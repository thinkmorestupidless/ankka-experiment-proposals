package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.StageRunEntity
import com.thinkmorestupidless.experimentproposals.domain.provenance.StageRunEvent
import com.thinkmorestupidless.experimentproposals.domain.provenance.StageRunEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per run: how many stages ran, how many failed, and the last one. */
final case class StageRow(
    runId: String,
    workflow: String,
    started: Int,
    completed: Int,
    failed: Int,
    lastStage: String,
    lastStatus: String
)

final class StageRowsView extends View[StageRunEvent, StageRow]:

  def onChange(event: StageRunEvent): Effect =
    val current = rowState.getOrElse(StageRow(updateContext.subject, "", 0, 0, 0, "", ""))
    event match
      case RunOpened(workflow, _) => effects.updateRow(current.copy(workflow = workflow))
      case StageStarted(stage, _, _, _, _, _) =>
        effects.updateRow(
          current.copy(started = current.started + 1, lastStage = stage, lastStatus = "running")
        )
      case StageCompleted(stage, _, _, _, _, status, _) =>
        effects.updateRow(
          current.copy(
            completed = current.completed + 1,
            lastStage = stage,
            lastStatus = status.toString
          )
        )
      case StageFailed(stage, _, _, _, _, _) =>
        effects.updateRow(
          current.copy(failed = current.failed + 1, lastStage = stage, lastStatus = "failed")
        )

object StageRows
    extends View.Companion[StageRowsView, StageRunEvent, StageRow](
      componentId = ComponentId("stage-rows"),
      source = ChangeSource.eventsOf(StageRunEntity),
      rowSerializer = Codecs.serializer[StageRow]("stage-row")
    ):
  val byWorkflow = query("by-workflow")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'workflow' = :workflow ORDER BY row_key"
  )
  val failed = query("failed")(
    s"SELECT payload FROM $table WHERE (payload::jsonb->>'failed')::int > 0 ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new StageRowsView
