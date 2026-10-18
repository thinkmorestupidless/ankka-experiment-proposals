package com.thinkmorestupidless.experimentproposals.domain.sources

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** A structural part of a source, holding canonical text that spans resolve into. */
final case class Section(
    id: String,
    parentId: Option[String],
    ordinal: Int,
    kind: SectionKind,
    title: String,
    text: String,
    relatedSectionIds: Vector[String] = Vector.empty,
    parseConfidence: ParseConfidence = ParseConfidence.high,
    tableResolved: Boolean = true
)

final case class ParseIssue(kind: String, location: String, detail: String)

/** An exact, resolvable location in a source version (glossary: source span). */
final case class SourceSpan(
    sourceId: String,
    sourceVersion: Int,
    sectionId: String,
    startOffset: Int,
    endOffset: Int,
    quotation: String,
    chunkId: Option[String] = None
):
  def key: String = s"$sourceId:v$sourceVersion:$sectionId:$startOffset-$endOffset"

object SourceSpan:
  def normalise(text: String): String = text.trim.replaceAll("\\s+", " ")

  /** Resolves when the slice of the section equals the quotation, whitespace-tolerant. */
  def resolves(section: Section, span: SourceSpan): Boolean =
    span.startOffset >= 0 && span.endOffset <= section.text.length && span.startOffset < span.endOffset &&
      normalise(section.text.substring(span.startOffset, span.endOffset)) == normalise(
        span.quotation
      )

/** Where a chunk sits in its section; the text and the vector are on the view's row, not here. */
final case class ChunkSpan(
    chunkId: String,
    sectionId: String,
    ordinal: Int,
    startOffset: Int,
    endOffset: Int
)

/** A retrieval unit, as the `Chunked` event carries it to the chunk view. */
final case class Chunk(
    chunkId: String,
    sectionId: String,
    ordinal: Int,
    startOffset: Int,
    endOffset: Int,
    text: String,
    tokenCount: Int,
    embedding: Vector[Float]
)

/** A quantitative internal value with its unit, validated and never converted. */
final case class Measurement(
    measurementId: String,
    quantityKind: String,
    value: BigDecimal,
    unit: String,
    unitValid: Boolean,
    method: Option[String],
    conditions: Map[String, String],
    qualityFlag: QualityFlag,
    rawText: String,
    recordedAt: Option[String] = None
)

final case class InternalDetails(
    sourceSystem: String,
    originalId: String,
    responsibleResearcher: String,
    recordedAt: Option[String],
    protocolVersion: Option[String],
    conditions: Map[String, String]
)

final case class RuleReason(ruleId: String, detail: String)

final case class Eligibility(
    eligible: Boolean,
    reasons: Vector[RuleReason],
    assessedAt: Instant,
    assessedBy: String,
    ruleSetVersion: String
)

final case class Extraction(promptVersion: String, recordIds: Vector[String], at: Instant)

/** One immutable version of a source's content and what was made of it. */
final case class SourceVersion(
    version: Int,
    contentHash: String,
    retrievedAt: Instant,
    sourceVersion: Option[String],
    retention: RetentionRestriction,
    content: Option[String],
    parseVersion: Option[String] = None,
    completeness: Option[ParseCompleteness] = None,
    issues: Vector[ParseIssue] = Vector.empty,
    sections: Vector[Section] = Vector.empty,
    chunks: Vector[ChunkSpan] = Vector.empty,
    chunkBatches: Vector[String] = Vector.empty,
    measurementIds: Vector[String] = Vector.empty,
    extractions: Vector[Extraction] = Vector.empty,
    quarantined: Boolean = false,
    quarantineReason: Option[String] = None
):
  def section(id: String): Option[Section] = sections.find(_.id == id)

/** The state of the `source` entity (glossary: source). */
final case class SourceDocument(
    id: String,
    registered: Boolean = false,
    identifiers: Map[String, String] = Map.empty,
    title: String = "",
    authors: Vector[String] = Vector.empty,
    publishedAt: Option[String] = None,
    venue: Option[String] = None,
    sourceUrl: Option[String] = None,
    licence: Option[String] = None,
    kind: SourceKind = SourceKind.literature,
    internal: Option[InternalDetails] = None,
    abstractText: Option[String] = None,
    status: SourceStatus = SourceStatus.active,
    statusCheckedAt: Option[Instant] = None,
    versions: Vector[SourceVersion] = Vector.empty,
    eligibility: Option[Eligibility] = None,
    ingestions: Vector[String] = Vector.empty
):
  def latest: Option[SourceVersion]                      = versions.lastOption
  def version(n: Int): Option[SourceVersion]             = versions.find(_.version == n)
  def versionByHash(hash: String): Option[SourceVersion] = versions.find(_.contentHash == hash)
  def retention: RetentionRestriction = latest.map(_.retention).getOrElse(RetentionRestriction.none)

object SourceDocument:
  def empty(id: String): SourceDocument = SourceDocument(id)

enum SourceEvent:
  case Registered(
      identifiers: Map[String, String],
      title: String,
      authors: Vector[String],
      publishedAt: Option[String],
      venue: Option[String],
      sourceUrl: Option[String],
      licence: Option[String],
      kind: SourceKind,
      internal: Option[InternalDetails],
      abstractText: Option[String],
      status: SourceStatus,
      at: Instant
  )
  case ContentFetched(
      version: Int,
      contentHash: String,
      retrievedAt: Instant,
      sourceVersion: Option[String],
      retention: RetentionRestriction,
      content: Option[String]
  )
  case Parsed(
      version: Int,
      parseVersion: String,
      sections: Vector[Section],
      issues: Vector[ParseIssue],
      completeness: ParseCompleteness
  )
  case Chunked(
      version: Int,
      embeddingModel: String,
      embeddingRevision: String,
      chunks: Vector[Chunk],
      batch: Int,
      batches: Int
  )
  case MeasurementsRecorded(version: Int, measurements: Vector[Measurement])
  case Extracted(version: Int, promptVersion: String, recordIds: Vector[String], at: Instant)
  case Quarantined(version: Int, reason: String, at: Instant)
  case StatusChanged(status: SourceStatus, reason: String, by: String, at: Instant)
  case EligibilityAssessed(eligibility: Eligibility)
  case IngestionNoted(workflowId: String)

