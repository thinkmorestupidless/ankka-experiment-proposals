package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.design.ApproachCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.provenance.{ReviewAction, ReviewFlag}
import com.thinkmorestupidless.experimentproposals.domain.request.FlagForReview
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** One candidate approach (FR-035 to FR-037): proposed, compared, then selected or rejected. */
final class ApproachEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ExperimentalApproach, ApproachEvent]:

  def emptyState: ExperimentalApproach = ExperimentalApproach.empty(context.entityId)

  def applyEvent(event: ApproachEvent): ExperimentalApproach = event match
    case ApproachEvent.Proposed(req, packages, c, by, dependsOn, _) =>
      currentState.copy(
        registered = true,
        requestRef = Some(req),
        packageRefs = packages,
        title = c.title,
        hypothesis = c.hypothesis,
        rationale = c.rationale,
        facetRefsAddressed = c.facetRefsAddressed,
        facetRefsNotAddressed = c.facetRefsNotAddressed,
        affirmativeClaimRefs = c.affirmativeClaimRefs,
        contraryClaimRefs = c.contraryClaimRefs,
        indirectClaimRefs = c.indirectClaimRefs,
        missingEvidence = c.missingEvidence,
        biologicalModel = c.biologicalModel,
        modelLimitations = c.modelLimitations,
        endpoints = c.endpoints,
        measurements = c.measurements,
        controls = c.controls,
        resources = c.resources,
        safetyRegulatory = c.safetyRegulatory,
        assumptions = c.assumptions,
        uncertainties = c.uncertainties,
        failureModes = c.failureModes,
        possibleResults = c.possibleResults,
        prerequisites = c.prerequisites,
        proposedBy = by,
        dependsOn = dependsOn,
        status = ApproachStatus.proposed,
        version = 1
      )
    case ApproachEvent.Compared(id, _) =>
      currentState.copy(comparisonId = Some(id), version = currentState.version + 1)
    case ApproachEvent.Selected(_, decisionId, _) =>
      currentState.copy(
        status = ApproachStatus.selected,
        decisionId = Some(decisionId),
        version = currentState.version + 1
      )
    case ApproachEvent.RejectedInDecision(_, decisionId, reason, _) =>
      currentState.copy(
        status = ApproachStatus.rejected,
        decisionId = Some(decisionId),
        rejectionReason = Some(reason),
        version = currentState.version + 1
      )
    case ApproachEvent.Reviewed(action, status) =>
      currentState.copy(
        reviews = currentState.reviews :+ action,
        status = status,
        version = currentState.version + 1
      )
    case ApproachEvent.FlaggedForReview(ref, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(ref, reason, at))

  def propose(request: ProposeApproach): Effect[ExperimentalApproach] =
    if currentState.registered then effects.reply(currentState)
    else if request.content.hypothesis.isBlank || request.content.biologicalModel.isBlank then
      effects.error("an approach states its hypothesis and its biological model")
    else
      effects
        .persist(
          ApproachEvent.Proposed(
            request.requestRef,
            request.packageRefs,
            request.content,
            request.proposedBy,
            request.dependsOn,
            Instant.now()
          )
        )
        .thenReplyState

  def compared(comparisonId: String): Effect[Done] =
    if !currentState.registered then effects.error("no such approach", ErrorCode.NotFound)
    else if currentState.comparisonId.contains(comparisonId) then effects.reply(Done)
    else effects.persist(ApproachEvent.Compared(comparisonId, Instant.now())).thenReply(_ => Done)

  def selected(request: SelectedIn): Effect[Done] =
    if !currentState.registered then effects.error("no such approach", ErrorCode.NotFound)
    else if currentState.decisionId.contains(request.decisionId) then effects.reply(Done)
    else if currentState.decisionId.isDefined then
      effects.error("the approach is already in a decision", ErrorCode.Conflict)
    else
      effects
        .persist(ApproachEvent.Selected(request.by, request.decisionId, Instant.now()))
        .thenReply(_ => Done)

  def rejectedInDecision(request: RejectedIn): Effect[Done] =
    if !currentState.registered then effects.error("no such approach", ErrorCode.NotFound)
    else if currentState.decisionId.contains(request.decisionId) then effects.reply(Done)
    else if currentState.decisionId.isDefined then
      effects.error("the approach is already in a decision", ErrorCode.Conflict)
    else if request.reason.isBlank then effects.error("a rejected approach keeps its reason")
    else
      effects
        .persist(
          ApproachEvent
            .RejectedInDecision(request.by, request.decisionId, request.reason, Instant.now())
        )
        .thenReply(_ => Done)

  def review(request: ReviewApproach): Effect[ExperimentalApproach] =
    Configured.reviewers.check(request.reviewer) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("no such approach", ErrorCode.NotFound)
      case Right(reviewer) =>
        val kind = ReviewActionKind.values.find(_.toString == request.action)
        kind match
          case None => effects.error(s"unknown review action '${request.action}'")
          case Some(k) =>
            val status = k match
              case ReviewActionKind.reject         => ApproachStatus.rejected
              case ReviewActionKind.mark_ambiguous => ApproachStatus.requires_review
              case _                               => currentState.status
            effects
              .persist(
                ApproachEvent.Reviewed(
                  ReviewAction(reviewer.name, k, Instant.now(), request.rationale),
                  status
                )
              )
              .thenReplyState

  def flagForReview(request: FlagForReview): Effect[Done] =
    if currentState.flags.exists(_.changedRef == request.changedRef) then effects.reply(Done)
    else
      effects
        .persist(ApproachEvent.FlaggedForReview(request.changedRef, request.reason, Instant.now()))
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[ExperimentalApproach] = effects.reply(currentState)

