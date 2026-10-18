package com.thinkmorestupidless.experimentproposals.unit

import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A loopback HTTP server answering from recorded responses, so an adapter test never reaches the
 * network. A route sees the request's path and its decoded query and answers status, content type
 * and body; anything unmatched is a 404 naming the request, which the adapter under test must
 * handle.
 */
final class LoopbackStub(routes: PartialFunction[(String, String), (Int, String, String)]):
  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private val seen   = scala.collection.mutable.ArrayBuffer.empty[String]

  server.createContext(
    "/",
    exchange =>
      val path  = exchange.getRequestURI.getPath
      val query = Option(exchange.getRequestURI.getQuery).getOrElse("")
      seen.synchronized(seen += s"$path?$query")
      val (status, contentType, body) =
        routes.lift((path, query)).getOrElse((404, "text/plain", s"no stub for $path?$query"))
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", contentType)
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      val out = exchange.getResponseBody
      out.write(bytes)
      out.close()
  )
  server.start()

  val base: String             = s"http://127.0.0.1:${server.getAddress.getPort}"
  def requests: Vector[String] = seen.synchronized(seen.toVector)
  def stop(): Unit             = server.stop(0)

object LoopbackStub:
  /** A recorded response from `src/test/resources`. */
  def resource(path: String): String =
    val in = Option(getClass.getClassLoader.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"no test resource $path"))
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
