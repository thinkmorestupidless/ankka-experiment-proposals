package com.thinkmorestupidless.experimentproposals.evaluation

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString,
  JsonValueCodec,
  WriterConfig
}
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

final case class FeatureResults(passed: Int, failed: Int, ignored: Int, drift: Vector[String])

/** The evaluation report (SC-006): critical failures first, per-stage sections, limitations. */
final case class Report(
    generatedAt: String,
    tier: String,
    pins: Map[String, String],
    blocking: Boolean,
    critical: Vector[CriticalFailure],
    stages: Vector[StageMetrics],
    features: Option[FeatureResults],
    casesRun: Vector[String],
    casesNotRun: Vector[String],
    passagesRun: Int,
    passagesNotRun: Int,
    limitations: Vector[String],
    baseline: Option[String]
)

object Report:
  given codec: JsonValueCodec[Report] = Codecs.make[Report]

  def build(
      run: RunResult,
      tier: String,
      pins: Map[String, String],
      features: Option[FeatureResults],
      extraLimitations: Vector[String]
  ): Report =
    val critical = Metrics.critical(run)
    val notRun   = run.cases.filterNot(_._2.ran)
    val limitations = Vector(
      "The model replies in the replay tier are authored or recorded cassettes, not a live model; drift against the live model is measured separately (FR-054).",
      "Every reviewer decision in the demonstration is by a stand-in reviewer, not a named scientific reviewer; reviewer acceptance is therefore not measured.",
      "Passages are evaluated through the deterministic gate and the recorded verification replies; a passage with no recorded reply for its exact content is not run."
    ) ++ Option.when(notRun.nonEmpty)(
      s"${notRun.size} of ${run.cases.size} case(s) did not run: ${notRun.map(_._1.id).mkString(", ")} (${notRun.map(_._2.reason).distinct.mkString("; ")})."
    ) ++ extraLimitations
    Report(
      Instant.now().toString,
      tier,
      pins,
      blocking = critical.nonEmpty,
      critical,
      Metrics.compute(run),
      features,
      run.cases.filter(_._2.ran).map(_._1.id),
      notRun.map(_._1.id),
      run.passages.count(_._2.ran),
      run.passages.count(!_._2.ran),
      limitations,
      None
    )

  private def pct(m: Measure): String =
    m.value.fold("—")(v => f"${v * 100}%.0f%% (${m.numerator}/${m.denominator})")

  def markdown(r: Report): String =
    val critical =
      if r.critical.isEmpty then "None observed. The run is not blocked by this category."
      else
        r.critical
          .map(c => s"- **${c.kind}** in `${c.caseId}`: ${c.detail}")
          .mkString(
            "\n"
          ) + "\n\n**Blocking: no release passes on the aggregate scores below while this list is not empty (FR-049).**"
    val stages = r.stages
      .map { s =>
        val rows = s.measures.map(m => s"| ${m.name} | ${pct(m)} | ${m.note} |").mkString("\n")
        s"## ${s.stage.capitalize}\n\n| Measure | Value | Note |\n|---|---|---|\n$rows\n"
      }
      .mkString("\n")
    val features = r.features.fold("Not part of this run.")(f =>
      s"${f.passed} passed, ${f.failed} failed, ${f.ignored} ignored." + (if f.drift.isEmpty then ""
                                                                          else
                                                                            s" Drift (replay pass, live failure): ${f.drift.mkString(", ")}")
    )
    s"""# Evaluation report
       |
       |Generated ${r.generatedAt} in the ${r.tier} tier.
       |
       |Pinned: ${r.pins.toVector.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")}
       |
       |## Critical failures (blocking)
       |
       |$critical
       |
       |$stages
       |## Living features
       |
       |$features
       |
       |## Cases
       |
       |Ran: ${if r.casesRun.isEmpty then "none" else r.casesRun.mkString(", ")}
       |Not run: ${if r.casesNotRun.isEmpty then "none" else r.casesNotRun.mkString(", ")}
       |Passages: ${r.passagesRun} ran, ${r.passagesNotRun} not run
       |
       |## Baseline
       |
       |${r.baseline.getOrElse(
        "No baseline yet: `evals/baseline.json` is written once a reviewer has read a report and agreed the thresholds."
      )}
       |
       |## Limitations
       |
       |${r.limitations.map("- " + _).mkString("\n")}
       |""".stripMargin

  /** Writes `<dir>/<timestamp>.json` and `.md`; answers both paths. */
  def write(r: Report, dir: Path): (Path, Path) =
    Files.createDirectories(dir)
    val stamp = r.generatedAt.replaceAll("[:.]", "-")
    val json  = dir.resolve(s"$stamp.json")
    val md    = dir.resolve(s"$stamp.md")
    Files.writeString(json, writeToString(r, WriterConfig.withIndentionStep(2)) + "\n")
    Files.writeString(md, markdown(r))
    (json, md)

  def read(path: Path): Report = readFromString[Report](Files.readString(path))

  /** The newest report under the directory, by name (the name is the timestamp). */
  def latest(dir: Path): Option[Path] =
    if !Files.isDirectory(dir) then None
    else
      Files
        .list(dir)
        .iterator
        .asScala
        .toVector
        .filter(_.toString.endsWith(".json"))
        .filterNot(_.getFileName.toString == "drift.json")
        .sortBy(_.getFileName.toString)
        .lastOption
