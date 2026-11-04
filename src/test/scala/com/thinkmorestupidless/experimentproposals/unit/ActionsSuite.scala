package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.conversation.*

class ActionsSuite extends munit.FunSuite:

  test("each action is accepted with its arguments") {
    assertEquals(
      Actions.validate("submit-request", Map("text" -> "Can you make foie gras?")),
      Right(Action.SubmitRequest("Can you make foie gras?"))
    )
    assertEquals(
      Actions.validate("ingest-source", Map("adapter" -> "file", "identifier" -> "paper-a.xml")),
      Right(Action.IngestSource("file", "paper-a.xml"))
    )
    assertEquals(
      Actions.validate("inject-fault", Map("recordId" -> "fnd-1", "fault" -> "endpoint_swap")),
      Right(Action.InjectFault("fnd-1", "endpoint_swap"))
    )
    assertEquals(
      Actions.validate("set-source-status", Map("sourceId" -> "src-1", "status" -> "retracted")),
      Right(Action.SetSourceStatus("src-1", "retracted", ""))
    )
  }

  test("a request's text is kept exactly as typed") {
    val typed = "  Can you make foie gras?  "
    assertEquals(
      Actions.validate("submit-request", Map("text" -> typed)).map(_.arguments("text")),
      Right(typed)
    )
  }

  test("a missing argument is refused naming it") {
    assertEquals(
      Actions.validate("ingest-source", Map("adapter" -> "file")),
      Left("ingest-source needs 'identifier'")
    )
    assertEquals(
      Actions.validate("inject-fault", Map.empty),
      Left("inject-fault needs 'recordId' and 'fault'")
    )
    assert(
      Actions
        .validate("submit-request", Map("text" -> " "))
        .left
        .exists(_.startsWith("submit-request needs 'text'"))
    )
  }

  test("an unknown fault, status or action is refused with what is allowed") {
    assert(
      Actions
        .validate("inject-fault", Map("recordId" -> "f", "fault" -> "melt"))
        .left
        .exists(m => m.startsWith("unknown fault 'melt'") && m.contains("endpoint_swap"))
    )
    assert(
      Actions
        .validate("set-source-status", Map("sourceId" -> "s", "status" -> "gone"))
        .left
        .exists(m => m.startsWith("unknown status 'gone'") && m.contains("retracted"))
    )
    assert(
      Actions
        .validate("approve-design", Map.empty)
        .left
        .exists(m =>
          m.startsWith("unknown action 'approve-design'") && m.contains("submit-request")
        )
    )
  }
