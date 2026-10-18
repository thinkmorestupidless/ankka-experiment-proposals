package com.thinkmorestupidless.experimentproposals.domain.design

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, ReviewFlag}
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/**
 * Material left out of a package for size, logged (glossary: trim); a critical trim invalidates.
 */
final case class Trim(ref: Ref, reason: String, critical: Boolean = false)

/** Material refused by a rule, never for size: an unverified record, an ineligible source. */
final case class Excluded(ref: Ref, reason: String)

/** One check of `package@v1` on one package. */
final case class PackageCheck(name: String, passed: Boolean, detail: String)

/** The validated, versioned context for a question (glossary: evidence package). */
final case class EvidencePackage(
    id: String,
    registered: Boolean = false,
    questionRefs: Vector[Ref] = Vector.empty,
    requestRef: Option[Ref] = None,
    approachRef: Option[Ref] = None,
    facetRefs: Vector[Ref] = Vector.empty,
    assessmentRefs: Vector[Ref] = Vector.empty,
    recordRefs: Vector[Ref] = Vector.empty,
    includedSpans: Vector[SourceSpan] = Vector.empty,
    excluded: Vector[Excluded] = Vector.empty,
    contradictoryAssessmentRefs: Vector[Ref] = Vector.empty,
    resourceConstraints: Vector[String] = Vector.empty,
    openQuestionIds: Vector[String] = Vector.empty,
    gapIds: Vector[String] = Vector.empty,
    provenanceManifest: Vector[Ref] = Vector.empty,
    checks: Vector[PackageCheck] = Vector.empty,
    ruleSetVersion: Option[String] = None,
    qualityStatus: PackageQuality = PackageQuality.requires_review,
    trims: Vector[Trim] = Vector.empty,
    builtBy: String = "",
    dependsOn: Vector[Ref] = Vector.empty,
    flags: Vector[ReviewFlag] = Vector.empty,
    version: Int = 0
)

object EvidencePackage:
  def empty(id: String): EvidencePackage        = EvidencePackage(id)
  def idFor(questionId: String, n: Int): String = s"$questionId:pk$n"

  /** FR-034: valid only when every check passed and no trim removed anything essential. */
  def quality(checks: Vector[PackageCheck], trims: Vector[Trim]): PackageQuality =
    if checks.nonEmpty && checks.forall(_.passed) && !trims.exists(_.critical) then
      PackageQuality.valid
    else PackageQuality.invalid

enum PackageEvent:
  case Built(
      questionRefs: Vector[Ref],
      requestRef: Ref,
      facetRefs: Vector[Ref],
      assessmentRefs: Vector[Ref],
      recordRefs: Vector[Ref],
      includedSpans: Vector[SourceSpan],
      excluded: Vector[Excluded],
      contradictoryAssessmentRefs: Vector[Ref],
      resourceConstraints: Vector[String],
      openQuestionIds: Vector[String],
      gapIds: Vector[String],
      provenanceManifest: Vector[Ref],
      trims: Vector[Trim],
      builtBy: String,
      dependsOn: Vector[Ref],
      at: Instant
  )
  case Validated(
      checks: Vector[PackageCheck],
      ruleSetVersion: String,
      qualityStatus: PackageQuality,
      at: Instant
  )
  case ApproachLinked(approachRef: Ref)
  case FlaggedForReview(changedRef: Ref, reason: String, at: Instant)

final case class BuildPackage(
    questionRefs: Vector[Ref],
    requestRef: Ref,
    facetRefs: Vector[Ref],
    assessmentRefs: Vector[Ref],
    recordRefs: Vector[Ref],
    includedSpans: Vector[SourceSpan],
    excluded: Vector[Excluded],
    contradictoryAssessmentRefs: Vector[Ref],
    resourceConstraints: Vector[String],
    openQuestionIds: Vector[String],
    gapIds: Vector[String],
    provenanceManifest: Vector[Ref],
    trims: Vector[Trim],
    builtBy: String,
    dependsOn: Vector[Ref]
)

final case class ValidatePackage(checks: Vector[PackageCheck], ruleSetVersion: String)

object PackageCodecs:
  given Serializer[EvidencePackage] = Codecs.serializer[EvidencePackage]("package")
  given Serializer[PackageEvent]    = Codecs.serializer[PackageEvent]("package-event")
  given Serializer[BuildPackage]    = Codecs.serializer[BuildPackage]("build-package")
  given Serializer[ValidatePackage] = Codecs.serializer[ValidatePackage]("validate-package")
