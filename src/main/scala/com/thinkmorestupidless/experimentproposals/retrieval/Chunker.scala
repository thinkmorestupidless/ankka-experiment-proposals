package com.thinkmorestupidless.experimentproposals.retrieval

import com.thinkmorestupidless.experimentproposals.domain.sources.{Chunk, Section}

/**
 * Child chunks of about 400 words with about 50 words of overlap, cut at sentence boundaries and
 * never across sections; offsets index the section's canonical text (research.md R9).
 */
object Chunker:
  final case class Settings(targetWords: Int = 400, overlapWords: Int = 50, batchSize: Int = 200)

  final case class Sentence(start: Int, end: Int, words: Int)

  def sentences(text: String): Vector[Sentence] =
    val boundaries = """(?<=[.!?])\s+""".r
    val out        = Vector.newBuilder[Sentence]
    var start      = 0
    for m <- boundaries.findAllMatchIn(text) do
      val end = m.start
      if end > start then
        out += Sentence(start, end, text.substring(start, end).split("\\s+").count(_.nonEmpty))
      start = m.end
    if start < text.length then
      val tail = text.substring(start)
      if tail.trim.nonEmpty then
        out += Sentence(start, text.length, tail.split("\\s+").count(_.nonEmpty))
    out.result()

  /** Chunks of one section; `embed` gives each its vector. */
  def chunk(
      sourceId: String,
      version: Int,
      section: Section,
      embed: String => Vector[Float],
      settings: Settings = Settings()
  ): Vector[Chunk] =
    val ss = sentences(section.text)
    if ss.isEmpty then Vector.empty
    else
      val out     = Vector.newBuilder[Chunk]
      var i       = 0
      var ordinal = 0
      while i < ss.length do
        var j     = i
        var words = 0
        while j < ss.length && (words == 0 || words + ss(j).words <= settings.targetWords) do
          words += ss(j).words
          j += 1
        val start = ss(i).start
        val end   = ss(j - 1).end
        ordinal += 1
        val text = section.text.substring(start, end)
        out += Chunk(
          s"$sourceId:v$version:${section.id}:$ordinal",
          section.id,
          ordinal,
          start,
          end,
          text,
          words,
          embed(text)
        )
        if j >= ss.length then i = ss.length
        else
          // Back up whole sentences while the overlap stays within the budget.
          var k       = j
          var overlap = 0
          while k > i + 1 && overlap + ss(k - 1).words <= settings.overlapWords do
            overlap += ss(k - 1).words
            k -= 1
          i = k
      out.result()

  def chunkAll(
      sourceId: String,
      version: Int,
      sections: Vector[Section],
      embed: String => Vector[Float],
      settings: Settings = Settings()
  ): Vector[Chunk] =
    sections.flatMap(s => chunk(sourceId, version, s, embed, settings))

  def batches(chunks: Vector[Chunk], size: Int): Vector[Vector[Chunk]] =
    if chunks.isEmpty then Vector(Vector.empty) else chunks.grouped(size).toVector
