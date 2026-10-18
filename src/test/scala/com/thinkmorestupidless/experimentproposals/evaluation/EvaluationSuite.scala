package com.thinkmorestupidless.experimentproposals.evaluation

import com.thinkmorestupidless.experimentproposals.evaluation.{
  CaseResult,
  Dataset,
  Evaluation,
  Report,
  RunResult
}
import com.thinkmorestupidless.experimentproposals.integration.ServiceFixture

import java.nio.file.Path
import scala.concurrent.duration.*

/**
 * The service-backed evaluation (SC-006, FR-050): every runnable case through the service in the
 * replay tier with the pinned prompts, rules, parser, embedding and cassettes; every passage
 * through the deterministic gate; the report written under `evals/reports/`. `just eval` runs this.
 */
class EvaluationSuite extends ServiceFixture:

  override val munitTimeout = 15.minutes

  private val evals    = Path.of("evals")
  private val fixtures = Path.of("evals/fixtures/papers")

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes(
      "facets@v1",
      "roundtrip@v1",
      "questions@v1",
      "extract@v1",
      "verify@v1",
      "reextract@v1",
      "assess@v1",
      "sufficiency@v1",
      "plan@v1",
      "candidates@v1",
      "design@v1"
    )

  private def question(id: String): String      = get(api(s"/questions/$id"))._2
  private def investigation(id: String): String = get(api(s"/workflows/investigation/$id"))._2
  private def ids(json: String, field: String): Vector[String] =
    s""""$field":\\[([^\\]]*)\\]""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .toVector
      .flatMap(_.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty))
  private def enumOf(json: String, field: String): Option[String] =
    s""""$field":\\{"type":"([a-z_]+)"\\}""".r.findFirstMatchIn(json).map(_.group(1))

  /** The reference case through the service; what it observed, in the dataset's terms. */
  private def runReference(c: EvalCase): CaseResult =
    c.fixtures.foreach(f =>
      if f.startsWith("paper") then recordsSettled(ingestFixture(f, extract = true))
      else ingestFixture(f)
    )
    val sources = get(api("/sources"))._2
    val parsed  = c.fixtures.size
    val complete = c.fixtures.count { f =>
      val id = "src:file:" + f.stripSuffix(".xml").stripSuffix(".txt")
      get(api(s"/sources/$id"))._2.contains("\"completeness\":{\"type\":\"complete\"}")
    }
    val (status, body) = post(
      api("/requests"),
      s"""{"text":${com.github.plokhotnyuk.jsoniter_scala.core.writeToString(c.request)(using
          com.thinkmorestupidless.ankka.core.Codecs.make[String]
        )},"submittedBy":"evaluation"}"""
    )
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review")(
      Option.when(investigation(id).contains("awaiting-facet-review"))(id)
    )
    val facets = objectsOf(get(api(s"/requests/$id/facets"))._2)
    val origins = facets.flatMap { f =>
      for
        n <- """"name":"([^"]+)"""".r.findFirstMatchIn(f).map(_.group(1)); o <- enumOf(f, "origin")
      yield n -> o
    }.toMap
    Seq("f1", "f2", "f3", "f8").foreach(f =>
      post(
        api(s"/facets/$id:$f/review"),
        """{"action":"confirm","reviewer":"ada","rationale":"ok"}"""
      )
    )
    assertEquals(post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")._1, 200)
    eventually("the lipid question is retrieved", within = 120.seconds)(
      Option.when(question(s"$id:q4").contains("\"status\":\"retrieved\""))(id)
    )
    val questions = objectsOf(get(api(s"/requests/$id/questions"))._2).flatMap(q =>
      """"question":"([^"]+)"""".r.findFirstMatchIn(q).map(_.group(1))
    )
    val recordIds = c.expected.relationships.keys.toVector.sorted
    assertEquals(
      post(
        api(s"/questions/$id:q4/assess"),
        s"""{"recordIds":[${recordIds.map(r => s""""$r"""").mkString(",")}]}"""
      )._1,
      202
    )
    eventually("the assessments")(
      Option.when("\"relationship\":".r.findAllIn(question(s"$id:q4")).size == recordIds.size)(id)
    )
    val assessments   = recordIds.map(r => r -> get(api(s"/assessments/$id:q4:$r"))._2)
    val relationships = assessments.flatMap((r, a) => enumOf(a, "relationship").map(r -> _)).toMap
    val applicability = assessments.flatMap((r, a) => enumOf(a, "applicability").map(r -> _)).toMap
    val sufficiency = c.expected.sufficiency.keys.toVector.map { decision =>
      assertEquals(
        post(api(s"/questions/$id:q4/sufficiency"), s"""{"intendedDecision":"$decision"}""")._1,
        202
      )
      val view = eventually(s"judged for $decision")(
        Option.when(question(s"$id:q4").contains(s""""intendedDecision":"$decision""""))(
          question(s"$id:q4")
        )
      )
      val judged = objectsOf(view.substring(view.indexOf("\"sufficiency\":[") + 14))
        .find(_.contains(s""""intendedDecision":"$decision""""))
        .get
      decision -> enumOf(judged, "status").getOrElse("")
    }.toMap
    val plan = get(api(s"/search-plans/$id:q4:plan"))._2
    val fused = ids(plan, "executionIds")
      .map(e => get(api(s"/search-executions/$e"))._2)
      .find(_.contains("\"fused\":true"))
      .getOrElse("")
    val eligible = fused.substring(
      math.max(0, fused.indexOf("\"eligibleResults\":")),
      math.max(0, fused.indexOf("\"exclusions\":"))
    )
    val retrieved =
      """"sourceId":"([^"]+)"""".r.findAllMatchIn(eligible).map(_.group(1)).toVector.distinct
    val excluded = """"sourceId":"([^"]+)"""".r
      .findAllMatchIn(fused.substring(math.max(0, fused.indexOf("\"exclusions\":"))))
      .map(_.group(1))
      .toVector
      .distinct
    val leaked = retrieved.count(c.expected.excluded.contains)
    assertEquals(post(api(s"/requests/$id/packages"), """{"reviewer":"ada"}""")._1, 202)
    eventually("packages built", within = 120.seconds)(
      Option.when(
        investigation(id).contains("awaiting-decision") || investigation(id).contains("failed")
      )(id)
    )
    val pkg = get(api(s"/packages/$id:q4:pk1"))._2
    CaseResult(
      c.id,
      ran = true,
      origins,
      questions,
      relationships,
      applicability,
      sufficiency,
      retrieved,
      excluded,
      leaked,
      complete,
      parsed,
      if pkg.contains("\"qualityStatus\":{\"type\":\"valid\"}") then 1 else 0,
      1
    )

  test(
    "the runnable cases and every passage: the report is written with its pins, per-stage measures, critical failures first and limitations"
  ) {
    val (cases, passages) = Dataset.load(evals).fold(p => fail(p.mkString("; ")), identity)
    val results = cases.map { c =>
      if c.runnable then c -> runReference(c)
      else c -> CaseResult(c.id, ran = false, reason = "no recorded model replies for this request")
    }
    val run    = RunResult(results, passages.map(p => p -> Evaluation.gate(p, fixtures)))
    val report = Report.build(run, "replay", Evaluation.pins("replay", "hash"), None, Vector.empty)
    val (json, md) = Report.write(report, evals.resolve("reports"))
    println(s"evaluation report: $json")
    val ref = results.find(_._1.runnable).get._2
    assert(ref.relationships.nonEmpty && ref.sufficiency.nonEmpty && ref.retrieved.nonEmpty, ref)
    assertEquals(ref.ineligibleLeaked, 0, "a retracted source leaked into the eligible results")
    assert(!report.blocking, s"critical failures: ${report.critical}")
    assert(
      report.stages.map(_.stage) == Vector(
        "requests",
        "ingestion",
        "retrieval",
        "extraction",
        "assessment",
        "sufficiency",
        "packages"
      )
    )
    val text = java.nio.file.Files.readString(md)
    assert(
      text.indexOf("## Critical failures (blocking)") < text.indexOf("## Retrieval") && text
        .contains("## Limitations"),
      text
    )
    assertEquals(get(api("/evals/latest"))._1, 200)
  }
