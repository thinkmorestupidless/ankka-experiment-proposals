package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent.*
import com.thinkmorestupidless.experimentproposals.rules.EligibilityRules
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One source (glossary: source): its identity, versions, status, licence, sections and quarantine.
 * A content hash already recorded is a duplicate and persists nothing (FR-012); versions are
 * append-only and an earlier version's sections stay resolvable (FR-041).
 */
final class SourceEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[SourceDocument, SourceEvent]:

  def emptyState: SourceDocument = SourceDocument.empty(context.entityId)

  def applyEvent(event: SourceEvent): SourceDocument = event match
    case Registered(
          ids,
          title,
          authors,
          publishedAt,
          venue,
          url,
          licence,
          kind,
          internal,
          abs,
          status,
          at
        ) =>
      currentState.copy(
        registered = true,
        identifiers = ids,
        title = title,
        authors = authors,
        publishedAt = publishedAt,
        venue = venue,
        sourceUrl = url,
        licence = licence,
        kind = kind,
        internal = internal,
        abstractText = abs,
        status = status,
        statusCheckedAt = Some(at)
      )
    case ContentFetched(version, hash, retrievedAt, sourceVersion, retention, content) =>
      currentState.copy(versions =
        currentState.versions :+ SourceVersion(
          version,
          hash,
          retrievedAt,
          sourceVersion,
          retention,
          content
        )
      )
    case Parsed(version, parseVersion, sections, issues, completeness) =>
      update(version)(
        _.copy(
          parseVersion = Some(parseVersion),
          sections = sections,
          issues = issues,
          completeness = Some(completeness)
        )
      )
    case Chunked(version, model, _, chunks, batch, _) =>
      update(version)(v =>
        v.copy(
          chunks = (v.chunks ++ chunks.map(c =>
            ChunkSpan(c.chunkId, c.sectionId, c.ordinal, c.startOffset, c.endOffset)
          ))
            .distinctBy(_.chunkId),
          chunkBatches = v.chunkBatches :+ s"$model#$batch"
        )
      )
    case MeasurementsRecorded(version, measurements) =>
      update(version)(v =>
        v.copy(measurementIds = (v.measurementIds ++ measurements.map(_.measurementId)).distinct)
      )
    case Extracted(version, promptVersion, recordIds, at) =>
      update(version)(v =>
        v.copy(extractions = v.extractions :+ Extraction(promptVersion, recordIds, at))
      )
    case Quarantined(version, reason, _) =>
      update(version)(_.copy(quarantined = true, quarantineReason = Some(reason)))
    case StatusChanged(status, _, _, at) =>
      currentState.copy(status = status, statusCheckedAt = Some(at))
    case EligibilityAssessed(eligibility) => currentState.copy(eligibility = Some(eligibility))
    case IngestionNoted(workflowId) =>
      currentState.copy(ingestions = (currentState.ingestions :+ workflowId).distinct)

  private def update(version: Int)(f: SourceVersion => SourceVersion): SourceDocument =
    currentState.copy(versions =
      currentState.versions.map(v => if v.version == version then f(v) else v)
    )

  private def known(version: Int): Option[SourceVersion] = currentState.version(version)

  def register(request: RegisterSource): Effect[Done] =
    if currentState.registered then effects.reply(Done)
    else
      effects
        .persist(
          Registered(
            request.identifiers,
            request.title,
            request.authors,
            request.publishedAt,
            request.venue,
            request.sourceUrl,
            request.licence,
            request.kind,
            request.internal,
            request.abstractText,
            request.status,
            Instant.now()
          )
        )
        .thenReply(_ => Done)

  def recordContent(request: FetchedContent): Effect[ContentOutcome] =
    if !currentState.registered then effects.error("register the source before recording content")
    else
      currentState.versionByHash(request.contentHash) match
        case Some(existing) => effects.reply(ContentOutcome.Duplicate(existing.version))
        case None =>
          val version = currentState.versions.size + 1
          effects
            .persist(
              ContentFetched(
                version,
                request.contentHash,
                request.retrievedAt,
                request.sourceVersion,
                request.retention,
                request.content
              )
            )
            .thenReply(_ => ContentOutcome.Recorded(version))

  def recordParse(request: RecordParse): Effect[Done] =
    known(request.version) match
      case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
      case Some(v) if v.parseVersion.contains(request.parseVersion) => effects.reply(Done)
      case Some(_) =>
        effects
          .persist(
            Parsed(
              request.version,
              request.parseVersion,
              request.sections,
              request.issues,
              request.completeness
            )
          )
          .thenReply(_ => Done)

  def recordChunks(request: RecordChunks): Effect[Done] =
    known(request.version) match
      case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
      case Some(v) if v.chunkBatches.contains(s"${request.embeddingModel}#${request.batch}") =>
        effects.reply(Done)
      case Some(_) =>
        effects
          .persist(
            Chunked(
              request.version,
              request.embeddingModel,
              request.embeddingRevision,
              request.chunks,
              request.batch,
              request.batches
            )
          )
          .thenReply(_ => Done)

  def recordMeasurements(request: RecordMeasurements): Effect[Done] =
    known(request.version) match
      case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
      case Some(v)
          if request.measurements.forall(m => v.measurementIds.contains(m.measurementId)) =>
        effects.reply(Done)
      case Some(_) =>
        effects
          .persist(MeasurementsRecorded(request.version, request.measurements))
          .thenReply(_ => Done)

  def recordExtraction(request: RecordExtraction): Effect[Done] =
    known(request.version) match
      case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
      case Some(v) if v.extractions.exists(_.promptVersion == request.promptVersion) =>
        effects.reply(Done)
      case Some(_) =>
        effects
          .persist(
            Extracted(request.version, request.promptVersion, request.recordIds, Instant.now())
          )
          .thenReply(_ => Done)

  def quarantine(request: QuarantineSource): Effect[Done] =
    known(request.version) match
      case None => effects.error(s"no version ${request.version}", ErrorCode.NotFound)
      case Some(v) if v.quarantined => effects.reply(Done)
      case Some(_) =>
        effects
          .persist(Quarantined(request.version, request.reason, Instant.now()))
          .thenReply(_ => Done)

  def changeStatus(request: ChangeStatus): Effect[Done] =
    Configured.actor(request.by) match
      case Left(problem) => effects.error(problem)
      case Right(_) if !currentState.registered =>
        effects.error("the source is not registered", ErrorCode.NotFound)
      case Right(_) if currentState.status == request.status => effects.reply(Done)
      case Right(by)                                         =>
        // The status is part of eligibility (FR-013), so the same command re-assesses it.
        val at    = Instant.now()
        val after = currentState.copy(status = request.status, statusCheckedAt = Some(at))
        val reassessed = after.latest.toVector.map(v =>
          EligibilityAssessed(
            EligibilityRules.assess(after, v.version, s"status-change:$by", now = at)
          )
        )
        effects
          .persistAll(StatusChanged(request.status, request.reason, by, at) +: reassessed)
          .thenReply(_ => Done)

  def assessEligibility(eligibility: Eligibility): Effect[Done] =
    if !currentState.registered then
      effects.error("the source is not registered", ErrorCode.NotFound)
    else effects.persist(EligibilityAssessed(eligibility)).thenReply(_ => Done)

  def noteIngestion(workflowId: String): Effect[Done] =
    if currentState.ingestions.contains(workflowId) then effects.reply(Done)
    else effects.persist(IngestionNoted(workflowId)).thenReply(_ => Done)

  def get: ReadOnlyEffect[SourceDocument] = effects.reply(currentState)

  def section(ref: SectionRef): ReadOnlyEffect[Section] =
    known(ref.version).flatMap(_.section(ref.sectionId)) match
      case Some(s) => effects.reply(s)
      case None =>
        effects.error(s"no section ${ref.sectionId} in version ${ref.version}", ErrorCode.NotFound)

  def resolveSpan(span: SourceSpan): ReadOnlyEffect[SpanResolution] =
    if span.sourceId != currentState.id then
      effects.reply(
        SpanResolution.Unresolved(s"span names ${span.sourceId}, not ${currentState.id}")
      )
    else
      known(span.sourceVersion).flatMap(_.section(span.sectionId)) match
        case None =>
          effects.reply(
            SpanResolution.Unresolved(
              s"no section ${span.sectionId} in version ${span.sourceVersion}"
            )
          )
        case Some(section) =>
          if SourceSpan.resolves(section, span) then
            effects.reply(
              SpanResolution.Resolved(section.text.substring(span.startOffset, span.endOffset))
            )
          else
            effects.reply(
              SpanResolution.Unresolved("the quotation does not match the text at the offsets")
            )

  def context(ref: ChunkRef): ReadOnlyEffect[ChunkContext] =
    known(ref.version) match
      case None => effects.error(s"no version ${ref.version}", ErrorCode.NotFound)
      case Some(v) =>
        v.chunks
          .find(_.chunkId == ref.chunkId)
          .flatMap(c => v.section(c.sectionId).map(c -> _)) match
          case None => effects.error(s"no chunk ${ref.chunkId}", ErrorCode.NotFound)
          case Some((chunk, section)) =>
            val siblings = v.chunks.filter(_.sectionId == chunk.sectionId).sortBy(_.ordinal)
            val adjacent = siblings
              .filter(c => (c.ordinal - chunk.ordinal).abs == 1)
              .map(c => section.text.substring(c.startOffset, c.endOffset))
            val related = section.relatedSectionIds.flatMap(v.section)
            effects.reply(
              ChunkContext(
                chunk,
                section,
                section.text.substring(chunk.startOffset, chunk.endOffset),
                adjacent,
                related,
                currentState.title,
                currentState.identifiers,
                currentState.status,
                currentState.licence
              )
            )

object SourceEntity
    extends EventSourcedEntity.Companion[SourceEntity, SourceDocument, SourceEvent](
      componentId = ComponentId("source"),
      stateSerializer = summon[Serializer[SourceDocument]],
      eventSerializer = summon[Serializer[SourceEvent]]
    ):

  def create(context: EventSourcedEntityContext) = new SourceEntity(context)

  val register           = command("register")(_.register)
  val recordContent      = command("record-content")(_.recordContent)
  val recordParse        = command("record-parse")(_.recordParse)
  val recordChunks       = command("record-chunks")(_.recordChunks)
  val recordMeasurements = command("record-measurements")(_.recordMeasurements)
  val recordExtraction   = command("record-extraction")(_.recordExtraction)
  val quarantine         = command("quarantine")(_.quarantine)
  val changeStatus       = command("change-status")(_.changeStatus)
  val assessEligibility  = command("assess-eligibility")(_.assessEligibility)
  val noteIngestion      = command("note-ingestion")(_.noteIngestion)
  val get                = query("get")(_.get)
  val section            = query("section")(_.section)
  val resolveSpan        = query("resolve-span")(_.resolveSpan)
  val context            = query("context")(_.context)
