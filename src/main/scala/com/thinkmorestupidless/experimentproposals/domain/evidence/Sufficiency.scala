package com.thinkmorestupidless.experimentproposals.domain.evidence

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, ReviewFlag}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

final case class GapProposal(description: String, kind: String, escalation: String)

/** The sufficiency judgement as the model proposes it, for one named decision. */
final case class SufficiencyProposal(
    proposition: String,
    status: String,
    assumptions: Vector[String],
    supportingAssessments: Vector[String],
    criticalGaps: Vector[GapProposal],
    coverageJudgement: String,
    rationale: String,
    escalation: String
)

/** Decision-relative sufficiency (glossary: sufficiency); a new judgement is a new version. */
final case class EvidenceSufficiency(
    id: String,
    registered: Boolean = false,
    questionRef: Option[Ref] = None,
    intendedDecision: String = "",
    proposition: String = "",
    status: SufficiencyStatus = SufficiencyStatus.requires_expert_review,
    assumptions: Vector[String] = Vector.empty,
    assessmentRefs: Vector[Ref] = Vector.empty,
    criticalGapIds: Vector[String] = Vector.empty,
    coverageJudgement: CoverageJudgement = CoverageJudgement.mixed,
    rationale: String = "",
    escalation: Escalation = Escalation.none,
    judgedBy: String = "",
    dependsOn: Vector[Ref] = Vector.empty,
    flags: Vector[ReviewFlag] = Vector.empty,
    version: Int = 0
)

object EvidenceSufficiency:
  def empty(id: String): EvidenceSufficiency = EvidenceSufficiency(id)
  def slug(decision: String): String =
    decision.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

enum SufficiencyEvent:
  case Judged(
      questionRef: Ref,
      intendedDecision: String,
      proposition: String,
      status: SufficiencyStatus,
      assumptions: Vector[String],
      assessmentRefs: Vector[Ref],
      criticalGapIds: Vector[String],
      coverageJudgement: CoverageJudgement,
      rationale: String,
      escalation: Escalation,
      judgedBy: String,
      dependsOn: Vector[Ref],
      at: Instant
  )
  case FlaggedForReview(changedRef: Ref, reason: String, at: Instant)

final case class JudgeSufficiency(
    questionRef: Ref,
    intendedDecision: String,
    proposition: String,
    status: SufficiencyStatus,
    assumptions: Vector[String],
    assessmentRefs: Vector[Ref],
    criticalGapIds: Vector[String],
    coverageJudgement: CoverageJudgement,
    rationale: String,
    escalation: Escalation,
    judgedBy: String,
    dependsOn: Vector[Ref]
)

/** A named missing piece of evidence (glossary: coverage gap, critical gap). */
final case class KnowledgeGap(
    id: String,
    registered: Boolean = false,
    questionRef: Option[Ref] = None,
    description: String = "",
    kind: GapKind = GapKind.absence,
    escalation: Escalation = Escalation.none,
    status: GapStatus = GapStatus.open,
    supplementalExecutionIds: Vector[String] = Vector.empty,
    version: Int = 0
)

object KnowledgeGap:
  def empty(id: String): KnowledgeGap = KnowledgeGap(id)

enum GapEvent:
  case Opened(
      questionRef: Ref,
      description: String,
      kind: GapKind,
      escalation: Escalation,
      at: Instant
  )
  case SupplementalSearched(executionId: String, at: Instant)
  case Escalated(escalation: Escalation, at: Instant)
  case Closed(reason: String, at: Instant)

final case class OpenGap(
    questionRef: Ref,
    description: String,
    kind: GapKind,
    escalation: Escalation
)

object SufficiencyCodecs:
  given Serializer[EvidenceSufficiency] = Codecs.serializer[EvidenceSufficiency]("sufficiency")
  given Serializer[SufficiencyEvent]    = Codecs.serializer[SufficiencyEvent]("sufficiency-event")
  given Serializer[JudgeSufficiency]    = Codecs.serializer[JudgeSufficiency]("judge-sufficiency")
  given Serializer[SufficiencyProposal] =
    Codecs.serializer[SufficiencyProposal]("sufficiency-proposal")
  given Serializer[KnowledgeGap] = Codecs.serializer[KnowledgeGap]("gap")
  given Serializer[GapEvent]     = Codecs.serializer[GapEvent]("gap-event")
  given Serializer[OpenGap]      = Codecs.serializer[OpenGap]("open-gap")
  given Serializer[Escalation]   = Codecs.serializer[Escalation]("escalation")
