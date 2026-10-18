package com.thinkmorestupidless.experimentproposals.application.workflows

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.experimentproposals.application.{StageCall, StageOutcome}
import com.thinkmorestupidless.experimentproposals.application.agents.*
import com.thinkmorestupidless.experimentproposals.application.entities.*
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, Ref}
import com.thinkmorestupidless.experimentproposals.domain.request.AdvanceQuestion
import com.thinkmorestupidless.experimentproposals.domain.search.*
import com.thinkmorestupidless.experimentproposals.domain.sources.{
  SourceDocument,
  SourceRecord,
  SpanResolution
}
import com.thinkmorestupidless.experimentproposals.retrieval.{Context, Searcher}
import com.thinkmorestupidless.experimentproposals.rules.{
  ApplicabilityRules,
  EligibilityRules,
  Rules
}
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

final case class QuestionInvestigationState(
    questionId: String = "",
    requestId: String = "",
    runId: String = "",
    status: String = "not-started",
    recordIds: Vector[String] = Vector.empty,
    assessmentIds: Vector[String] = Vector.empty,
    sufficiencyIds: Vector[String] = Vector.empty,
    gapIds: Vector[String] = Vector.empty,
    emptySearch: Boolean = false,
    attempts: Map[String, Int] = Map.empty,
    failure: Option[String] = None,
    planId: Option[String] = None,
    replans: Int = 0,
    executionIds: Vector[String] = Vector.empty,
    roundExecutionIds: Vector[String] = Vector.empty,
    fusedExecutionId: Option[String] = None,
    executionCount: Int = 0,
    rounds: Int = 0,
    sourcesOverride: Vector[String] = Vector.empty,
    supplementalFor: Option[String] = None,
    resolvedChunkIds: Vector[String] = Vector.empty,
    coverageGapIds: Vector[String] = Vector.empty,
    retrieved: Boolean = false
):
  def started: Boolean = status != "not-started"

  /** Where the investigation rests between the stages a command or the API moves it through. */
  def resting: String = if retrieved then "retrieved" else "awaiting-retrieval"

final case class StartQuestionInvestigation(questionId: String, requestId: String)
final case class AssessRecords(recordIds: Vector[String])
final case class ExecutePlan(sources: Vector[String] = Vector.empty)

// The `RecordContent` codec this package uses is declared once, in VerificationWorkflow.scala.

/**
 * One question's investigation (plan: component table; data-model.md): `plan` (agent) → `execute`
 * (local lexical and semantic, then the adapters named) → `eligibility` (after ranking, with
 * exclusions) → `resolve` (context and the verified records among the hits) → waits; `assess` and
 * `judge` are asked for by the API or the request's packaging; a judgement that escalates to a
 * supplemental search runs one more bounded round linked to the gap.
 */
