package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.application.views.ChunkRows

/** A fixture through the whole ingestion workflow, over HTTP, surviving a restart. */
class IngestionSuite extends ServiceFixture:

  test("health is open; the API refuses a missing or wrong token") {
    assertEquals(send("GET", "/healthz", token = None)._1, 200)
    assertEquals(send("GET", api("/reviewers"), token = None)._1, 401)
    assertEquals(send("GET", api("/reviewers"), token = Some("wrong"))._1, 401)
    val (status, body) = get(api("/reviewers"))
    assertEquals(status, 200)
    assert(body.contains("demo-reviewer") && body.contains("\"standIn\":true"), body)
  }

  test(
    "paper A is fetched, recorded, parsed, chunked and judged eligible; the same file again is no new version"
  ) {
    val sourceId = ingestFixture("paper-a.xml")
    assertEquals(sourceId, "src:file:paper-a")
    val (status, doc) = get(api(s"/sources/$sourceId"))
    assertEquals(status, 200, doc)
    assert(doc.contains("\"title\":\"Proliferation of primary duck hepatocytes"), doc)
    assert(doc.contains("\"parseVersion\":\"jats@v1\""), doc)
    assert(doc.contains("\"eligible\":true"), doc)
    assert(!doc.contains("<article"), "the retained text is not in the listing")
    val rows = eventually("chunk rows for paper A") {
      val found =
        testKit.service.viewClient.forView(ChunkRows).ask(ChunkRows.bySource, "source" -> sourceId)
      Option.when(found.nonEmpty)(found)
    }
    assert(rows.forall(_.embeddingModel == "hash"))
    val (sStatus, section) = get(api(s"/sources/$sourceId/sections/${rows.head.sectionId}"))
    assertEquals(sStatus, 200, section)
    assert(section.contains(rows.head.chunkId), section)

    val again = ingestFixture("paper-a.xml")
    assertEquals(again, sourceId)
    val (_, after) = get(api(s"/sources/$sourceId"))
    assertEquals("\"version\":1".r.findAllIn(after).size, 1, after)
    assert(!after.contains("\"version\":2"), after)
  }

  test("a span resolves against the stored section, and everything survives a restart") {
    val sourceId = ingestFixture("paper-c.xml")
    val rows = eventually("chunk rows for paper C") {
      val found =
        testKit.service.viewClient.forView(ChunkRows).ask(ChunkRows.bySource, "source" -> sourceId)
      Option.when(found.nonEmpty)(found)
    }
    val quote   = "The suspension culture method was unsuccessful"
    val results = rows.find(_.text.contains(quote)).getOrElse(fail("no results chunk"))
    val start   = results.startOffset + results.text.indexOf(quote)
    def resolve = get(
      api(
        s"/spans:resolve?source=$sourceId&version=1&section=${results.sectionId}&start=$start&end=${start + quote.length}&quotation=${java.net.URLEncoder.encode(quote, "UTF-8")}"
      )
    )
    assert(resolve._2.contains("\"type\":\"Resolved\""), resolve._2)

    restart()

    assert(resolve._2.contains("\"type\":\"Resolved\""), resolve._2)
    val (status, doc) = get(api(s"/sources/$sourceId"))
    assertEquals(status, 200)
    assert(doc.contains("\"eligible\":true") && !doc.contains("retracted"), doc)
    val (_, run) = get(api(s"/runs/run-ingest:file:paper-c.xml"))
    assert(run.contains("\"stage\":\"eligibility\""), run)
  }
