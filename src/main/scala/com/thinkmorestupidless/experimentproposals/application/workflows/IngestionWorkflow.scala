package com.thinkmorestupidless.experimentproposals.application.workflows

import com.thinkmorestupidless.experimentproposals.adapters.jats.JatsParser
import com.thinkmorestupidless.experimentproposals.application.{Extraction, StageCall, StageOutcome}
import com.thinkmorestupidless.experimentproposals.application.agents.{
  EvidenceAgent,
  ExtractRequest
}
import com.thinkmorestupidless.experimentproposals.application.entities.Records
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.experimentproposals.application.entities.SourceEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceCodecs.given
import com.thinkmorestupidless.experimentproposals.retrieval.Chunker
import com.thinkmorestupidless.experimentproposals.rules.EligibilityRules
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

/** The process's memory: what it was asked, where it has got to, and what it recorded. */
final case class IngestionState(
    adapter: String = "",
    identifier: String = "",
    extract: Boolean = false,
    questionIds: Vector[String] = Vector.empty,
    runId: String = "",
    sourceId: Option[String] = None,
    format: Option[String] = None,
    version: Option[Int] = None,
    duplicateOf: Option[Int] = None,
    measurements: Vector[Measurement] = Vector.empty,
    adapterIssues: Vector[ParseIssue] = Vector.empty,
    status: String = "not-started",
    failure: Option[String] = None,
    recordIds: Vector[String] = Vector.empty,
    attempts: Map[String, Int] = Map.empty,
    polls: Int = 0
):
  def started: Boolean = status != "not-started"

final case class ExtractFromSource(questionIds: Vector[String] = Vector.empty)

final case class StartIngestion(
    adapter: String,
    identifier: String,
    extract: Boolean = false,
    questionIds: Vector[String] = Vector.empty
)

/**
 * Fetch, record, parse, chunk, record measurements, judge eligibility (contracts/stages.md). Each
 * step reads the `source` entity before writing and goes through the run's `stage-run`, so a step
 * run twice does no second work; `fetch` is the one step that may fail for reasons outside the
 * service, and is retried twice before `record-failure` records the failure and pauses.
 */
