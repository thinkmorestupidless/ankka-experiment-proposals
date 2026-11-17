package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.conversation.*

class ReferenceMarkersSuite extends munit.FunSuite:

  test("a marker names a record by kind and id, and a version when it carries one") {
    val (refs, prefill) =
      ReferenceMarkers.parse(
        "Indirect by the species rule [[assessment:asm-1]], see [[design:dsg-1@2]]."
      )
    assertEquals(
      refs,
      Vector(Reference("assessment", "asm-1"), Reference("design", "dsg-1", Some(2)))
    )
    assertEquals(prefill, None)
  }

  test("every one of the sixteen kinds is a marker") {
    val text = CardKind.values.map(k => s"[[${k.wire}:x-1]]").mkString(" ")
    assertEquals(ReferenceMarkers.parse(text)._1.map(_.kind), CardKind.values.toVector.map(_.wire))
  }

  test("an unknown kind is not a marker") {
    assertEquals(
      ReferenceMarkers.parse("[[recipe:r-1]] and [[finding:fnd-1]]")._1.map(_.id),
      Vector("fnd-1")
    )
  }

  test("a marker inside a code span is text") {
    val (refs, prefill) = ReferenceMarkers.parse(
      "Cite with `[[finding:fnd-9]]`; this one is real: [[claim:clm-1]]. `[[prefill:approval:d|x=y]]`"
    )
    assertEquals(refs, Vector(Reference("claim", "clm-1")))
    assertEquals(prefill, None)
  }

  test("a record cited twice is one reference, in order of first appearance") {
    val refs = ReferenceMarkers.parse("[[finding:b]] [[finding:a]] [[finding:b]]")._1
    assertEquals(refs.map(_.id), Vector("b", "a"))
  }

  test("a prefill names the form, its target and its fields; an empty field stays empty") {
    val (_, prefill) = ReferenceMarkers.parse(
      "A reviewer must approve it. [[prefill:approval:dsg-1|outcome=approve|version=1|rationale=]]"
    )
    assertEquals(
      prefill,
      Some(
        Prefill(
          "approval",
          "dsg-1",
          Map("outcome" -> "approve", "version" -> "1", "rationale" -> "")
        )
      )
    )
  }

  test("a prefill of an unknown form is not a prefill, nor a reference") {
    val (refs, prefill) = ReferenceMarkers.parse("[[prefill:delete:req-1|now=yes]]")
    assertEquals(prefill, None)
    assertEquals(refs, Vector.empty)
  }
