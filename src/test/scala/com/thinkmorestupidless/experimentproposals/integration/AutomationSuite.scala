package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.replay.Cassettes
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.ankka.agent.{
  ChatMessage,
  MessageContent,
  ModelProvider,
  ModelRequest,
  ModelResponse
}

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The investigation runs itself between the reviewer's pauses (feature 002, US2), through the whole
 * service with the cassettes as the scripted model's rules.
 */
trait AutomationFixture extends ServiceFixture:

  protected val store = Cassettes(Path.of("evals/cassettes"))
  protected val stagesScripted =
    Vector(
      "facets@v1",
      "roundtrip@v1",
      "questions@v1",
      "plan@v1",
      "extract@v1",
      "verify@v1",
      "reextract@v1",
      "assess@v1",
      "sufficiency@v1",
      "candidates@v1",
      "design@v1"
    )

  protected def investigation(id: String): String = get(api(s"/workflows/investigation/$id"))._2

  /** The case line of every model call made, and which of them no cassette answers. */
  protected def misses: Vector[String] =
    val known = stagesScripted.flatMap(pv => store.list(pv).map(_.`case`)).toSet
    model.requests.toVector
      .flatMap(_.messages.collect { case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString("\n")
      })
      .flatMap(t => """# case: (\S+)""".r.findFirstMatchIn(t).map(_.group(1)))
      .distinct
      .filterNot(known.contains)

  protected def submitAndConfirm(): String =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review") {
      Option.when(investigation(id).contains("awaiting-facet-review"))(id)
    }
    Seq("f1", "f2", "f3", "f8").foreach(f =>
      assertEquals(
        post(
          api(s"/facets/$id:$f/review"),
          """{"action":"confirm","reviewer":"ada","rationale":"ok"}"""
        )._1,
        201
      )
    )
    assertEquals(post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")._1, 200)
    id

  protected def stageNames(runJson: String): Vector[String] =
    """"stage":"([a-z-]+)"""".r.findAllMatchIn(runJson).map(_.group(1)).toVector

  /**
   * Model calls whose case line contains a key wait for its promise. Each key holds one call only:
   * the call after it, a retry or a rerun after a restart, goes straight to the script.
   */
  private val held    = java.util.concurrent.ConcurrentHashMap[String, Promise[Unit]]()
  private val holdAll = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  private val arrived = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  protected def hold(key: String): Promise[Unit] =
    val p = Promise[Unit](); held.put(key, p); p

  /** Holds every call whose case line contains the key, retries included, until released. */
  protected def holdEvery(key: String): Promise[Unit] =
    holdAll.add(key); val p = hold(key)
    p.future.onComplete(_ => holdAll.remove(key))(using ExecutionContext.global)
    p

  protected def hasArrived(key: String): Boolean = arrived.contains(key)

  /** From now on every call whose case line contains the key fails, as an outage would. */
  private val failing = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  protected def failEvery(key: String): Unit =
    failing.add(key)
    Option(held.remove(key)).foreach(_.tryFailure(RuntimeException(s"the model is down: $key")))

  private def caseLine(request: ModelRequest): String =
    request.messages
      .collect { case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString("\n")
      }
      .lastOption
      .getOrElse("")

  override protected def modelProvider: ModelProvider = new ModelProvider:
    def name: String      = model.name
    def modelName: String = model.modelName
    def complete(request: ModelRequest): Future[ModelResponse] =
      val text = caseLine(request)
      if failing.asScala.exists(text.contains) then
        Future.failed(RuntimeException("the model is down"))
      else
        held.asScala.collectFirst { case (k, p) if text.contains(k) => k -> p } match
          case Some((k, p)) =>
            if !holdAll.contains(k) then held.remove(k)
            arrived.add(k)
            p.future.flatMap(_ => model.complete(request))(using ExecutionContext.global)
          case None => model.complete(request)

  /**
   * On failure, what the lipid question's workflow last said and which cases no cassette answers.
   */
  protected def diagnosed[A](requestId: => String)(body: => A): A =
    try body
    catch
      case e: Throwable =>
        val q = s"$requestId:q4"
        println(s"DIAG workflow: ${questionWorkflow(q).replaceAll("executionIds[^]]*]", "")}")
        println(
          s"DIAG fd1: ${"\"(status|attempt|verifiedAt|failure|defect)\":[^,]*".r.findAllIn(get(api("/findings/src:file:paper-a:v1:fd1"))._2).mkString(" ")}"
        )
        println(
          s"DIAG verify: ${get(api("/workflows/verification/src:file:paper-a:v1:fd1:a1"))._2.take(800)}"
        )
        println(
          s"DIAG verify run: ${get(api("/runs/run-src:file:paper-a:v1:fd1:a1"))._2.take(1500)}"
        )
        println(
          s"DIAG run: ${get(api(s"/runs/run-$q"))._2.replaceAll("\"(inputs|outputs|checks)\":\\[[^]]*]", "").take(3000)}"
        )
        println(s"DIAG misses: ${misses.mkString(", ")}")
        throw e

  protected val LipidQuestion =
    "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"

  /**
   * The request confirmed while the lipid question's search plan is held, the reference sources
   * ingested with their verification unsettled, then the plan released: the question meets the
   * records while they are still being verified.
   */
  /**
   * The reference sources ingested with every verification call of Paper A's records held, so they
   * are still being verified when the lipid question resolves; then the request confirmed.
   */
  protected val PaperAVerify =
    Vector("fd1", "cl1").map(k => s"# case: verify:src:file:paper-a:v1:$k")

  /**
   * Paper A's ingestion waits for its records' verification, which is held, so the request is
   * confirmed once both verification calls have arrived rather than when the ingestion ends.
   */
  protected def confirmWhilePaperAIsVerified(): (String, Vector[Promise[Unit]]) =
    val gates = PaperAVerify.map(holdEvery)
    Seq("paper-b.xml", "paper-c.xml").foreach(f => recordsSettled(ingestFixture(f, extract = true)))
    val (status, body) = post(
      api("/sources:ingest"),
      """{"adapter":"file","identifier":"paper-a.xml","extract":true}"""
    )
    assertEquals(status, 202, body)
    eventually("Paper A's verification is asked for and held", within = 120.seconds)(
      Option.when(PaperAVerify.forall(hasArrived))(())
    )
    (submitAndConfirm(), gates)

  protected def questionOf(id: String): String = get(api(s"/questions/$id"))._2
  protected def questionWorkflow(id: String): String =
    get(api(s"/workflows/question-investigation/$id"))._2

  protected def ingestReference(settled: Boolean = true): Unit =
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach { f =>
      val id = ingestFixture(f, extract = true)
      if settled then recordsSettled(id)
    }

  protected val BC =
    Vector("b", "c").flatMap(p => Vector(s"src:file:paper-$p:v1:fd1", s"src:file:paper-$p:v1:cl1"))

  protected def idsJson(ids: Seq[String]): String =
    ids.map(r => "\"" + r + "\"").mkString("{\"recordIds\":[", ",", "]}")

  protected val Six = Vector("a", "b", "c").flatMap(p =>
    Vector(s"src:file:paper-$p:v1:fd1", s"src:file:paper-$p:v1:cl1")
  )

