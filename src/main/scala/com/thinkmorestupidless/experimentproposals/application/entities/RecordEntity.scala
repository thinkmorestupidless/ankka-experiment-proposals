package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordEvent.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{ReviewAction, ReviewFlag}
import com.thinkmorestupidless.experimentproposals.domain.request.FlagForReview
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * A finding or a claim (FR-020 to FR-027, FR-046): proposed, validated, verified, reviewed, retried
 * within a budget or quarantined, never edited. Every attempt is an event, so history is kept. The
 * class is hosted twice, as `finding` and as `claim`, by the two companions below.
 */
final class RecordEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[EvidenceRecord, RecordEvent]:

  def emptyState: EvidenceRecord = EvidenceRecord.empty(context.entityId)

  def applyEvent(event: RecordEvent): EvidenceRecord = event match
    case Proposed(kind, sourceRef, content, spans, by, dependsOn, at) =>
      currentState.copy(
        registered = true,
        kind = kind,
        sourceRef = Some(sourceRef),
        content = Some(content),
        spans = spans,
        generatedBy = by,
        dependsOn = dependsOn,
        status = RecordStatus.proposed,
        attempts = Vector(Attempt(1, content, spans, by, None, at = at)),
        version = 1
      )
    case Validated(attempt, result) => updateAttempt(attempt)(_.copy(validation = Some(result)))
    case Verified(attempt, v, _) =>
      updateAttempt(attempt)(_.copy(verification = Some(v), outcome = Some(RecordStatus.verified)))
        .copy(status = RecordStatus.verified, defect = None)
    case Rejected(attempt, defect, v, _) =>
      updateAttempt(attempt)(
        _.copy(verification = v, outcome = Some(RecordStatus.rejected), defect = Some(defect))
      ).copy(status = RecordStatus.rejected, defect = Some(defect))
    case RequiresReview(attempt, _, v, _) =>
      updateAttempt(attempt)(_.copy(verification = v, outcome = Some(RecordStatus.requires_review)))
        .copy(status = RecordStatus.requires_review)
    case Quarantined(attempt, defect, _) =>
      updateAttempt(attempt)(
        _.copy(outcome = Some(RecordStatus.quarantined), defect = Some(defect))
      ).copy(status = RecordStatus.quarantined, defect = Some(defect))
    case Reproposed(attempt, content, spans, triggeredBy, by, at) =>
      currentState.copy(
        content = Some(content),
        spans = spans,
        status = RecordStatus.proposed,
        defect = None,
        attempts =
          currentState.attempts :+ Attempt(attempt, content, spans, by, Some(triggeredBy), at = at),
        version = currentState.version + 1
      )
    case Reviewed(action, by, at, rationale, statusAfter) =>
      currentState.copy(
        status = statusAfter,
        reviews = currentState.reviews :+ ReviewAction(by, action, at, rationale),
        version = currentState.version + 1
      )
    case FlaggedForReview(changedRef, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(changedRef, reason, at))

  private def updateAttempt(n: Int)(f: Attempt => Attempt): EvidenceRecord =
    currentState.copy(attempts = currentState.attempts.map(a => if a.n == n then f(a) else a))

  private def openAttempt(n: Int): Boolean =
    currentState.attempts.exists(a => a.n == n && a.outcome.isEmpty)

  def propose(request: ProposeRecord): Effect[EvidenceRecord] =
    if currentState.registered then
      effects.error("the record is already proposed", ErrorCode.Conflict)
    else if request.spans.isEmpty then
      effects.error("a finding or claim cites at least one source span")
    else
      effects
        .persist(
          Proposed(
            request.kind,
            request.sourceRef,
            request.content,
            request.spans,
            request.generatedBy,
            request.dependsOn,
            Instant.now()
          )
        )
        .thenReplyState

  def recordValidation(request: RecordValidation): Effect[Done] =
    if !openAttempt(request.attempt) then
      effects.error(s"no open attempt ${request.attempt}", ErrorCode.Conflict)
    else effects.persist(Validated(request.attempt, request.result)).thenReply(_ => Done)

  def verified(request: RecordVerified): Effect[Done] =
    if !openAttempt(request.attempt) then
      effects.error(s"no open attempt ${request.attempt}", ErrorCode.Conflict)
    else if currentState.isHypothesis then
      effects.error(
        "a system hypothesis is never verified; it is tested by a design",
        ErrorCode.Conflict
      )
    else if request.verification.checks.values
        .exists(!_.passed) || request.verification.label != VerificationLabel.entailed
    then
      effects.error(
        "a verification with a failed check or a label other than entailed cannot verify",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(Verified(request.attempt, request.verification, Instant.now()))
        .thenReply(_ => Done)

  def rejected(request: RecordRejected): Effect[Done] =
    if !openAttempt(request.attempt) then
      effects.error(s"no open attempt ${request.attempt}", ErrorCode.Conflict)
    else
      effects
        .persist(Rejected(request.attempt, request.defect, request.verification, Instant.now()))
        .thenReply(_ => Done)

  def requiresReview(request: RecordRequiresReview): Effect[Done] =
    if !openAttempt(request.attempt) then
      effects.error(s"no open attempt ${request.attempt}", ErrorCode.Conflict)
    else
      effects
        .persist(
          RequiresReview(request.attempt, request.reason, request.verification, Instant.now())
        )
        .thenReply(_ => Done)

  def quarantine(request: QuarantineRecord): Effect[Done] =
    if currentState.status == RecordStatus.quarantined then effects.reply(Done)
    else if !openAttempt(request.attempt) && currentState.attempts.exists(_.n == request.attempt)
    then
      effects
        .persist(Quarantined(request.attempt, request.defect, Instant.now()))
        .thenReply(_ => Done)
    else if openAttempt(request.attempt) then
      effects
        .persist(Quarantined(request.attempt, request.defect, Instant.now()))
        .thenReply(_ => Done)
    else effects.error(s"no attempt ${request.attempt}", ErrorCode.Conflict)

  /** A retry is a new attempt on the same id, within the budget (FR-026). */
  def repropose(request: ReproposeRecord): Effect[EvidenceRecord] =
    if !currentState.registered then effects.error("no such record", ErrorCode.NotFound)
    else if currentState.status == RecordStatus.proposed then
      effects.error("the current attempt is still open", ErrorCode.Conflict)
    else if currentState.attempts.size > Configured.retryBudget then
      effects.error(
        s"the retry budget (${Configured.retryBudget}) is spent; quarantine instead",
        ErrorCode.Conflict
      )
    else if request.spans.isEmpty then
      effects.error("a finding or claim cites at least one source span")
    else
      effects
        .persist(
          Reproposed(
            currentState.attempts.size + 1,
            request.content,
            request.spans,
            request.triggeredBy,
            request.generatedBy,
            Instant.now()
          )
        )
        .thenReplyState

  def review(request: ReviewRecord): Effect[EvidenceRecord] =
    Configured.reviewers.check(request.by) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("no such record", ErrorCode.NotFound)
      case Right(_) =>
        val after = (currentState.status, request.action) match
          case (RecordStatus.requires_review, "verify") if !currentState.isHypothesis =>
            Some((ReviewActionKind.verify, RecordStatus.verified))
          case (RecordStatus.requires_review, "reject") =>
            Some((ReviewActionKind.reject, RecordStatus.rejected))
          case (s, "complain_citation") if s != RecordStatus.quarantined =>
            Some((ReviewActionKind.complain_citation, RecordStatus.requires_review))
          case _ => None
        after match
          case None =>
            effects.error(
              s"a record that is ${currentState.status} cannot be ${request.action}",
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

  def get: ReadOnlyEffect[EvidenceRecord] = effects.reply(currentState)

/** The handler set both companions declare. */
abstract class RecordCompanion(id: String)
    extends EventSourcedEntity.Companion[RecordEntity, EvidenceRecord, RecordEvent](
      componentId = ComponentId(id),
      stateSerializer = summon[Serializer[EvidenceRecord]],
      eventSerializer = summon[Serializer[RecordEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new RecordEntity(context)

  val propose          = command("propose")(_.propose)
  val recordValidation = command("record-validation")(_.recordValidation)
  val verified         = command("verified")(_.verified)
  val rejected         = command("rejected")(_.rejected)
  val requiresReview   = command("requires-review")(_.requiresReview)
  val quarantine       = command("quarantine")(_.quarantine)
  val repropose        = command("repropose")(_.repropose)
  val review           = command("review")(_.review)
  val flagForReview    = command("flag-for-review")(_.flagForReview)
  val get              = query("get")(_.get)

object FindingEntity extends RecordCompanion("finding"):
  def idFor(sourceId: String, version: Int, n: Int): String = s"$sourceId:v$version:fd$n"

object ClaimEntity extends RecordCompanion("claim"):
  def idFor(sourceId: String, version: Int, n: Int): String = s"$sourceId:v$version:cl$n"
  def idForReviewerClaim(sourceId: String, version: Int, short: String): String =
    s"$sourceId:v$version:clr-$short"

object Records:
  /** The companion a record id belongs to: findings carry `:fd`, claims `:cl`. */
  def companionFor(id: String): Option[RecordCompanion] =
    if id.matches(".*:fd\\d+$") then Some(FindingEntity)
    else if id.matches(".*:cl.*") then Some(ClaimEntity)
    else None
