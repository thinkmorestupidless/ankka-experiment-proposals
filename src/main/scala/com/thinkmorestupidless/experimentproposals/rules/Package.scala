package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.{PackageCheck, Trim}
import com.thinkmorestupidless.experimentproposals.domain.evidence.{
  EvidenceAssessment,
  EvidenceRecord,
  RecordContent
}
import com.thinkmorestupidless.experimentproposals.domain.request.Facet
import com.thinkmorestupidless.experimentproposals.domain.sources.{SourceDocument, SourceSpan}

/**
 * `package@v1` (FR-033, FR-034): what a package must satisfy to be `valid`. Pure over what the
 * builder hands it; the entity turns the checks into the quality status.
 */
object PackageRules:
  val spansResolve           = Rule("package.spans-resolve", 1)
  val qualifiersPresent      = Rule("package.qualifiers-present", 1)
  val sameStudyDeduplicated  = Rule("package.same-study-deduplicated", 1)
  val contradictionsIncluded = Rule("package.contradictions-included", 1)
  val mandatoryFacetsCovered = Rule("package.mandatory-facets-covered", 1)
  val eligibilityEnforced    = Rule("package.eligibility-enforced", 1)
  val verificationEnforced   = Rule("package.verification-enforced", 1)
  val noCriticalTrim         = Rule("package.no-critical-trim", 1)
  val ruleSet: RuleSet = RuleSet(
    "package",
    1,
    Vector(
      spansResolve,
      qualifiersPresent,
      sameStudyDeduplicated,
      contradictionsIncluded,
      mandatoryFacetsCovered,
      eligibilityEnforced,
      verificationEnforced,
      noCriticalTrim
    )
  )

  /** Everything the checks look at, resolved by the caller: the rules read nothing themselves. */
  final case class Input(
      included: Vector[EvidenceAssessment],
      candidates: Vector[EvidenceAssessment],
      records: Map[String, EvidenceRecord],
      sources: Map[String, SourceDocument],
      facets: Vector[Facet],
      spans: Vector[SourceSpan],
      resolves: SourceSpan => Boolean,
      trims: Vector[Trim]
  )

  private def qualified(r: EvidenceRecord): Boolean = r.content match
    case Some(RecordContent.OfFinding(f)) =>
      f.species.isDefined || f.qualifications.nonEmpty || f.limitations.nonEmpty
    case Some(RecordContent.OfClaim(c)) => c.qualifications.nonEmpty || c.speciesScope.isDefined
    case None                           => false

  def check(in: Input): Vector[PackageCheck] =
    val includedRecords = in.included.flatMap(_.recordRef).flatMap(r => in.records.get(r.id))
    val unresolved      = in.spans.filterNot(in.resolves)
    val unqualified     = includedRecords.filterNot(qualified)
    val studies = includedRecords
      .flatMap(r => r.sourceRef.flatMap(s => in.sources.get(s.id)))
      .distinctBy(_.id)
    val sharedIds = studies
      .flatMap(s => s.identifiers.toVector.map(kv => kv -> s.id))
      .groupMap(_._1)(_._2)
      .collect { case (k, ids) if ids.distinct.size > 1 => s"${k._1}=${k._2}" }
      .toVector
    val contradictions = in.candidates.filter(_.relationship == Relationship.contradicts)
    val dropped        = contradictions.filterNot(c => in.included.exists(_.id == c.id))
    val uncovered = in.facets.filter(f =>
      f.importance == Importance.mandatory && f.status == FacetStatus.proposed
    )
    val ineligible = includedRecords.filter(r =>
      !r.sourceRef.flatMap(s => in.sources.get(s.id)).flatMap(_.eligibility).exists(_.eligible)
    )
    val unverified = includedRecords.filter(_.status != RecordStatus.verified)
    val critical   = in.trims.filter(_.critical)
    Vector(
      PackageCheck(
        spansResolve.key,
        unresolved.isEmpty,
        if unresolved.isEmpty then s"${in.spans.size} span(s) resolve"
        else s"${unresolved.size} span(s) do not resolve: ${unresolved.map(_.key).mkString(", ")}"
      ),
      PackageCheck(
        qualifiersPresent.key,
        unqualified.isEmpty,
        if unqualified.isEmpty then "every record carries its qualifiers"
        else s"without qualifiers: ${unqualified.map(_.id).mkString(", ")}"
      ),
      PackageCheck(
        sameStudyDeduplicated.key,
        sharedIds.isEmpty,
        if sharedIds.isEmpty then "no two sources share an identifier"
        else s"shared identifiers: ${sharedIds.mkString(", ")}"
      ),
      PackageCheck(
        contradictionsIncluded.key,
        dropped.isEmpty,
        if dropped.isEmpty then s"${contradictions.size} contradiction(s), all included"
        else s"contradictions left out: ${dropped.map(_.id).mkString(", ")}"
      ),
      PackageCheck(
        mandatoryFacetsCovered.key,
        uncovered.isEmpty,
        if uncovered.isEmpty then "every mandatory facet is confirmed, assumed or flagged"
        else s"still proposed: ${uncovered.map(_.name).mkString(", ")}"
      ),
      PackageCheck(
        eligibilityEnforced.key,
        ineligible.isEmpty,
        if ineligible.isEmpty then "every source is eligible"
        else s"ineligible sources behind: ${ineligible.map(_.id).mkString(", ")}"
      ),
      PackageCheck(
        verificationEnforced.key,
        unverified.isEmpty,
        if unverified.isEmpty then "every record is verified"
        else s"not verified: ${unverified.map(_.id).mkString(", ")}"
      ),
      PackageCheck(
        noCriticalTrim.key,
        critical.isEmpty,
        if critical.isEmpty then s"${in.trims.size} trim(s), none critical"
        else s"critical trims: ${critical.map(_.reason).mkString("; ")}"
      )
    )
