package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.application.views.ChunkRows

import java.nio.file.Path

/**
 * US5 through the whole service: literature and internal records with their provenance, the
 * duplicate rule, the parse flag, statuses surfaced to eligibility, abstract-only and quarantine,
 * every read repeated after a restart.
 */
class ProvenanceSuite extends ServiceFixture:

  private def workflowEnds(workflowId: String): String =
    eventually(s"ingestion $workflowId ends") {
      val (_, w) = get(api(s"/workflows/ingestion/$workflowId"))
      Option.when(w.contains("\"isTerminal\":true"))(w)
    }

  test(
    "a chunk traces to its section, offsets and version; the same content again is no new version"
  ) {
    val sourceId = ingestFixture("paper-a.xml")
    val rows = eventually("chunk rows for paper A") {
      val found =
        testKit.service.viewClient.forView(ChunkRows).ask(ChunkRows.bySource, "source" -> sourceId)
      Option.when(found.nonEmpty)(found)
    }
    val row = rows.head
    assertEquals(row.sourceVersion, 1)
    val (_, section) = get(api(s"/sources/$sourceId/sections/${row.sectionId}?version=1"))
    assert(
      section.contains(s""""chunkId":"${row.chunkId}"""") && section.contains(
        s""""startOffset":${row.startOffset}"""
      ),
      section
    )
    val (_, doc) = get(api(s"/sources/$sourceId"))
    Seq(
      "\"identifiers\":{\"doi\":",
      "\"retrievedAt\":",
      "\"contentHash\":",
      "\"parseVersion\":\"jats@v1\"",
      "\"licence\":\"CC BY 4.0\"",
      "\"sourceUrl\":"
    )
      .foreach(f => assert(doc.contains(f), s"$f missing in $doc"))

    // The same file under another spelling of its path: a new workflow, the same identifier and hash.
    val absolute = Path.of("evals/fixtures/papers/paper-a.xml").toAbsolutePath.toString
    val (status, body) =
      post(api("/sources:ingest"), s"""{"adapter":"file","identifier":"$absolute"}""")
    assertEquals(status, 202, body)
    val workflowId = """"workflowId":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    val ended      = workflowEnds(workflowId)
    assert(ended.contains("\"status\":\"duplicate\"") || ended.contains("duplicateOf"), ended)
    assert(ended.contains(sourceId), ended)
    val (_, after) = get(api(s"/sources/$sourceId"))
    assertEquals("\"version\":1".r.findAllIn(after).size, 1, after)
    assert(!after.contains("\"version\":2"), after)
    val (_, one) = get(api(s"/sources/$sourceId?version=1"))
    assert(one.contains("\"version\":1"), one)
  }

  test(
    "the internal dataset: one source per file, measurements with their unit as written, validated and never converted"
  ) {
    val (status, body) =
      post(
        api("/sources:ingest-internal"),
        """{"manifestPath":"evals/fixtures/internal/manifest.json"}"""
      )
    assertEquals(status, 202, body)
    val ids = """"workflowId":"([^"]+)"""".r.findAllMatchIn(body).map(_.group(1)).toVector
    assertEquals(ids.size, 1, body)
    ids.foreach(workflowEnds)
    val sourceId = "src:internal:lab-lims:measurements"
    val (_, doc) = get(api(s"/sources/$sourceId"))
    assert(doc.contains("\"kind\":{\"type\":\"internal_measurement\"}"), doc)
    assert(doc.contains("\"responsibleResearcher\":\"demo-researcher\""), doc)
    assert(
      doc.contains("\"unit_invalid\"") && doc.contains("\"completeness\":{\"type\":\"partial\"}"),
      doc
    )
    assert(doc.contains("\"eligible\":true"), doc)
    def inRange = get(api("/measurements?kind=triglyceride_content&min=20&max=90"))._2
    val rows    = eventually("measurement rows")(Option.when(inRange.contains("S-001"))(inRange))
    assert(
      rows.contains("\"value\":21.5") && rows.contains("\"unit\":\"ug/mg\"") && rows.contains(
        "\"unitValid\":true"
      ),
      rows
    )
    assert(rows.contains("S-002") && !rows.contains("S-005") && !rows.contains("cell_count"), rows)
    val (_, invalid) = get(api("/measurements?invalid=true"))
    assert(
      invalid.contains("S-005") && invalid.contains("\"unit\":\"ug per mg\"") && invalid.contains(
        "\"value\":93.0"
      ),
      invalid
    )
    assert(!invalid.contains("S-001"), invalid)
    restart()
    assert(inRange.contains("S-001"), inRange)
    assert(get(api(s"/sources/$sourceId"))._2.contains("\"unit_invalid\""))
    assert(get(api("/measurements"))._2.contains("S-004"))
  }

  test("the parse flag, the statuses in eligibility, abstract-only and quarantine") {
    val d = get(api(s"/sources/${ingestFixture("paper-d.xml")}"))._2
    assert(
      d.contains("table_unresolved") && d.contains("Table 2") && d.contains(
        "\"completeness\":{\"type\":\"partial\"}"
      ),
      d
    )
    assert(d.contains("\"eligible\":true"), d)

    val r = get(api(s"/sources/${ingestFixture("retracted.xml")}"))._2
    assert(
      r.contains("\"status\":{\"type\":\"retracted\"}") && r.contains("\"eligible\":false") && r
        .contains("eligibility.retracted"),
      r
    )

    val c = get(api(s"/sources/${ingestFixture("corrected.txt")}"))._2
    assert(
      c.contains("\"status\":{\"type\":\"corrected\"}") && c.contains("\"eligible\":true") && c
        .contains("eligibility.corrected"),
      c
    )

    val s = get(api(s"/sources/${ingestFixture("superseded.txt")}"))._2
    assert(
      s.contains("\"status\":{\"type\":\"superseded\"}") && s.contains("\"eligible\":false") && s
        .contains("eligibility.superseded"),
      s
    )

    val a = get(api(s"/sources/${ingestFixture("abstract-only.txt")}"))._2
    assert(
      a.contains("\"retention\":{\"type\":\"abstract_only\"}") && a.contains(
        "\"abstractText\":\"A review of lipid metabolism"
      ) && a.contains("abstract_only"),
      a
    )
    assert(a.contains("\"title\":\"Lipid metabolism in waterfowl liver"), a)

    val q = get(api(s"/sources/${ingestFixture("corrupt.xml")}"))._2
    assert(
      q.contains("\"quarantined\":true") && q.contains(
        "\"quarantineReason\":\"the content could not be parsed"
      ),
      q
    )
    assert(
      q.contains("\"contentHash\":") && q.contains("\"corrupt\"") && q.contains(
        "\"eligible\":false"
      ),
      q
    )
    val (_, run) = get(api("/runs/run-ingest:file:corrupt.xml"))
    assert(run.contains("\"stage\":\"parse\""), run)

    restart()
    assert(get(api("/sources/src:file:corrupt"))._2.contains("\"quarantined\":true"))
    assert(get(api("/sources/src:file:retracted"))._2.contains("eligibility.retracted"))
  }

  test(
    "a status change re-assesses eligibility; an unknown actor is refused before anything persists"
  ) {
    val sourceId = ingestFixture("paper-c.xml")
    assert(get(api(s"/sources/$sourceId"))._2.contains("\"eligible\":true"))
    val (bad, _) = post(
      api(s"/sources/$sourceId/status"),
      """{"status":"retracted","reason":"x","by":"nobody"}"""
    )
    assert(bad >= 400 && bad < 500, bad.toString)
    val (unknown, _) =
      post(api(s"/sources/$sourceId/status"), """{"status":"gone","reason":"x","by":"operator"}""")
    assertEquals(unknown, 400)
    val (ok, doc) = post(
      api(s"/sources/$sourceId/status"),
      """{"status":"retracted","reason":"publisher notice","by":"operator"}"""
    )
    assertEquals(ok, 200, doc)
    assert(
      doc.contains("\"status\":{\"type\":\"retracted\"}") && doc.contains(
        "\"eligible\":false"
      ) && doc.contains("status-change:operator"),
      doc
    )
    eventually("the listing shows the new status") {
      val (_, listed) = get(api("/sources?status=retracted"))
      Option.when(listed.contains(sourceId))(listed)
    }
  }
