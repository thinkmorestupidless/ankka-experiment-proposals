package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.AssessmentCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.evidence.AssessmentEvent.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.provenance.{ReviewAction, ReviewFlag}
import com.thinkmorestupidless.experimentproposals.domain.request.FlagForReview
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One finding or claim judged against one question (FR-028, FR-029). The species rule is enforced
 * here as well as in the rule set: a record that still claims direct applicability across a species
 * mismatch is refused. No numeric score exists.
 */
final class AssessmentEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[EvidenceAssessment, AssessmentEvent]:

  def emptyState: EvidenceAssessment = EvidenceAssessment.empty(context.entityId)

  def applyEvent(event: AssessmentEvent): EvidenceAssessment = event match
    case Proposed(q, r, rel, app, dims, rationale, limitations, assessor, rules, dependsOn, _) =>
      currentState.copy(
        registered = true,
        questionRef = Some(q),
        recordRef = Some(r),
        relationship = rel,
        applicability = app,
        dimensions = dims,
        rationale = rationale,
        limitations = limitations,
        assessor = assessor,
        ruleResults = rules,
        dependsOn = dependsOn,
        reviewStatus = RecordStatus.proposed,
        version = 1
      )
    case Reviewed(action, by, at, rationale, after) =>
      currentState.copy(
        reviewStatus = after,
        reviews = currentState.reviews :+ ReviewAction(by, action, at, rationale),
        version = currentState.version + 1
      )
    case FlaggedForReview(ref, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(ref, reason, at))

  def propose(request: ProposeAssessment): Effect[EvidenceAssessment] =
    if currentState.registered then
      effects.error("the assessment is already proposed", ErrorCode.Conflict)
    else if request.dimensions
        .get("species")
        .contains(DimensionMatch.mismatch) && request.applicability == Applicability.direct
    then
      effects.error(
        "a species mismatch forbids direct applicability (the species rule)",
        ErrorCode.Conflict
      )
    else if request.rationale.isBlank then effects.error("an assessment states its rationale")
    else
      effects
        .persist(
          Proposed(
            request.questionRef,
            request.recordRef,
            request.relationship,
            request.applicability,
            request.dimensions,
            request.rationale,
            request.limitations,
            request.assessor,
            request.ruleResults,
            request.dependsOn,
            Instant.now()
          )
        )
        .thenReplyState

  def review(request: ReviewRecord): Effect[EvidenceAssessment] =
    Configured.reviewers.check(request.by) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("no such assessment", ErrorCode.NotFound)
      case Right(_) =>
        val after = (currentState.reviewStatus, request.action) match
          case (RecordStatus.proposed | RecordStatus.requires_review, "verify") =>
            Some((ReviewActionKind.verify, RecordStatus.verified))
          case (RecordStatus.proposed | RecordStatus.requires_review, "reject") =>
            Some((ReviewActionKind.reject, RecordStatus.rejected))
          case _ => None
        after match
          case None =>
            effects.error(
              s"an assessment that is ${currentState.reviewStatus} cannot be ${request.action}",
              ErrorCode.Conflict
            )
          case Some((kind, status)) =>
            effects
              .persist(Reviewed(kind, request.by, Instant.now(), request.rationale, status))
              .thenReplyState

  def flagForReview(request: FlagForReview): Effect[Done] =
    if currentState.flags.exists(_.changedRef == request.changedRef) then effects.reply(Done)
    else
      effects
        .persist(FlaggedForReview(request.changedRef, request.reason, Instant.now()))
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[EvidenceAssessment] = effects.reply(currentState)

object AssessmentEntity
    extends EventSourcedEntity.Companion[AssessmentEntity, EvidenceAssessment, AssessmentEvent](
      componentId = ComponentId("assessment"),
      stateSerializer = summon[Serializer[EvidenceAssessment]],
      eventSerializer = summon[Serializer[AssessmentEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new AssessmentEntity(context)

  def idFor(questionId: String, recordId: String): String = s"$questionId:$recordId"

  val propose       = command("propose")(_.propose)
  val review        = command("review")(_.review)
  val flagForReview = command("flag-for-review")(_.flagForReview)
  val get           = query("get")(_.get)
