package com.thinkmorestupidless.experimentproposals.unit

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.ankka.core.Codecs

/** How the closed lists and references are encoded is protocol, so it is pinned here. */
class EnumerationsSuite extends munit.FunSuite:

  final case class Holder(origin: FacetOrigin, status: RecordStatus, ref: Ref)
  private given JsonValueCodec[Holder] = Codecs.make[Holder]

  test("an enumeration value is encoded by its wire name and a value outside the list is refused") {
    val json = writeToString(
      Holder(
        FacetOrigin.domain_derived,
        RecordStatus.requires_review,
        Ref(RecordKind.finding, "f:1", 2)
      )
    )
    assert(json.contains("domain_derived"), json)
    assert(json.contains("requires_review"), json)
    assertEquals(readFromString[Holder](json).origin, FacetOrigin.domain_derived)
    intercept[Throwable](readFromString[Holder](json.replace("domain_derived", "guessed")))
  }

  test("a ref round-trips through its key, with colons in the id") {
    val ref = Ref(RecordKind.search_execution, "rq-1:q4:plan:e2", 1)
    assertEquals(Ref.parse(ref.key), Right(ref))
    assert(Ref.parse("nonsense").isLeft)
  }
