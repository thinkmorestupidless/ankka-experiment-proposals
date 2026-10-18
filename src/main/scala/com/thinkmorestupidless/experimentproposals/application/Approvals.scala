package com.thinkmorestupidless.experimentproposals.application

import com.thinkmorestupidless.experimentproposals.application.entities.DesignEntity
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  ApprovalOutcome,
  InvestigationWorkflow
}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.ApprovalDecision
import com.thinkmorestupidless.experimentproposals.domain.design.{DesignState, RecordApproval}
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/** A reviewer's decision on a design version: one door for the API and the review page (FR-042). */
object Approvals:
  final case class Problem(status: Int, message: String) extends RuntimeException(message)

  def record(
      client: ComponentClient,
      designId: String,
      version: Int,
      decision: String,
      reviewer: String,
      rationale: String,
      requestedChanges: Vector[String]
  ): DesignState =
    val d = ApprovalDecision.values
      .find(_.toString == decision.trim.replace(' ', '_'))
      .getOrElse(throw Problem(400, s"unknown decision '$decision'"))
    val state =
      try
        client
          .forEventSourcedEntity(EntityId(designId))
          .call(DesignEntity.recordApproval)
          .invoke(
            RecordApproval(
              version,
              d,
              reviewer,
              rationale,
              requestedChanges.map(_.trim).filter(_.nonEmpty)
            )
          )
      catch
        case e: CommandError if e.code == ErrorCode.Conflict => throw Problem(409, e.message)
        case e: CommandError if e.code == ErrorCode.NotFound => throw Problem(404, e.message)
        case e: CommandError                                 => throw Problem(400, e.message)
    // A design generated outside an investigation has no workflow to resume.
    try
      client
        .forWorkflow(EntityId(DesignState.requestOf(designId)))
        .call(InvestigationWorkflow.approvalRecorded)
        .invoke(ApprovalOutcome(version, d, requestedChanges))
    catch case e: CommandError if e.code == ErrorCode.NotFound => ()
    state
