package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.DesignEntity
import com.thinkmorestupidless.experimentproposals.domain.design.DesignEvent
import com.thinkmorestupidless.experimentproposals.domain.design.DesignEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per design: where its latest version stands and whether a change flagged it. */
final case class DesignRow(
    id: String,
    requestId: String,
    latestVersion: Int,
    latestStatus: String,
    approvedVersion: Option[Int],
    flagged: Boolean
)

final class DesignRowsView extends View[DesignEvent, DesignRow]:

  def onChange(event: DesignEvent): Effect =
    val current = rowState.getOrElse(DesignRow(updateContext.subject, "", 0, "", None, false))
    def status(version: Int, status: String) =
      if version == current.latestVersion then current.copy(latestStatus = status) else current
    event match
      case DraftGenerated(req, version, _, _, _, _, _, _, _, _) =>
        effects.updateRow(
          current.copy(requestId = req.id, latestVersion = version, latestStatus = "draft")
        )
      case Submitted(v, _, _) => effects.updateRow(status(v, "under_review"))
      case Approved(v, _, _, _) =>
        effects.updateRow(status(v, "approved").copy(approvedVersion = Some(v)))
      case Rejected(v, _, _, _)            => effects.updateRow(status(v, "rejected"))
      case ChangesRequested(v, _, _, _, _) => effects.updateRow(status(v, "changes_requested"))
      case Superseded(v, _, _)             => effects.updateRow(status(v, "superseded"))
      case FlaggedForReview(_, _, _, _)    => effects.updateRow(current.copy(flagged = true))

object DesignRows
    extends View.Companion[DesignRowsView, DesignEvent, DesignRow](
      componentId = ComponentId("design-rows"),
      source = ChangeSource.eventsOf(DesignEntity),
      rowSerializer = Codecs.serializer[DesignRow]("design-row")
    ):
  val flagged = query("flagged")(
    s"SELECT payload FROM $table WHERE (payload::jsonb->>'flagged')::boolean = true ORDER BY row_key"
  )
  val byRequest = query("by-request")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'requestId' = :request ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new DesignRowsView
