package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.{
  DesignCheck,
  DesignSections,
  ExperimentalApproach
}
import com.thinkmorestupidless.experimentproposals.domain.evidence.EvidenceRecord
import com.thinkmorestupidless.experimentproposals.domain.request.Facet
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan

/** `design@v1` (FR-039 to FR-041): what a design version must satisfy before a reviewer sees it. */
object DesignRules:
  val sectionsPresent = Rule("design.sections-present", 1)
  val claimsVerified  = Rule("design.claims-verified", 1)
  val spansResolve    = Rule("design.spans-resolve", 1)
  val facetsAccounted = Rule("design.mandatory-facets-accounted", 1)
  val ruleSet: RuleSet =
    RuleSet("design", 1, Vector(sectionsPresent, claimsVerified, spansResolve, facetsAccounted))

  final case class Input(
      sections: DesignSections,
      claims: Map[String, EvidenceRecord],
      resolves: SourceSpan => Boolean,
      facets: Vector[Facet],
      approach: ExperimentalApproach
  )

  def check(in: Input): Vector[DesignCheck] =
    val missing = DesignSections.missing(in.sections)
    val cited = (in.sections.background.flatMap(
      _.claimRefs
    ) ++ in.sections.evidenceManifest.claimRefs).map(_.id).distinct
    val unverified =
      cited.filterNot(id => in.claims.get(id).exists(_.status == RecordStatus.verified))
    val spans = (in.sections.background.flatMap(_.spans) ++ in.sections.evidenceManifest.spans)
      .distinctBy(_.key)
    val unresolved = spans.filterNot(in.resolves)
    val accounted =
      (in.approach.facetRefsAddressed ++ in.approach.facetRefsNotAddressed).map(_.id).toSet
    val unaccounted =
      in.facets.filter(f => f.importance == Importance.mandatory && !accounted.contains(f.id))
    Vector(
      DesignCheck(
        sectionsPresent.key,
        missing.isEmpty,
        if missing.isEmpty then "twelve sections present" else s"empty: ${missing.mkString(", ")}"
      ),
      DesignCheck(
        claimsVerified.key,
        unverified.isEmpty,
        if unverified.isEmpty then s"${cited.size} claim(s) cited, all verified"
        else s"not verified: ${unverified.mkString(", ")}"
      ),
      DesignCheck(
        spansResolve.key,
        unresolved.isEmpty,
        if unresolved.isEmpty then s"${spans.size} span(s) resolve"
        else s"do not resolve: ${unresolved.map(_.key).mkString(", ")}"
      ),
      DesignCheck(
        facetsAccounted.key,
        unaccounted.isEmpty,
        if unaccounted.isEmpty then "every mandatory facet is addressed or named as not addressed"
        else s"unaccounted: ${unaccounted.map(_.name).mkString(", ")}"
      )
    )
