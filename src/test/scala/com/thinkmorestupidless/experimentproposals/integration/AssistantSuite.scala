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
    // Every turn resends the history, tool results included, and the scripted provider counts it as a
    // model would: the suite's many turns on one request need more than the default budget.
    budget = Configured.conversationBudget
    Configured.conversationBudget = 50_000_000L
    assertEquals(open(chat)._1, 200)

  private var budget = 0L

  override def afterAll(): Unit =
    Configured.assistantModel = None
    Configured.conversationBudget = budget
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

  test("the walkthrough's script answers the reference request with references that all resolve") {
    val script = com.thinkmorestupidless.experimentproposals.replay.ScriptedModelProvider
      .fromFile(java.nio.file.Path.of("evals/assistant/walkthrough.json"))
      .fold(p => fail(p), identity)
    Configured.assistantModel = Some(script)
    try
      val id = s"request:$rid"
      Seq(
        "Why is the finding from Paper B indirect?"                   -> 2,
        "What blocked the decision recommend production feasibility?" -> 1,
        "What did the search for the lipid question exclude?"         -> 1,
        "Show me the facets"                                          -> 1,
        "Who is it for?"                                              -> 1
      ).foreach { (question, refs) =>
        val (status, events, body) = turn(id, question)
        assertEquals(status, 200, body)
        allResolved(done(events), refs)
      }
      val approve = done(turn(id, "approve it")._2)
      assert(approve.contains(s""""target":"$rid:design""""), approve)
      val (_, cut, _) = turn(id, "an answer cut short")
      assert(cut.exists(_.name == "token") && cut.last.name == "error", cut)
      assertEquals(open("free:walkthrough-script")._1, 200)
      eventually(s"$rid is listed", within = 90.seconds)(
        Option.when(get(api("/requests"))._2.contains(rid))(())
      )
      val waiting =
        done(turn("free:walkthrough-script", "Which requests are waiting for a reviewer?")._2)
      assert(
        waiting.contains(s""""id":"$rid"""") && waiting.contains("\"resolved\":true"),
        s"$waiting; the requests are ${get(api("/requests"))._2.take(400)}"
      )
    finally Configured.assistantModel = Some(assistant)
  }

  // ── acting from the conversation (US5) ───────────────────────────────────────

  private val ops = "free:operations"

  /** Proposes through the scripted model and answers the proposal's number. */
  private def propose(action: String, arguments: String): Int =
    asked("propose_action", "action" -> action, "arguments" -> arguments).expectText("Confirm it.")
    val (status, events, body) = turn(ops, s"please do $action")
    assertEquals(status, 200, body)
    val proposal =
      events.find(_.name == "proposal").map(_.data).getOrElse(fail(s"no proposal: $events"))
    assert(toolsCalled.last._2.startsWith("proposed as #"), toolsCalled.last._2)
    """"n":(\d+)""".r.findFirstMatchIn(proposal).map(_.group(1).toInt).get

  private def settle(n: Int, verb: String): (Int, String) =
    post(api(s"/conversations/$ops/proposals/$n:$verb"), "{}")

  test("a confirmed request is submitted as typed, and a second confirmation is refused") {
    assertEquals(open(ops)._1, 200)
    // The reference text, so the new investigation's facets replay from the cassettes.
    val typed  = "Can you make foie gras?"
    val before = """"id":"rq-""".r.findAllIn(get(api("/requests"))._2).size
    val n      = propose("submit-request", s"""{"text":${quoteJson(typed)}}""")
    assertEquals(""""id":"rq-""".r.findAllIn(get(api("/requests"))._2).size, before)
    val (status, body) = settle(n, "confirm")
    assertEquals(status, 200, body)
    assert(body.contains("\"status\":\"performed\"") && body.contains("\"resolved\":true"), body)
    val id = """"id":"(rq-[^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    assert(get(api(s"/requests/$id"))._2.contains(quoteJson(typed)), "the text is not as typed")
    eventually(s"$id's investigation starts")(
      Option.when(investigation(id).contains("awaiting-facet-review"))(())
    )
    val (again, refused) = settle(n, "confirm")
    assertEquals(again, 409, refused)
    assert(refused.contains(s"proposal #$n is already performed"), refused)
  }

  test("a declined proposal records the decline and performs nothing") {
    val n = propose("set-source-status", """{"sourceId":"src:file:paper-c","status":"retracted"}""")
    val (status, body) = settle(n, "decline")
    assertEquals(status, 200, body)
    assert(body.contains("\"status\":\"declined\""), body)
    assert(!get(api("/sources/src:file:paper-c"))._2.contains("\"retracted\""))
    assertEquals(settle(n, "confirm")._1, 409)
  }

  test("an action the assistant is not given reaches the model as the validation message") {
    asked("propose_action", "action" -> "approve-design", "arguments" -> "{}").expectText("No.")
    turn(ops, "approve the design")
    assert(toolsCalled.head._2.startsWith("unknown action 'approve-design'"), toolsCalled.head._2)
    assert(!get(api(s"/conversations/$ops"))._2.contains("approve-design"))
  }

  test("an ingestion and a fault confirmed in the conversation do what their routes do") {
    val n =
      propose("ingest-source", """{"adapter":"file","identifier":"paper-a.xml@endpoint_swap"}""")
    val (status, body) = settle(n, "confirm")
    assertEquals(status, 200, body)
    assert(body.contains(""""kind":"source","id":"src:file:paper-a@endpoint_swap""""), body)
    recordsSettled("src:file:paper-a@endpoint_swap")
    val finding  = "src:file:paper-a@endpoint_swap:v1:fd1"
    val m        = propose("inject-fault", s"""{"recordId":"$finding","fault":"endpoint_swap"}""")
    val (s2, b2) = settle(m, "confirm")
    assertEquals(s2, 200, b2)
    assert(b2.contains(s""""kind":"finding","id":"$finding""""), b2)
    eventually("the verification catches the swapped endpoint", within = 120.seconds) {
      Option.when(get(api(s"/findings/$finding"))._2.contains("endpoint_mismatch"))(())
    }
  }

  test("a status confirmed in the conversation is changed as the route changes it") {
    val n = propose("set-source-status", """{"sourceId":"src:file:paper-c","status":"retracted"}""")
    val (status, body) = settle(n, "confirm")
    assertEquals(status, 200, body)
    assert(body.contains(""""kind":"source","id":"src:file:paper-c""""), body)
    assert(get(api("/sources/src:file:paper-c"))._2.contains("\"status\":{\"type\":\"retracted\"}"))
    post(
      api("/sources/src:file:paper-c/status"),
      """{"status":"active","reason":"restored","by":"operator"}"""
    ): Unit
  }
