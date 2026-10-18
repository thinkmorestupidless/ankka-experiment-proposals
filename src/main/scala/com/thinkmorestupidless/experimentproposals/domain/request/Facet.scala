package com.thinkmorestupidless.experimentproposals.domain.request

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, ReviewAction, ReviewFlag}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** One facet as the model proposes it: closed lists as text, checked by the gate, never trusted. */
final case class FacetProposal(
    name: String,
    statement: String,
    origin: String,
    importance: String,
    status: String,
    quotations: Vector[String] = Vector.empty,
    dependencies: Vector[String] = Vector.empty
)

/** The extraction agent's reply. */
final case class FacetSet(facets: Vector[FacetProposal])

/** An earlier state of a facet, kept visible after a change (glossary: history). */
final case class FacetHistory(
    statement: String,
    origin: FacetOrigin,
    status: FacetStatus,
    at: Instant
)

final case class Facet(
    id: String,
    registered: Boolean = false,
    requestId: String = "",
    name: String = "",
    statement: String = "",
    origin: FacetOrigin = FacetOrigin.inferred,
    importance: Importance = Importance.preferred,
    status: FacetStatus = FacetStatus.proposed,
    requestSpans: Vector[Span] = Vector.empty,
    dependencies: Vector[String] = Vector.empty,
    reviews: Vector[ReviewAction] = Vector.empty,
    history: Vector[FacetHistory] = Vector.empty,
    assumedValue: Option[String] = None,
    flags: Vector[ReviewFlag] = Vector.empty,
    version: Int = 0
)

object Facet:
  def empty(id: String): Facet = Facet(id)

enum FacetEvent:
  case Proposed(
      requestId: String,
      name: String,
      statement: String,
      origin: FacetOrigin,
      importance: Importance,
      status: FacetStatus,
      spans: Vector[Span],
      dependencies: Vector[String],
      at: Instant
  )
  case Reviewed(
      action: FacetAction,
      by: String,
      at: Instant,
      rationale: String,
      statementAfter: Option[String],
      statusAfter: FacetStatus
  )
  case AssumedWithApproval(value: String, by: String, at: Instant, rationale: String)
  case FlaggedForReview(changedRef: Ref, reason: String, at: Instant)

final case class ProposeFacet(
    requestId: String,
    name: String,
    statement: String,
    origin: FacetOrigin,
    importance: Importance,
    status: FacetStatus,
    spans: Vector[Span],
    dependencies: Vector[String]
)
final case class ReviewFacet(
    action: FacetAction,
    by: String,
    rationale: String,
    statement: Option[String] = None
)
final case class AssumeFacet(value: String, by: String, rationale: String)
final case class FlagForReview(changedRef: Ref, reason: String)

/** The transition table (data-model.md): what each reviewer action does from each status. */
object FacetTransitions:
  def after(from: FacetStatus, action: FacetAction): Option[FacetStatus] = (from, action) match
    case (FacetStatus.rejected, _)                            => None
    case (_, FacetAction.amend)                               => Some(from)
    case (FacetStatus.proposed, FacetAction.confirm)          => Some(FacetStatus.confirmed)
    case (FacetStatus.proposed, FacetAction.mark_ambiguous)   => Some(FacetStatus.ambiguous)
    case (FacetStatus.proposed, FacetAction.leave_unresolved) => Some(FacetStatus.unresolved)
    case (FacetStatus.ambiguous | FacetStatus.unresolved, FacetAction.confirm) =>
      Some(FacetStatus.confirmed)
    case (FacetStatus.ambiguous, FacetAction.leave_unresolved) => Some(FacetStatus.unresolved)
    case (FacetStatus.unresolved, FacetAction.mark_ambiguous)  => Some(FacetStatus.ambiguous)
    case (_, FacetAction.reject)                               => Some(FacetStatus.rejected)
    case _                                                     => None

  def canAssume(from: FacetStatus): Boolean =
    from == FacetStatus.proposed || from == FacetStatus.ambiguous || from == FacetStatus.unresolved

object FacetCodecs:
  given Serializer[Facet]         = Codecs.serializer[Facet]("facet")
  given Serializer[FacetEvent]    = Codecs.serializer[FacetEvent]("facet-event")
  given Serializer[ProposeFacet]  = Codecs.serializer[ProposeFacet]("propose-facet")
  given Serializer[ReviewFacet]   = Codecs.serializer[ReviewFacet]("review-facet")
  given Serializer[AssumeFacet]   = Codecs.serializer[AssumeFacet]("assume-facet")
  given Serializer[FlagForReview] = Codecs.serializer[FlagForReview]("flag-for-review")
  given Serializer[FacetSet]      = Codecs.serializer[FacetSet]("facet-set")
