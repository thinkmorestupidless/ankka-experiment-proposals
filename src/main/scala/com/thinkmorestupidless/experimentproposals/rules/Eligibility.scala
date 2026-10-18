package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.{
  Eligibility,
  RuleReason,
  SourceDocument
}

import java.time.Instant

/**
 * `eligibility@v1`: retracted blocks; superseded blocks unless the successor is reviewed; a failed
 * parse or a quarantined version blocks; a duplicate version downgrades; the licence is recorded
 * and is never a gate (research.md R7).
 */
object EligibilityRules:
  val retracted   = Rule("eligibility.retracted", 1)
  val superseded  = Rule("eligibility.superseded", 1)
  val parseFailed = Rule("eligibility.parse-failed", 1)
  val quarantined = Rule("eligibility.quarantined", 1)
  val restricted  = Rule("eligibility.restricted", 1)
  val licence     = Rule("eligibility.licence-recorded", 1)
  val corrected   = Rule("eligibility.corrected", 1)
  val ruleSet: RuleSet = RuleSet(
    "eligibility",
    1,
    Vector(retracted, superseded, parseFailed, quarantined, restricted, licence, corrected)
  )

  def assess(
      doc: SourceDocument,
      version: Int,
      by: String,
      successorReviewed: Boolean = false,
      now: Instant = Instant.now()
  ): Eligibility =
    val v        = doc.version(version)
    val blocking = Vector.newBuilder[RuleReason]
    val notes    = Vector.newBuilder[RuleReason]
    doc.status match
      case SourceStatus.retracted =>
        blocking += RuleReason(retracted.key, "the source is retracted")
      case SourceStatus.superseded if !successorReviewed =>
        blocking += RuleReason(
          superseded.key,
          "the source is superseded and its successor has not been reviewed"
        )
      case SourceStatus.restricted =>
        blocking += RuleReason(restricted.key, "the source is restricted")
      case SourceStatus.corrected =>
        notes += RuleReason(
          corrected.key,
          "the source is corrected; the version retained is read as carrying the correction"
        )
      case _ => ()
    v match
      case None => blocking += RuleReason(parseFailed.key, s"no version $version")
      case Some(ver) =>
        if ver.quarantined then
          blocking += RuleReason(quarantined.key, ver.quarantineReason.getOrElse("quarantined"))
        if ver.completeness.contains(ParseCompleteness.failed) then
          blocking += RuleReason(parseFailed.key, "the parse failed")
    notes += RuleReason(
      licence.key,
      doc.licence.fold("no licence recorded; retention unchanged")(l => s"licence recorded: $l")
    )
    val blocks = blocking.result()
    Eligibility(
      eligible = blocks.isEmpty,
      reasons = blocks ++ notes.result(),
      assessedAt = now,
      assessedBy = by,
      ruleSetVersion = ruleSet.key
    )
