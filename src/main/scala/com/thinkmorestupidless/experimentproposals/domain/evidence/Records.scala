package com.thinkmorestupidless.experimentproposals.domain.evidence

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{
  Defect,
  Ref,
  ReviewAction,
  ReviewFlag,
  ValidationResult
}
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** A quotation the extractor took from a section, before it is located as a span. */
final case class Quotation(section: String, text: String)

/** A finding as the extractor proposes it (glossary: finding). Closed lists arrive as text. */
final case class FindingProposal(
    key: String,
    species: Option[String],
    cellType: Option[String],
    experimentalModel: Option[String],
    conditions: Vector[String],
    observedEndpoint: String,
    reportedOutcome: String,
    outcomeDirection: String,
    qualifications: Vector[String] = Vector.empty,
    limitations: Vector[String] = Vector.empty,
    authorInterpretation: Option[String] = None,
    extractorUncertainty: String = "",
    missingValues: Map[String, String] = Map.empty,
    quotations: Vector[Quotation] = Vector.empty
)

/** A claim as the extractor proposes it (glossary: claim). */
final case class ClaimProposal(
    key: String,
    proposition: String,
    kind: String,
    findings: Vector[String] = Vector.empty,
    qualifications: Vector[String] = Vector.empty,
    speciesScope: Option[String] = None,
    endpointScope: Option[String] = None,
    quotations: Vector[Quotation] = Vector.empty
)

/** The extraction agent's reply: distinct kinds, each grounded in quotations (FR-020, FR-021). */
final case class ExtractionSet(findings: Vector[FindingProposal], claims: Vector[ClaimProposal])

/** The content of a finding, as kept. */
final case class FindingContent(
    species: Option[String],
    cellType: Option[String],
    experimentalModel: Option[String],
    conditions: Vector[String],
    observedEndpoint: String,
    reportedOutcome: String,
    outcomeDirection: OutcomeDirection,
    qualifications: Vector[String],
    limitations: Vector[String],
    authorInterpretation: Option[String],
    extractorUncertainty: String,
    missingValues: Map[String, MissingValueStatus]
)

/** The content of a claim, as kept. */
final case class ClaimContent(
    proposition: String,
    kind: ClaimKind,
    findingRefs: Vector[Ref],
    derivedFromClaimRefs: Vector[Ref],
    qualifications: Vector[String],
    speciesScope: Option[String],
    endpointScope: Option[String]
)

/** One record is a finding or a claim; the same entity keeps both kinds. */
enum RecordContent:
  case OfFinding(finding: FindingContent)
  case OfClaim(claim: ClaimContent)

final case class Check(passed: Boolean, evidenceQuote: String, note: String)

/** The verifier's reply (glossary: verification). A failed check forbids `entailed`. */
final case class ClaimVerification(
    label: String,
    checks: Map[String, Check],
    rationale: String
)

final case class VerificationRecord(
    attempt: Int,
    label: VerificationLabel,
    checks: Map[String, Check],
    contextSectionIds: Vector[String],
    promptVersion: String,
    model: String,
    rationale: String,
    verifiedAt: Instant
)

final case class Attempt(
    n: Int,
    content: RecordContent,
    spans: Vector[SourceSpan],
    generatedBy: String,
    triggeredBy: Option[Defect],
    validation: Option[ValidationResult] = None,
    verification: Option[VerificationRecord] = None,
    outcome: Option[RecordStatus] = None,
    defect: Option[Defect] = None,
    at: Instant
)

final case class EvidenceRecord(
    id: String,
    registered: Boolean = false,
    kind: String = "",
    sourceRef: Option[Ref] = None,
    content: Option[RecordContent] = None,
    spans: Vector[SourceSpan] = Vector.empty,
    generatedBy: String = "",
    status: RecordStatus = RecordStatus.proposed,
    attempts: Vector[Attempt] = Vector.empty,
    defect: Option[Defect] = None,
    reviews: Vector[ReviewAction] = Vector.empty,
    dependsOn: Vector[Ref] = Vector.empty,
    flags: Vector[ReviewFlag] = Vector.empty,
    testedByDesignRef: Option[Ref] = None,
    version: Int = 0
):
  def attempt: Int = attempts.size
  def isHypothesis: Boolean = content.exists {
    case RecordContent.OfClaim(c) => c.kind == ClaimKind.system_hypothesis; case _ => false
  }

object EvidenceRecord:
  def empty(id: String): EvidenceRecord = EvidenceRecord(id)

enum RecordEvent:
  case Proposed(
      kind: String,
      sourceRef: Ref,
      content: RecordContent,
      spans: Vector[SourceSpan],
      generatedBy: String,
      dependsOn: Vector[Ref],
      at: Instant
  )
  case Validated(attempt: Int, result: ValidationResult)
  case Verified(attempt: Int, verification: VerificationRecord, at: Instant)
  case Rejected(attempt: Int, defect: Defect, verification: Option[VerificationRecord], at: Instant)
  case RequiresReview(
      attempt: Int,
      reason: String,
      verification: Option[VerificationRecord],
      at: Instant
  )
  case Quarantined(attempt: Int, defect: Defect, at: Instant)
  case Reproposed(
      attempt: Int,
      content: RecordContent,
      spans: Vector[SourceSpan],
      triggeredBy: Defect,
      generatedBy: String,
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

final case class ProposeRecord(
    kind: String,
    sourceRef: Ref,
    content: RecordContent,
    spans: Vector[SourceSpan],
    generatedBy: String,
    dependsOn: Vector[Ref]
)
final case class RecordValidation(attempt: Int, result: ValidationResult)
final case class RecordVerified(attempt: Int, verification: VerificationRecord)
final case class RecordRejected(
    attempt: Int,
    defect: Defect,
    verification: Option[VerificationRecord]
)
final case class RecordRequiresReview(
    attempt: Int,
    reason: String,
    verification: Option[VerificationRecord]
)
final case class QuarantineRecord(attempt: Int, defect: Defect)
final case class ReproposeRecord(
    content: RecordContent,
    spans: Vector[SourceSpan],
    triggeredBy: Defect,
    generatedBy: String
)
final case class ReviewRecord(action: String, by: String, rationale: String)

object RecordCodecs:
  given Serializer[EvidenceRecord]   = Codecs.serializer[EvidenceRecord]("evidence-record")
  given Serializer[RecordEvent]      = Codecs.serializer[RecordEvent]("evidence-record-event")
  given Serializer[ProposeRecord]    = Codecs.serializer[ProposeRecord]("propose-record")
  given Serializer[RecordValidation] = Codecs.serializer[RecordValidation]("record-validation")
  given Serializer[RecordVerified]   = Codecs.serializer[RecordVerified]("record-verified")
  given Serializer[RecordRejected]   = Codecs.serializer[RecordRejected]("record-rejected")
  given Serializer[RecordRequiresReview] =
    Codecs.serializer[RecordRequiresReview]("record-requires-review")
  given Serializer[QuarantineRecord]  = Codecs.serializer[QuarantineRecord]("quarantine-record")
  given Serializer[ReproposeRecord]   = Codecs.serializer[ReproposeRecord]("repropose-record")
  given Serializer[ReviewRecord]      = Codecs.serializer[ReviewRecord]("review-record")
  given Serializer[Attempt]           = Codecs.serializer[Attempt]("record-attempt")
  given Serializer[ExtractionSet]     = Codecs.serializer[ExtractionSet]("extraction-set")
  given Serializer[ClaimVerification] = Codecs.serializer[ClaimVerification]("claim-verification")
