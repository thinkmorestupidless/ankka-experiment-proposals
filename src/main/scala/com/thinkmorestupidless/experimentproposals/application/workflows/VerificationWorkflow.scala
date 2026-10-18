package com.thinkmorestupidless.experimentproposals.application.workflows

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.application.{Extraction, StageCall, StageOutcome}
import com.thinkmorestupidless.experimentproposals.application.agents.{
  EvidenceAgent,
  ReextractRequest,
  VerifyRequest
}
import com.thinkmorestupidless.experimentproposals.application.entities.{
  RecordCompanion,
  Records,
  SourceEntity
}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.sources.Section
import com.thinkmorestupidless.experimentproposals.rules.{Deterministic, Rules, Verification}
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

final case class VerificationState(
    recordId: String = "",
    attempt: Int = 0,
    runId: String = "",
    status: String = "not-started",
    outcome: Option[String] = None,
    defect: Option[String] = None
):
  def started: Boolean = status != "not-started"

final case class StartVerification(recordId: String, attempt: Int)

private given JsonValueCodec[RecordContent] = Codecs.make[RecordContent]

/**
 * Validation, then verification against the source, then the decision (plan: component table). A
 * deterministic failure quarantines before any model call. A retryable defect within the budget
 * re-extracts the record from the text and proposes it again, which starts the next attempt.
 */
