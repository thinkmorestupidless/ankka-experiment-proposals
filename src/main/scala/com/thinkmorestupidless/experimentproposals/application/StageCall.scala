package com.thinkmorestupidless.experimentproposals.application

import com.thinkmorestupidless.experimentproposals.application.entities.StageRunEntity
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.*
import com.thinkmorestupidless.experimentproposals.rules.Rules
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import java.security.MessageDigest
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

// docs:start stage-outcome
/** What a stage's model call came to. The caller records the outcome's records, then completes. */
enum StageOutcome[+O]:
  case Skipped(outputRef: Option[Ref])
  case Done(attempt: Int, value: O, modelCall: ModelCall)
  case Failed(attempt: Int, defect: Defect, modelCall: Option[ModelCall])
// docs:end stage-outcome

/**
 * The helper every stage uses (plan: "every step goes through `stage-run`"): start the stage on the
 * run's provenance entity, skip when it was already done for these inputs, run the call under a
 * timeout, classify a failure as a defect, and record completion or failure.
 */
final class StageCall(client: ComponentClient, val runId: String, val workflow: String):

  private def run = client.forEventSourcedEntity(EntityId(runId))

  def start(
      stage: String,
      stageVersion: Int,
      inputRefs: Vector[Ref],
      inputHash: String
  ): StartOutcome =
    run
      .call(StageRunEntity.start)
      .invoke(StartStage(workflow, stage, stageVersion, inputRefs, inputHash))

  def complete(
      stage: String,
      attempt: Int,
      outputRef: Option[Ref],
      validations: Vector[ValidationResult] = Vector.empty,
      modelCalls: Vector[ModelCall] = Vector.empty,
      status: StageStatus = StageStatus.passed
  ): Done =
    run
      .call(StageRunEntity.complete)
      .invoke(CompleteStage(stage, attempt, outputRef, validations, modelCalls, status))

  def fail(
      stage: String,
      attempt: Int,
      defect: Defect,
      validations: Vector[ValidationResult] = Vector.empty,
      modelCalls: Vector[ModelCall] = Vector.empty
  ): Done =
    run.call(StageRunEntity.fail).invoke(FailStage(stage, attempt, defect, validations, modelCalls))

  /** A stage that needs no model: start, do the work, complete; `AlreadyDone` skips the work. */
  def plain[O](stage: String, stageVersion: Int, inputRefs: Vector[Ref], inputHash: String)(
      work: Int => (O, Option[Ref])
  ): Either[Option[Ref], O] =
    start(stage, stageVersion, inputRefs, inputHash) match
      case StartOutcome.AlreadyDone(ref, _) => Left(ref)
      case StartOutcome.Started(attempt) =>
        try
          val (value, ref) = work(attempt)
          complete(stage, attempt, ref)
          Right(value)
        catch
          case NonFatal(e) =>
            fail(stage, attempt, Rules.defect(DefectKind.other, e.getMessage, retryable = false))
            throw e

  /** A stage whose work is one model call: the outcome says done, skipped or failed, classified. */
  def model[O](
      stage: String,
      stageVersion: Int,
      inputRefs: Vector[Ref],
      inputHash: String,
      promptVersion: String,
      modelName: String,
      effort: String,
      timeout: FiniteDuration
  )(call: => Future[O]): StageOutcome[O] =
    start(stage, stageVersion, inputRefs, inputHash) match
      case StartOutcome.AlreadyDone(ref, _) => StageOutcome.Skipped(ref)
      case StartOutcome.Started(attempt) =>
        val began = System.nanoTime()
        def recorded(refused: Boolean) =
          ModelCall(
            promptVersion,
            modelName,
            effort,
            0L,
            0L,
            (System.nanoTime() - began) / 1_000_000L,
            refused
          )
        try
          val value = ComponentClient.await(call, timeout)
          StageOutcome.Done(attempt, value, recorded(refused = false))
        catch
          case e: CommandError =>
            val (defect, refused) = StageCall.classify(e)
            val mc                = recorded(refused)
            fail(stage, attempt, defect, modelCalls = Vector(mc))
            StageOutcome.Failed(attempt, defect, Some(mc))
          case NonFatal(e) =>
            val defect = Rules.defect(
              DefectKind.other,
              Option(e.getMessage).getOrElse(e.toString),
              retryable = false
            )
            fail(stage, attempt, defect)
            StageOutcome.Failed(attempt, defect, None)

object StageCall:
  def hash(parts: String*): String =
    val digest =
      MessageDigest.getInstance("SHA-256").digest(parts.mkString("\u0000").getBytes("UTF-8"))
    digest.take(16).map("%02x".format(_)).mkString

  /**
   * A refusal is `model_refused` and final; a timeout or outage may pass; anything else is the
   * reply's shape.
   */
  def classify(error: CommandError): (Defect, Boolean) = error.code match
    case ErrorCode.Forbidden =>
      (Rules.defect(DefectKind.model_refused, error.message, retryable = false), true)
    case ErrorCode.Timeout | ErrorCode.Unavailable =>
      (
        Rules.defect(
          DefectKind.other,
          s"the model call did not complete: ${error.message}",
          retryable = true
        ),
        false
      )
    case _ =>
      (Rules.defect(DefectKind.schema_invalid, error.message, retryable = true), false)
