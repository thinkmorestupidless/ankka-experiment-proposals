package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.adapters.{
  AdapterPermanent,
  CrossrefAdapter,
  HttpFetch
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

import scala.concurrent.duration.*

class CrossrefAdapterSuite extends munit.FunSuite:

  private val json  = "application/json"
  private val empty = """{"status":"ok","message":{"items":[]}}"""
  private val stub = LoopbackStub {
    case ("/works/10.3390/ani15243603", _) =>
      (200, json, LoopbackStub.resource("adapters/crossref/work-10.3390-ani15243603.json"))
    case ("/works/10.1016/S0140-6736(97)11096-0", _) =>
      (
        200,
        json,
        LoopbackStub.resource("adapters/crossref/work-10.1016-S0140-6736-97-11096-0.json")
      )
    case ("/works", q) if q.contains("filter=updates:10.1016/S0140-6736(97)11096-0") =>
      (200, json, LoopbackStub.resource("adapters/crossref/updates-wakefield.json"))
    case ("/works", q) if q.contains("filter=updates:") => (200, json, empty)
    case ("/works", q) if q.contains("query=goose") =>
      (200, json, LoopbackStub.resource("adapters/crossref/search.json"))
  }
  private val adapter =
    CrossrefAdapter(Some("test@example.org"), stub.base, Some(HttpFetch("test", 1.milli)))

  override def afterAll(): Unit = stub.stop()

  test("a work is fetched with its title, authors, venue, date and licence URL; no full text") {
    val r = adapter.fetch("doi:10.3390/ani15243603")
    assertEquals(r.identifier, "doi:10.3390/ani15243603")
    assert(r.title.startsWith("Oleic Acid Increases Lipid Accumulation"), r.title)
    assert(r.authors.nonEmpty, r.authors)
    assertEquals(r.venue, Some("Animals"))
    assertEquals(r.publishedAt, Some("2025-12-15"))
    assertEquals(r.licence, Some("https://creativecommons.org/licenses/by/4.0/"))
    assertEquals(r.status, SourceStatus.active)
    assertEquals(r.content, None)
    assertEquals(r.retention, RetentionRestriction.abstract_only)
    assert(stub.requests.forall(_.contains("mailto=test@example.org")), stub.requests)
  }

  test("a retraction recorded on the notice marks the article retracted") {
    val r = adapter.fetch("10.1016/S0140-6736(97)11096-0")
    assertEquals(r.status, SourceStatus.retracted)
    assert(r.title.startsWith("RETRACTED:"), r.title)
  }

  test("search lists DOIs; a non-DOI is permanent") {
    val refs = adapter.search("goose fatty liver hepatocytes lipid", Map.empty)
    assertEquals(refs.size, 3)
    assert(refs.forall(_.identifier.startsWith("doi:10.")), refs)
    intercept[AdapterPermanent](adapter.fetch("PMC1"))
  }
