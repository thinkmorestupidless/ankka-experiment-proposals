package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/** Steps for `features/request/questions.feature`. */
trait QuestionSteps extends GherkinSuite with RequestSteps:

  protected var questionId: String        = ""
  protected var lastQuestionJson: String  = ""
  protected var lastQuestionsJson: String = ""

  protected def confirmFacets(names: Seq[String]): Unit =
    awaitFacetReview()
    names.foreach(n => review(n, "confirm"))
    val (status, body) =
      post(s"/requests/$requestId/facets:confirmed", """{"reviewer":"demo-reviewer"}""")
    assertEquals(status, 200, body)

  protected def awaitQuestions(): Unit =
    lastQuestionsJson = eventually(s"the questions of $requestId are derived") {
      val (_, w)  = get(s"/workflows/investigation/$requestId")
      val (_, qs) = get(s"/requests/$requestId/questions")
      Option.when(
        (w.contains("questions-investigating") || w.contains("\"status\":\"Completed\"")) && qs
          .contains("\"question\":")
      )(qs)
    }
    lastFacetsJson = get(s"/requests/$requestId/facets")._2

  private def questionObjects: Vector[String] = objectsOf(lastQuestionsJson)

  private def facetNamesOf(q: String): Vector[String] =
    """"facetNames":\[([^\]]*)\]""".r
      .findFirstMatchIn(q)
      .map(_.group(1))
      .toVector
      .flatMap(_.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty))

  Given("the facets {string}, {string}, {string}, {string}, {string} and {string} are confirmed") {
    (a: String, b: String, c: String, d: String, e: String, f: String) =>
      confirmFacets(Seq(a, b, c, d, e, f))
  }

  When("the scientific questions are derived") { () =>
    awaitQuestions()
  }

  Then("every mandatory facet is investigated by at least one scientific question") { () =>
    val mandatory = objectsOf(lastFacetsJson)
      .filter(_.contains(""""importance":{"type":"mandatory"}"""))
      .flatMap(f => """"name":"([^"]+)"""".r.findFirstMatchIn(f).map(_.group(1)))
    assert(mandatory.nonEmpty, lastFacetsJson)
    val investigated = questionObjects.flatMap(facetNamesOf).toSet
    val uncovered    = mandatory.filterNot(investigated.contains)
    assert(uncovered.isEmpty, s"uninvestigated mandatory facets: $uncovered")
  }

  Then("every scientific question investigates at least one facet") { () =>
    val qs = questionObjects
    assert(qs.nonEmpty, lastQuestionsJson)
    assert(qs.forall(q => facetNamesOf(q).nonEmpty), lastQuestionsJson)
  }

  Then("the scientific question {string} requires the answer fields {}") {
    (question: String, fields: String) =>
      val required = "\"([^\"]+)\"".r.findAllMatchIn(fields).map(_.group(1)).toVector
      assert(required.size == 6, s"expected six quoted fields in: $fields")
      val q = questionObjects
        .find(_.contains(s""""question":${quote(question)}"""))
        .getOrElse(fail(s"no question '$question' in $lastQuestionsJson"))
      required.foreach(field => assert(q.contains(s""""$field""""), s"$field missing in $q"))
  }

  Given("a scientific question with no eligible evidence") { () =>
    awaitQuestions()
    questionId = """"id":"([^"]+)"""".r
      .findFirstMatchIn(lastQuestionsJson)
      .map(_.group(1))
      .getOrElse(fail(lastQuestionsJson))
  }

  When("the status of the scientific question is read") { () =>
    // The view carries the question and its investigation; the status asked about is the question's.
    val view = get(s"/questions/$questionId")._2
    lastQuestionJson =
      objectsOf(view.substring(view.indexOf("\"question\":") + 11)).headOption.getOrElse(view)
  }

  Then("the status is {string}") { (status: String) =>
    assert(enumIs(lastQuestionJson, "status", status, "unresolved"), lastQuestionJson)
  }

  Then("the missing information is stated") { () =>
    assert(lastQuestionJson.contains("\"missingInformation\":[\""), lastQuestionJson)
  }
