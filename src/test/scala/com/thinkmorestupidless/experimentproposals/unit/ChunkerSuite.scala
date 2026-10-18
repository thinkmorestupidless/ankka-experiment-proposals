package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.Section
import com.thinkmorestupidless.experimentproposals.retrieval.{Chunker, Embedder, HashEmbedder}

class ChunkerSuite extends munit.FunSuite:

  private val text = (1 to 120)
    .map(i =>
      s"Sentence number $i reports that cultured cells behaved as expected under condition $i."
    )
    .mkString(" ")
  private val section = Section("sec-1", None, 1, SectionKind.results, "Results", text)

  test(
    "chunks stay within the word budget, overlap at sentence boundaries, and their offsets slice the text"
  ) {
    val chunks = Chunker.chunk(
      "src:x",
      1,
      section,
      HashEmbedder.embed,
      Chunker.Settings(targetWords = 100, overlapWords = 20)
    )
    assert(chunks.size >= 10, chunks.size)
    chunks.foreach { c =>
      assertEquals(section.text.substring(c.startOffset, c.endOffset), c.text)
      assert(c.tokenCount <= 100, c.tokenCount)
      assert(c.text.endsWith("."), c.text.takeRight(20))
      assertEquals(c.embedding.length, 384)
    }
    assert(
      chunks(1).startOffset < chunks(0).endOffset,
      "the second chunk starts inside the first: overlap"
    )
    assertEquals(chunks.map(_.ordinal), (1 to chunks.size).toVector)
    assertEquals(chunks.head.chunkId, "src:x:v1:sec-1:1")
    assertEquals(chunks.last.endOffset, text.length)
  }

  test("no chunk crosses a section, batches are bounded, and the hash embedder is deterministic") {
    val sections = Vector(section, section.copy(id = "sec-2", text = "Short. Two."))
    val chunks = Chunker.chunkAll(
      "src:x",
      1,
      sections,
      HashEmbedder.embed,
      Chunker.Settings(targetWords = 100)
    )
    assert(
      chunks.exists(_.sectionId == "sec-2") && chunks.forall(c =>
        c.sectionId == "sec-1" || c.sectionId == "sec-2"
      )
    )
    assert(Chunker.batches(chunks, 4).forall(_.size <= 4))
    assertEquals(HashEmbedder.embed("lipid accumulation"), HashEmbedder.embed("lipid accumulation"))
    assert(
      Embedder.cosine(
        HashEmbedder.embed("lipid accumulation in duck"),
        HashEmbedder.embed("duck lipid accumulation")
      ) >
        Embedder.cosine(
          HashEmbedder.embed("lipid accumulation in duck"),
          HashEmbedder.embed("quarterly tax return")
        )
    )
  }
