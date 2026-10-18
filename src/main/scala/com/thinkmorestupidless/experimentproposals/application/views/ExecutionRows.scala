package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.SearchExecutionEntity
import com.thinkmorestupidless.experimentproposals.domain.search.SearchExecutionEvent
import com.thinkmorestupidless.experimentproposals.domain.search.SearchExecutionEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per search execution, for listing a question's searches and their outcome. */
final case class ExecutionRow(
    id: String,
    planId: String,
    questionId: String,
    source: String,
    fused: Boolean,
    executedAt: String,
    resultCount: Int,
    eligibleCount: Int,
    exclusionCount: Int,
    coverageGap: Boolean,
    gapId: Option[String],
    rerunOf: Option[String],
    error: Option[String]
)

final class ExecutionRowsView extends View[SearchExecutionEvent, ExecutionRow]:

  def onChange(event: SearchExecutionEvent): Effect =
    val current = rowState.getOrElse(
      ExecutionRow(updateContext.subject, "", "", "", false, "", 0, 0, 0, false, None, None, None)
    )
    event match
      case Executed(
            planRef,
            questionId,
            _,
            fused,
            source,
            at,
            _,
            _,
            results,
            error,
            rerunOf,
            gapId
          ) =>
        effects.updateRow(
          current.copy(
            planId = planRef.id,
            questionId = questionId,
            source = source,
            fused = fused,
            executedAt = at.toString,
            resultCount = results.size,
            gapId = gapId,
            rerunOf = rerunOf,
            error = error
          )
        )
      case EligibilityApplied(eligible, exclusions, gap, _, _) =>
        effects.updateRow(
          current.copy(
            eligibleCount = eligible.size,
            exclusionCount = exclusions.size,
            coverageGap = gap
          )
        )

object ExecutionRows
    extends View.Companion[ExecutionRowsView, SearchExecutionEvent, ExecutionRow](
      componentId = ComponentId("execution-rows"),
      source = ChangeSource.eventsOf(SearchExecutionEntity),
      rowSerializer = Codecs.serializer[ExecutionRow]("execution-row")
    ):
  val byQuestion = query("by-question")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'questionId' = :question ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new ExecutionRowsView
