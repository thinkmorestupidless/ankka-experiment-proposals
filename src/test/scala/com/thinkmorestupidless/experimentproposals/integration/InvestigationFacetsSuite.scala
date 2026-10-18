package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.replay.Cassettes

import java.nio.file.Path

/**
 * US1 through the whole service with a scripted model: extraction, the round-trip check, the pause,
 * review.
 */
class InvestigationFacetsSuite extends ServiceFixture:

  private val cassettes = Cassettes(Path.of("evals/cassettes"))
  private val text      = "Can you make foie gras?"

  private def facetsOf(requestId: String): String = get(api(s"/requests/$requestId/facets"))._2

  private def submit(): String =
    val (status, body) = post(api("/requests"), s"""{"text":"$text","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))

  private def paused(requestId: String): String =
    eventually(s"investigation $requestId pauses for review") {
      val (_, w) = get(api(s"/workflows/investigation/$requestId"))
      Option.when(w.contains("\"status\":\"Paused\""))(w)
    }

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes("plan@v1")
    model.whenUserSays("# case: facets:")(cassettes.list("facets@v1").head.reply)
    model.whenUserSays("# case: roundtrip:")(
      cassettes
        .list("roundtrip@v1")
        .map(_.reply)
        .find(_.contains("\"unsupportedAdditions\": []"))
        .get
    )
    model.whenUserSays("# case: questions:")(cassettes.list("questions@v1").head.reply)

  test(
    "facets are extracted with their origins, the round-trip check runs, and the workflow pauses; all of it survives a restart"
  ) {
    val id = submit()
    val w  = paused(id)
    assert(w.contains("awaiting-facet-review"), w)
    val facets = facetsOf(id)
    assertEquals("\"name\":".r.findAllIn(facets).size, 8, facets)
    assert(
      facets.contains("\"name\":\"product\"") && facets.contains("\"type\":\"explicit\""),
      facets
    )
    assert(facets.contains("\"quotation\":\"foie gras\""), facets)
    val (_, req) = get(api(s"/requests/$id"))
    assert(req.contains(s"\"text\":\"$text\""), req)
    assert(req.contains("\"passed\":true"), req)

    restart()

    val (_, after) = get(api(s"/workflows/investigation/$id"))
    assert(after.contains("\"status\":\"Paused\""), after)
    assertEquals("\"name\":".r.findAllIn(facetsOf(id)).size, 8)
    val (_, run) = get(api(s"/runs/run-$id"))
    assert(
      run.contains("\"stage\":\"extract-facets\"") && run.contains("\"stage\":\"round-trip\""),
      run
    )
  }

  test(
    "an unlisted reviewer is refused; confirming the mandatory facets resumes the investigation"
  ) {
    val id = submit()
    paused(id)
    val (unlisted, body) =
      post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"mallory"}""")
    assertEquals(unlisted, 400, body)
    val (blocked, why) = post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")
    assertEquals(blocked, 409, why)
    assert(why.contains("product"), why)
    Seq("f1", "f2", "f4").foreach { f =>
      val (s, b) = post(
        api(s"/facets/$id:$f/review"),
        """{"action":"confirm","reviewer":"ada","rationale":"as interpreted"}"""
      )
      assertEquals(s, 201, b)
      assert(b.contains("human_confirmed") && b.contains("\"history\":[{"), b)
    }
    val (ok, resumed) = post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")
    assertEquals(ok, 200, resumed)
    eventually("the investigation starts the questions' investigations and pauses") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("questions-investigating"))(w)
    }
    val (_, proposal) = get(api(s"/requests/$id/proposal"))
    assert(
      proposal.contains("\"unresolvedAndFlagged\":[\"biological source\",\"resources\"]"),
      proposal
    )
    assert(proposal.contains("\"stillProposed\":[]"), proposal)
  }

  test(
    "a reply that is not the shape is retried within the budget, then the run records the failure and the workflow pauses"
  ) {
    model.reset()
    // Question investigations of an earlier test may still plan; their calls must not eat the script.
    scriptFromCassettes("plan@v1")
    // A standing rule rather than an ordered script: other components' calls cannot consume it.
    model.whenUserSays("# case: facets:")("not json at all")
    val id = submit()
    val w = eventually("the investigation pauses at record-failure") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("extract-facets: schema_invalid"))(w)
    }
    assert(w.contains("schema_invalid"), w)
    val (_, run) = get(api(s"/runs/run-$id"))
    assertEquals("\"attempt\":".r.findAllIn(run).size, 3, run)
    assert(model.callCount >= 3, model.callCount.toString)
  }
