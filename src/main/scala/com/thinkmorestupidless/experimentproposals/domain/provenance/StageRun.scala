package com.thinkmorestupidless.experimentproposals.domain.provenance

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** The provenance record of one run: every stage execution, in order (FR-043). */
final case class StageRun(runId: String, workflow: String, stages: Vector[StageExecution]):
  def completed(stage: String, stageVersion: Int, inputHash: String): Option[StageExecution] =
    stages.find(s =>
      s.stage == stage && s.stageVersion == stageVersion && s.inputHash == inputHash &&
        s.status != StageStatus.failed && s.endedAt.isDefined
    )

  def open(stage: String, attempt: Int): Option[StageExecution] =
    stages.find(s => s.stage == stage && s.attempt == attempt && s.endedAt.isEmpty)

  def attemptsOf(stage: String): Int = stages.count(_.stage == stage)

object StageRun:
  def empty(runId: String): StageRun = StageRun(runId, workflow = "", stages = Vector.empty)

enum StageRunEvent:
  case RunOpened(workflow: String, at: Instant)
  case StageStarted(
      stage: String,
      stageVersion: Int,
      attempt: Int,
      inputRefs: Vector[Ref],
      inputHash: String,
      at: Instant
  )
  case StageCompleted(
      stage: String,
      attempt: Int,
      outputRef: Option[Ref],
      validations: Vector[ValidationResult],
      modelCalls: Vector[ModelCall],
      status: StageStatus,
      at: Instant
  )
  case StageFailed(
      stage: String,
      attempt: Int,
      defect: Defect,
      validations: Vector[ValidationResult],
      modelCalls: Vector[ModelCall],
      at: Instant
  )

/** What `start` says: the stage is now running, or it was already done for these inputs. */
enum StartOutcome:
  case Started(attempt: Int)
  case AlreadyDone(outputRef: Option[Ref], attempt: Int)

final case class StartStage(
    workflow: String,
    stage: String,
    stageVersion: Int,
    inputRefs: Vector[Ref],
    inputHash: String
)

final case class CompleteStage(
    stage: String,
    attempt: Int,
    outputRef: Option[Ref],
    validations: Vector[ValidationResult] = Vector.empty,
    modelCalls: Vector[ModelCall] = Vector.empty,
    status: StageStatus = StageStatus.passed
)

final case class FailStage(
    stage: String,
    attempt: Int,
    defect: Defect,
    validations: Vector[ValidationResult] = Vector.empty,
    modelCalls: Vector[ModelCall] = Vector.empty
)

object StageRunCodecs:
  given Serializer[StageRun]      = Codecs.serializer[StageRun]("stage-run")
  given Serializer[StageRunEvent] = Codecs.serializer[StageRunEvent]("stage-run-event")
  given Serializer[StartOutcome]  = Codecs.serializer[StartOutcome]("stage-start-outcome")
  given Serializer[StartStage]    = Codecs.serializer[StartStage]("start-stage")
  given Serializer[CompleteStage] = Codecs.serializer[CompleteStage]("complete-stage")
  given Serializer[FailStage]     = Codecs.serializer[FailStage]("fail-stage")
