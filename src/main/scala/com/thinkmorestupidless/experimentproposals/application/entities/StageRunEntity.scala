package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.StageStatus
import com.thinkmorestupidless.experimentproposals.domain.provenance.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.StageRunCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.provenance.StageRunEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One run's provenance (FR-043). `start` for a stage already completed with the same inputs replies
 * `AlreadyDone` and persists nothing, which is how a workflow step run twice skips its work.
 */
final class StageRunEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[StageRun, StageRunEvent]:

  private val runId: String = context.entityId

  def emptyState: StageRun = StageRun.empty(runId)

  def applyEvent(event: StageRunEvent): StageRun = event match
    case RunOpened(workflow, _) => currentState.copy(workflow = workflow)
    case StageStarted(stage, stageVersion, attempt, inputRefs, inputHash, at) =>
      currentState.copy(stages =
        currentState.stages :+ StageExecution(
          stage,
          stageVersion,
          attempt,
          inputRefs,
          inputHash,
          None,
          Vector.empty,
          Vector.empty,
          StageStatus.passed,
          None,
          at,
          None
        )
      )
    case StageCompleted(stage, attempt, outputRef, validations, modelCalls, status, at) =>
      update(stage, attempt)(
        _.copy(
          outputRef = outputRef,
          validations = validations,
          modelCalls = modelCalls,
          status = status,
          endedAt = Some(at)
        )
      )
    case StageFailed(stage, attempt, defect, validations, modelCalls, at) =>
      update(stage, attempt)(
        _.copy(
          validations = validations,
          modelCalls = modelCalls,
          status = StageStatus.failed,
          defect = Some(defect),
          endedAt = Some(at)
        )
      )

  private def update(stage: String, attempt: Int)(f: StageExecution => StageExecution): StageRun =
    currentState.copy(stages =
      currentState.stages.map(s => if s.stage == stage && s.attempt == attempt then f(s) else s)
    )

  def start(request: StartStage): Effect[StartOutcome] =
    currentState.completed(request.stage, request.stageVersion, request.inputHash) match
      case Some(done) => effects.reply(StartOutcome.AlreadyDone(done.outputRef, done.attempt))
      case None =>
        val attempt = currentState.attemptsOf(request.stage) + 1
        val now     = Instant.now()
        val opened =
          if currentState.workflow.isEmpty then Vector(RunOpened(request.workflow, now))
          else Vector.empty
        effects
          .persistAll(
            opened :+ StageStarted(
              request.stage,
              request.stageVersion,
              attempt,
              request.inputRefs,
              request.inputHash,
              now
            )
          )
          .thenReply(_ => StartOutcome.Started(attempt))

  def complete(request: CompleteStage): Effect[Done] =
    currentState.open(request.stage, request.attempt) match
      case None =>
        effects.error(
          s"no open attempt ${request.attempt} of stage '${request.stage}'",
          ErrorCode.Conflict
        )
      case Some(_) =>
        effects
          .persist(
            StageCompleted(
              request.stage,
              request.attempt,
              request.outputRef,
              request.validations,
              request.modelCalls,
              request.status,
              Instant.now()
            )
          )
          .thenReply(_ => Done)

  def fail(request: FailStage): Effect[Done] =
    currentState.open(request.stage, request.attempt) match
      case None =>
        effects.error(
          s"no open attempt ${request.attempt} of stage '${request.stage}'",
          ErrorCode.Conflict
        )
      case Some(_) =>
        effects
          .persist(
            StageFailed(
              request.stage,
              request.attempt,
              request.defect,
              request.validations,
              request.modelCalls,
              Instant.now()
            )
          )
          .thenReply(_ => Done)

  def get: ReadOnlyEffect[StageRun] = effects.reply(currentState)

object StageRunEntity
    extends EventSourcedEntity.Companion[StageRunEntity, StageRun, StageRunEvent](
      componentId = ComponentId("stage-run"),
      stateSerializer = summon[Serializer[StageRun]],
      eventSerializer = summon[Serializer[StageRunEvent]]
    ):

  def create(context: EventSourcedEntityContext) = new StageRunEntity(context)

  val start    = command("start")(_.start)
  val complete = command("complete")(_.complete)
  val fail     = command("fail")(_.fail)
  val get      = query("get")(_.get)
