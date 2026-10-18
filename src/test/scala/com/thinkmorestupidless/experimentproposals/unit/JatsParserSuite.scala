package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.adapters.jats.JatsParser
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

import java.nio.file.{Files, Path}

class JatsParserSuite extends munit.FunSuite:

  private def fixture(name: String) = Files.readString(Path.of("evals/fixtures/papers", name))

  test(
    "paper A parses into titled sections with stable text, a resolved table and method-result links"
  ) {
    val parsed = JatsParser.parse(fixture("paper-a.xml"))
    assertEquals(parsed.completeness, ParseCompleteness.complete)
    assert(parsed.title.exists(_.startsWith("Proliferation of primary duck hepatocytes")))
    val kinds = parsed.sections.map(_.kind)
    assert(
      kinds.contains(SectionKind.methods) && kinds.contains(SectionKind.results) && kinds.contains(
        SectionKind.abstract_
      )
    )
    val results = parsed.sections.find(_.kind == SectionKind.results).get
    val methods = parsed.sections.find(_.kind == SectionKind.methods).get
    assert(results.relatedSectionIds.contains(methods.id))
    val table = parsed.sections.find(_.kind == SectionKind.table).get
    assert(table.tableResolved)
    assert(table.text.contains("96 | 6.2 | 91"), table.text)
    assertEquals(table.parentId, Some(results.id))
    assert(results.relatedSectionIds.contains(table.id))
    val quote = "Cell number increased 3.1-fold between 0 and 96 hours"
    val at    = results.text.indexOf(quote)
    assert(at >= 0)
    assertEquals(results.text.substring(at, at + quote.length), quote)
    assertEquals(parsed.issues, Vector.empty)
  }

  test("a table that is only a graphic is flagged, not dropped") {
    val parsed = JatsParser.parse(fixture("retracted.xml"))
    assertEquals(parsed.completeness, ParseCompleteness.partial)
    assert(parsed.issues.exists(_.kind == "table_unresolved"))
    val table = parsed.sections.find(_.kind == SectionKind.table).get
    assert(!table.tableResolved)
    assertEquals(table.parseConfidence, ParseConfidence.low)
  }

  test("plain text is one body section, and empty text fails") {
    assertEquals(
      JatsParser.plainText("Some text. More text.").sections.map(_.kind),
      Vector(SectionKind.body)
    )
    assertEquals(JatsParser.plainText("  ").completeness, ParseCompleteness.failed)
  }

  test("corrupt XML is a failed parse with a `corrupt` issue, not an exception") {
    val parsed = JatsParser.parse("<article><body><sec><title>Results</title><p>Viability was 8")
    assertEquals(parsed.completeness, ParseCompleteness.failed)
    assertEquals(parsed.issues.map(_.kind), Vector("corrupt"))
    assertEquals(parsed.sections, Vector.empty)
  }

  test("structure with no readable text is materially ambiguous and fails") {
    val parsed = JatsParser.parse(
      "<article><body><sec><title>Methods</title></sec><sec><title>Results</title></sec></body></article>"
    )
    assertEquals(parsed.completeness, ParseCompleteness.failed)
    assert(parsed.issues.exists(_.kind == "no_text"), parsed.issues)
  }

  test("a DOCTYPE naming a DTD is parsed without fetching it") {
    val xml =
      """<?xml version="1.0"?><!DOCTYPE article PUBLIC "-//NLM//DTD JATS (Z39.96) Journal Archiving and Interchange DTD v1.2 20190208//EN" "JATS-archivearticle1.dtd"><article><front><article-meta><title-group><article-title>T</article-title></title-group></article-meta></front><body><sec sec-type="results"><title>Results</title><p>Cells grew.</p></sec></body></article>"""
    val parsed = JatsParser.parse(xml)
    assertEquals(parsed.completeness, ParseCompleteness.complete)
    assertEquals(parsed.sections.map(_.text), Vector("Cells grew."))
  }
