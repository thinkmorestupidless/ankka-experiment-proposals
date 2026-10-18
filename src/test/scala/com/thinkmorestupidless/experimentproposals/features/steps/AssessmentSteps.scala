package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/** Steps for `features/evidence/assessment.feature` and `features/evidence/sufficiency.feature`. */
trait AssessmentSteps extends GherkinSuite with QuestionSteps with EvidenceSteps:

  protected var questionText: String        = ""
  protected var assessedRecordId: String    = ""
  protected var lastAssessmentJson: String  = ""
  protected var lastQuestionView: String    = ""
  protected var lastSufficiencyJson: String = ""
  protected var decisionUnderTest: String   = ""

  /**
   * The reference request through confirmation and derivation; answers the question with this text.
   */
  protected def referenceQuestion(text: String): String =
    submitRequest("Can you make foie gras?")
    confirmFacets(
      Seq(
        "product",
        "production approach",
        "biological source",
        "desired attributes",
        "time",
        "resources"
      )
    )
    awaitQuestions()
    val q = objectsOf(lastQuestionsJson)
      .find(_.contains(s""""question":${quote(text)}"""))
      .getOrElse(fail(s"no question '$text' in $lastQuestionsJson"))
    questionId = """"id":"([^"]+)"""".r.findFirstMatchIn(q).map(_.group(1)).getOrElse(fail(q))
    eventually(s"$questionId waits for retrieval") {
      val (_, j) = get(s"/questions/$questionId")
      Option.when(j.contains("\"status\":\"retrieved\"") || j.contains("awaiting-retrieval"))(j)
    }
    questionId

  protected def assessRecords(ids: Seq[String]): Unit =
    val (status, body) =
      post(s"/questions/$questionId/assess", s"""{"recordIds":[${ids.map(quote).mkString(",")}]}""")
    assertEquals(status, 202, body)
    lastQuestionView = eventually(s"the assessments of ${ids.mkString(", ")} exist") {
      val (_, j) = get(s"/questions/$questionId")
      Option.when(ids.forall(id => j.contains(s""""id":"$questionId:$id"""")))(j)
    }

  protected def assessmentOf(recordId: String): String = get(
    s"/assessments/$questionId:$recordId"
  )._2

  protected def judge(decision: String): Unit =
    decisionUnderTest = decision
    val (status, body) =
      post(s"/questions/$questionId/sufficiency", s"""{"intendedDecision":${quote(decision)}}""")
    assertEquals(status, 202, body)
    lastQuestionView = eventually(s"the decision '$decision' is judged") {
      val (_, j) = get(s"/questions/$questionId")
      Option.when(j.contains(s""""intendedDecision":${quote(decision)}"""))(j)
    }
    lastSufficiencyJson =
      objectsOf(lastQuestionView.substring(lastQuestionView.indexOf("\"sufficiency\":[") + 14))
        .find(_.contains(s""""intendedDecision":${quote(decision)}"""))
        .getOrElse(fail(lastQuestionView))

  private def ingestVerified(name: String): String =
    ingestPaper(name); settled(sourceId)
    assessedRecordId = s"$sourceId:v1:fd1"
    assessedRecordId

  // ── assessment.feature ───────────────────────────────────────────────────────

  Given("the scientific question {string}") { (text: String) =>
    questionText = text
    referenceQuestion(text)
  }

  Given("the target species is {string}")((_: String) => ())

  Given("the verified finding from the source {string} with the endpoint {string}") {
    (name: String, endpoint: String) =>
      ingestVerified(name)
      assert(
        recordJson(assessedRecordId).contains(s""""observedEndpoint":"$endpoint""""),
        recordJson(assessedRecordId)
      )
  }

  Given("the verified finding from the source {string} with the species {string}") {
    (name: String, _: String) =>
      ingestVerified(name)
  }

  Given("the verified finding from the source {string} with the outcome {string}") {
    (name: String, outcome: String) =>
      ingestVerified(name)
      assert(
        recordJson(assessedRecordId).contains(s""""outcomeDirection":{"type":"$outcome"}"""),
        recordJson(assessedRecordId)
      )
  }

  Given("a verified finding") { () =>
    ingestVerified("Paper A")
  }

  When("the finding is assessed against the scientific question") { () =>
    assessRecords(Seq(assessedRecordId))
    lastAssessmentJson = assessmentOf(assessedRecordId)
  }

  Then("the relationship is not {string} with the applicability {string}") {
    (relationship: String, applicability: String) =>
      val both = lastAssessmentJson.contains(
        s""""relationship":{"type":"$relationship"}"""
      ) && lastAssessmentJson.contains(s""""applicability":{"type":"$applicability"}""")
      assert(!both, lastAssessmentJson)
  }

  Then("the rationale names the endpoint mismatch") { () =>
    assert(lastAssessmentJson.toLowerCase.contains("endpoint"), lastAssessmentJson)
  }

  Then("the applicability is {string}") { (applicability: String) =>
    assert(
      lastAssessmentJson.contains(s""""applicability":{"type":"$applicability"}"""),
      lastAssessmentJson
    )
  }

  Then("the species rule prevents the applicability {string}") { (applicability: String) =>
    assert(
      !lastAssessmentJson.contains(
        s""""applicability":{"type":"$applicability"}"""
      ) && lastAssessmentJson.contains("species-mismatch-blocks-direct") && lastAssessmentJson
        .contains("\"outcome\":\"adjusted\""),
      lastAssessmentJson
    )
  }

  Then("the relationship is {string} or {string}") { (a: String, b: String) =>
    assert(
      lastAssessmentJson.contains(s""""relationship":{"type":"$a"}""") || lastAssessmentJson
        .contains(s""""relationship":{"type":"$b"}"""),
      lastAssessmentJson
    )
  }

  Then("the assessment states a relationship, an applicability, a rationale and limitations") { () =>
    Seq("\"relationship\"", "\"applicability\"", "\"rationale\":\"", "\"limitations\":[").foreach(
      k => assert(lastAssessmentJson.contains(k), lastAssessmentJson)
    )
  }

  Then("no single numeric truth score is presented as the judgement") { () =>
    assert(
      !lastAssessmentJson.toLowerCase.contains("score") && !lastAssessmentJson.toLowerCase.contains(
        "confidence"
      ),
      lastAssessmentJson
    )
  }

  // ── sufficiency.feature ──────────────────────────────────────────────────────

  Given("the verified findings from the sources {string}, {string} and {string} are assessed") {
    (a: String, b: String, c: String) =>
      val ids = Seq(a, b, c).map(ingestVerified)
      assessRecords(ids)
  }

  When("sufficiency is judged for the decision {string}") { (decision: String) =>
    judge(decision)
  }

  /**
   * The feature says "sufficient for the proposed next step"; the closed list spells it without the
   * article.
   */
  private def wire(status: String): String =
    status.replace("the proposed", "proposed").replace(' ', '_')

  Then("the sufficiency status is {string}") { (status: String) =>
    assert(
      lastSufficiencyJson.contains(s""""status":{"type":"${wire(status)}"}"""),
      lastSufficiencyJson
    )
  }

  Then("the critical gaps list the missing direct avian lipid accumulation evidence") { () =>
    val (_, gaps) = get(s"/questions/$questionId/gaps")
    assert(
      gaps.toLowerCase.contains("avian") && gaps.toLowerCase.contains("lipid accumulation"),
      gaps
    )
  }

  Then("the sufficiency status is {string} or {string}") { (a: String, b: String) =>
    assert(
      Seq(a, b).exists(st => lastSufficiencyJson.contains(s""""status":{"type":"${wire(st)}"}""")),
      lastSufficiencyJson
    )
  }

  Then("the assumptions are listed") { () =>
    assert(lastSufficiencyJson.contains("\"assumptions\":[\""), lastSufficiencyJson)
  }

  Given("a scientific question for which no source was retrieved") { () =>
    referenceQuestion("What internal operational constraints determine feasibility?")
  }

  When("sufficiency is judged for that scientific question") { () =>
    judge("recommend production feasibility")
  }

  Then("the coverage judgement distinguishes an inadequate search from absent evidence") { () =>
    assert(
      lastSufficiencyJson.contains(
        "\"coverageJudgement\":{\"type\":\"search_inadequate\"}"
      ) || lastSufficiencyJson.contains("\"coverageJudgement\":{\"type\":\"evidence_absent\"}"),
      lastSufficiencyJson
    )
  }

  Then(
    "the escalation is a bounded supplemental search, a clarification or an exploratory question"
  ) { () =>
    assert(
      Seq("supplemental_search", "clarification", "exploratory_question").exists(e =>
        lastSufficiencyJson.contains(s""""escalation":{"type":"$e"}""")
      ),
      lastSufficiencyJson
    )
  }
