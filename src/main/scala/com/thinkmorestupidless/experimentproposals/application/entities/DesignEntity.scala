package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.design.DesignCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * The request's experimental design (FR-039 to FR-042): each generation is a new version, an
 * approved version's content never changes, and only a configured reviewer approves, rejects or
 * asks for changes, from `under_review`.
 */
final class DesignEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[DesignState, DesignEvent]:

  def emptyState: DesignState = DesignState.empty(context.entityId)

  private def withStatus(version: Int, status: DesignStatus): DesignState =
    currentState.copy(versions =
      currentState.versions.map(v => if v.version == version then v.copy(status = status) else v)
    )

  def applyEvent(event: DesignEvent): DesignState = event match
    case DesignEvent.DraftGenerated(
          req,
          version,
          approach,
          decision,
          sections,
          checks,
          ruleSet,
          by,
          changes,
          at
        ) =>
      currentState.copy(
        registered = true,
        requestRef = Some(req),
        versions = currentState.versions :+ DesignVersion(
          version,
          approach,
          decision,
          sections,
          DesignStatus.draft,
          checks,
          ruleSet,
          at,
          by,
          changes
        )
      )
    case DesignEvent.Submitted(version, _, _) => withStatus(version, DesignStatus.under_review)
    case DesignEvent.Approved(version, by, at, why) =>
      withStatus(version, DesignStatus.approved).copy(approvals =
        currentState.approvals :+ Approval(version, ApprovalDecision.approved, by, at, why)
      )
    case DesignEvent.Rejected(version, by, at, why) =>
      withStatus(version, DesignStatus.rejected).copy(approvals =
        currentState.approvals :+ Approval(version, ApprovalDecision.rejected, by, at, why)
      )
    case DesignEvent.ChangesRequested(version, by, at, why, changes) =>
      withStatus(version, DesignStatus.changes_requested).copy(approvals =
        currentState.approvals :+ Approval(
          version,
          ApprovalDecision.changes_requested,
          by,
          at,
          why,
          changes
        )
      )
    case DesignEvent.Superseded(version, _, _) => withStatus(version, DesignStatus.superseded)
    case DesignEvent.FlaggedForReview(ref, version, reason, at) =>
      currentState.copy(flags = currentState.flags :+ DesignFlag(ref, version, reason, at))

  /** A new version; every earlier version that was not approved is superseded. */
  def generate(request: GenerateDesign): Effect[DesignState] =
    if DesignSections.missing(request.sections).nonEmpty then
      effects.error(
        s"a design has twelve sections; empty: ${DesignSections.missing(request.sections).mkString(", ")}"
      )
    else
      val version = currentState.versions.size + 1
      val at      = Instant.now()
      val superseded = currentState.versions
        .filter(v => v.status != DesignStatus.approved && v.status != DesignStatus.superseded)
        .map(v => DesignEvent.Superseded(v.version, version, at))
      effects
        .persistAll(
          superseded :+ DesignEvent.DraftGenerated(
            request.requestRef,
            version,
            request.approachRef,
            request.decisionRef,
            request.sections,
            request.checks,
            request.ruleSetVersion,
            request.generatedBy,
            request.changesApplied,
            at
          )
        )
        .thenReplyState

  def submit(request: SubmitDesign): Effect[DesignState] =
    currentState.version(request.version) match
      case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
      case Some(v) if v.status == DesignStatus.under_review => effects.reply(currentState)
      case Some(v) if !DesignTransitions.allowed(v.status, DesignStatus.under_review) =>
        effects.error(
          s"version ${request.version} is ${v.status}; only a draft goes under review",
          ErrorCode.Conflict
        )
      case Some(_) =>
        Configured.actor(request.by) match
          case Left(problem) => effects.error(problem)
          case Right(by) =>
            effects
              .persist(DesignEvent.Submitted(request.version, by, Instant.now()))
              .thenReplyState

  /**
   * The reviewer's decision on one version; the entity refuses anyone not on the configured list.
   */
  def recordApproval(request: RecordApproval): Effect[DesignState] =
    Configured.reviewers.check(request.reviewer) match
      case Left(problem) => effects.error(problem)
      case Right(reviewer) =>
        currentState.version(request.version) match
          case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
          case Some(v) if v.status != DesignStatus.under_review =>
            effects.error(
              s"version ${request.version} is ${v.status}, not under review",
              ErrorCode.Conflict
            )
          case Some(_) if request.rationale.isBlank =>
            effects.error("a decision on a design states its rationale")
          case Some(_)
              if request.decision == ApprovalDecision.changes_requested && request.requestedChanges
                .forall(_.isBlank) =>
            effects.error("requesting changes names the changes")
          case Some(_) =>
            val at = Instant.now()
            val event = request.decision match
              case ApprovalDecision.approved =>
                DesignEvent.Approved(request.version, reviewer.name, at, request.rationale)
              case ApprovalDecision.rejected =>
                DesignEvent.Rejected(request.version, reviewer.name, at, request.rationale)
              case ApprovalDecision.changes_requested =>
                DesignEvent.ChangesRequested(
                  request.version,
                  reviewer.name,
                  at,
                  request.rationale,
                  request.requestedChanges.filter(_.nonEmpty)
                )
            effects.persist(event).thenReplyState

  def flagForReview(request: FlagDesign): Effect[Done] =
    if currentState.flags.exists(f =>
        f.changedRef == request.changedRef && f.version == request.version
      )
    then effects.reply(Done)
    else
      effects
        .persist(
          DesignEvent
            .FlaggedForReview(request.changedRef, request.version, request.reason, Instant.now())
        )
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[DesignState] = effects.reply(currentState)

object DesignEntity
    extends EventSourcedEntity.Companion[DesignEntity, DesignState, DesignEvent](
      componentId = ComponentId("design"),
      stateSerializer = summon[Serializer[DesignState]],
      eventSerializer = summon[Serializer[DesignEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new DesignEntity(context)

  val generate       = command("generate")(_.generate)
  val submit         = command("submit")(_.submit)
  val recordApproval = command("record-approval")(_.recordApproval)
  val flagForReview  = command("flag-for-review")(_.flagForReview)
  val get            = query("get")(_.get)