// ── commands and replies ───────────────────────────────────────────────────────

final case class RegisterSource(
    identifiers: Map[String, String],
    title: String,
    authors: Vector[String],
    publishedAt: Option[String],
    venue: Option[String],
    sourceUrl: Option[String],
    licence: Option[String],
    kind: SourceKind,
    internal: Option[InternalDetails],
    abstractText: Option[String],
    status: SourceStatus
)

final case class FetchedContent(
    contentHash: String,
    retrievedAt: Instant,
    sourceVersion: Option[String],
    retention: RetentionRestriction,
    content: Option[String]
)

enum ContentOutcome:
  case Recorded(version: Int)
  case Duplicate(version: Int)

final case class RecordParse(
    version: Int,
    parseVersion: String,
    sections: Vector[Section],
    issues: Vector[ParseIssue],
    completeness: ParseCompleteness
)

final case class RecordChunks(
    version: Int,
    embeddingModel: String,
    embeddingRevision: String,
    chunks: Vector[Chunk],
    batch: Int,
    batches: Int
)

final case class RecordMeasurements(version: Int, measurements: Vector[Measurement])
final case class RecordExtraction(version: Int, promptVersion: String, recordIds: Vector[String])
final case class QuarantineSource(version: Int, reason: String)
final case class ChangeStatus(status: SourceStatus, reason: String, by: String)
final case class SectionRef(version: Int, sectionId: String)
final case class ChunkRef(version: Int, chunkId: String)

enum SpanResolution:
  case Resolved(text: String)
  case Unresolved(reason: String)

/** What a retrieved chunk is resolved with for assessment: its section, neighbours and metadata. */
final case class ChunkContext(
    chunk: ChunkSpan,
    section: Section,
    chunkText: String,
    adjacent: Vector[String],
    related: Vector[Section],
    title: String,
    identifiers: Map[String, String],
    status: SourceStatus,
    licence: Option[String]
)

/** What an adapter returns for one source (contracts/stages.md). */
final case class SourceRecord(
    identifier: String,
    identifiers: Map[String, String],
    title: String,
    authors: Vector[String],
    publishedAt: Option[String],
    venue: Option[String],
    sourceUrl: Option[String],
    licence: Option[String],
    status: SourceStatus,
    kind: SourceKind,
    internal: Option[InternalDetails],
    content: Option[String],
    format: String,
    abstractText: Option[String],
    contentHash: String,
    sourceVersion: Option[String],
    measurements: Vector[Measurement] = Vector.empty,
    /** What the adapter could not read reliably; the parse step records them with its own. */
    issues: Vector[ParseIssue] = Vector.empty
):
  def sourceId: String = SourceRecord.sourceIdOf(identifier)
  def retention: RetentionRestriction =
    if content.isDefined then RetentionRestriction.none else RetentionRestriction.abstract_only
  def register: RegisterSource = RegisterSource(
    identifiers,
    title,
    authors,
    publishedAt,
    venue,
    sourceUrl,
    licence,
    kind,
    internal,
    abstractText,
    status
  )

object SourceRecord:
  /** `src:<identifier>`, with `/` as `~` so the id can travel in a path segment. */
  def sourceIdOf(identifier: String): String = s"src:${identifier.replace('/', '~')}"

object SourceCodecs:
  given Serializer[SourceDocument] = Codecs.serializer[SourceDocument]("source")
  given Serializer[SourceEvent]    = Codecs.serializer[SourceEvent]("source-event")
  given Serializer[RegisterSource] = Codecs.serializer[RegisterSource]("register-source")
  given Serializer[FetchedContent] = Codecs.serializer[FetchedContent]("record-content")
  given Serializer[ContentOutcome] = Codecs.serializer[ContentOutcome]("content-outcome")
  given Serializer[RecordParse]    = Codecs.serializer[RecordParse]("record-parse")
  given Serializer[RecordChunks]   = Codecs.serializer[RecordChunks]("record-chunks")
  given Serializer[RecordMeasurements] =
    Codecs.serializer[RecordMeasurements]("record-measurements")
  given Serializer[RecordExtraction] = Codecs.serializer[RecordExtraction]("record-extraction")
  given Serializer[QuarantineSource] = Codecs.serializer[QuarantineSource]("quarantine-source")
  given Serializer[ChangeStatus]     = Codecs.serializer[ChangeStatus]("change-status")
  given Serializer[Eligibility]      = Codecs.serializer[Eligibility]("eligibility")
  given Serializer[SectionRef]       = Codecs.serializer[SectionRef]("section-ref")
  given Serializer[Section]          = Codecs.serializer[Section]("section")
  given Serializer[SourceSpan]       = Codecs.serializer[SourceSpan]("source-span")
  given Serializer[SpanResolution]   = Codecs.serializer[SpanResolution]("span-resolution")
  given Serializer[ChunkRef]         = Codecs.serializer[ChunkRef]("chunk-ref")
  given Serializer[ChunkContext]     = Codecs.serializer[ChunkContext]("chunk-context")
  given Serializer[SourceRecord]     = Codecs.serializer[SourceRecord]("source-record")
