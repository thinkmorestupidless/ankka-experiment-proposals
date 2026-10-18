package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.adapters.{
  AdapterPermanent,
  AdapterTransient,
  EuropePmcAdapter,
  HttpFetch
}
import com.thinkmorestupidless.experimentproposals.adapters.jats.JatsParser
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

import scala.concurrent.duration.*

class EuropePmcAdapterSuite extends munit.FunSuite:

  private val json = "application/json"
  private val stub = LoopbackStub {
    case ("/search", q) if q.contains("PMCID:PMC12729497") =>
      (200, json, LoopbackStub.resource("adapters/europepmc/search-PMC12729497.json"))
    case ("/search", q) if q.contains("PMCID:PMC12999981") =>
      (200, json, LoopbackStub.resource("adapters/europepmc/search-PMC12999981.json"))
    case ("/search", q) if q.contains("EXT_ID:39491705") =>
      (200, json, LoopbackStub.resource("adapters/europepmc/search-MED-39491705.json"))
    case ("/search", q) if q.contains("EXT_ID:1 ") =>
      (200, json, """{"hitCount":0,"resultList":{"result":[]}}""")
    case ("/search", q) if q.contains("resultType=lite") =>
      (200, json, LoopbackStub.resource("adapters/europepmc/search-PMC12729497.json"))
    case ("/search", q) if q.contains("PMCID:PMC5") => (503, "text/plain", "down")
    case ("/PMC12729497/fullTextXML", _) =>
      (200, "application/xml", LoopbackStub.resource("adapters/europepmc/PMC12729497.fullTextXML"))
    case ("/PMC12999981/fullTextXML", _) => (404, "text/plain", "no text")
  }
  private val adapter =
    EuropePmcAdapter(Some("test@example.org"), stub.base, Some(HttpFetch("test", 1.milli)))

  override def afterAll(): Unit = stub.stop()

  test("an open-access article is fetched with its metadata, licence and JATS, which parses") {
    val r = adapter.fetch("PMC12729497")
    assertEquals(r.identifier, "doi:10.3390/ani15243603")
    assertEquals(
      r.identifiers,
      Map("doi" -> "10.3390/ani15243603", "pmid" -> "41463887", "pmcid" -> "PMC12729497")
    )
    assert(
      r.title.startsWith("Oleic Acid Increases Lipid Accumulation in Duck Hepatocytes"),
      r.title
    )
    assertEquals(r.authors.take(2), Vector("Pan Z", "Li X"))
    assertEquals(r.publishedAt, Some("2025-12-15"))
    assertEquals(r.venue, Some("Animals : an open access journal from MDPI"))
    assertEquals(r.licence, Some("cc by"))
    assertEquals(r.status, SourceStatus.active)
    assertEquals(r.retention, RetentionRestriction.none)
    assertEquals(r.format, "jats")
    assert(r.abstractText.exists(_.contains("duck")), r.abstractText)
    val parsed = JatsParser.parse(r.content.get)
    assert(parsed.completeness != ParseCompleteness.failed, parsed.issues)
    assert(parsed.sections.exists(_.kind == SectionKind.methods), parsed.sections.map(_.title))
    // The DOCTYPE in real JATS names a DTD; nothing was fetched for it.
    assert(
      stub.requests.forall(r => r.startsWith("/search") || r.startsWith("/PMC")),
      stub.requests
    )
  }

  test(
    "a retracted publication carries its status from the publication types and the correction list"
  ) {
    val r = adapter.fetch("pmcid:PMC12999981")
    assertEquals(r.status, SourceStatus.retracted)
    assertEquals(r.retention, RetentionRestriction.abstract_only)
  }

  test("an article without full text keeps its metadata and abstract, abstract-only") {
    val r = adapter.fetch("pmid:39491705")
    assertEquals(r.identifier, "doi:10.1016/j.ijbiomac.2024.137222")
    assertEquals(r.content, None)
    assertEquals(r.retention, RetentionRestriction.abstract_only)
    assert(r.abstractText.exists(_.contains("goose fatty liver")), r.abstractText)
    assertEquals(r.identifiers.get("pmcid"), None)
  }

  test(
    "nothing found is permanent, a 5xx is transient, a bad identifier is permanent, search lists handles"
  ) {
    intercept[AdapterPermanent](adapter.fetch("1"))
    intercept[AdapterTransient](adapter.fetch("PMC5"))
    intercept[AdapterPermanent](adapter.fetch("not-an-id"))
    assertEquals(
      adapter.search("duck hepatocytes", Map.empty).map(_.identifier),
      Vector("PMC12729497")
    )
  }

  test("the status rules read Europe PMC's vocabulary") {
    import com.thinkmorestupidless.experimentproposals.adapters.EpmcCorrection
    assertEquals(
      EuropePmcAdapter.statusOf(Vector("Journal Article"), Vector.empty),
      SourceStatus.active
    )
    assertEquals(
      EuropePmcAdapter.statusOf(Vector.empty, Vector(EpmcCorrection(Some("Erratum in")))),
      SourceStatus.corrected
    )
    assertEquals(
      EuropePmcAdapter.statusOf(Vector.empty, Vector(EpmcCorrection(Some("Retraction in")))),
      SourceStatus.retracted
    )
    assertEquals(EuropePmcAdapter.queryFor("doi:10.1/x"), Right("DOI:\"10.1/x\""))
  }

  test("requests are paced to the adapter's rate") {
    val paced   = EuropePmcAdapter(None, stub.base, Some(HttpFetch("test", 150.millis)))
    val started = System.nanoTime()
    paced.search("a", Map.empty); paced.search("b", Map.empty); paced.search("c", Map.empty)
    assert((System.nanoTime() - started).nanos >= 300.millis)
  }