final class QuestionInvestigationWorkflow(context: WorkflowContext)
    extends Workflow[QuestionInvestigationState]:

  private val client  = context.componentClient
  private val timeout = 3.minutes

  def emptyState: QuestionInvestigationState = QuestionInvestigationState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(timeout + 30.seconds)
      .stepRecovery(
        QuestionInvestigationWorkflow.executeStep,
        RecoverStrategy.maxRetries(2).failoverTo(QuestionInvestigationWorkflow.executeFailed)
      )
      .build

  private def stages = StageCall(client, currentState.runId, "question-investigation")
  private def question = client
    .forEventSourcedEntity(EntityId(currentState.questionId))
    .call(QuestionEntity.get)
    .invoke()
  private def questionRef = Ref(RecordKind.question, currentState.questionId, 1)
  private def planId      = SearchPlan.idFor(currentState.questionId)
  private def plan =
    client.forEventSourcedEntity(EntityId(planId)).call(SearchPlanEntity.get).invoke()
  private def planRef(p: SearchPlan) = Ref(RecordKind.search_plan, p.id, p.version)
  private def execution(id: String) =
    client.forEventSourcedEntity(EntityId(id)).call(SearchExecutionEntity.get).invoke()
  private def views = Configured.views.getOrElse(
    throw IllegalStateException("retrieval needs the view client; Configured.views is not set")
  )

  // ── commands ────────────────────────────────────────────────────────────────

  def start(cmd: StartQuestionInvestigation): Effect[QuestionInvestigationState] =
    if currentState.started then effects.reply(currentState)
    else
      effects
        .updateState(
          QuestionInvestigationState(
            cmd.questionId,
            cmd.requestId,
            s"run-${context.workflowId}",
            "planning"
          )
        )
        .transitionTo(QuestionInvestigationWorkflow.planStep.ref)
        .thenReplyState

  /** A replan: a new plan version, then the chain runs again. */
  def replan: Effect[QuestionInvestigationState] =
    if !currentState.started then
      effects.error("the question investigation has not started", ErrorCode.NotFound)
    else
      effects
        .updateState(currentState.copy(status = "planning", replans = currentState.replans + 1))
        .transitionTo(QuestionInvestigationWorkflow.planStep.ref)
        .thenReplyState

  def execute(cmd: ExecutePlan): Effect[QuestionInvestigationState] =
    if currentState.planId.isEmpty then
      effects.error("the question has no plan yet", ErrorCode.Conflict)
    else
      effects
        .updateState(
          currentState
            .copy(status = "executing", sourcesOverride = cmd.sources, supplementalFor = None)
        )
        .transitionTo(QuestionInvestigationWorkflow.executeStep.ref)
        .thenReplyState

  def supplemental(gapId: String): Effect[QuestionInvestigationState] =
    if currentState.planId.isEmpty then
      effects.error("the question has no plan yet", ErrorCode.Conflict)
    else if !currentState.gapIds.contains(gapId) && !currentState.coverageGapIds.contains(gapId)
    then effects.error(s"$gapId is not a gap of this question", ErrorCode.NotFound)
    else
      effects
        .updateState(currentState.copy(status = "supplemental", supplementalFor = Some(gapId)))
        .transitionTo(QuestionInvestigationWorkflow.executeStep.ref)
        .thenReplyState

  def assess(cmd: AssessRecords): Effect[QuestionInvestigationState] =
    if !currentState.started then
      effects.error("the question investigation has not started", ErrorCode.NotFound)
    else
      val ids = if cmd.recordIds.nonEmpty then cmd.recordIds else currentState.recordIds
      if ids.isEmpty then
        effects.error("no records to assess: retrieval returned none and none were named")
      else
        effects
          .updateState(currentState.copy(status = "assessing"))
          .transitionTo(QuestionInvestigationWorkflow.assessStep.withInput(AssessRecords(ids)))
          .thenReplyState

  def judge(decision: String): Effect[QuestionInvestigationState] =
    if !currentState.started then
      effects.error("the question investigation has not started", ErrorCode.NotFound)
    else if decision.isBlank then effects.error("name the decision")
    else
      effects
        .updateState(currentState.copy(status = "judging"))
        .transitionTo(QuestionInvestigationWorkflow.sufficiencyStep.withInput(decision))
        .thenReplyState

  def status: ReadOnlyEffect[QuestionInvestigationState] = effects.reply(currentState)

  // ── plan ────────────────────────────────────────────────────────────────────

  def planStepRun: StepEffect =
    val stage  = "plan"
    val prompt = SearchAgent.planPrompt
    val q      = question
    val marker = SearchAgent.planMarker(q.question)
    val outcome = stages.model(
      stage,
      1,
      Vector(questionRef),
      StageCall.hash(marker, prompt.promptVersion, currentState.replans.toString),
      prompt.promptVersion,
      if Configured.tier == com.thinkmorestupidless.experimentproposals.config.Tier.live then
        SearchAgent.Model
      else Configured.modelName,
      SearchAgent.Effort,
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(SearchAgent.plan)
        .invokeAsync(
          PlanRequest(
            q.id,
            q.question,
            q.evidenceNeeded,
            q.answerFields,
            "local" +: Configured.adapters.names.filterNot(_ == "file")
          )
        )
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        stepEffects
          .updateState(currentState.copy(planId = Some(planId), status = "executing"))
          .thenTransitionTo(QuestionInvestigationWorkflow.executeStep.ref)
      case StageOutcome.Done(attempt, proposal, modelCall) =>
        QuestionInvestigationWorkflow.gate(proposal) match
          case Left(defect) =>
            stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
            retryOrGiveUp(stage, defect, QuestionInvestigationWorkflow.planStep.ref)
          case Right((queries, escalation)) =>
            val recorded = client
              .forEventSourcedEntity(EntityId(planId))
              .call(SearchPlanEntity.plan)
              .invoke(
                RecordPlan(
                  questionRef,
                  queries,
                  proposal.targetSources.map(_.trim).filter(_.nonEmpty).distinct,
                  proposal.filters,
                  Searcher.parameters(Searcher.Settings()),
                  proposal.stoppingCriterion,
                  escalation,
                  proposal.expansions,
                  s"model:${prompt.promptVersion}"
                )
              )
            client
              .forEventSourcedEntity(EntityId(currentState.questionId))
              .call(QuestionEntity.recordPlan)
              .invoke(planId)
            stages.complete(
              stage,
              attempt,
              Some(Ref(RecordKind.search_plan, planId, recorded.version)),
              modelCalls = Vector(modelCall)
            )
            stepEffects
              .updateState(currentState.copy(planId = Some(planId), status = "executing"))
              .thenTransitionTo(QuestionInvestigationWorkflow.executeStep.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrGiveUp(stage, defect, QuestionInvestigationWorkflow.planStep.ref)

  // ── execute ─────────────────────────────────────────────────────────────────

  def executeStepRun: StepEffect =
    val p        = plan
    val settings = Searcher.Settings()
    val targets =
      if currentState.sourcesOverride.nonEmpty then currentState.sourcesOverride
      else p.targetSources
    val expansions = currentState.supplementalFor.toVector.flatMap(_ =>
      p.expansions.map(text =>
        Query(QueryKind.lexical, text, p.queries.find(_.kind == QueryKind.lexical).map(_.text))
      )
    )
    val queries          = p.queries ++ expansions
    val round            = currentState.rounds + 1
    val base             = currentState.executionCount
    var next             = base
    def nextId(): String = { next += 1; SearchExecution.idFor(p.id, next) }
    val gapId            = currentState.supplementalFor

    def record(
        query: Option[Query],
        fused: Boolean,
        source: String,
        snapshot: Vector[Ref],
        results: Vector[Hit],
        error: Option[String]
    ): String =
      val id = nextId()
      client
        .forEventSourcedEntity(EntityId(id))
        .call(SearchExecutionEntity.execute)
        .invoke(
          RecordExecution(
            planRef(p),
            currentState.questionId,
            query,
            fused,
            source,
            Searcher.parameters(settings) ++ p.filters.map((k, v) => s"filter.$k" -> v),
            snapshot,
            results,
            error,
            None,
            gapId
          )
        )
      client.forEventSourcedEntity(EntityId(p.id)).call(SearchPlanEntity.recordExecution).invoke(id)
      id

    val outcome = stages.plain(
      "execute",
      1,
      Vector(planRef(p)),
      StageCall.hash(
        p.id,
        p.version.toString,
        round.toString,
        targets.mkString(","),
        queries.map(_.text).mkString("|")
      )
    ) { _ =>
      val ids   = Vector.newBuilder[String]
      var fused = Option.empty[String]
      targets.foreach {
        case "local" =>
          val store    = Searcher.store(views)
          val perQuery = queries.map(q => q -> Searcher.run(q, store, settings, Some(views)))
          perQuery.foreach((q, hits) =>
            ids += record(Some(q), false, "local", store.snapshot, hits, None)
          )
          val fusedHits = Searcher.fuse(perQuery, store, settings)
          val fid       = record(None, true, "local", store.snapshot, fusedHits, None)
          ids += fid
          fused = Some(fid)
        case name =>
          Configured.adapters(name) match
            case Left(problem) =>
              queries
                .filter(_.kind == QueryKind.lexical)
                .foreach(q =>
                  ids += record(Some(q), false, name, Vector.empty, Vector.empty, Some(problem))
                )
            case Right(_)
                if QuestionInvestigationWorkflow.network(name) && !Configured.externalSearch =>
              queries
                .filter(_.kind == QueryKind.lexical)
                .foreach(q =>
                  ids += record(
                    Some(q),
                    false,
                    name,
                    Vector.empty,
                    Vector.empty,
                    Some(s"$name is not searched in the replay tier")
                  )
                )
            case Right(adapter) =>
              queries.filter(_.kind == QueryKind.lexical).foreach { q =>
                val refs = adapter.search(q.text, p.filters)
                val hits = refs.zipWithIndex.map { (r, i) =>
                  Hit(
                    i + 1,
                    r.score.getOrElse(1.0 / (i + 1)),
                    SourceRecord.sourceIdOf(r.identifier),
                    0,
                    None,
                    None,
                    identifier = Some(r.identifier),
                    title = Some(r.title)
                  )
                }
                ids += record(Some(q), false, name, Vector.empty, hits, None)
              }
      }
      ((ids.result(), fused), None)
    }
    outcome match
      case Left(_) =>
        // Already executed for these inputs: the round's executions are those recorded last time.
        stepEffects
          .updateState(currentState.copy(status = "applying-eligibility"))
          .thenTransitionTo(QuestionInvestigationWorkflow.eligibilityStep.ref)
      case Right((ids, fused)) =>
        stepEffects
          .updateState(
            currentState.copy(
              status = "applying-eligibility",
              executionIds = (currentState.executionIds ++ ids).distinct,
              roundExecutionIds = ids,
              fusedExecutionId = fused,
              executionCount = next,
              rounds = round
            )
          )
          .thenTransitionTo(QuestionInvestigationWorkflow.eligibilityStep.ref)

  /**
   * The failover of `execute` after its retries: the failure is on the state; the operator decides.
   */
  def executeFailedStep: StepEffect =
    stepEffects
      .updateState(
        currentState.copy(
          status = currentState.resting,
          failure = Some("execute: an external source failed after retries"),
          supplementalFor = None
        )
      )
      .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)

  // ── eligibility ─────────────────────────────────────────────────────────────

  def eligibilityStepRun: StepEffect =
    val round = currentState.roundExecutionIds
    val docs  = scala.collection.mutable.Map.empty[String, SourceDocument]
    def doc(id: String): SourceDocument =
      docs.getOrElseUpdate(
        id,
        client.forEventSourcedEntity(EntityId(id)).call(SourceEntity.get).invoke()
      )

    stages.plain(
      "eligibility",
      1,
      round.map(id => Ref(RecordKind.search_execution, id, 1)),
      StageCall.hash((round :+ EligibilityRules.ruleSet.key)*)
    ) { _ =>
      round.foreach { id =>
        val ex = execution(id)
        if !ex.eligibilityApplied then
          val judged = ex.results.map { hit =>
            val d = doc(hit.sourceId)
            if !d.registered then
              (
                hit,
                Some(
                  Exclusion(
                    hit.sourceId,
                    hit.sourceVersion,
                    hit.chunkId,
                    "the source is not ingested; its status is unknown until it is",
                    "eligibility.unfetched",
                    ExclusionAction.downgraded
                  )
                )
              )
            else
              val e = EligibilityRules.assess(d, hit.sourceVersion, "system:retrieval")
              if !e.eligible then
                val blocking = e.reasons.head
                (
                  hit,
                  Some(
                    Exclusion(
                      hit.sourceId,
                      hit.sourceVersion,
                      hit.chunkId,
                      blocking.detail,
                      blocking.ruleId,
                      ExclusionAction.excluded
                    )
                  )
                )
              else if d.status == SourceStatus.corrected then
                (
                  hit,
                  Some(
                    Exclusion(
                      hit.sourceId,
                      hit.sourceVersion,
                      hit.chunkId,
                      "the source is corrected; read with the correction",
                      EligibilityRules.corrected.key,
                      ExclusionAction.downgraded
                    )
                  )
                )
              else (hit, None)
          }
          val kept = judged.collect { case (h, None) => h }
          val downgraded = judged.collect {
            case (h, Some(x)) if x.action == ExclusionAction.downgraded => h
          }
          val eligible = (kept ++ downgraded).zipWithIndex.map((h, i) => h.copy(rank = i + 1))
          client
            .forEventSourcedEntity(EntityId(id))
            .call(SearchExecutionEntity.applyEligibility)
            .invoke(
              ApplyEligibility(
                eligible,
                judged.flatMap(_._2),
                coverageGap = eligible.isEmpty && ex.error.isEmpty,
                EligibilityRules.ruleSet.key
              )
            )
      }
      ((), currentState.fusedExecutionId.map(Ref(RecordKind.search_execution, _, 2)))
    }

    val decisive = currentState.fusedExecutionId.map(execution).toVector ++
      (if currentState.fusedExecutionId.isEmpty then round.map(execution) else Vector.empty)
    val nothingEligible = decisive.forall(_.eligibleResults.isEmpty)
    val gapIds = currentState.supplementalFor match
      case Some(gapId) =>
        // A supplemental round counts against the gap's budget, whatever it found.
        val linked = currentState.fusedExecutionId.orElse(round.headOption)
        linked.foreach { eid =>
          try
            client
              .forEventSourcedEntity(EntityId(gapId))
              .call(GapEntity.recordSupplemental)
              .invoke(eid)
          catch case e: CommandError if e.code == ErrorCode.Conflict => ()
        }
        Vector.empty
      case None if nothingEligible =>
        val q = question
        val id = GapEntity.idFor(
          currentState.questionId,
          currentState.gapIds.size + currentState.coverageGapIds.size + 1
        )
        client
          .forEventSourcedEntity(EntityId(id))
          .call(GapEntity.open)
          .invoke(
            OpenGap(
              questionRef,
              s"The search for '${q.question}' returned nothing eligible (${round.size} execution(s)); a coverage gap caused by the search, not evidence that the approach is impossible.",
              GapKind.coverage,
              Escalation.supplemental_search
            )
          )
        Vector(id)
      case None => Vector.empty
    stepEffects
      .updateState(
        currentState.copy(
          status = "resolving",
          coverageGapIds = (currentState.coverageGapIds ++ gapIds).distinct,
          emptySearch = nothingEligible
        )
      )
      .thenTransitionTo(QuestionInvestigationWorkflow.resolveStep.ref)

  // ── resolve ─────────────────────────────────────────────────────────────────

  def resolveStepRun: StepEffect =
    val decisive = currentState.fusedExecutionId.map(execution).toVector ++
      (if currentState.fusedExecutionId.isEmpty then currentState.roundExecutionIds.map(execution)
       else Vector.empty)
    val eligible = decisive.flatMap(_.eligibleResults).filter(_.chunkId.isDefined)
    val resolved =
      eligible.take(10).flatMap(h => h.chunkId.flatMap(c => Context.resolve(client, c).map(_ => c)))
    val records = eligible
      .map(h => (h.sourceId, h.sourceVersion))
      .distinct
      .flatMap { (sourceId, version) =>
        val d = client.forEventSourcedEntity(EntityId(sourceId)).call(SourceEntity.get).invoke()
        d.version(version).toVector.flatMap(_.extractions.flatMap(_.recordIds))
      }
      .distinct
      .filter(id => recordOf(id).exists(_.status == RecordStatus.verified))
    stepEffects
      .updateState(
        currentState.copy(
          status = "retrieved",
          retrieved = true,
          resolvedChunkIds = resolved,
          recordIds = (currentState.recordIds ++ records).distinct,
          supplementalFor = None,
          sourcesOverride = Vector.empty
        )
      )
      .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)

  /** Between stages the investigation waits for the API or the request's packaging (US7). */
  def awaitStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = currentState.resting)).thenPause()

  // ── assess and sufficiency (US4) ────────────────────────────────────────────

  private def targetSpecies: Option[String] =
    val req = client
      .forEventSourcedEntity(EntityId(currentState.requestId))
      .call(RequestEntity.get)
      .invoke()
    req.facets
      .find(_.name == "biological source")
      .map(f =>
        client.forEventSourcedEntity(EntityId(f.id)).call(FacetEntity.get).invoke().statement
      )

  private def recordOf(id: String): Option[EvidenceRecord] =
    Records
      .companionFor(id)
      .map(c => client.forEventSourcedEntity(EntityId(id)).call(c.get).invoke())
      .filter(_.registered)

  private def passagesOf(r: EvidenceRecord): Vector[String] =
    r.spans.flatMap { span =>
      client
        .forEventSourcedEntity(EntityId(span.sourceId))
        .call(SourceEntity.resolveSpan)
        .invoke(span) match
        case SpanResolution.Resolved(text) => Some(text)
        case _                             => None
    }

  def assessStepRun(cmd: AssessRecords): StepEffect =
    val stage   = "assess"
    val prompt  = EvidenceAgent.assessPrompt
    val q       = question
    val records = cmd.recordIds.flatMap(recordOf).filter(_.status == RecordStatus.verified)
    if records.isEmpty then
      stepEffects
        .updateState(
          currentState.copy(
            status = currentState.resting,
            failure = Some("none of the named records is verified")
          )
        )
        .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)
    else
      val marker = EvidenceAgent.assessMarker(q.question, records.map(_.id))
      val forModel = records.map(r =>
        RecordForAssessment(
          r.id,
          r.kind,
          r.content.map(c => writeToString(c)).getOrElse(""),
          passagesOf(r)
        )
      )
      val outcome = stages.model(
        stage,
        1,
        records.map(r =>
          Ref(if r.kind == "finding" then RecordKind.finding else RecordKind.claim, r.id, r.version)
        ),
        StageCall.hash(marker, prompt.promptVersion),
        prompt.promptVersion,
        Configured.modelName,
        "high",
        timeout
      ) {
        client
          .forAgent(SessionId(marker))
          .call(EvidenceAgent.assess)
          .invokeAsync(
            AssessRequest(q.id, marker, q.question, q.answerFields, targetSpecies, forModel)
          )
      }
      outcome match
        case StageOutcome.Skipped(_) =>
          val ids = records.map(r => AssessmentEntity.idFor(currentState.questionId, r.id))
          stepEffects
            .updateState(
              currentState.copy(
                status = currentState.resting,
                assessmentIds = (currentState.assessmentIds ++ ids).distinct
              )
            )
            .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)
        case StageOutcome.Done(attempt, set, modelCall) =>
          val byId = records.map(r => r.id -> r).toMap
          val applied = set.assessments.flatMap(p =>
            byId.get(p.recordId.trim).map(r => (r, p, ApplicabilityRules.apply(p)))
          )
          applied.collectFirst { case (_, _, Left(defect)) => defect } match
            case Some(defect) =>
              stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
              retryOrGiveUp(stage, defect, QuestionInvestigationWorkflow.assessStep.withInput(cmd))
            case None =>
              val ids = applied.collect { case (r, p, Right(a)) =>
                val id = AssessmentEntity.idFor(currentState.questionId, r.id)
                val recordRef = Ref(
                  if r.kind == "finding" then RecordKind.finding else RecordKind.claim,
                  r.id,
                  r.version
                )
                try
                  client
                    .forEventSourcedEntity(EntityId(id))
                    .call(AssessmentEntity.propose)
                    .invoke(
                      ProposeAssessment(
                        questionRef,
                        recordRef,
                        a.relationship,
                        a.applicability,
                        a.dimensions,
                        p.rationale,
                        p.limitations,
                        s"model:${prompt.promptVersion}",
                        a.ruleResults,
                        Vector(questionRef, recordRef)
                      )
                    )
                catch case e: CommandError if e.code == ErrorCode.Conflict => ()
                id
              }
              stages.complete(
                stage,
                attempt,
                ids.headOption.map(Ref(RecordKind.assessment, _, 1)),
                modelCalls = Vector(modelCall)
              )
              stepEffects
                .updateState(
                  currentState.copy(
                    status = currentState.resting,
                    recordIds = (currentState.recordIds ++ records.map(_.id)).distinct,
                    assessmentIds = (currentState.assessmentIds ++ ids).distinct
                  )
                )
                .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)
        case StageOutcome.Failed(_, defect, _) =>
          retryOrGiveUp(stage, defect, QuestionInvestigationWorkflow.assessStep.withInput(cmd))

  def sufficiencyStepRun(decision: String): StepEffect =
    val stage  = "sufficiency"
    val prompt = EvidenceAgent.sufficiencyPrompt
    val q      = question
    val assessments = currentState.assessmentIds
      .map(id => client.forEventSourcedEntity(EntityId(id)).call(AssessmentEntity.get).invoke())
      .filter(_.registered)
    val marker = EvidenceAgent.sufficiencyMarker(
      q.question,
      decision,
      assessments.flatMap(_.recordRef.map(_.id))
    )
    val forModel = assessments.map(a =>
      AssessmentForSufficiency(
        a.id,
        a.recordRef.map(_.id).getOrElse(""),
        a.relationship.toString,
        a.applicability.toString,
        a.dimensions.map((k, v) => k -> v.toString),
        a.rationale
      )
    )
    val emptySearch = assessments.isEmpty
    val outcome = stages.model(
      stage,
      1,
      assessments.map(a => Ref(RecordKind.assessment, a.id, a.version)) :+ questionRef,
      StageCall.hash(marker, prompt.promptVersion),
      prompt.promptVersion,
      Configured.modelName,
      "high",
      timeout
    ) {
      client
        .forAgent(SessionId(marker))
        .call(EvidenceAgent.judgeSufficiency)
        .invokeAsync(SufficiencyRequest(q.id, marker, q.question, decision, forModel, emptySearch))
    }
    outcome match
      case StageOutcome.Skipped(_) =>
        stepEffects
          .updateState(currentState.copy(status = currentState.resting))
          .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)
      case StageOutcome.Done(attempt, proposal, modelCall) =>
        ApplicabilityRules.parseSufficiency(proposal) match
          case Left(defect) =>
            stages.fail(stage, attempt, defect, modelCalls = Vector(modelCall))
            retryOrGiveUp(
              stage,
              defect,
              QuestionInvestigationWorkflow.sufficiencyStep.withInput(decision)
            )
          case Right((status, coverage, escalation, gaps)) =>
            // FR-025 (b): a record that is the only affirmative evidence waits for a reviewer, and so does the judgement.
            val affirmative = assessments.filter(a =>
              a.relationship == Relationship.supports && a.applicability == Applicability.direct
            )
            val (finalStatus, soleRefs) =
              if affirmative.size == 1 && status == SufficiencyStatus.sufficient_for_proposed_next_step
              then
                val sole = affirmative.head
                sole.recordRef.foreach { ref =>
                  Records.companionFor(ref.id).foreach { c =>
                    try
                      client
                        .forEventSourcedEntity(EntityId(ref.id))
                        .call(c.review)
                        .invoke(
                          ReviewRecord(
                            "complain_citation",
                            "operator",
                            "sole affirmative evidence for a question (FR-025 b)"
                          )
                        )
                    catch case _: CommandError => ()
                  }
                }
                (SufficiencyStatus.requires_expert_review, Vector(sole.id))
              else (status, Vector.empty)
            val offset = currentState.gapIds.size + currentState.coverageGapIds.size
            val gapIds = gaps.zipWithIndex.map { case ((description, kind, esc), i) =>
              val id = GapEntity.idFor(currentState.questionId, offset + i + 1)
              client
                .forEventSourcedEntity(EntityId(id))
                .call(GapEntity.open)
                .invoke(OpenGap(questionRef, description, kind, esc))
              id
            }
            val sufficiencyId = SufficiencyEntity.idFor(currentState.questionId, decision)
            val judged = client
              .forEventSourcedEntity(EntityId(sufficiencyId))
              .call(SufficiencyEntity.judge)
              .invoke(
                JudgeSufficiency(
                  questionRef,
                  decision,
                  proposal.proposition,
                  finalStatus,
                  proposal.assumptions ++ soleRefs
                    .map(s => s"the sole affirmative assessment $s awaits a reviewer"),
                  assessments.map(a => Ref(RecordKind.assessment, a.id, a.version)),
                  gapIds ++ currentState.coverageGapIds,
                  coverage,
                  proposal.rationale,
                  escalation,
                  s"model:${prompt.promptVersion}",
                  assessments.map(a => Ref(RecordKind.assessment, a.id, a.version)) :+ questionRef
                )
              )
            val questionStatus = finalStatus match
              case SufficiencyStatus.requires_expert_review => QuestionStatus.requires_review
              case _                                        => QuestionStatus.partial
            try
              client
                .forEventSourcedEntity(EntityId(currentState.questionId))
                .call(QuestionEntity.advance)
                .invoke(
                  AdvanceQuestion(
                    questionStatus,
                    Some(Ref(RecordKind.sufficiency, sufficiencyId, judged.version)),
                    None,
                    gaps.map(_._1)
                  )
                )
            catch case _: CommandError => ()
            stages.complete(
              stage,
              attempt,
              Some(Ref(RecordKind.sufficiency, sufficiencyId, judged.version)),
              modelCalls = Vector(modelCall)
            )
            val next = currentState.copy(
              status = currentState.resting,
              sufficiencyIds = (currentState.sufficiencyIds :+ sufficiencyId).distinct,
              gapIds = currentState.gapIds ++ gapIds,
              emptySearch = emptySearch
            )
            // A judgement that asks for more searching gets one bounded round, linked to the gap.
            val supplementalGap = Option
              .when(escalation == Escalation.supplemental_search)(
                (gapIds ++ currentState.coverageGapIds).find(withinBudget)
              )
              .flatten
            supplementalGap match
              case Some(gapId) =>
                stepEffects
                  .updateState(next.copy(status = "supplemental", supplementalFor = Some(gapId)))
                  .thenTransitionTo(QuestionInvestigationWorkflow.executeStep.ref)
              case None =>
                stepEffects
                  .updateState(next)
                  .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)
      case StageOutcome.Failed(_, defect, _) =>
        retryOrGiveUp(
          stage,
          defect,
          QuestionInvestigationWorkflow.sufficiencyStep.withInput(decision)
        )

  private def withinBudget(gapId: String): Boolean =
    val g = client.forEventSourcedEntity(EntityId(gapId)).call(GapEntity.get).invoke()
    g.registered && g.status != GapStatus.closed &&
    g.supplementalExecutionIds.size < Configured.supplementalBudget

  private def retryOrGiveUp(
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
          currentState.copy(
            status = currentState.resting,
            failure = Some(s"$stage: ${defect.kind} (${defect.detail})")
          )
        )
        .thenTransitionTo(QuestionInvestigationWorkflow.waiting.ref)

