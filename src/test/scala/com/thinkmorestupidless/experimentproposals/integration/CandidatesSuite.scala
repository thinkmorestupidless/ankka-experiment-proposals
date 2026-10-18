package com.thinkmorestupidless.experimentproposals.integration

import scala.concurrent.duration.*

/**
 * US7 through the whole service with the cassettes: packages validated with their checks, the
 * guardrail refusing a probability, two candidates with their evidence and unknowns, the decision
 * with the rejected alternatives kept across a restart.
 */
class CandidatesSuite extends ServiceFixture:

  private val A = "src:file:paper-a:v1:fd1"
  private val B = "src:file:paper-b:v1:fd1"
  private val C = "src:file:paper-c:v1:fd1"

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
    // The first candidates call answers with a probability of success; the guardrail must refuse it.
    val good = com.thinkmorestupidless.experimentproposals.replay
      .Cassettes(java.nio.file.Path.of("evals/cassettes"))
      .list("candidates@v1")
      .head
      .reply
    val firstTime = new java.util.concurrent.atomic.AtomicBoolean(true)
    def asksCandidates(r: com.thinkmorestupidless.ankka.agent.ModelRequest): Boolean =
      r.messages.exists {
        case com.thinkmorestupidless.ankka.agent.ChatMessage.User(content) =>
          content.exists {
            case com.thinkmorestupidless.ankka.agent.MessageContent.Text(t) =>
              t.contains("# case: candidates:")
            case _ => false
          }
        case _ => false
      }
    model.whenRequest(r => asksCandidates(r) && firstTime.getAndSet(false))(
      com.thinkmorestupidless.ankka.agent.ModelResponse(
        good.replace(
          "\"rationale\": \"The target product is duck liver;",
          "\"rationale\": \"A 70% chance of success. The target product is duck liver;"
        )
      )
    )
    scriptFromCassettes("candidates@v1")

  private def question(id: String): String      = get(api(s"/questions/$id"))._2
  private def investigation(id: String): String = get(api(s"/workflows/investigation/$id"))._2

  /**
   * The reference request through facets, questions, retrieval and the assessment of A, B and C.
   */
  private def assessedRequest(): String =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review") {
      Option.when(investigation(id).contains("awaiting-facet-review"))(id)
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
    eventually("the lipid question is retrieved", within = 120.seconds) {
      val q = question(s"$id:q4"); Option.when(q.contains("\"status\":\"retrieved\""))(q)
    }
    assertEquals(
      post(api(s"/questions/$id:q4/assess"), s"""{"recordIds":["$A","$B","$C"]}""")._1,
      202
    )
    eventually("three assessments") {
      val j = question(s"$id:q4"); Option.when("\"relationship\":".r.findAllIn(j).size == 3)(j)
    }
    id

  private def packaged(id: String, expected: String): String =
    assertEquals(post(api(s"/requests/$id/packages"), """{"reviewer":"ada"}""")._1, 202)
    eventually(s"the investigation reaches $expected", within = 120.seconds) {
      val w = investigation(id); Option.when(w.contains(expected))(w)
    }

  test(
    "packages are built and validated: the lipid package passes every check; a probability in the candidates reply is refused and recorded"
  ) {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    val id = assessedRequest()
    val w  = packaged(id, "candidates: model_refused")
    assert(w.contains("candidates: model_refused"), w)
    val (ps, pkg) = get(api(s"/packages/$id:q4:pk1"))
    assertEquals(ps, 200, pkg)
    assert(pkg.contains("\"qualityStatus\":{\"type\":\"valid\"}"), pkg)
    assertEquals("\"passed\":true".r.findAllIn(pkg).size, 8, pkg)
    assert(
      pkg.contains(s""""id":"$B"""") && pkg.contains(s""""id":"$C"""") && pkg.contains(
        s""""id":"$A""""
      ),
      pkg
    )
    assert(pkg.contains("\"passages\":[{") && pkg.contains("\"resolves\":true"), pkg)
    assert(pkg.contains("\"ruleSetVersion\":\"package@v1\""), pkg)
    val empty = get(api(s"/packages/$id:q6:pk1"))._2
    assert(
      empty.contains(s""""gapIds":["$id:q6:g1"""") && !empty.contains("\"assessmentRefs\":[{"),
      empty
    )
    val (_, run) = get(api(s"/runs/run-$id"))
    assert(run.contains("model_refused") && run.contains("no-probability"), run)
    assert(get(api(s"/requests/$id/approaches"))._2.contains("\"approaches\":[]"))
  }

  test(
    "two candidates with their evidence and unknowns; a reviewer's decision keeps the rejected alternative, across a restart"
  ) {
    val id = assessedRequest()
    packaged(id, "awaiting-decision")
    val (as, approaches) = get(api(s"/requests/$id/approaches"))
    assertEquals(as, 200, approaches)
    assertEquals("\"hypothesis\":".r.findAllIn(approaches).size, 2, approaches)
    assert(
      approaches.contains(s""""id":"$id:c1"""") && approaches.contains(s""""id":"$id:c2""""),
      approaches
    )
    assert(
      approaches.contains("\"contraryClaimRefs\":[{") && approaches.contains(
        "\"missingEvidence\":[\""
      ) && approaches.contains("\"indirectClaimRefs\":[{"),
      approaches
    )
    assert(approaches.contains("\"evidenceStatus\":{\"type\":\"unknown\"}"), approaches)
    com.thinkmorestupidless.experimentproposals.domain.design.Comparison.CriterionNames.foreach(n =>
      assert(approaches.contains(s""""name":"$n""""), n)
    )
    assert(
      !approaches.toLowerCase.contains("probability") && !approaches.matches(
        "(?s).*\\d+\\s?% (chance|probability|likelihood).*"
      ),
      approaches
    )
    val (bad, badBody) = post(
      api(s"/requests/$id/decide"),
      s"""{"selectedApproachId":"$id:c1","rejected":[],"decidedBy":"ada","rationale":"species first"}"""
    )
    assertEquals(bad, 400, badBody)
    val (unlisted, _) = post(
      api(s"/requests/$id/decide"),
      s"""{"selectedApproachId":"$id:c1","rejected":[{"approachId":"$id:c2","reason":"not the target species"}],"decidedBy":"nobody","rationale":"x"}"""
    )
    assertEquals(unlisted, 400)
    val (ds, decision) = post(
      api(s"/requests/$id/decide"),
      s"""{"selectedApproachId":"$id:c1","rejected":[{"approachId":"$id:c2","reason":"not the target species"}],"decidedBy":"ada","rationale":"the target species comes first"}"""
    )
    assertEquals(ds, 201, decision)
    assert(
      decision.contains(s""""selectedApproachRef":{"kind":{"type":"approach"},"id":"$id:c1""""),
      decision
    )
    assert(
      decision.contains("\"reason\":\"not the target species\"") && decision.contains(
        "\"decidedBy\":\"ada\""
      ),
      decision
    )
    eventually("the investigation moves past the decision") {
      val w = investigation(id);
      Option.when(w.contains("decided") || w.contains("designing"))(w)
    }
    assertEquals(
      post(
        api(s"/requests/$id/decide"),
        s"""{"selectedApproachId":"$id:c2","rejected":[{"approachId":"$id:c1","reason":"x"}],"decidedBy":"ada","rationale":"again"}"""
      )._1,
      409
    )

    restart()

    val after = get(api(s"/requests/$id/approaches"))._2
    assert(
      after.contains(s""""id":"$id:c2"""") && after.contains(
        "\"status\":{\"type\":\"rejected\"}"
      ) && after.contains("\"rejectionReason\":\"not the target species\""),
      after
    )
    assert(
      after.contains("\"status\":{\"type\":\"selected\"}") && after.contains("\"decision\":{"),
      after
    )
  }
