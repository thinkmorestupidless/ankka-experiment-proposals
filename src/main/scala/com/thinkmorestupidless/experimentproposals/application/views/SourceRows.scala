package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.SourceEntity
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per source, for listing and for finding sources by identifier, status or kind. */
final case class SourceRow(
    id: String,
    identifiers: Map[String, String],
    title: String,
    kind: String,
    status: String,
    latestVersion: Int,
    eligible: Option[Boolean],
    quarantined: Boolean,
    retention: String
)

final class SourceRowsView extends View[SourceEvent, SourceRow]:

  def onChange(event: SourceEvent): Effect =
    val current = rowState.getOrElse(
      SourceRow(updateContext.subject, Map.empty, "", "", "", 0, None, false, "none")
    )
    event match
      case Registered(ids, title, _, _, _, _, _, kind, _, _, status, _) =>
        effects.updateRow(
          current.copy(
            identifiers = ids,
            title = title,
            kind = kind.toString,
            status = status.toString
          )
        )
      case ContentFetched(version, _, _, _, retention, _) =>
        effects.updateRow(current.copy(latestVersion = version, retention = retention.toString))
      case Quarantined(_, _, _) => effects.updateRow(current.copy(quarantined = true))
      case StatusChanged(status, _, _, _) =>
        effects.updateRow(current.copy(status = status.toString))
      case EligibilityAssessed(e) => effects.updateRow(current.copy(eligible = Some(e.eligible)))
      case _                      => effects.ignore()

object SourceRows
    extends View.Companion[SourceRowsView, SourceEvent, SourceRow](
      componentId = ComponentId("source-rows"),
      source = ChangeSource.eventsOf(SourceEntity),
      rowSerializer = Codecs.serializer[SourceRow]("source-row")
    ):
  val byStatus = query("by-status")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'status' = :status ORDER BY row_key"
  )
  val byKind = query("by-kind")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'kind' = :kind ORDER BY row_key"
  )
  val byDoi = query("by-doi")(
    s"SELECT payload FROM $table WHERE payload::jsonb->'identifiers'->>'doi' = :doi ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new SourceRowsView
