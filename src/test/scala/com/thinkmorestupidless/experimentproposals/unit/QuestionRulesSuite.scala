package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.rules.QuestionRules

class QuestionRulesSuite extends munit.FunSuite:

  private def facet(
      id: String,
      name: String,
      importance: Importance,
      status: FacetStatus = FacetStatus.confirmed
  ) =
    Facet(
      id,
      registered = true,
      requestId = "rq-1",
      name = name,
      statement = s"$name statement",
      importance = importance,
      status = status,
      version = 1
    )
  private val facets = Vector(
    facet("rq-1:f1", "product", Importance.mandatory),
    facet("rq-1:f2", "biological source", Importance.mandatory),
    facet("rq-1:f3", "time", Importance.preferred),
    facet("rq-1:f4", "success", Importance.preferred, FacetStatus.rejected)
  )
  private def q(text: String, names: String*) = QuestionProposal(
    text,
    names.toVector,
    Vector("evidence"),
    Vector("Species"),
    Vector("criterion")
  )

  test("every question investigates a facet of the set and every mandatory facet is investigated") {
    val ok = QuestionRules
      .gate(
        "rq-1",
        facets,
        QuestionSet(Vector(q("A?", "product"), q("B?", "biological source", "time")))
      )
      .toOption
      .get
    assertEquals(
      ok.map(_.facetNames),
      Vector(Vector("product"), Vector("biological source", "time"))
    )
    assertEquals(ok.head.answerFields, Vector("species"))
    assertEquals(ok.head.facetRefs.head.id, "rq-1:f1")
  }

  test(
    "an unknown or rejected facet, a missing field and an uncovered mandatory facet are defects"
  ) {
    assertEquals(
      QuestionRules
        .gate("rq-1", facets, QuestionSet(Vector(q("A?", "product", "success"))))
        .left
        .map(_.kind),
      Left(DefectKind.schema_invalid)
    )
    assertEquals(
      QuestionRules
        .gate(
          "rq-1",
          facets,
          QuestionSet(Vector(q("A?", "product").copy(answerFields = Vector.empty)))
        )
        .left
        .map(_.kind),
      Left(DefectKind.schema_invalid)
    )
    assertEquals(
      QuestionRules.gate("rq-1", facets, QuestionSet(Vector(q("A?", "product")))).left.map(_.kind),
      Left(DefectKind.coverage_gap)
    )
    assert(
      QuestionRules
        .gate(
          "rq-1",
          facets,
          QuestionSet(Vector(q("A?", "product")), uninvestigated = Vector("biological source"))
        )
        .isRight
    )
  }

  test("the marker ignores review state and rejected facets' absence changes it") {
    val a = QuestionRules.marker("t", facets)
    val b = QuestionRules.marker(
      "t",
      facets.map(f => if f.name == "product" then f.copy(status = FacetStatus.proposed) else f)
    )
    assertEquals(a, b)
    assertNotEquals(a, QuestionRules.marker("t", facets.filterNot(_.name == "time")))
  }
