package com.thinkmorestupidless.experimentproposals.integration

/**
 * US4 through the whole service with the cassettes: the species rule, decision-relative
 * sufficiency, an empty search.
 */
class AssessmentSuite extends ServiceFixture:

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
      "sufficiency@v1"
    )

  /**
   * The reference request through facets and questions; answers the ids of the lipid and the
   * constraints questions.
   */
  private def referenceQuestions(): (String, String) =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review") {
      val (_, w) = get(api(s"/workflows/investigation/$id"));
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
    eventually("the questions are investigating") {
      val (_, w) = get(api(s"/workflows/investigation/$id"));
      Option.when(w.contains("questions-investigating"))(w)
    }
    eventually("the lipid question waits for retrieval") {
      val (_, q) = get(api(s"/questions/$id:q4"))
      Option.when(q.contains("\"status\":\"retrieved\"") || q.contains("awaiting-retrieval"))(q)
    }
    (s"$id:q4", s"$id:q6")

  private def question(id: String): String = get(api(s"/questions/$id"))._2

  test(
    "the three papers are assessed against the lipid question: A background, B indirect by the species rule, C qualifying; no score"
  ) {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    val (q4, _) = referenceQuestions()
    assertEquals(post(api(s"/questions/$q4/assess"), s"""{"recordIds":["$A","$B","$C"]}""")._1, 202)
    val q = eventually("three assessments") {
      val j = question(q4); Option.when("\"relationship\":".r.findAllIn(j).size == 3)(j)
    }
    val a = get(api(s"/assessments/$q4:$A"))._2
    assert(
      a.contains("\"relationship\":{\"type\":\"background\"}") && a.toLowerCase.contains(
        "endpoint"
      ),
      a
    )
    val b = get(api(s"/assessments/$q4:$B"))._2
    assert(
      b.contains("\"applicability\":{\"type\":\"indirect\"}") && b.contains(
        "species-mismatch-blocks-direct"
      ) && b.contains("\"outcome\":\"adjusted\""),
      b
    )
    val c = get(api(s"/assessments/$q4:$C"))._2
    assert(c.contains("\"relationship\":{\"type\":\"qualifies\"}"), c)
    assert(!q.contains("score"), q)

    assertEquals(
      post(
        api(s"/questions/$q4/sufficiency"),
        """{"intendedDecision":"recommend production feasibility"}"""
      )._1,
      202
    )
    val insufficient = eventually("the production decision is judged") {
      val j = question(q4);
      Option.when(j.contains("\"intendedDecision\":\"recommend production feasibility\""))(j)
    }
    assert(
      insufficient.contains("insufficient_for_recommendation") && insufficient.contains("avian"),
      insufficient
    )
    restart()
    assertEquals(
      post(
        api(s"/questions/$q4/sufficiency"),
        """{"intendedDecision":"propose a feasibility experiment on lipid accumulation"}"""
      )._1,
      202
    )
    val feasible = eventually("the experiment decision is judged") {
      val j = question(q4);
      Option.when(
        j.contains(
          "\"intendedDecision\":\"propose a feasibility experiment on lipid accumulation\""
        )
      )(j)
    }
    assert(
      feasible.contains("sufficient_for_proposed_next_step") && feasible.contains(
        "\"assumptions\":[\""
      ),
      feasible
    )
    assert(feasible.contains("\"status\":{\"type\":\"partial\"}"), feasible)
    val (_, run) = get(api(s"/runs/run-$q4"))
    assert(run.contains("\"stage\":\"assess\"") && run.contains("\"stage\":\"sufficiency\""), run)
  }

  test(
    "a question nothing was retrieved for is judged a coverage gap with a bounded escalation, not absent evidence"
  ) {
    val (_, q6) = referenceQuestions()
    assertEquals(
      post(
        api(s"/questions/$q6/sufficiency"),
        """{"intendedDecision":"recommend production feasibility"}"""
      )._1,
      202
    )
    val j = eventually("the empty question is judged") {
      val j = question(q6); Option.when(j.contains("\"intendedDecision\""))(j)
    }
    assert(
      j.contains("search_inadequate") && j.contains("supplemental_search") && j.contains(
        "\"emptySearch\":true"
      ),
      j
    )
    val (_, gaps) = get(api(s"/questions/$q6/gaps"))
    assert(gaps.contains("\"kind\":{\"type\":\"coverage\"}"), gaps)
  }
