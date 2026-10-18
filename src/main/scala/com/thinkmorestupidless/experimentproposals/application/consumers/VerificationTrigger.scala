package com.thinkmorestupidless.experimentproposals.application.consumers

import com.thinkmorestupidless.experimentproposals.application.entities.{ClaimEntity, FindingEntity}
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  StartVerification,
  VerificationWorkflow
}
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordEvent
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Every proposed or reproposed record starts its verification, under an id derived from the record
 * and the attempt, so a redelivered event addresses the run already started.
 */
final class VerificationTrigger(client: ComponentClient) extends Consumer[RecordEvent, Nothing]:

  def onMessage(event: RecordEvent): Effect = event match
    case Proposed(_, _, _, _, _, _, _)      => start(1)
    case Reproposed(attempt, _, _, _, _, _) => start(attempt)
    case _                                  => effects.ignore()

  private def start(attempt: Int): Effect =
    val recordId = messageContext.subject
    try
      client
        .forWorkflow(EntityId(VerificationWorkflow.idFor(recordId, attempt)))
        .call(VerificationWorkflow.start)
        .invoke(StartVerification(recordId, attempt)): Unit
    catch case e: CommandError if e.code == ErrorCode.Conflict => ()
    effects.done()

object FindingVerificationTrigger
    extends Consumer.Companion[VerificationTrigger, RecordEvent, Nothing](
      componentId = ComponentId("verification-trigger"),
      source = ChangeSource.eventsOf(FindingEntity)
    ):
  def create(ctx: ConsumerContext) = new VerificationTrigger(ctx.componentClient)

object ClaimVerificationTrigger
    extends Consumer.Companion[VerificationTrigger, RecordEvent, Nothing](
      componentId = ComponentId("verification-trigger-claims"),
      source = ChangeSource.eventsOf(ClaimEntity)
    ):
  def create(ctx: ConsumerContext) = new VerificationTrigger(ctx.componentClient)
