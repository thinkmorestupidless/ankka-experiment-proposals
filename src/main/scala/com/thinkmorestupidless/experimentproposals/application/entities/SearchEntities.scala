package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.domain.search.*
import com.thinkmorestupidless.experimentproposals.domain.search.SearchCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** The per-question search plan (FR-015): each plan is a new version behind the two-kinds gate. */
final class SearchPlanEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[SearchPlan, SearchPlanEvent]:

  def emptyState: SearchPlan = SearchPlan.empty(context.entityId)

  def applyEvent(event: SearchPlanEvent): SearchPlan = event match
    case SearchPlanEvent.Planned(q, queries, targets, filters, params, stop, esc, exp, by, _) =>
      currentState.copy(
        registered = true,
        questionRef = Some(q),
        queries = queries,
        targetSources = targets,
        filters = filters,
        parameters = params,
        stoppingCriterion = stop,
        escalation = esc,
        expansions = exp,
        plannedBy = by,
        version = currentState.version + 1
      )
    case SearchPlanEvent.ExecutionRecorded(id, _) =>
      currentState.copy(executionIds = (currentState.executionIds :+ id).distinct)

  // docs:start plan-invariant
  def plan(request: RecordPlan): Effect[SearchPlan] =
    if request.queries.isEmpty then effects.error("a plan has at least one query")
    else if !SearchPlan.twoKinds(request.queries) then
      effects.error("a plan has at least one lexical and one semantic query")
    else if request.targetSources.isEmpty then effects.error("a plan names its target sources")
    else if request.stoppingCriterion.isBlank then effects.error("a plan states when to stop")
    else
      effects
        .persist(
          SearchPlanEvent.Planned(
            request.questionRef,
            request.queries,
            request.targetSources,
            request.filters,
            request.parameters,
            request.stoppingCriterion,
            request.escalation,
            request.expansions,
            request.plannedBy,
            Instant.now()
          )
        )
        .thenReplyState
  // docs:end plan-invariant

  def recordExecution(executionId: String): Effect[Done] =
    if !currentState.registered then effects.error("no such plan", ErrorCode.NotFound)
    else if currentState.executionIds.contains(executionId) then effects.reply(Done)
    else
      effects
        .persist(SearchPlanEvent.ExecutionRecorded(executionId, Instant.now()))
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[SearchPlan] = effects.reply(currentState)

object SearchPlanEntity
    extends EventSourcedEntity.Companion[SearchPlanEntity, SearchPlan, SearchPlanEvent](
      componentId = ComponentId("search-plan"),
      stateSerializer = summon[Serializer[SearchPlan]],
      eventSerializer = summon[Serializer[SearchPlanEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new SearchPlanEntity(context)

  val plan            = command("plan")(_.plan)
  val recordExecution = command("record-execution")(_.recordExecution)
  val get             = query("get")(_.get)

/**
 * One search execution (FR-016, FR-017): written once with everything it returned and the snapshot
 * searched; eligibility is applied afterwards as its own event, never by the ranking.
 */
final class SearchExecutionEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[SearchExecution, SearchExecutionEvent]:

  def emptyState: SearchExecution = SearchExecution.empty(context.entityId)

  def applyEvent(event: SearchExecutionEvent): SearchExecution = event match
    case SearchExecutionEvent.Executed(
          planRef,
          questionId,
          query,
          fused,
          source,
          at,
          params,
          snapshot,
          results,
          error,
          rerunOf,
          gapId
        ) =>
      currentState.copy(
        registered = true,
        planRef = Some(planRef),
        questionId = questionId,
        query = query,
        fused = fused,
        source = source,
        executedAt = Some(at),
        parameters = params,
        snapshot = snapshot,
        results = results,
        error = error,
        rerunOf = rerunOf,
        gapId = gapId,
        version = 1
      )
    case SearchExecutionEvent.EligibilityApplied(eligible, exclusions, gap, _, _) =>
      currentState.copy(
        eligibleResults = eligible,
        exclusions = exclusions,
        coverageGap = gap,
        eligibilityApplied = true,
        version = currentState.version + 1
      )

  def execute(request: RecordExecution): Effect[SearchExecution] =
    if currentState.registered then effects.reply(currentState)
    else if request.source.isBlank then effects.error("an execution names its source")
    else
      effects
        .persist(
          SearchExecutionEvent.Executed(
            request.planRef,
            request.questionId,
            request.query,
            request.fused,
            request.source,
            Instant.now(),
            request.parameters,
            request.snapshot,
            request.results,
            request.error,
            request.rerunOf,
            request.gapId
          )
        )
        .thenReplyState

  def applyEligibility(request: ApplyEligibility): Effect[SearchExecution] =
    if !currentState.registered then effects.error("no such execution", ErrorCode.NotFound)
    else if currentState.eligibilityApplied then effects.reply(currentState)
    else
      effects
        .persist(
          SearchExecutionEvent.EligibilityApplied(
            request.eligibleResults,
            request.exclusions,
            request.coverageGap,
            request.ruleSetVersion,
            Instant.now()
          )
        )
        .thenReplyState

  def get: ReadOnlyEffect[SearchExecution] = effects.reply(currentState)

object SearchExecutionEntity
    extends EventSourcedEntity.Companion[
      SearchExecutionEntity,
      SearchExecution,
      SearchExecutionEvent
    ](
      componentId = ComponentId("search-execution"),
      stateSerializer = summon[Serializer[SearchExecution]],
      eventSerializer = summon[Serializer[SearchExecutionEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new SearchExecutionEntity(context)

  val execute          = command("execute")(_.execute)
  val applyEligibility = command("apply-eligibility")(_.applyEligibility)
  val get              = query("get")(_.get)
