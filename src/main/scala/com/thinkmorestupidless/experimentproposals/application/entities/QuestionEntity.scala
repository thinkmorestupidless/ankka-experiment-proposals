package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.ReviewFlag
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.request.QuestionCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.request.QuestionEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * A scientific question (FR-005 to FR-007): linked to facets, with the evidence it needs and the
 * fields an answer must carry. Its status advances only by a sufficiency judgement, and to
 * `answered` only with a reviewer's name beside one. Its missing information starts as everything
 * it needs.
 */
final class QuestionEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ScientificQuestion, QuestionEvent]:

  def emptyState: ScientificQuestion = ScientificQuestion.empty(context.entityId)

  def applyEvent(event: QuestionEvent): ScientificQuestion = event match
    case Derived(requestId, question, refs, names, needed, fields, criteria, _) =>
      currentState.copy(
        registered = true,
        requestId = requestId,
        question = question,
        facetRefs = refs,
        facetNames = names,
        evidenceNeeded = needed,
        answerFields = fields,
        sufficiencyCriteria = criteria,
        missingInformation = needed,
        version = 1
      )
    case PlanRecorded(planId) => currentState.copy(planId = Some(planId))
    case StatusAdvanced(status, byRef, _, missing, _) =>
      currentState.copy(
        status = status,
        advancedBy = byRef,
        missingInformation = missing,
        version = currentState.version + 1
      )
    case FlaggedForReview(changedRef, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(changedRef, reason, at))

  def derive(request: DeriveQuestion): Effect[ScientificQuestion] =
    if currentState.registered then
      effects.error("the question is already derived", ErrorCode.Conflict)
    else if request.question.isBlank then effects.error("a question needs its text")
    else if request.facetRefs.isEmpty then
      effects.error("a question investigates at least one facet")
    else
      effects
        .persist(
          Derived(
            request.requestId,
            request.question,
            request.facetRefs,
            request.facetNames,
            request.evidenceNeeded,
            request.answerFields,
            request.sufficiencyCriteria,
            Instant.now()
          )
        )
        .thenReplyState

  def recordPlan(planId: String): Effect[Done] =
    if !currentState.registered then effects.error("no such question", ErrorCode.NotFound)
    else if currentState.planId.contains(planId) then effects.reply(Done)
    else effects.persist(PlanRecorded(planId)).thenReply(_ => Done)

  def advance(request: AdvanceQuestion): Effect[ScientificQuestion] =
    if !currentState.registered then effects.error("no such question", ErrorCode.NotFound)
    else if !QuestionTransitions.allowed(currentState.status, request.status) then
      effects.error(
        s"a question that is ${currentState.status} does not become ${request.status}",
        ErrorCode.Conflict
      )
    else if request.status == QuestionStatus.answered && (request.byRef.isEmpty || request.by
        .forall(n => !Configured.reviewers.isListed(n)))
    then
      effects.error(
        "answered needs a sufficiency judgement and a configured reviewer's confirmation",
        ErrorCode.Conflict
      )
    else if currentState.status == request.status && currentState.missingInformation == request.missingInformation
    then effects.reply(currentState)
    else
      effects
        .persist(
          StatusAdvanced(
            request.status,
            request.byRef,
            request.by,
            request.missingInformation,
            Instant.now()
          )
        )
        .thenReplyState

  def flagForReview(request: FlagForReview): Effect[Done] =
    if currentState.flags.exists(_.changedRef == request.changedRef) then effects.reply(Done)
    else
      effects
        .persist(FlaggedForReview(request.changedRef, request.reason, Instant.now()))
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[ScientificQuestion] = effects.reply(currentState)

object QuestionEntity
    extends EventSourcedEntity.Companion[QuestionEntity, ScientificQuestion, QuestionEvent](
      componentId = ComponentId("question"),
      stateSerializer = summon[Serializer[ScientificQuestion]],
      eventSerializer = summon[Serializer[QuestionEvent]]
    ):

  def create(context: EventSourcedEntityContext) = new QuestionEntity(context)

  def idFor(requestId: String, n: Int): String = s"$requestId:q$n"

  val derive        = command("derive")(_.derive)
  val recordPlan    = command("record-plan")(_.recordPlan)
  val advance       = command("advance")(_.advance)
  val flagForReview = command("flag-for-review")(_.flagForReview)
  val get           = query("get")(_.get)