final class VerificationWorkflow(context: WorkflowContext) extends Workflow[VerificationState]:

  private val client  = context.componentClient
  private val timeout = 3.minutes

  def emptyState: VerificationState = VerificationState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(timeout + 30.seconds).build

  private def stages = StageCall(client, currentState.runId, "verification")
  private def companion: RecordCompanion =
    Records
      .companionFor(currentState.recordId)
      .getOrElse(throw IllegalStateException(s"not a record id: ${currentState.recordId}"))
  private def recordEntity = client.forEventSourcedEntity(EntityId(currentState.recordId))
  private def record       = recordEntity.call(companion.get).invoke()
  private def recordRef(r: EvidenceRecord) =
    Ref(if r.kind == "finding" then RecordKind.finding else RecordKind.claim, r.id, r.version)

  def start(cmd: StartVerification): Effect[VerificationState] =
    if currentState.started then effects.reply(currentState)
    else
      effects
        .updateState(
          VerificationState(
            cmd.recordId,
            cmd.attempt,
            s"run-${cmd.recordId}:a${cmd.attempt}",
            "validating"
          )
        )
        .transitionTo(VerificationWorkflow.validate.ref)
        .thenReplyState

  def status: ReadOnlyEffect[VerificationState] = effects.reply(currentState)

  private def sectionsFor(r: EvidenceRecord): Vector[Section] =
    val sourceId = r.sourceRef.map(_.id).getOrElse("")
    val version  = r.sourceRef.map(_.version).getOrElse(1)
    val doc      = client.forEventSourcedEntity(EntityId(sourceId)).call(SourceEntity.get).invoke()
    val all      = doc.version(version).map(_.sections).getOrElse(Vector.empty)
    val cited    = r.spans.map(_.sectionId).distinct
    val related  = all.filter(s => cited.contains(s.id)).flatMap(_.relatedSectionIds)
    val wanted   = (cited ++ related).distinct
    all.filter(s => wanted.contains(s.id))

  def validateStep: StepEffect =
    val r = record
    if r.attempt != currentState.attempt || r.status != RecordStatus.proposed then
      stepEffects
        .updateState(
          currentState.copy(status = "superseded", outcome = Some("another attempt is current"))
        )
        .thenEnd
    else
      val sourceId = r.sourceRef.map(_.id).getOrElse("")
      val source   = client.forEventSourcedEntity(EntityId(sourceId))
      val doc      = source.call(SourceEntity.get).invoke()
      val exists   = r.sourceRef.exists(ref => doc.version(ref.version).isDefined)
      val result = stages.plain(
        "validate",
        1,
        Vector(recordRef(r)),
        StageCall.hash(r.id, r.attempt.toString, Deterministic.ruleSet.key)
      ) { _ =>
        val v = Deterministic.validate(
          r,
          span => source.call(SourceEntity.resolveSpan).invoke(span),
          exists
        )
        recordEntity.call(companion.recordValidation).invoke(RecordValidation(r.attempt, v))
        (v, Some(recordRef(r)))
      }
      result match
        case Left(_) =>
          stepEffects
            .updateState(currentState.copy(status = "verifying"))
            .thenTransitionTo(VerificationWorkflow.verify.ref)
        case Right(v) if v.passed =>
          stepEffects
            .updateState(currentState.copy(status = "verifying"))
            .thenTransitionTo(VerificationWorkflow.verify.ref)
        case Right(v) =>
          val defect = v.defects.headOption.getOrElse(
            Rules.defect(DefectKind.other, "validation failed", retryable = false)
          )
          recordEntity.call(companion.quarantine).invoke(QuarantineRecord(r.attempt, defect))
          stepEffects
            .updateState(
              currentState.copy(
                status = "quarantined",
                outcome = Some("quarantined"),
                defect = Some(defect.kind.toString)
              )
            )
            .thenEnd

  def verifyStep: StepEffect =
    val r        = record
    val stage    = "verify"
    val prompt   = EvidenceAgent.verifyPrompt
    val sections = sectionsFor(r)
    val rendered = r.content.map(c => writeToString(c)).getOrElse("") + "\nspans: " + r.spans
      .map(s => s"${s.sectionId} '${s.quotation}'")
      .mkString("; ")
    val marker = EvidenceAgent.verifyMarker(
      r.id,
      r.content.map(c => EvidenceAgent.fingerprint(c, r.spans)).getOrElse("none")
    )
    val outcome = stages.model(
      stage,
      1,
      Vector(recordRef(r)),
      StageCall.hash(r.id, r.attempt.toString, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(EvidenceAgent.verify)
        .invokeAsync(VerifyRequest(r.id, marker, rendered, sections))
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        stepEffects.updateState(currentState.copy(status = "decided")).thenEnd
      case StageOutcome.Done(attempt, reply, modelCall) =>
        Verification.parse(
          reply,
          r.attempt,
          sections.map(_.id),
          prompt.promptVersion,
          Configured.modelName
        ) match
          case Left(defect) =>
            stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
            recordEntity
              .call(companion.requiresReview)
              .invoke(
                RecordRequiresReview(
                  r.attempt,
                  s"the verifier's reply could not be read: ${defect.detail}",
                  None
                )
              )
            stepEffects
              .updateState(
                currentState.copy(status = "requires-review", outcome = Some("requires_review"))
              )
              .thenEnd
          case Right(verification) =>
            stages.complete(stage, attempt, Some(recordRef(r)), modelCalls = Vector(modelCall))
            Verification.decide(r, verification) match
              case Verification.Decision.Verify(v) =>
                recordEntity.call(companion.verified).invoke(RecordVerified(r.attempt, v))
                stepEffects
                  .updateState(currentState.copy(status = "verified", outcome = Some("verified")))
                  .thenEnd
              case Verification.Decision.Review(v, reason) =>
                recordEntity
                  .call(companion.requiresReview)
                  .invoke(RecordRequiresReview(r.attempt, reason, Some(v)))
                stepEffects
                  .updateState(
                    currentState.copy(status = "requires-review", outcome = Some("requires_review"))
                  )
                  .thenEnd
              case Verification.Decision.Reject(v, defect) =>
                recordEntity
                  .call(companion.rejected)
                  .invoke(RecordRejected(r.attempt, defect, Some(v)))
                if defect.retryable && r.attempts.size <= Configured.retryBudget then
                  stepEffects
                    .updateState(
                      currentState.copy(
                        status = "retrying",
                        outcome = Some("rejected"),
                        defect = Some(defect.kind.toString)
                      )
                    )
                    .thenTransitionTo(VerificationWorkflow.retry.ref)
                else
                  stepEffects
                    .updateState(
                      currentState.copy(
                        status = "rejected",
                        outcome = Some("rejected"),
                        defect = Some(defect.kind.toString)
                      )
                    )
                    .thenEnd
      case StageOutcome.Failed(_, defect, _) =>
        recordEntity
          .call(companion.requiresReview)
          .invoke(
            RecordRequiresReview(
              r.attempt,
              s"the verifier could not be run: ${defect.detail}",
              None
            )
          )
        stepEffects
          .updateState(
            currentState.copy(
              status = "requires-review",
              outcome = Some("requires_review"),
              defect = Some(defect.kind.toString)
            )
          )
          .thenEnd

  /**
   * Every section of the version, keyed as the first extraction keyed them, so quotations locate
   * the same way.
   */
  private def allSectionsFor(r: EvidenceRecord): Vector[Section] =
    val doc = client
      .forEventSourcedEntity(EntityId(r.sourceRef.map(_.id).getOrElse("")))
      .call(SourceEntity.get)
      .invoke()
    doc.version(r.sourceRef.map(_.version).getOrElse(1)).map(_.sections).getOrElse(Vector.empty)

  /** A classified, retryable defect: extract the record again from the text and propose it anew. */
  def retryStep: StepEffect =
    val r        = record
    val stage    = "reextract"
    val prompt   = EvidenceAgent.reextractPrompt
    val sections = allSectionsFor(r)
    val defect   = r.defect.getOrElse(Rules.defect(DefectKind.other, "unknown", retryable = true))
    val next     = r.attempts.size + 1
    val marker = EvidenceAgent.reextractMarker(
      r.id,
      r.content.map(c => EvidenceAgent.fingerprint(c, r.spans)).getOrElse("none"),
      defect.kind.toString
    )
    val outcome = stages.model(
      stage,
      1,
      Vector(recordRef(r)),
      StageCall.hash(r.id, next.toString, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(EvidenceAgent.reextract)
        .invokeAsync(
          ReextractRequest(
            r.id,
            marker,
            r.content.map(c => writeToString(c)).getOrElse(""),
            s"${defect.kind}: ${defect.detail}",
            sections
          )
        )
    }
    val sourceId = r.sourceRef.map(_.id).getOrElse("")
    val version  = r.sourceRef.map(_.version).getOrElse(1)
    outcome match
      case StageOutcome.Done(attempt, set, modelCall) =>
        Extraction.prepareOne(
          sourceId,
          version,
          sections,
          set,
          r
        ) match
          case Right((content, spans)) =>
            stages.complete(stage, attempt, Some(recordRef(r)), modelCalls = Vector(modelCall))
            try
              recordEntity
                .call(companion.repropose)
                .invoke(ReproposeRecord(content, spans, defect, s"model:${prompt.promptVersion}"))
            catch
              case e: CommandError if e.code == ErrorCode.Conflict =>
                recordEntity.call(companion.quarantine).invoke(QuarantineRecord(r.attempt, defect))
            stepEffects.updateState(currentState.copy(status = "reproposed")).thenEnd
          case Left(problem) =>
            stages.fail(stage, attempt, problem, modelCalls = Vector(modelCall))
            recordEntity.call(companion.quarantine).invoke(QuarantineRecord(r.attempt, problem))
            stepEffects
              .updateState(
                currentState.copy(
                  status = "quarantined",
                  outcome = Some("quarantined"),
                  defect = Some(problem.kind.toString)
                )
              )
              .thenEnd
      case StageOutcome.Skipped(_) =>
        stepEffects.updateState(currentState.copy(status = "reproposed")).thenEnd
      case StageOutcome.Failed(_, problem, _) =>
        recordEntity.call(companion.quarantine).invoke(QuarantineRecord(r.attempt, problem))
        stepEffects
          .updateState(
            currentState.copy(
              status = "quarantined",
              outcome = Some("quarantined"),
              defect = Some(problem.kind.toString)
            )
          )
          .thenEnd

object VerificationWorkflow
    extends Workflow.Companion[VerificationWorkflow, VerificationState](
      componentId = ComponentId("verification"),
      stateSerializer = Codecs.serializer[VerificationState]("verification-state")
    ):

  given Serializer[StartVerification] = Codecs.serializer[StartVerification]("start-verification")

  def idFor(recordId: String, attempt: Int): String = s"$recordId:a$attempt"

  def create(context: WorkflowContext) = new VerificationWorkflow(context)

  val validate = step("validate")(_.validateStep)
  val verify   = step("verify")(_.verifyStep)
  val retry    = step("retry")(_.retryStep)
  val start    = command("start")(_.start)
  val status   = query("status")(_.status)
