package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.replay.Cassettes

import java.nio.file.Path

/**
 * US2 through the whole service with a scripted model: derivation after confirmation, the coverage
 * rule, the status.
 */
class QuestionsSuite extends ServiceFixture:

  private val cassettes = Cassettes(Path.of("evals/cassettes"))
  private val text      = "Can you make foie gras?"
  private def cassette(promptVersion: String, pick: String => Boolean = _ => true): String =
    cassettes
      .list(promptVersion)
      .map(_.reply)
      .find(pick)
      .getOrElse(fail(s"no cassette for $promptVersion"))

  private def submitAndConfirm(): String =
    val (status, body) = post(api("/requests"), s"""{"text":"$text","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    eventually(s"$id pauses for facet review") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("awaiting-facet-review"))(w)
    }
    Seq("f1", "f2", "f3", "f8").foreach { f =>
      assertEquals(
        post(
          api(s"/facets/$id:$f/review"),
          """{"action":"confirm","reviewer":"ada","rationale":"ok"}"""
        )._1,
        201
      )
    }
    assertEquals(post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")._1, 200)
    id

  override def beforeAll(): Unit =
    super.beforeAll()
    // Every question's investigation plans its search as soon as it starts (US6).
    scriptFromCassettes("plan@v1")
    model.whenUserSays("# case: facets:")(cassette("facets@v1"))
    model.whenUserSays("# case: roundtrip:")(
      cassette("roundtrip@v1", _.contains("\"unsupportedAdditions\": []"))
    )

  test(
    "questions are derived from the confirmed facets, each linked to facets and unresolved with its missing information"
  ) {
    model.whenUserSays("# case: questions:")(cassette("questions@v1"))
    val id = submitAndConfirm()
    eventually("the investigation reaches the questions") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("questions-investigating"))(w)
    }
    val (status, qs) = get(api(s"/requests/$id/questions"))
    assertEquals(status, 200, qs)
    assertEquals("\"question\":".r.findAllIn(qs).size, 6, qs)
    assert(
      qs.contains("\"facetNames\":[\"product\",\"production approach\",\"biological source\"]"),
      qs
    )
    val (_, q4) = get(api(s"/questions/$id:q4"))
    assert(
      q4.contains("lipid accumulation") && q4.contains(
        "\"answerFields\":[\"species\",\"cell type\",\"endpoint\",\"measurement\",\"observed outcome\",\"limitations\"]"
      ),
      q4
    )
    assert(
      !q4.contains("\"status\":{\"type\":") && q4.contains("\"missingInformation\":[\"species\""),
      q4
    )
    val (_, run) = get(api(s"/runs/run-$id"))
    assert(run.contains("\"stage\":\"derive-questions\""), run)
    // Each question's investigation retrieves and then waits, without a timeout, for assessment.
    eventually("the question's investigation rests after retrieval") {
      val (_, qw) = get(api(s"/workflows/question-investigation/$id:q4"))
      Option.when(qw.contains("\"status\":\"Paused\"") && qw.contains("retrieved"))(qw)
    }
  }

  test(
    "a set that leaves a mandatory facet uninvestigated is a coverage gap, retried, then accepted"
  ) {
    model.reset()
    scriptFromCassettes("plan@v1")
    model.whenUserSays("# case: facets:")(cassette("facets@v1"))
    model.whenUserSays("# case: roundtrip:")(
      cassette("roundtrip@v1", _.contains("\"unsupportedAdditions\": []"))
    )
    val good = cassette("questions@v1")
    val partial =
      """{"questions":[{"question":"Only the product?","facets":["product"],"evidenceNeeded":["x"],"answerFields":["y"],"sufficiencyCriteria":["z"]}],"uninvestigated":[]}"""
    val firstTime = new java.util.concurrent.atomic.AtomicBoolean(true)
    def asksQuestions(r: com.thinkmorestupidless.ankka.agent.ModelRequest): Boolean =
      r.messages.exists {
        case com.thinkmorestupidless.ankka.agent.ChatMessage.User(content) =>
          content.exists {
            case com.thinkmorestupidless.ankka.agent.MessageContent.Text(t) =>
              t.contains("# case: questions:")
            case _ => false
          }
        case _ => false
      }
    model.whenRequest(r => asksQuestions(r) && firstTime.getAndSet(false))(
      com.thinkmorestupidless.ankka.agent.ModelResponse(partial)
    )
    model.whenUserSays("# case: questions:")(good)
    val id = submitAndConfirm()
    eventually("the investigation completes after the retry") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("questions-investigating"))(w)
    }
    val (_, run) = get(api(s"/runs/run-$id"))
    assert(run.contains("coverage_gap"), run)
    assertEquals("\"question\":".r.findAllIn(get(api(s"/requests/$id/questions"))._2).size, 6)
  }
