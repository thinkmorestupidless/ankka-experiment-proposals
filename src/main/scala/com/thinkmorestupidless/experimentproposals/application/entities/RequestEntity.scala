package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.request.RequestCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.request.RequestEvent.*
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * The customer request (FR-001): its text is written once by `Submitted` and never changed. It
 * keeps the bounded list of its facets and questions, the latest round-trip check, and the
 * reviewer's confirmation, which it refuses while a mandatory facet is still only proposed.
 */
final class RequestEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[CustomerRequest, RequestEvent]:

  def emptyState: CustomerRequest = CustomerRequest.empty(context.entityId)

  def applyEvent(event: RequestEvent): CustomerRequest = event match
    case Submitted(text, by, at, hash) =>
      currentState.copy(
        registered = true,
        text = text,
        submittedBy = by,
        submittedAt = Some(at),
        contentHash = hash
      )
    case FacetSetProposed(facets, setVersion) =>
      currentState.copy(
        facets = facets,
        facetSetVersion = setVersion,
        status = RequestStatus.facets_proposed
      )
    case RoundTripChecked(result) =>
      currentState.copy(roundTrip = Some(result), roundTripRuns = result.run)
    case FacetsConfirmed(by, _) =>
      currentState.copy(confirmedBy = Some(by), status = RequestStatus.facets_confirmed)
    case QuestionsDerived(ids) =>
      currentState.copy(questionIds = ids, status = RequestStatus.questions_derived)
    case StatusChanged(status) => currentState.copy(status = status)

  def submit(request: SubmitRequest): Effect[CustomerRequest] =
    if currentState.registered then effects.error("the request already exists", ErrorCode.Conflict)
    else if request.text.isBlank then effects.error("the request text is empty")
    else
      effects
        .persist(
          Submitted(request.text, request.submittedBy, Instant.now(), Ids.sha256(request.text))
        )
        .thenReplyState

  def recordFacetSet(request: RecordFacetSet): Effect[Done] =
    if !currentState.registered then effects.error("no such request", ErrorCode.NotFound)
    else if currentState.facets == request.facets then effects.reply(Done)
    else
      effects
        .persist(FacetSetProposed(request.facets, currentState.facetSetVersion + 1))
        .thenReply(_ => Done)

  def recordRoundTrip(request: RecordRoundTrip): Effect[RoundTripResult] =
    if !currentState.registered then effects.error("no such request", ErrorCode.NotFound)
    else
      val c = request.check
      val passed =
        c.omissions.isEmpty && c.unsupportedAdditions.isEmpty && c.contradictions.isEmpty && c.ambiguities.isEmpty
      val result = RoundTripResult(
        c,
        passed,
        currentState.facetSetVersion,
        currentState.roundTripRuns + 1,
        request.promptVersion,
        Instant.now()
      )
      effects.persist(RoundTripChecked(result)).thenReply(_ => result)

  /**
   * Confirmed, assumed with approval, or unresolved and flagged: nothing mandatory stays
   * `proposed`.
   */
  def confirmFacets(request: ConfirmFacets): Effect[Done] =
    Configured.reviewers.check(request.by) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("no such request", ErrorCode.NotFound)
      case Right(_) =>
        val blocking = request.facets.filter(f =>
          f.importance == Importance.mandatory && f.status == FacetStatus.proposed
        )
        if blocking.nonEmpty then
          effects.error(
            s"mandatory facets still proposed: ${blocking.map(_.name).mkString(", ")}; confirm, assume or leave each unresolved first",
            ErrorCode.Conflict
          )
        else if currentState.confirmedBy.isDefined then effects.reply(Done)
        else effects.persist(FacetsConfirmed(request.by, Instant.now())).thenReply(_ => Done)

  def recordQuestions(request: RecordQuestions): Effect[Done] =
    if !currentState.registered then effects.error("no such request", ErrorCode.NotFound)
    else if currentState.questionIds == request.questionIds then effects.reply(Done)
    else effects.persist(QuestionsDerived(request.questionIds)).thenReply(_ => Done)

  def advance(status: RequestStatus): Effect[Done] =
    if currentState.status == status then effects.reply(Done)
    else effects.persist(StatusChanged(status)).thenReply(_ => Done)

  def get: ReadOnlyEffect[CustomerRequest] = effects.reply(currentState)

  def roundTrip: ReadOnlyEffect[RoundTripResult] = currentState.roundTrip match
    case Some(r) => effects.reply(r)
    case None    => effects.error("no round-trip check yet", ErrorCode.NotFound)

object RequestEntity
    extends EventSourcedEntity.Companion[RequestEntity, CustomerRequest, RequestEvent](
      componentId = ComponentId("request"),
      stateSerializer = summon[Serializer[CustomerRequest]],
      eventSerializer = summon[Serializer[RequestEvent]]
    ):

  given Serializer[RequestStatus] = Codecs.serializer[RequestStatus]("request-status")

  def create(context: EventSourcedEntityContext) = new RequestEntity(context)

  val submit          = command("submit")(_.submit)
  val recordFacetSet  = command("record-facet-set")(_.recordFacetSet)
  val recordRoundTrip = command("record-round-trip")(_.recordRoundTrip)
  val confirmFacets   = command("confirm-facets")(_.confirmFacets)
  val recordQuestions = command("record-questions")(_.recordQuestions)
  val advance         = command("advance")(_.advance)
  val get             = query("get")(_.get)
  val roundTrip       = query("round-trip")(_.roundTrip)
