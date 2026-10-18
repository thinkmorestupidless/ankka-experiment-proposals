package com.thinkmorestupidless.experimentproposals.application.consumers

import com.thinkmorestupidless.experimentproposals.application.entities.{
  ClaimEntity,
  FindingEntity,
  SourceEntity
}
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  ImpactWorkflow,
  StartImpact
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.RecordKind
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordEvent
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceEvent
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

/** Which changes propagate (FR-045), as a pure function the consumers and their test share. */
object ImpactTrigger:
  /** A source's change: a new status, or a new version superseding the previous one. */
  def ofSource(event: SourceEvent, subject: String, latestVersion: Int): Option[StartImpact] =
    event match
      case SourceEvent.StatusChanged(status, reason, by, _) =>
        Some(
          StartImpact(
            Ref(RecordKind.source, subject, latestVersion),
            s"the source's status changed to $status ($reason, by $by)"
          )
        )
      case SourceEvent.ContentFetched(version, _, _, _, _, _) if version > 1 =>
        Some(
          StartImpact(
            Ref(RecordKind.source, subject, version - 1),
            s"a new version ($version) of the source was fetched"
          )
        )
      case _ => None

  /** A record's change of standing: reproposed, rejected, quarantined, or sent to review. */
  def ofRecord(kind: RecordKind, event: RecordEvent, subject: String): Option[StartImpact] =
    event match
      case RecordEvent.Reproposed(attempt, _, _, _, _, _) =>
        Some(StartImpact(Ref(kind, subject, 1), s"the record was reproposed (attempt $attempt)"))
      case RecordEvent.Rejected(attempt, defect, _, _) =>
        Some(
          StartImpact(
            Ref(kind, subject, 1),
            s"the record was rejected at attempt $attempt: ${defect.kind}"
          )
        )
      case RecordEvent.Quarantined(attempt, defect, _) =>
        Some(
          StartImpact(
            Ref(kind, subject, 1),
            s"the record was quarantined at attempt $attempt: ${defect.kind}"
          )
        )
      case RecordEvent.RequiresReview(attempt, reason, _, _) =>
        Some(
          StartImpact(
            Ref(kind, subject, 1),
            s"the record requires review at attempt $attempt: $reason"
          )
        )
      case _ => None

/** The source's changes start an impact walk under an id derived from the change. */
final class SourceImpactTrigger(client: ComponentClient) extends Consumer[SourceEvent, Nothing]:
  def onMessage(event: SourceEvent): Effect =
    val subject = messageContext.subject
    val latest = event match
      case SourceEvent.StatusChanged(_, _, _, _) =>
        client
          .forEventSourcedEntity(EntityId(subject))
          .call(SourceEntity.get)
          .invoke()
          .latest
          .map(_.version)
          .getOrElse(1)
      case _ => 1
    ImpactTrigger.ofSource(event, subject, latest).foreach { start =>
      try
        client
          .forWorkflow(
            EntityId(ImpactWorkflow.idFor(start.changedRef, messageContext.sequenceNumber))
          )
          .call(ImpactWorkflow.start)
          .invoke(start): Unit
      catch case e: CommandError if e.code == ErrorCode.Conflict => ()
    }
    effects.done()

object SourceImpactTrigger
    extends Consumer.Companion[SourceImpactTrigger, SourceEvent, Nothing](
      componentId = ComponentId("impact-trigger-sources"),
      source = ChangeSource.eventsOf(SourceEntity)
    ):
  def create(ctx: ConsumerContext) = new SourceImpactTrigger(ctx.componentClient)

/** A finding's or claim's change of standing starts an impact walk. */
final class RecordImpactTrigger(kind: RecordKind, client: ComponentClient)
    extends Consumer[RecordEvent, Nothing]:
  def onMessage(event: RecordEvent): Effect =
    ImpactTrigger.ofRecord(kind, event, messageContext.subject).foreach { start =>
      try
        client
          .forWorkflow(
            EntityId(ImpactWorkflow.idFor(start.changedRef, messageContext.sequenceNumber))
          )
          .call(ImpactWorkflow.start)
          .invoke(start): Unit
      catch case e: CommandError if e.code == ErrorCode.Conflict => ()
    }
    effects.done()

object FindingImpactTrigger
    extends Consumer.Companion[RecordImpactTrigger, RecordEvent, Nothing](
      componentId = ComponentId("impact-trigger-findings"),
      source = ChangeSource.eventsOf(FindingEntity)
    ):
  def create(ctx: ConsumerContext) =
    new RecordImpactTrigger(RecordKind.finding, ctx.componentClient)

object ClaimImpactTrigger
    extends Consumer.Companion[RecordImpactTrigger, RecordEvent, Nothing](
      componentId = ComponentId("impact-trigger-claims"),
      source = ChangeSource.eventsOf(ClaimEntity)
    ):
  def create(ctx: ConsumerContext) = new RecordImpactTrigger(RecordKind.claim, ctx.componentClient)
