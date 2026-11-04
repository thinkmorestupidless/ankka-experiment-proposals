package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.journey.JourneyDerivation
import com.thinkmorestupidless.experimentproposals.application.journey.JourneyDerivation.Lane
import com.thinkmorestupidless.experimentproposals.application.workflows.*
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.journey.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{StageExecution, StageRun}

import java.time.Instant

class JourneySuite extends munit.FunSuite:

  private val req                    = "req-1"
  private val t0                     = Instant.parse("2026-11-04T10:00:00Z")
  private def running(step: String)  = Standing("Running", Some(step), None)
  private def pausedAt(step: String) = Standing("Paused", Some(step), None)

  private def exec(stage: String, attempt: Int = 1, minutes: Int = 0, ended: Boolean = true) =
    StageExecution(
      stage,
      1,
      attempt,
      Vector.empty,
      "h",
      None,
      Vector.empty,
      Vector.empty,
      StageStatus.passed,
      None,
      t0.plusSeconds(minutes * 60L),
      Option.when(ended)(t0.plusSeconds(minutes * 60L + 30))
    )

  private def run(stages: StageExecution*) = StageRun(s"run-$req", "investigation", stages.toVector)
  private val noRun                        = run()

  private val inv =
    InvestigationState(requestId = req, text = "Can you make foie gras?", runId = s"run-$req")

  private def lane(q: QuestionInvestigationState, standing: Standing) =
    q.questionId -> Lane(
      "Has lipid accumulation been shown?",
      q,
      standing,
      StageRun(q.runId, "q", Vector.empty)
    )

  private def question(id: String, status: String) =
    QuestionInvestigationState(
      questionId = id,
      requestId = req,
      runId = s"run-$id",
      status = status
    )

  private def derive(
      s: InvestigationState,
      standing: Standing,
      lanes: Vector[(String, Lane)] = Vector.empty,
      r: StageRun = noRun,
      flags: Int = 0
  ) = JourneyDerivation.derive(req, s, standing, r, lanes, flags, decisions = 2)

  private def states(j: Journey): Map[String, String] = j.spine.map(n => n.stage -> n.state).toMap
  private def node(j: Journey, stage: String)         = j.spine.find(_.stage == stage).get

  test("a fresh request: the facets are current and every later stage unreached") {
    val j = derive(inv.copy(status = "extracting-facets"), running("extract-facets"))
    assertEquals(j.spine.map(_.stage), Stages.spine)
    assertEquals(states(j)("request"), "reached")
    assertEquals(states(j)("facets"), "current")
    assert(Stages.spine.drop(2).forall(s => states(j)(s) == "unreached"), clue(states(j)))
  }

  test("paused at facet review: the stages before are reached and name their stage runs") {
    val s = inv.copy(status = "awaiting-facet-review", facetIds = Vector.tabulate(8)(i => s"f-$i"))
    val j = derive(
      s,
      pausedAt("await-facet-review"),
      r = run(exec("extract-facets"), exec("round-trip", minutes = 1))
    )
    assertEquals(states(j)("facets"), "reached")
    assertEquals(states(j)("round-trip"), "reached")
    assertEquals(states(j)("facet-review"), "paused")
    assertEquals(node(j, "facets").detail, Some("8 facets"))
    assertEquals(node(j, "facets").runs.map(_.stage), Vector("extract-facets"))
    assertEquals(node(j, "facets").runs.head.status, "passed")
  }

  private val investigating =
    inv.copy(
      status = "questions-investigating",
      questionIds = Vector("q-1", "q-2"),
      confirmedBy = Some("ada")
    )

  test("a lane resting on a defect pauses the spine's questions, with the defect as its detail") {
    val defect  = "assess: schema_invalid (missing field)"
    val failedQ = question("q-1", "retrieved").copy(retrieved = true, failure = Some(defect))
    val j = derive(
      investigating,
      pausedAt("investigate-questions"),
      Vector(
        lane(failedQ, pausedAt("waiting")),
        lane(question("q-2", "judging"), running("judge-all"))
      )
    )
    assertEquals(states(j)("questions"), "paused")
    assertEquals(node(j, "questions").detail, Some(defect))
    val q1 = j.questions.head.nodes.find(_.stage == "assessment").get
    assertEquals(q1.state, "paused")
    assertEquals(q1.detail, Some(defect))
  }

  test("a lane waiting for verification is current and counts settled of retrieved") {
    val q = question("q-1", "awaiting-verification").copy(
      settled = Vector("f-1"),
      unsettled = Vector("f-2", "f-3")
    )
    val j = derive(
      investigating,
      pausedAt("investigate-questions"),
      Vector(lane(q, running("await-verification")))
    )
    val v = j.questions.head.nodes.find(_.stage == "verification").get
    assertEquals(v.state, "current")
    assertEquals(v.detail, Some("1 of 3 settled"))
    assertEquals(
      j.questions.head.nodes.take(3).map(_.state),
      Vector("reached", "reached", "reached")
    )
    assertEquals(states(j)("questions"), "current")
  }

  test("when every lane has reported, the questions are reached and the packages current") {
    val done = (id: String) =>
      question(id, "reported").copy(
        sufficiencyIds = Vector("s-1", "s-2"),
        reportedToInvestigation = true
      )
    val j = derive(
      investigating,
      pausedAt("investigate-questions"),
      Vector(
        lane(done("q-1"), Standing("Paused", Some("waiting"), None)),
        lane(done("q-2"), Standing("Paused", Some("waiting"), None))
      )
    )
    assertEquals(states(j)("questions"), "reached")
    assertEquals(states(j)("packages"), "current")
    assertEquals(node(j, "questions").detail, Some("2 questions, 2 reported"))
    assertEquals(j.questions.head.nodes.last.detail, Some("2 of 2 decisions judged"))
  }

  test("awaiting the decision: the decision is paused and the candidates reached") {
    val s = investigating.copy(
      status = "awaiting-decision",
      packageIds = Vector("p-1", "p-2"),
      approachIds = Vector("a-1", "a-2")
    )
    val j = derive(s, pausedAt("await-decision"))
    assertEquals(states(j)("candidates"), "reached")
    assertEquals(states(j)("decision"), "paused")
    assertEquals(node(j, "candidates").detail, Some("2 approaches"))
    assertEquals(states(j)("design"), "unreached")
  }

  test("awaiting approval with two design versions names the versions") {
    val s = investigating.copy(
      status = "awaiting-approval",
      decisionId = Some("dec-1"),
      designVersions = 2,
      lastApproval =
        Some(ApprovalOutcome(1, ApprovalDecision.values.find(_.toString.contains("change")).get))
    )
    val j = derive(s, pausedAt("await-approval"))
    assertEquals(states(j)("approval"), "paused")
    assert(node(j, "approval").detail.exists(_.contains("2 versions")), clue(node(j, "approval")))
    assertEquals(node(j, "design").detail, Some("version 2"))
  }

  test("an approved design with an impact flag: every stage reached") {
    val approve = ApprovalDecision.values.find(_.toString.startsWith("approv")).get
    val s = investigating.copy(
      status = "designed",
      designVersions = 1,
      lastApproval = Some(ApprovalOutcome(1, approve))
    )
    val j = derive(s, Standing("Completed", None, None), flags = 1)
    assert(j.spine.forall(_.state == "reached"), clue(states(j)))
    assertEquals(node(j, "impact").detail, Some("1 flag"))
    assertEquals(states(derive(s, Standing("Completed", None, None)))("impact"), "unreached")
  }

  test("an investigation paused on a recorded failure pauses at that stage with the defect") {
    val defect = "candidates: schema_invalid (no approaches)"
    val s      = investigating.copy(status = "failed", failure = Some(defect))
    val j      = derive(s, pausedAt("record-failure"))
    assertEquals(states(j)("candidates"), "paused")
    assertEquals(node(j, "candidates").detail, Some(defect))
    assertEquals(states(j)("packages"), "reached")
  }

  test("an investigation the engine failed is failed at its step, with the engine's reason") {
    val j = derive(
      investigating.copy(status = "designing"),
      Standing("Failed", Some("design"), Some("timeout"))
    )
    assertEquals(states(j)("design"), "failed")
    assertEquals(node(j, "design").detail, Some("timeout"))
  }

  test("two derivations from the same inputs are equal") {
    val q = question("q-1", "assessing")
    val args =
      (investigating, pausedAt("investigate-questions"), Vector(lane(q, running("assess-all"))))
    assertEquals(derive(args._1, args._2, args._3), derive(args._1, args._2, args._3))
  }

  test("a node lists its stage runs newest first") {
    val r = run(
      exec("extract-facets", 1, 0),
      exec("extract-facets", 2, 2),
      exec("round-trip", minutes = 3, ended = false)
    )
    val j = derive(inv.copy(status = "round-trip"), running("round-trip"), r = r)
    assertEquals(node(j, "facets").runs.map(_.attempt), Vector(2, 1))
    assertEquals(node(j, "round-trip").runs.map(_.status), Vector("running"))
  }