object QuestionInvestigationWorkflow
    extends Workflow.Companion[QuestionInvestigationWorkflow, QuestionInvestigationState](
      componentId = ComponentId("question-investigation"),
      stateSerializer =
        Codecs.serializer[QuestionInvestigationState]("question-investigation-state")
    ):

  given Serializer[StartQuestionInvestigation] =
    Codecs.serializer[StartQuestionInvestigation]("start-question-investigation")
  given Serializer[AssessRecords] = Codecs.serializer[AssessRecords]("assess-records")
  given Serializer[ExecutePlan]   = Codecs.serializer[ExecutePlan]("execute-plan")

  def create(context: WorkflowContext) = new QuestionInvestigationWorkflow(context)

  /** The adapters that reach the network; the replay tier records them as not searched. */
  val network: Set[String] = Set("europepmc", "pubmed", "crossref")

  /** The plan gate: known kinds, both kinds present, a known escalation. */
  def gate(p: SearchPlanProposal): Either[Defect, (Vector[Query], Escalation)] =
    val queries = p.queries.flatMap(q =>
      QueryKind.values
        .find(_.toString == q.kind.trim.toLowerCase)
        .map(k => Query(k, q.text.trim, q.expansionOf))
    )
    if queries.size != p.queries.size then
      Left(
        Rules.defect(
          DefectKind.schema_invalid,
          "a query kind is not lexical or semantic",
          retryable = true
        )
      )
    else if queries.exists(_.text.isEmpty) then
      Left(Rules.defect(DefectKind.schema_invalid, "a query has no text", retryable = true))
    else if !SearchPlan.twoKinds(queries) then
      Left(
        Rules.defect(
          DefectKind.schema_invalid,
          "a plan has at least one lexical and one semantic query",
          retryable = true
        )
      )
    else if p.targetSources.forall(_.isBlank) then
      Left(
        Rules.defect(DefectKind.schema_invalid, "a plan names its target sources", retryable = true)
      )
    else if p.stoppingCriterion.isBlank then
      Left(
        Rules.defect(
          DefectKind.schema_invalid,
          "a plan states its stopping criterion",
          retryable = true
        )
      )
    else
      Right(
        (
          queries,
          Escalation.values
            .find(_.toString == p.escalation.trim.replace(' ', '_'))
            .getOrElse(Escalation.supplemental_search)
        )
      )

  val waiting         = step("waiting")(_.awaitStep)
  val planStep        = step("plan")(_.planStepRun)
  val executeStep     = step("execute")(_.executeStepRun)
  val executeFailed   = step("execute-failed")(_.executeFailedStep)
  val eligibilityStep = step("eligibility")(_.eligibilityStepRun)
  val resolveStep     = step("resolve")(_.resolveStepRun)
  val assessStep      = step("assess")(_.assessStepRun)
  val sufficiencyStep = step("sufficiency")(_.sufficiencyStepRun)

  val start        = command("start")(_.start)
  val replan       = command("plan")(_.replan)
  val execute      = command("execute")(_.execute)
  val supplemental = command("supplemental")(_.supplemental)
  val assess       = command("assess")(_.assess)
  val judge        = command("judge")(_.judge)
  val status       = query("status")(_.status)
