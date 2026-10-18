package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.SufficiencyCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.provenance.ReviewFlag
import com.thinkmorestupidless.experimentproposals.domain.request.FlagForReview
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** Sufficiency for one named decision (FR-031, FR-032): each judgement is a new version. */
final class SufficiencyEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[EvidenceSufficiency, SufficiencyEvent]:

  def emptyState: EvidenceSufficiency = EvidenceSufficiency.empty(context.entityId)

  def applyEvent(event: SufficiencyEvent): EvidenceSufficiency = event match
    case SufficiencyEvent.Judged(
          q,
          decision,
          proposition,
          status,
          assumptions,
          refs,
          gaps,
          coverage,
          rationale,
          escalation,
          by,
          dependsOn,
          _
        ) =>
      currentState.copy(
        registered = true,
        questionRef = Some(q),
        intendedDecision = decision,
        proposition = proposition,
        status = status,
        assumptions = assumptions,
        assessmentRefs = refs,
        criticalGapIds = gaps,
        coverageJudgement = coverage,
        rationale = rationale,
        escalation = escalation,
        judgedBy = by,
        dependsOn = dependsOn,
        version = currentState.version + 1
      )
    case SufficiencyEvent.FlaggedForReview(ref, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(ref, reason, at))

  def judge(request: JudgeSufficiency): Effect[EvidenceSufficiency] =
    if request.intendedDecision.isBlank then
      effects.error("a sufficiency judgement names its decision")
    else if request.status != SufficiencyStatus.requires_expert_review && request.proposition.isBlank
    then effects.error("a judgement states its proposition")
    else
      effects
        .persist(
          SufficiencyEvent.Judged(
            request.questionRef,
            request.intendedDecision,
            request.proposition,
            request.status,
            request.assumptions,
            request.assessmentRefs,
            request.criticalGapIds,
            request.coverageJudgement,
            request.rationale,
            request.escalation,
            request.judgedBy,
            request.dependsOn,
            Instant.now()
          )
        )
        .thenReplyState

  def flagForReview(request: FlagForReview): Effect[Done] =
    if currentState.flags.exists(_.changedRef == request.changedRef) then effects.reply(Done)
    else
      effects
        .persist(
          SufficiencyEvent.FlaggedForReview(request.changedRef, request.reason, Instant.now())
        )
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[EvidenceSufficiency] = effects.reply(currentState)

object SufficiencyEntity
    extends EventSourcedEntity.Companion[SufficiencyEntity, EvidenceSufficiency, SufficiencyEvent](
      componentId = ComponentId("sufficiency"),
      stateSerializer = summon[Serializer[EvidenceSufficiency]],
      eventSerializer = summon[Serializer[SufficiencyEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new SufficiencyEntity(context)

  def idFor(questionId: String, decision: String): String =
    s"$questionId:${EvidenceSufficiency.slug(decision)}"

  val judge         = command("judge")(_.judge)
  val flagForReview = command("flag-for-review")(_.flagForReview)
  val get           = query("get")(_.get)

/** A knowledge gap with its bounded supplemental search budget (FR-032). */
final class GapEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[KnowledgeGap, GapEvent]:

  def emptyState: KnowledgeGap = KnowledgeGap.empty(context.entityId)

  def applyEvent(event: GapEvent): KnowledgeGap = event match
    case GapEvent.Opened(q, description, kind, escalation, _) =>
      currentState.copy(
        registered = true,
        questionRef = Some(q),
        description = description,
        kind = kind,
        escalation = escalation,
        status = GapStatus.open,
        version = 1
      )
    case GapEvent.SupplementalSearched(id, _) =>
      currentState.copy(
        supplementalExecutionIds = currentState.supplementalExecutionIds :+ id,
        status = GapStatus.searched,
        version = currentState.version + 1
      )
    case GapEvent.Escalated(escalation, _) =>
      currentState.copy(escalation = escalation, version = currentState.version + 1)
    case GapEvent.Closed(_, _) =>
      currentState.copy(status = GapStatus.closed, version = currentState.version + 1)

  def open(request: OpenGap): Effect[KnowledgeGap] =
    if currentState.registered then effects.reply(currentState)
    else if request.description.isBlank then effects.error("a gap says what is missing")
    else
      effects
        .persist(
          GapEvent.Opened(
            request.questionRef,
            request.description,
            request.kind,
            request.escalation,
            Instant.now()
          )
        )
        .thenReplyState

  def recordSupplemental(executionId: String): Effect[Done] =
    if !currentState.registered then effects.error("no such gap", ErrorCode.NotFound)
    else if currentState.supplementalExecutionIds.contains(executionId) then effects.reply(Done)
    else if currentState.supplementalExecutionIds.size >= Configured.supplementalBudget then
      effects.error(
        s"the supplemental search budget (${Configured.supplementalBudget}) for this gap is spent",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(GapEvent.SupplementalSearched(executionId, Instant.now()))
        .thenReply(_ => Done)

  def escalate(escalation: Escalation): Effect[Done] =
    if currentState.escalation == escalation then effects.reply(Done)
    else effects.persist(GapEvent.Escalated(escalation, Instant.now())).thenReply(_ => Done)

  def close(reason: String): Effect[Done] =
    if currentState.status == GapStatus.closed then effects.reply(Done)
    else effects.persist(GapEvent.Closed(reason, Instant.now())).thenReply(_ => Done)

  def get: ReadOnlyEffect[KnowledgeGap] = effects.reply(currentState)

object GapEntity
    extends EventSourcedEntity.Companion[GapEntity, KnowledgeGap, GapEvent](
      componentId = ComponentId("gap"),
      stateSerializer = summon[Serializer[KnowledgeGap]],
      eventSerializer = summon[Serializer[GapEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new GapEntity(context)

  def idFor(questionId: String, n: Int): String = s"$questionId:g$n"

  val open               = command("open")(_.open)
  val recordSupplemental = command("record-supplemental")(_.recordSupplemental)
  val escalate           = command("escalate")(_.escalate)
  val close              = command("close")(_.close)
  val get                = query("get")(_.get)
