package com.thinkmorestupidless.experimentproposals.retrieval

/** Reciprocal Rank Fusion over ranked lists of chunk ids (research.md R6): `k = 60`. */
object Fusion:
  val K = 60

  final case class Fused(
      chunkId: String,
      score: Double,
      lexicalRank: Option[Int],
      semanticRank: Option[Int]
  )

  /** One lexical and one semantic list, in rank order. */
  def rrf(lexical: Seq[String], semantic: Seq[String], k: Int = K): Vector[Fused] =
    rrfAll(Seq(lexical), Seq(semantic), k)

  /**
   * Several lists of each kind: a chunk's rank for a kind is its best rank in that kind's lists; a
   * chunk in both kinds keeps both ranks and sums its two contributions. Ties fall to the chunk id.
   */
  def rrfAll(lexical: Seq[Seq[String]], semantic: Seq[Seq[String]], k: Int = K): Vector[Fused] =
    def best(lists: Seq[Seq[String]]): Map[String, Int] =
      lists
        .flatMap(_.zipWithIndex.map((id, i) => id -> (i + 1)))
        .groupMapReduce(_._1)(_._2)(math.min)
    val lex = best(lexical)
    val sem = best(semantic)
    (lex.keySet ++ sem.keySet).toVector
      .map { id =>
        val score = lex.get(id).map(r => 1.0 / (k + r)).getOrElse(0.0) +
          sem.get(id).map(r => 1.0 / (k + r)).getOrElse(0.0)
        Fused(id, score, lex.get(id), sem.get(id))
      }
      .sortBy(f => (-f.score, f.chunkId))