final class IngestionWorkflow(context: WorkflowContext) extends Workflow[IngestionState]:

  private val client = context.componentClient

  def emptyState: IngestionState = IngestionState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(2.minutes)
      .stepRecovery(
        IngestionWorkflow.fetch,
        RecoverStrategy.maxRetries(2).failoverTo(IngestionWorkflow.recordFailure)
      )
      .build

  private def stages             = StageCall(client, currentState.runId, "ingestion")
  private def source(id: String) = client.forEventSourcedEntity(EntityId(id))
  private def sourceRef(version: Int) =
    Ref(RecordKind.source, currentState.sourceId.getOrElse(""), version)

  def start(request: StartIngestion): Effect[IngestionState] =
    if currentState.started then effects.reply(currentState)
    else if request.adapter.isBlank || request.identifier.isBlank then
      effects.error("an adapter and an identifier are required")
    else
      Configured.adapters(request.adapter) match
        case Left(problem) => effects.error(problem)
        case Right(_) =>
          effects
            .updateState(
              IngestionState(
                adapter = request.adapter,
                identifier = request.identifier,
                extract = request.extract,
                questionIds = request.questionIds,
                runId = s"run-${IngestionWorkflow.idFor(request.adapter, request.identifier)}",
                status = "fetching"
              )
            )
            .transitionTo(IngestionWorkflow.fetch.ref)
            .thenReplyState

  def status: ReadOnlyEffect[IngestionState] = effects.reply(currentState)

  def fetchStep: StepEffect =
    val adapter =
      Configured.adapters(currentState.adapter).fold(p => throw IllegalStateException(p), identity)
    val record = adapter.fetch(currentState.identifier)
    stepEffects
      .updateState(
        currentState.copy(
          status = "fetched",
          sourceId = Some(record.sourceId),
          format = Some(record.format),
          measurements = record.measurements,
          adapterIssues = record.issues
        )
      )
      .thenTransitionTo(IngestionWorkflow.record.withInput(record))

  def recordStep(record: SourceRecord): StepEffect =
    val s = source(record.sourceId)
    s.call(SourceEntity.register).invoke(record.register)
    s.call(SourceEntity.noteIngestion).invoke(context.workflowId.toString)
    val outcome = s
      .call(SourceEntity.recordContent)
      .invoke(
        FetchedContent(
          record.contentHash,
          java.time.Instant.now(),
          record.sourceVersion,
          record.retention,
          record.content
        )
      )
    outcome match
      case ContentOutcome.Duplicate(v) =>
        stages.plain(
          "record",
          1,
          Vector.empty,
          StageCall.hash(record.sourceId, record.contentHash)
        ) { _ =>
          ((), Some(sourceRef(v)))
        }
        stepEffects
          .updateState(
            currentState.copy(status = "duplicate", duplicateOf = Some(v), version = Some(v))
          )
          .thenEnd
      case ContentOutcome.Recorded(v) =>
        stages.plain(
          "record",
          1,
          Vector.empty,
          StageCall.hash(record.sourceId, record.contentHash)
        ) { _ =>
          ((), Some(sourceRef(v)))
        }
        stepEffects
          .updateState(currentState.copy(status = "recorded", version = Some(v)))
          .thenTransitionTo(IngestionWorkflow.parse.ref)

  def parseStep: StepEffect =
    val (id, version) = (currentState.sourceId.get, currentState.version.get)
    val doc           = source(id).call(SourceEntity.get).invoke()
    val v =
      doc.version(version).getOrElse(throw IllegalStateException(s"no version $version of $id"))
    stages.plain(
      "parse",
      1,
      Vector(sourceRef(version)),
      StageCall.hash(id, v.contentHash, JatsParser.Version)
    ) { _ =>
      val fromContent = (v.content, currentState.format) match
        case (Some(text), Some("jats")) => JatsParser.parse(text)
        case (Some(text), _)            => JatsParser.plainText(text)
        case (None, _) =>
          com.thinkmorestupidless.experimentproposals.adapters.jats.ParsedDocument(
            doc.abstractText.toVector.map(a =>
              Section("sec-1", None, 1, SectionKind.abstract_, "Abstract", a)
            ),
            Vector(
              ParseIssue(
                "abstract_only",
                "content",
                "the full text could not be fetched; the abstract is retained"
              )
            ),
            ParseCompleteness.partial,
            None,
            doc.abstractText
          )
      // What the adapter could not read reliably counts against completeness too (FR-012).
      val parsed =
        if currentState.adapterIssues.isEmpty then fromContent
        else
          fromContent.copy(
            issues = fromContent.issues ++ currentState.adapterIssues,
            completeness =
              if fromContent.completeness == ParseCompleteness.complete then
                ParseCompleteness.partial
              else fromContent.completeness
          )
      source(id)
        .call(SourceEntity.recordParse)
        .invoke(
          RecordParse(
            version,
            JatsParser.Version,
            parsed.sections,
            parsed.issues,
            parsed.completeness
          )
        )
      if parsed.completeness == ParseCompleteness.failed then
        source(id)
          .call(SourceEntity.quarantine)
          .invoke(
            QuarantineSource(
              version,
              "the content could not be parsed: " + parsed.issues.map(_.detail).mkString("; ")
            )
          )
      ((), Some(sourceRef(version)))
    }
    stepEffects
      .updateState(currentState.copy(status = "parsed"))
      .thenTransitionTo(IngestionWorkflow.chunk.ref)

  def chunkStep: StepEffect =
    val (id, version) = (currentState.sourceId.get, currentState.version.get)
    val embedder      = Configured.embedder
    stages.plain(
      "chunk",
      1,
      Vector(sourceRef(version)),
      StageCall.hash(id, version.toString, embedder.model, embedder.revision)
    ) { _ =>
      val doc      = source(id).call(SourceEntity.get).invoke()
      val sections = doc.version(version).map(_.sections).getOrElse(Vector.empty)
      val chunks   = Chunker.chunkAll(id, version, sections, embedder.embed)
      val batches  = Chunker.batches(chunks, Chunker.Settings().batchSize)
      batches.zipWithIndex.foreach { case (batch, i) =>
        source(id)
          .call(SourceEntity.recordChunks)
          .invoke(
            RecordChunks(version, embedder.model, embedder.revision, batch, i + 1, batches.size)
          )
      }
      ((), Some(sourceRef(version)))
    }
    stepEffects
      .updateState(currentState.copy(status = "chunked"))
      .thenTransitionTo(IngestionWorkflow.measurements.ref)

  def measurementsStep: StepEffect =
    val (id, version) = (currentState.sourceId.get, currentState.version.get)
    if currentState.measurements.nonEmpty then
      stages.plain(
        "measurements",
        1,
        Vector(sourceRef(version)),
        StageCall.hash(id, version.toString, "measurements")
      ) { _ =>
        source(id)
          .call(SourceEntity.recordMeasurements)
          .invoke(RecordMeasurements(version, currentState.measurements))
        ((), Some(sourceRef(version)))
      }
    stepEffects
      .updateState(currentState.copy(status = "measured"))
      .thenTransitionTo(IngestionWorkflow.eligibility.ref)

  def eligibilityStep: StepEffect =
    val (id, version) = (currentState.sourceId.get, currentState.version.get)
    stages.plain(
      "eligibility",
      1,
      Vector(sourceRef(version)),
      StageCall.hash(id, version.toString, EligibilityRules.ruleSet.key)
    ) { _ =>
      val doc = source(id).call(SourceEntity.get).invoke()
      source(id)
        .call(SourceEntity.assessEligibility)
        .invoke(EligibilityRules.assess(doc, version, "system:ingestion"))
      ((), Some(sourceRef(version)))
    }
    if currentState.extract then
      stepEffects
        .updateState(currentState.copy(status = "extracting"))
        .thenTransitionTo(IngestionWorkflow.extract.ref)
    else stepEffects.updateState(currentState.copy(status = "ingested")).thenEnd

  /** Runs extraction on an ingested source: the API's `sources/{id}:extract`. */
  def extractCommand(cmd: ExtractFromSource): Effect[IngestionState] =
    if currentState.version.isEmpty then
      effects.error("the source has no recorded version to extract from", ErrorCode.Conflict)
    else
      effects
        .updateState(
          currentState.copy(extract = true, questionIds = cmd.questionIds, status = "extracting")
        )
        .transitionTo(IngestionWorkflow.extract.ref)
        .thenReplyState

  def extractStep: StepEffect =
    val stage         = "extract"
    val prompt        = EvidenceAgent.extractPrompt
    val (id, version) = (currentState.sourceId.get, currentState.version.get)
    val doc           = source(id).call(SourceEntity.get).invoke()
    val v =
      doc.version(version).getOrElse(throw IllegalStateException(s"no version $version of $id"))
    v.extractions.find(_.promptVersion == prompt.promptVersion) match
      case Some(done) =>
        stepEffects
          .updateState(
            currentState
              .copy(recordIds = done.recordIds, status = "awaiting-verification", polls = 0)
          )
          .thenTransitionTo(IngestionWorkflow.awaitVerification.ref)
      case None if v.quarantined || v.sections.isEmpty =>
        stepEffects
          .updateState(
            currentState.copy(
              status = "ingested",
              failure = Some("nothing to extract: the version is quarantined or has no sections")
            )
          )
          .thenEnd
      case None =>
        val outcome = stages.model(
          stage,
          1,
          Vector(sourceRef(version)),
          StageCall.hash(id, version.toString, prompt.promptVersion),
          prompt.promptVersion,
          Configured.modelName,
          "high",
          3.minutes
        ) {
          client
            .forAgent(SessionId(EvidenceAgent.extractMarker(id, version)))
            .call(EvidenceAgent.extract)
            .invokeAsync(ExtractRequest(id, version, v.sections, currentState.questionIds))
        }
        outcome match
          case StageOutcome.Skipped(_) =>
            stepEffects
              .updateState(currentState.copy(status = "awaiting-verification", polls = 0))
              .thenTransitionTo(IngestionWorkflow.awaitVerification.ref)
          case StageOutcome.Done(attempt, set, modelCall) =>
            Extraction.prepare(id, version, v.sections, set, s"model:${prompt.promptVersion}") match
              case Left(defect) =>
                stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
                retryExtraction(defect)
              case Right(prepared) =>
                prepared.foreach { p =>
                  val companion = Records.companionFor(p.id).get
                  try
                    client
                      .forEventSourcedEntity(EntityId(p.id))
                      .call(companion.propose)
                      .invoke(p.request)
                  catch case e: CommandError if e.code == ErrorCode.Conflict => ()
                }
                source(id)
                  .call(SourceEntity.recordExtraction)
                  .invoke(RecordExtraction(version, prompt.promptVersion, prepared.map(_.id)))
                stages.complete(
                  stage,
                  attempt,
                  prepared.headOption.map(p => Ref(RecordKind.finding, p.id, 1)),
                  modelCalls = Vector(modelCall)
                )
                stepEffects
                  .updateState(
                    currentState.copy(
                      recordIds = prepared.map(_.id),
                      status = "awaiting-verification",
                      polls = 0
                    )
                  )
                  .thenTransitionTo(IngestionWorkflow.awaitVerification.ref)
          case StageOutcome.Failed(_, defect, _) => retryExtraction(defect)

  private def retryExtraction(
      defect: com.thinkmorestupidless.experimentproposals.domain.provenance.Defect
  ): StepEffect =
    val tried = currentState.attempts.getOrElse("extract", 1)
    if defect.retryable && tried < Configured.retryBudget + 1 then
      stepEffects
        .updateState(
          currentState.copy(attempts = currentState.attempts + ("extract" -> (tried + 1)))
        )
        .thenTransitionTo(IngestionWorkflow.extract.ref)
    else
      stepEffects
        .updateState(
          currentState.copy(
            status = "extraction-failed",
            failure = Some(s"extract: ${defect.kind} (${defect.detail})")
          )
        )
        .thenEnd

  /** Waits, in bounded pauses, until every record of the version has left `proposed`. */
  def awaitVerificationStep: StepEffect =
    val pending = currentState.recordIds.filter { rid =>
      val companion = Records.companionFor(rid).get
      client
        .forEventSourcedEntity(EntityId(rid))
        .call(companion.get)
        .invoke()
        .status == RecordStatus.proposed
    }
    if pending.isEmpty then stepEffects.updateState(currentState.copy(status = "ingested")).thenEnd
    else if currentState.polls >= 60 then
      stepEffects
        .updateState(
          currentState.copy(
            status = "ingested",
            failure = Some(s"${pending.size} record(s) still proposed after waiting")
          )
        )
        .thenEnd
    else
      stepEffects
        .updateState(currentState.copy(polls = currentState.polls + 1))
        .thenPause(5.seconds, IngestionWorkflow.awaitVerification.ref)

  /**
   * The failover of `fetch`: the failure is recorded in the state and the workflow waits for the
   * operator.
   */
  def recordFailureStep: StepEffect =
    stepEffects
      .updateState(
        currentState.copy(
          status = "failed",
          failure = Some(
            s"fetching ${currentState.identifier} through ${currentState.adapter} failed after retries"
          )
        )
      )
      .thenPause()

