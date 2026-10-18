package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.{
  AssessmentEntity,
  GapEntity,
  SufficiencyEntity
}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, Reviewers}
import com.thinkmorestupidless.experimentproposals.rules.ApplicabilityRules
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

class AssessmentSuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    Configured.reviewers = Reviewers.fromInline("ada:reviewer").toOption.get
    Configured.supplementalBudget = 2

  private val q = Ref(RecordKind.question, "rq-1:q4", 1)
  private val r = Ref(RecordKind.finding, "src:file:paper-b:v1:fd1", 1)
  private def proposal(app: String, species: String, endpoint: String = "match") =
    AssessmentProposal(
      r.id,
      "supports",
      app,
      Map(
        "species"   -> species,
        "cellModel" -> "match",
        "mechanism" -> "match",
        "endpoint"  -> endpoint,
        "condition" -> "match"
      ),
      "because",
      Vector("x")
    )

  test(
    "the species rule rewrites direct to indirect and records it; an endpoint mismatch does too; unknown species is unknown"
  ) {
    val b = ApplicabilityRules.apply(proposal("direct", "mismatch")).toOption.get
    assertEquals(b.applicability, Applicability.indirect)
    assert(
      b.ruleResults.exists(rr =>
        rr.ruleId == ApplicabilityRules.speciesRule.key && rr.outcome == "adjusted"
      )
    )
    assertEquals(
      ApplicabilityRules.apply(proposal("direct", "match", "mismatch")).toOption.get.applicability,
      Applicability.indirect
    )
    assertEquals(
      ApplicabilityRules.apply(proposal("direct", "unknown")).toOption.get.applicability,
      Applicability.unknown
    )
    assertEquals(
      ApplicabilityRules.apply(proposal("direct", "match")).toOption.get.applicability,
      Applicability.direct
    )
    assert(ApplicabilityRules.apply(proposal("sideways", "match")).isLeft)
  }

  test(
    "the assessment entity refuses direct across a species mismatch, keeps no score, and a listed reviewer verifies"
  ) {
    val k = EventSourcedTestKit.of(AssessmentEntity, "rq-1:q4:src:file:paper-b:v1:fd1")
    val bad = ProposeAssessment(
      q,
      r,
      Relationship.supports,
      Applicability.direct,
      Map("species" -> DimensionMatch.mismatch),
      "r",
      Vector.empty,
      "m",
      Vector.empty,
      Vector(q, r)
    )
    assert(k.call(AssessmentEntity.propose)(bad).isError)
    k.call(AssessmentEntity.propose)(bad.copy(applicability = Applicability.indirect))
    assertEquals(k.currentState.applicability, Applicability.indirect)
    assert(
      !com.github.plokhotnyuk.jsoniter_scala.core
        .writeToString(k.currentState)(using
          com.thinkmorestupidless.ankka.core.Codecs.make[EvidenceAssessment]
        )
        .contains("score")
    )
    assert(k.call(AssessmentEntity.review)(ReviewRecord("verify", "mallory", "x")).isError)
    assertEquals(
      k.call(AssessmentEntity.review)(ReviewRecord("verify", "ada", "agreed"))
        .replyValue
        .reviewStatus,
      RecordStatus.verified
    )
  }

  test(
    "a sufficiency judgement is a version per decision, and a gap's supplemental budget is bounded"
  ) {
    val k = EventSourcedTestKit.of(
      SufficiencyEntity,
      SufficiencyEntity.idFor("rq-1:q4", "recommend production feasibility")
    )
    assertEquals(
      SufficiencyEntity.idFor("rq-1:q4", "Recommend production feasibility!"),
      "rq-1:q4:recommend-production-feasibility"
    )
    val judge = JudgeSufficiency(
      q,
      "recommend production feasibility",
      "not established",
      SufficiencyStatus.insufficient_for_recommendation,
      Vector.empty,
      Vector.empty,
      Vector("rq-1:q4:g1"),
      CoverageJudgement.mixed,
      "r",
      Escalation.exploratory_question,
      "m",
      Vector(q)
    )
    k.call(SufficiencyEntity.judge)(judge)
    k.call(SufficiencyEntity.judge)(
      judge.copy(status = SufficiencyStatus.plausible_but_unverified, proposition = "maybe")
    )
    assertEquals(k.currentState.version, 2)
    assertEquals(k.currentState.status, SufficiencyStatus.plausible_but_unverified)
    val g = EventSourcedTestKit.of(GapEntity, "rq-1:q4:g1")
    g.call(GapEntity.open)(
      OpenGap(q, "no avian lipid data", GapKind.absence, Escalation.supplemental_search)
    )
    g.call(GapEntity.recordSupplemental)("e1"); g.call(GapEntity.recordSupplemental)("e2")
    assert(g.call(GapEntity.recordSupplemental)("e3").isError)
    assertEquals(g.currentState.supplementalExecutionIds, Vector("e1", "e2"))
  }

  test(
    "the sufficiency reply is parsed into its closed lists and a sufficient status must cite assessments"
  ) {
    val ok = SufficiencyProposal(
      "p",
      "sufficient_for_proposed_next_step",
      Vector("a"),
      Vector("as1"),
      Vector(GapProposal("g", "absence", "exploratory_question")),
      "mixed",
      "r",
      "none"
    )
    assert(ApplicabilityRules.parseSufficiency(ok).isRight)
    assert(
      ApplicabilityRules.parseSufficiency(ok.copy(supportingAssessments = Vector.empty)).isLeft
    )
    assert(ApplicabilityRules.parseSufficiency(ok.copy(coverageJudgement = "dunno")).isLeft)
    assert(
      ApplicabilityRules
        .parseSufficiency(
          ok.copy(status = "insufficient_for_recommendation", supportingAssessments = Vector.empty)
        )
        .isRight
    )
  }
