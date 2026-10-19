package com.thinkmorestupidless.experimentproposals.domain.search

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** One query of a plan (glossary: lexical query, semantic query); an expansion names its parent. */
final case class Query(kind: QueryKind, text: String, expansionOf: Option[String] = None)

/** A query as the model proposes it; the gate turns the kind into the closed list. */
final case class QueryProposal(kind: String, text: String, expansionOf: Option[String] = None)

// docs:start plan-proposal
/** The search agent's reply: queries, targets, filters, a stopping criterion, and expansions. */
final case class SearchPlanProposal(
    queries: Vector[QueryProposal],
    targetSources: Vector[String],
    filters: Map[String, String] = Map.empty,
    stoppingCriterion: String,
    escalation: String = "supplemental_search",
    expansions: Vector[String] = Vector.empty
)
// docs:end plan-proposal

/** The per-question search plan (glossary: search plan); a replan is a new version. */
final case class SearchPlan(
    id: String,
    registered: Boolean = false,
    questionRef: Option[Ref] = None,
    queries: Vector[Query] = Vector.empty,
    targetSources: Vector[String] = Vector.empty,
    filters: Map[String, String] = Map.empty,
    parameters: Map[String, String] = Map.empty,
    stoppingCriterion: String = "",
    escalation: Escalation = Escalation.none,
    expansions: Vector[String] = Vector.empty,
    executionIds: Vector[String] = Vector.empty,
    plannedBy: String = "",
    version: Int = 0
)

object SearchPlan:
  def empty(id: String): SearchPlan      = SearchPlan(id)
  def idFor(questionId: String): String  = s"$questionId:plan"
  def questionOf(planId: String): String = planId.stripSuffix(":plan")

  /** The gate (data-model.md): at least one lexical and one semantic query. */
  def twoKinds(queries: Vector[Query]): Boolean =
    queries.exists(_.kind == QueryKind.lexical) && queries.exists(_.kind == QueryKind.semantic)

enum SearchPlanEvent:
  case Planned(
      questionRef: Ref,
      queries: Vector[Query],
      targetSources: Vector[String],
      filters: Map[String, String],
      parameters: Map[String, String],
      stoppingCriterion: String,
      escalation: Escalation,
      expansions: Vector[String],
      plannedBy: String,
      at: Instant
  )
  case ExecutionRecorded(executionId: String, at: Instant)

final case class RecordPlan(
    questionRef: Ref,
    queries: Vector[Query],
    targetSources: Vector[String],
    filters: Map[String, String],
    parameters: Map[String, String],
    stoppingCriterion: String,
    escalation: Escalation,
    expansions: Vector[String],
    plannedBy: String
)

/** One returned result, before eligibility (glossary: search execution). */
final case class Hit(
    rank: Int,
    score: Double,
    sourceId: String,
    sourceVersion: Int,
    chunkId: Option[String],
    sectionId: Option[String],
    lexicalRank: Option[Int] = None,
    semanticRank: Option[Int] = None,
    identifier: Option[String] = None,
    title: Option[String] = None
)

/** Why a result was removed or moved down after ranking (glossary: exclusion). */
final case class Exclusion(
    sourceId: String,
    sourceVersion: Int,
    chunkId: Option[String],
    reason: String,
    ruleId: String,
    action: ExclusionAction
)

/** One run of one query against one source, written once (glossary: search execution). */
final case class SearchExecution(
    id: String,
    registered: Boolean = false,
    planRef: Option[Ref] = None,
    questionId: String = "",
    query: Option[Query] = None,
    fused: Boolean = false,
    source: String = "",
    executedAt: Option[Instant] = None,
    parameters: Map[String, String] = Map.empty,
    snapshot: Vector[Ref] = Vector.empty,
    results: Vector[Hit] = Vector.empty,
    eligibleResults: Vector[Hit] = Vector.empty,
    exclusions: Vector[Exclusion] = Vector.empty,
    eligibilityApplied: Boolean = false,
    coverageGap: Boolean = false,
    error: Option[String] = None,
    rerunOf: Option[String] = None,
    gapId: Option[String] = None,
    version: Int = 0
)

object SearchExecution:
  def empty(id: String): SearchExecution    = SearchExecution(id)
  def idFor(planId: String, n: Int): String = s"$planId:e$n"

enum SearchExecutionEvent:
  case Executed(
      planRef: Ref,
      questionId: String,
      query: Option[Query],
      fused: Boolean,
      source: String,
      executedAt: Instant,
      parameters: Map[String, String],
      snapshot: Vector[Ref],
      results: Vector[Hit],
      error: Option[String],
      rerunOf: Option[String],
      gapId: Option[String]
  )
  case EligibilityApplied(
      eligibleResults: Vector[Hit],
      exclusions: Vector[Exclusion],
      coverageGap: Boolean,
      ruleSetVersion: String,
      at: Instant
  )

final case class RecordExecution(
    planRef: Ref,
    questionId: String,
    query: Option[Query],
    fused: Boolean,
    source: String,
    parameters: Map[String, String],
    snapshot: Vector[Ref],
    results: Vector[Hit],
    error: Option[String],
    rerunOf: Option[String],
    gapId: Option[String]
)

final case class ApplyEligibility(
    eligibleResults: Vector[Hit],
    exclusions: Vector[Exclusion],
    coverageGap: Boolean,
    ruleSetVersion: String
)

object SearchCodecs:
  given Serializer[SearchPlan]      = Codecs.serializer[SearchPlan]("search-plan")
  given Serializer[SearchPlanEvent] = Codecs.serializer[SearchPlanEvent]("search-plan-event")
  given Serializer[RecordPlan]      = Codecs.serializer[RecordPlan]("record-plan")
  given Serializer[SearchPlanProposal] =
    Codecs.serializer[SearchPlanProposal]("search-plan-proposal")
  given Serializer[SearchExecution] = Codecs.serializer[SearchExecution]("search-execution")
  given Serializer[SearchExecutionEvent] =
    Codecs.serializer[SearchExecutionEvent]("search-execution-event")
  given Serializer[RecordExecution]  = Codecs.serializer[RecordExecution]("record-execution")
  given Serializer[ApplyEligibility] = Codecs.serializer[ApplyEligibility]("apply-eligibility")