class AutomationSuite extends AutomationFixture:

  private var lastRequest: String = null

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes(stagesScripted*)

  test(
    "confirming the facets carries the investigation to the choice of approach, with no further call"
  ) {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    val id = submitAndConfirm()
    lastRequest = id
    val reached =
      try
        eventually(s"$id reaches the choice of approach", within = 240.seconds) {
          val w = investigation(id)
          Option.when(w.contains("awaiting-decision") || w.contains("\"status\":\"failed\""))(w)
        }
      finally println(s"cassette misses on the automatic path: ${misses.mkString(", ")}")
    assert(reached.contains("awaiting-decision"), reached)
    val spine = stageNames(get(api(s"/runs/run-$id"))._2)
    Seq("extract-facets", "round-trip", "derive-questions", "build-packages", "candidates").foreach(
      s => assert(spine.contains(s), s"$s missing from $spine")
    )
    val (_, run) = get(api(s"/runs/run-$id"))
    assert(run.contains("\"actor\":\"system:investigation\""), run)
    val lipid = stageNames(get(api(s"/runs/run-$id:q4"))._2)
    Seq("plan", "execute", "eligibility", "verification", "assess", "sufficiency").foreach(s =>
      assert(lipid.contains(s), s"$s missing from the lipid question's run: $lipid")
    )
    val journey = get(api(s"/requests/$id/journey"))._2
    assert(journey.contains("\"stage\":\"decision\",\"state\":\"paused\""), journey)
    // FR-010: the investigation started no ingestion; only the three this test asked for exist.
    assertEquals("\"id\":\"src:".r.findAllIn(get(api("/sources"))._2).size, 3)
  }

  test("a manual assessment after the automatic one runs and records a further stage run") {
    val id     = Option(lastRequest).getOrElse(fail("the first test did not run"))
    val before = stageNames(get(api(s"/runs/run-$id:q4"))._2).count(_ == "assess")
    val three  = Six.filter(_.endsWith("fd1"))
    assertEquals(
      post(api(s"/questions/$id:q4/assess"), idsJson(three))._1,
      202
    )
    eventually("a further assess stage run") {
      val n = stageNames(get(api(s"/runs/run-$id:q4"))._2).count(_ == "assess")
      Option.when(n > before)(n)
    }
  }

  test("a manual packaging after the automatic one adds no duplicate package") {
    val id             = Option(lastRequest).getOrElse(fail("the first test did not run"))
    val (status, body) = post(api(s"/requests/$id/packages"), """{"reviewer":"ada"}""")
    assert(Set(202, 409).contains(status), s"$status $body")
    Thread.sleep(3000)
    assertEquals(get(api(s"/packages/$id:q4:pk1"))._1, 200)
    // A further packaging is a new package (pk2), as in feature 001; a duplicate would be the
    // automatic packaging firing again, which would leave a third.
    assertEquals(get(api(s"/packages/$id:q4:pk2"))._1, 200)
    assertEquals(get(api(s"/packages/$id:q4:pk3"))._1, 404)
  }

