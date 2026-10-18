package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.Extraction
import com.thinkmorestupidless.experimentproposals.application.entities.{ClaimEntity, FindingEntity}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.experimentproposals.domain.sources.{
  Section,
  SourceSpan,
  SpanResolution
}
import com.thinkmorestupidless.experimentproposals.rules.{Deterministic, Rules, Verification}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class EvidenceRulesSuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    Configured.reviewers = Reviewers.fromInline("ada:reviewer").toOption.get
    Configured.retryBudget = 2

  private val results = Section(
    "sec-4",
    None,
    4,
    SectionKind.results,
    "Results",
    "Duck hepatocytes proliferated under the defined conditions. No measurement of intracellular lipid was made."
  )
  private val sections =
    Vector(Section("sec-1", None, 1, SectionKind.abstract_, "Abstract", "An abstract."), results)
  private val sourceRef = Ref(RecordKind.source, "src:file:paper-a", 1)
  private val finding = FindingProposal(
    "f1",
    Some("duck"),
    Some("primary hepatocytes"),
    None,
    Vector("serum-free"),
    "proliferation",
    "cells proliferated",
    "increase",
    missingValues = Map("lipid accumulation" -> "not_established_by_assessed_material"),
    quotations =
      Vector(Quotation("S2", "Duck hepatocytes proliferated under the defined conditions"))
  )
  private val claim = ClaimProposal(
    "c1",
    "Duck hepatocytes proliferate in serum-free medium",
    "normalised_claim",
    Vector("f1"),
    Vector("species: duck"),
    Some("duck"),
    Some("proliferation"),
    Vector(Quotation("S2", "Duck hepatocytes proliferated"))
  )

  test("an extraction set becomes records with located spans, claims linked to their findings") {
    val prepared = Extraction
      .prepare(
        "src:file:paper-a",
        1,
        sections,
        ExtractionSet(Vector(finding), Vector(claim)),
        "model:extract@v1"
      )
      .toOption
      .get
    assertEquals(prepared.map(_.id), Vector("src:file:paper-a:v1:fd1", "src:file:paper-a:v1:cl1"))
    assertEquals(prepared.head.request.spans.head.startOffset, 0)
    prepared(1).request.content match
      case RecordContent.OfClaim(c) =>
        assertEquals(c.findingRefs.map(_.id), Vector("src:file:paper-a:v1:fd1"))
      case other => fail(s"$other")
  }

  test(
    "a quotation not in its section, a bad closed list and a reviewer-only status are classified defects"
  ) {
    val missing = finding.copy(quotations = Vector(Quotation("S2", "cells accumulated lipid")))
    assertEquals(
      Extraction
        .prepare("s", 1, sections, ExtractionSet(Vector(missing), Vector.empty), "m")
        .left
        .map(_.kind),
      Left(DefectKind.span_unresolved)
    )
    assertEquals(
      Deterministic.finding(finding.copy(outcomeDirection = "sideways")).left.map(_.kind),
      Left(DefectKind.schema_invalid)
    )
    assertEquals(
      Deterministic
        .finding(finding.copy(missingValues = Map("x" -> "established_absent")))
        .left
        .map(_.kind),
      Left(DefectKind.status_invalid)
    )
    assertEquals(
      Deterministic.claim(claim.copy(kind = "guess"), Vector.empty).left.map(_.kind),
      Left(DefectKind.schema_invalid)
    )
  }

  test(
    "the deterministic gate fails a span the source does not resolve, before any model is consulted"
  ) {
    val span = SourceSpan("src:file:paper-a", 1, "sec-4", 0, 10, "Duck hepat")
    val record = EvidenceRecord(
      "src:file:paper-a:v1:fd1",
      registered = true,
      kind = "finding",
      sourceRef = Some(sourceRef),
      content = Some(RecordContent.OfFinding(Deterministic.finding(finding).toOption.get)),
      spans = Vector(span)
    )
    assert(
      Deterministic
        .validate(record, _ => SpanResolution.Resolved("Duck hepat"), sourceVersionExists = true)
        .passed
    )
    val failed = Deterministic.validate(
      record,
      _ => SpanResolution.Unresolved("offsets"),
      sourceVersionExists = true
    )
    assert(!failed.passed && failed.defects.head.kind == DefectKind.span_unresolved)
  }

  test("a failed check forbids entailed; the decision follows the label and the classification") {
    def check(ok: Boolean) = Check(ok, "q", "n")
    val all                = CheckName.values.map(c => c.toString -> check(true)).toMap
    val entailed = Verification
      .parse(ClaimVerification("entailed", all, "fine"), 1, Vector("sec-4"), "verify@v1", "m")
      .toOption
      .get
    assertEquals(entailed.label, VerificationLabel.entailed)
    val record = EvidenceRecord(
      "x:fd1",
      registered = true,
      kind = "finding",
      content = Some(RecordContent.OfFinding(Deterministic.finding(finding).toOption.get))
    )
    assert(Verification.decide(record, entailed).isInstanceOf[Verification.Decision.Verify])
    val sneaky = Verification
      .parse(
        ClaimVerification("entailed", all + ("species" -> check(false)), "no"),
        1,
        Vector.empty,
        "verify@v1",
        "m"
      )
      .toOption
      .get
    assertEquals(sneaky.label, VerificationLabel.insufficiently_supported)
    Verification.decide(
      record,
      Verification
        .parse(
          ClaimVerification("contradicted", all + ("negation" -> check(false)), "no"),
          1,
          Vector.empty,
          "v",
          "m"
        )
        .toOption
        .get
    ) match
      case Verification.Decision.Reject(_, d) =>
        assertEquals(d.kind, DefectKind.negation_error); assert(!d.retryable)
      case other => fail(s"$other")
    Verification.decide(
      record,
      Verification
        .parse(
          ClaimVerification("insufficiently_supported", all + ("treatment" -> check(false)), "no"),
          1,
          Vector.empty,
          "v",
          "m"
        )
        .toOption
        .get
    ) match
      case Verification.Decision.Reject(_, d) =>
        assertEquals(d.kind, DefectKind.context_mismatch); assert(d.retryable)
      case other => fail(s"$other")
    assert(
      Verification
        .decide(
          record,
          Verification
            .parse(ClaimVerification("ambiguous", all, "unclear"), 1, Vector.empty, "v", "m")
            .toOption
            .get
        )
        .isInstanceOf[Verification.Decision.Review]
    )
    val hypothesis = EvidenceRecord(
      "x:cl1",
      registered = true,
      kind = "claim",
      content = Some(
        RecordContent.OfClaim(
          ClaimContent(
            "maybe",
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
    assert(Verification.decide(hypothesis, entailed).isInstanceOf[Verification.Decision.Review])
    assert(
      Verification
        .parse(ClaimVerification("entailed", all - "scope", "x"), 1, Vector.empty, "v", "m")
        .isLeft
    )
  }

  test(
    "the record entity keeps every attempt, bounds the retries, never verifies a hypothesis, and refuses an unlisted reviewer"
  ) {
    val span    = SourceSpan("src:file:paper-a", 1, "sec-4", 0, 10, "Duck hepat")
    val content = RecordContent.OfFinding(Deterministic.finding(finding).toOption.get)
    val k       = EventSourcedTestKit.of(FindingEntity, "src:file:paper-a:v1:fd1")
    k.call(FindingEntity.propose)(
      ProposeRecord(
        "finding",
        sourceRef,
        content,
        Vector(span),
        "model:extract@v1",
        Vector(sourceRef)
      )
    )
    val retryable = Rules.defect(DefectKind.context_mismatch, "conditions", retryable = true)
    assert(
      k.call(FindingEntity.repropose)(ReproposeRecord(content, Vector(span), retryable, "m"))
        .isError,
      "the first attempt is still open"
    )
    k.call(FindingEntity.rejected)(RecordRejected(1, retryable, None))
    k.call(FindingEntity.repropose)(ReproposeRecord(content, Vector(span), retryable, "m"))
    k.call(FindingEntity.rejected)(RecordRejected(2, retryable, None))
    k.call(FindingEntity.repropose)(ReproposeRecord(content, Vector(span), retryable, "m"))
    k.call(FindingEntity.rejected)(RecordRejected(3, retryable, None))
    assert(
      k.call(FindingEntity.repropose)(ReproposeRecord(content, Vector(span), retryable, "m"))
        .isError,
      "the budget is spent"
    )
    assertEquals(k.currentState.attempts.size, 3)
    assertEquals(
      k.currentState.attempts.map(_.outcome),
      Vector.fill(3)(Some(RecordStatus.rejected))
    )
    k.call(FindingEntity.quarantine)(QuarantineRecord(3, retryable))
    assertEquals(k.currentState.status, RecordStatus.quarantined)

    val c = EventSourcedTestKit.of(ClaimEntity, "src:file:paper-a:v1:cl9")
    c.call(ClaimEntity.propose)(
      ProposeRecord(
        "claim",
        sourceRef,
        RecordContent.OfClaim(
          ClaimContent(
            "maybe",
            ClaimKind.system_hypothesis,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            None,
            None
          )
        ),
        Vector(span),
        "model:x",
        Vector(sourceRef)
      )
    )
    val ok = VerificationRecord(
      1,
      VerificationLabel.entailed,
      CheckName.values.map(_.toString -> Check(true, "q", "n")).toMap,
      Vector("sec-4"),
      "v",
      "m",
      "r",
      java.time.Instant.now()
    )
    assert(c.call(ClaimEntity.verified)(RecordVerified(1, ok)).isError)
    c.call(ClaimEntity.requiresReview)(RecordRequiresReview(1, "hypothesis", Some(ok)))
    assert(
      c.call(ClaimEntity.review)(ReviewRecord("verify", "ada", "x")).isError,
      "a reviewer cannot verify a hypothesis either"
    )
    assert(c.call(ClaimEntity.review)(ReviewRecord("reject", "mallory", "x")).isError)
    assertEquals(
      c.call(ClaimEntity.review)(ReviewRecord("reject", "ada", "not established"))
        .replyValue
        .status,
      RecordStatus.rejected
    )
  }

  test("the named mutations change what their gate must catch") {
    val content = RecordContent.OfFinding(Deterministic.finding(finding).toOption.get)
    val span    = SourceSpan("s", 1, "sec-4", 0, 10, "Duck hepat")
    Mutations("endpoint_swap", content, Vector(span)).toOption.get._1 match
      case RecordContent.OfFinding(f) => assertEquals(f.observedEndpoint, "lipid accumulation")
      case _                          => fail("finding expected")
    Mutations("species_drop", content, Vector(span)).toOption.get._1 match
      case RecordContent.OfFinding(f) => assertEquals(f.species, None)
      case _                          => fail("finding expected")
    assertEquals(Mutations("span_break", content, Vector(span)).toOption.get._2.head.startOffset, 7)
    assert(Mutations("nonsense", content, Vector(span)).isLeft)
    assertEquals(Mutations.expectedDetection("negation_flip"), Some(DefectKind.negation_error))
  }
