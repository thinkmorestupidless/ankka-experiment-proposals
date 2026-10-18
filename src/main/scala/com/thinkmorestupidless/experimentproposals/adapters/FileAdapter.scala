package com.thinkmorestupidless.experimentproposals.adapters

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceRecord
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.{Files, Path}

/** `<name>.meta.json` beside a fixture: what an index would have said about it. */
final case class FileMeta(
    identifier: String,
    identifiers: Map[String, String] = Map.empty,
    title: String,
    authors: Vector[String] = Vector.empty,
    publishedAt: Option[String] = None,
    venue: Option[String] = None,
    licence: Option[String] = None,
    status: String = "active",
    kind: String = "literature",
    `abstract`: Option[String] = None,
    /**
     * When true the file's text is not retained: the record is abstract-only, as a failed fetch.
     */
    abstractOnly: Boolean = false,
    sourceVersion: Option[String] = None
)

/**
 * Local JATS XML or plain text files with a `.meta.json` beside them: the fixtures, and anything a
 * person downloaded under institutional access. The adapter has no index to search.
 */
final class FileAdapter(root: Path) extends Adapter:
  private given JsonValueCodec[FileMeta] = Codecs.make[FileMeta]

  def name: String = "file"

  def search(query: String, filters: Map[String, String]): Vector[SourceRef] =
    if !Files.isDirectory(root) then Vector.empty
    else
      import scala.jdk.CollectionConverters.*
      Files
        .list(root)
        .iterator
        .asScala
        .toVector
        .filter(p => p.toString.endsWith(".meta.json"))
        .map(p => readFromString[FileMeta](Files.readString(p)))
        .filter(m => query.isBlank || m.title.toLowerCase.contains(query.toLowerCase))
        .map(m => SourceRef(m.identifier, m.title))
        .sortBy(_.identifier)

  /**
   * The identifier is the file's path, absolute or relative to the adapter's root. `<path>@<alias>`
   * fetches the same file as a distinct source (`file:<name>@<alias>`): a scenario that alters an
   * extraction works on its own copy and leaves the shared fixture untouched.
   */
  def fetch(identifier: String): SourceRecord =
    val (path, alias) = identifier.lastIndexOf('@') match
      case -1 => (identifier, None)
      case i  => (identifier.substring(0, i), Some(identifier.substring(i + 1)).filter(_.nonEmpty))
    val file =
      Option(Path.of(path)).filter(Files.exists(_)).getOrElse(root.resolve(path))
    if !Files.exists(file) then throw AdapterPermanent(s"no file at $file")
    val metaFile = file.resolveSibling(stripExtension(file.getFileName.toString) + ".meta.json")
    if !Files.exists(metaFile) then
      throw AdapterPermanent(s"no metadata beside $file (expected $metaFile)")
    val meta   = readFromString[FileMeta](Files.readString(metaFile))
    val text   = Files.readString(file)
    val format = if file.toString.endsWith(".xml") then "jats" else "text"
    val status = SourceStatus.values
      .find(_.toString == meta.status)
      .getOrElse(throw AdapterPermanent(s"unknown status '${meta.status}' in $metaFile"))
    val kind = SourceKind.values
      .find(_.toString == meta.kind)
      .getOrElse(throw AdapterPermanent(s"unknown kind '${meta.kind}' in $metaFile"))
    val content = Option.when(!meta.abstractOnly)(text)
    SourceRecord(
      identifier = alias.fold(meta.identifier)(a => s"${meta.identifier}@$a"),
      identifiers = meta.identifiers,
      title = meta.title,
      authors = meta.authors,
      publishedAt = meta.publishedAt,
      venue = meta.venue,
      sourceUrl = Some(file.toUri.toString),
      licence = meta.licence,
      status = status,
      kind = kind,
      internal = None,
      content = content,
      format = format,
      abstractText = meta.`abstract`,
      contentHash = Adapter.sha256(content.getOrElse(meta.`abstract`.getOrElse(meta.identifier))),
      sourceVersion = meta.sourceVersion
    )

  private def stripExtension(name: String): String =
    val dot = name.lastIndexOf('.')
    if dot > 0 then name.substring(0, dot) else name
