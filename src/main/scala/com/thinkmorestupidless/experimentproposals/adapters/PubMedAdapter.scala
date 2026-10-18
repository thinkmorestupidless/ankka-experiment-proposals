package com.thinkmorestupidless.experimentproposals.adapters

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceRecord
import com.thinkmorestupidless.ankka.core.Codecs

import scala.concurrent.duration.*
import scala.xml.Node

final case class ESearchResult(idlist: Vector[String] = Vector.empty)
final case class ESearch(esearchresult: ESearchResult = ESearchResult())

/**
 * PubMed through E-utilities (contracts/stages.md): `esearch` and `esummary` for discovery,
 * `efetch` XML for one record. PubMed has no full text, so every record is `abstract_only`; status
 * comes from the publication types and the comments-corrections list. Three requests a second
 * without a key, ten with one; `tool` and `email` go on every request as NCBI asks.
 */
final class PubMedAdapter(
    contact: Option[String] = None,
    apiKey: Option[String] = None,
    base: String = PubMedAdapter.DefaultBase,
    http: Option[HttpFetch] = None
) extends Adapter:

  private val fetcher =
    http.getOrElse(
      HttpFetch(HttpFetch.agent(contact), if apiKey.isDefined then 100.millis else 334.millis)
    )
  private given JsonValueCodec[ESearch] = Codecs.make[ESearch]

  def name: String = "pubmed"

  private def common: Seq[(String, String)] =
    Seq("db" -> "pubmed", "tool" -> "ankka-experiment-proposals") ++
      contact.map("email" -> _) ++ apiKey.map("api_key" -> _)

  def search(query: String, filters: Map[String, String]): Vector[SourceRef] =
    val ids = fetcher.get(
      HttpFetch.uri(
        base,
        "esearch.fcgi",
        common ++ Seq(
          "term"    -> query,
          "retmode" -> "json",
          "retmax"  -> filters.getOrElse("pageSize", "25")
        )
      )
    ) match
      case Fetched.NotFound => Vector.empty
      case Fetched.Ok(body) =>
        try readFromString[ESearch](body).esearchresult.idlist
        catch
          case e: Exception =>
            throw AdapterPermanent(s"esearch answered no id list: ${e.getMessage}", Some(e))
    if ids.isEmpty then Vector.empty
    else
      val titles = fetcher.get(
        HttpFetch.uri(
          base,
          "esummary.fcgi",
          common ++ Seq("id" -> ids.mkString(","), "retmode" -> "json")
        )
      ) match
        case Fetched.NotFound => Map.empty[String, String]
        case Fetched.Ok(body) => PubMedAdapter.summaryTitles(body)
      ids.map(id => SourceRef(s"pmid:$id", titles.getOrElse(id, "")))

  def fetch(identifier: String): SourceRecord =
    val pmid = PubMedAdapter.pmidOf(identifier).fold(p => throw AdapterPermanent(p), identity)
    fetcher.get(
      HttpFetch.uri(base, "efetch.fcgi", common ++ Seq("id" -> pmid, "retmode" -> "xml")),
      "application/xml"
    ) match
      case Fetched.NotFound => throw AdapterPermanent(s"PubMed has no record $pmid")
      case Fetched.Ok(xml) =>
        val root =
          try SafeXml.load(xml)
          catch
            case e: Exception =>
              throw AdapterPermanent(s"efetch answered no XML: ${e.getMessage}", Some(e))
        (root \\ "PubmedArticle").headOption
          .map(PubMedAdapter.record)
          .getOrElse(throw AdapterPermanent(s"PubMed has no record $pmid"))

