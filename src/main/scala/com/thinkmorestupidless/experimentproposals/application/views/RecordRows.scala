package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.{ClaimEntity, FindingEntity}
import com.thinkmorestupidless.experimentproposals.domain.evidence.{RecordContent, RecordEvent}
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One row per finding or claim: what a queue or a source listing needs, nothing to decide by. */
final case class RecordRow(
    id: String,
    sourceId: String,
    kind: String,
    status: String,
    endpoint: String,
    species: String,
    outcome: String,
    proposition: String,
    generatedBy: String,
    attempts: Int,
    flagged: Boolean
)

final class RecordRowsView extends View[RecordEvent, RecordRow]:
  private def summarise(row: RecordRow, content: RecordContent): RecordRow = content match
    case RecordContent.OfFinding(f) =>
      row.copy(
        endpoint = f.observedEndpoint,
        species = f.species.getOrElse(""),
        outcome = f.reportedOutcome
      )
    case RecordContent.OfClaim(c) =>
      row.copy(
        proposition = c.proposition,
        species = c.speciesScope.getOrElse(""),
        endpoint = c.endpointScope.getOrElse("")
      )

  def onChange(event: RecordEvent): Effect =
    val current = rowState.getOrElse(
      RecordRow(updateContext.subject, "", "", "proposed", "", "", "", "", "", 0, false)
    )
    event match
      case Proposed(kind, sourceRef, content, _, by, _, _) =>
        effects.updateRow(
          summarise(
            current.copy(
              sourceId = sourceRef.id,
              kind = kind,
              generatedBy = by,
              attempts = 1,
              status = "proposed"
            ),
            content
          )
        )
      case Reproposed(attempt, content, _, _, _, _) =>
        effects.updateRow(summarise(current.copy(attempts = attempt, status = "proposed"), content))
      case Verified(_, _, _)          => effects.updateRow(current.copy(status = "verified"))
      case Rejected(_, _, _, _)       => effects.updateRow(current.copy(status = "rejected"))
      case RequiresReview(_, _, _, _) => effects.updateRow(current.copy(status = "requires_review"))
      case Quarantined(_, _, _)       => effects.updateRow(current.copy(status = "quarantined"))
      case Reviewed(_, _, _, _, after) => effects.updateRow(current.copy(status = after.toString))
      case FlaggedForReview(_, _, _)   => effects.updateRow(current.copy(flagged = true))
      case Validated(_, _)             => effects.ignore()

object FindingRows
    extends View.Companion[RecordRowsView, RecordEvent, RecordRow](
      componentId = ComponentId("finding-rows"),
      source = ChangeSource.eventsOf(FindingEntity),
      rowSerializer = Codecs.serializer[RecordRow]("record-row")
    ):
  val bySource = query("by-source")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'sourceId' = :source ORDER BY row_key"
  )
  val byStatus = query("by-status")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'status' = :status ORDER BY row_key"
  )
  def create(ctx: ViewComponentContext) = new RecordRowsView

object ClaimRows
    extends View.Companion[RecordRowsView, RecordEvent, RecordRow](
      componentId = ComponentId("claim-rows"),
      source = ChangeSource.eventsOf(ClaimEntity),
      rowSerializer = Codecs.serializer[RecordRow]("record-row")
    ):
  val bySource = query("by-source")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'sourceId' = :source ORDER BY row_key"
  )
  val byStatus = query("by-status")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'status' = :status ORDER BY row_key"
  )
  def create(ctx: ViewComponentContext) = new RecordRowsView
