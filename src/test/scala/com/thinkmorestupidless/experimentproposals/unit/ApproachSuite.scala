package com.thinkmorestupidless.experimentproposals.unit

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  DecisionEntity
}
import com.thinkmorestupidless.experimentproposals.application.workflows.InvestigationWorkflow
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

class ApproachSuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    Configured.reviewers = Reviewers.fromInline("ada:reviewer").toOption.get

  private val req = Ref(RecordKind.request, "rq-1", 1)
  private def content(title: String) = ApproachContent(
    title,
    "H",
    "R",
    Vector.empty,
    Vector.empty,
    Vector.empty,
    Vector(Ref(RecordKind.finding, "src:c:v1:fd1", 1)),
    Vector.empty,
    Vector("direct avian evidence"),
    "primary duck hepatocytes",
    Vector.empty,
    Vector("lipid"),
    Vector("Oil Red O"),
    Vector("vehicle"),
    Vector.empty,
    Vector.empty,
    Vector(
      Assumption("cells attach", EvidenceStatus.established),
      Assumption("stay lipogenic", EvidenceStatus.unknown)
    ),
    Vector.empty,
    Vector.empty,
    Vector.empty,
    Vector.empty
  )
  private def proposal(title: String) = ApproachProposal(
    title = title,
    hypothesis = "H",
    rationale = "R",
    biologicalModel = "M",
    endpoints = Vector("e"),
    measurements = Vector("m"),
    controls = Vector("c"),
    assumptions = Vector(AssumptionProposal("a", "unknown"))
  )
  private def criteria(titles: Vector[String]) =
    Comparison.CriterionNames.map(n =>
      CriterionProposal(n, titles.map(t => t -> RatingProposal("moderate", "because")).toMap)
    )

  test(
    "an approach is proposed once, carries no probability field, and is compared, selected or rejected with a reason"
  ) {
    val k = EventSourcedTestKit.of(ApproachEntity, "rq-1:c1")
    val a = k
      .call(ApproachEntity.propose)(
        ProposeApproach(req, Vector.empty, content("A"), "model:candidates@v1", Vector.empty)
      )
      .replyValue
    assertEquals(a.status, ApproachStatus.proposed)
    val json = writeToString(a)(using Codecs.make[ExperimentalApproach])
    assert(!json.toLowerCase.contains("probab"), json)
    assert(json.contains("\"evidenceStatus\":{\"type\":\"unknown\"}"), json)
    k.call(ApproachEntity.compared)("rq-1:comparison1")
    k.call(ApproachEntity.rejectedInDecision)(
      RejectedIn("ada", "rq-1:d1", "not the target species")
    )
    assertEquals(k.currentState.status, ApproachStatus.rejected)
    assertEquals(k.currentState.rejectionReason, Some("not the target species"))
    assert(
      k.call(ApproachEntity.selected)(SelectedIn("ada", "rq-1:d2")).isError,
      "already in a decision"
    )
    assert(k.call(ApproachEntity.rejectedInDecision)(RejectedIn("ada", "rq-1:d9", "")).isError)
  }

  test(
    "a decision is recorded once, by a configured reviewer, with a reason for every rejected approach"
  ) {
    val k = EventSourcedTestKit.of(DecisionEntity, "rq-1:d1")
    val comparison = Comparison(
      "rq-1:comparison1",
      Vector(Ref(RecordKind.approach, "rq-1:c1", 1), Ref(RecordKind.approach, "rq-1:c2", 1)),
      Vector.empty,
      "model",
      Instant.now()
    )
    def decide(by: String, reason: String, rationale: String = "the target species first") =
      Decide(
        req,
        comparison,
        Ref(RecordKind.approach, "rq-1:c1", 1),
        Vector(RejectedApproach(Ref(RecordKind.approach, "rq-1:c2", 1), reason)),
        by,
        rationale
      )
    assert(k.call(DecisionEntity.decide)(decide("nobody", "surrogate")).isError)
    assert(k.call(DecisionEntity.decide)(decide("ada", "")).isError)
    assert(k.call(DecisionEntity.decide)(decide("ada", "surrogate", "")).isError)
    assertEquals(k.allEvents.size, 0, "nothing persisted by a refusal")
    val d = k.call(DecisionEntity.decide)(decide("ada", "surrogate")).replyValue
    assertEquals(d.decidedBy, "ada")
    assertEquals(d.rejected.map(_.reason), Vector("surrogate"))
    assert(k.call(DecisionEntity.decide)(decide("ada", "again")).isError, "written once")
  }

  test(
    "the candidates gate: fewer than two, an unlabelled assumption, a missing criterion, an unrated approach"
  ) {
    val two = ApproachSet(Vector(proposal("A"), proposal("B")), criteria(Vector("A", "B")))
    assert(InvestigationWorkflow.gateCandidates(two).isRight)
    val one = InvestigationWorkflow.gateCandidates(
      ApproachSet(Vector(proposal("A")), criteria(Vector("A")))
    )
    assertEquals(one.left.map(_.kind), Left(DefectKind.fewer_than_two))
    assert(one.left.exists(_.retryable))
    val unlabelled = two.copy(approaches =
      Vector(
        proposal("A").copy(assumptions = Vector(AssumptionProposal("a", "probably"))),
        proposal("B")
      )
    )
    assertEquals(
      InvestigationWorkflow.gateCandidates(unlabelled).left.map(_.kind),
      Left(DefectKind.schema_invalid)
    )
    val missing = two.copy(comparison = two.comparison.tail)
    assert(
      InvestigationWorkflow
        .gateCandidates(missing)
        .left
        .exists(_.detail.contains("scientific_support"))
    )
    val unrated = two.copy(comparison = criteria(Vector("A")))
    assert(
      InvestigationWorkflow
        .gateCandidates(unrated)
        .left
        .exists(_.detail.contains("rate every approach"))
    )
    assertEquals(
      InvestigationWorkflow.candidatesMarker("Can you make foie gras?", Vector("b", "a")),
      InvestigationWorkflow.candidatesMarker("Can you make foie gras?", Vector("a", "b"))
    )
  }

  test("the no-probability guardrail refuses a percentage chance and passes a rating in words") {
    import com.thinkmorestupidless.experimentproposals.application.agents.DesignAgent
    assert(
      DesignAgent.noProbability.checkOutput("""{"rationale":"a 70% chance of success"}""").isLeft
    )
    assert(
      DesignAgent.noProbability.checkOutput("""{"rationale":"roughly 0.7 probability"}""").isRight,
      "only the percentage form is forbidden by the pattern"
    )
    assert(
      DesignAgent.noProbability
        .checkOutput("""{"rating":"moderate","rationale":"3.6-fold increase in Paper B"}""")
        .isRight
    )
  }