object ApproachEntity
    extends EventSourcedEntity.Companion[ApproachEntity, ExperimentalApproach, ApproachEvent](
      componentId = ComponentId("approach"),
      stateSerializer = summon[Serializer[ExperimentalApproach]],
      eventSerializer = summon[Serializer[ApproachEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new ApproachEntity(context)

  val propose            = command("propose")(_.propose)
  val compared           = command("compared")(_.compared)
  val selected           = command("selected")(_.selected)
  val rejectedInDecision = command("rejected-in-decision")(_.rejectedInDecision)
  val review             = command("review")(_.review)
  val flagForReview      = command("flag-for-review")(_.flagForReview)
  val get                = query("get")(_.get)

/** The decision record (FR-038): written once, by a configured reviewer. */
final class DecisionEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[DecisionRecord, DecisionEvent]:

  def emptyState: DecisionRecord = DecisionRecord.empty(context.entityId)

  def applyEvent(event: DecisionEvent): DecisionRecord = event match
    case DecisionEvent.Decided(req, comparison, selected, rejected, by, rationale, at) =>
      currentState.copy(
        registered = true,
        requestRef = Some(req),
        comparison = Some(comparison),
        selectedApproachRef = Some(selected),
        rejected = rejected,
        decidedBy = by,
        decidedAt = Some(at),
        rationale = rationale,
        version = 1
      )

  def decide(request: Decide): Effect[DecisionRecord] =
    if currentState.registered then
      effects.error("the decision is already recorded", ErrorCode.Conflict)
    else
      Configured.reviewers.check(request.decidedBy) match
        case Left(problem) => effects.error(problem)
        case Right(_) if request.rationale.isBlank =>
          effects.error("a decision states its rationale")
        case Right(_) if request.rejected.exists(_.reason.isBlank) =>
          effects.error("every rejected approach keeps its reason")
        case Right(_) if request.rejected.exists(_.approachRef == request.selectedApproachRef) =>
          effects.error("the selected approach cannot also be rejected")
        case Right(reviewer) =>
          effects
            .persist(
              DecisionEvent.Decided(
                request.requestRef,
                request.comparison,
                request.selectedApproachRef,
                request.rejected,
                reviewer.name,
                request.rationale,
                Instant.now()
              )
            )
            .thenReplyState

  def get: ReadOnlyEffect[DecisionRecord] = effects.reply(currentState)

object DecisionEntity
    extends EventSourcedEntity.Companion[DecisionEntity, DecisionRecord, DecisionEvent](
      componentId = ComponentId("decision"),
      stateSerializer = summon[Serializer[DecisionRecord]],
      eventSerializer = summon[Serializer[DecisionEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new DecisionEntity(context)

  val decide = command("decide")(_.decide)
  val get    = query("get")(_.get)
