package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.views.ChunkRow
import com.thinkmorestupidless.experimentproposals.retrieval.{
  Embedder,
  Fusion,
  HashEmbedder,
  Lexical,
  Semantic
}

class FusionSuite extends munit.FunSuite:

  test("RRF with k = 60 sums both ranks, keeps each rank, and deduplicates by chunk id") {
    val fused = Fusion.rrf(Seq("a", "b", "c"), Seq("b", "d"))
    assertEquals(fused.map(_.chunkId), Vector("b", "a", "d", "c"))
    val b = fused.head
    assertEquals((b.lexicalRank, b.semanticRank), (Some(2), Some(1)))
    assertEqualsDouble(b.score, 1.0 / 62 + 1.0 / 61, 1e-9)
    assertEquals(fused.find(_.chunkId == "a").get.semanticRank, None)
  }

  test("several lists of one kind: the best rank counts") {
    val fused = Fusion.rrfAll(Seq(Seq("a", "b"), Seq("b", "a")), Seq(Seq("c")))
    assertEquals(fused.map(_.chunkId).take(2).toSet, Set("a", "b"))
    assertEquals(fused.find(_.chunkId == "a").get.lexicalRank, Some(1))
    assertEquals(fused.find(_.chunkId == "b").get.lexicalRank, Some(1))
  }

  private def row(id: String, text: String) =
    ChunkRow(
      id,
      "src:x",
      1,
      "sec-1",
      1,
      0,
      text.length,
      text,
      text.split(" ").length,
      "hash",
      "1",
      HashEmbedder.embed(text)
    )

  test(
    "semantic retrieval ranks by cosine with the hash embedder and honours the cut-off and top K"
  ) {
    val rows = Vector(
      row("c1", "Oleate treated duck hepatocytes accumulated lipid droplets"),
      row("c2", "Near-infrared spectra of liver were calibrated against fat content"),
      row("c3", "Lipid accumulated in cultured duck hepatocytes after oleate exposure")
    )
    val q      = HashEmbedder.embed("lipid accumulation in duck hepatocytes")
    val scored = Semantic.search(rows, q, 2, 0.0)
    assertEquals(scored.size, 2)
    assert(
      Set("c1", "c3").contains(scored.head.row.chunkId),
      scored.map(s => (s.row.chunkId, s.score))
    )
    assert(scored.head.score > Embedder.cosine(q, rows(1).embedding))
    assertEquals(Semantic.search(rows, q, 10, 0.99).size, 0)
  }

  test("the in-memory lexical ranking for a rerun counts distinct query terms") {
    val rows =
      Vector(row("c1", "duck hepatocytes and lipid"), row("c2", "goose liver"), row("c3", "lipid"))
    assertEquals(
      Lexical.rank(rows, "lipid duck hepatocytes", 10).map(_.chunkId),
      Vector("c1", "c3")
    )
  }
