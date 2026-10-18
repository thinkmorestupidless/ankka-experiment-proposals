package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.Main
import com.thinkmorestupidless.experimentproposals.application.views.ChunkRows
import com.thinkmorestupidless.experimentproposals.config.{Configured, Settings}
import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers
import com.thinkmorestupidless.ankka.agent.{ModelProvider, TestModelProvider}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * What every integration suite starts: the whole service, as `Main` registers it, on a free port.
 */
trait ServiceFixture extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  val Token = "test-token"
  protected def env: Map[String, String] = Map(
    "EDP_API_TOKEN"       -> Token,
    "EDP_TIER"            -> "replay",
    "EDP_EMBEDDING_MODEL" -> "hash",
    "EDP_CASSETTES"       -> "evals/cassettes"
  )
  protected val settings: Settings = Settings.fromEnv(env).toOption.get
  protected val reviewers: Reviewers =
    Reviewers.fromInline("demo-reviewer:stand-in:stand-in, ada:reviewer").toOption.get
  protected val model: TestModelProvider     = TestModelProvider()
  protected def modelProvider: ModelProvider = model

  protected var testKit: AnkkaTestKit = null
  protected var baseUrl: String       = ""
  protected var server: HttpServer    = null
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    Main.configure(settings, reviewers, env)
    server = HttpServer.at("127.0.0.1", 0)(Main.endpoints(settings)*)
    testKit = AnkkaTestKit.start(
      Main.descriptors,
      Seq(ProjectionRuntime(), Main.agentRuntime(settings, modelProvider), server)
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    Configured.views = Some(testKit.service.viewClient)

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  /** Drops every entity from memory; the server rebinds, so the base URL is read again. */
  def restart(): Unit =
    testKit.restartService()
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not rebind"))}"
    Configured.views = Some(testKit.service.viewClient)

  /** A request with the token; `token = None` sends none. */
  def send(
      method: String,
      path: String,
      body: Option[String] = None,
      token: Option[String] = Some(Token)
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60))
    token.foreach(t => builder.header("Authorization", s"Bearer $t"))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  def get(path: String): (Int, String)                = send("GET", path)
  def post(path: String, json: String): (Int, String) = send("POST", path, Some(json))

  /**
   * Retries until `check` answers, within the bound; the value that changes, not a mere presence.
   */
  def eventually[A](what: String, within: FiniteDuration = 60.seconds)(check: => Option[A]): A =
    testKit.eventually(what, within)(check)

  def api(path: String): String = s"/api/v1$path"

  /**
   * Every authored cassette becomes a standing rule of the scripted model, keyed by its case line.
   */
  def scriptFromCassettes(promptVersions: String*): Unit =
    val store = com.thinkmorestupidless.experimentproposals.replay
      .Cassettes(java.nio.file.Path.of("evals/cassettes"))
    promptVersions.foreach { pv =>
      store.list(pv).foreach(c => model.whenUserSays(s"# case: ${c.`case`}\n")(c.reply))
    }

  /** The records of a source, once none is still proposed. */
  def recordsSettled(sourceId: String): String =
    eventually(s"the records of $sourceId leave proposed") {
      val (_, rows) = get(api(s"/sources/$sourceId/records"))
      Option.when(rows.contains("\"id\":") && !rows.contains("\"status\":\"proposed\""))(rows)
    }

  /** Starts an ingestion of a fixture through the API and waits for its workflow to end. */
  def ingestFixture(file: String, extract: Boolean = false): String =
    val (status, body) = post(
      api("/sources:ingest"),
      s"""{"adapter":"file","identifier":"$file","extract":$extract}"""
    )
    assertEquals(status, 202, body)
    val workflowId =
      """"workflowId":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    val ended = eventually(s"ingestion $workflowId ends") {
      val (_, w) = get(api(s"/workflows/ingestion/$workflowId"))
      Option.when(w.contains("\"isTerminal\":true"))(w)
    }
    val sourceId = """"sourceId":"([^"]+)"""".r
      .findFirstMatchIn(ended)
      .map(_.group(1))
      .getOrElse(fail(s"no source id in $ended"))
    chunksProjected(sourceId)
    sourceId

  /** The chunk view lags the entity; retrieval reads the view, so a test waits for it once. */
  def chunksProjected(sourceId: String): Unit =
    if get(api(s"/sources/$sourceId"))._2.contains("\"sections\":[{") then
      eventually(s"the chunks of $sourceId are projected") {
        val rows =
          testKit.service.viewClient
            .forView(ChunkRows)
            .ask(ChunkRows.bySource, "source" -> sourceId)
        Option.when(rows.nonEmpty)(rows.size)
      }: Unit

  /**
   * The top-level objects of a JSON array, by brace counting; enough for the assertions tests make.
   */
  def objectsOf(json: String): Vector[String] =
    val out      = Vector.newBuilder[String]
    var depth    = 0
    var start    = -1
    var inString = false
    var escaped  = false
    json.zipWithIndex.foreach { case (c, i) =>
      if inString then
        if escaped then escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then inString = false
      else if c == '"' then inString = true
      else if c == '{' then { if depth == 0 then start = i; depth += 1 }
      else if c == '}' then { depth -= 1; if depth == 0 then out += json.substring(start, i + 1) }
    }
    out.result()
