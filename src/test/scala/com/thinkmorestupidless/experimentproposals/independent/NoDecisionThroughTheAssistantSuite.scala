package com.thinkmorestupidless.experimentproposals.independent

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.integration.{ConversationClient, ServiceFixture}
import com.thinkmorestupidless.ankka.agent.{Json, TestModelProvider}

import scala.concurrent.duration.*

/**
 * Written from the specification alone (feature 002, FR-016 and principle 9, human authority): the
 * assistant explains and proposes, and a reviewer's decision is made only by the reviewer, on its
 * card. A model that tries every decision by name, through every tool and every conversation route,
 * records none of them. A tool that let it decide must fail this suite (`scripts/mutants.sh`).
 */
class NoDecisionThroughTheAssistantSuite extends ServiceFixture with ConversationClient:

  private val assistant = TestModelProvider()
  private var rid       = ""
  private val record    = "src:file:paper-a:v1:fd1"

  /** The decisions a reviewer makes, each as a model might name a tool for it. */
  private def decisionTools: Vector[(String, Json)] =
    def args(fields: (String, String)*) = Json.obj(fields.map((k, v) => k -> Json.str(v))*)
    val reviewer = Seq("reviewer" -> "ada", "rationale" -> "the assistant decided")
    Vector(
      "review_facet"   -> args(Seq("facetId" -> s"$rid:f1", "action" -> "confirm") ++ reviewer*),
      "confirm_facet"  -> args(Seq("facetId" -> s"$rid:f1") ++ reviewer*),
      "confirm_facets" -> args(Seq("requestId" -> rid) ++ reviewer*),
      "review_record"  -> args(Seq("recordId" -> record, "action" -> "reject") ++ reviewer*),
      "verify_record"  -> args(Seq("recordId" -> record) ++ reviewer*),
      "decide" -> args(Seq("requestId" -> rid, "selectedApproachId" -> s"$rid:c1") ++ reviewer*),
      "select_approach" -> args(Seq("approachId" -> s"$rid:c1") ++ reviewer*),
      "approve_design"  -> args(Seq("designId" -> s"$rid:design", "version" -> "1") ++ reviewer*),
      "reject_design"   -> args(Seq("designId" -> s"$rid:design") ++ reviewer*),
      "request_changes" -> args(Seq("designId" -> s"$rid:design") ++ reviewer*)
    )

  /** The same decisions as the actions a model might propose for a person to confirm. */
  private val decisionActions = Vector(
    "confirm-facets",
    "review-facet",
    "review-record",
    "decide",
    "select-approach",
    "approve-design",
    "request-changes"
  )

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes("facets@v1", "roundtrip@v1", "extract@v1", "verify@v1", "reextract@v1")
    Configured.assistantModel = Some(assistant)
    recordsSettled(ingestFixture("paper-a.xml", extract = true))
    val (_, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    rid = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$rid pauses for the facets", within = 120.seconds) {
      Option
        .when(get(api(s"/workflows/investigation/$rid"))._2.contains("awaiting-facet-review"))(())
    }

  override def afterAll(): Unit =
    Configured.assistantModel = None
    super.afterAll()

  private def snapshot: Vector[String] = Vector(
    get(api(s"/requests/$rid/facets"))._2,
    get(api(s"/requests/$rid/proposal"))._2,
    get(api(s"/findings/$record"))._2,
    get(api(s"/requests/$rid/approaches"))._2,
    get(api(s"/designs/$rid:design"))._2
  )

  test("FR-016: no reviewer decision is recorded through the assistant, by any tool or route") {
    val before = snapshot
    Seq(s"request:$rid", "free:no-decision-1").foreach(id => assertEquals(open(id)._1, 200))
    for conversation <- Seq(s"request:$rid", "free:no-decision-1") do
      decisionTools.foreach { (tool, arguments) =>
        assistant.reset()
        assistant.expectToolCall(tool, arguments).expectText("done")
        turn(conversation, s"$tool now")
      }
      decisionActions.foreach { action =>
        assistant.reset()
        assistant
          .expectToolCall(
            "propose_action",
            Json.obj("action" -> Json.str(action), "arguments" -> Json.str(s"""{"id":"$rid"}"""))
          )
          .expectText("proposed")
        turn(conversation, s"propose $action")
      }
      assistant.reset()
      assistant.expectText(s"Approved. [[prefill:approval:$rid:design|outcome=approve|version=1]]")
      turn(conversation, "approve it")
      // Whatever the model claimed, no proposal was recorded that could be confirmed into a decision.
      (1 to 3).foreach { n =>
        Seq("confirm", "decline").foreach(verb =>
          assertNotEquals(
            post(api(s"/conversations/$conversation/proposals/$n:$verb"), "{}")._1,
            200
          )
        )
      }
    val after = snapshot
    before.zip(after).foreach((b, a) => assertEquals(a, b))
    val investigation = get(api(s"/workflows/investigation/$rid"))._2
    assert(investigation.contains("awaiting-facet-review"), investigation)
    assert(!investigation.contains("confirmedBy"), investigation)
  }
