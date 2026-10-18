package com.thinkmorestupidless.experimentproposals.domain.provenance

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** A versioned reference to a record: links name the version they used (FR-042). */
final case class Ref(kind: RecordKind, id: String, version: Int):
  def key: String         = s"${kind.toString}:$id:v$version"
  def unversioned: String = s"${kind.toString}:$id"

object Ref:
  /** Parses `kind:id:vN`; an id may itself contain colons, so the version is read from the end. */
  def parse(key: String): Either[String, Ref] =
    val lastColon  = key.lastIndexOf(":v")
    val firstColon = key.indexOf(':')
    if firstColon < 0 || lastColon <= firstColon then Left(s"not a ref: '$key'")
    else
      val kindText    = key.substring(0, firstColon)
      val id          = key.substring(firstColon + 1, lastColon)
      val versionText = key.substring(lastColon + 2)
      for
        kind <- RecordKind.values
          .find(_.toString == kindText)
          .toRight(s"unknown record kind '$kindText'")
        version <- versionText.toIntOption.toRight(s"not a version: '$versionText'")
      yield Ref(kind, id, version)

/** A reviewer's action on a record, kept in the record's history. */
final case class ReviewAction(
    reviewer: String,
    action: ReviewActionKind,
    at: Instant,
    rationale: String,
    before: Option[String] = None,
    after: Option[String] = None
)

/** Set by impact propagation: something this record depends on changed (FR-045). */
final case class ReviewFlag(changedRef: Ref, reason: String, at: Instant)

/** A classified reason an extraction failed validation or verification. */
final case class Defect(kind: DefectKind, severity: Severity, detail: String, retryable: Boolean)

/** The outcome of one gate on one record. */
final case class ValidationResult(
    gate: Gate,
    passed: Boolean,
    defects: Vector[Defect],
    ruleVersions: Map[String, Int],
    runAt: Instant
)

/** One model interaction, as recorded on a stage execution (FR-043, FR-048). */
final case class ModelCall(
    promptVersion: String,
    model: String,
    effort: String,
    inputTokens: Long,
    outputTokens: Long,
    latencyMs: Long,
    refused: Boolean
)

/** One execution of one stage within a run. */
final case class StageExecution(
    stage: String,
    stageVersion: Int,
    attempt: Int,
    inputRefs: Vector[Ref],
    inputHash: String,
    outputRef: Option[Ref],
    validations: Vector[ValidationResult],
    modelCalls: Vector[ModelCall],
    status: StageStatus,
    defect: Option[Defect],
    startedAt: Instant,
    endedAt: Option[Instant]
)

object Refs:
  given Serializer[Ref]              = Codecs.serializer[Ref]("ref")
  given Serializer[ReviewAction]     = Codecs.serializer[ReviewAction]("review-action")
  given Serializer[ReviewFlag]       = Codecs.serializer[ReviewFlag]("review-flag")
  given Serializer[Defect]           = Codecs.serializer[Defect]("defect")
  given Serializer[ValidationResult] = Codecs.serializer[ValidationResult]("validation-result")
  given Serializer[ModelCall]        = Codecs.serializer[ModelCall]("model-call")
  given Serializer[StageExecution]   = Codecs.serializer[StageExecution]("stage-execution")
