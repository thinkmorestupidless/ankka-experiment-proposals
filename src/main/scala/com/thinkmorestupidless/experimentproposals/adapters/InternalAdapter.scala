package com.thinkmorestupidless.experimentproposals.adapters

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.{
  InternalDetails,
  Measurement,
  ParseIssue,
  SourceRecord
}
import com.thinkmorestupidless.experimentproposals.rules.Units
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** One CSV file the manifest describes: which columns carry what. */
final case class InternalFile(
    file: String,
    kind: String = "internal_measurement",
    idColumn: String,
    quantityColumn: String,
    valueColumn: String,
    unitColumn: String,
    recordedAtColumn: Option[String] = None,
    methodColumn: Option[String] = None,
    conditionColumns: Vector[String] = Vector.empty
)

/**
 * `manifest.json` (research.md R7): the source system, who is responsible, the protocol, the files.
 */
final case class InternalManifest(
    sourceSystem: String,
    responsibleResearcher: String,
    protocolVersion: Option[String] = None,
    files: Vector[InternalFile] = Vector.empty
)

/**
 * Internal records from a local, ignored directory (`EDP_INTERNAL_DIR`): a manifest names CSV
 * files; each file becomes one source of an internal kind whose rows are measurements with their
 * unit validated against the unit table and never converted (FR-010 to FR-012). The identifier of a
 * file is `<manifest path>::<file>`; `records(manifest)` lists them so the API can start one
 * ingestion each.
 */
final class InternalAdapter(root: Path) extends Adapter:
  private given JsonValueCodec[InternalManifest] = Codecs.make[InternalManifest]

  def name: String = "internal"

  /** Every manifest under the root, every file in it. */
  def search(query: String, filters: Map[String, String]): Vector[SourceRef] =
    if !Files.isDirectory(root) then Vector.empty
    else
      Files
        .walk(root)
        .iterator
        .asScala
        .filter(p => p.getFileName.toString == "manifest.json")
        .toVector
        .sorted
        .flatMap { m =>
          val manifest = read(m)
          manifest.files
            .filter(f =>
              query.isBlank || f.file.toLowerCase.contains(
                query.toLowerCase
              ) || manifest.sourceSystem.toLowerCase.contains(query.toLowerCase)
            )
            .map(f => SourceRef(s"$m::${f.file}", s"${manifest.sourceSystem}: ${f.file}"))
        }

  /** The identifiers of every file a manifest names. */
  def records(manifestPath: String): Vector[String] =
    val m = resolve(manifestPath)
    read(m).files.map(f => s"$m::${f.file}")

  def fetch(identifier: String): SourceRecord =
    val (manifestPath, file) = identifier.split("::", 2) match
      case Array(m, f) => (m, f)
      case _           => throw AdapterPermanent(s"'$identifier' is not <manifest>::<file>")
    val m        = resolve(manifestPath)
    val manifest = read(m)
    val spec = manifest.files
      .find(_.file == file)
      .getOrElse(throw AdapterPermanent(s"$m names no file '$file'"))
    val csvPath = m.resolveSibling(spec.file)
    if !Files.exists(csvPath) then throw AdapterPermanent(s"no file at $csvPath")
    val csv = Files.readString(csvPath)
    val kind = SourceKind.values
      .find(_.toString == spec.kind)
      .getOrElse(throw AdapterPermanent(s"unknown kind '${spec.kind}' in $m"))
    val stem                   = spec.file.stripSuffix(".csv")
    val id                     = s"internal:${manifest.sourceSystem}:$stem"
    val (measurements, issues) = InternalAdapter.measurements(id, spec, csv)
    SourceRecord(
      identifier = id,
      identifiers = Map("internalId" -> s"${manifest.sourceSystem}/${spec.file}"),
      title = s"${manifest.sourceSystem} ${spec.kind.replace('_', ' ')}: ${spec.file}",
      authors = Vector(manifest.responsibleResearcher),
      publishedAt = measurements.flatMap(_.recordedAt).maxOption,
      venue = Some(manifest.sourceSystem),
      sourceUrl = Some(csvPath.toUri.toString),
      licence = Some("internal"),
      status = SourceStatus.active,
      kind = kind,
      internal = Some(
        InternalDetails(
          sourceSystem = manifest.sourceSystem,
          originalId = spec.file,
          responsibleResearcher = manifest.responsibleResearcher,
          recordedAt = measurements.flatMap(_.recordedAt).maxOption,
          protocolVersion = manifest.protocolVersion,
          conditions = Map.empty
        )
      ),
      content = Some(csv),
      format = "text",
      abstractText = Some(
        s"${measurements.size} ${spec.kind.replace('_', ' ')} rows from ${manifest.sourceSystem}" +
          manifest.protocolVersion.fold("")(p => s", protocol $p") +
          s", responsible researcher ${manifest.responsibleResearcher}."
      ),
      contentHash = Adapter.sha256(csv),
      sourceVersion = manifest.protocolVersion,
      measurements = measurements,
      issues = issues
    )

  private def resolve(manifestPath: String): Path =
    val direct = Path.of(manifestPath)
    val m      = if Files.exists(direct) then direct else root.resolve(manifestPath)
    if !Files.exists(m) then throw AdapterPermanent(s"no manifest at $m")
    m.toAbsolutePath.normalize

  private def read(m: Path): InternalManifest =
    try readFromString[InternalManifest](Files.readString(m))
    catch
      case e: Exception => throw AdapterPermanent(s"$m is not a manifest: ${e.getMessage}", Some(e))

