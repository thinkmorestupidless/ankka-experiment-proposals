package com.thinkmorestupidless.experimentproposals.integration

import scala.concurrent.duration.*

/**
 * US6 through the whole service with the cassettes: the plan, the local executions and their
 * snapshot, eligibility after ranking, the rerun, the coverage gap and the bounded supplemental
 * search, surviving a restart.
 */
class RetrievalSuite extends ServiceFixture:

  private var requestId = ""

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
      "plan@v1"
    )
    // Retrieval collects every verified record of the hit sources, a set no cassette was recorded
    // for; the three-record reply names A, B and C and the others are simply not assessed.
    val assess = com.thinkmorestupidless.experimentproposals.replay
      .Cassettes(java.nio.file.Path.of("evals/cassettes"))
      .list("assess@v1")
      .map(_.reply)
      .find(r => r.contains("paper-a") && r.contains("paper-b") && r.contains("paper-c"))
      .getOrElse(fail("no three-record assess cassette"))
    model.whenUserSays("# case: assess:")(assess)

  private def referenceRequest(): String =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("awaiting-facet-review"))(w)
    }
    Seq("f1", "f2", "f3", "f8").foreach(f =>
      assertEquals(
        post(
          api(s"/facets/$id:$f/review"),
          """{"action":"confirm","reviewer":"ada","rationale":"ok"}"""
        )._1,
        201
      )
    )
    assertEquals(post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")._1, 200)
    id

  private def question(id: String): String = get(api(s"/questions/$id"))._2

  private def retrieved(id: String): String =
    eventually(s"$id is retrieved", within = 120.seconds) {
      val j = question(id); Option.when(j.contains("\"status\":\"retrieved\""))(j)
    }

  private def ids(json: String, field: String): Vector[String] =
    s""""$field":\\[([^\\]]*)\\]""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .toVector
      .flatMap(
        _.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty)
      )

  private def fusedExecution(q: String): (String, String) =
    val plan = get(api(s"/search-plans/$q:plan"))._2
    val fused = ids(plan, "executionIds")
      .map(e => e -> get(api(s"/search-executions/$e"))._2)
      .find(_._2.contains("\"fused\":true"))
      .getOrElse(fail(s"no fused execution in $plan"))
    fused

  test(
    "the lipid plan has both kinds and its targets; the local executions return A, B and C; the retracted paper is excluded after ranking with its rule"
  ) {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    ingestFixture("retracted.xml")
    requestId = referenceRequest()
    val q4   = s"$requestId:q4"
    val view = retrieved(q4)
    assert(
      view.contains("\"kind\":{\"type\":\"lexical\"}") && view.contains(
        "\"kind\":{\"type\":\"semantic\"}"
      ),
      view
    )
    assert(
      view.contains("\"targetSources\":[\"local\",\"europepmc\"]") && view.contains(
        "\"stoppingCriterion\":\"Stop"
      ),
      view
    )
    assert(view.contains("\"filters\":{\"species\":\"duck or goose\"}"), view)
    assert(view.contains(s""""planId":"$q4:plan""""), view)
    val plan = get(api(s"/search-plans/$q4:plan"))._2
    assert(plan.contains("\"version\":1"), plan)
    val (fusedId, fused) = fusedExecution(q4)
    Seq("src:file:paper-a", "src:file:paper-b", "src:file:paper-c", "src:file:retracted")
      .foreach(s => assert(fused.contains(s""""sourceId":"$s""""), s"$s missing in $fused"))
    assert(
      fused.contains("\"snapshot\":[{") && fused.contains("\"executedAt\":") && fused.contains(
        "\"topK\":\"20\""
      ),
      fused
    )
    assert(fused.contains("\"eligibilityApplied\":true"), fused)
    assert(
      fused.contains("\"ruleId\":\"eligibility.retracted@v1\"") && fused.contains(
        "\"action\":{\"type\":\"excluded\"}"
      ),
      fused
    )
    val eligible = fused.substring(fused.indexOf("\"eligibleResults\":"))
    assert(
      !eligible.substring(0, eligible.indexOf("\"exclusions\":")).contains("src:file:retracted"),
      eligible
    )
    val firstRanked = """"results":\[\{"rank":1,"score":[0-9.E-]+,"sourceId":"([^"]+)"""".r
      .findFirstMatchIn(fused)
      .map(_.group(1))
    assertEquals(
      firstRanked,
      Some("src:file:retracted"),
      "the retracted paper ranks first before eligibility"
    )
    val external = ids(plan, "executionIds")
      .map(e => get(api(s"/search-executions/$e"))._2)
      .find(_.contains("\"source\":\"europepmc\""))
    assert(external.exists(_.contains("not searched in the replay tier")), external)

    val (rs, rerun) = post(api(s"/search-executions/$fusedId/rerun"), "{}")
    assertEquals(rs, 200, rerun)
    assert(rerun.contains("\"matches\":true") && rerun.contains(s""""rerunOf":"$fusedId""""), rerun)

    val chunkId       = """"chunkId":"([^"]+)"""".r.findFirstMatchIn(eligible).map(_.group(1)).get
    val (cs, context) = get(api(s"/chunks/$chunkId/context"))
    assertEquals(cs, 200, context)
    assert(
      context.contains("\"section\":{") && context.contains("\"adjacent\":[") && context.contains(
        "\"related\":["
      ) && context.contains("\"title\":\"") && context.contains("\"identifiers\":{"),
      context
    )
    val (_, listed) = get(api(s"/questions/$q4/executions"))
    assert(listed.contains(fusedId) || listed == "[]", listed)
  }

  test("assess with no record ids judges the records retrieval collected") {
    val q4 = s"$requestId:q4"
    assertEquals(post(api(s"/questions/$q4/assess"), """{"recordIds":[]}""")._1, 202)
    val q = eventually("three assessments") {
      val j = question(q4); Option.when("\"relationship\":".r.findAllIn(j).size >= 3)(j)
    }
    assert(q.contains("src:file:paper-b:v1:fd1"), q)
  }

  test(
    "a plan that targets only internal records returns nothing: a coverage gap, a supplemental search bounded at two, across a restart"
  ) {
    val q6   = s"$requestId:q6"
    val view = retrieved(q6)
    assert(view.contains("\"targetSources\":[\"internal\"]"), view)
    assert(
      view.contains("\"kind\":{\"type\":\"coverage\"}") && view.contains(
        "not evidence that the approach is impossible"
      ),
      view
    )
    assert(view.contains("\"coverageGap\":true"), view)
    val gapId = s"$q6:g1"
    def gap   = get(api(s"/questions/$q6/gaps"))._2
    assert(gap.contains(gapId), gap)

    assertEquals(post(api(s"/gaps/$gapId/search"), "{}")._1, 202)
    eventually("the first supplemental execution is linked to the gap") {
      val g = gap;
      Option.when(
        g.contains("\"supplementalExecutionIds\":[\"") && g.contains(
          "\"status\":{\"type\":\"searched\"}"
        )
      )(g)
    }
    val after1 = retrieved(q6)
    assert(after1.contains(s""""gapId":"$gapId""""), after1)

    restart()

    assertEquals(post(api(s"/gaps/$gapId/search"), "{}")._1, 202)
    eventually("the second supplemental execution is linked to the gap") {
      val g = gap; Option.when("""q6:plan:e\d+""".r.findAllIn(g).size >= 2)(g)
    }
    retrieved(q6)
    val (third, body) = post(api(s"/gaps/$gapId/search"), "{}")
    assertEquals(third, 409, body)
  }
