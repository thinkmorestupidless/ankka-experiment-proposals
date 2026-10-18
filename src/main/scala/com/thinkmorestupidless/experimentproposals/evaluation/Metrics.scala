package com.thinkmorestupidless.experimentproposals.evaluation

/** One measurement: a ratio with its counts, or a note when nothing could be measured. */
final case class Measure(
    name: String,
    value: Option[Double],
    numerator: Int,
    denominator: Int,
    note: String
)

final case class StageMetrics(stage: String, measures: Vector[Measure])

/** A critical failure (FR-049): never averaged away, listed first, blocking. */
final case class CriticalFailure(caseId: String, kind: String, detail: String)

final case class CaseResult(
    caseId: String,
    ran: Boolean,
    facetOrigins: Map[String, String] = Map.empty,
    questions: Vector[String] = Vector.empty,
    relationships: Map[String, String] = Map.empty,
    applicability: Map[String, String] = Map.empty,
    sufficiency: Map[String, String] = Map.empty,
    retrieved: Vector[String] = Vector.empty,
    excluded: Vector[String] = Vector.empty,
    ineligibleLeaked: Int = 0,
    parseComplete: Int = 0,
    parsed: Int = 0,
    packagesValid: Int = 0,
    packages: Int = 0,
    criticalObserved: Vector[String] = Vector.empty,
    reason: String = ""
)

final case class PassageResult(
    passageId: String,
    ran: Boolean,
    observedLabel: Option[String],
    observedChecksFailed: Vector[String],
    observedDefect: Option[String],
    reason: String = ""
)

final case class RunResult(
    cases: Vector[(EvalCase, CaseResult)],
    passages: Vector[(Passage, PassageResult)]
)

