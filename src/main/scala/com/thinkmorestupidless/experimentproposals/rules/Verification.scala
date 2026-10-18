package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Defect

import java.time.Instant

/**
 * What the verifier's reply becomes (FR-023 to FR-026) and `high-impact@v1` conditions (a) and (c)
 * (FR-025): a failed check forbids `entailed`; each failed check names a defect; ambiguous or
 * insufficiently supported, and a system hypothesis, are routed to a reviewer.
 */
object Verification:
  val failedCheckForbidsEntailed = Rule("verification.failed-check-forbids-entailed", 1)
  val highImpact                 = Rule("high-impact.routing", 1)
  val ruleSet: RuleSet = RuleSet("verification", 1, Vector(failedCheckForbidsEntailed, highImpact))

  enum Decision:
    case Verify(record: VerificationRecord)
    case Reject(record: VerificationRecord, defect: Defect)
    case Review(record: VerificationRecord, reason: String)

  def parse(
      reply: ClaimVerification,
      attempt: Int,
      contextSectionIds: Vector[String],
      promptVersion: String,
      model: String,
      now: Instant = Instant.now()
  ): Either[Defect, VerificationRecord] =
    for
      label <- VerificationLabel.values
        .find(_.toString == reply.label.trim.replace(' ', '_'))
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"verification label '${reply.label}' is not in the list",
            retryable = true
          )
        )
      checks <- reply.checks.toVector.foldLeft[Either[Defect, Map[String, Check]]](
        Right(Map.empty)
      ) { case (acc, (name, check)) =>
        acc.flatMap(m =>
          CheckName.values
            .find(_.toString == name.trim.toLowerCase)
            .map(c => m + (c.toString -> check))
            .toRight(
              Rules.defect(
                DefectKind.schema_invalid,
                s"check '$name' is not one of ${CheckName.values.mkString(", ")}",
                retryable = true
              )
            )
        )
      }
      _ <- Either.cond(
        CheckName.values.forall(c => checks.contains(c.toString)),
        (),
        Rules.defect(
          DefectKind.schema_invalid,
          s"the five checks are required: ${CheckName.values.mkString(", ")}",
          retryable = true
        )
      )
    yield
      val anyFailed = checks.values.exists(!_.passed)
      val effective =
        if anyFailed && label == VerificationLabel.entailed then
          VerificationLabel.insufficiently_supported
        else label
      VerificationRecord(
        attempt,
        effective,
        checks,
        contextSectionIds,
        promptVersion,
        model,
        reply.rationale,
        now
      )

  /** The defect a failed check names; which are retryable is the classification FR-026 asks for. */
  def defectOf(name: CheckName, check: Check): Defect = name match
    case CheckName.negation =>
      Rules.defect(DefectKind.negation_error, check.note, retryable = false)
    case CheckName.species =>
      Rules.defect(DefectKind.species_overgeneralisation, check.note, retryable = false)
    case CheckName.endpoint =>
      Rules.defect(DefectKind.endpoint_mismatch, check.note, retryable = false)
    case CheckName.treatment =>
      Rules.defect(DefectKind.context_mismatch, check.note, retryable = true)
    case CheckName.scope => Rules.defect(DefectKind.scope_loss, check.note, retryable = true)

  def decide(record: EvidenceRecord, verification: VerificationRecord): Decision =
    val failed = verification.checks.toVector
      .filter(!_._2.passed)
      .flatMap((n, c) => CheckName.values.find(_.toString == n).map(_ -> c))
    // A failed negation check on a record that reports success is the failure-as-success fault.
    val reportsSuccess = record.content.exists {
      case RecordContent.OfFinding(f) =>
        val o             = f.reportedOutcome.toLowerCase
        val claimsSuccess = o.contains("success") && !o.contains("unsuccess")
        val stillNegated  = o.matches("(?s).*\\b(did not|does not|no |not |failed|fell below).*")
        claimsSuccess && stillNegated
      case _ => false
    }
    def defectOf(n: CheckName, c: Check): Defect =
      if n == CheckName.negation && reportsSuccess then
        Rules.defect(DefectKind.failure_as_success, c.note, retryable = false)
      else Verification.defectOf(n, c)
    if record.isHypothesis then
      Decision.Review(
        verification,
        "a system hypothesis is tested by a design, not admitted as evidence (FR-025 c)"
      )
    else
      verification.label match
        case VerificationLabel.entailed if failed.isEmpty => Decision.Verify(verification)
        case VerificationLabel.contradicted | VerificationLabel.entailed =>
          val defects = failed.map((n, c) => defectOf(n, c))
          val worst = defects
            .find(!_.retryable)
            .orElse(defects.headOption)
            .getOrElse(
              Rules
                .defect(DefectKind.unsupported_by_span, verification.rationale, retryable = false)
            )
          Decision.Reject(verification, worst)
        case VerificationLabel.insufficiently_supported =>
          val defects = failed.map((n, c) => defectOf(n, c))
          defects.find(_.retryable) match
            case Some(retryable) if defects.forall(_.retryable) =>
              Decision.Reject(verification, retryable)
            case _ =>
              Decision.Review(
                verification,
                "insufficiently supported by the cited spans (FR-025 a)"
              )
        case VerificationLabel.ambiguous =>
          Decision.Review(verification, "the verification is ambiguous (FR-025 a)")
