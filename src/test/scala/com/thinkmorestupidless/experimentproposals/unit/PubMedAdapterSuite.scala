package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.adapters.{
  AdapterPermanent,
  HttpFetch,
  PubMedAdapter
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

import scala.concurrent.duration.*

class PubMedAdapterSuite extends munit.FunSuite:

  private val stub = LoopbackStub {
    case ("/esearch.fcgi", q) if q.contains("term=goose fatty liver") =>
      (200, "application/json", LoopbackStub.resource("adapters/pubmed/esearch.json"))
    case ("/esummary.fcgi", q) if q.contains("id=42685486,42341503,41751030") =>
      (200, "application/json", LoopbackStub.resource("adapters/pubmed/esummary.json"))
    case ("/efetch.fcgi", q) if q.contains("id=39491705") =>
      (200, "application/xml", LoopbackStub.resource("adapters/pubmed/efetch-39491705.xml"))
    case ("/efetch.fcgi", q) if q.contains("id=26098879") =>
      (200, "application/xml", LoopbackStub.resource("adapters/pubmed/efetch-26098879.xml"))
    case ("/efetch.fcgi", q) if q.contains("id=1&") || q.endsWith("id=1") =>
      (200, "application/xml", "<PubmedArticleSet></PubmedArticleSet>")
  }
  private val adapter =
    PubMedAdapter(Some("test@example.org"), None, stub.base, Some(HttpFetch("test", 1.milli)))

  override def afterAll(): Unit = stub.stop()

  test("a record is fetched from efetch XML: metadata, abstract, identifiers, no full text") {
    val r = adapter.fetch("39491705")
    assertEquals(r.identifier, "doi:10.1016/j.ijbiomac.2024.137222")
    assertEquals(r.identifiers.get("pmid"), Some("39491705"))
    assert(r.title.startsWith("Mitochondrial HKDC1"), r.title)
    assert(r.authors.nonEmpty && r.authors.head.matches("[A-Z][a-z]+ [A-Z]+"), r.authors)
    assertEquals(r.publishedAt, Some("2024-Dec"))
    assertEquals(r.venue, Some("International journal of biological macromolecules"))
    assertEquals(r.content, None)
    assertEquals(r.retention, RetentionRestriction.abstract_only)
    assert(r.abstractText.exists(_.contains("goose fatty liver")), r.abstractText)
    assertEquals(r.status, SourceStatus.active)
    assertEquals(r.sourceUrl, Some("https://pubmed.ncbi.nlm.nih.gov/39491705/"))
    // The DOCTYPE names NLM's DTD; it was not fetched.
    assert(stub.requests.forall(_.startsWith("/efetch.fcgi")), stub.requests)
  }

  test("a retracted publication is recognised from its publication type") {
    assertEquals(adapter.fetch("pmid:26098879").status, SourceStatus.retracted)
    assertEquals(PubMedAdapter.statusOf(Nil, Seq("ErratumIn")), SourceStatus.corrected)
    assertEquals(PubMedAdapter.statusOf(Nil, Seq("RetractionIn")), SourceStatus.retracted)
  }

  test(
    "search goes through esearch and esummary, carrying tool and email; nothing found is permanent"
  ) {
    val refs = adapter.search("goose fatty liver hepatocytes lipid", Map.empty)
    assertEquals(refs.map(_.identifier), Vector("pmid:42685486", "pmid:42341503", "pmid:41751030"))
    assert(refs.head.title.startsWith("FABP4"), refs.head.title)
    assert(
      stub.requests.exists(r =>
        r.contains("tool=ankka-experiment-proposals") && r.contains("email=test@example.org")
      ),
      stub.requests
    )
    intercept[AdapterPermanent](adapter.fetch("1"))
    intercept[AdapterPermanent](adapter.fetch("PMC1"))
  }
