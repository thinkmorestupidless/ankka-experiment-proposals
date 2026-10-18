package com.thinkmorestupidless.experimentproposals.application.workflows

import com.thinkmorestupidless.experimentproposals.application.{StageCall, StageOutcome}
import com.thinkmorestupidless.experimentproposals.application.agents.{
  ApproachForModel,
  AssessmentLine,
  CandidatesRequest,
  ClaimLine,
  DesignRequest,
  RejectedLine,
  DeriveQuestions,
  DesignAgent,
  ExtractFacets,
  FacetLine,
  PackageForModel,
  QuestionLine,
  RequestAgent,
  RoundTripRequest
}
import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  AssessmentEntity,
  DecisionEntity,
  DesignEntity,
  FacetEntity,
  GapEntity,
  PackageEntity,
  QuestionEntity,
  Records,
  RequestEntity,
  SourceEntity
}
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.{EvidenceRecord, RecordContent}
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.experimentproposals.domain.sources.{SourceSpan, SpanResolution}
import com.thinkmorestupidless.experimentproposals.rules.{DesignRules, PackageRules}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, Ref}
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.rules.{FacetRules, QuestionRules, Rules}
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

final case class InvestigationState(
    requestId: String = "",
    text: String = "",
    runId: String = "",
    facetIds: Vector[String] = Vector.empty,
    questionIds: Vector[String] = Vector.empty,
    attempts: Map[String, Int] = Map.empty,
    status: String = "not-started",
    failure: Option[String] = None,
    confirmedBy: Option[String] = None,
    packageIds: Vector[String] = Vector.empty,
    approachIds: Vector[String] = Vector.empty,
    comparison: Option[Comparison] = None,
    decisionId: Option[String] = None,
    packagedBy: Option[String] = None,
    designVersions: Int = 0,
    requestedChanges: Vector[String] = Vector.empty,
    lastApproval: Option[ApprovalOutcome] = None
):
  def started: Boolean = status != "not-started"

final case class StartInvestigation(requestId: String, text: String)
final case class ApprovalOutcome(
    version: Int,
    decision: ApprovalDecision,
    requestedChanges: Vector[String] = Vector.empty
)

/**
 * From a request to a design, pausing where a reviewer decides (plan: component table). This
 * story's steps: extract the facets, run the round-trip check, wait for the reviewer. Every model
 * step goes through `StageCall`; a classified, retryable defect is retried within the budget and
 * then recorded as the run's failure, with the workflow paused for the operator.
 */
