package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.SourceEntity
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent.MeasurementsRecorded
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One internal measurement, typed, queryable by kind and value; the unit as written (FR-011). */
final case class MeasurementRow(
    measurementId: String,
    sourceId: String,
    sourceVersion: Int,
    quantityKind: String,
    value: BigDecimal,
    unit: String,
    unitValid: Boolean,
    method: Option[String],
    conditions: Map[String, String],
    qualityFlag: String,
    recordedAt: Option[String],
    rawText: String
)

/**
 * A keyed view over the source's events: one `MeasurementsRecorded` event writes every measurement
 * it carries under its own id, so the internal data is a table and not only chunk text (plan.md).
 */
final class MeasurementRowsView extends KeyedView[MeasurementRow]:

  def onSource(event: SourceEvent, change: Change): Effect = event match
    case MeasurementsRecorded(version, measurements) =>
      effects.updateRows(measurements.map { m =>
        m.measurementId -> MeasurementRow(
          m.measurementId,
          change.subject,
          version,
          m.quantityKind,
          m.value,
          m.unit,
          m.unitValid,
          m.method,
          m.conditions,
          m.qualityFlag.toString,
          m.recordedAt,
          m.rawText
        )
      })
    case _ => effects.ignore()

object MeasurementRows
    extends KeyedView.Companion[MeasurementRowsView, MeasurementRow](
      ComponentId("measurement-rows"),
      Codecs.serializer[MeasurementRow]("measurement-row")
    ):

  val sources = source(ChangeSource.eventsOf(SourceEntity))(_.onSource)

  /** Measurements of one quantity kind whose value lies in a range, as numbers, not text. */
  val byKindAndRange = query("by-kind-and-range")(
    s"""SELECT payload FROM $table
       WHERE payload::jsonb->>'quantityKind' = :kind
         AND (payload::jsonb->>'value')::numeric >= (:min)::numeric
         AND (payload::jsonb->>'value')::numeric <= (:max)::numeric
       ORDER BY row_key"""
  )

  /** Every measurement whose unit is outside the unit table: the operator's correction list. */
  val invalidUnits = query("invalid-units")(
    s"SELECT payload FROM $table WHERE (payload::jsonb->>'unitValid')::boolean = false ORDER BY row_key"
  )

  val bySource = query("by-source")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'sourceId' = :source ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new MeasurementRowsView