object InternalAdapter:

  /** RFC 4180 enough: quoted fields, doubled quotes, no embedded newlines. */
  def parseCsv(text: String): Vector[Vector[String]] =
    text.linesIterator.filter(_.trim.nonEmpty).map(parseLine).toVector

  private def parseLine(line: String): Vector[String] =
    val out      = Vector.newBuilder[String]
    val cell     = new StringBuilder
    var inQuotes = false
    var i        = 0
    while i < line.length do
      val c = line.charAt(i)
      if inQuotes then
        if c == '"' && i + 1 < line.length && line.charAt(i + 1) == '"' then { cell += '"'; i += 1 }
        else if c == '"' then inQuotes = false
        else cell += c
      else if c == '"' then inQuotes = true
      else if c == ',' then { out += cell.result(); cell.clear() }
      else cell += c
      i += 1
    out += cell.result()
    out.result()

  /** Rows to measurements; a bad unit is kept with `unitValid = false`, a bad value is an issue. */
  def measurements(
      sourceIdentifier: String,
      spec: InternalFile,
      csv: String
  ): (Vector[Measurement], Vector[ParseIssue]) =
    val rows = parseCsv(csv)
    if rows.isEmpty then
      return (Vector.empty, Vector(ParseIssue("empty", spec.file, "the file has no rows")))
    val header                    = rows.head.map(_.trim)
    val issues                    = Vector.newBuilder[ParseIssue]
    def column(name: String): Int = header.indexOf(name)
    val required = Seq(spec.idColumn, spec.quantityColumn, spec.valueColumn, spec.unitColumn)
    val missing  = required.filter(column(_) < 0)
    if missing.nonEmpty then
      return (
        Vector.empty,
        Vector(
          ParseIssue("columns_missing", spec.file, s"the header lacks ${missing.mkString(", ")}")
        )
      )
    val lines = csv.linesIterator.filter(_.trim.nonEmpty).toVector
    val out = rows.tail.zipWithIndex.flatMap { case (row, i) =>
      def cell(name: String): Option[String] =
        val c = column(name)
        if c >= 0 && c < row.length then Some(row(c).trim).filter(_.nonEmpty) else None
      val rowId = cell(spec.idColumn).getOrElse(s"row-${i + 2}")
      val unit  = cell(spec.unitColumn).getOrElse("")
      val valid = unit.nonEmpty && Units.valid(unit)
      if !valid then
        issues += ParseIssue(
          "unit_invalid",
          rowId,
          s"'$unit' is not in the unit table; the value is kept as written and not converted"
        )
      cell(spec.valueColumn).flatMap(v => scala.util.Try(BigDecimal(v)).toOption) match
        case None =>
          issues += ParseIssue(
            "value_invalid",
            rowId,
            s"'${cell(spec.valueColumn).getOrElse("")}' is not a number; the row is kept in the text and not a measurement"
          )
          None
        case Some(value) =>
          Some(
            Measurement(
              measurementId = s"$sourceIdentifier:$rowId",
              quantityKind = cell(spec.quantityColumn).getOrElse("unknown"),
              value = value,
              unit = unit,
              unitValid = valid,
              method = spec.methodColumn.flatMap(cell),
              conditions = spec.conditionColumns.flatMap(c => cell(c).map(c -> _)).toMap,
              qualityFlag = QualityFlag.ok,
              rawText = lines.lift(i + 1).getOrElse(row.mkString(",")),
              recordedAt = spec.recordedAtColumn.flatMap(cell)
            )
          )
    }
    (out, issues.result())
