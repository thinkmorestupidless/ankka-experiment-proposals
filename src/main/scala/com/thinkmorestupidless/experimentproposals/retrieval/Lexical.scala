package com.thinkmorestupidless.experimentproposals.retrieval

import com.thinkmorestupidless.experimentproposals.application.views.{ChunkRow, ChunkRows}
import com.thinkmorestupidless.ankka.runtime.ViewClient

/** Postgres full-text search through the chunk view's declared query, best rank first (R6). */
object Lexical:
  def search(views: ViewClient, model: String, query: String, topK: Int): Vector[ChunkRow] =
    if query.isBlank then Vector.empty
    else views.forView(ChunkRows).ask(ChunkRows.lexical, topK, "model" -> model, "q" -> query)

  /** The same ranking over rows already in hand: a rerun against a recorded snapshot. */
  def rank(rows: Vector[ChunkRow], query: String, topK: Int): Vector[ChunkRow] =
    val terms = query.toLowerCase.split("[^a-z0-9]+").filter(t => t.length > 2).distinct
    rows
      .map { r =>
        val text  = r.text.toLowerCase
        val score = terms.count(text.contains)
        (r, score)
      }
      .filter(_._2 > 0)
      .sortBy((r, s) => (-s, r.chunkId))
      .take(topK)
      .map(_._1)
