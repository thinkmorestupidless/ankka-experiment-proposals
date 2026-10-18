package com.thinkmorestupidless.experimentproposals.retrieval

import com.thinkmorestupidless.experimentproposals.application.views.{ChunkRow, ChunkRows}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.search.{Hit, Query}
import com.thinkmorestupidless.ankka.runtime.ViewClient

/**
 * Local retrieval over the chunk store: one ranked list per query, and the fused list (research.md
 * R6). The same code runs a rerun against a recorded snapshot, so the two can be compared.
 */
object Searcher:
  final case class Settings(topK: Int = 20, semanticCutOff: Double = 0.0, rrfK: Int = Fusion.K)

  final case class Store(rows: Vector[ChunkRow]):
    lazy val snapshot: Vector[Ref] =
      rows
        .map(r => Ref(RecordKind.source, r.sourceId, r.sourceVersion))
        .distinct
        .sortBy(r => (r.id, r.version))
    lazy val byChunk: Map[String, ChunkRow] = rows.map(r => r.chunkId -> r).toMap
    def restrictedTo(snap: Vector[Ref]): Store =
      val keep = snap.map(r => (r.id, r.version)).toSet
      Store(rows.filter(r => keep.contains((r.sourceId, r.sourceVersion))))

  /** Every chunk of the configured embedding model: the snapshot a local execution searches. */
  def store(views: ViewClient): Store =
    Store(
      views.forView(ChunkRows).ask(ChunkRows.byModel, 50000, "model" -> Configured.embedder.model)
    )

  def parameters(settings: Settings): Map[String, String] = Map(
    "topK"              -> settings.topK.toString,
    "rrfK"              -> settings.rrfK.toString,
    "semanticCutOff"    -> settings.semanticCutOff.toString,
    "embeddingModel"    -> Configured.embedder.model,
    "embeddingRevision" -> Configured.embedder.revision
  )

  private def hit(row: ChunkRow, rank: Int, score: Double, kind: QueryKind): Hit =
    Hit(
      rank,
      score,
      row.sourceId,
      row.sourceVersion,
      Some(row.chunkId),
      Some(row.sectionId),
      lexicalRank = Option.when(kind == QueryKind.lexical)(rank),
      semanticRank = Option.when(kind == QueryKind.semantic)(rank)
    )

  /** One query against the store; `live` uses the database's ranking, a rerun ranks in memory. */
  def run(query: Query, store: Store, settings: Settings, views: Option[ViewClient]): Vector[Hit] =
    query.kind match
      case QueryKind.lexical =>
        val rows = views match
          case Some(v) => Lexical.search(v, Configured.embedder.model, query.text, settings.topK)
          case None    => Lexical.rank(store.rows, query.text, settings.topK)
        rows.zipWithIndex.map((r, i) => hit(r, i + 1, 1.0 / (i + 1), QueryKind.lexical))
      case QueryKind.semantic =>
        val vector = Configured.embedder.embed(query.text)
        Semantic
          .search(store.rows, vector, settings.topK, settings.semanticCutOff)
          .zipWithIndex
          .map((s, i) => hit(s.row, i + 1, s.score, QueryKind.semantic))

  /** The fused list over every query's hits, by chunk id, both ranks kept. */
  def fuse(perQuery: Vector[(Query, Vector[Hit])], store: Store, settings: Settings): Vector[Hit] =
    val lexical = perQuery.collect {
      case (q, hits) if q.kind == QueryKind.lexical => hits.flatMap(_.chunkId)
    }
    val semantic = perQuery.collect {
      case (q, hits) if q.kind == QueryKind.semantic => hits.flatMap(_.chunkId)
    }
    val byChunk = perQuery.flatMap(_._2).flatMap(h => h.chunkId.map(_ -> h)).toMap
    Fusion.rrfAll(lexical, semantic, settings.rrfK).zipWithIndex.flatMap { (f, i) =>
      byChunk
        .get(f.chunkId)
        .map(h =>
          h.copy(
            rank = i + 1,
            score = f.score,
            lexicalRank = f.lexicalRank,
            semanticRank = f.semanticRank
          )
        )
    }
