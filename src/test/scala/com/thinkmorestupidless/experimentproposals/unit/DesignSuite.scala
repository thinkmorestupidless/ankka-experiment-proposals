package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.DesignEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.experimentproposals.domain.request.Facet
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.experimentproposals.rules.DesignRules
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class DesignSuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    Configured.reviewers = Reviewers.fromInline("ada:reviewer").toOption.get

  private val span = SourceSpan("src:a", 1, "sec-1", 0, 5, "Cells")
  private def claim(id: String, status: RecordStatus) =
    EvidenceRecord(
      id,
      registered = true,
      kind = "claim",
      sourceRef = Some(Ref(RecordKind.source, "src:a", 1)),
      content = Some(
        RecordContent.OfClaim(
          ClaimContent(
            "P",
            ClaimKind.normalised_claim,
            Vector.empty,
            Vector.empty,
            Vector("duck"),
            Some("duck"),
            None
          )
        )
      ),
      spans = Vector(span),
      status = status
    )
  private val a = claim("src:a:v1:cl1", RecordStatus.verified)
  private val sections = DesignSections(
    "Q",
    Vector(Grounded("B", Vector(Ref(RecordKind.claim, a.id, 1)), Vector(span))),
    "H",
    "M",
    "V",
    "E",
    "S",
    "R",
    "L",
    "A",
    "O",
    Manifest(Vector(Ref(RecordKind.claim, a.id, 1)), Vector(span), Vector.empty)
  )
  private val facets = Vector(
    Facet(
      "rq:f1",
      registered = true,
      name = "product",
      importance = Importance.mandatory,
      status = FacetStatus.confirmed
    )
  )
  private val approach = ExperimentalApproach(
    "rq:c1",
    registered = true,
    title = "T",
    hypothesis = "H",
    biologicalModel = "M",
    facetRefsAddressed = Vector(Ref(RecordKind.facet, "rq:f1", 1))
  )
  private def input(
      s: DesignSections = sections,
      claims: Map[String, EvidenceRecord] = Map(a.id -> a),
      resolves: Boolean = true,
      ap: ExperimentalApproach = approach
  ) =
    DesignRules.Input(s, claims, _ => resolves, facets, ap)
  private val req = Ref(RecordKind.request, "rq", 1)
  private def generate(s: DesignSections = sections, changes: Vector[String] = Vector.empty) =
    GenerateDesign(
      req,
      Ref(RecordKind.approach, "rq:c1", 1),
      Ref(RecordKind.decision, "rq:d1", 1),
      s,
      DesignRules.check(input()),
      "design@v1",
      "model:design@v1",
      changes
    )

  test(
    "design@v1: twelve sections, verified claims, resolving spans, mandatory facets accounted for"
  ) {
    val ok = DesignRules.check(input())
    assert(ok.forall(_.passed), ok.filterNot(_.passed))
    val empty = DesignRules.check(input(sections.copy(hypothesis = " ")))
    assert(
      empty.exists(c =>
        c.name == DesignRules.sectionsPresent.key && !c.passed && c.detail.contains("hypothesis")
      )
    )
    assertEquals(
      DesignSections.missing(sections.copy(background = Vector.empty)),
      Vector("background")
    )
    val unverified =
      DesignRules.check(input(claims = Map(a.id -> a.copy(status = RecordStatus.requires_review))))
    assert(unverified.exists(c => c.name == DesignRules.claimsVerified.key && !c.passed))
    val unresolved = DesignRules.check(input(resolves = false))
    assert(unresolved.exists(c => c.name == DesignRules.spansResolve.key && !c.passed))
    val unaccounted =
      DesignRules.check(input(ap = approach.copy(facetRefsAddressed = Vector.empty)))
    assert(
      unaccounted.exists(c =>
        c.name == DesignRules.facetsAccounted.key && !c.passed && c.detail.contains("product")
      )
    )
  }

  test(
    "each generation is a new version; an approved version's content is unchanged by any later event"
  ) {
    val k = EventSourcedTestKit.of(DesignEntity, "rq:design")
    assertEquals(
      k.call(DesignEntity.generate)(generate()).replyValue.versions.map(_.status),
      Vector(DesignStatus.draft)
    )
    assert(
      k.call(DesignEntity.recordApproval)(
        RecordApproval(1, ApprovalDecision.approved, "ada", "fine")
      ).isError,
      "not under review yet"
    )
    assert(k.call(DesignEntity.submit)(SubmitDesign(1, "nobody")).isError)
    assertEquals(
      k.call(DesignEntity.submit)(SubmitDesign(1, "operator")).replyValue.version(1).map(_.status),
      Some(DesignStatus.under_review)
    )
    assert(
      k.call(DesignEntity.recordApproval)(
        RecordApproval(1, ApprovalDecision.approved, "nobody", "fine")
      ).isError,
      "an unlisted reviewer"
    )
    assert(
      k.call(DesignEntity.recordApproval)(RecordApproval(1, ApprovalDecision.approved, "ada", " "))
        .isError,
      "needs a rationale"
    )
    assert(
      k.call(DesignEntity.recordApproval)(
        RecordApproval(1, ApprovalDecision.changes_requested, "ada", "x", Vector.empty)
      ).isError,
      "changes must be named"
    )
    val approved = k
      .call(DesignEntity.recordApproval)(
        RecordApproval(1, ApprovalDecision.approved, "ada", "fine")
      )
      .replyValue
    assertEquals(approved.version(1).map(_.status), Some(DesignStatus.approved))
    assertEquals(
      approved.approvals.map(a => (a.version, a.decision, a.reviewer)),
      Vector((1, ApprovalDecision.approved, "ada"))
    )
    val before = approved.version(1).get.sections
    val v2 = k
      .call(DesignEntity.generate)(generate(sections.copy(hypothesis = "H2"), Vector("more")))
      .replyValue
    assertEquals(v2.versions.map(_.version), Vector(1, 2))
    assertEquals(
      v2.version(1).map(_.status),
      Some(DesignStatus.approved),
      "an approved version is not superseded"
    )
    assertEquals(v2.version(1).map(_.sections), Some(before), "content unchanged")
    assertEquals(v2.version(2).map(_.changesApplied), Some(Vector("more")))
    assert(
      k.call(DesignEntity.recordApproval)(
        RecordApproval(1, ApprovalDecision.rejected, "ada", "late")
      ).isError,
      "approved is final"
    )
    k.call(DesignEntity.submit)(SubmitDesign(2, "operator"))
    val v3 = k.call(DesignEntity.generate)(generate(sections.copy(hypothesis = "H3"))).replyValue
    assertEquals(v3.version(2).map(_.status), Some(DesignStatus.superseded))
    k.call(DesignEntity.flagForReview)(
      FlagDesign(Ref(RecordKind.source, "src:a", 2), 1, "the source changed")
    )
    assertEquals(
      k.currentState.flags.map(f => (f.version, f.reason)),
      Vector((1, "the source changed"))
    )
    assertEquals(k.currentState.version(1).map(_.sections), Some(before))
  }

  test(
    "a rejection and a request for changes are recorded against the version with the reviewer and rationale"
  ) {
    val k = EventSourcedTestKit.of(DesignEntity, "rq2:design")
    k.call(DesignEntity.generate)(generate())
    k.call(DesignEntity.submit)(SubmitDesign(1, "ada"))
    val changes = k
      .call(DesignEntity.recordApproval)(
        RecordApproval(
          1,
          ApprovalDecision.changes_requested,
          "ada",
          "no threshold",
          Vector("state a threshold")
        )
      )
      .replyValue
    assertEquals(changes.version(1).map(_.status), Some(DesignStatus.changes_requested))
    assertEquals(changes.approvals.head.requestedChanges, Vector("state a threshold"))
    k.call(DesignEntity.generate)(generate(changes = Vector("state a threshold")))
    k.call(DesignEntity.submit)(SubmitDesign(2, "operator"))
    val rejected = k
      .call(DesignEntity.recordApproval)(
        RecordApproval(2, ApprovalDecision.rejected, "ada", "still no")
      )
      .replyValue
    assertEquals(rejected.version(2).map(_.status), Some(DesignStatus.rejected))
    assertEquals(rejected.approvals.size, 2)
    assertEquals(rejected.version(1).map(_.status), Some(DesignStatus.superseded))
  }

  test("the rendering references only manifest claims, carries the banner, and shows the history") {
    val v = DesignVersion(
      1,
      Ref(RecordKind.approach, "rq:c1", 1),
      Ref(RecordKind.decision, "rq:d1", 1),
      sections,
      DesignStatus.under_review,
      Vector.empty,
      "design@v1",
      java.time.Instant.parse("2026-10-17T10:00:00Z"),
      "model:design@v1"
    )
    val md = Rendering.render("rq", v, Vector.empty)
    assert(
      md.contains("Proposal awaiting review") && md.contains("## 12. Evidence manifest") && md
        .contains("## 7. Success, failure and inconclusive criteria"),
      md
    )
    assertEquals(Rendering.claimsReferenced(md), Vector(a.id))
    assert(
      Rendering.claimsReferenced(md).forall(sections.evidenceManifest.claimRefs.map(_.id).contains)
    )
    val approval = Approval(
      1,
      ApprovalDecision.rejected,
      "ada",
      java.time.Instant.parse("2026-10-17T11:00:00Z"),
      "no threshold"
    )
    val rejected = Rendering.render("rq", v.copy(status = DesignStatus.rejected), Vector(approval))
    assert(
      rejected.contains("**rejected** by ada") && rejected.contains("no threshold") && !rejected
        .contains("**Approved**"),
      rejected
    )
    val approved = Rendering.render(
      "rq",
      v.copy(status = DesignStatus.approved),
      Vector(approval.copy(decision = ApprovalDecision.approved))
    )
    assert(approved.contains("**Approved** by ada"), approved)
    // A section the design lacks is not asserted: the rendering writes the record's text, nothing else.
    assert(!md.contains("null"))
  }
