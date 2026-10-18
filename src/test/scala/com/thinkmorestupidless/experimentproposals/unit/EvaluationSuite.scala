package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.experimentproposals.evaluation.*

import java.nio.file.Path

class EvaluationSuite extends munit.FunSuite:

  private val evals    = Path.of("evals")
  private val fixtures = Path.of("evals/fixtures/papers")

  test(
    "the dataset loads, covers every adversarial category, and meets the minimum sizes (SC-012)"
  ) {
    val (cases, passages) = Dataset.load(evals).fold(p => fail(p.mkString("; ")), identity)
    assert(
      cases.size >= Dataset.MinCases && passages.size >= Dataset.MinPassages,
      (cases.size, passages.size)
    )
    Dataset.Categories.foreach(c => assert(cases.exists(_.category == c), c))
    assert(
      cases.exists(_.runnable) && cases.exists(_.expected.uncertain) && cases.exists(
        _.expected.acceptableAlternatives.nonEmpty
      )
    )
    assert(passages.exists(_.uncertain) && passages.exists(_.expectedLabel == "rejected_by_gate"))
  }

  test(
    "the deterministic gate rejects a quotation that is in no section and lets a real one through to the verifier"
  ) {
    val (_, passages) = Dataset.load(evals).toOption.get
    val broken        = Evaluation.gate(passages.find(_.id == "a-quotation-broken").get, fixtures)
    assertEquals(broken.observedLabel, Some("rejected_by_gate"))
    assertEquals(broken.observedDefect, Some("span_unresolved"))
    val real = Evaluation.gate(passages.find(_.id == "a-proliferation-entailed").get, fixtures)
    assert(!real.ran && real.reason.contains("not recorded"), real)
  }

  test(
    "metrics are per stage; a critical failure blocks regardless of the aggregate; the report states its limitations"
  ) {
    val (cases, passages) = Dataset.load(evals).toOption.get
    val ref               = cases.find(_.runnable).get
    val good = CaseResult(
      ref.id,
      ran = true,
      facetOrigins = Map(
        "product"             -> "explicit",
        "production approach" -> "inferred",
        "objective"           -> "explicit",
        "biological source"   -> "domain_derived"
      ),
      questions = Vector(
        "Has relevant lipid accumulation been demonstrated?",
        "Which cell types?",
        "Can cells be maintained and proliferated?",
        "Which measurements?",
        "What operational constraints?",
        "What properties?"
      ),
      relationships = Map(
        "src:file:paper-a:v1:fd1" -> "not_applicable",
        "src:file:paper-b:v1:fd1" -> "supports",
        "src:file:paper-c:v1:fd1" -> "qualifies"
      ),
      applicability = Map(
        "src:file:paper-a:v1:fd1" -> "indirect",
        "src:file:paper-b:v1:fd1" -> "indirect",
        "src:file:paper-c:v1:fd1" -> "indirect"
      ),
      sufficiency = Map(
        "recommend production feasibility" -> "insufficient_for_recommendation",
        "propose a feasibility experiment on lipid accumulation" -> "sufficient_for_proposed_next_step"
      ),
      retrieved = Vector("src:file:paper-a", "src:file:paper-b", "src:file:paper-c"),
      excluded = Vector("src:file:retracted"),
      parseComplete = 3,
      parsed = 4,
      packagesValid = 1,
      packages = 1
    )
    val run = RunResult(
      Vector(ref -> good) ++ cases
        .filterNot(_.runnable)
        .map(c => c -> CaseResult(c.id, ran = false, reason = "no replies")),
      passages.map(p => p -> Evaluation.gate(p, fixtures))
    )
    val report = Report.build(run, "replay", Evaluation.pins("replay", "hash"), None, Vector.empty)
    assertEquals(
      report.stages.map(_.stage),
      Vector(
        "requests",
        "ingestion",
        "retrieval",
        "extraction",
        "assessment",
        "sufficiency",
        "packages"
      )
    )
    assert(!report.blocking && report.critical.isEmpty, report.critical)
    val facetRecall = report.stages.head.measures.find(_.name == "facet_recall").get
    assertEquals(facetRecall.value, Some(1.0), "an acceptable alternative counts")
    assertEquals(
      report.stages
        .find(_.stage == "retrieval")
        .get
        .measures
        .find(_.name == "retrieval_recall")
        .get
        .value,
      Some(1.0)
    )
    val unsafe = run.copy(cases =
      run.cases.map((c, r) =>
        if c.runnable then
          c -> r.copy(sufficiency =
            r.sufficiency + ("recommend production feasibility" -> "sufficient_for_proposed_next_step")
          )
        else c -> r
      )
    )
    val blocked = Report.build(unsafe, "replay", Map.empty, None, Vector.empty)
    assert(
      blocked.blocking && blocked.critical.exists(_.kind == "unsafe_recommendation"),
      blocked.critical
    )
    val md = Report.markdown(blocked)
    assert(md.indexOf("## Critical failures (blocking)") < md.indexOf("## Retrieval"))
    assert(md.contains("## Limitations") && md.contains("stand-in") && md.contains("did not run"))
    assertEquals(
      Baseline.compare(report, Path.of("evals/no-such-baseline.json")),
      Left("no baseline yet")
    )
  }

  test(
    "each named mutation changes exactly what it says and names the defect that should catch it"
  ) {
    val f = FindingContent(
      Some("goose"),
      None,
      None,
      Vector("stirred suspension"),
      "cell number",
      "Cell number did not increase; the suspension culture method was unsuccessful",
      OutcomeDirection.no_change,
      Vector.empty,
      Vector.empty,
      None,
      "low",
      Map.empty
    )
    val spans        = Vector(SourceSpan("src:c", 1, "sec-2", 10, 30, "did not increase"))
    val (flipped, _) = Mutations("negation_flip", RecordContent.OfFinding(f), spans).toOption.get
    assert(
      flipped
        .asInstanceOf[RecordContent.OfFinding]
        .finding
        .reportedOutcome
        .startsWith("Cell number increased")
    )
    val (success, _) =
      Mutations("failure_to_success", RecordContent.OfFinding(f), spans).toOption.get
    assert(
      success.asInstanceOf[RecordContent.OfFinding].finding.reportedOutcome.contains("successful")
    )
    val (dropped, _) = Mutations("species_drop", RecordContent.OfFinding(f), spans).toOption.get
    assertEquals(dropped.asInstanceOf[RecordContent.OfFinding].finding.species, None)
    val (_, broken) = Mutations("span_break", RecordContent.OfFinding(f), spans).toOption.get
    assertEquals(broken.head.startOffset, 17)
    assertEquals(
      Mutations.expectedDetection("citation_mismatch"),
      Some(DefectKind.unsupported_by_span)
    )
    assert(Mutations("nothing", RecordContent.OfFinding(f), spans).isLeft)
    assertEquals(Mutations.names.size, 6)
  }
