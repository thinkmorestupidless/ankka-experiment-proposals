package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, ValidationResult}

import java.time.Instant

/** A rule with an id and a version, both recorded on every validation it takes part in. */
final case class Rule(id: String, version: Int):
  def key: String = s"$id@v$version"

enum RuleOutcome:
  case passed, failed, adjusted

final case class RuleResult(rule: Rule, outcome: RuleOutcome, detail: Option[String] = None):
  def failed: Boolean = outcome == RuleOutcome.failed

/** A named, versioned set of rules; the name and version are what a gate records. */
final case class RuleSet(name: String, version: Int, rules: Vector[Rule]):
  def key: String                = s"$name@v$version"
  def versions: Map[String, Int] = rules.map(r => r.id -> r.version).toMap + (name -> version)

object Rules:
  /** Builds a validation result from rule results: it passes when no rule failed. */
  def validation(
      gate: Gate,
      ruleSet: RuleSet,
      results: Vector[RuleResult],
      defects: Vector[Defect],
      at: Instant = Instant.now()
  ): ValidationResult =
    ValidationResult(
      gate = gate,
      passed = !results.exists(_.failed) && defects.forall(_.severity == Severity.minor),
      defects = defects,
      ruleVersions = ruleSet.versions,
      runAt = at
    )

  def defect(kind: DefectKind, detail: String, retryable: Boolean = false): Defect =
    val severity = kind match
      case DefectKind.negation_error | DefectKind.failure_as_success | DefectKind.span_unresolved |
          DefectKind.species_overgeneralisation | DefectKind.endpoint_mismatch |
          DefectKind.unsupported_by_span | DefectKind.unverified_claim_ref =>
        Severity.critical
      case DefectKind.schema_invalid | DefectKind.unit_invalid | DefectKind.status_invalid |
          DefectKind.scope_loss | DefectKind.context_mismatch | DefectKind.model_refused |
          DefectKind.coverage_gap | DefectKind.fewer_than_two =>
        Severity.major
      case DefectKind.other => Severity.minor
    Defect(kind, severity, detail, retryable)
