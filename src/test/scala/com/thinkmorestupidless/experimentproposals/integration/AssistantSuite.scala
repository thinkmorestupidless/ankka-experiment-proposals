package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json, ModelRequest, TestModelProvider}

import scala.concurrent.duration.*

/**
 * The assistant (contracts/assistant.md) against the reference request at the choice of approach,
 * with a scripted model of its own. Prose is never asserted on: which tools ran, which references
 * resolved, and what was recorded.
 */
class AssistantSuite extends AutomationFixture with ConversationClient:

  private val assistant = TestModelProvider()
  private var rid       = ""
  private def q4        = s"$rid:q4"
  private def chat      = s"request:$rid"

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes(stagesScripted*)
    Configured.assistantModel = Some(assistant)
    ingestReference()
    ingestFixture("retracted.xml")
    rid = submitAndConfirm()
    eventually(s"$rid reaches the choice of approach", within = 240.seconds) {
      Option.when(investigation(rid).contains("awaiting-decision"))(())
    }
    assertEquals(open(chat)._1, 200)

  override def afterAll(): Unit =
    Configured.assistantModel = None
    super.afterAll()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context); assistant.reset()

  /** The tools this turn ran: the results after the last question in each request the model saw. */
  private def toolsCalled: Vector[(String, String)] =
    assistant.requests.toVector.flatMap { r =>
      val asked = r.messages.lastIndexWhere(_.isInstanceOf[ChatMessage.User])
      r.messages
        .drop(asked + 1)
        .collect { case ChatMessage.ToolResults(results) =>
          results.map(t => t.name -> t.content)
        }
        .flatten
    }.distinct

  private def text(request: ModelRequest): String = request.messages.mkString("\n")

  private def done(events: Vector[Event]): String =
    events.find(_.name == "done").map(_.data).getOrElse(fail(s"no done event: $events"))

  private def asked(tool: String, args: (String, String)*): TestModelProvider =
    assistant.expectToolCall(tool, Json.obj(args.map((k, v) => k -> Json.str(v))*))

  private def objectWith(json: String, needle: String): String =
    objectsOf(json).find(_.contains(needle)).getOrElse(fail(s"nothing with $needle in $json"))

  private def idOf(obj: String): String =
    """"id":"([^"]+)"""".r.findFirstMatchIn(obj).map(_.group(1)).get

  private def view = get(api(s"/questions/$q4"))._2

  private def assessmentOf(record: String): String =
    val assessments = view.substring(view.indexOf("\"assessments\":["))
    idOf(objectWith(assessments, s""""id":"$record""""))

  private def allResolved(d: String, refs: Int): Unit =
    assertEquals("\"resolved\":true".r.findAllIn(d).size, refs, d)

  test("why Paper B is indirect: the question is read and the assessment cited and resolved") {
    val asm = assessmentOf("src:file:paper-b:v1:fd1")
    asked("get_question", "questionId" -> q4)
      .expectText(
        s"The species rule makes it indirect: [[assessment:$asm]] on [[finding:src:file:paper-b:v1:fd1]]."
      )
    val (status, events, body) = turn(chat, "why is Paper B indirect?")
    assertEquals(status, 200, body)
    assert(events.count(_.name == "token") >= 2, events)
    val tools = toolsCalled
    assertEquals(tools.map(_._1), Vector("get_question"))
    assert(tools.head._2.contains("species-mismatch-blocks-direct"), tools.head._2.take(300))
    allResolved(done(events), 2)
  }

  test("what blocked the recommendation: the sufficiency judgement is cited") {
    val suff = idOf(
      objectWith(
        view.substring(view.indexOf("\"sufficiency\":[")),
        "recommend production feasibility"
      )
    )
    asked("get_question", "questionId" -> q4).expectText(
      s"Insufficient evidence: [[sufficiency:$suff]]."
    )
    val (_, events, _) = turn(chat, "what evidence gap blocked the recommendation?")
    assertEquals(toolsCalled.map(_._1), Vector("get_question"))
    allResolved(done(events), 1)
  }

  test("what the search excluded: the fused execution is cited") {
    val fused = idOf(objectWith(view.substring(view.indexOf("\"executions\":[")), "\"fused\":true"))
    asked("get_question", "questionId" -> q4).expectText(
      s"The retracted paper: [[search-execution:$fused]]."
    )
    val (_, events, _) = turn(chat, "what did the search exclude and why?")
    assert(toolsCalled.head._2.contains("eligibility.retracted"), toolsCalled.head._2.take(300))
    allResolved(done(events), 1)
  }

  test("show me the facets: the request defaults to the conversation's subject") {
    assistant.expectToolCall("get_request", Json.obj()).expectText(s"[[facets:$rid]]")
    val (_, events, _) = turn(chat, "show me the facets")
    val (name, result) = toolsCalled.head
    assertEquals(name, "get_request")
    assert(result.contains(s""""id":"$rid:f1""""), result.take(300))
    allResolved(done(events), 1)
  }

  test("a reference to a record that does not exist is marked unresolved") {
    assistant.expectText("See [[finding:src:file:nowhere:v1:fd9]].")
    val d = done(turn(chat, "anything")._2)
    // The codecs leave out a field at its default: an unresolved reference has no "resolved".
    assert(d.contains("nowhere") && !d.contains("\"resolved\":true"), d)
  }

  test("a wish to approve becomes a prefill and no approval") {
    assistant.expectText(
      s"A reviewer must approve it on its card. [[prefill:approval:$rid:design|outcome=approve|version=1|rationale=]]"
    )
    val d = done(turn(chat, "approve it")._2)
    assert(d.contains("\"prefill\":{\"decision\":\"approval\""), d)
    assert(!get(api(s"/designs/$rid:design"))._2.contains("\"approved\""))
  }

  test("a proposal is recorded and nothing is performed until it is confirmed") {
    val before = get(api("/requests"))._2
    asked(
      "propose_action",
      "action"    -> "submit-request",
      "arguments" -> """{"text":"Can you make duck pâté?"}"""
    )
      .expectText("I have proposed it; confirm it to submit.")
    val (_, events, _) = turn(chat, "submit: Can you make duck pâté?")
    assert(toolsCalled.head._2.startsWith("proposed as #1"), toolsCalled.head._2)
    assert(events.exists(e => e.name == "proposal" && e.data.contains("submit-request")), events)
    assertEquals(get(api("/requests"))._2, before)
    // A proposal's status is at its default, "proposed", so the conversation's JSON leaves it out.
    val conv = get(api(s"/conversations/$chat"))._2
    assert(conv.contains("\"action\":\"submit-request\"") && !conv.contains("performed"), conv)
  }

  test("a failed model call ends the stream with error and records no turn") {
    val id = "free:failing-turn"
    assertEquals(open(id)._1, 200)
    val (_, events, _) = turn(id, "this has no script")
    assert(events.lastOption.exists(_.name == "error"), events)
    assert(!events.exists(_.name == "done"), events)
    assert(!get(api(s"/conversations/$id"))._2.contains("\"turns\":[{"))
  }

  test("two conversations do not share a history") {
    Seq("free:history-one", "free:history-two").foreach(i => assertEquals(open(i)._1, 200))
    assistant.expectText("one").expectText("two")
    turn("free:history-one", "the word is aardvark")
    turn("free:history-two", "what was the word?")
    assert(!text(assistant.lastRequest).contains("aardvark"), text(assistant.lastRequest))
  }

  test("a request's conversation carries its subject in context") {
    assistant.expectText("noted")
    turn(chat, "what is this about?")
    val seen = assistant.lastRequest.toString
    assert(seen.contains("Can you make foie gras?") && seen.contains(rid), seen.take(500))
  }

  test("without a model the turn is refused and the assistant says why") {
    Configured.assistantModel = None
    try
      val (status, _, body) = turn(chat, "anything")
      assertEquals(status, 503, body)
      val (_, a) = get(api("/assistant"))
      assert(a.contains("\"available\":false") && a.contains("ANTHROPIC_API_KEY"), a)
    finally Configured.assistantModel = Some(assistant)
    assert(get(api("/assistant"))._2.contains("\"available\":true"))
  }

  test("a conversation id of neither form, or a subject that does not match, is refused") {
    assertEquals(open("whatever")._1, 400)
    assertEquals(open(chat, """{"subject":"rq-other"}""")._1, 400)
    assertEquals(open("request:rq-does-not-exist")._1, 400)
  }
