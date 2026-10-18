package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, Ref, ValidationResult}
import com.thinkmorestupidless.experimentproposals.domain.sources.{
  Section,
  SourceSpan,
  SpanResolution
}

import java.time.Instant

/**
 * `deterministic@v1` (FR-022): codec parse, source version exists, every span resolves, required
 * metadata, units, status in its list, no `verified` set by the extractor, a hypothesis never
 * verified. Runs before any model call; a failure quarantines the record.
 */
object Deterministic:
  val spansResolve = Rule("deterministic.spans-resolve", 1)
  val metadata     = Rule("deterministic.required-metadata", 1)
  val closedLists  = Rule("deterministic.closed-lists", 1)
  val sourceExists = Rule("deterministic.source-version-exists", 1)
  val ruleSet: RuleSet =
    RuleSet("deterministic", 1, Vector(spansResolve, metadata, closedLists, sourceExists))

  /**
   * Maps the extractor's quotations onto spans in the section texts; the first miss is the defect.
   */
  def locateAll(
      sourceId: String,
      version: Int,
      sections: Map[String, Section],
      quotations: Vector[Quotation]
  ): Either[Defect, Vector[SourceSpan]] =
    val located = quotations.map { q =>
      sections.get(q.section) match
        case None => Left(s"quotation names section '${q.section}', which the source does not have")
        case Some(section) =>
          FacetRules.locate(section.text, q.text) match
            case None => Left(s"quotation is not in section ${q.section} ('${q.text.take(60)}')")
            case Some(span) =>
              Right(
                SourceSpan(
                  sourceId,
                  version,
                  section.id,
                  span.start,
                  span.end,
                  section.text.substring(span.start, span.end)
                )
              )
    }
    located.collectFirst { case Left(p) => p } match
      case Some(problem) =>
        Left(Rules.defect(DefectKind.span_unresolved, problem, retryable = true))
      case None if located.isEmpty =>
        Left(Rules.defect(DefectKind.span_unresolved, "no quotation cited", retryable = true))
      case None => Right(located.collect { case Right(s) => s })

  /** The content a proposal becomes, with its closed lists checked. */
  def finding(p: FindingProposal): Either[Defect, FindingContent] =
    for
      direction <- OutcomeDirection.values
        .find(_.toString == p.outcomeDirection.trim.replace(' ', '_'))
        .toRight(
          Rules.defect(
            DefectKind.schema_invalid,
            s"finding '${p.key}': outcome direction '${p.outcomeDirection}' is not in the list",
            retryable = true
          )
        )
      missing <- p.missingValues.toVector.foldLeft[Either[Defect, Map[String, MissingValueStatus]]](
        Right(Map.empty)
      ) { case (acc, (field, status)) =>
        acc.flatMap(m =>
          MissingValueStatus.values
            .find(_.toString == status.trim.replace(' ', '_'))
            .map(v => m + (field.trim.toLowerCase -> v))
            .toRight(
              Rules.defect(
                DefectKind.status_invalid,
                s"finding '${p.key}': missing-value status '$status' is not in the list",
                retryable = true
              )
            )
        )
      }
      _ <- Either.cond(
        p.observedEndpoint.trim.nonEmpty && p.reportedOutcome.trim.nonEmpty,
        (),
        Rules.defect(
          DefectKind.schema_invalid,
          s"finding '${p.key}' lacks its endpoint or outcome",
          retryable = true
        )
      )
      _ <- Either.cond(
        !missing.values.exists(_ == MissingValueStatus.established_absent),
        (),
        Rules.defect(
          DefectKind.status_invalid,
          s"finding '${p.key}': only a reviewer records a value as established absent (FR-027)",
          retryable = true
        )
      )
    yield FindingContent(
      p.species.map(_.trim).filter(_.nonEmpty),
      p.cellType.map(_.trim).filter(_.nonEmpty),
      p.experimentalModel.map(_.trim).filter(_.nonEmpty),
      p.conditions,
      p.observedEndpoint.trim.toLowerCase,
      p.reportedOutcome.trim,
      direction,
      p.qualifications,
      p.limitations,
      p.authorInterpretation,
      p.extractorUncertainty,
      missing
    )

  def claim(p: ClaimProposal, findingRefs: Vector[Ref]): Either[Defect, ClaimContent] =
    ClaimKind.values
      .find(_.toString == p.kind.trim.replace(' ', '_'))
      .toRight(
        Rules.defect(
          DefectKind.schema_invalid,
          s"claim '${p.key}': kind '${p.kind}' is not in the list",
          retryable = true
        )
      )
      .filterOrElse(
        _ => p.proposition.trim.nonEmpty,
        Rules.defect(
          DefectKind.schema_invalid,
          s"claim '${p.key}' has no proposition",
          retryable = true
        )
      )
      .map(kind =>
        ClaimContent(
          p.proposition.trim,
          kind,
          findingRefs,
          Vector.empty,
          p.qualifications,
          p.speciesScope.map(_.trim).filter(_.nonEmpty),
          p.endpointScope.map(_.trim).filter(_.nonEmpty)
        )
      )

  /** The gate on a proposed record, given how each span resolves against the stored source. */
  def validate(
      record: EvidenceRecord,
      resolve: SourceSpan => SpanResolution,
      sourceVersionExists: Boolean,
      now: Instant = Instant.now()
  ): ValidationResult =
    val defects = Vector.newBuilder[Defect]
    if !sourceVersionExists then
      defects += Rules.defect(
        DefectKind.other,
        "the source version does not exist",
        retryable = false
      )
    record.spans.foreach { span =>
      resolve(span) match
        case SpanResolution.Unresolved(reason) =>
          defects += Rules.defect(
            DefectKind.span_unresolved,
            s"${span.key}: $reason",
            retryable = true
          )
        case _ => ()
    }
    if record.spans.isEmpty then
      defects += Rules.defect(DefectKind.span_unresolved, "no span cited", retryable = true)
    record.content match
      case Some(RecordContent.OfFinding(f)) =>
        if f.observedEndpoint.isBlank || f.reportedOutcome.isBlank then
          defects += Rules.defect(
            DefectKind.schema_invalid,
            "a finding states its endpoint and outcome",
            retryable = true
          )
      case Some(RecordContent.OfClaim(c)) =>
        if c.proposition.isBlank then
          defects += Rules.defect(
            DefectKind.schema_invalid,
            "a claim states its proposition",
            retryable = true
          )
      case None =>
        defects += Rules.defect(DefectKind.schema_invalid, "no content", retryable = false)
    if record.status != RecordStatus.proposed then
      defects += Rules.defect(
        DefectKind.status_invalid,
        s"a record under validation is proposed, not ${record.status}",
        retryable = false
      )
    val found = defects.result()
    ValidationResult(Gate.deterministic, found.isEmpty, found, ruleSet.versions, now)
