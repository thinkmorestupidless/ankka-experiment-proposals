package com.thinkmorestupidless.experimentproposals.evaluation

import com.thinkmorestupidless.experimentproposals.adapters.jats.JatsParser
import com.thinkmorestupidless.experimentproposals.application.agents.{
  EvidenceAgent,
  RequestAgent,
  SearchAgent
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.experimentproposals.rules.{
  ApplicabilityRules,
  EligibilityRules,
  PackageRules
}

import java.nio.file.{Files, Path}

/**
 * The evaluation that needs no running service: the dataset's validity, every passage through the
 * deterministic gate against the fixture it names, the pins, and the report. The service-backed
 * part (the runnable cases) is the evaluation suite, which adds its case results to this.
 */
object Evaluation:
  def pins(tier: String, embedding: String): Map[String, String] = Map(
    "tier"                -> tier,
    "prompt.facets"       -> RequestAgent.facetsPrompt.promptVersion,
    "prompt.extract"      -> EvidenceAgent.extractPrompt.promptVersion,
    "prompt.verify"       -> EvidenceAgent.verifyPrompt.promptVersion,
    "prompt.assess"       -> EvidenceAgent.assessPrompt.promptVersion,
    "prompt.plan"         -> SearchAgent.planPrompt.promptVersion,
    "rules.eligibility"   -> EligibilityRules.ruleSet.key,
    "rules.applicability" -> ApplicabilityRules.ruleSet.key,
    "rules.package"       -> PackageRules.ruleSet.key,
    "parser"              -> JatsParser.Version,
    "embedding"           -> embedding
  )

  /** A passage through the deterministic gate: does the quotation it cites exist in the source? */
  def gate(p: Passage, fixtures: Path): PassageResult =
    val file =
      Seq(s"${p.source}.xml", s"${p.source}.txt").map(fixtures.resolve).find(Files.exists(_))
    file match
      case None =>
        PassageResult(p.id, ran = false, None, Vector.empty, None, s"no fixture ${p.source}")
      case Some(f) =>
        val text = Files.readString(f)
        val sections =
          if f.toString.endsWith(".xml") then JatsParser.parse(text).sections
          else JatsParser.plainText(text).sections
        val found = sections.exists(s =>
          SourceSpan.normalise(s.text).contains(SourceSpan.normalise(p.proposed.quotation))
        )
        if !found then
          PassageResult(
            p.id,
            ran = true,
            Some("rejected_by_gate"),
            Vector("span"),
            Some(DefectKind.span_unresolved.toString)
          )
        else if p.expectedLabel == "rejected_by_gate" then
          PassageResult(
            p.id,
            ran = true,
            Some("entailed"),
            Vector.empty,
            None,
            "the quotation resolves; the gate did not reject it"
          )
        else
          PassageResult(
            p.id,
            ran = false,
            None,
            Vector.empty,
            None,
            "the quotation resolves; the verifier's reply for this passage is not recorded"
          )

  def offline(
      evals: Path,
      fixtures: Path,
      tier: String = "replay",
      embedding: String = "hash"
  ): Report =
    val (cases, passages) = Dataset
      .load(evals)
      .fold(problems => throw IllegalStateException(problems.mkString("; ")), identity)
    val run = RunResult(
      cases.map(c =>
        c -> CaseResult(
          c.id,
          ran = false,
          reason =
            if c.runnable then "the service-backed suite did not run"
            else "no recorded model replies for this request"
        )
      ),
      passages.map(p => p -> gate(p, fixtures))
    )
    Report.build(run, tier, pins(tier, embedding), None, Vector.empty)

  def write(report: Report, dir: Path): (Path, Path) = Report.write(report, dir)

/** `evalCompare <report.json>` and `evalValidate`: what `just` runs without a service. */
@main def evalRun(args: String*): Unit =
  args.toList match
    case "compare" :: report :: _ =>
      println(
        Baseline.render(
          Baseline.compare(Report.read(Path.of(report)), Path.of("evals/baseline.json"))
        )
      )
    case "validate" :: _ =>
      Dataset.load(Path.of("evals")) match
        case Left(problems) => problems.foreach(System.err.println); sys.exit(1)
        case Right((c, p)) =>
          println(s"${c.size} cases, ${p.size} passages; every category covered")
    case "offline" :: _ =>
      val (json, md) = Evaluation.write(
        Evaluation.offline(Path.of("evals"), Path.of("evals/fixtures/papers")),
        Path.of("evals/reports")
      )
      println(s"wrote $json and $md")
    case _ =>
      System.err.println("usage: evalRun compare <report.json> | validate | offline")
      sys.exit(2)
