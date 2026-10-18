package com.thinkmorestupidless.experimentproposals.adapters

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceRecord
import com.thinkmorestupidless.ankka.core.Codecs

import scala.concurrent.duration.*

// The subset of Europe PMC's `core` result the adapter reads; everything else is skipped.
final case class EpmcJournal(title: Option[String] = None)
final case class EpmcJournalInfo(journal: Option[EpmcJournal] = None)
final case class EpmcPubTypes(pubType: Vector[String] = Vector.empty)
final case class EpmcCorrection(`type`: Option[String] = None, id: Option[String] = None)
final case class EpmcCorrections(commentCorrection: Vector[EpmcCorrection] = Vector.empty)
final case class EpmcResult(
    id: Option[String] = None,
    source: Option[String] = None,
    pmid: Option[String] = None,
    pmcid: Option[String] = None,
    doi: Option[String] = None,
    title: Option[String] = None,
    authorString: Option[String] = None,
    journalInfo: Option[EpmcJournalInfo] = None,
    pubYear: Option[String] = None,
    firstPublicationDate: Option[String] = None,
    dateOfRevision: Option[String] = None,
    isOpenAccess: Option[String] = None,
    inEPMC: Option[String] = None,
    license: Option[String] = None,
    pubTypeList: Option[EpmcPubTypes] = None,
    commentCorrectionList: Option[EpmcCorrections] = None,
    abstractText: Option[String] = None
)
final case class EpmcResultList(result: Vector[EpmcResult] = Vector.empty)
final case class EpmcSearch(hitCount: Int = 0, resultList: Option[EpmcResultList] = None)

/**
 * Europe PMC (contracts/stages.md, source adapters): `search` for discovery, `fullTextXML` for the
 * JATS of an open-access article, status from the publication types and the correction list, at
 * most five requests a second. An article without full text is kept with its abstract,
 * `abstract_only`.
 */
final class EuropePmcAdapter(
    contact: Option[String] = None,
    base: String = EuropePmcAdapter.DefaultBase,
    http: Option[HttpFetch] = None
) extends Adapter:

  private val fetcher = http.getOrElse(HttpFetch(HttpFetch.agent(contact), 200.millis))
  private given JsonValueCodec[EpmcSearch] = Codecs.make[EpmcSearch]

  def name: String = "europepmc"

  def search(query: String, filters: Map[String, String]): Vector[SourceRef] =
    val q = filters
      .get("openAccessOnly")
      .filter(_ == "true")
      .fold(query)(_ => s"($query) AND OPEN_ACCESS:y")
    results(q, "lite", filters.getOrElse("pageSize", "25")).flatMap { r =>
      EuropePmcAdapter.handle(r).map(h => SourceRef(h, r.title.getOrElse("")))
    }

  def fetch(identifier: String): SourceRecord =
    val query = EuropePmcAdapter.queryFor(identifier).fold(p => throw AdapterPermanent(p), identity)
    val found = results(query, "core", "1").headOption
      .getOrElse(throw AdapterPermanent(s"Europe PMC has no record for '$identifier'"))
    val fullText = found.pmcid
      .filter(_ => found.isOpenAccess.contains("Y") || found.inEPMC.contains("Y"))
      .flatMap { pmcid =>
        fetcher.get(HttpFetch.uri(base, s"$pmcid/fullTextXML"), "application/xml") match
          case Fetched.Ok(xml)  => Some(xml)
          case Fetched.NotFound => None
      }
    EuropePmcAdapter.record(found, fullText)

  private def results(query: String, resultType: String, pageSize: String): Vector[EpmcResult] =
    val uri = HttpFetch.uri(
      base,
      "search",
      Seq("query" -> query, "format" -> "json", "resultType" -> resultType, "pageSize" -> pageSize)
    )
    fetcher.get(uri) match
      case Fetched.NotFound => Vector.empty
      case Fetched.Ok(body) =>
        try readFromString[EpmcSearch](body).resultList.map(_.result).getOrElse(Vector.empty)
        catch
          case e: Exception =>
            throw AdapterPermanent(
              s"Europe PMC answered something other than a search result: ${e.getMessage}",
              Some(e)
            )

object EuropePmcAdapter:
  val DefaultBase = "https://www.ebi.ac.uk/europepmc/webservices/rest"

  private val Pmcid = "(?i)(?:pmcid:)?(PMC\\d+)".r
  private val Pmid  = "(?i)(?:pmid:)?(\\d+)".r
  private val Doi   = "(?i)doi:(.+)".r

  /** The search query that names one article, from a PMCID, a PMID or a DOI. */
  def queryFor(identifier: String): Either[String, String] = identifier.trim match
    case Pmcid(p) => Right(s"PMCID:${p.toUpperCase}")
    case Pmid(p)  => Right(s"EXT_ID:$p AND SRC:MED")
    case Doi(d)   => Right(s"""DOI:"$d"""")
    case other    => Left(s"'$other' is not a PMCID, a PMID or a doi: identifier")

  /** The identifier a search hands back so that `fetch` accepts it. */
  def handle(r: EpmcResult): Option[String] =
    r.pmcid.orElse(r.pmid.map(p => s"pmid:$p")).orElse(r.doi.map(d => s"doi:$d"))

  /** Retracted from the publication type or a `Retraction in`; corrected from an `Erratum in`. */
  def statusOf(pubTypes: Vector[String], corrections: Vector[EpmcCorrection]): SourceStatus =
    val types = corrections.flatMap(_.`type`).map(_.toLowerCase)
    if pubTypes.exists(_.equalsIgnoreCase("Retracted Publication")) || types.exists(
        _.startsWith("retraction in")
      )
    then SourceStatus.retracted
    else if types.exists(t => t.startsWith("erratum in") || t.startsWith("correction in")) then
      SourceStatus.corrected
    else SourceStatus.active

  def record(found: EpmcResult, fullText: Option[String]): SourceRecord =
    val ids = Vector(
      found.doi.map("doi" -> _),
      found.pmid.map("pmid" -> _),
      found.pmcid.map("pmcid" -> _)
    ).flatten.toMap
    val identifier = found.doi
      .map(d => s"doi:$d")
      .orElse(found.pmid.map(p => s"pmid:$p"))
      .orElse(found.pmcid.map(p => s"pmcid:$p"))
      .getOrElse(throw AdapterPermanent("the Europe PMC record has no identifier"))
    val abstractText = found.abstractText.map(HttpFetch.stripTags).filter(_.nonEmpty)
    SourceRecord(
      identifier = identifier,
      identifiers = ids,
      title = found.title.map(HttpFetch.stripTags).getOrElse(""),
      authors =
        found.authorString.toVector.flatMap(_.stripSuffix(".").split(",\\s*")).filter(_.nonEmpty),
      publishedAt = found.firstPublicationDate.orElse(found.pubYear),
      venue = found.journalInfo.flatMap(_.journal).flatMap(_.title),
      sourceUrl = for s <- found.source; i <- found.id yield s"https://europepmc.org/article/$s/$i",
      licence = found.license,
      status = statusOf(
        found.pubTypeList.map(_.pubType).getOrElse(Vector.empty),
        found.commentCorrectionList.map(_.commentCorrection).getOrElse(Vector.empty)
      ),
      kind = SourceKind.literature,
      internal = None,
      content = fullText,
      format = "jats",
      abstractText = abstractText,
      contentHash = Adapter.sha256(fullText.orElse(abstractText).getOrElse(identifier)),
      sourceVersion = found.dateOfRevision.orElse(found.firstPublicationDate)
    )
