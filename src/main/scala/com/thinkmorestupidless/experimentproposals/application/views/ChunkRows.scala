package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.SourceEntity
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent.Chunked
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One retrieval chunk: its text for lexical search, its vector for semantic search (R6). */
final case class ChunkRow(
    chunkId: String,
    sourceId: String,
    sourceVersion: Int,
    sectionId: String,
    ordinal: Int,
    startOffset: Int,
    endOffset: Int,
    text: String,
    tokenCount: Int,
    embeddingModel: String,
    embeddingRevision: String,
    embedding: Vector[Float]
)

/**
 * A keyed view over the source's events: one `Chunked` event writes every chunk it carries, each
 * under its chunk id, so a source has as many rows as chunks rather than one.
 */
final class ChunkRowsView extends KeyedView[ChunkRow]:

  def onSource(event: SourceEvent, change: Change): Effect = event match
    case Chunked(version, model, revision, chunks, _, _) =>
      effects.updateRows(chunks.map { c =>
        c.chunkId -> ChunkRow(
          c.chunkId,
          change.subject,
          version,
          c.sectionId,
          c.ordinal,
          c.startOffset,
          c.endOffset,
          c.text,
          c.tokenCount,
          model,
          revision,
          c.embedding
        )
      })
    case _ => effects.ignore()

object ChunkRows
    extends KeyedView.Companion[ChunkRowsView, ChunkRow](
      ComponentId("chunk-rows"),
      Codecs.serializer[ChunkRow]("chunk-row")
    ):

  val sources = source(ChangeSource.eventsOf(SourceEntity))(_.onSource)

  /** Postgres full-text search over the row's text, best rank first; the caller gives the limit. */
  val lexical = query("lexical")(
    s"""
    SELECT payload FROM $table
    WHERE payload::jsonb->>'embeddingModel' = :model
      AND to_tsvector('english', payload::jsonb->>'text') @@ websearch_to_tsquery('english', :q)
    ORDER BY ts_rank_cd(to_tsvector('english', payload::jsonb->>'text'), websearch_to_tsquery('english', :q)) DESC"""
  )

  val bySource = query("by-source")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'sourceId' = :source ORDER BY row_key"
  )

  /** Every chunk of one embedding model: what semantic retrieval compares against. */
  val byModel = query("by-model")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'embeddingModel' = :model ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new ChunkRowsView
