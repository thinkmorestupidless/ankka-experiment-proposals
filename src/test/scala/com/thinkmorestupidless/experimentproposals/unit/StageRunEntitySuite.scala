package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.StageRunEntity
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.*
import com.thinkmorestupidless.experimentproposals.rules.Rules
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class StageRunEntitySuite extends munit.FunSuite:

  private def kit   = EventSourcedTestKit.of(StageRunEntity, "run-1")
  private val input = Vector(Ref(RecordKind.request, "rq-1", 1))
  private def start = StartStage("investigation", "extract-facets", 1, input, "h1")

  test("a stage starts once, completes, and a second start for the same inputs is already done") {
    val k = kit
    assertEquals(k.call(StageRunEntity.start)(start).replyValue, StartOutcome.Started(1))
    val out = Some(Ref(RecordKind.facet, "rq-1:f1", 1))
    k.call(StageRunEntity.complete)(CompleteStage("extract-facets", 1, out))
    assertEquals(k.call(StageRunEntity.start)(start).replyValue, StartOutcome.AlreadyDone(out, 1))
    assertEquals(k.allEvents.size, 3, "the repeated start persisted nothing")
    assertEquals(k.currentState.workflow, "investigation")
  }

  test("a failed attempt is kept and the next start is a new attempt") {
    val k = kit
    k.call(StageRunEntity.start)(start)
    k.call(StageRunEntity.fail)(
      FailStage(
        "extract-facets",
        1,
        Rules.defect(DefectKind.schema_invalid, "bad json", retryable = true)
      )
    )
    assertEquals(k.call(StageRunEntity.start)(start).replyValue, StartOutcome.Started(2))
    assertEquals(
      k.currentState.stages.map(_.status),
      Vector(StageStatus.failed, StageStatus.passed)
    )
    assertEquals(k.currentState.stages.head.defect.map(_.kind), Some(DefectKind.schema_invalid))
  }

  test("completing an attempt that is not open is refused and persists nothing") {
    val k = kit
    val r = k.call(StageRunEntity.complete)(CompleteStage("extract-facets", 1, None))
    assert(r.isError)
    assertEquals(k.allEvents, Vector.empty)
  }
