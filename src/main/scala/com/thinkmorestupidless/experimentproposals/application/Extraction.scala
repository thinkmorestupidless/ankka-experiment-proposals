package com.thinkmorestupidless.experimentproposals.application

import com.thinkmorestupidless.experimentproposals.application.entities.{ClaimEntity, FindingEntity}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, Ref}
import com.thinkmorestupidless.experimentproposals.domain.sources.Section
import com.thinkmorestupidless.experimentproposals.rules.Deterministic

/** Turns the extractor's reply into records to propose, with quotations located as spans. */
object Extraction:
  final case class Prepared(id: String, request: ProposeRecord)

  /** The section keys the model quotes by: `S<n>` in the order the sections were shown. */
  def keyed(sections: Vector[Section]): Map[String, Section] =
    sections.zipWithIndex.map { case (s, i) => s"S${i + 1}" -> s }.toMap

  def prepare(
      sourceId: String,
      version: Int,
      sections: Vector[Section],
      set: ExtractionSet,
      generatedBy: String,
      firstFinding: Int = 1,
      firstClaim: Int = 1
  ): Either[Defect, Vector[Prepared]] =
    val byKey     = keyed(sections)
    val sourceRef = Ref(RecordKind.source, sourceId, version)
    val findings = set.findings.zipWithIndex.map { case (p, i) =>
      for
        content <- Deterministic.finding(p)
        spans   <- Deterministic.locateAll(sourceId, version, byKey, p.quotations)
      yield (
        p.key,
        Prepared(
          FindingEntity.idFor(sourceId, version, firstFinding + i),
          ProposeRecord(
            "finding",
            sourceRef,
            RecordContent.OfFinding(content),
            spans,
            generatedBy,
            Vector(sourceRef)
          )
        )
      )
    }
    findings.collectFirst { case Left(d) => d } match
      case Some(defect) => Left(defect)
      case None =>
        val ok      = findings.collect { case Right(v) => v }
        val idOfKey = ok.map { case (key, prepared) => key -> prepared.id }.toMap
        val claims = set.claims.zipWithIndex.map { case (p, i) =>
          val refs = p.findings.flatMap(idOfKey.get).map(id => Ref(RecordKind.finding, id, 1))
          for
            content <- Deterministic.claim(p, refs)
            spans   <- Deterministic.locateAll(sourceId, version, byKey, p.quotations)
          yield Prepared(
            ClaimEntity.idFor(sourceId, version, firstClaim + i),
            ProposeRecord(
              "claim",
              sourceRef,
              RecordContent.OfClaim(content),
              spans,
              generatedBy,
              sourceRef +: refs
            )
          )
        }
        claims.collectFirst { case Left(d) => d } match
          case Some(defect) => Left(defect)
          case None         => Right(ok.map(_._2) ++ claims.collect { case Right(v) => v })

  /** A single record from a re-extraction reply, of the kind the record is. */
  def prepareOne(
      sourceId: String,
      version: Int,
      sections: Vector[Section],
      set: ExtractionSet,
      record: EvidenceRecord
  ): Either[
    Defect,
    (RecordContent, Vector[com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan])
  ] =
    val byKey = keyed(sections)
    record.content match
      case Some(RecordContent.OfFinding(_)) =>
        set.findings.headOption
          .toRight(
            com.thinkmorestupidless.experimentproposals.rules.Rules.defect(
              DefectKind.schema_invalid,
              "the re-extraction returned no finding",
              retryable = true
            )
          )
          .flatMap(p =>
            for
              c <- Deterministic.finding(p);
              s <- Deterministic.locateAll(sourceId, version, byKey, p.quotations)
            yield (RecordContent.OfFinding(c), s)
          )
      case Some(RecordContent.OfClaim(old)) =>
        set.claims.headOption
          .toRight(
            com.thinkmorestupidless.experimentproposals.rules.Rules.defect(
              DefectKind.schema_invalid,
              "the re-extraction returned no claim",
              retryable = true
            )
          )
          .flatMap(p =>
            for
              c <- Deterministic.claim(p, old.findingRefs);
              s <- Deterministic.locateAll(sourceId, version, byKey, p.quotations)
            yield (RecordContent.OfClaim(c), s)
          )
      case None =>
        Left(
          com.thinkmorestupidless.experimentproposals.rules.Rules
            .defect(DefectKind.other, "the record has no content", retryable = false)
        )
