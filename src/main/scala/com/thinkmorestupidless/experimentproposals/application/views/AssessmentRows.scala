package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.AssessmentEntity
import com.thinkmorestupidless.experimentproposals.domain.evidence.AssessmentEvent
import com.thinkmorestupidless.experimentproposals.domain.evidence.AssessmentEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

final case class AssessmentRow(
    id: String,
    questionId: String,
    recordId: String,
    relationship: String,
    applicability: String,
    reviewStatus: String,
    flagged: Boolean
)

final class AssessmentRowsView extends View[AssessmentEvent, AssessmentRow]:
  def onChange(event: AssessmentEvent): Effect =
    val current =
      rowState.getOrElse(AssessmentRow(updateContext.subject, "", "", "", "", "proposed", false))
    event match
      case Proposed(q, r, rel, app, _, _, _, _, _, _, _) =>
        effects.updateRow(
          current.copy(
            questionId = q.id,
            recordId = r.id,
            relationship = rel.toString,
            applicability = app.toString
          )
        )
      case Reviewed(_, _, _, _, after) =>
        effects.updateRow(current.copy(reviewStatus = after.toString))
      case FlaggedForReview(_, _, _) => effects.updateRow(current.copy(flagged = true))

object AssessmentRows
    extends View.Companion[AssessmentRowsView, AssessmentEvent, AssessmentRow](
      componentId = ComponentId("assessment-rows"),
      source = ChangeSource.eventsOf(AssessmentEntity),
      rowSerializer = Codecs.serializer[AssessmentRow]("assessment-row")
    ):
  val byQuestion = query("by-question")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'questionId' = :question ORDER BY row_key"
  )
  val byStatus = query("by-status")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'reviewStatus' = :status ORDER BY row_key"
  )
  def create(ctx: ViewComponentContext) = new AssessmentRowsView