/** Per-stage measurements (FR-048), each from the cases or passages that ran. */
object Metrics:
  private def ratio(name: String, num: Int, den: Int, note: String = ""): Measure =
    Measure(
      name,
      Option.when(den > 0)(num.toDouble / den),
      num,
      den,
      if den == 0 then "nothing measured" else note
    )

  private def accepted(expected: String, observed: String, alternatives: Vector[String]): Boolean =
    observed == expected || alternatives.contains(observed)

  def compute(run: RunResult): Vector[StageMetrics] =
    val ran = run.cases.filter(_._2.ran)
    def alts(c: EvalCase, key: String) =
      c.expected.acceptableAlternatives.getOrElse(key, Vector.empty)

    // requests
    val originPairs = ran.flatMap((c, r) =>
      c.expected.facetOrigins.toVector.map((name, exp) =>
        (exp, r.facetOrigins.get(name), alts(c, s"facet:$name"))
      )
    )
    val originHits      = originPairs.count((exp, obs, a) => obs.exists(o => accepted(exp, o, a)))
    val proposedOrigins = ran.map(_._2.facetOrigins.size).sum
    val expectedQuestions = ran.flatMap((c, r) =>
      c.expected.questions.map(q => r.questions.exists(_.toLowerCase.contains(q.toLowerCase)))
    )
    val requests = StageMetrics(
      "requests",
      Vector(
        ratio(
          "facet_precision",
          originHits,
          proposedOrigins,
          "facets proposed with the expected origin, over facets proposed"
        ),
        ratio(
          "facet_recall",
          originHits,
          originPairs.size,
          "expected facets found with their origin"
        ),
        ratio(
          "missed_questions",
          expectedQuestions.count(!_),
          expectedQuestions.size,
          "expected questions no derived question covers"
        )
      )
    )
    // ingestion
    val ingestion = StageMetrics(
      "ingestion",
      Vector(
        ratio(
          "parse_completeness",
          ran.map(_._2.parseComplete).sum,
          ran.map(_._2.parsed).sum,
          "fixtures parsed complete"
        )
      )
    )
    // retrieval
    val retrievalPairs =
      ran.flatMap((c, r) => c.expected.retrieved.map(s => r.retrieved.contains(s)))
    val ranks = ran.flatMap((c, r) =>
      c.expected.retrieved.flatMap(s =>
        Option(r.retrieved.indexOf(s)).filter(_ >= 0).map(i => 1.0 / (i + 1))
      )
    )
    val leaked = ran.map(_._2.ineligibleLeaked).sum
    val retrieval = StageMetrics(
      "retrieval",
      Vector(
        ratio(
          "retrieval_recall",
          retrievalPairs.count(identity),
          retrievalPairs.size,
          "expected sources among the eligible results"
        ),
        Measure(
          "ranking_quality_mrr",
          Option.when(ranks.nonEmpty)(ranks.sum / ranks.size),
          ranks.size,
          ranks.size,
          "mean reciprocal rank of the expected sources"
        ),
        ratio(
          "ineligible_evidence_leakage",
          leaked,
          ran.flatMap(_._2.retrieved).size + leaked,
          "ineligible sources among the eligible results"
        ),
        ratio(
          "contradictory_evidence_recall",
          ran.count((c, r) =>
            c.expected.relationships.values.exists(_ == "contradicts") && r.relationships.values
              .exists(_ == "contradicts")
          ),
          ran.count(_._1.expected.relationships.values.exists(_ == "contradicts")),
          "cases whose expected contradiction was retrieved and assessed"
        )
      )
    )
    // extraction (passages)
    val pr       = run.passages.filter(_._2.ran)
    val fidelity = pr.count((p, r) => r.observedLabel.contains(p.expectedLabel))
    val falseRejections = pr.count((p, r) =>
      p.expectedLabel == "entailed" && r.observedLabel.exists(l => l != "entailed")
    )
    val criticalExpected = pr.filter(_._1.expectedDefect.isDefined)
    val criticalCaught = criticalExpected.count((p, r) =>
      r.observedDefect == p.expectedDefect || p.failingChecks.forall(
        r.observedChecksFailed.contains
      )
    )
    val extraction = StageMetrics(
      "extraction",
      Vector(
        ratio(
          "extraction_fidelity",
          fidelity,
          pr.size,
          "passages whose label matched the expected one"
        ),
        ratio(
          "false_rejection_rate",
          falseRejections,
          pr.count(_._1.expectedLabel == "entailed"),
          "entailed passages that were rejected"
        ),
        ratio(
          "critical_error_recall",
          criticalCaught,
          criticalExpected.size,
          "passages with an expected critical defect that were caught"
        )
      )
    )
    // assessment
    val relPairs = ran.flatMap((c, r) =>
      c.expected.relationships.toVector.map((id, exp) =>
        (exp, r.relationships.get(id), alts(c, s"relationship:$id"))
      )
    )
    val appPairs = ran.flatMap((c, r) =>
      c.expected.applicability.toVector.map((id, exp) =>
        (exp, r.applicability.get(id), alts(c, s"applicability:$id"))
      )
    )
    val misclassified =
      relPairs.count((e, o, a) => o.exists(x => !accepted(e, x, a))) + appPairs.count((e, o, a) =>
        o.exists(x => !accepted(e, x, a))
      )
    val assessment = StageMetrics(
      "assessment",
      Vector(
        ratio(
          "assessment_misclassification",
          misclassified,
          relPairs.size + appPairs.size,
          "relationships or applicabilities outside the accepted labels"
        )
      )
    )
    // sufficiency
    val suffPairs = ran.flatMap((c, r) =>
      c.expected.sufficiency.toVector.map((d, exp) =>
        (exp, r.sufficiency.get(d), alts(c, s"sufficiency:$d"))
      )
    )
    val unsafe = suffPairs.count((e, o, _) =>
      o.contains("sufficient_for_proposed_next_step") && e == "insufficient_for_recommendation"
    )
    val abstainExpected = suffPairs.filter((e, _, _) =>
      e == "insufficient_for_recommendation" || e == "requires_expert_review"
    )
    val abstained = abstainExpected.count((_, o, _) =>
      o.exists(x => x == "insufficient_for_recommendation" || x == "requires_expert_review")
    )
    val sufficiency = StageMetrics(
      "sufficiency",
      Vector(
        ratio(
          "unsafe_recommendation_rate",
          unsafe,
          suffPairs.size,
          "sufficient where insufficiency was expected"
        ),
        ratio(
          "appropriate_abstention",
          abstained,
          abstainExpected.size,
          "abstained where abstention was expected"
        )
      )
    )
    // packages and review
    val packages = StageMetrics(
      "packages",
      Vector(
        ratio(
          "package_coverage",
          ran.map(_._2.packagesValid).sum,
          ran.map(_._2.packages).sum,
          "packages valid over packages built"
        ),
        Measure(
          "reviewer_acceptance",
          None,
          0,
          0,
          "not measured: every review in the demonstration is by the stand-in reviewer"
        )
      )
    )
    Vector(requests, ingestion, retrieval, extraction, assessment, sufficiency, packages)

  /** Critical failures observed in a run (FR-049). */
  def critical(run: RunResult): Vector[CriticalFailure] =
    run.cases.flatMap((c, r) =>
      r.criticalObserved.map(k => CriticalFailure(c.id, k, "observed in the run"))
    ) ++
      run.cases
        .filter(_._2.ran)
        .flatMap((c, r) =>
          c.expected.sufficiency.toVector.collect {
            case (d, "insufficient_for_recommendation")
                if r.sufficiency.get(d).contains("sufficient_for_proposed_next_step") =>
              CriticalFailure(
                c.id,
                "unsafe_recommendation",
                s"$d judged sufficient where the label says insufficient"
              )
          }
        ) ++
      run.cases.filter(_._2.ran).collect {
        case (c, r) if r.ineligibleLeaked > 0 =>
          CriticalFailure(
            c.id,
            "ineligible_evidence_leakage",
            s"${r.ineligibleLeaked} ineligible source(s) among eligible results"
          )
      } ++
      run.passages.filter(_._2.ran).collect {
        case (p, r) if p.expectedDefect.isDefined && r.observedLabel.contains("entailed") =>
          CriticalFailure(
            p.id,
            "unsupported_assertion_admitted",
            s"${p.expectedDefect.get} not caught"
          )
      }
