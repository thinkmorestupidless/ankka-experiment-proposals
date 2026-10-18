package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.adapters.{AdapterPermanent, FileAdapter}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

import java.nio.file.Path

class FileAdapterSuite extends munit.FunSuite:

  private val adapter = FileAdapter(Path.of("evals/fixtures/papers"))

  test("a JATS fixture is fetched with its metadata, licence, status and hash") {
    val r = adapter.fetch("paper-a.xml")
    assertEquals(r.identifier, "file:paper-a")
    assertEquals(r.identifiers.get("doi"), Some("10.9999/fixture.paper-a"))
    assertEquals(r.format, "jats")
    assertEquals(r.status, SourceStatus.active)
    assertEquals(r.licence, Some("CC BY 4.0"))
    assert(r.content.exists(_.contains("<article")))
    assertEquals(r.retention, RetentionRestriction.none)
    assertEquals(r.contentHash.length, 64)
  }

  test(
    "a retracted fixture carries its status, and an abstract-only one keeps its abstract and no text"
  ) {
    assertEquals(adapter.fetch("retracted.xml").status, SourceStatus.retracted)
    val a = adapter.fetch("abstract-only.txt")
    assertEquals(a.content, None)
    assertEquals(a.retention, RetentionRestriction.abstract_only)
    assert(a.abstractText.exists(_.contains("goose")))
  }

  test("a missing file or missing metadata is permanent, and search lists the fixtures") {
    intercept[AdapterPermanent](adapter.fetch("nope.xml"))
    assertEquals(
      adapter.search("", Map.empty).map(_.identifier).sorted,
      Vector(
        "file:abstract-only",
        "file:corrected",
        "file:corrupt",
        "file:paper-a",
        "file:paper-b",
        "file:paper-c",
        "file:paper-d",
        "file:retracted",
        "file:superseded"
      )
    )
    assertEquals(adapter.search("goose", Map.empty).map(_.identifier), Vector("file:paper-c"))
  }
