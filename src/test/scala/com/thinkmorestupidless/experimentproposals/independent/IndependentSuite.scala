package com.thinkmorestupidless.experimentproposals.independent

import com.thinkmorestupidless.experimentproposals.application.entities.{
  DecisionEntity,
  DesignEntity
}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.rules.{
  ApplicabilityRules,
  Deterministic,
  EligibilityRules,
  Verification
}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

/**
 * Written from the specification and the glossary alone (FR-051): no prompt text, no cassette. Each
 * test names the requirement it holds the system to; a disabled safeguard must fail one of them
 * (SC-013, `scripts/mutants.sh`).
 */
class IndependentSuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    Configured.reviewers = Reviewers.fromInline("ada:reviewer").toOption.get

  private val now = Instant.parse("2026-10-17T12:00:00Z")

  test("FR-013: a retracted source is never eligible, whatever else is true of it") {
    val doc = SourceDocument(
      "src:x",
      registered = true,
      status = SourceStatus.retracted,
      licence = Some("CC BY 4.0"),
      versions = Vector(
        SourceVersion(
          1,
          "h",
          now,
          None,
          RetentionRestriction.none,
          Some("text"),
          Some("jats@v1"),
          Some(ParseCompleteness.complete)
        )
      )
    )
    val e = EligibilityRules.assess(doc, 1, "independent", now = now)
    assert(!e.eligible, e)
    assert(e.reasons.exists(_.detail.toLowerCase.contains("retracted")), e.reasons)
    val active =
      EligibilityRules.assess(doc.copy(status = SourceStatus.active), 1, "independent", now = now)
    assert(active.eligible, "the same source, active, is eligible: the status is what decides")
  }

  test("FR-029: evidence from another species is never directly applicable") {
    val proposal = AssessmentProposal(
      "src:b:v1:fd1",
      "supports",
      "direct",
      Map(
        "species"   -> "mismatch",
        "endpoint"  -> "match",
        "cellModel" -> "match",
        "mechanism" -> "match",
        "condition" -> "match"
      ),
      "human cells",
      Vector.empty
    )
    val applied =
      ApplicabilityRules.apply(proposal).toOption.getOrElse(fail("the proposal is well-formed"))
    assertNotEquals(applied.applicability, Applicability.direct)
    val matching = ApplicabilityRules
      .apply(proposal.copy(dimensions = proposal.dimensions + ("species" -> "match")))
      .toOption
      .get
    assertEquals(
      matching.applicability,
      Applicability.direct,
      "with the species matching, direct stands"
    )
  }

  test("FR-022: a span whose text does not match its quotation is never admitted") {
    val section = Section(
      "sec-1",
      None,
      1,
      SectionKind.results,
      "Results",
      "Cell number increased 3.1-fold between 0 and 96 hours."
    )
    val good = SourceSpan("src:a", 1, "sec-1", 0, 31, "Cell number increased 3.1-fold")
    val bad  = good.copy(quotation = "Cell number increased 9.9-fold")
    assert(SourceSpan.resolves(section, good))
    assert(!SourceSpan.resolves(section, bad))
    val record = EvidenceRecord(
      "src:a:v1:fd1",
      registered = true,
      kind = "finding",
      sourceRef = Some(Ref(RecordKind.source, "src:a", 1)),
      content = Some(
        RecordContent.OfFinding(
          FindingContent(
            Some("duck"),
            None,
            None,
            Vector.empty,
            "proliferation",
            "increased",
            OutcomeDirection.increase,
            Vector.empty,
            Vector.empty,
            None,
            "low",
            Map.empty
          )
        )
      ),
      spans = Vector(bad)
    )
    val result = Deterministic.validate(
      record,
      sp =>
        if SourceSpan.resolves(section, sp) then SpanResolution.Resolved("")
        else SpanResolution.Unresolved("mismatch"),
      sourceVersionExists = true,
      now
    )
    assert(!result.passed, result)
    assert(result.defects.exists(_.kind == DefectKind.span_unresolved), result.defects)
  }

  test(
    "FR-025 (c): a system hypothesis is never verified as evidence, even when the text entails it"
  ) {
    val hypothesis = EvidenceRecord(
      "rq:cl1",
      registered = true,
      kind = "claim",
      content = Some(
        RecordContent.OfClaim(
          ClaimContent(
            "Duck hepatocytes will accumulate lipid",
            ClaimKind.system_hypothesis,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            None,
            None
          )
        )
      )
    )
    val allPassed = VerificationRecord(
      1,
      VerificationLabel.entailed,
      CheckName.values.map(c => c.toString -> Check(true, "", "agrees")).toMap,
      Vector.empty,
      "verify@v1",
      "independent",
      "entailed",
      now
    )
    Verification.decide(hypothesis, allPassed) match
      case Verification.Decision.Verify(_) => fail("a hypothesis was verified")
      case _                               => ()
  }

  test(
    "FR-042: nobody outside the configured reviewer list approves a design or decides between candidates"
  ) {
    val d = EventSourcedTestKit.of(DesignEntity, "rq-i:design")
    val sections = DesignSections(
      "Q",
      Vector(Grounded("B", Vector(Ref(RecordKind.claim, "c", 1)), Vector.empty)),
      "H",
      "M",
      "V",
      "E",
      "S",
      "R",
      "L",
      "A",
      "O",
      Manifest(Vector(Ref(RecordKind.claim, "c", 1)), Vector.empty, Vector.empty)
    )
    d.call(DesignEntity.generate)(
      GenerateDesign(
        Ref(RecordKind.request, "rq-i", 1),
        Ref(RecordKind.approach, "rq-i:c1", 1),
        Ref(RecordKind.decision, "rq-i:d1", 1),
        sections,
        Vector.empty,
        "design@v1",
        "t",
        Vector.empty
      )
    )
    d.call(DesignEntity.submit)(SubmitDesign(1, "operator"))
    assert(
      d.call(DesignEntity.recordApproval)(
        RecordApproval(1, ApprovalDecision.approved, "someone-else", "fine")
      ).isError
    )
    assertEquals(d.currentState.approvals, Vector.empty)
    assertEquals(d.currentState.version(1).map(_.status), Some(DesignStatus.under_review))
    val dec = EventSourcedTestKit.of(DecisionEntity, "rq-i:d1")
    val cmp = Comparison(
      "rq-i:comparison1",
      Vector(Ref(RecordKind.approach, "rq-i:c1", 1)),
      Vector.empty,
      "t",
      now
    )
    assert(
      dec
        .call(DecisionEntity.decide)(
          Decide(
            Ref(RecordKind.request, "rq-i", 1),
            cmp,
            Ref(RecordKind.approach, "rq-i:c1", 1),
            Vector.empty,
            "someone-else",
            "because"
          )
        )
        .isError
    )
    assert(!dec.currentState.registered)
  }

  test("FR-041: a rendering never adds a claim that the design does not carry") {
    val claim = Ref(RecordKind.claim, "src:a:v1:cl1", 1)
    val sections = DesignSections(
      "Q",
      Vector(Grounded("B", Vector(claim), Vector.empty)),
      "H",
      "M",
      "V",
      "E",
      "S",
      "R",
      "L",
      "A",
      "O",
      Manifest(Vector(claim), Vector.empty, Vector.empty)
    )
    val v = DesignVersion(
      1,
      Ref(RecordKind.approach, "a", 1),
      Ref(RecordKind.decision, "d", 1),
      sections,
      DesignStatus.under_review,
      Vector.empty,
      "design@v1",
      now,
      "t"
    )
    val md         = Rendering.render("rq", v, Vector.empty)
    val referenced = Rendering.claimsReferenced(md)
    assert(referenced.nonEmpty)
    assert(referenced.forall(sections.evidenceManifest.claimRefs.map(_.id).contains), referenced)
    assert(md.contains("Proposal awaiting review"), "an unapproved design is a proposal")
  }
