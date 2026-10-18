package com.thinkmorestupidless.experimentproposals.adapters

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceRecord
import com.thinkmorestupidless.ankka.core.Codecs

import scala.concurrent.duration.*

final case class CrDate(@named("date-parts") dateParts: Vector[Vector[Option[Int]]] = Vector.empty)
final case class CrLicense(
    URL: Option[String] = None,
    @named("content-version") contentVersion: Option[String] = None
)
final case class CrAuthor(
    `given`: Option[String] = None,
    family: Option[String] = None,
    name: Option[String] = None
)
final case class CrUpdate(
    DOI: Option[String] = None,
    `type`: Option[String] = None,
    label: Option[String] = None
)
final case class CrWork(
    DOI: String,
    title: Vector[String] = Vector.empty,
    author: Vector[CrAuthor] = Vector.empty,
    @named("container-title") containerTitle: Vector[String] = Vector.empty,
    issued: Option[CrDate] = None,
    license: Vector[CrLicense] = Vector.empty,
    @named("update-to") updateTo: Vector[CrUpdate] = Vector.empty,
    `abstract`: Option[String] = None,
    URL: Option[String] = None,
    score: Option[Double] = None
)
final case class CrWorkMessage(message: CrWork)
final case class CrItems(items: Vector[CrWork] = Vector.empty)
final case class CrListMessage(message: CrItems)

/**
 * Crossref (contracts/stages.md): `works?query=` for discovery, `works/{doi}` for metadata and the
 * licence URLs, and `works?filter=updates:{doi}` for the notices that retract or correct it,
 * because Crossref records a retraction on the notice (`update-to`), not on the article. No full
 * text, so every record is `abstract_only`. Polite pool: `mailto` on every request.
 */
final class CrossrefAdapter(
    contact: Option[String] = None,
    base: String = CrossrefAdapter.DefaultBase,
    http: Option[HttpFetch] = None
) extends Adapter:

  private val fetcher = http.getOrElse(HttpFetch(HttpFetch.agent(contact), 100.millis))
  private given JsonValueCodec[CrWorkMessage] = Codecs.make[CrWorkMessage]
  private given JsonValueCodec[CrListMessage] = Codecs.make[CrListMessage]

  def name: String = "crossref"

  private def polite: Seq[(String, String)] = contact.map("mailto" -> _).toSeq

  def search(query: String, filters: Map[String, String]): Vector[SourceRef] =
    val uri = HttpFetch.uri(
      base,
      "works",
      Seq(
        "query"  -> query,
        "rows"   -> filters.getOrElse("pageSize", "25"),
        "select" -> CrossrefAdapter.Select
      ) ++ polite
    )
    list(uri).map(w => SourceRef(s"doi:${w.DOI}", w.title.headOption.getOrElse(""), w.score))

  def fetch(identifier: String): SourceRecord =
    val doi = CrossrefAdapter.doiOf(identifier).fold(p => throw AdapterPermanent(p), identity)
    val work = fetcher.get(HttpFetch.uri(base, s"works/$doi", polite)) match
      case Fetched.NotFound => throw AdapterPermanent(s"Crossref has no work $doi")
      case Fetched.Ok(body) =>
        try readFromString[CrWorkMessage](body).message
        catch
          case e: Exception =>
            throw AdapterPermanent(s"Crossref answered no work: ${e.getMessage}", Some(e))
    val notices = list(
      HttpFetch.uri(
        base,
        "works",
        Seq("filter" -> s"updates:$doi", "rows" -> "20", "select" -> "DOI,update-to") ++ polite
      )
    )
    CrossrefAdapter.record(work, CrossrefAdapter.statusOf(doi, notices))

  private def list(uri: java.net.URI): Vector[CrWork] =
    fetcher.get(uri) match
      case Fetched.NotFound => Vector.empty
      case Fetched.Ok(body) =>
        try readFromString[CrListMessage](body).message.items
        catch
          case e: Exception =>
            throw AdapterPermanent(s"Crossref answered no list: ${e.getMessage}", Some(e))

object CrossrefAdapter:
  val DefaultBase = "https://api.crossref.org"
  val Select      = "DOI,title,author,container-title,issued,license,update-to,abstract,URL,score"

  def doiOf(identifier: String): Either[String, String] =
    val raw = identifier.trim.stripPrefix("doi:").stripPrefix("https://doi.org/")
    if raw.matches("(?i)10\\.\\d{4,9}/\\S+") then Right(raw)
    else Left(s"'$identifier' is not a DOI")

  /** The notices that update this DOI decide its status; a retraction outranks a correction. */
  def statusOf(doi: String, notices: Vector[CrWork]): SourceStatus =
    val types = notices
      .flatMap(_.updateTo)
      .filter(_.DOI.exists(_.equalsIgnoreCase(doi)))
      .flatMap(_.`type`)
      .map(_.toLowerCase)
    if types.exists(_.contains("retraction")) then SourceStatus.retracted
    else if types.exists(t => t.contains("correction") || t.contains("erratum")) then
      SourceStatus.corrected
    else SourceStatus.active

  def record(work: CrWork, status: SourceStatus): SourceRecord =
    val abstractText = work.`abstract`.map(HttpFetch.stripTags).filter(_.nonEmpty)
    val identifier   = s"doi:${work.DOI}"
    SourceRecord(
      identifier = identifier,
      identifiers = Map("doi" -> work.DOI),
      title = work.title.headOption.map(HttpFetch.stripTags).getOrElse(""),
      authors = work.author
        .map(a =>
          a.name.getOrElse(
            Seq(a.family, a.`given`.map(g => g.split("\\s+").map(_.take(1)).mkString)).flatten
              .mkString(" ")
          )
        )
        .filter(_.nonEmpty),
      publishedAt =
        work.issued.flatMap(_.dateParts.headOption).map(_.flatten.mkString("-")).filter(_.nonEmpty),
      venue = work.containerTitle.headOption,
      sourceUrl = work.URL.orElse(Some(s"https://doi.org/${work.DOI}")),
      licence = work.license
        .find(_.contentVersion.contains("vor"))
        .orElse(work.license.headOption)
        .flatMap(_.URL),
      status = status,
      kind = SourceKind.literature,
      internal = None,
      content = None,
      format = "text",
      abstractText = abstractText,
      contentHash = Adapter.sha256(abstractText.getOrElse(identifier)),
      sourceVersion = None
    )
