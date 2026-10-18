package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.views.MeasurementRows
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.*

class MeasurementRowsSuite extends munit.FunSuite:

  test(
    "a MeasurementsRecorded event writes one row per measurement under its id; other events nothing"
  ) {
    val kit = KeyedViewTestKitHolder.kit
    kit.change(MeasurementRows.sources, "src:internal:lims:m", SourceEvent.IngestionNoted("w"))
    assertEquals(kit.rows.size, 0)
    val ms = Vector(
      Measurement(
        "internal:lims:m:S-1",
        "triglyceride_content",
        BigDecimal("21.5"),
        "ug/mg",
        true,
        None,
        Map("cell_line" -> "HepG2"),
        QualityFlag.ok,
        "S-1,...",
        Some("2026-09-01")
      ),
      Measurement(
        "internal:lims:m:S-2",
        "triglyceride_content",
        BigDecimal("93.0"),
        "ug per mg",
        false,
        None,
        Map.empty,
        QualityFlag.ok,
        "S-2,...",
        None
      )
    )
    kit.change(
      MeasurementRows.sources,
      "src:internal:lims:m",
      SourceEvent.MeasurementsRecorded(1, ms)
    )
    assertEquals(kit.rows.keySet, Set("internal:lims:m:S-1", "internal:lims:m:S-2"))
    val row = kit.row("internal:lims:m:S-2").get
    assertEquals(row.sourceId, "src:internal:lims:m")
    assertEquals(row.value, BigDecimal("93.0"))
    assertEquals(row.unit, "ug per mg")
    assert(!row.unitValid)
    assertEquals(kit.row("internal:lims:m:S-1").get.conditions, Map("cell_line" -> "HepG2"))
  }

object KeyedViewTestKitHolder:
  def kit = com.thinkmorestupidless.ankka.testkit.KeyedViewTestKit(MeasurementRows)
