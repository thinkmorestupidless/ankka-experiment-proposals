package com.thinkmorestupidless.experimentproposals.domain.design

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, ReviewAction, ReviewFlag}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** A stated assumption and whether evidence backs it (glossary: assumption). */
final case class Assumption(statement: String, evidenceStatus: EvidenceStatus)
final case class PossibleResult(result: String, informationGained: String)

// ── the model's reply ──────────────────────────────────────────────────────────

final case class AssumptionProposal(statement: String, evidenceStatus: String)
final case class ApproachProposal(
    title: String,
    hypothesis: String,
    rationale: String,
    facetsAddressed: Vector[String] = Vector.empty,
    facetsNotAddressed: Vector[String] = Vector.empty,
    affirmativeClaimIds: Vector[String] = Vector.empty,
    contraryClaimIds: Vector[String] = Vector.empty,
    indirectClaimIds: Vector[String] = Vector.empty,
    missingEvidence: Vector[String] = Vector.empty,
    biologicalModel: String,
    modelLimitations: Vector[String] = Vector.empty,
    endpoints: Vector[String] = Vector.empty,
    measurements: Vector[String] = Vector.empty,
    controls: Vector[String] = Vector.empty,
    resources: Vector[String] = Vector.empty,
    safetyRegulatory: Vector[String] = Vector.empty,
    assumptions: Vector[AssumptionProposal] = Vector.empty,
    uncertainties: Vector[String] = Vector.empty,
    failureModes: Vector[String] = Vector.empty,
    possibleResults: Vector[PossibleResult] = Vector.empty,
    prerequisites: Vector[String] = Vector.empty
)
final case class RatingProposal(rating: String, rationale: String)
final case class CriterionProposal(name: String, perApproach: Map[String, RatingProposal])
final case class ApproachSet(
    approaches: Vector[ApproachProposal],
    comparison: Vector[CriterionProposal]
)

// ── the comparison ─────────────────────────────────────────────────────────────

final case class Rating(rating: String, rationale: String)
final case class Criterion(name: String, perApproach: Map[String, Rating])

/**
 * The side-by-side judgement on the eight criteria (glossary: comparison), kept on the decision.
 */
final case class Comparison(
    id: String,
    approachRefs: Vector[Ref],
    criteria: Vector[Criterion],
    comparedBy: String,
    comparedAt: Instant
)

object Comparison:
  val CriterionNames: Vector[String] = Vector(
    "scientific_support",
    "requirement_coverage",
    "feasibility",
    "resource_cost",
    "timeline",
    "risk",
    "information_gain",
    "follow_on_value"
  )

// ── the approach entity ────────────────────────────────────────────────────────

/** One candidate approach (glossary: candidate approach). No probability field exists (FR-037). */
final case class ExperimentalApproach(
    id: String,
    registered: Boolean = false,
    requestRef: Option[Ref] = None,
    packageRefs: Vector[Ref] = Vector.empty,
    title: String = "",
    hypothesis: String = "",
    rationale: String = "",
    facetRefsAddressed: Vector[Ref] = Vector.empty,
    facetRefsNotAddressed: Vector[Ref] = Vector.empty,
    affirmativeClaimRefs: Vector[Ref] = Vector.empty,
    contraryClaimRefs: Vector[Ref] = Vector.empty,
    indirectClaimRefs: Vector[Ref] = Vector.empty,
    missingEvidence: Vector[String] = Vector.empty,
    biologicalModel: String = "",
    modelLimitations: Vector[String] = Vector.empty,
    endpoints: Vector[String] = Vector.empty,
    measurements: Vector[String] = Vector.empty,
    controls: Vector[String] = Vector.empty,
    resources: Vector[String] = Vector.empty,
    safetyRegulatory: Vector[String] = Vector.empty,
    assumptions: Vector[Assumption] = Vector.empty,
    uncertainties: Vector[String] = Vector.empty,
    failureModes: Vector[String] = Vector.empty,
    possibleResults: Vector[PossibleResult] = Vector.empty,
    prerequisites: Vector[String] = Vector.empty,
    status: ApproachStatus = ApproachStatus.proposed,
    comparisonId: Option[String] = None,
    decisionId: Option[String] = None,
    rejectionReason: Option[String] = None,
    proposedBy: String = "",
    reviews: Vector[ReviewAction] = Vector.empty,
    dependsOn: Vector[Ref] = Vector.empty,
    flags: Vector[ReviewFlag] = Vector.empty,
    version: Int = 0
)

