package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.consumers.{
  ImpactTrigger,
  SourceImpactTrigger
}
import com.thinkmorestupidless.experimentproposals.application.views.Dependents
import com.thinkmorestupidless.experimentproposals.application.workflows.ImpactWorkflow
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.sources.{SourceEvent, SourceSpan}
import com.thinkmorestupidless.experimentproposals.rules.Rules
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, KeyedViewTestKit}

import java.time.Instant

class ImpactUnitSuite extends munit.FunSuite:

  private val now = Instant.parse("2026-10-17T12:00:00Z")

  test("the dependents view writes one edge per (on, dependent) for assessments and designs") {
    val kit = KeyedViewTestKit(Dependents)
    val q   = Ref(RecordKind.question, "rq:q4", 1)
    val b   = Ref(RecordKind.finding, "src:b:v1:fd1", 1)
    kit.change(
      Dependents.assessments,
      "rq:q4:src:b:v1:fd1",
      AssessmentEvent.Proposed(
        q,
        b,
        Relationship.supports,
        Applicability.indirect,
        Map.empty,
        "r",
        Vector.empty,
        "model",
        Vector.empty,
        Vector(q, b),
        now
      )
    )
    assertEquals(
      kit.rows.keySet,
      Set(
        "question:rq:q4:v1|assessment:rq:q4:src:b:v1:fd1:v1",
        "finding:src:b:v1:fd1:v1|assessment:rq:q4:src:b:v1:fd1:v1"
      )
    )
    val row = kit.row("finding:src:b:v1:fd1:v1|assessment:rq:q4:src:b:v1:fd1:v1").get
    assertEquals(
      (row.onUnversioned, row.dependentKind, row.dependentId),
      ("finding:src:b:v1:fd1", "assessment", "rq:q4:src:b:v1:fd1")
    )
    val span = SourceSpan("src:b", 1, "sec-2", 0, 4, "Cell")
    val sections = DesignSections(
      "Q",
      Vector(Grounded("B", Vector(Ref(RecordKind.claim, "src:b:v1:cl1", 1)), Vector(span))),
      "H",
      "M",
      "V",
      "E",
      "S",
      "R",
      "L",
      "A",
      "O",
      Manifest(Vector(Ref(RecordKind.claim, "src:b:v1:cl1", 1)), Vector(span), Vector.empty)
    )
    kit.change(
      Dependents.designs,
      "rq:design",
      DesignEvent.DraftGenerated(
        Ref(RecordKind.request, "rq", 1),
        1,
        Ref(RecordKind.approach, "rq:c1", 1),
        Ref(RecordKind.decision, "rq:d1", 1),
        sections,
        Vector.empty,
        "design@v1",
        "m",
        Vector.empty,
        now
      )
    )
    assert(kit.rows.contains("source:src:b:v1|design:rq:design:v1"), kit.rows.keySet)
    assert(kit.rows.contains("claim:src:b:v1:cl1:v1|design:rq:design:v1"))
    kit.change(Dependents.designs, "rq:design", DesignEvent.Submitted(1, "operator", now))
    // Two edges for the assessment, five for the design; a submission adds none.
    assertEquals(kit.rows.size, 7)
  }

  test("which changes start an impact walk, and under which id") {
    val changed = ImpactTrigger.ofSource(
      SourceEvent.StatusChanged(SourceStatus.retracted, "notice", "ada", now),
      "src:b",
      1
    )
    assertEquals(changed.map(_.changedRef), Some(Ref(RecordKind.source, "src:b", 1)))
    assert(changed.exists(_.reason.contains("retracted")))
    assertEquals(
      ImpactTrigger
        .ofSource(
          SourceEvent.ContentFetched(2, "h", now, None, RetentionRestriction.none, None),
          "src:b",
          2
        )
        .map(_.changedRef),
      Some(Ref(RecordKind.source, "src:b", 1))
    )
    assertEquals(
      ImpactTrigger.ofSource(
        SourceEvent.ContentFetched(1, "h", now, None, RetentionRestriction.none, None),
        "src:b",
        1
      ),
      None
    )
    assertEquals(ImpactTrigger.ofSource(SourceEvent.IngestionNoted("w"), "src:b", 1), None)
    val defect = Rules.defect(DefectKind.endpoint_mismatch, "x")
    assert(
      ImpactTrigger
        .ofRecord(RecordKind.finding, RecordEvent.Rejected(2, defect, None, now), "src:a:v1:fd1")
        .exists(_.changedRef.kind == RecordKind.finding)
    )
    assert(
      ImpactTrigger
        .ofRecord(
          RecordKind.claim,
          RecordEvent.Verified(
            1,
            VerificationRecord(
              1,
              VerificationLabel.entailed,
              Map.empty,
              Vector.empty,
              "verify@v1",
              "m",
              "ok",
              now
            ),
            now
          ),
          "src:a:v1:cl1"
        )
        .isEmpty
    )
    assertEquals(
      ImpactWorkflow.idFor(Ref(RecordKind.source, "src:b", 1), 7L),
      "impact:source:src:b:v1:s7"
    )
    // The consumer ignores what does not propagate, without calling anything.
    val kit = ConsumerTestKit.of(SourceImpactTrigger)
    kit.onMessage(SourceEvent.IngestionNoted("w"), "src:b")
  }
