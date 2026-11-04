package com.thinkmorestupidless.experimentproposals.api

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.experimentproposals.api.Json.given
import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  AssessmentEntity,
  DecisionEntity,
  DesignEntity,
  FacetEntity,
  GapEntity,
  PackageEntity,
  QuestionEntity,
  Records,
  RequestEntity,
  SearchExecutionEntity,
  SearchPlanEntity,
  SourceEntity,
  StageRunEntity,
  SufficiencyEntity
}
import com.thinkmorestupidless.experimentproposals.application.views.{
  ClaimRows,
  FindingRows,
  RecordRow,
  RequestRow,
  RequestRows,
  SourceRow,
  SourceRows
}
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  InvestigationWorkflow,
  QuestionInvestigationState,
  QuestionInvestigationWorkflow
}
import com.thinkmorestupidless.experimentproposals.domain.conversation.{CardKind, Reference}
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.search.*
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.HttpProblem
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import scala.util.control.NonFatal

/**
 * The records as the API returns them, read once for the API's routes and the assistant's tools, so
 * the ids and the JSON an answer cites are the ones a card fetches (contracts/assistant.md). A
 * record that does not exist is an `HttpProblem.notFound` whose message names the id.
 */
final class Readers(client: ComponentClient, views: ViewClient):
  private def entity(id: String) = client.forEventSourcedEntity(EntityId(id))

  def investigation(id: String): WorkflowView =
    val lifecycle = client.forWorkflow(EntityId(id)).lifecycle(InvestigationWorkflow).invoke()
    val state     = client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.status).invoke()
    WorkflowView(
      "investigation",
      id,
      lifecycle.status,
      lifecycle.pendingStep,
      lifecycle.failure,
      lifecycle.isTerminal,
      None,
      writeToString(state)
    )

  def requestOf(id: String): CustomerRequest =
    val req = entity(id).call(RequestEntity.get).invoke()
    if !req.registered then
      throw HttpProblem.notFound(s"no request '$id'; request ids look like rq-…")
    req

  def facetsOf(req: CustomerRequest): Vector[Facet] =
    req.facets.map(f => entity(f.id).call(FacetEntity.get).invoke())

  def questionsOf(req: CustomerRequest): Vector[ScientificQuestion] =
    req.questionIds.map(q => entity(q).call(QuestionEntity.get).invoke())

  def request(id: String): RequestView =
    val req = requestOf(id)
    RequestView(req, facetsOf(req), questionsOf(req), investigation(id))

  def requests(): Vector[RequestRow] =
    views.forView(RequestRows).ask(RequestRows.newestFirst)

  def questionInvestigation(id: String): Option[QuestionInvestigationState] =
    val st = client.forWorkflow(EntityId(id)).call(QuestionInvestigationWorkflow.status).invoke()
    Option.when(st.started)(st)

  def planOf(id: String): SearchPlan           = entity(id).call(SearchPlanEntity.get).invoke()
  def executionOf(id: String): SearchExecution = entity(id).call(SearchExecutionEntity.get).invoke()

  def question(id: String): QuestionView =
    val q = entity(id).call(QuestionEntity.get).invoke()
    if !q.registered then
      throw HttpProblem.notFound(s"no question '$id'; question ids look like rq-…:q4")
    val inv = questionInvestigation(id)
    val assessments =
      inv.toVector.flatMap(_.assessmentIds).map(a => entity(a).call(AssessmentEntity.get).invoke())
    val sufficiency =
      inv.toVector
        .flatMap(_.sufficiencyIds)
        .map(s => entity(s).call(SufficiencyEntity.get).invoke())
    val gaps = inv.toVector
      .flatMap(s => s.gapIds ++ s.coverageGapIds)
      .distinct
      .map(g => entity(g).call(GapEntity.get).invoke())
    val plan       = inv.flatMap(_.planId).map(planOf).filter(_.registered)
    val executions = inv.toVector.flatMap(_.executionIds).map(executionOf)
    QuestionView(q, inv, plan, executions, assessments, sufficiency, gaps)

  private def resolved(span: SourceSpan): SpanView =
    entity(span.sourceId).call(SourceEntity.resolveSpan).invoke(span) match
      case SpanResolution.Resolved(text) => SpanView(span, resolves = true, Some(text))
      case SpanResolution.Unresolved(_)  => SpanView(span, resolves = false, None)

  def record(id: String): RecordView =
    val companion = Records
      .companionFor(id)
      .getOrElse(
        throw HttpProblem.notFound(s"not a record id: '$id'; record ids look like src:…:v1:fd1")
      )
    val r = entity(id).call(companion.get).invoke()
    if !r.registered then throw HttpProblem.notFound(s"no record '$id'")
    RecordView(r, r.spans.map(resolved))

  def packageOf(id: String): PackageView =
    val p = entity(id).call(PackageEntity.get).invoke()
    if !p.registered then throw HttpProblem.notFound(s"no package '$id'")
    PackageView(p, p.includedSpans.map(resolved))

  def approachesOf(id: String): ApproachesView =
    val state      = client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.status).invoke()
    val approaches = state.approachIds.map(a => entity(a).call(ApproachEntity.get).invoke())
    val decision = state.decisionId
      .map(d => entity(d).call(DecisionEntity.get).invoke())
      .filter(_.registered)
    ApproachesView(approaches, state.comparison, decision)

  def designOf(id: String): DesignState =
    val d = entity(id).call(DesignEntity.get).invoke()
    if !d.registered then
      throw HttpProblem.notFound(s"no design '$id'; design ids look like rq-…:design")
    d

  def sources(): Vector[SourceRow] = views.forView(SourceRows).all()

  def reviewQueue(): Vector[RecordRow] =
    views.forView(FindingRows).ask(FindingRows.byStatus, "status" -> "requires_review") ++
      views.forView(ClaimRows).ask(ClaimRows.byStatus, "status" -> "requires_review")

  /** Whether a reference names a record that exists; any failure to read it is "no". */
  def resolves(ref: Reference): Boolean =
    try
      CardKind.fromWire(ref.kind) match
        case None                                     => false
        case Some(CardKind.request | CardKind.facets) => requestOf(ref.id).registered
        case Some(CardKind.candidates)                => approachesOf(ref.id).approaches.nonEmpty
        case Some(CardKind.decision)                  => approachesOf(ref.id).decision.nonEmpty
        case Some(CardKind.impact)                    => requestOf(ref.id).registered
        case Some(CardKind.question) => entity(ref.id).call(QuestionEntity.get).invoke().registered
        case Some(CardKind.search_plan)      => planOf(ref.id).registered
        case Some(CardKind.search_execution) => executionOf(ref.id).registered
        case Some(CardKind.source) => entity(ref.id).call(SourceEntity.get).invoke().registered
        case Some(CardKind.finding | CardKind.claim) => record(ref.id).record.registered
        case Some(CardKind.assessment) =>
          entity(ref.id).call(AssessmentEntity.get).invoke().registered
        case Some(CardKind.sufficiency) =>
          entity(ref.id).call(SufficiencyEntity.get).invoke().registered
        case Some(CardKind.`package`) => packageOf(ref.id).`package`.registered
        case Some(CardKind.design)    => designOf(ref.id).registered
        case Some(CardKind.stage_run) =>
          entity(ref.id).call(StageRunEntity.get).invoke().stages.nonEmpty
    catch case NonFatal(_) => false