object PubMedAdapter:
  val DefaultBase = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"

  private val Pmid = "(?i)(?:pmid:)?(\\d+)".r

  def pmidOf(identifier: String): Either[String, String] = identifier.trim match
    case Pmid(p) => Right(p)
    case other   => Left(s"'$other' is not a PMID")

  /** `result.<uid>.title` out of an esummary reply, without modelling the whole document. */
  def summaryTitles(json: String): Map[String, String] =
    val pattern = """"uid":\s*"(\d+)"[\s\S]*?"title":\s*"((?:[^"\\]|\\.)*)"""".r
    pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2).replace("\\\"", "\"")).toMap

  private def text(n: Node): String = n.text.replaceAll("\\s+", " ").trim

  def statusOf(pubTypes: Seq[String], refTypes: Seq[String]): SourceStatus =
    if pubTypes.exists(_.equalsIgnoreCase("Retracted Publication")) || refTypes.contains(
        "RetractionIn"
      )
    then SourceStatus.retracted
    else if refTypes.contains("ErratumIn") then SourceStatus.corrected
    else SourceStatus.active

  def record(article: Node): SourceRecord =
    val citation = article \ "MedlineCitation"
    val art      = citation \ "Article"
    val pmid     = text((citation \ "PMID").head)
    val ids = (article \\ "ArticleId").flatMap { n =>
      (n \ "@IdType").text match
        case "doi" => Some("doi" -> text(n))
        case "pmc" => Some("pmcid" -> text(n))
        case _     => None
    }.toMap + ("pmid" -> pmid)
    val authors = (art \ "AuthorList" \ "Author")
      .map { a =>
        val last     = text((a \ "LastName").headOption.getOrElse(<x/>))
        val initials = text((a \ "Initials").headOption.getOrElse(<x/>))
        val coll     = text((a \ "CollectiveName").headOption.getOrElse(<x/>))
        if last.nonEmpty then s"$last $initials".trim else coll
      }
      .filter(_.nonEmpty)
      .toVector
    val pubDate = (art \ "Journal" \ "JournalIssue" \ "PubDate").headOption
    val publishedAt = pubDate.flatMap { d =>
      val year = text((d \ "Year").headOption.getOrElse(<x/>))
      if year.nonEmpty then
        val month = text((d \ "Month").headOption.getOrElse(<x/>))
        val day   = text((d \ "Day").headOption.getOrElse(<x/>))
        Some(Seq(year, month, day).filter(_.nonEmpty).mkString("-"))
      else Some(text((d \ "MedlineDate").headOption.getOrElse(<x/>))).filter(_.nonEmpty)
    }
    val abstractText = Some(
      (art \ "Abstract" \ "AbstractText")
        .map { t =>
          val label = (t \ "@Label").text
          if label.nonEmpty then s"$label: ${text(t)}" else text(t)
        }
        .mkString(" ")
    ).filter(_.nonEmpty)
    val pubTypes = (art \ "PublicationTypeList" \ "PublicationType").map(text)
    val refTypes =
      (citation \ "CommentsCorrectionsList" \ "CommentsCorrections").map(n => (n \ "@RefType").text)
    val identifier = ids.get("doi").map(d => s"doi:$d").getOrElse(s"pmid:$pmid")
    SourceRecord(
      identifier = identifier,
      identifiers = ids,
      title = text((art \ "ArticleTitle").headOption.getOrElse(<x/>)),
      authors = authors,
      publishedAt = publishedAt,
      venue = (art \ "Journal" \ "Title").headOption.map(text),
      sourceUrl = Some(s"https://pubmed.ncbi.nlm.nih.gov/$pmid/"),
      licence = None,
      status = statusOf(pubTypes, refTypes),
      kind = SourceKind.literature,
      internal = None,
      content = None,
      format = "text",
      abstractText = abstractText,
      contentHash = Adapter.sha256(abstractText.getOrElse(identifier)),
      sourceVersion = (citation \ "DateRevised").headOption.map(d =>
        Seq("Year", "Month", "Day")
          .map(k => text((d \ k).headOption.getOrElse(<x/>)))
          .filter(_.nonEmpty)
          .mkString("-")
      )
    )