/** Assessment waits for the retrieved records' verification to settle (feature 002, US2). */
class AutomationVerificationWaitSuite extends AutomationFixture:
  override def beforeAll(): Unit =
    super.beforeAll(); scriptFromCassettes(stagesScripted*)

  test(
    "the question waits for a retrieved source's verification, then assesses its verified records"
  ) {
    val (id, gates) = confirmWhilePaperAIsVerified()
    diagnosed(id) {
      eventually("the lipid question waits for verification", within = 120.seconds) {
        val w = questionWorkflow(s"$id:q4"); Option.when(w.contains("awaiting-verification"))(w)
      }
      assert(
        !stageNames(get(api(s"/runs/run-$id:q4"))._2).contains("assess"),
        "assessed before verification settled"
      )
      // Paper A's verifier then fails, so its records settle as requiring review; the question
      // waits for that and assesses only the verified records of B and C.
      PaperAVerify.foreach(failEvery)
      val q = eventually("the lipid question reports", within = 180.seconds) {
        val q = questionOf(s"$id:q4")
        Option.when(q.contains("\"status\":\"reported\""))(q)
      }
      gates.foreach(_.trySuccess(()))
      val assessed = stageNames(get(api(s"/runs/run-$id:q4"))._2)
      assert(assessed.indexOf("verification") < assessed.indexOf("assess"), assessed.toString)
      BC.foreach(r => assert(q.contains(s""""id":"$r""""), s"$r not assessed: $q"))
      assert(
        !q.contains("\"id\":\"src:file:paper-a:v1:fd1\""),
        s"an unverified record was assessed: $q"
      )
    }
  }

/** The unsettled bound: verification that does not settle fails the stage (feature 002, US2). */
class AutomationUnsettledSuite extends AutomationFixture:
  private var bound = Configured.verificationBound
  override def beforeAll(): Unit =
    super.beforeAll(); scriptFromCassettes(stagesScripted*)
    bound = Configured.verificationBound
    Configured.verificationBound = 2.seconds
  override def afterAll(): Unit =
    Configured.verificationBound = bound; super.afterAll()

  test(
    "verification unsettled within the bound fails the stage; a manual assessment resumes without duplicates"
  ) {
    val (id, gates) = confirmWhilePaperAIsVerified()
    diagnosed(id) {
      val failed =
        eventually("the lipid question's verification stage fails", within = 180.seconds) {
          val w = questionWorkflow(s"$id:q4")
          Option.when(w.contains("verification_unsettled") && w.contains("\"status\":\"Paused\""))(
            w
          )
        }
      val journey = get(api(s"/requests/$id/journey"))._2
      assert(journey.contains("\"stage\":\"verification\",\"state\":\"failed\""), journey)
      assert(!failed.contains("overran its step"), failed)
      val run = get(api(s"/runs/run-$id:q4"))._2
      assert(run.contains("verification_unsettled"), s"the stage run records no defect: $run")
      // The manual assessment resumes the lane over the verified records, once, with no duplicate.
      assertEquals(post(api(s"/questions/$id:q4/assess"), idsJson(BC))._1, 202)
      val q = eventually("the verified records are assessed", within = 120.seconds) {
        val q = questionOf(s"$id:q4"); Option.when(BC.forall(r => q.contains(s""""id":"$r"""")))(q)
      }
      gates.foreach(_.trySuccess(()))
      val ids = """"id":"([^"]+:as\d+)"""".r.findAllMatchIn(q).map(_.group(1)).toVector
      assertEquals(ids.distinct.size, ids.size, s"a duplicate assessment: $ids")
    }
  }

/** An empty corpus does not stop the investigation (feature 002, US2). */
class AutomationEmptyCorpusSuite extends AutomationFixture:
  override def beforeAll(): Unit =
    super.beforeAll(); scriptFromCassettes(stagesScripted*)

  test(
    "with nothing ingested every question records a coverage gap, sufficiency abstains and packages are built"
  ) {
    val id = submitAndConfirm()
    diagnosed(id) {
      eventually("every question is reported", within = 180.seconds) {
        val all = (1 to 6).map(n => questionOf(s"$id:q$n"))
        Option.when(all.forall(_.contains("\"status\":\"reported\"")))(all)
      }
      (1 to 6).foreach { n =>
        val gaps = get(api(s"/questions/$id:q$n/gaps"))._2
        assert(gaps.contains("coverage"), s"q$n has no coverage gap: $gaps")
        val q = questionOf(s"$id:q$n")
        assert(!q.contains("sufficient_for"), s"q$n did not abstain: $q")
      }
      eventually("the packages are built", within = 60.seconds) {
        Option.when(get(api(s"/packages/$id:q4:pk1"))._1 == 200)(())
      }
      println(s"cassette misses with an empty corpus: ${misses.mkString(", ")}")
    }
  }

/** A restart between judge-all steps resumes at the next decision (feature 002, US2). */
class AutomationRestartSuite extends AutomationFixture:
  override def beforeAll(): Unit =
    super.beforeAll(); scriptFromCassettes(stagesScripted*)

  test(
    "a restart while the second decision is judged resumes there and judges the first only once"
  ) {
    val Vector(first, second) = Configured.decisions.take(2)
    val key =
      s"# case: sufficiency:${Ids.short("Has relevant lipid accumulation been demonstrated in appropriate avian cell models?")}:${Ids.short(second)}:"
    val gate = hold(key)
    ingestReference()
    val id = submitAndConfirm()
    eventually("the second decision is being judged", within = 180.seconds)(
      Option.when(hasArrived(key))(())
    )
    restart()
    gate.trySuccess(())
    val q = eventually("the lipid question reports after the restart", within = 180.seconds) {
      val q = questionOf(s"$id:q4"); Option.when(q.contains("\"status\":\"reported\""))(q)
    }
    Seq(first, second).foreach(d =>
      assert(q.contains(s""""intendedDecision":"$d""""), s"$d not judged: $q")
    )
    val firstJudgement = q.substring(q.indexOf(s""""intendedDecision":"$first""""))
    val upTo = firstJudgement.indexOf("\"intendedDecision\"", 1) match
      case -1 => firstJudgement
      case n  => firstJudgement.substring(0, n)
    assert(!upTo.contains("\"version\":2"), s"the first decision was judged again: $upTo")
  }
