package com.thinkmorestupidless.experimentproposals.adapters

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import javax.xml.parsers.SAXParserFactory
import scala.concurrent.duration.*
import scala.xml.{Elem, XML}

/** What a GET came back with, once the failures that matter have become adapter exceptions. */
enum Fetched:
  case Ok(body: String)
  case NotFound

/**
 * One paced HTTP client per adapter (research.md R7): at most one request per `minInterval`, a
 * `User-Agent` that names the service and its contact, and the status codes mapped to the two
 * adapter failures. A 404 is not a failure here because an adapter decides what it means.
 */
final class HttpFetch(
    userAgent: String,
    minInterval: FiniteDuration,
    timeout: FiniteDuration = 30.seconds
):

  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  private var nextAllowedAt = 0L

  /** Serialises callers and sleeps whoever would exceed the rate. */
  private def pace(): Unit = synchronized {
    val now  = System.nanoTime()
    val wait = nextAllowedAt - now
    if wait > 0 then Thread.sleep(wait / 1000000, (wait % 1000000).toInt)
    nextAllowedAt = math.max(now, nextAllowedAt) + minInterval.toNanos
  }

  def get(uri: URI, accept: String = "application/json"): Fetched =
    pace()
    val request = HttpRequest
      .newBuilder(uri)
      .timeout(Duration.ofMillis(timeout.toMillis))
      .header("User-Agent", userAgent)
      .header("Accept", accept)
      .GET()
      .build()
    val response =
      try client.send(request, HttpResponse.BodyHandlers.ofString())
      catch
        case e: java.net.http.HttpTimeoutException =>
          throw AdapterTransient(s"timed out fetching $uri", Some(e))
        case e: java.io.IOException =>
          throw AdapterTransient(s"could not reach $uri: ${e.getMessage}", Some(e))
    response.statusCode() match
      case 200           => Fetched.Ok(response.body())
      case 404           => Fetched.NotFound
      case 429           => throw AdapterTransient(s"rate limited by $uri")
      case s if s >= 500 => throw AdapterTransient(s"$s from $uri")
      case s             => throw AdapterPermanent(s"$s from $uri: ${response.body().take(200)}")

object HttpFetch:
  def agent(contact: Option[String]): String =
    "ankka-experiment-proposals/0.1" + contact.fold("")(c => s" (mailto:$c)")

  /** `base/path?k=v&…`, every value percent-encoded (spaces as `%20`, never `+`). */
  def uri(base: String, path: String, params: Seq[(String, String)] = Nil): URI =
    val query =
      if params.isEmpty then ""
      else params.map { case (k, v) => s"${encode(k)}=${encode(v)}" }.mkString("?", "&", "")
    URI.create(s"${base.stripSuffix("/")}/${path.stripPrefix("/")}$query")

  def encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

  /** Tags out of an HTML or JATS fragment, whitespace collapsed: abstracts come decorated. */
  def stripTags(s: String): String =
    s.replaceAll("<[^>]+>", " ")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")
      .replaceAll("\\s+", " ")
      .trim

/** XML parsed without fetching the DTD a DOCTYPE names: JATS and PubMed records both carry one. */
object SafeXml:
  private def parser =
    val factory = SAXParserFactory.newInstance()
    factory.setNamespaceAware(false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.newSAXParser()

  def load(text: String): Elem = XML.withSAXParser(parser).loadString(text)
