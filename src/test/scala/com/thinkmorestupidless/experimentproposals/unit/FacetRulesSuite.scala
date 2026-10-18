package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.rules.FacetRules

class FacetRulesSuite extends munit.FunSuite:

  private val text = "Can you make foie gras?"
  private def proposal(
      name: String,
      origin: String = "inferred",
      status: String = "proposed",
      quotations: Vector[String] = Vector.empty
  ) =
    FacetProposal(name, s"$name statement", origin, "mandatory", status, quotations)
  private val full = FacetSet(FacetRules.Names.map {
    case "product" => proposal("product", "explicit", quotations = Vector("foie gras"))
    case n         => proposal(n)
  })

  test("a complete set with resolvable explicit quotations passes and carries spans") {
    val r = FacetRules.gate("rq-1", text, full).toOption.get
    assertEquals(r.size, 8)
    assertEquals(r.head.spans, Vector(Span(13, 22, "foie gras")))
    assertEquals(r.head.origin, FacetOrigin.explicit)
  }

  test("a value outside a closed list, a missing facet and an unresolvable quotation are defects") {
    assertEquals(
      FacetRules
        .gate("rq-1", text, FacetSet(full.facets.map(_.copy(origin = "guessed"))))
        .left
        .map(_.kind),
      Left(DefectKind.schema_invalid)
    )
    assertEquals(
      FacetRules.gate("rq-1", text, FacetSet(full.facets.tail)).left.map(_.kind),
      Left(DefectKind.schema_invalid)
    )
    val bad = FacetSet(
      full.facets.map(f => if f.name == "product" then f.copy(quotations = Vector("caviar")) else f)
    )
    assertEquals(
      FacetRules.gate("rq-1", text, bad).left.map(_.kind),
      Left(DefectKind.span_unresolved)
    )
  }

  test(
    "locating is whitespace tolerant, the round-trip passes only when every list is empty, markers are stable"
  ) {
    assertEquals(
      FacetRules.locate("make  foie\ngras now", "foie gras").map(_.quotation),
      Some("foie\ngras")
    )
    val check = RoundTripCheck(text, Vector.empty, Vector.empty, Vector.empty, Vector.empty)
    assert(FacetRules.passed(check))
    assert(!FacetRules.passed(check.copy(ambiguities = Vector(Issue("which foie gras")))))
    assertEquals(FacetRules.marker(text), FacetRules.marker("Can you make foie gras?"))
    assertEquals(
      FacetRules.roundTripMarker(text, Vector("b" -> "2", "a" -> "1")),
      FacetRules.roundTripMarker(text, Vector("a" -> "1", "b" -> "2"))
    )
  }
