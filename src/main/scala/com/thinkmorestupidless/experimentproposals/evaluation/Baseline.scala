package com.thinkmorestupidless.experimentproposals.evaluation

import java.nio.file.{Files, Path}

/** Comparison against the agreed baseline (FR-050); without one there is nothing to compare to. */
object Baseline:
  final case class Change(
      stage: String,
      measure: String,
      baseline: Option[Double],
      now: Option[Double]
  ):
    def worse: Boolean = (baseline, now) match
      case (Some(b), Some(n)) => n < b - 1e-9
      case _                  => false

  def compare(report: Report, baselinePath: Path): Either[String, Vector[Change]] =
    if !Files.exists(baselinePath) then Left("no baseline yet")
    else
      val base = Report.read(baselinePath)
      val changes = for
        s  <- report.stages
        m  <- s.measures
        bs <- base.stages.find(_.stage == s.stage).toVector
        bm <- bs.measures.find(_.name == m.name).toVector
      yield Change(s.stage, m.name, bm.value, m.value)
      Right(changes)

  def render(result: Either[String, Vector[Change]]): String = result match
    case Left(message) => message
    case Right(changes) =>
      val lines = changes.map(c =>
        s"${c.stage}.${c.measure}: ${c.baseline.fold("—")(v => f"$v%.2f")} → ${c.now
            .fold("—")(v => f"$v%.2f")}${if c.worse then "  WORSE" else ""}"
      )
      (if changes.exists(_.worse) then "regressions against the baseline:\n"
       else "no regression against the baseline:\n") + lines.mkString("\n")
