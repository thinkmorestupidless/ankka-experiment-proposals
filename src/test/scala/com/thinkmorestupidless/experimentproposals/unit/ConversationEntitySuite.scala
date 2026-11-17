package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.ConversationEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.conversation.*
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/** The conversation's own rules (data-model.md): the budget, the turns and the proposals. */
class ConversationEntitySuite extends munit.FunSuite:

  private var budget = Configured.conversationBudget
  override def beforeAll(): Unit =
    budget = Configured.conversationBudget; Configured.conversationBudget = 1000L
  override def afterAll(): Unit = Configured.conversationBudget = budget

  private def kit(id: String = "request:rq-1") =
    EventSourcedTestKit.of(ConversationEntity, id)

  private def answered(total: Long, question: String = "why?") =
    Answered(
      question,
      "because [[finding:f1]]",
      Vector(Reference("finding", "f1")),
      None,
      total
    )

  test("opening is idempotent and fixes the subject and the budget when it opens") {
    val k     = kit()
    val first = k.call(ConversationEntity.open)(OpenConversation(Some("rq-1"))).replyValue
    assertEquals(first.subject, Some("rq-1"))
    assertEquals(first.budget, 1000L)
    Configured.conversationBudget = 5L
    try
      val again = k.call(ConversationEntity.open)(OpenConversation(None))
      assert(!again.persisted)
      assertEquals(again.replyValue.budget, 1000L)
    finally Configured.conversationBudget = 1000L
    assertEquals(k.allEvents.size, 1)
  }

  test("a free-standing conversation has no subject; an id of neither form is refused") {
    assertEquals(
      kit("free:abcdefgh12")
        .call(ConversationEntity.open)(OpenConversation(None))
        .replyValue
        .subject,
      None
    )
    assert(kit("whatever").call(ConversationEntity.open)(OpenConversation(None)).isError)
    assert(
      kit("request:rq-1").call(ConversationEntity.open)(OpenConversation(Some("rq-2"))).isError
    )
  }

  test("a question is refused at the budget, with the tokens used and the total") {
    val k = kit()
    k.call(ConversationEntity.open)(OpenConversation(None))
    assert(!k.call(ConversationEntity.ask).isError)
    k.call(ConversationEntity.answered)(answered(1000L))
    val refused = k.call(ConversationEntity.ask)
    assert(refused.isError)
    assertEquals(refused.error.code, ErrorCode.Conflict)
    assertEquals(refused.errorMessage, "the conversation's budget is reached: 1000 of 1000 tokens")
  }

  test("an answer may not lower the tokens used; turns are numbered") {
    val k = kit()
    k.call(ConversationEntity.open)(OpenConversation(None))
    assertEquals(
      k.call(ConversationEntity.answered)(answered(300L)).replyValue.turns.map(_.n),
      Vector(1)
    )
    assert(k.call(ConversationEntity.answered)(answered(200L)).isError)
    assertEquals(
      k.call(ConversationEntity.answered)(answered(400L)).replyValue.turns.map(_.n),
      Vector(1, 2)
    )
    assertEquals(k.currentState.used, 400L)
  }

  test("a proposal is validated and numbered; it is performed or declined only once") {
    val k = kit()
    k.call(ConversationEntity.open)(OpenConversation(None))
    assert(k.call(ConversationEntity.propose)(Propose("approve-design", Map.empty)).isError)
    val p1 = k
      .call(ConversationEntity.propose)(
        Propose("submit-request", Map("text" -> "Can you make foie gras?"))
      )
      .replyValue
    val p2 = k
      .call(ConversationEntity.propose)(
        Propose("ingest-source", Map("adapter" -> "file", "identifier" -> "paper-a.xml"))
      )
      .replyValue
    assertEquals((p1.n, p2.n), (1, 2))
    assertEquals(p1.arguments("text"), "Can you make foie gras?")
    val done =
      k.call(ConversationEntity.performed)(Performed(1, Reference("request", "rq-9")))
    assertEquals(done.replyValue.proposal(1).map(_.status), Some("performed"))
    val again =
      k.call(ConversationEntity.performed)(Performed(1, Reference("request", "rq-9")))
    assertEquals(again.error.code, ErrorCode.Conflict)
    assertEquals(again.errorMessage, "proposal #1 is already performed")
    assertEquals(
      k.call(ConversationEntity.declined)(2).replyValue.proposal(2).map(_.status),
      Some("declined")
    )
    assertEquals(
      k.call(ConversationEntity.declined)(2).errorMessage,
      "proposal #2 is already declined"
    )
    assertEquals(k.call(ConversationEntity.declined)(3).error.code, ErrorCode.NotFound)
  }

  test("two conversation ids are two states") {
    val a = kit("request:rq-1"); val b = kit("free:abcdefgh12")
    a.call(ConversationEntity.open)(OpenConversation(None))
    b.call(ConversationEntity.open)(OpenConversation(None))
    a.call(ConversationEntity.answered)(answered(10L))
    assertEquals(a.currentState.turns.size, 1)
    assertEquals(b.currentState.turns.size, 0)
  }
