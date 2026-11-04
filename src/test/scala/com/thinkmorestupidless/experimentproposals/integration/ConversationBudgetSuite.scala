package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.ankka.agent.{ModelResponse, TestModelProvider, TokenUsage}

/** A conversation's budget (FR-019): spent, it declines; a new conversation starts fresh. */
class ConversationBudgetSuite extends ServiceFixture with ConversationClient:
  private val assistant = TestModelProvider()
  private var budget    = Configured.conversationBudget

  override def beforeAll(): Unit =
    super.beforeAll()
    budget = Configured.conversationBudget
    Configured.conversationBudget = 100L
    Configured.assistantModel = Some(assistant)

  override def afterAll(): Unit =
    Configured.conversationBudget = budget
    Configured.assistantModel = None
    super.afterAll()

  test("a spent budget refuses the next question with the message; a new conversation is fresh") {
    val id = "free:budget-spent"
    assertEquals(open(id)._1, 200)
    assistant.expect(
      ModelResponse("an answer", usage = TokenUsage(inputTokens = 80, outputTokens = 40))
    )
    val (status, events, body) = turn(id, "first")
    assertEquals(status, 200, body)
    val done = events.find(_.name == "done").map(_.data).getOrElse(fail(body))
    assert(done.contains("\"used\":120") && done.contains("\"budget\":100"), done)
    val conv = get(api(s"/conversations/$id"))._2
    assert(conv.contains("\"used\":120") && conv.contains("\"budget\":100"), conv)
    val (refused, _, message) = turn(id, "second")
    assertEquals(refused, 409, message)
    assert(message.contains("the conversation's budget is reached: 120 of 100 tokens"), message)
    val fresh       = "free:budget-fresh"
    val (_, opened) = open(fresh)
    assert(!opened.contains("\"used\":"), opened)
    assistant.expectText("fresh")
    assertEquals(turn(fresh, "again")._1, 200)
  }
