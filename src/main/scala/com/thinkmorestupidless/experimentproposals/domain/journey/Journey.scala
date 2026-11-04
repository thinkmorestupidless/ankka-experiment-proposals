package com.thinkmorestupidless.experimentproposals.domain.journey

/**
 * A request's path through the stages (contracts/journey.md), derived from the workflows' states
 * and the stage runs, never stored. Every field is what the interface draws; nothing here is a
 * record of its own.
 */
final case class Journey(
    requestId: String,
    spine: Vector[StageNode],
    questions: Vector[QuestionLane]
)

final case class QuestionLane(questionId: String, text: String, nodes: Vector[StageNode])

/** One stage, its state, a short detail, and the stage executions it is derived from. */
final case class StageNode(
    stage: String,
    state: String,
    detail: Option[String] = None,
    runs: Vector[StageRunRef] = Vector.empty
)

/** One stage execution as the journey names it; the stage-run card holds the rest. */
final case class StageRunRef(
    runId: String,
    stage: String,
    attempt: Int,
    status: String,
    startedAt: String,
    endedAt: Option[String],
    defect: Option[String],
    // A stage execution does not record who asked for it; the actor is on the record it produced.
    actor: Option[String] = None
)

/** Where a workflow stood: the engine's view of it, apart from the workflow's own state. */
final case class Standing(status: String, pendingStep: Option[String], failure: Option[String]):
  def failed: Boolean    = status == "Failed"
  def running: Boolean   = status == "Running"
  def paused: Boolean    = status == "Paused"
  def completed: Boolean = status == "Completed"

object Standing:
  val none: Standing = Standing("NotStarted", None, None)

enum NodeState:
  case unreached, reached, current, paused, failed

object Stages:
  /** The request's spine, in order. */
  val spine: Vector[String] = Vector(
    "request",
    "facets",
    "round-trip",
    "facet-review",
    "questions",
    "packages",
    "candidates",
    "decision",
    "design",
    "approval",
    "impact"
  )

  /** One question's lane, in order. */
  val lane: Vector[String] =
    Vector("search-plan", "executions", "eligibility", "verification", "assessment", "sufficiency")
