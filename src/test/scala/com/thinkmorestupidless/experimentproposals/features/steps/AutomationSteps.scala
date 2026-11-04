package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.ankka.agent.{
  ChatMessage,
  MessageContent,
  ModelCallFailed,
  ModelProvider,
  ModelRequest,
  ModelResponse
}
import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Steps for `features/investigation/automation.feature`: the investigation runs itself between the
 * reviewer's pauses. A model call can be held or failed by its case line, which is how a step makes
 * verification run long or a stage fail after its retries.
 */
trait AutomationSteps extends GherkinSuite with CommonSteps with RequestSteps:

  // ── the model, held or failed by case line ──────────────────────────────────

  private val held    = java.util.concurrent.ConcurrentHashMap[String, Promise[Unit]]()
  private val failing = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** Holds every call whose case line contains the key, retries included, until released. */
  private def holdEvery(key: String): Unit = held.put(key, Promise[Unit]()): Unit
  private def failEvery(key: String): Unit = failing.add(key): Unit
  private def releaseAll(): Unit =
    held.values.asScala.foreach(_.trySuccess(())); held.clear(); failing.clear()

  private def caseLine(request: ModelRequest): String =
    request.messages
      .collect { case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString("\n")
      }
      .lastOption
      .getOrElse("")

  override protected def modelProvider: ModelProvider =
    val underlying = super.modelProvider
    new ModelProvider:
      def name: String      = underlying.name
      def modelName: String = underlying.modelName
      def complete(request: ModelRequest): Future[ModelResponse] =
        val text = caseLine(request)
        if failing.asScala.exists(text.contains) then
          Future.failed(ModelCallFailed(name, "the step fails this stage's model call"))
        else
          held.asScala.collectFirst { case (k, p) if text.contains(k) => p } match
            case Some(p) =>
              p.future.flatMap(_ => underlying.complete(request))(using ExecutionContext.global)
            case None => underlying.complete(request)

  // ── helpers ─────────────────────────────────────────────────────────────────

  private val Lipid =
    "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
  private val Mandatory = Seq("f1", "f2", "f3", "f8")
  private val Findings  = Vector("a", "b", "c").map(p => s"src:file:paper-$p:v1:fd1")
  private val Verified =
    Vector("a", "b", "c").flatMap(p =>
      Vector(s"src:file:paper-$p:v1:fd1", s"src:file:paper-$p:v1:cl1")
    )

  private var confirmedFor: String     = ""
  private var heldRecords: Seq[String] = Nil
  private var sourcesBefore: Int       = 0
  private var assessRunsBefore: Int    = 0
  private var sawWaiting: Boolean      = false
  private var assessedEarly: Boolean   = false
  private var savedBound               = Configured.verificationBound

  private def lipidId: String           = s"$requestId:q4"
  private def questionIds: Seq[String]  = (1 to 6).map(n => s"$requestId:q$n")
  private def view(qid: String): String = get(s"/questions/$qid")._2
  private def lane(qid: String): String = get(s"/workflows/question-investigation/$qid")._2
  private def runOf(id: String): String = get(s"/runs/run-$id")._2
  private def stages(run: String): Vector[String] =
    """"stage":"([a-z-]+)"""".r.findAllMatchIn(run).map(_.group(1)).toVector
  private def assessed(qview: String): Vector[String] =
    """"recordRef":\{"kind":\{"type":"[a-z_]+"\},"id":"([^"]+)"""".r
      .findAllMatchIn(qview)
      .map(_.group(1))
      .toVector
  private def sourceCount: Int = """"id":"src:""".r.findAllIn(get("/sources")._2).size
  private def longly[A](what: String)(check: => Option[A]): A =
    testKit.eventually(what, if patience > 45.seconds then patience else 4.minutes)(check)

  /** Whether a lane rests: reported, or paused with a failure recorded. */
  private def laneRests(qid: String): Boolean =
    val w = lane(qid)
    w.contains("\\\"status\\\":\\\"reported\\\"") ||
    (w.contains("\"status\":\"Paused\"") && w.contains("\\\"failure\\\":"))

  private def confirmTheFacets(): Unit =
    if confirmedFor != requestId then
      eventually(s"the facets of $requestId are proposed") {
        val (_, w) = get(s"/workflows/investigation/$requestId")
        Option.when(w.contains("\"status\":\"Paused\"") && w.contains("awaiting-facet-review"))(w)
      }
      sourcesBefore = sourceCount
      Mandatory.foreach { f =>
        val (status, body) = post(
          s"/facets/$requestId:$f/review",
          """{"action":"confirm","reviewer":"demo-reviewer","rationale":"feature step"}"""
        )
        assertEquals(status, 201, body)
      }
      val (status, body) =
        post(s"/requests/$requestId/facets:confirmed", """{"reviewer":"demo-reviewer"}""")
      assertEquals(status, 200, body)
      confirmedFor = requestId

  private def settledVerification(recordId: String): Boolean =
    get(s"/workflows/verification/$recordId:a1")._2.contains("\"isTerminal\":true")

  /** A copy of a reference paper whose every verification call is held. */
  private def ingestHeld(file: String, sourceId: String): Unit =
    heldRecords = Seq("fd1", "cl1").map(k => s"$sourceId:v1:$k")
    heldRecords.foreach(r => holdEvery(s"# case: verify:$r:"))
    ingest(file, extract = true): Unit

  // ── sources and the facets ──────────────────────────────────────────────────

  Given("no source is ingested") { () =>
    assertEquals(sourceCount, 0, "this scenario runs first, on an empty store")
  }

  Given("the sources {string}, {string}, {string} and the retracted source are ingested") {
    (a: String, b: String, c: String) =>
      releaseAll()
      Configured.verificationBound = savedBound
      Seq(a, b, c).foreach { name =>
        val file = name.toLowerCase.replace(' ', '-') + ".xml"
        val id   = ingest(file, extract = true)
        eventually(s"the records of $id are verified") {
          val (_, rows) = get(s"/sources/$id/records")
          Option
            .when(rows.contains("\"status\":\"verified\"") && !rows.contains("\"proposed\""))(())
        }
      }
      ingest("retracted.xml"): Unit
  }

  Given("the reviewer confirms the facets")(() => confirmTheFacets())

  Given("the investigation runs")(() => confirmTheFacets())

  When("nothing else is done")(() => ())

  // ── the automatic stages ────────────────────────────────────────────────────

  When("the automatic stage {string} runs") { (stage: String) =>
    confirmTheFacets()
    stage match
      case "search" =>
        longly("every question's search is done") {
          Option.when(questionIds.forall(q => lane(q).contains("\\\"retrieved\\\":true")))(())
        }
      case _ =>
        longly(s"the lipid question's $stage has run") {
          val w = lane(lipidId)
          if heldRecords.nonEmpty && heldRecords.exists(r => !settledVerification(r)) then
            if w.contains("awaiting-verification") then sawWaiting = true
            if stages(runOf(lipidId)).contains("assess") then assessedEarly = true
          Option.when(laneRests(lipidId))(())
        }
  }

  When("the automatic stage {string} finds nothing in the store") { (_: String) =>
    longly("every question's search is done") {
      Option.when(questionIds.forall(q => lane(q).contains("\\\"retrieved\\\":true")))(())
    }
  }

  Given(
    "the search for a scientific question retrieved three verified findings that are eligible evidence"
  ) { () =>
    Findings.foreach(f =>
      assert(get(s"/findings/$f")._2.contains("\"status\":{\"type\":\"verified\"}"), f)
    )
  }

  Given(
    "the search for a scientific question retrieved a source whose findings are still being verified"
  ) { () =>
    ingestHeld("paper-a.xml@endpoint_swap", "src:file:paper-a@endpoint_swap")
  }

  Given(
    "the search for a scientific question retrieved a source whose verification does not settle within the bound"
  ) { () =>
    savedBound = Configured.verificationBound
    Configured.verificationBound = 2.seconds
    ingestHeld("paper-b.xml@species_drop", "src:file:paper-b@species_drop")
  }

  Given("the decisions {string} and {string} are configured") { (a: String, b: String) =>
    assert(
      Configured.decisions.contains(a) && Configured.decisions.contains(b),
      Configured.decisions
    )
  }

  Given("a stage that fails after its retries") { () =>
    failEvery(
      s"# case: sufficiency:${Ids.short(Lipid)}:${Ids.short(Configured.decisions.head)}:"
    )
  }

  When("the investigation reaches the stage") { () =>
    confirmTheFacets()
    longly("the lipid question's sufficiency fails after its retries") {
      val w = lane(lipidId)
      Option.when(w.contains("\"status\":\"Paused\"") && w.contains("sufficiency: "))(w)
    }
  }

  Given("the automatic stage {string} has run for a scientific question") { (_: String) =>
    confirmTheFacets()
    longly("the lipid question reports")(Option.when(laneRests(lipidId))(()))
    assessRunsBefore = stages(runOf(lipidId)).count(_ == "assess")
  }

  When("the operator requests the assessment of that scientific question again") { () =>
    val body            = Findings.map(f => "\"" + f + "\"").mkString("{\"recordIds\":[", ",", "]}")
    val (status, reply) = post(s"/questions/$lipidId/assess", body)
    assertEquals(status, 202, reply)
  }

  // ── outcomes ────────────────────────────────────────────────────────────────

  Then("the investigation reaches the pause for the choice of approach") { () =>
    longly(s"$requestId reaches the choice of approach") {
      val (_, w) = get(s"/workflows/investigation/$requestId")
      Option.when(w.contains("awaiting-decision"))(w)
    }
  }

  Then("a stage run is recorded for every stage between") { () =>
    val spine = stages(runOf(requestId))
    Seq("extract-facets", "round-trip", "derive-questions", "build-packages", "candidates").foreach(
      s => assert(spine.contains(s), s"$s missing from $spine")
    )
    val lipid = stages(runOf(lipidId))
    Seq("plan", "execute", "eligibility", "verification", "assess", "sufficiency").foreach(s =>
      assert(lipid.contains(s), s"$s missing from the lipid question's run: $lipid")
    )
    assert(runOf(requestId).contains("\"actor\":\"system:investigation\""))
  }

  Then("all three findings are assessed against the scientific question") { () =>
    val ids = assessed(view(lipidId))
    Findings.foreach(f => assert(ids.contains(f), s"$f not assessed: $ids"))
  }

  Then("no finding that is unverified or not eligible evidence is assessed") { () =>
    val ids = assessed(view(lipidId))
    assertEquals(ids.toSet, Verified.toSet, "only the verified eligible records are assessed")
  }

  Then("the stage waits until the verification of every retrieved finding has settled") { () =>
    assert(sawWaiting, "the question did not wait while the source's verification ran")
    assert(!assessedEarly, "the question assessed before the source's verification settled")
  }

  Then("only the verified findings that are eligible evidence are assessed") { () =>
    val ids = assessed(view(lipidId))
    heldRecords.foreach(r => assert(!ids.contains(r), s"an unverified record was assessed: $r"))
    Findings.foreach(f => assert(ids.contains(f), s"$f not assessed: $ids"))
    releaseAll()
  }

  Then("the stage fails with the defect {string}") { (defect: String) =>
    val wire = defect.replace(' ', '_')
    assert(
      runOf(lipidId).contains(wire),
      s"no $wire in the lipid question's run: ${lane(lipidId).replaceAll("(execution|chunk)[A-Za-z]*\\\\\":\\[[^]]*]", "")}"
    )
  }

  Then("the investigation pauses at the stage") { () =>
    val w = lane(lipidId)
    assert(w.contains("\"status\":\"Paused\"") && w.contains("verification: "), w)
    val journey = get(s"/requests/$requestId/journey")._2
    assert(journey.contains("\"stage\":\"verification\",\"state\":\"failed\""), journey)
    releaseAll()
    Configured.verificationBound = savedBound
  }

  Then("a sufficiency judgement exists for each decision for every scientific question") { () =>
    longly("every question reports") {
      Option.when(questionIds.forall(q => lane(q).contains("\\\"status\\\":\\\"reported\\\"")))(())
    }
    questionIds.foreach { q =>
      val v = view(q)
      Configured.decisions.foreach(d =>
        assert(v.contains(s""""intendedDecision":"$d""""), s"$q has no judgement for '$d'")
      )
    }
  }

  Then("the investigation pauses at the stage with the defect recorded") { () =>
    val run = runOf(lipidId)
    assert(stages(run).count(_ == "sufficiency") >= 1 && run.contains("\"type\":\"failed\""), run)
  }

  Then("resuming the investigation runs the stage again") { () =>
    failing.clear()
    val decision = Configured.decisions.head
    val (status, reply) =
      post(s"/questions/$lipidId/sufficiency", s"""{"intendedDecision":${quote(decision)}}""")
    assertEquals(status, 202, reply)
    longly(s"the lipid question is judged for '$decision'") {
      Option.when(view(lipidId).contains(s""""intendedDecision":${quote(decision)}"""))(())
    }
  }

  Then("no record is duplicated") { () =>
    val v = view(lipidId)
    Configured.decisions.headOption.foreach(d =>
      assert(s""""intendedDecision":${quote(d)}""".r.findAllIn(v).size <= 1, v)
    )
    val ids = assessed(v)
    assertEquals(ids.distinct.size, ids.size, s"a duplicate assessment: $ids")
  }

  Then("every scientific question records a coverage gap") { () =>
    questionIds.foreach { q =>
      val gaps = get(s"/questions/$q/gaps")._2
      assert(gaps.contains("coverage"), s"$q has no coverage gap: $gaps")
    }
  }

  Then("the investigation proceeds to sufficiency") { () =>
    longly("every question is judged") {
      Option.when(questionIds.forall(q => stages(runOf(q)).contains("sufficiency")))(())
    }
  }

  Then("sufficiency abstains") { () =>
    longly("every question reports") {
      Option.when(questionIds.forall(q => lane(q).contains("\\\"status\\\":\\\"reported\\\"")))(())
    }
    questionIds.foreach { q =>
      val v = view(q)
      assert(v.contains("\"intendedDecision\":"), s"$q was not judged")
      assert(!v.contains("sufficient_for"), s"$q did not abstain")
    }
  }

  Then("the assessment runs") { () =>
    longly("a further assessment runs") {
      Option.when(stages(runOf(lipidId)).count(_ == "assess") > assessRunsBefore)(())
    }
  }

  Then("a further stage run is recorded") { () =>
    assert(stages(runOf(lipidId)).count(_ == "assess") > assessRunsBefore)
  }

  Then("no source is ingested by the investigation") { () =>
    assertEquals(sourceCount, sourcesBefore, "the investigation ingested a source")
  }
