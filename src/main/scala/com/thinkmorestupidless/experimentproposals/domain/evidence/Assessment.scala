package com.thinkmorestupidless.experimentproposals.domain.evidence

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, ReviewAction, ReviewFlag}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/**
 * One assessment as the model proposes it: closed lists as text, checked and adjusted by the rules.
 */
final case class AssessmentProposal(
    recordId: String,
    relationship: String,
    applicability: String,
    dimensions: Map[String, String],
    rationale: String,
    limitations: Vector[String] = Vector.empty
)

final case class AssessmentSet(assessments: Vector[AssessmentProposal])

final case class RuleOutcomeRecord(ruleId: String, outcome: String, detail: String)

/**
 * A question-specific judgement on one finding or claim (glossary: assessment). No score exists
 * (FR-030).
 */
final case class EvidenceAssessment(
    id: String,
    registered: Boolean = false,
    questionRef: Option[Ref] = None,
    recordRef: Option[Ref] = None,
    relationship: Relationship = Relationship.not_applicable,
    applicability: Applicability = Applicability.unknown,
    dimensions: Map[String, DimensionMatch] = Map.empty,
    rationale: String = "",
    limitations: Vector[String] = Vector.empty,
    assessor: String = "",
    reviewStatus: RecordStatus = RecordStatus.proposed,
    ruleResults: Vector[RuleOutcomeRecord] = Vector.empty,
    reviews: Vector[ReviewAction] = Vector.empty,
    dependsOn: Vector[Ref] = Vector.empty,
    flags: Vector[ReviewFlag] = Vector.empty,
    version: Int = 0
)

object EvidenceAssessment:
  def empty(id: String): EvidenceAssessment = EvidenceAssessment(id)

enum AssessmentEvent:
  case Proposed(
      questionRef: Ref,
      recordRef: Ref,
      relationship: Relationship,
      applicability: Applicability,
      dimensions: Map[String, DimensionMatch],
      rationale: String,
      limitations: Vector[String],
      assessor: String,
      ruleResults: Vector[RuleOutcomeRecord],
      dependsOn: Vector[Ref],
      at: Instant
  )
  case Reviewed(
      action: ReviewActionKind,
      by: String,
      at: Instant,
      rationale: String,
      statusAfter: RecordStatus
  )
  case FlaggedForReview(changedRef: Ref, reason: String, at: Instant)

final case class ProposeAssessment(
    questionRef: Ref,
    recordRef: Ref,
    relationship: Relationship,
    applicability: Applicability,
    dimensions: Map[String, DimensionMatch],
    rationale: String,
    limitations: Vector[String],
    assessor: String,
    ruleResults: Vector[RuleOutcomeRecord],
    dependsOn: Vector[Ref]
)

object AssessmentCodecs:
  given Serializer[EvidenceAssessment] = Codecs.serializer[EvidenceAssessment]("assessment")
  given Serializer[AssessmentEvent]    = Codecs.serializer[AssessmentEvent]("assessment-event")
  given Serializer[ProposeAssessment]  = Codecs.serializer[ProposeAssessment]("propose-assessment")
  given Serializer[AssessmentSet]      = Codecs.serializer[AssessmentSet]("assessment-set")
