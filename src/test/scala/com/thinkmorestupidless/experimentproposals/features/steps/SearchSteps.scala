package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/** Steps for `features/search/retrieval.feature`. */
trait SearchSteps extends GherkinSuite with AssessmentSteps:

  private val Lipid =
    "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
  private val Constraints = "What internal operational constraints determine feasibility?"

  private var planJson: String         = ""
  private var executionId: String      = ""
  private var executionJson: String    = ""
  private var chunkId: String          = ""
  private var rerunJson: String        = ""
  private var gapId: String            = ""
  private var supplementalJson: String = ""
  private var refusals: Int            = 0

  private def retrievedView(): String =
    lastQuestionView = eventually(s"$questionId is retrieved") {
      val (_, j) = get(s"/questions/$questionId")
      Option.when(j.contains("\"status\":\"retrieved\""))(j)
    }
    lastQuestionView

  private def idsOf(json: String, field: String): Vector[String] =
    s""""$field":\\[([^\\]]*)\\]""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .toVector
      .flatMap(
        _.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).filter(_.nonEmpty)
      )

  private def planOf(view: String): String =
    val start = view.indexOf("\"plan\":{")
    assert(start >= 0, view)
    objectsOf(view.substring(start + 7)).headOption.getOrElse(fail(view))

  private def fusedExecution(): (String, String) =
    val plan = get(s"/search-plans/$questionId:plan")._2
    idsOf(plan, "executionIds")
      .map(e => e -> get(s"/search-executions/$e")._2)
      .find(_._2.contains("\"fused\":true"))
      .getOrElse(fail(s"no fused execution in $plan"))

  private def buildAndExecute(text: String): Unit =
    referenceQuestion(text)
    val view = retrievedView()
    planJson = planOf(view)
    val (id, json) = fusedExecution()
    executionId = id
    executionJson = json

  // ── retrieval.feature ────────────────────────────────────────────────────────

  Given("a scientific question") { () =>
    Seq("Paper A", "Paper B", "Paper C").foreach(ingestPaper)
    referenceQuestion(Lipid)
  }

  When("a search plan is built") { () =>
    planJson = planOf(retrievedView())
  }

  Then("the search plan names the scientific question") { () =>
    assert(
      planJson.contains(s""""questionRef":{"kind":{"type":"question"},"id":"$questionId""""),
      planJson
    )
  }

  Then("the search plan has at least one lexical query and one semantic query") { () =>
    assert(
      planJson.contains("\"kind\":{\"type\":\"lexical\"}") && planJson.contains(
        "\"kind\":{\"type\":\"semantic\"}"
      ),
      planJson
    )
  }

  Then("the search plan names its target sources, filters and stopping criterion") { () =>
    assert(
      planJson.contains("\"targetSources\":[\"") && planJson.contains("\"filters\":{") && planJson
        .contains("\"stoppingCriterion\":\""),
      planJson
    )
  }

  Given("a search plan") { () =>
    Seq("Paper A", "Paper B", "Paper C").foreach(ingestPaper)
    referenceQuestion(Lipid)
  }

  When("the search plan is executed") { () =>
    retrievedView()
    val (id, json) = fusedExecution()
    executionId = id
    executionJson = json
  }

  Then(
    "the search execution records the query, the source, the time, the parameters and every returned result with its version"
  ) { () =>
    val plan = get(s"/search-plans/$questionId:plan")._2
    val local = idsOf(plan, "executionIds")
      .map(e => get(s"/search-executions/$e")._2)
      .find(_.contains("\"query\":{"))
    assert(
      local.exists(e =>
        e.contains("\"source\":\"local\"") && e.contains("\"executedAt\":\"") && e.contains(
          "\"parameters\":{"
        ) && e.contains("\"snapshot\":[")
      ),
      local
    )
    assert(
      executionJson.contains("\"results\":[{\"rank\":1,") && executionJson.contains(
        "\"sourceVersion\":1"
      ),
      executionJson
    )
  }

  Given("a search execution whose first-ranked result is a source marked {string}") {
    (status: String) =>
      assertEquals(status, "retracted")
      ingest("retracted.xml")
      Seq("Paper A", "Paper B", "Paper C").foreach(ingestPaper)
      buildAndExecute(Lipid)
      val first = """"results":\[\{"rank":1,"score":[0-9.E-]+,"sourceId":"([^"]+)"""".r
        .findFirstMatchIn(executionJson)
        .map(_.group(1))
      assertEquals(first, Some("src:file:retracted"), executionJson)
  }

  When("eligibility is applied") { () =>
    assert(executionJson.contains("\"eligibilityApplied\":true"), executionJson)
  }

  Then("the source is excluded or downgraded") { () =>
    val eligible = executionJson.substring(
      executionJson.indexOf("\"eligibleResults\":"),
      executionJson.indexOf("\"exclusions\":")
    )
    assert(!eligible.contains("src:file:retracted"), eligible)
    assert(
      executionJson.contains(
        "\"sourceId\":\"src:file:retracted\",\"sourceVersion\":1,\"chunkId\":\"src:file:retracted"
      ) && executionJson.contains("\"action\":{\"type\":\"excluded\"}"),
      executionJson
    )
  }

  Then("the reason is recorded against the search execution") { () =>
    assert(
      executionJson.contains("\"ruleId\":\"eligibility.retracted@v1\"") && executionJson.contains(
        "\"reason\":\"the source is retracted\""
      ),
      executionJson
    )
  }

  Given("a retrieved chunk") { () =>
    Seq("Paper A", "Paper B", "Paper C").foreach(ingestPaper)
    buildAndExecute(Lipid)
    val eligible = executionJson.substring(executionJson.indexOf("\"eligibleResults\":"))
    chunkId = """"chunkId":"([^"]+)"""".r
      .findFirstMatchIn(eligible)
      .map(_.group(1))
      .getOrElse(fail(executionJson))
  }

  When("the chunk is resolved for assessment") { () =>
    val (status, body) = get(s"/chunks/$chunkId/context")
    assertEquals(status, 200, body)
    executionJson = body
  }

  Then("its parent section, adjacent context, associated tables and source metadata are available") {
    () =>
      Seq(
        "\"section\":{",
        "\"adjacent\":[",
        "\"related\":[",
        "\"title\":\"",
        "\"identifiers\":{",
        "\"chunkText\":\""
      )
        .foreach(f => assert(executionJson.contains(f), s"$f missing in $executionJson"))
  }

  Given("a search execution") { () =>
    Seq("Paper A", "Paper B", "Paper C").foreach(ingestPaper)
    buildAndExecute(Lipid)
  }

  When("the search execution is re-run from its record") { () =>
    val (status, body) = post(s"/search-executions/$executionId/rerun", "{}")
    assertEquals(status, 200, body)
    rerunJson = body
  }

  Then("the same identifiers are returned against the same source versions") { () =>
    assert(
      rerunJson.contains("\"matches\":true") && rerunJson.contains(s""""rerunOf":"$executionId""""),
      rerunJson
    )
  }

  Given("a coverage gap for a scientific question") { () =>
    referenceQuestion(Constraints)
    val view = retrievedView()
    gapId = s"$questionId:g1"
    assert(
      view.contains(s""""id":"$gapId"""") && view.contains("\"kind\":{\"type\":\"coverage\"}"),
      view
    )
  }

  When("a supplemental search is run for the coverage gap") { () =>
    val (status, body) = post(s"/gaps/$gapId/search", "{}")
    assertEquals(status, 202, body)
    supplementalJson = eventually("the supplemental execution is linked to the gap") {
      val view = retrievedView()
      Option.when(
        view.contains(s""""gapId":"$gapId"""") && view.contains(
          "\"status\":{\"type\":\"searched\"}"
        )
      )(view)
    }
  }

  Then("the search execution is linked to the coverage gap") { () =>
    assert(supplementalJson.contains(s""""gapId":"$gapId""""), supplementalJson)
    assert(supplementalJson.contains("\"supplementalExecutionIds\":[\""), supplementalJson)
  }

  Then("the supplemental search is bounded") { () =>
    // The budget is two: one more is accepted, the next is refused with 409.
    refusals = 0
    (1 to 3).foreach { _ =>
      val (status, _) = post(s"/gaps/$gapId/search", "{}")
      if status == 409 then refusals += 1
      else
        eventually("the supplemental round ends") {
          val view = retrievedView(); Option.when(view.contains("\"status\":\"retrieved\""))(view)
        }
    }
    assert(refusals >= 1, "the third supplemental search was not refused")
    val gaps = get(s"/questions/$questionId/gaps")._2
    assert("""q6:plan:e\d+""".r.findAllIn(gaps).size <= 2, gaps)
  }

  Given("a search plan whose execution returns nothing") { () =>
    referenceQuestion(Constraints)
    retrievedView()
  }

  When("the result is interpreted") { () =>
    lastQuestionView = retrievedView()
  }

  Then("it is recorded as a coverage gap") { () =>
    assert(
      lastQuestionView.contains("\"coverageGap\":true") && lastQuestionView.contains(
        "\"kind\":{\"type\":\"coverage\"}"
      ),
      lastQuestionView
    )
  }

  Then("it is not treated as evidence that the approach is impossible") { () =>
    assert(
      lastQuestionView.contains("not evidence that the approach is impossible"),
      lastQuestionView
    )
    assert(
      !lastQuestionView.contains("\"relationship\":{\"type\":\"contradicts\"}"),
      lastQuestionView
    )
  }
