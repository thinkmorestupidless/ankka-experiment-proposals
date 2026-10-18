package com.thinkmorestupidless.experimentproposals.retrieval

import com.thinkmorestupidless.experimentproposals.application.views.ChunkRow

/** Brute-force cosine over the current model's chunk rows, top K (research.md R6). */
object Semantic:
  final case class Scored(row: ChunkRow, score: Double)

  def search(
      rows: Vector[ChunkRow],
      queryVector: Vector[Float],
      topK: Int,
      cutOff: Double
  ): Vector[Scored] =
    rows
      .map(r => Scored(r, Embedder.cosine(queryVector, r.embedding)))
      .filter(_.score >= cutOff)
      .sortBy(s => (-s.score, s.row.chunkId))
      .take(topK)
