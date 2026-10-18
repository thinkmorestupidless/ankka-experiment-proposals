package com.thinkmorestupidless.experimentproposals.domain.request

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** A location in the customer request's text. */
final case class Span(start: Int, end: Int, quotation: String)

/** What the request keeps about each of its facets: enough to account for them, no more. */
final case class FacetRef(id: String, name: String, importance: Importance)

/** One thing the round-trip check found. */
final case class Issue(
    description: String,
    facetName: Option[String] = None,
    span: Option[String] = None
)

/** The model's reply for the round-trip check (glossary: round-trip check, reconstruction). */
final case class RoundTripCheck(
    reconstruction: String,
    omissions: Vector[Issue],
    unsupportedAdditions: Vector[Issue],
    contradictions: Vector[Issue],
    ambiguities: Vector[Issue]
)

/** The check as recorded: `passed` is the rule's verdict, never the model's (FR-003). */
final case class RoundTripResult(
    check: RoundTripCheck,
    passed: Boolean,
    facetSetVersion: Int,
    run: Int,
    promptVersion: String,
    at: Instant
)

final case class CustomerRequest(
    id: String,
    registered: Boolean = false,
    text: String = "",
    submittedBy: String = "",
    submittedAt: Option[Instant] = None,
    contentHash: String = "",
    facets: Vector[FacetRef] = Vector.empty,
    facetSetVersion: Int = 0,
    questionIds: Vector[String] = Vector.empty,
    roundTrip: Option[RoundTripResult] = None,
    roundTripRuns: Int = 0,
    confirmedBy: Option[String] = None,
    status: RequestStatus = RequestStatus.submitted
)

object CustomerRequest:
  def empty(id: String): CustomerRequest = CustomerRequest(id)

enum RequestEvent:
  case Submitted(text: String, submittedBy: String, at: Instant, contentHash: String)
  case FacetSetProposed(facets: Vector[FacetRef], setVersion: Int)
  case RoundTripChecked(result: RoundTripResult)
  case FacetsConfirmed(by: String, at: Instant)
  case QuestionsDerived(questionIds: Vector[String])
  case StatusChanged(status: RequestStatus)

final case class SubmitRequest(text: String, submittedBy: String)
final case class RecordFacetSet(facets: Vector[FacetRef])
final case class RecordRoundTrip(check: RoundTripCheck, promptVersion: String)
final case class FacetState(id: String, name: String, importance: Importance, status: FacetStatus)
final case class ConfirmFacets(by: String, facets: Vector[FacetState])
final case class RecordQuestions(questionIds: Vector[String])

/** How the mandatory facets stand at proposal time (SC-001, FR-039). */
final case class ProposalStatus(
    requestId: String,
    confirmed: Vector[String],
    assumedWithApproval: Vector[String],
    unresolvedAndFlagged: Vector[String],
    stillProposed: Vector[String],
    assumedValues: Map[String, String]
)

object RequestCodecs:
  given Serializer[CustomerRequest] = Codecs.serializer[CustomerRequest]("request")
  given Serializer[RequestEvent]    = Codecs.serializer[RequestEvent]("request-event")
  given Serializer[SubmitRequest]   = Codecs.serializer[SubmitRequest]("submit-request")
  given Serializer[RecordFacetSet]  = Codecs.serializer[RecordFacetSet]("record-facet-set")
  given Serializer[RecordRoundTrip] = Codecs.serializer[RecordRoundTrip]("record-round-trip")
  given Serializer[ConfirmFacets]   = Codecs.serializer[ConfirmFacets]("confirm-facets")
  given Serializer[RecordQuestions] = Codecs.serializer[RecordQuestions]("record-questions")
  given Serializer[RoundTripResult] = Codecs.serializer[RoundTripResult]("round-trip-result")
  given Serializer[RoundTripCheck]  = Codecs.serializer[RoundTripCheck]("round-trip-check")