object IngestionWorkflow
    extends Workflow.Companion[IngestionWorkflow, IngestionState](
      componentId = ComponentId("ingestion"),
      stateSerializer = Codecs.serializer[IngestionState]("ingestion-state")
    ):

  given Serializer[StartIngestion] = Codecs.serializer[StartIngestion]("start-ingestion")

  def idFor(adapter: String, identifier: String): String =
    s"ingest:$adapter:${identifier.replace('/', '~')}"

  def create(context: WorkflowContext) = new IngestionWorkflow(context)

  val fetch             = step("fetch")(_.fetchStep)
  val record            = step("record")(_.recordStep)
  val parse             = step("parse")(_.parseStep)
  val chunk             = step("chunk")(_.chunkStep)
  val measurements      = step("measurements")(_.measurementsStep)
  val eligibility       = step("eligibility")(_.eligibilityStep)
  val recordFailure     = step("record-failure")(_.recordFailureStep)
  val extract           = step("extract")(_.extractStep)
  val awaitVerification = step("await-verification")(_.awaitVerificationStep)

  given Serializer[ExtractFromSource] = Codecs.serializer[ExtractFromSource]("extract-from-source")

  val start          = command("start")(_.start)
  val extractCommand = command("extract")(_.extractCommand)
  val status         = query("status")(_.status)
