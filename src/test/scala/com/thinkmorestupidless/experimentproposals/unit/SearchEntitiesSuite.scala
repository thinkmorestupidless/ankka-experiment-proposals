package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.{
  SearchExecutionEntity,
  SearchPlanEntity
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.search.*
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class SearchEntitiesSuite extends munit.FunSuite:

  private val q = Ref(RecordKind.question, "rq-1:q4", 1)
  private def plan(queries: Vector[Query]) =
    RecordPlan(
      q,
      queries,
      Vector("local"),
      Map.empty,
      Map("topK" -> "20"),
      "stop when done",
      Escalation.supplemental_search,
      Vector("wider"),
      "model:plan@v1"
    )
  private val both =
    Vector(Query(QueryKind.lexical, "lipid duck"), Query(QueryKind.semantic, "lipid in duck cells"))

  test("a plan needs both query kinds; each plan is a new version; executions are recorded once") {
    val k = EventSourcedTestKit.of(SearchPlanEntity, "rq-1:q4:plan")
    assert(k.call(SearchPlanEntity.plan)(plan(Vector(Query(QueryKind.lexical, "only")))).isError)
    assert(k.call(SearchPlanEntity.plan)(plan(both).copy(targetSources = Vector.empty)).isError)
    assertEquals(k.call(SearchPlanEntity.plan)(plan(both)).replyValue.version, 1)
    assertEquals(k.call(SearchPlanEntity.plan)(plan(both)).replyValue.version, 2)
    k.call(SearchPlanEntity.recordExecution)("rq-1:q4:plan:e1")
    k.call(SearchPlanEntity.recordExecution)("rq-1:q4:plan:e1")
    assertEquals(k.currentState.executionIds, Vector("rq-1:q4:plan:e1"))
    assertEquals(k.allEvents.size, 3)
    assertEquals(SearchPlan.questionOf("rq-1:q4:plan"), "rq-1:q4")
  }

  test("an execution is written once; eligibility is applied once, after it, with its exclusions") {
    val k = EventSourcedTestKit.of(SearchExecutionEntity, "rq-1:q4:plan:e1")
    val hit =
      Hit(1, 0.9, "src:file:retracted", 1, Some("src:file:retracted:v1:sec-1:1"), Some("sec-1"))
    assert(
      k.call(SearchExecutionEntity.applyEligibility)(
        ApplyEligibility(Vector.empty, Vector.empty, true, "eligibility@v1")
      ).isError
    )
    val first = k
      .call(SearchExecutionEntity.execute)(
        RecordExecution(
          Ref(RecordKind.search_plan, "rq-1:q4:plan", 1),
          "rq-1:q4",
          Some(both.head),
          false,
          "local",
          Map.empty,
          Vector(Ref(RecordKind.source, "src:file:retracted", 1)),
          Vector(hit),
          None,
          None,
          None
        )
      )
      .replyValue
    assertEquals(first.results, Vector(hit))
    val again = k
      .call(SearchExecutionEntity.execute)(
        RecordExecution(
          Ref(RecordKind.search_plan, "rq-1:q4:plan", 1),
          "rq-1:q4",
          None,
          true,
          "local",
          Map.empty,
          Vector.empty,
          Vector.empty,
          None,
          None,
          None
        )
      )
      .replyValue
    assertEquals(again.results, Vector(hit), "written once")
    val exclusion = Exclusion(
      "src:file:retracted",
      1,
      hit.chunkId,
      "the source is retracted",
      "eligibility.retracted@v1",
      ExclusionAction.excluded
    )
    val applied = k
      .call(SearchExecutionEntity.applyEligibility)(
        ApplyEligibility(Vector.empty, Vector(exclusion), true, "eligibility@v1")
      )
      .replyValue
    assert(applied.coverageGap && applied.eligibilityApplied)
    assertEquals(applied.exclusions, Vector(exclusion))
    k.call(SearchExecutionEntity.applyEligibility)(
      ApplyEligibility(Vector(hit), Vector.empty, false, "eligibility@v1")
    )
    assertEquals(k.currentState.eligibleResults, Vector.empty, "applied once")
    assertEquals(k.allEvents.size, 2)
  }
