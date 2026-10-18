package com.thinkmorestupidless.experimentproposals.evaluation

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * What an end-to-end case expects; a label may admit alternatives and say it is uncertain (FR-047).
 */
final case class CaseExpectation(
    facetOrigins: Map[String, String] = Map.empty,
    questions: Vector[String] = Vector.empty,
    relationships: Map[String, String] = Map.empty,
    applicability: Map[String, String] = Map.empty,
    sufficiency: Map[String, String] = Map.empty,
    criticalFailures: Vector[String] = Vector.empty,
    retrieved: Vector[String] = Vector.empty,
    excluded: Vector[String] = Vector.empty,
    acceptableAlternatives: Map[String, Vector[String]] = Map.empty,
    uncertain: Boolean = false,
    notes: String = ""
)

final case class EvalCase(
    id: String,
    category: String,
    request: String,
    fixtures: Vector[String],
    expected: CaseExpectation,
    labelledBy: String,
    runnable: Boolean = false
)

final case class ProposedExtraction(
    kind: String,
    quotation: String,
    species: Option[String] = None,
    endpoint: Option[String] = None,
    outcome: Option[String] = None,
    proposition: Option[String] = None
)

final case class Passage(
    id: String,
    category: String,
    source: String,
    text: String,
    proposed: ProposedExtraction,
    expectedLabel: String,
    failingChecks: Vector[String] = Vector.empty,
    expectedDefect: Option[String] = None,
    uncertain: Boolean = false,
    labelledBy: String
)

/**
 * The labelled files under `evals/` (data-model.md, "Evaluation data") and what they must satisfy.
 */
object Dataset:
  val Categories: Vector[String] = Vector(
    "species_mismatch",
    "negation",
    "proliferation_vs_lipid_accumulation",
    "negative_result",
    "superseded_protocol",
    "retracted_paper",
    "hypothesis_as_result",
    "correlation_vs_causation",
    "unit_misconverted",
    "absent_table",
    "resource_unavailability",
    "insufficient_evidence"
  )
  val Labels: Set[String] =
    Set("entailed", "contradicted", "insufficiently_supported", "ambiguous", "rejected_by_gate")
  val Checks: Set[String] = Set("negation", "scope", "species", "treatment", "endpoint", "span")
  val MinCases            = 20
  val MinPassages         = 30

  private given JsonValueCodec[EvalCase] = Codecs.make[EvalCase]
  private given JsonValueCodec[Passage]  = Codecs.make[Passage]

  private def files(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      Files
        .list(dir)
        .iterator
        .asScala
        .toVector
        .filter(_.toString.endsWith(".json"))
        .sortBy(_.getFileName.toString)

  def loadCases(dir: Path): Vector[EvalCase] =
    files(dir.resolve("cases")).map(p => readFromString[EvalCase](Files.readString(p)))
  def loadPassages(dir: Path): Vector[Passage] =
    files(dir.resolve("passages")).map(p => readFromString[Passage](Files.readString(p)))

  /** The dataset, or every problem with it (FR-047, SC-012). */
  def load(dir: Path): Either[Vector[String], (Vector[EvalCase], Vector[Passage])] =
    val (cases, passages) =
      try (loadCases(dir), loadPassages(dir))
      catch
        case e: Exception =>
          return Left(Vector(s"a file under $dir does not parse: ${e.getMessage}"))
    val problems = Vector.newBuilder[String]
    if cases.size < MinCases then
      problems += s"${cases.size} case(s); at least $MinCases are required"
    if passages.size < MinPassages then
      problems += s"${passages.size} passage(s); at least $MinPassages are required"
    val missing = Categories.filterNot(c => cases.exists(_.category == c))
    if missing.nonEmpty then problems += s"categories with no case: ${missing.mkString(", ")}"
    cases
      .filterNot(c => Categories.contains(c.category))
      .foreach(c => problems += s"case ${c.id}: unknown category '${c.category}'")
    cases.filter(_.labelledBy.isBlank).foreach(c => problems += s"case ${c.id}: no labeller")
    cases
      .groupBy(_.id)
      .collect { case (id, cs) if cs.size > 1 => id }
      .foreach(id => problems += s"case id '$id' is used twice")
    passages
      .filterNot(p => Labels.contains(p.expectedLabel))
      .foreach(p => problems += s"passage ${p.id}: unknown label '${p.expectedLabel}'")
    passages
      .flatMap(p =>
        p.failingChecks.filterNot(Checks.contains).map(c => s"passage ${p.id}: unknown check '$c'")
      )
      .foreach(problems += _)
    passages
      .filter(_.proposed.quotation.isBlank)
      .foreach(p => problems += s"passage ${p.id}: no quotation")
    val out = problems.result()
    if out.isEmpty then Right((cases, passages)) else Left(out)
