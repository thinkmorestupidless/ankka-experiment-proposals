package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.RequestEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class RequestEntitySuite extends munit.FunSuite:

  override def beforeAll(): Unit = Configured.reviewers =
    Reviewers.fromInline("ada:reviewer").toOption.get

  private val text = "Can you make foie gras?"
  private def kit =
    val k = EventSourcedTestKit.of(RequestEntity, "rq-1")
    k.call(RequestEntity.submit)(SubmitRequest(text, "customer"))
    k

  test("the text is stored once, byte for byte, and a second submit is refused") {
    val k = kit
    assertEquals(k.currentState.text, text)
    assert(k.call(RequestEntity.submit)(SubmitRequest("other", "x")).isError)
    assertEquals(k.currentState.text, text)
    assert(k.allEvents.size == 1)
  }

  test("a facet set and a round-trip check are recorded; passing is the rule's verdict") {
    val k    = kit
    val refs = Vector(FacetRef("rq-1:f1", "product", Importance.mandatory))
    k.call(RequestEntity.recordFacetSet)(RecordFacetSet(refs))
    k.call(RequestEntity.recordFacetSet)(RecordFacetSet(refs))
    assertEquals(k.currentState.facetSetVersion, 1)
    val passing = RoundTripCheck(text, Vector.empty, Vector.empty, Vector.empty, Vector.empty)
    val r1 =
      k.call(RequestEntity.recordRoundTrip)(RecordRoundTrip(passing, "roundtrip@v1")).replyValue
    assert(r1.passed && r1.run == 1)
    val failing = passing.copy(unsupportedAdditions = Vector(Issue("lunar", Some("time"))))
    val r2 =
      k.call(RequestEntity.recordRoundTrip)(RecordRoundTrip(failing, "roundtrip@v1")).replyValue
    assert(!r2.passed && r2.run == 2)
    assertEquals(k.currentState.roundTripRuns, 2)
  }

  test("confirmation is refused while a mandatory facet is proposed, and by an unlisted name") {
    val k = kit
    val proposed =
      Vector(FacetState("rq-1:f1", "product", Importance.mandatory, FacetStatus.proposed))
    assert(k.call(RequestEntity.confirmFacets)(ConfirmFacets("ada", proposed)).isError)
    val settled = Vector(
      FacetState("rq-1:f1", "product", Importance.mandatory, FacetStatus.confirmed),
      FacetState("rq-1:f3", "biological source", Importance.mandatory, FacetStatus.unresolved),
      FacetState("rq-1:f6", "time", Importance.preferred, FacetStatus.proposed)
    )
    assert(k.call(RequestEntity.confirmFacets)(ConfirmFacets("mallory", settled)).isError)
    k.call(RequestEntity.confirmFacets)(ConfirmFacets("ada", settled))
    assertEquals(k.currentState.confirmedBy, Some("ada"))
    assertEquals(k.currentState.status, RequestStatus.facets_confirmed)
  }
