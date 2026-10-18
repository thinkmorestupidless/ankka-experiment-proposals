package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.experimentproposals.evaluation.{Dataset, Evaluation}
import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import java.nio.file.{Files, Path}

/** Steps for `features/assurance/fault-injection.feature` and `evaluation.feature`. */
trait AssuranceSteps extends GherkinSuite with DesignSteps:

  private var faultSource: String   = ""
  private var faultedRecord: String = ""
  private var faultWorkflow: String = ""
  private var faultedJson: String   = ""
  private var reportJson: String    = ""
  private var reportMd: String      = ""
  private var safeguard: String     = ""
  private var mutantResult: String  = ""

  /** The feature's words for a fault, the mutation and the record it is injected into. */
  private def mutationFor(fault: String): (String, String) =
    val f = fault.trim
    if f.contains("\"proliferation\" becomes \"lipid accumulation\"") then ("endpoint_swap", "fd1")
    else if f.contains("\"did not increase\" becomes \"increased\"") then ("negation_flip", "fd1")
    else if f.contains("species qualification is removed") then ("species_drop", "fd1")
    else if f.contains("\"failure\" becomes \"success\"") then ("failure_to_success", "fd1")
    else if f.contains("resolves but does not support") then ("citation_mismatch", "cl1")
    else fail(s"no mutation for '$fault'")

  private val detections: Map[String, String] = Map(
    "unsupported endpoint"         -> "endpoint_mismatch",
    "critical negation error"      -> "negation_error",
    "overgeneralisation"           -> "species_overgeneralisation",
    "failure presented as success" -> "failure_as_success",
    "unsupported by span"          -> "unsupported_by_span"
  )

  /** Each fault works on its own copy of the paper, named after the fault (`<file>@<fault>`). */
  private def copyFor(name: String, mutation: String): String =
    val file = fixtureOf.getOrElse(name, fail(s"no fixture for $name"))
    val id   = ingest(s"$file@$mutation", extract = true)
    settled(id)
    id

  private def inject(mutation: String, suffix: String): Unit =
    faultedRecord = s"$faultSource:v1:$suffix"
    if recordJson(faultedRecord).contains(s"operator:fault:$mutation") then
      // Already injected on this copy by an earlier scenario: nothing to add.
      faultWorkflow = ""
      return
    val (status, body) = post(
      "/faults:inject",
      s"""{"target":{"kind":"${
          if suffix.startsWith("fd") then "finding" else "claim"
        }","id":"$faultedRecord"},"mutation":"$mutation"}"""
    )
    assertEquals(status, 202, body)
    faultWorkflow =
      """"workflowId":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))

  private def verified(): String =
    faultedJson =
      try awaitStatus(faultedRecord, "rejected", "quarantined", "requires_review")
      catch
        case e: AssertionError =>
          // Name what the verification asked for, so a missing cassette is visible.
          val (_, run) = get(s"/runs/run-$faultWorkflow")
          fail(s"${e.getMessage}; run: $run")
    faultedJson

  // ── fault-injection.feature ──────────────────────────────────────────────────

  private var faultPaper: String = ""

  Given("the verified extraction from the source {string}") { (name: String) =>
    faultPaper = name
  }

  Given("the extraction is altered so that {}") { (fault: String) =>
    val (mutation, suffix) = mutationFor(fault)
    faultSource = copyFor(faultPaper, mutation)
    inject(mutation, suffix)
  }

  When("the altered extraction is verified")(() => verified())

  Then("the fault is detected as {string}") { (detection: String) =>
    val kind = detections.getOrElse(detection, fail(s"no defect kind for '$detection'"))
    if !faultedJson.contains(s""""defect":{"kind":{"type":"$kind"}""") then
      // Name what the verification asked for, so a missing cassette is visible in the failure.
      val (_, run) = get(s"/runs/run-$faultWorkflow")
      fail(s"expected $kind; record: ${faultedJson.take(600)}; run: $run")
  }

  Then("the altered extraction is not admitted as affirmative evidence") { () =>
    assert(
      faultedJson.contains("\"status\":{\"type\":\"rejected\"}") || faultedJson.contains(
        "\"status\":{\"type\":\"quarantined\"}"
      ),
      faultedJson
    )
    assert(!faultedJson.contains("\"status\":{\"type\":\"verified\"}"), faultedJson)
  }

  Given(
    "the extraction from the source {string} altered so that the endpoint {string} becomes {string}"
  ) { (name: String, from: String, to: String) =>
    assertEquals((from, to), ("proliferation", "lipid accumulation"))
    faultSource = copyFor(name, "endpoint_swap")
    inject("endpoint_swap", "fd1")
  }

  Given("the fault has been detected") { () =>
    val j = verified()
    assert(j.contains("\"defect\":{\"kind\":{\"type\":\"endpoint_mismatch\"}"), j)
  }

  When("sufficiency is judged for the scientific question {string}") { (text: String) =>
    Seq("Paper B", "Paper C").foreach(ingestPaper)
    referenceQuestion(text)
    // The faulted record of Paper A is no longer verified; the assessment covers what is.
    assessRecords(Seq("src:file:paper-b:v1:fd1", "src:file:paper-c:v1:fd1"))
    judge("recommend production feasibility")
  }

  Then("the lipid accumulation gap persists") { () =>
    assert(
      lastSufficiencyJson.contains("\"status\":{\"type\":\"insufficient_for_recommendation\"}"),
      lastSufficiencyJson
    )
    assert(lastSufficiencyJson.contains("\"criticalGapIds\":[\""), lastSufficiencyJson)
    val gaps = get(s"/questions/$questionId/gaps")._2
    assert(gaps.toLowerCase.contains("duck or goose hepatocytes"), gaps)
    assert(
      !lastQuestionView.contains(s""""id":"$questionId:src:file:paper-a@endpoint_swap""""),
      "the faulted record was not assessed"
    )
  }

  // ── a disabled safeguard (SC-013) ────────────────────────────────────────────

  Given("the safeguard {string} is disabled in a disposable copy of the system") { (name: String) =>
    safeguard = name
    val file = Path.of("scripts/mutants/last-run.json")
    assert(Files.exists(file), "no scripts/mutants/last-run.json: run `just mutants` first")
    mutantResult = Files.readString(file)
  }

  When("the independent test suite runs") { () =>
    val key = safeguard match
      case "claim eligibility"    => "bypass-eligibility"
      case "the species rule"     => "remove-species-rule"
      case "source span matching" => "accept-mismatched-span"
      case other                  => fail(s"no mutant for '$other'")
    assert(mutantResult.contains(s""""mutant":"$key""""), mutantResult)
    mutantResult =
      objectsOf(mutantResult).find(_.contains(s""""mutant":"$key"""")).getOrElse(fail(mutantResult))
  }

  Then("at least one independent test fails") { () =>
    assert(mutantResult.contains("\"killed\":true"), mutantResult)
  }

  // ── evaluation.feature ───────────────────────────────────────────────────────

  Given("the evaluation dataset") { () =>
    val loaded = Dataset.load(Path.of("evals"))
    assert(loaded.isRight, loaded.left.getOrElse(Vector.empty).mkString("; "))
  }

  When("an evaluation run completes") { () =>
    val report     = Evaluation.offline(Path.of("evals"), Path.of("evals/fixtures/papers"))
    val (json, md) = Evaluation.write(report, Path.of("evals/reports"))
    reportJson = Files.readString(json)
    reportMd = Files.readString(md)
  }

  Then("retrieval, extraction, assessment and sufficiency are reported separately") { () =>
    Seq("retrieval", "extraction", "assessment", "sufficiency").foreach(stage =>
      assert(s""""stage":\\s*"$stage"""".r.findFirstIn(reportJson).isDefined, stage)
    )
    Seq("## Retrieval", "## Extraction", "## Assessment", "## Sufficiency").foreach(h =>
      assert(reportMd.contains(h), h)
    )
  }

  Then("critical failures are listed as a blocking category regardless of aggregate scores") { () =>
    assert(
      reportJson.contains("\"critical\"") && reportJson.contains("\"blocking\""),
      reportJson.take(300)
    )
    assert(reportMd.contains("## Critical failures (blocking)"), reportMd)
    assert(
      reportMd.indexOf("## Critical failures (blocking)") < reportMd.indexOf("## Retrieval"),
      "critical failures come first"
    )
  }

  Then("the limitations of the evaluation are stated") { () =>
    assert(
      reportMd.contains("## Limitations") && reportJson.contains("\"limitations\""),
      reportMd
    )
    assert(reportMd.contains("stand-in") && reportMd.contains("authored"), reportMd)
  }
