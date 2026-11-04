package com.thinkmorestupidless.experimentproposals.integration

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration

/** Puts a question to a conversation and reads the whole event stream of its answer. */
trait ConversationClient extends ServiceFixture:
  private val streams = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  final case class Event(name: String, data: String)

  def open(id: String, body: String = "{}"): (Int, String) = post(api(s"/conversations/$id"), body)

  /** The status, and each event's name and data in order (a refusal has none). */
  def turn(id: String, question: String): (Int, Vector[Event], String) =
    val request = JdkRequest
      .newBuilder(URI.create(baseUrl + api(s"/conversations/$id/turns")))
      .timeout(Duration.ofSeconds(120))
      .header("Authorization", s"Bearer $Token")
      .header("Content-Type", "application/json")
      .header("Accept", "text/event-stream")
      .POST(JdkRequest.BodyPublishers.ofString(s"""{"question":${quoteJson(question)}}"""))
      .build()
    val response = streams.send(request, JdkResponse.BodyHandlers.ofString())
    val events   = Vector.newBuilder[Event]
    var name     = ""
    val data     = StringBuilder()
    (response.body + "\n\n").linesIterator.foreach { line =>
      if line.startsWith("event:") then name = line.drop(6).trim
      else if line.startsWith("data:") then data.append(line.drop(5).trim)
      else if line.isEmpty && (name.nonEmpty || data.nonEmpty) then
        events += Event(if name.isEmpty then "token" else name, data.toString)
        name = ""
        data.clear()
    }
    (response.statusCode, events.result(), response.body)

  def quoteJson(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
