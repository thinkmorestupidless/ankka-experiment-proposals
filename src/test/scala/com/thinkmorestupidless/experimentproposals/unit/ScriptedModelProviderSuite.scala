package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.replay.ScriptedModelProvider
import com.thinkmorestupidless.ankka.agent.*

import scala.concurrent.Await
import scala.concurrent.duration.*

class ScriptedModelProviderSuite extends munit.FunSuite:

  private val script = """[
    { "when": "approve it", "text": "A reviewer must approve it on the card." },
    { "when": "Why is Paper B indirect", "tools": [ { "name": "get_question", "arguments": { "questionId": "q-1" } } ],
      "text": "Indirect by the species rule: [[assessment:asm-1]]." }
  ]"""
  private val model  = ScriptedModelProvider.parse(script).fold(fail(_), identity)

  private def ask(messages: ChatMessage*): ModelResponse =
    Await.result(
      model.complete(ModelRequest(ModelSettings("scripted"), None, messages.toVector)),
      5.seconds
    )

  test("a text rule answers with its text, whatever the case of what was said") {
    val r = ask(ChatMessage.User.text("Please APPROVE IT now"))
    assertEquals(r.text, "A reviewer must approve it on the card.")
    assertEquals(r.toolCalls, Vector.empty)
    assert(r.usage.total > 0)
  }

  test("a rule with tools calls them first, then answers once their results are in") {
    val asked = ChatMessage.User.text("why is paper b indirect?")
    val first = ask(asked)
    assertEquals(first.stopReason, StopReason.ToolUse)
    assertEquals(first.toolCalls.map(_.name), Vector("get_question"))
    assertEquals(first.toolCalls.head.arguments("questionId").flatMap(_.asString), Some("q-1"))
    val second = ask(
      asked,
      ChatMessage.Assistant("", first.toolCalls),
      ChatMessage.ToolResults(Vector(ToolResult("scripted-1", "get_question", "{}")))
    )
    assertEquals(second.text, "Indirect by the species rule: [[assessment:asm-1]].")
    assertEquals(second.toolCalls, Vector.empty)
  }

  test("nothing matching answers the fixed line") {
    assertEquals(
      ask(ChatMessage.User.text("what is the weather")).text,
      ScriptedModelProvider.NoAnswer
    )
  }

  test("a malformed script is refused with what is wrong") {
    assertEquals(
      ScriptedModelProvider.parse("""[{"text":"x"}]""").left.toOption,
      Some("rule 1: needs 'when'")
    )
    assert(ScriptedModelProvider.parse("{}").isLeft)
  }
