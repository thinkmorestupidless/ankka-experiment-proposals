package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.{FacetEntity, RequestEntity}
import com.thinkmorestupidless.experimentproposals.domain.request.{FacetEvent, RequestEvent}
import com.thinkmorestupidless.experimentproposals.domain.request.FacetEvent.*
import com.thinkmorestupidless.experimentproposals.domain.request.RequestEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

final case class RequestRow(
    id: String,
    submittedAt: String,
    status: String,
    facets: Int,
    questions: Int,
    roundTripPassed: Option[Boolean],
    text: String = "",
    submittedBy: String = ""
)

final class RequestRowsView extends View[RequestEvent, RequestRow]:
  def onChange(event: RequestEvent): Effect =
    val current = rowState.getOrElse(RequestRow(updateContext.subject, "", "submitted", 0, 0, None))
    event match
      case Submitted(text, by, at, _) =>
        effects.updateRow(current.copy(submittedAt = at.toString, text = text, submittedBy = by))
      case FacetSetProposed(fs, _) =>
        effects.updateRow(current.copy(facets = fs.size, status = "facets_proposed"))
      case RoundTripChecked(r) => effects.updateRow(current.copy(roundTripPassed = Some(r.passed)))
      case FacetsConfirmed(_, _) => effects.updateRow(current.copy(status = "facets_confirmed"))
      case QuestionsDerived(ids) =>
        effects.updateRow(current.copy(questions = ids.size, status = "questions_derived"))
      case StatusChanged(status) => effects.updateRow(current.copy(status = status.toString))

object RequestRows
    extends View.Companion[RequestRowsView, RequestEvent, RequestRow](
      componentId = ComponentId("request-rows"),
      source = ChangeSource.eventsOf(RequestEntity),
      rowSerializer = Codecs.serializer[RequestRow]("request-row")
    ):
  // Version 2 added the text and the submitter to the row (2026-11-04); rows written before are rebuilt.
  override def version = Some(2)
  val byStatus = query("by-status")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'status' = :status ORDER BY row_key"
  )

  /** Every request, newest first: the way in for anyone who does not already hold an id. */
  val newestFirst = query("newest-first")(
    s"SELECT payload FROM $table ORDER BY payload::jsonb->>'submittedAt' DESC, row_key"
  )
  def create(ctx: ViewComponentContext) = new RequestRowsView

final case class FacetRow(
    id: String,
    requestId: String,
    name: String,
    statement: String,
    origin: String,
    importance: String,
    status: String,
    flagged: Boolean
)

final class FacetRowsView extends View[FacetEvent, FacetRow]:
  def onChange(event: FacetEvent): Effect =
    val current = rowState.getOrElse(FacetRow(updateContext.subject, "", "", "", "", "", "", false))
    event match
      case Proposed(requestId, name, statement, origin, importance, status, _, _, _) =>
        effects.updateRow(
          current.copy(
            requestId = requestId,
            name = name,
            statement = statement,
            origin = origin.toString,
            importance = importance.toString,
            status = status.toString
          )
        )
      case Reviewed(action, _, _, _, statementAfter, statusAfter) =>
        val origin =
          if action.toString == "confirm" || action.toString == "amend" then "human_confirmed"
          else current.origin
        effects.updateRow(
          current.copy(
            statement = statementAfter.getOrElse(current.statement),
            origin = origin,
            status = statusAfter.toString
          )
        )
      case AssumedWithApproval(_, _, _, _) => effects.updateRow(current.copy(status = "assumed"))
      case FlaggedForReview(_, _, _)       => effects.updateRow(current.copy(flagged = true))

object FacetRows
    extends View.Companion[FacetRowsView, FacetEvent, FacetRow](
      componentId = ComponentId("facet-rows"),
      source = ChangeSource.eventsOf(FacetEntity),
      rowSerializer = Codecs.serializer[FacetRow]("facet-row")
    ):
  val byRequest = query("by-request")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'requestId' = :request ORDER BY row_key"
  )
  def create(ctx: ViewComponentContext) = new FacetRowsView
