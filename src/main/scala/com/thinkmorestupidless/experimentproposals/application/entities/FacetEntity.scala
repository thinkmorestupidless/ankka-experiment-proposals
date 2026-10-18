package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{ReviewAction, ReviewFlag}
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.request.FacetEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One facet (FR-002, FR-004): its review transitions and the reviewer check are about this one
 * record, and its journal is the history the feature asks to remain visible.
 */
final class FacetEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Facet, FacetEvent]:

  def emptyState: Facet = Facet.empty(context.entityId)

  def applyEvent(event: FacetEvent): Facet = event match
    case Proposed(requestId, name, statement, origin, importance, status, spans, dependencies, _) =>
      currentState.copy(
        registered = true,
        requestId = requestId,
        name = name,
        statement = statement,
        origin = origin,
        importance = importance,
        status = status,
        requestSpans = spans,
        dependencies = dependencies,
        version = 1
      )
    case Reviewed(action, by, at, rationale, statementAfter, statusAfter) =>
      val before = currentState
      val originAfter =
        if action == FacetAction.confirm || action == FacetAction.amend then
          FacetOrigin.human_confirmed
        else before.origin
      currentState.copy(
        statement = statementAfter.getOrElse(before.statement),
        origin = originAfter,
        status = statusAfter,
        reviews = before.reviews :+ ReviewAction(
          by,
          actionKind(action),
          at,
          rationale,
          Some(before.statement),
          statementAfter
        ),
        history =
          before.history :+ FacetHistory(before.statement, before.origin, before.status, at),
        version = before.version + 1
      )
    case AssumedWithApproval(value, by, at, rationale) =>
      val before = currentState
      currentState.copy(
        status = FacetStatus.assumed,
        assumedValue = Some(value),
        reviews = before.reviews :+ ReviewAction(
          by,
          ReviewActionKind.confirm,
          at,
          rationale,
          Some(before.statement),
          Some(value)
        ),
        history =
          before.history :+ FacetHistory(before.statement, before.origin, before.status, at),
        version = before.version + 1
      )
    case FlaggedForReview(changedRef, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(changedRef, reason, at))

  private def actionKind(action: FacetAction): ReviewActionKind = action match
    case FacetAction.confirm          => ReviewActionKind.confirm
    case FacetAction.amend            => ReviewActionKind.amend
    case FacetAction.reject           => ReviewActionKind.reject
    case FacetAction.leave_unresolved => ReviewActionKind.leave_unresolved
    case FacetAction.mark_ambiguous   => ReviewActionKind.mark_ambiguous

  def propose(request: ProposeFacet): Effect[Facet] =
    if currentState.registered then
      effects.error("the facet is already proposed", ErrorCode.Conflict)
    else if request.name.isBlank || request.statement.isBlank then
      effects.error("a facet needs a name and a statement")
    else
      effects
        .persist(
          Proposed(
            request.requestId,
            request.name,
            request.statement,
            request.origin,
            request.importance,
            request.status,
            request.spans,
            request.dependencies,
            Instant.now()
          )
        )
        .thenReplyState

  def review(request: ReviewFacet): Effect[Facet] =
    Configured.reviewers.check(request.by) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("no such facet", ErrorCode.NotFound)
      case Right(_) if request.action == FacetAction.amend && request.statement.forall(_.isBlank) =>
        effects.error("an amendment needs the new statement")
      case Right(_) =>
        FacetTransitions.after(currentState.status, request.action) match
          case None =>
            effects.error(
              s"a facet that is ${currentState.status} cannot be ${request.action}ed",
              ErrorCode.Conflict
            )
          case Some(after) =>
            effects
              .persist(
                Reviewed(
                  request.action,
                  request.by,
                  Instant.now(),
                  request.rationale,
                  request.statement.map(_.trim),
                  after
                )
              )
              .thenReplyState

  def assume(request: AssumeFacet): Effect[Facet] =
    Configured.reviewers.check(request.by) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("no such facet", ErrorCode.NotFound)
      case Right(_) if currentState.importance != Importance.mandatory =>
        effects.error(
          "only a mandatory facet is assumed with approval; confirm or amend the others",
          ErrorCode.Conflict
        )
      case Right(_) if !FacetTransitions.canAssume(currentState.status) =>
        effects.error(
          s"a facet that is ${currentState.status} cannot be assumed",
          ErrorCode.Conflict
        )
      case Right(_) =>
        effects
          .persist(AssumedWithApproval(request.value, request.by, Instant.now(), request.rationale))
          .thenReplyState

  def flagForReview(request: FlagForReview): Effect[Done] =
    if currentState.flags.exists(_.changedRef == request.changedRef) then effects.reply(Done)
    else
      effects
        .persist(FlaggedForReview(request.changedRef, request.reason, Instant.now()))
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[Facet] = effects.reply(currentState)

object FacetEntity
    extends EventSourcedEntity.Companion[FacetEntity, Facet, FacetEvent](
      componentId = ComponentId("facet"),
      stateSerializer = summon[Serializer[Facet]],
      eventSerializer = summon[Serializer[FacetEvent]]
    ):

  def create(context: EventSourcedEntityContext) = new FacetEntity(context)

  def idFor(requestId: String, n: Int): String = s"$requestId:f$n"

  val propose       = command("propose")(_.propose)
  val review        = command("review")(_.review)
  val assume        = command("assume")(_.assume)
  val flagForReview = command("flag-for-review")(_.flagForReview)
  val get           = query("get")(_.get)
