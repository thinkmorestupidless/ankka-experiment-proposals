package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.agents.{Prompts, Sections}

class PromptsSuite extends munit.FunSuite:

  test("a prompt loads by stage and version and states its version on its first line") {
    val p = Prompts.load("test-stage", 1)
    assertEquals(p.promptVersion, "test-stage@v1")
    assertEquals(Prompts.versionOf(p.system), Some("test-stage@v1"))
  }

  test("a missing prompt, or one whose header disagrees, fails loudly") {
    intercept[IllegalStateException](Prompts.load("test-stage", 2))
    intercept[IllegalStateException](Prompts.load("other-stage", 1))
  }

  test("the case marker is the user message's first line and is read back") {
    val m = Prompts.userMessage("rq-1", "Extract the facets.")
    assertEquals(Prompts.caseOf(m), Some("rq-1"))
    assert(m.endsWith("Extract the facets."))
  }

  test("sections render under keys the model quotes by") {
    val text = Sections.render(
      Sections.keyed(Seq("Methods" -> "We cultured.", "Results" -> "Cells grew."))(_._1, _._2)
    )
    assertEquals(text, "[S1] Methods\nWe cultured.\n\n[S2] Results\nCells grew.")
  }
