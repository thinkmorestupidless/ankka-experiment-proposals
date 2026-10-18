package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.views.ChunkRows
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.ankka.testkit.KeyedViewTestKit

import java.time.Instant

class ChunkRowsSuite extends munit.FunSuite:

  test("a Chunked event writes one row per chunk under the chunk id; other events write nothing") {
    val kit = KeyedViewTestKit(ChunkRows) // also checks the declared queries parse
    kit.change(
      ChunkRows.sources,
      "src:file:paper-a",
      SourceEvent.ContentFetched(1, "h", Instant.now(), None, RetentionRestriction.none, None)
    )
    assertEquals(kit.rows.size, 0)
    val chunks = Vector(
      Chunk("src:file:paper-a:v1:sec-3:1", "sec-3", 1, 0, 10, "Cells grew.", 2, Vector(0.1f, 0.2f)),
      Chunk(
        "src:file:paper-a:v1:sec-3:2",
        "sec-3",
        2,
        11,
        25,
        "Lipid rose too.",
        3,
        Vector(0.3f, 0.4f)
      )
    )
    kit.change(
      ChunkRows.sources,
      "src:file:paper-a",
      SourceEvent.Chunked(1, "hash", "1", chunks, 1, 1)
    )
    assertEquals(kit.rows.keySet, chunks.map(_.chunkId).toSet)
    val row = kit.row("src:file:paper-a:v1:sec-3:2").get
    assertEquals(row.sourceId, "src:file:paper-a")
    assertEquals(row.text, "Lipid rose too.")
    assertEquals(row.embeddingModel, "hash")
    assertEquals(row.embedding, Vector(0.3f, 0.4f))
  }
