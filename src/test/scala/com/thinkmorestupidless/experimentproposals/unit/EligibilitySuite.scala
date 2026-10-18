package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.rules.EligibilityRules

import java.time.Instant

class EligibilitySuite extends munit.FunSuite:

  private val version = SourceVersion(
    1,
    "h",
    Instant.now(),
    None,
    RetentionRestriction.none,
    Some("t"),
    completeness = Some(ParseCompleteness.complete)
  )
  private val doc = SourceDocument(
    "src:x",
    registered = true,
    licence = Some("CC BY 4.0"),
    versions = Vector(version)
  )

  test("an active, parsed source is eligible and its licence is recorded, not a gate") {
    val e = EligibilityRules.assess(doc, 1, "system")
    assert(e.eligible)
    assert(e.reasons.exists(_.ruleId == EligibilityRules.licence.key))
    assert(EligibilityRules.assess(doc.copy(licence = None), 1, "system").eligible)
  }

  test("retracted, superseded, quarantined and failed parses block, each naming its rule") {
    assertEquals(
      EligibilityRules
        .assess(doc.copy(status = SourceStatus.retracted), 1, "system")
        .reasons
        .head
        .ruleId,
      EligibilityRules.retracted.key
    )
    assert(
      !EligibilityRules.assess(doc.copy(status = SourceStatus.superseded), 1, "system").eligible
    )
    assert(
      EligibilityRules
        .assess(doc.copy(status = SourceStatus.superseded), 1, "system", successorReviewed = true)
        .eligible
    )
    assert(
      !EligibilityRules
        .assess(doc.copy(versions = Vector(version.copy(quarantined = true))), 1, "system")
        .eligible
    )
    assert(
      !EligibilityRules
        .assess(
          doc.copy(versions = Vector(version.copy(completeness = Some(ParseCompleteness.failed)))),
          1,
          "system"
        )
        .eligible
    )
    assertEquals(EligibilityRules.assess(doc, 1, "system").ruleSetVersion, "eligibility@v1")
  }
