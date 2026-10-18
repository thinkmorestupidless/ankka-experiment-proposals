package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.FacetEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class FacetEntitySuite extends munit.FunSuite:

  override def beforeAll(): Unit = Configured.reviewers =
    Reviewers.fromInline("ada:reviewer").toOption.get

  private def kit(
      origin: FacetOrigin = FacetOrigin.inferred,
      status: FacetStatus = FacetStatus.proposed,
      importance: Importance = Importance.mandatory
  ) =
    val k = EventSourcedTestKit.of(FacetEntity, "rq-1:f2")
    k.call(FacetEntity.propose)(
      ProposeFacet(
        "rq-1",
        "production approach",
        "Cell cultivation",
        origin,
        importance,
        status,
        Vector.empty,
        Vector.empty
      )
    )
    k

  test(
    "confirming sets human_confirmed, keeps the prior origin in history and records the reviewer"
  ) {
    val k = kit()
    val f = k
      .call(FacetEntity.review)(
        ReviewFacet(FacetAction.confirm, "ada", "the context is cell cultivation")
      )
      .replyValue
    assertEquals(f.origin, FacetOrigin.human_confirmed)
    assertEquals(f.status, FacetStatus.confirmed)
    assertEquals(f.history.map(_.origin), Vector(FacetOrigin.inferred))
    assertEquals(f.reviews.map(_.reviewer), Vector("ada"))
    assertEquals(f.version, 2)
  }

  test("an unlisted reviewer, an illegal transition and a second propose persist nothing") {
    val k = kit()
    assert(k.call(FacetEntity.review)(ReviewFacet(FacetAction.confirm, "mallory", "x")).isError)
    k.call(FacetEntity.review)(ReviewFacet(FacetAction.reject, "ada", "not this"))
    val r = k.call(FacetEntity.review)(ReviewFacet(FacetAction.confirm, "ada", "after all"))
    assert(r.isError)
    assert(
      k.call(FacetEntity.propose)(
        ProposeFacet(
          "rq-1",
          "x",
          "y",
          FacetOrigin.explicit,
          Importance.mandatory,
          FacetStatus.proposed,
          Vector.empty,
          Vector.empty
        )
      ).isError
    )
    assertEquals(k.allEvents.size, 2)
  }

  test(
    "an amendment keeps the status and the earlier statement; an assumption needs a mandatory facet"
  ) {
    val k = kit(status = FacetStatus.unresolved, origin = FacetOrigin.domain_derived)
    val amended = k
      .call(FacetEntity.review)(
        ReviewFacet(FacetAction.amend, "ada", "narrower", Some("Duck cells"))
      )
      .replyValue
    assertEquals(amended.statement, "Duck cells")
    assertEquals(amended.status, FacetStatus.unresolved)
    assertEquals(amended.history.head.statement, "Cell cultivation")
    val assumed = k
      .call(FacetEntity.assume)(AssumeFacet("Pekin duck", "ada", "the lab has duck lines"))
      .replyValue
    assertEquals(assumed.status, FacetStatus.assumed)
    assertEquals(assumed.assumedValue, Some("Pekin duck"))
    val preferred = kit(importance = Importance.preferred)
    assert(preferred.call(FacetEntity.assume)(AssumeFacet("v", "ada", "r")).isError)
  }

  test("a flag for the same changed ref is recorded once") {
    val k   = kit()
    val ref = Ref(RecordKind.source, "src:file:paper-b", 1)
    k.call(FacetEntity.flagForReview)(FlagForReview(ref, "retracted"))
    k.call(FacetEntity.flagForReview)(FlagForReview(ref, "retracted"))
    assertEquals(k.currentState.flags.size, 1)
    assertEquals(k.allEvents.size, 2)
  }