object ExperimentalApproach:
  def empty(id: String): ExperimentalApproach  = ExperimentalApproach(id)
  def idFor(requestId: String, n: Int): String = s"$requestId:c$n"

final case class ApproachContent(
    title: String,
    hypothesis: String,
    rationale: String,
    facetRefsAddressed: Vector[Ref],
    facetRefsNotAddressed: Vector[Ref],
    affirmativeClaimRefs: Vector[Ref],
    contraryClaimRefs: Vector[Ref],
    indirectClaimRefs: Vector[Ref],
    missingEvidence: Vector[String],
    biologicalModel: String,
    modelLimitations: Vector[String],
    endpoints: Vector[String],
    measurements: Vector[String],
    controls: Vector[String],
    resources: Vector[String],
    safetyRegulatory: Vector[String],
    assumptions: Vector[Assumption],
    uncertainties: Vector[String],
    failureModes: Vector[String],
    possibleResults: Vector[PossibleResult],
    prerequisites: Vector[String]
)

enum ApproachEvent:
  case Proposed(
      requestRef: Ref,
      packageRefs: Vector[Ref],
      content: ApproachContent,
      proposedBy: String,
      dependsOn: Vector[Ref],
      at: Instant
  )
  case Compared(comparisonId: String, at: Instant)
  case Selected(by: String, decisionId: String, at: Instant)
  case RejectedInDecision(by: String, decisionId: String, reason: String, at: Instant)
  case Reviewed(action: ReviewAction, status: ApproachStatus)
  case FlaggedForReview(changedRef: Ref, reason: String, at: Instant)

final case class ProposeApproach(
    requestRef: Ref,
    packageRefs: Vector[Ref],
    content: ApproachContent,
    proposedBy: String,
    dependsOn: Vector[Ref]
)
final case class SelectedIn(by: String, decisionId: String)
final case class RejectedIn(by: String, decisionId: String, reason: String)
final case class ReviewApproach(action: String, reviewer: String, rationale: String)

// ── the decision ───────────────────────────────────────────────────────────────

final case class RejectedApproach(approachRef: Ref, reason: String)

/** Which approach was selected and why the others were not (glossary: decision record). */
final case class DecisionRecord(
    id: String,
    registered: Boolean = false,
    requestRef: Option[Ref] = None,
    comparison: Option[Comparison] = None,
    selectedApproachRef: Option[Ref] = None,
    rejected: Vector[RejectedApproach] = Vector.empty,
    decidedBy: String = "",
    decidedAt: Option[Instant] = None,
    rationale: String = "",
    version: Int = 0
)

object DecisionRecord:
  def empty(id: String): DecisionRecord        = DecisionRecord(id)
  def idFor(requestId: String, n: Int): String = s"$requestId:d$n"

enum DecisionEvent:
  case Decided(
      requestRef: Ref,
      comparison: Comparison,
      selectedApproachRef: Ref,
      rejected: Vector[RejectedApproach],
      decidedBy: String,
      rationale: String,
      at: Instant
  )

final case class Decide(
    requestRef: Ref,
    comparison: Comparison,
    selectedApproachRef: Ref,
    rejected: Vector[RejectedApproach],
    decidedBy: String,
    rationale: String
)

object ApproachCodecs:
  given Serializer[ExperimentalApproach] = Codecs.serializer[ExperimentalApproach]("approach")
  given Serializer[ApproachEvent]        = Codecs.serializer[ApproachEvent]("approach-event")
  given Serializer[ProposeApproach]      = Codecs.serializer[ProposeApproach]("propose-approach")
  given Serializer[SelectedIn]           = Codecs.serializer[SelectedIn]("selected-in")
  given Serializer[RejectedIn]           = Codecs.serializer[RejectedIn]("rejected-in")
  given Serializer[ReviewApproach]       = Codecs.serializer[ReviewApproach]("review-approach")
  given Serializer[ApproachSet]          = Codecs.serializer[ApproachSet]("approach-set")
  given Serializer[Comparison]           = Codecs.serializer[Comparison]("comparison")
  given Serializer[DecisionRecord]       = Codecs.serializer[DecisionRecord]("decision")
  given Serializer[DecisionEvent]        = Codecs.serializer[DecisionEvent]("decision-event")
  given Serializer[Decide]               = Codecs.serializer[Decide]("decide")
