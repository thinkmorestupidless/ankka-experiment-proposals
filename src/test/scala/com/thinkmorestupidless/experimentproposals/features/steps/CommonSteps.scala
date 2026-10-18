package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.experimentproposals.Main
import com.thinkmorestupidless.experimentproposals.application.views.ChunkRows
import com.thinkmorestupidless.experimentproposals.config.{Configured, Settings, Tier}
import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers
import com.thinkmorestupidless.experimentproposals.replay.ModelProviders
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * What every step needs: the service, started once for the suite in the tier the environment names;
 * an HTTP client with the token; ids derived from `scenarioId` so scenarios share nothing; waiting
 * on a changing value. No step here names a route: the helpers do.
 */
trait CommonSteps extends GherkinSuite:

  override val munitTimeout = 15.minutes

  protected val Token = sys.env.getOrElse("EDP_API_TOKEN", "feature-token")
  protected val env: Map[String, String] = sys.env ++ Map(
    "EDP_API_TOKEN"       -> Token,
    "EDP_TIER"            -> sys.env.getOrElse("EDP_TIER", "replay"),
    "EDP_EMBEDDING_MODEL" -> sys.env.getOrElse("EDP_EMBEDDING_MODEL", "hash")
  )
  protected val settings: Settings =
    Settings.fromEnv(env).fold(p => throw IllegalStateException(p), identity)
  protected val reviewers: Reviewers =
    Reviewers.fromInline("demo-reviewer:stand-in:stand-in, lab-reviewer:reviewer").toOption.get

  protected var testKit: AnkkaTestKit = null
  protected var baseUrl: String       = ""
  protected var server: HttpServer    = null
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    Main.configure(settings, reviewers, env)
    server = HttpServer.at("127.0.0.1", 0)(Main.endpoints(settings)*)
    testKit = AnkkaTestKit.start(
      Main.descriptors,
      Seq(
        ProjectionRuntime(),
        Main.agentRuntime(settings, ModelProviders.forTier(settings)),
        server
      )
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
    Configured.views = Some(testKit.service.viewClient)

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  /** Drops every entity from memory; the server rebinds, so the base URL is read again. */
  def restart(): Unit =
    testKit.restartService()
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not rebind"))}"
    Configured.views = Some(testKit.service.viewClient)

  /** How long a step may wait for a workflow: short against cassettes, long against the model. */
  protected def patience: FiniteDuration =
    if settings.tier == Tier.live then 10.minutes else 45.seconds

  protected def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(120))
    builder.header("Authorization", s"Bearer $Token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  protected def get(path: String): (Int, String) = send("GET", s"/api/v1$path")
  protected def post(path: String, json: String): (Int, String) =
    send("POST", s"/api/v1$path", Some(json))

  protected def eventually[A](what: String)(check: => Option[A]): A =
    testKit.eventually(what, patience)(check)

  /**
   * The top-level objects of a JSON array, by brace counting; enough for the assertions steps make.
   */
  protected def objectsOf(json: String): Vector[String] =
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

  /** A JSON string literal. */
  protected def quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** An id no other scenario uses, from the running scenario. */
  protected def scoped(prefix: String): String =
    s"$prefix-${scenarioId.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase.takeRight(40)}"

  /** Ingests a fixture through the API and waits for the workflow to end; answers the source id. */
  protected def ingest(file: String, extract: Boolean = false): String =
    val (status, body) =
      post("/sources:ingest", s"""{"adapter":"file","identifier":"$file","extract":$extract}""")
    assertEquals(status, 202, body)
    val workflowId =
      """"workflowId":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    val ended = eventually(s"ingestion $workflowId ends") {
      val (_, w) = get(s"/workflows/ingestion/$workflowId")
      Option.when(w.contains("\"isTerminal\":true"))(w)
    }
    val sourceId = """"sourceId":"([^"]+)"""".r
      .findFirstMatchIn(ended)
      .map(_.group(1))
      .getOrElse(fail(s"no source id in $ended"))
    // The chunk view lags the entity; retrieval reads the view, so a scenario waits for it once.
    if get(s"/sources/$sourceId")._2.contains("\"sections\":[{") then
      eventually(s"the chunks of $sourceId are projected") {
        val rows =
          testKit.service.viewClient
            .forView(ChunkRows)
            .ask(ChunkRows.bySource, "source" -> sourceId)
        Option.when(rows.nonEmpty)(rows.size)
      }: Unit
    sourceId
