package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.adapters.{
  AdapterPermanent,
  InternalAdapter,
  InternalFile
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

import java.nio.file.Path

class InternalAdapterSuite extends munit.FunSuite:

  private val adapter  = InternalAdapter(Path.of("evals/fixtures"))
  private val manifest = "evals/fixtures/internal/manifest.json"

  test(
    "a manifest lists one record per file; a file becomes one internal source with its measurements"
  ) {
    val ids = adapter.records(manifest)
    assertEquals(ids.size, 1)
    assert(ids.head.endsWith("manifest.json::measurements.csv"), ids.head)
    val r = adapter.fetch(ids.head)
    assertEquals(r.identifier, "internal:lab-lims:measurements")
    assertEquals(r.kind, SourceKind.internal_measurement)
    assertEquals(r.internal.map(_.responsibleResearcher), Some("demo-researcher"))
    assertEquals(r.internal.flatMap(_.protocolVersion), Some("LIPID-ASSAY-v3"))
    assertEquals(r.format, "text")
    assert(r.content.exists(_.startsWith("sample_id,")), r.content)
    assertEquals(r.measurements.size, 5)
  }

  test("a unit is validated against the table and never converted; an unknown unit is an issue") {
    val r  = adapter.fetch(adapter.records(manifest).head)
    val s1 = r.measurements.find(_.measurementId.endsWith(":S-001")).get
    assertEquals(s1.value, BigDecimal("21.5"))
    assertEquals(s1.unit, "ug/mg")
    assert(s1.unitValid)
    assertEquals(
      s1.conditions,
      Map("cell_line" -> "HepG2", "treatment" -> "vehicle", "hours" -> "48")
    )
    assertEquals(s1.recordedAt, Some("2026-09-01T10:00:00Z"))
    val s5 = r.measurements.find(_.measurementId.endsWith(":S-005")).get
    assertEquals(s5.value, BigDecimal("93.0"))
    assertEquals(s5.unit, "ug per mg")
    assert(!s5.unitValid)
    assertEquals(r.issues.map(_.kind), Vector("unit_invalid"))
    assertEquals(r.issues.head.location, "S-005")
  }

  test("a value that is not a number is an issue naming the row, not a measurement") {
    val spec = InternalFile("x.csv", "internal_measurement", "id", "kind", "value", "unit")
    val (ms, issues) = InternalAdapter.measurements(
      "internal:sys:x",
      spec,
      "id,kind,value,unit\nA,mass,\"1,5\",g\nB,mass,2,g\n"
    )
    assertEquals(ms.map(_.measurementId), Vector("internal:sys:x:B"))
    assertEquals(issues.map(i => (i.kind, i.location)), Vector(("value_invalid", "A")))
  }

  test(
    "a missing manifest, a file it does not name and an unknown kind are permanent; search lists files"
  ) {
    intercept[AdapterPermanent](adapter.fetch("nowhere/manifest.json::a.csv"))
    intercept[AdapterPermanent](adapter.fetch(s"$manifest::other.csv"))
    intercept[AdapterPermanent](adapter.fetch("no-separator"))
    assertEquals(adapter.search("", Map.empty).map(_.title), Vector("lab-lims: measurements.csv"))
  }