final class InvestigationWorkflow(context: WorkflowContext) extends Workflow[InvestigationState]:

  private val client  = context.componentClient
  private val timeout = 3.minutes

  def emptyState: InvestigationState = InvestigationState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(timeout + 30.seconds)
      .stepTimeout(InvestigationWorkflow.investigateQuestions, 31.minutes)
      .build

  private def stages            = StageCall(client, currentState.runId, "investigation")
  private def request           = client.forEventSourcedEntity(EntityId(currentState.requestId))
  private def facet(id: String) = client.forEventSourcedEntity(EntityId(id))
  private def requestRef        = Ref(RecordKind.request, currentState.requestId, 1)

  def start(cmd: StartInvestigation): Effect[InvestigationState] =
    if currentState.started then effects.reply(currentState)
    else
      effects
        .updateState(
          InvestigationState(
            cmd.requestId,
            cmd.text,
            runId = s"run-${cmd.requestId}",
            status = "extracting-facets"
          )
        )
        .transitionTo(InvestigationWorkflow.extractFacets.ref)
        .thenReplyState

  def facetsConfirmed(by: String): Effect[InvestigationState] =
    if !currentState.started then
      effects.error("the investigation has not started", ErrorCode.NotFound)
    else if currentState.confirmedBy.isDefined then effects.reply(currentState)
    else
      effects
        .updateState(currentState.copy(confirmedBy = Some(by), status = "deriving-questions"))
        .transitionTo(InvestigationWorkflow.deriveQuestions.ref)
        .thenReplyState

  def rerunRoundTrip: Effect[InvestigationState] =
    if !currentState.started then
      effects.error("the investigation has not started", ErrorCode.NotFound)
    else
      effects
        .updateState(currentState.copy(status = "round-trip"))
        .transitionTo(InvestigationWorkflow.roundTrip.ref)
        .thenReplyState

  /** The evidence is judged enough: build the packages and propose the candidates. */
  def packageEvidence(by: String): Effect[InvestigationState] =
    if !currentState.started then
      effects.error("the investigation has not started", ErrorCode.NotFound)
    else if currentState.questionIds.isEmpty then
      effects.error("the questions are not derived yet", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(status = "building-packages", packagedBy = Some(by)))
        .transitionTo(InvestigationWorkflow.buildPackages.ref)
        .thenReplyState

  /** A reviewer decided (recorded on the `decision` entity first): on to the design (US8). */
  def decided(decisionId: String): Effect[InvestigationState] =
    if !currentState.started then
      effects.error("the investigation has not started", ErrorCode.NotFound)
    else if currentState.decisionId.contains(decisionId) then effects.reply(currentState)
    else if currentState.comparison.isEmpty then
      effects.error("no candidates have been compared yet", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(decisionId = Some(decisionId), status = "designing"))
        .transitionTo(InvestigationWorkflow.design.ref)
        .thenReplyState

  /** A reviewer decided on a design version (recorded on the `design` entity first). */
  def approvalRecorded(outcome: ApprovalOutcome): Effect[InvestigationState] =
    if !currentState.started then
      effects.error("the investigation has not started", ErrorCode.NotFound)
    else if currentState.lastApproval.contains(outcome) then effects.reply(currentState)
    else
      effects
        .updateState(currentState.copy(lastApproval = Some(outcome), status = "closing"))
        .transitionTo(InvestigationWorkflow.close.ref)
        .thenReplyState

  def status: ReadOnlyEffect[InvestigationState] = effects.reply(currentState)

  // ── steps ────────────────────────────────────────────────────────────────────

  def extractFacetsStep: StepEffect =
    val stage  = "extract-facets"
    val prompt = RequestAgent.facetsPrompt
    val text   = currentState.text
    val outcome = stages.model(
      stage,
      1,
      Vector(requestRef),
      StageCall.hash(text, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(FacetRules.marker(text)))
        .call(RequestAgent.extractFacets)
        .invokeAsync(ExtractFacets(currentState.requestId, text))
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        val existing = request.call(RequestEntity.get).invoke().facets.map(_.id)
        stepEffects
          .updateState(currentState.copy(facetIds = existing, status = "round-trip"))
          .thenTransitionTo(InvestigationWorkflow.roundTrip.ref)
      case StageOutcome.Done(attempt, set, modelCall) =>
        FacetRules.gate(currentState.requestId, text, set) match
          case Left(defect) =>
            stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
            retryOrFail(stage, defect, InvestigationWorkflow.extractFacets.ref)
          case Right(proposals) =>
            val refs = proposals.zipWithIndex.map { case (p, i) =>
              val id = FacetEntity.idFor(currentState.requestId, i + 1)
              try facet(id).call(FacetEntity.propose).invoke(p)
              catch
                case e: CommandError if e.code == ErrorCode.Conflict => () // a repeat of this step
              FacetRef(id, p.name, p.importance)
            }
            request.call(RequestEntity.recordFacetSet).invoke(RecordFacetSet(refs))
            stages.complete(
              stage,
              attempt,
              Some(Ref(RecordKind.facet, refs.head.id, 1)),
              modelCalls = Vector(modelCall)
            )
            stepEffects
              .updateState(currentState.copy(facetIds = refs.map(_.id), status = "round-trip"))
              .thenTransitionTo(InvestigationWorkflow.roundTrip.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrFail(stage, defect, InvestigationWorkflow.extractFacets.ref)

  def roundTripStep: StepEffect =
    val stage  = "round-trip"
    val prompt = RequestAgent.roundTripPrompt
    val text   = currentState.text
    val facets = currentState.facetIds
      .map(id => facet(id).call(FacetEntity.get).invoke())
      .filter(_.status != FacetStatus.rejected)
    val lines =
      facets.map(f => FacetLine(f.name, f.statement, f.origin.toString, f.status.toString))
    val marker = FacetRules.roundTripMarker(text, lines.map(l => l.name -> l.statement))
    val outcome = stages.model(
      stage,
      1,
      facets.map(f => Ref(RecordKind.facet, f.id, f.version)),
      StageCall.hash(marker, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(RequestAgent.roundTrip)
        .invokeAsync(RoundTripRequest(currentState.requestId, text, lines))
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        stepEffects
          .updateState(currentState.copy(status = "awaiting-facet-review"))
          .thenTransitionTo(InvestigationWorkflow.awaitFacetReview.ref)
      case StageOutcome.Done(attempt, check, modelCall) =>
        request
          .call(RequestEntity.recordRoundTrip)
          .invoke(RecordRoundTrip(check, prompt.promptVersion))
        stages.complete(stage, attempt, Some(requestRef), modelCalls = Vector(modelCall))
        stepEffects
          .updateState(currentState.copy(status = "awaiting-facet-review"))
          .thenTransitionTo(InvestigationWorkflow.awaitFacetReview.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrFail(stage, defect, InvestigationWorkflow.roundTrip.ref)

  /** A reviewer decides; nothing is promoted on a timeout (edge case: an unavailable reviewer). */
  def awaitFacetReviewStep: StepEffect = stepEffects.thenPause()

  def deriveQuestionsStep: StepEffect =
    val stage  = "derive-questions"
    val prompt = RequestAgent.questionsPrompt
    val text   = currentState.text
    val facets = currentState.facetIds.map(id => facet(id).call(FacetEntity.get).invoke())
    val live   = facets.filter(_.status != FacetStatus.rejected)
    val marker = QuestionRules.marker(text, facets)
    val lines =
      live.map(f => QuestionLine(f.name, f.statement, f.importance.toString, f.status.toString))
    val outcome = stages.model(
      stage,
      1,
      live.map(f => Ref(RecordKind.facet, f.id, f.version)),
      StageCall.hash(marker, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(RequestAgent.deriveQuestions)
        .invokeAsync(DeriveQuestions(currentState.requestId, text, marker, lines))
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        val existing = request.call(RequestEntity.get).invoke().questionIds
        stepEffects
          .updateState(
            currentState.copy(questionIds = existing, status = "investigating-questions")
          )
          .thenTransitionTo(InvestigationWorkflow.investigateQuestions.ref)
      case StageOutcome.Done(attempt, set, modelCall) =>
        QuestionRules.gate(currentState.requestId, facets, set) match
          case Left(defect) =>
            stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
            retryOrFail(stage, defect, InvestigationWorkflow.deriveQuestions.ref)
          case Right(derived) =>
            val ids = derived.zipWithIndex.map { case (d, i) =>
              val id = QuestionEntity.idFor(currentState.requestId, i + 1)
              try client.forEventSourcedEntity(EntityId(id)).call(QuestionEntity.derive).invoke(d)
              catch case e: CommandError if e.code == ErrorCode.Conflict => ()
              id
            }
            request.call(RequestEntity.recordQuestions).invoke(RecordQuestions(ids))
            stages.complete(
              stage,
              attempt,
              Some(Ref(RecordKind.question, ids.head, 1)),
              modelCalls = Vector(modelCall)
            )
            stepEffects
              .updateState(currentState.copy(questionIds = ids, status = "investigating-questions"))
              .thenTransitionTo(InvestigationWorkflow.investigateQuestions.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrFail(stage, defect, InvestigationWorkflow.deriveQuestions.ref)

  /**
   * One investigation per question, started together and awaited; each is journalled on its own.
   */
  def investigateQuestionsStep: StepEffect =
    val pending = currentState.questionIds.map { qid =>
      client
        .forWorkflow(EntityId(qid))
        .call(QuestionInvestigationWorkflow.start)
        .invokeAsync(StartQuestionInvestigation(qid, currentState.requestId))
    }
    pending.foreach { f =>
      try ComponentClient.await(f, 60.seconds)
      catch case e: CommandError if e.code == ErrorCode.Conflict => ()
    }
    // Each question's investigation retrieves and waits; `package` moves the request on (US7).
    stepEffects.updateState(currentState.copy(status = "questions-investigating")).thenPause()

  // ── packages and candidates (US7) ────────────────────────────────────────────

  private def recordOf(id: String): Option[EvidenceRecord] =
    Records
      .companionFor(id)
      .map(c => client.forEventSourcedEntity(EntityId(id)).call(c.get).invoke())
      .filter(_.registered)

  private def summaryOf(r: EvidenceRecord): String = r.content match
    case Some(RecordContent.OfFinding(f)) =>
      s"${f.observedEndpoint}: ${f.reportedOutcome}" + f.species.fold("")(sp => s" ($sp)")
    case Some(RecordContent.OfClaim(c)) => c.proposition
    case None                           => ""

  /** One package per question from its assessments, through `package@v1` (FR-033, FR-034). */
  def buildPackagesStep: StepEffect =
    val facets    = currentState.facetIds.map(id => facet(id).call(FacetEntity.get).invoke())
    val facetRefs = facets.map(f => Ref(RecordKind.facet, f.id, f.version))
    val constraints = facets
      .filter(f => Set("resources", "time").contains(f.name))
      .map(f => s"${f.name}: ${f.statement}")
    val built = stages.plain(
      "build-packages",
      1,
      currentState.questionIds.map(Ref(RecordKind.question, _, 1)),
      StageCall.hash((currentState.questionIds :+ currentState.packageIds.size.toString)*)
    ) { _ =>
      val ids = currentState.questionIds.map { qid =>
        val qState =
          client.forWorkflow(EntityId(qid)).call(QuestionInvestigationWorkflow.status).invoke()
        val q = client.forEventSourcedEntity(EntityId(qid)).call(QuestionEntity.get).invoke()
        val assessments = qState.assessmentIds
          .map(id => client.forEventSourcedEntity(EntityId(id)).call(AssessmentEntity.get).invoke())
          .filter(_.registered)
        val records =
          assessments.flatMap(_.recordRef).flatMap(r => recordOf(r.id).map(r.id -> _)).toMap
        val sources = records.values
          .flatMap(_.sourceRef)
          .map(_.id)
          .toVector
          .distinct
          .map(sid =>
            sid -> client.forEventSourcedEntity(EntityId(sid)).call(SourceEntity.get).invoke()
          )
          .toMap
        def eligible(r: EvidenceRecord) =
          r.sourceRef.flatMap(s => sources.get(s.id)).flatMap(_.eligibility).exists(_.eligible)
        val (admissible, refused) = assessments.partition { a =>
          a.recordRef
            .flatMap(r => records.get(r.id))
            .exists(r => r.status == RecordStatus.verified && eligible(r))
        }
        val excluded = refused.map { a =>
          val r = a.recordRef.flatMap(r => records.get(r.id))
          val reason =
            if r.isEmpty then "the record is not registered"
            else if r.exists(_.status != RecordStatus.verified) then
              s"the record is ${r.get.status}, not verified"
            else "the record's source is not eligible"
          Excluded(Ref(RecordKind.assessment, a.id, a.version), reason)
        }
        // Not applicable to the question: left out for size, logged, never essential (FR-034).
        val (notApplicable, relevant) =
          admissible.partition(_.relationship == Relationship.not_applicable)
        val (backgroundKept, backgroundOver) =
          relevant
            .filter(_.relationship == Relationship.background)
            .splitAt(InvestigationWorkflow.BackgroundBudget)
        val included = relevant.filterNot(a => backgroundOver.exists(_.id == a.id))
        val trims = notApplicable.map(a =>
          Trim(
            Ref(RecordKind.assessment, a.id, a.version),
            "not applicable to the question; trimmed for size"
          )
        ) ++
          backgroundOver.map(a =>
            Trim(
              Ref(RecordKind.assessment, a.id, a.version),
              s"background beyond the first ${InvestigationWorkflow.BackgroundBudget}; trimmed for size"
            )
          )
        val _               = backgroundKept
        val includedRecords = included.flatMap(_.recordRef).flatMap(r => records.get(r.id))
        val spans           = includedRecords.flatMap(_.spans).distinctBy(_.key)
        def resolves(span: SourceSpan): Boolean =
          client
            .forEventSourcedEntity(EntityId(span.sourceId))
            .call(SourceEntity.resolveSpan)
            .invoke(span) match
            case SpanResolution.Resolved(_) => true
            case _                          => false
        val resolved       = spans.map(sp => sp -> resolves(sp)).toMap
        val gapIds         = (qState.gapIds ++ qState.coverageGapIds).distinct
        val n              = currentState.packageIds.count(_.startsWith(s"$qid:pk")) + 1
        val id             = EvidencePackage.idFor(qid, n)
        val assessmentRefs = included.map(a => Ref(RecordKind.assessment, a.id, a.version))
        client
          .forEventSourcedEntity(EntityId(id))
          .call(PackageEntity.build)
          .invoke(
            BuildPackage(
              Vector(Ref(RecordKind.question, qid, q.version)),
              requestRef,
              facetRefs,
              assessmentRefs,
              includedRecords.map(r =>
                Ref(
                  if r.kind == "finding" then RecordKind.finding else RecordKind.claim,
                  r.id,
                  r.version
                )
              ),
              spans,
              excluded,
              included
                .filter(_.relationship == Relationship.contradicts)
                .map(a => Ref(RecordKind.assessment, a.id, a.version)),
              constraints,
              Option.when(q.status != QuestionStatus.answered)(qid).toVector,
              gapIds,
              Vector(
                Ref(RecordKind.stage_run, currentState.runId, 1),
                Ref(RecordKind.stage_run, qState.runId, 1)
              ),
              trims,
              s"system:${PackageRules.ruleSet.key}",
              assessmentRefs ++ facetRefs :+ Ref(RecordKind.question, qid, q.version)
            )
          )
        val checks = PackageRules.check(
          PackageRules.Input(
            included,
            assessments,
            records,
            sources,
            facets,
            spans,
            sp => resolved.getOrElse(sp, false),
            trims
          )
        )
        client
          .forEventSourcedEntity(EntityId(id))
          .call(PackageEntity.validate)
          .invoke(ValidatePackage(checks, PackageRules.ruleSet.key))
        id
      }
      (ids, ids.headOption.map(Ref(RecordKind.`package`, _, 2)))
    }
    val ids = built.fold(
      _ => currentState.packageIds,
      newIds => (currentState.packageIds ++ newIds).distinct
    )
    stepEffects
      .updateState(currentState.copy(packageIds = ids, status = "generating-candidates"))
      .thenTransitionTo(InvestigationWorkflow.candidates.ref)

  def candidatesStep: StepEffect =
    val stage  = "candidates"
    val prompt = DesignAgent.candidatesPrompt
    val facets = currentState.facetIds.map(id => facet(id).call(FacetEntity.get).invoke())
    val packages = currentState.packageIds
      .map(id => client.forEventSourcedEntity(EntityId(id)).call(PackageEntity.get).invoke())
      .filter(_.registered)
    val questionsById = currentState.questionIds
      .map(qid =>
        qid -> client.forEventSourcedEntity(EntityId(qid)).call(QuestionEntity.get).invoke()
      )
      .toMap
    val assessedIds = packages.flatMap(_.recordRefs.map(_.id)).distinct.sorted
    val marker      = InvestigationWorkflow.candidatesMarker(currentState.text, assessedIds)
    val forModel = packages.map { p =>
      val qid = p.questionRefs.headOption.map(_.id).getOrElse("")
      val lines = p.assessmentRefs.map { ref =>
        val a = client.forEventSourcedEntity(EntityId(ref.id)).call(AssessmentEntity.get).invoke()
        val r = a.recordRef.flatMap(r => recordOf(r.id))
        AssessmentLine(
          a.recordRef.map(_.id).getOrElse(""),
          r.map(_.kind).getOrElse(""),
          a.relationship.toString,
          a.applicability.toString,
          r.map(summaryOf).getOrElse("")
        )
      }
      val gaps = p.gapIds
        .map(g => client.forEventSourcedEntity(EntityId(g)).call(GapEntity.get).invoke())
        .filter(_.registered)
        .map(_.description)
      PackageForModel(
        p.id,
        qid,
        questionsById.get(qid).map(_.question).getOrElse(""),
        p.qualityStatus.toString,
        lines,
        gaps,
        p.resourceConstraints
      )
    }
    val outcome = stages.model(
      stage,
      1,
      packages.map(p => Ref(RecordKind.`package`, p.id, p.version)),
      StageCall.hash(marker, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(DesignAgent.candidates)
        .invokeAsync(
          CandidatesRequest(
            currentState.requestId,
            marker,
            currentState.text,
            facets.map(f => FacetLine(f.name, f.statement, f.origin.toString, f.status.toString)),
            forModel
          )
        )
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        stepEffects
          .updateState(currentState.copy(status = "awaiting-decision"))
          .thenTransitionTo(InvestigationWorkflow.awaitDecision.ref)
      case StageOutcome.Done(attempt, set, modelCall) =>
        InvestigationWorkflow.gateCandidates(set) match
          case Left(defect) =>
            stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
            retryOrFail(stage, defect, InvestigationWorkflow.candidates.ref)
          case Right(approaches) =>
            val facetsByName =
              facets.map(f => f.name -> Ref(RecordKind.facet, f.id, f.version)).toMap
            val packageRefs = packages.map(p => Ref(RecordKind.`package`, p.id, p.version))
            def recordRefs(ids: Vector[String]) = ids.flatMap(id =>
              recordOf(id.trim).map(r =>
                Ref(
                  if r.kind == "finding" then RecordKind.finding else RecordKind.claim,
                  r.id,
                  r.version
                )
              )
            )
            val base = currentState.approachIds.size
            val idsByTitle = approaches.zipWithIndex.map { case ((a, _), i) =>
              a.title -> ExperimentalApproach.idFor(currentState.requestId, base + i + 1)
            }.toMap
            approaches.foreach { case (a, assumptions) =>
              val id = idsByTitle(a.title)
              try
                client
                  .forEventSourcedEntity(EntityId(id))
                  .call(ApproachEntity.propose)
                  .invoke(
                    ProposeApproach(
                      requestRef,
                      packageRefs,
                      ApproachContent(
                        a.title,
                        a.hypothesis,
                        a.rationale,
                        a.facetsAddressed.flatMap(facetsByName.get),
                        a.facetsNotAddressed.flatMap(facetsByName.get),
                        recordRefs(a.affirmativeClaimIds),
                        recordRefs(a.contraryClaimIds),
                        recordRefs(a.indirectClaimIds),
                        a.missingEvidence,
                        a.biologicalModel,
                        a.modelLimitations,
                        a.endpoints,
                        a.measurements,
                        a.controls,
                        a.resources,
                        a.safetyRegulatory,
                        assumptions,
                        a.uncertainties,
                        a.failureModes,
                        a.possibleResults,
                        a.prerequisites
                      ),
                      s"model:${prompt.promptVersion}",
                      packageRefs :+ requestRef
                    )
                  )
              catch case e: CommandError if e.code == ErrorCode.Conflict => ()
            }
            val comparisonId =
              s"${currentState.requestId}:comparison${base / math.max(1, approaches.size) + 1}"
            val comparison = Comparison(
              comparisonId,
              approaches.map((a, _) => Ref(RecordKind.approach, idsByTitle(a.title), 1)),
              set.comparison.map(c =>
                Criterion(
                  c.name.trim,
                  c.perApproach.flatMap((title, r) =>
                    idsByTitle.get(title.trim).map(_ -> Rating(r.rating, r.rationale))
                  )
                )
              ),
              s"model:${prompt.promptVersion}",
              java.time.Instant.now()
            )
            idsByTitle.values.foreach(id =>
              client
                .forEventSourcedEntity(EntityId(id))
                .call(ApproachEntity.compared)
                .invoke(comparisonId)
            )
            stages.complete(
              stage,
              attempt,
              idsByTitle.values.headOption.map(Ref(RecordKind.approach, _, 1)),
              modelCalls = Vector(modelCall)
            )
            stepEffects
              .updateState(
                currentState.copy(
                  approachIds =
                    (currentState.approachIds ++ idsByTitle.values.toVector.sorted).distinct,
                  comparison = Some(comparison),
                  status = "awaiting-decision"
                )
              )
              .thenTransitionTo(InvestigationWorkflow.awaitDecision.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrFail(stage, defect, InvestigationWorkflow.candidates.ref)

  def awaitDecisionStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = "awaiting-decision")).thenPause()

  // ── the design and its approval (US8) ──────────────────────────────────────────

  private def designId = DesignState.idFor(currentState.requestId)

  def designStep: StepEffect =
    val stage      = "design"
    val prompt     = DesignAgent.designPrompt
    val decisionId = currentState.decisionId.getOrElse(throw IllegalStateException("no decision"))
    val decision =
      client.forEventSourcedEntity(EntityId(decisionId)).call(DecisionEntity.get).invoke()
    val selectedId = decision.selectedApproachRef
      .map(_.id)
      .getOrElse(throw IllegalStateException("no selected approach"))
    val approach =
      client.forEventSourcedEntity(EntityId(selectedId)).call(ApproachEntity.get).invoke()
    val rejected = decision.rejected.map(r =>
      RejectedLine(
        client
          .forEventSourcedEntity(EntityId(r.approachRef.id))
          .call(ApproachEntity.get)
          .invoke()
          .title,
        r.reason
      )
    )
    val facets = currentState.facetIds.map(id => facet(id).call(FacetEntity.get).invoke())
    // The packages carry the assessed records; the design cites the verified claims of their sources.
    val packagedRecords = currentState.packageIds
      .map(id => client.forEventSourcedEntity(EntityId(id)).call(PackageEntity.get).invoke())
      .flatMap(_.recordRefs)
      .map(_.id)
      .distinct
      .flatMap(recordOf)
    val claims = packagedRecords
      .flatMap(_.sourceRef)
      .distinct
      .flatMap { src =>
        val doc = client.forEventSourcedEntity(EntityId(src.id)).call(SourceEntity.get).invoke()
        doc.version(src.version).toVector.flatMap(_.extractions.flatMap(_.recordIds))
      }
      .distinct
      .flatMap(recordOf)
      .filter(r => r.kind == "claim" && r.status == RecordStatus.verified)
    val claimsById = claims.map(c => c.id -> c).toMap
    val marker =
      DesignAgent.designMarker(currentState.text, approach.title, currentState.requestedChanges)
    val approachRef = Ref(RecordKind.approach, approach.id, approach.version)
    val decisionRef = Ref(RecordKind.decision, decisionId, decision.version)
    val outcome = stages.model(
      stage,
      1,
      Vector(approachRef, decisionRef),
      StageCall.hash(marker, prompt.promptVersion, currentState.designVersions.toString),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(DesignAgent.design)
        .invokeAsync(
          DesignRequest(
            currentState.requestId,
            marker,
            currentState.text,
            facets.map(f => FacetLine(f.name, f.statement, f.origin.toString, f.status.toString)),
            ApproachForModel(
              approach.title,
              approach.hypothesis,
              approach.rationale,
              approach.biologicalModel,
              approach.endpoints,
              approach.measurements,
              approach.controls,
              approach.assumptions.map(a => s"${a.statement} (${a.evidenceStatus})"),
              approach.missingEvidence
            ),
            rejected,
            claims.map(c =>
              ClaimLine(
                c.id,
                summaryOf(c),
                c.content
                  .collect { case RecordContent.OfClaim(cl) => cl.qualifications }
                  .getOrElse(Vector.empty),
                c.sourceRef.map(_.id).getOrElse("")
              )
            ),
            currentState.requestedChanges
          )
        )
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        stepEffects
          .updateState(currentState.copy(status = "awaiting-approval"))
          .thenTransitionTo(InvestigationWorkflow.awaitApproval.ref)
      case StageOutcome.Done(attempt, proposal, modelCall) =>
        val unknown = proposal.background
          .flatMap(_.claimIds)
          .map(_.trim)
          .distinct
          .filterNot(claimsById.contains)
        if unknown.nonEmpty then
          val defect = Rules.defect(
            DefectKind.unverified_claim_ref,
            s"background cites claims that are not verified claims of the packages: ${unknown.mkString(", ")}",
            retryable = true
          )
          stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
          retryOrFail(stage, defect, InvestigationWorkflow.design.ref)
        else
          val background = proposal.background.map { g =>
            val cited = g.claimIds.map(_.trim).distinct.flatMap(claimsById.get)
            Grounded(
              g.text,
              cited.map(c => Ref(RecordKind.claim, c.id, c.version)),
              cited.flatMap(_.spans).distinctBy(_.key)
            )
          }
          val manifest = Manifest(
            background.flatMap(_.claimRefs).distinct,
            background.flatMap(_.spans).distinctBy(_.key),
            Vector.empty
          )
          val sections = DesignSections(
            proposal.questionAndScope,
            background,
            proposal.hypothesis,
            proposal.modelAndPrerequisites,
            proposal.variablesControlsComparators,
            proposal.endpointsAndMeasurementPlan,
            proposal.successFailureInconclusiveCriteria,
            proposal.resourcesTimelineDependencies,
            proposal.limitations,
            proposal.alternatives,
            proposal.outcomesAndDecisionPoints,
            manifest
          )
          def resolves(span: SourceSpan): Boolean =
            client
              .forEventSourcedEntity(EntityId(span.sourceId))
              .call(SourceEntity.resolveSpan)
              .invoke(span) match
              case SpanResolution.Resolved(_) => true
              case _                          => false
          val checks =
            DesignRules.check(DesignRules.Input(sections, claimsById, resolves, facets, approach))
          checks.find(!_.passed) match
            case Some(failed) =>
              val kind =
                if failed.name == DesignRules.claimsVerified.key then
                  DefectKind.unverified_claim_ref
                else if failed.name == DesignRules.spansResolve.key then DefectKind.span_unresolved
                else DefectKind.schema_invalid
              val defect = Rules.defect(
                kind,
                s"${failed.name}: ${failed.detail}",
                retryable = failed.name != DesignRules.facetsAccounted.key
              )
              stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
              retryOrFail(stage, defect, InvestigationWorkflow.design.ref)
            case None =>
              val generated = client
                .forEventSourcedEntity(EntityId(designId))
                .call(DesignEntity.generate)
                .invoke(
                  GenerateDesign(
                    requestRef,
                    approachRef,
                    decisionRef,
                    sections,
                    checks,
                    DesignRules.ruleSet.key,
                    s"model:${prompt.promptVersion}",
                    currentState.requestedChanges
                  )
                )
              val version = generated.versions.size
              client
                .forEventSourcedEntity(EntityId(designId))
                .call(DesignEntity.submit)
                .invoke(SubmitDesign(version, "operator"))
              stages.complete(
                stage,
                attempt,
                Some(Ref(RecordKind.design, designId, version)),
                modelCalls = Vector(modelCall)
              )
              stepEffects
                .updateState(
                  currentState.copy(status = "awaiting-approval", designVersions = version)
                )
                .thenTransitionTo(InvestigationWorkflow.awaitApproval.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrFail(stage, defect, InvestigationWorkflow.design.ref)

  def awaitApprovalStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = "awaiting-approval")).thenPause()

  /**
   * After the reviewer's decision on the design: approved ends it, changes regenerate, a rejection
   * ends it too.
   */
  def closeStep: StepEffect =
    currentState.lastApproval.map(_.decision) match
      case Some(ApprovalDecision.approved) =>
        request.call(RequestEntity.advance).invoke(RequestStatus.designed)
        stepEffects.updateState(currentState.copy(status = "designed")).thenEnd
      case Some(ApprovalDecision.changes_requested) =>
        stepEffects
          .updateState(
            currentState.copy(
              status = "designing",
              requestedChanges =
                currentState.lastApproval.map(_.requestedChanges).getOrElse(Vector.empty)
            )
          )
          .thenTransitionTo(InvestigationWorkflow.design.ref)
      case _ =>
        stepEffects.updateState(currentState.copy(status = "design-rejected")).thenEnd

  def recordFailureStep: StepEffect = stepEffects.thenPause()

  private def retryOrFail(
      stage: String,
      defect: Defect,
      again: com.thinkmorestupidless.ankka.core.effect.StepRef
  ): StepEffect =
    val tried = currentState.attempts.getOrElse(stage, 1)
    if defect.retryable && tried < Configured.retryBudget + 1 then
      stepEffects
        .updateState(currentState.copy(attempts = currentState.attempts + (stage -> (tried + 1))))
        .thenTransitionTo(again)
    else
      stepEffects
        .updateState(
          currentState
            .copy(status = "failed", failure = Some(s"$stage: ${defect.kind} (${defect.detail})"))
        )
        .thenTransitionTo(InvestigationWorkflow.recordFailure.ref)

object InvestigationWorkflow
    extends Workflow.Companion[InvestigationWorkflow, InvestigationState](
      componentId = ComponentId("investigation"),
      stateSerializer = Codecs.serializer[InvestigationState]("investigation-state")
    ):

  given Serializer[StartInvestigation] =
    Codecs.serializer[StartInvestigation]("start-investigation")

  def create(context: WorkflowContext) = new InvestigationWorkflow(context)

  val extractFacets        = step("extract-facets")(_.extractFacetsStep)
  val roundTrip            = step("round-trip")(_.roundTripStep)
  val awaitFacetReview     = step("await-facet-review")(_.awaitFacetReviewStep)
  val deriveQuestions      = step("derive-questions")(_.deriveQuestionsStep)
  val investigateQuestions = step("investigate-questions")(_.investigateQuestionsStep)
  val buildPackages        = step("build-packages")(_.buildPackagesStep)
  val candidates           = step("candidates")(_.candidatesStep)
  val awaitDecision        = step("await-decision")(_.awaitDecisionStep)
  val design               = step("design")(_.designStep)
  val awaitApproval        = step("await-approval")(_.awaitApprovalStep)
  val close                = step("close")(_.closeStep)
  val recordFailure        = step("record-failure")(_.recordFailureStep)

  val start            = command("start")(_.start)
  val facetsConfirmed  = command("facets-confirmed")(_.facetsConfirmed)
  val rerunRoundTrip   = command("rerun-round-trip")(_.rerunRoundTrip)
  val packageEvidence  = command("package")(_.packageEvidence)
  val decided          = command("decided")(_.decided)
  val approvalRecorded = command("approval-recorded")(_.approvalRecorded)
  val status           = query("status")(_.status)

  given Serializer[ApprovalOutcome] = Codecs.serializer[ApprovalOutcome]("approval-outcome")

  /** How many background assessments a package keeps before trimming for size. */
  val BackgroundBudget = 10

  def candidatesMarker(text: String, assessedRecordIds: Vector[String]): String =
    s"candidates:${Ids.short(text)}:${Ids.short(assessedRecordIds.sorted.mkString(","))}"

  /**
   * The candidates gate: at least two, every field that must be present, every assumption labelled,
   * the eight criteria rated.
   */
  def gateCandidates(
      set: ApproachSet
  ): Either[Defect, Vector[(ApproachProposal, Vector[Assumption])]] =
    if set.approaches.size < 2 then
      Left(
        Rules.defect(
          DefectKind.fewer_than_two,
          s"${set.approaches.size} approach(es); at least two are required",
          retryable = true
        )
      )
    else if set.approaches.map(_.title.trim).distinct.size != set.approaches.size then
      Left(
        Rules.defect(DefectKind.schema_invalid, "two approaches share a title", retryable = true)
      )
    else
      val labelled = set.approaches.map { a =>
        val assumptions = a.assumptions.flatMap(p =>
          EvidenceStatus.values
            .find(_.toString == p.evidenceStatus.trim.toLowerCase)
            .map(Assumption(p.statement, _))
        )
        (a, assumptions)
      }
      val unlabelled = labelled.collect { case (a, as) if as.size != a.assumptions.size => a.title }
      val incomplete = set.approaches
        .filter(a =>
          a.hypothesis.isBlank || a.biologicalModel.isBlank || a.endpoints.isEmpty || a.measurements.isEmpty || a.controls.isEmpty
        )
        .map(_.title)
      val titles          = set.approaches.map(_.title.trim).toSet
      val names           = set.comparison.map(_.name.trim)
      val missingCriteria = Comparison.CriterionNames.filterNot(names.contains)
      val unrated = set.comparison
        .filter(c =>
          titles.exists(t => !c.perApproach.keySet.map(_.trim).contains(t)) || c.perApproach.values
            .exists(_.rationale.isBlank)
        )
        .map(_.name)
      if unlabelled.nonEmpty then
        Left(
          Rules.defect(
            DefectKind.schema_invalid,
            s"an assumption is not labelled established, assumed or unknown in: ${unlabelled.mkString(", ")}",
            retryable = true
          )
        )
      else if incomplete.nonEmpty then
        Left(
          Rules.defect(
            DefectKind.schema_invalid,
            s"missing hypothesis, model, endpoints, measurements or controls in: ${incomplete.mkString(", ")}",
            retryable = true
          )
        )
      else if missingCriteria.nonEmpty then
        Left(
          Rules.defect(
            DefectKind.schema_invalid,
            s"criteria missing from the comparison: ${missingCriteria.mkString(", ")}",
            retryable = true
          )
        )
      else if unrated.nonEmpty then
        Left(
          Rules.defect(
            DefectKind.schema_invalid,
            s"criteria that do not rate every approach with a rationale: ${unrated.mkString(", ")}",
            retryable = true
          )
        )
      else Right(labelled)
