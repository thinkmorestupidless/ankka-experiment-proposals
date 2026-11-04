package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.replay.Cassettes

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*

/**
 * The journey and its stream through the whole service (contracts/journey.md, SC-004): the first
 * event is the journey now, a reviewer's act reaches the stream within five seconds, and nothing
 * unchanged is sent twice.
 */
class JourneyStreamSuite extends ServiceFixture:

  private val cassettes = Cassettes(Path.of("evals/cassettes"))
  private val http      = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes("plan@v1")
    model.whenUserSays("# case: facets:")(cassettes.list("facets@v1").head.reply)
    model.whenUserSays("# case: roundtrip:")(
      cassettes
        .list("roundtrip@v1")
        .map(_.reply)
        .find(_.contains("\"unsupportedAdditions\": []"))
        .get
    )
    model.whenUserSays("# case: questions:")(cassettes.list("questions@v1").head.reply)

  /** An open stream: each `journey` event's data, in order, until it is closed. */
  final class JourneyEvents(path: String):
    val events = LinkedBlockingQueue[String]()
    private val request = JdkRequest
      .newBuilder(URI.create(baseUrl + path))
      .header("Authorization", s"Bearer $Token")
      .header("Accept", "text/event-stream")
      .build()
    private val response: CompletableFuture[JdkResponse[java.util.stream.Stream[String]]] =
      http.sendAsync(request, JdkResponse.BodyHandlers.ofLines())
    private val reader = Thread.ofVirtual().start { () =>
      try
        // An event is its lines up to a blank one, in any order (the service sends data first).
        var name = ""
        val data = StringBuilder()
        response.get().body().forEach { line =>
          if line.startsWith("event:") then name = line.drop(6).trim
          else if line.startsWith("data:") then data.append(line.drop(5).trim)
          else if line.isEmpty then
            if name == "journey" then events.put(data.toString)
            name = ""
            data.clear()
        }
      catch case _: Throwable => ()
    }
    def status: Int = response.get(10, TimeUnit.SECONDS).statusCode
    def next(within: FiniteDuration): Option[String] =
      Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))
    def close(): Unit =
      response.cancel(true)
      reader.interrupt()

  private def submit(): String =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get

  private def atFacetReview(id: String): Unit =
    eventually(s"$id pauses for facet review") {
      val (_, w) = get(api(s"/workflows/investigation/$id"))
      Option.when(w.contains("awaiting-facet-review"))(w)
    }: Unit

  private def confirmFacets(id: String): Unit =
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

  private def stateOf(journey: String, stage: String): Option[String] =
    s""""stage":"$stage","state":"([a-z]+)"""".r.findFirstMatchIn(journey).map(_.group(1))

  test("the journey of a request paused at facet review, by the plain route") {
    val id = submit()
    atFacetReview(id)
    val (status, body) = get(api(s"/requests/$id/journey"))
    assertEquals(status, 200, body)
    assertEquals(stateOf(body, "facets"), Some("reached"))
    assertEquals(stateOf(body, "facet-review"), Some("paused"))
    assertEquals(stateOf(body, "questions"), Some("unreached"))
    assert(body.contains("\"detail\":\"8 facets\""), body)
    assert(body.contains("\"stage\":\"extract-facets\""), body)
  }

  test("the first event is the journey now, and an unchanged journey is not sent again") {
    val id = submit()
    atFacetReview(id)
    val stream = JourneyEvents(api(s"/requests/$id/journey:watch"))
    try
      assertEquals(stream.status, 200)
      val first = stream.next(5.seconds).getOrElse(fail("no first journey event"))
      assertEquals(stateOf(first, "facet-review"), Some("paused"))
      assertEquals(stream.next(5.seconds), None, "an unchanged journey was sent again")
    finally stream.close()
  }

  test(
    "confirming the facets reaches the stream within five seconds, ten times out of ten (SC-004)"
  ) {
    val took = (1 to 10).map { _ =>
      val id = submit()
      atFacetReview(id)
      val stream = JourneyEvents(api(s"/requests/$id/journey:watch"))
      try
        stream.next(5.seconds).getOrElse(fail("no first journey event"))
        val started = System.nanoTime()
        confirmFacets(id)
        val moved = Iterator
          .continually(stream.next(5.seconds))
          .takeWhile(_.isDefined)
          .flatten
          .find(j => stateOf(j, "facet-review").contains("reached"))
          .getOrElse(fail(s"$id: facet review never reached on the stream"))
        val elapsed = (System.nanoTime() - started).nanos
        assert(
          Set("current", "paused", "reached").exists(stateOf(moved, "questions").contains),
          moved
        )
        assert(elapsed < 5.seconds, s"$id took $elapsed")
        elapsed.toMillis
      finally stream.close()
    }
    println(s"journey stream, confirm to event, ms: ${took.mkString(", ")}; max ${took.max}")
  }

  test("an unknown request is refused before the stream begins") {
    assertEquals(get(api("/requests/req-nonesuch/journey"))._1, 404)
    assertEquals(get(api("/requests/req-nonesuch/journey:watch"))._1, 404)
  }
