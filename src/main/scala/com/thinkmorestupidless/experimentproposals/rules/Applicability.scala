package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Defect

/**
 * `applicability@v1` (FR-029): a species mismatch forbids direct; an endpoint mismatch forbids
 * direct; an unknown species makes the applicability unknown. Rules rewrite and record what they
 * did; the entity refuses what still violates them.
 */
object ApplicabilityRules:
  val speciesRule      = Rule("applicability.species-mismatch-blocks-direct", 1)
  val endpointRule     = Rule("applicability.endpoint-mismatch-blocks-direct", 1)
  val unknownRule      = Rule("applicability.unknown-species-is-unknown", 1)
  val ruleSet: RuleSet = RuleSet("applicability", 1, Vector(speciesRule, endpointRule, unknownRule))

  val Dimensions: Vector[String] =
    Vector("species", "cellModel", "mechanism", "endpoint", "condition")

  final case class Applied(
      relationship: Relationship,
      applicability: Applicability,
      dimensions: Map[String, DimensionMatch],
      ruleResults: Vector[RuleOutcomeRecord]
  )

  def apply(p: AssessmentProposal): Either[Defect, Applied] =
    for
      relationship <- Relationship.values
        .find(_.toString == p.relationship.trim.replace(' ', '_'))
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"relationship '${p.relationship}' is not in the list",
            retryable = true
          )
        )
      proposed <- Applicability.values
        .find(_.toString == p.applicability.trim)
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"applicability '${p.applicability}' is not in the list",
            retryable = true
          )
        )
      dims <- p.dimensions.toVector.foldLeft[Either[Defect, Map[String, DimensionMatch]]](
        Right(Map.empty)
      ) { case (acc, (name, value)) =>
        acc.flatMap(m =>
          DimensionMatch.values
            .find(_.toString == value.trim)
            .map(v => m + (name.trim -> v))
            .toRight(
              Rules.defect(
                DefectKind.schema_invalid,
                s"dimension '$name' has '$value', not match, mismatch or unknown",
                retryable = true
              )
            )
        )
      }
    yield
      val species       = dims.getOrElse("species", DimensionMatch.unknown)
      val endpoint      = dims.getOrElse("endpoint", DimensionMatch.unknown)
      val results       = Vector.newBuilder[RuleOutcomeRecord]
      var applicability = proposed
      if species == DimensionMatch.mismatch && applicability == Applicability.direct then
        applicability = Applicability.indirect
        results += RuleOutcomeRecord(
          speciesRule.key,
          "adjusted",
          "direct rewritten to indirect: the species does not match"
        )
      else results += RuleOutcomeRecord(speciesRule.key, "passed", s"species ${species}")
      if endpoint == DimensionMatch.mismatch && applicability == Applicability.direct then
        applicability = Applicability.indirect
        results += RuleOutcomeRecord(
          endpointRule.key,
          "adjusted",
          "direct rewritten to indirect: the endpoint does not match"
        )
      else results += RuleOutcomeRecord(endpointRule.key, "passed", s"endpoint ${endpoint}")
      if species == DimensionMatch.unknown && applicability == Applicability.direct then
        applicability = Applicability.unknown
        results += RuleOutcomeRecord(
          unknownRule.key,
          "adjusted",
          "direct rewritten to unknown: the species is not known"
        )
      else results += RuleOutcomeRecord(unknownRule.key, "passed", "")
      Applied(relationship, applicability, dims, results.result())

  def parseSufficiency(p: SufficiencyProposal): Either[
    Defect,
    (SufficiencyStatus, CoverageJudgement, Escalation, Vector[(String, GapKind, Escalation)])
  ] =
    for
      status <- SufficiencyStatus.values
        .find(_.toString == p.status.trim.replace(' ', '_'))
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"sufficiency status '${p.status}' is not in the list",
            retryable = true
          )
        )
      coverage <- CoverageJudgement.values
        .find(_.toString == p.coverageJudgement.trim.replace(' ', '_'))
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"coverage judgement '${p.coverageJudgement}' is not in the list",
            retryable = true
          )
        )
      escalation <- Escalation.values
        .find(_.toString == p.escalation.trim.replace(' ', '_'))
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"escalation '${p.escalation}' is not in the list",
            retryable = true
          )
        )
      gaps <- p.criticalGaps.foldLeft[Either[Defect, Vector[(String, GapKind, Escalation)]]](
        Right(Vector.empty)
      ) { (acc, g) =>
        acc.flatMap(v =>
          for
            kind <- GapKind.values
              .find(_.toString == g.kind.trim)
              .toRight(
                Rules.defect(
                  DefectKind.schema_invalid,
                  s"gap kind '${g.kind}' is not in the list",
                  retryable = true
                )
              )
            esc <- Escalation.values
              .find(_.toString == g.escalation.trim.replace(' ', '_'))
              .toRight(
                Rules.defect(
                  DefectKind.schema_invalid,
                  s"gap escalation '${g.escalation}' is not in the list",
                  retryable = true
                )
              )
          yield v :+ (g.description.trim, kind, esc)
        )
      }
      _ <- Either.cond(
        status == SufficiencyStatus.requires_expert_review || p.supportingAssessments.nonEmpty || status == SufficiencyStatus.insufficient_for_recommendation,
        (),
        Rules.defect(
          DefectKind.schema_invalid,
          "a judgement that finds the evidence sufficient or plausible cites the assessments it rests on",
          retryable = true
        )
      )
    yield (status, coverage, escalation, gaps)
