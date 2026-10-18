package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.QuestionEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class QuestionEntitySuite extends munit.FunSuite:

  override def beforeAll(): Unit = Configured.reviewers =
    Reviewers.fromInline("ada:reviewer").toOption.get

  private val derive = DeriveQuestion(
    "rq-1",
    "Has lipid accumulation been shown?",
    Vector(Ref(RecordKind.facet, "rq-1:f1", 1)),
    Vector("product"),
    Vector("species", "endpoint"),
    Vector("species", "endpoint", "limitations"),
    Vector("direct measurement")
  )
  private def kit =
    val k = EventSourcedTestKit.of(QuestionEntity, "rq-1:q4")
    k.call(QuestionEntity.derive)(derive)
    k

  test("a derived question is unresolved with everything it needs still missing") {
    val k = kit
    assertEquals(k.currentState.status, QuestionStatus.unresolved)
    assertEquals(k.currentState.missingInformation, Vector("species", "endpoint"))
    assert(k.call(QuestionEntity.derive)(derive).isError)
    assert(
      EventSourcedTestKit
        .of(QuestionEntity, "x")
        .call(QuestionEntity.derive)(derive.copy(facetRefs = Vector.empty))
        .isError
    )
  }

  test(
    "answered needs a sufficiency reference and a listed reviewer; other advances follow the table"
  ) {
    val k = kit
    assert(
      k.call(QuestionEntity.advance)(
        AdvanceQuestion(QuestionStatus.answered, None, None, Vector.empty)
      ).isError
    )
    val sufficiency = Some(Ref(RecordKind.sufficiency, "rq-1:q4:feasibility", 1))
    assert(
      k.call(QuestionEntity.advance)(
        AdvanceQuestion(QuestionStatus.answered, sufficiency, Some("mallory"), Vector.empty)
      ).isError
    )
    k.call(QuestionEntity.advance)(
      AdvanceQuestion(QuestionStatus.partial, sufficiency, None, Vector("endpoint"))
    )
    assertEquals(k.currentState.missingInformation, Vector("endpoint"))
    assert(
      k.call(QuestionEntity.advance)(
        AdvanceQuestion(QuestionStatus.unresolved, None, None, Vector.empty)
      ).isError
    )
    k.call(QuestionEntity.advance)(
      AdvanceQuestion(QuestionStatus.answered, sufficiency, Some("ada"), Vector.empty)
    )
    assertEquals(k.currentState.status, QuestionStatus.answered)
    assertEquals(k.currentState.version, 3)
  }
