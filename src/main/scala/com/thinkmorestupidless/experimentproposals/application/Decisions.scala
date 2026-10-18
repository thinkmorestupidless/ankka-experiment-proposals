package com.thinkmorestupidless.experimentproposals.application

import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  DecisionEntity
}
import com.thinkmorestupidless.experimentproposals.application.workflows.InvestigationWorkflow
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.RecordKind
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/**
 * A reviewer's decision between the candidates: one door for the API and the review page (FR-038).
 */
object Decisions:
  final case class Problem(status: Int, message: String) extends RuntimeException(message)

  def decide(
      client: ComponentClient,
      requestId: String,
      selectedApproachId: String,
      rejected: Vector[(String, String)],
      decidedBy: String,
      rationale: String
  ): DecisionRecord =
    val state = client.forWorkflow(EntityId(requestId)).call(InvestigationWorkflow.status).invoke()
    val comparison =
      state.comparison.getOrElse(throw Problem(409, "no candidates have been compared yet"))
    Configured.reviewers.check(decidedBy).left.foreach(p => throw Problem(400, p))
    if !state.approachIds.contains(selectedApproachId) then
      throw Problem(400, s"'$selectedApproachId' is not a candidate of $requestId")
    val others      = state.approachIds.filterNot(_ == selectedApproachId)
    val reasons     = rejected.toMap
    val unexplained = others.filterNot(id => reasons.get(id).exists(_.trim.nonEmpty))
    if unexplained.nonEmpty then
      throw Problem(
        400,
        s"every rejected approach needs its reason; missing for ${unexplained.mkString(", ")}"
      )
    val decisionId = DecisionRecord.idFor(requestId, 1)
    val decision =
      try
        client
          .forEventSourcedEntity(EntityId(decisionId))
          .call(DecisionEntity.decide)
          .invoke(
            Decide(
              Ref(RecordKind.request, requestId, 1),
              comparison,
              Ref(RecordKind.approach, selectedApproachId, 1),
              others.map(id => RejectedApproach(Ref(RecordKind.approach, id, 1), reasons(id))),
              decidedBy,
              rationale
            )
          )
      catch
        case e: CommandError if e.code == ErrorCode.Conflict => throw Problem(409, e.message)
        case e: CommandError                                 => throw Problem(400, e.message)
    client
      .forEventSourcedEntity(EntityId(selectedApproachId))
      .call(ApproachEntity.selected)
      .invoke(SelectedIn(decision.decidedBy, decisionId))
    others.foreach(id =>
      client
        .forEventSourcedEntity(EntityId(id))
        .call(ApproachEntity.rejectedInDecision)
        .invoke(RejectedIn(decision.decidedBy, decisionId, reasons(id)))
    )
    client.forWorkflow(EntityId(requestId)).call(InvestigationWorkflow.decided).invoke(decisionId)
    decision
