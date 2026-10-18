package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.QuestionEntity
import com.thinkmorestupidless.experimentproposals.domain.request.QuestionEvent
import com.thinkmorestupidless.experimentproposals.domain.request.QuestionEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

final case class QuestionRow(
    id: String,
    requestId: String,
    question: String,
    facets: Vector[String],
    status: String,
    flagged: Boolean
)

final class QuestionRowsView extends View[QuestionEvent, QuestionRow]:
  def onChange(event: QuestionEvent): Effect =
    val current = rowState.getOrElse(
      QuestionRow(updateContext.subject, "", "", Vector.empty, "unresolved", false)
    )
    event match
      case Derived(requestId, question, _, names, _, _, _, _) =>
        effects.updateRow(current.copy(requestId = requestId, question = question, facets = names))
      case StatusAdvanced(status, _, _, _, _) =>
        effects.updateRow(current.copy(status = status.toString))
      case FlaggedForReview(_, _, _) => effects.updateRow(current.copy(flagged = true))
      case PlanRecorded(_)           => effects.ignore()

object QuestionRows
    extends View.Companion[QuestionRowsView, QuestionEvent, QuestionRow](
      componentId = ComponentId("question-rows"),
      source = ChangeSource.eventsOf(QuestionEntity),
      rowSerializer = Codecs.serializer[QuestionRow]("question-row")
    ):
  val byRequest = query("by-request")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'requestId' = :request ORDER BY row_key"
  )
  def create(ctx: ViewComponentContext) = new QuestionRowsView
