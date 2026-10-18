package com.thinkmorestupidless.experimentproposals.api

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.experimentproposals.api.Json.given
import com.thinkmorestupidless.experimentproposals.application.{Approvals, Decisions}
import com.thinkmorestupidless.experimentproposals.evaluation.Report
import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  DecisionEntity,
  DesignEntity,
  PackageEntity,
  SearchExecutionEntity,
  SearchPlanEntity,
  AssessmentEntity,
  ClaimEntity,
  FacetEntity,
  GapEntity,
  QuestionEntity,
  Records,
  RequestEntity,
  SourceEntity,
  StageRunEntity,
  SufficiencyEntity
}
import com.thinkmorestupidless.experimentproposals.adapters.{AdapterPermanent, InternalAdapter}
import com.thinkmorestupidless.experimentproposals.application.views.{
  ClaimRows,
  DependentRow,
  Dependents,
  DesignRows,
  ExecutionRows,
  FindingRows,
  MeasurementRows,
  SourceRows
}
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  ImpactWorkflow,
  AssessRecords,
  ExecutePlan,
  ExtractFromSource,
  IngestionWorkflow,
  InvestigationWorkflow,
  QuestionInvestigationState,
  QuestionInvestigationWorkflow,
  StartIngestion,
  StartInvestigation,
  VerificationWorkflow
}
import com.thinkmorestupidless.experimentproposals.config.{Configured, Settings}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.{
  ClaimKind,
  FacetAction,
  RecordStatus,
  ReviewActionKind,
  SourceStatus
}
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ids, Ref, Reviewer}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.RecordKind
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.domain.search.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.retrieval.{Context, Searcher}
import com.thinkmorestupidless.experimentproposals.rules.{FacetRules, Rules}
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/**
 * The operator's and the tests' API (contracts/http-api.md). Thin: every rule lives in an entity.
 */
final class ApiEndpoint(settings: Settings, client: ComponentClient, views: ViewClient)
    extends HttpEndpoint("/api/v1"):

  val acl: Acl = Auth.acl(settings.apiToken)

  private def source(id: String)        = client.forEventSourcedEntity(EntityId(id))
  private def requestEntity(id: String) = client.forEventSourcedEntity(EntityId(id))
  private def facetEntity(id: String)   = client.forEventSourcedEntity(EntityId(id))

  get("/reviewers") { () =>
    Configured.reviewers.all: Vector[Reviewer]
  }

  get("/runs/{runId}") { (runId: String) =>
    client.forEventSourcedEntity(EntityId(runId)).call(StageRunEntity.get).invoke()
  }

  get("/workflows/{kind}/{id}") { (kind: String, id: String) =>
    kind match
      case "ingestion" =>
        val lifecycle = client.forWorkflow(EntityId(id)).lifecycle(IngestionWorkflow).invoke()
        val state     = client.forWorkflow(EntityId(id)).call(IngestionWorkflow.status).invoke()
        WorkflowView(
          kind,
          id,
          lifecycle.status,
          lifecycle.pendingStep,
          lifecycle.failure,
          lifecycle.isTerminal,
          state.sourceId,
          writeToString(state)
        )
      case "investigation" => investigation(id)
      case "question-investigation" =>
        val lifecycle =
          client.forWorkflow(EntityId(id)).lifecycle(QuestionInvestigationWorkflow).invoke()
        val state =
          client.forWorkflow(EntityId(id)).call(QuestionInvestigationWorkflow.status).invoke()
        WorkflowView(
          kind,
          id,
          lifecycle.status,
          lifecycle.pendingStep,
          lifecycle.failure,
          lifecycle.isTerminal,
          None,
          writeToString(state)
        )
      case "verification" =>
        val lifecycle = client.forWorkflow(EntityId(id)).lifecycle(VerificationWorkflow).invoke()
        val state     = client.forWorkflow(EntityId(id)).call(VerificationWorkflow.status).invoke()
        WorkflowView(
          kind,
          id,
          lifecycle.status,
          lifecycle.pendingStep,
          lifecycle.failure,
          lifecycle.isTerminal,
          None,
          writeToString(state)
        )
      case other => throw HttpProblem.notFound(s"no workflow kind '$other'")
  }

  private def investigation(id: String): WorkflowView =
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

  private def facetsOf(req: CustomerRequest): Vector[Facet] =
    req.facets.map(f => facetEntity(f.id).call(FacetEntity.get).invoke())

  private def questionsOf(req: CustomerRequest): Vector[ScientificQuestion] =
    req.questionIds.map(q =>
      client.forEventSourcedEntity(EntityId(q)).call(QuestionEntity.get).invoke()
    )

  /**
   * The endpoint refuses an unlisted reviewer early; the entity refuses it again before persisting.
   */
  private def reviewerOrRefuse(name: String): Unit =
    Configured.reviewers
      .check(name)
      .left
      .foreach(problem => throw HttpProblem.badRequest(s"unknown_reviewer: $problem"))

  // ── requests and facets (US1) ────────────────────────────────────────────────

  postBody("/requests") { (body: SubmitRequestBody) =>
    val id = Ids.mint("rq")
    val req = requestEntity(id)
      .call(RequestEntity.submit)
      .invoke(SubmitRequest(body.text, body.submittedBy))
    client
      .forWorkflow(EntityId(id))
      .call(InvestigationWorkflow.start)
      .invoke(StartInvestigation(id, req.text))
    Respond(Created(id, 1, Some(id)), status = 201)
  }

  get("/requests/{id}") { (id: String) =>
    val req = requestEntity(id).call(RequestEntity.get).invoke()
    if !req.registered then throw HttpProblem.notFound(s"no request '$id'")
    RequestView(req, facetsOf(req), questionsOf(req), investigation(id))
  }

  get("/requests/{id}/facets") { (id: String) =>
    val req = requestEntity(id).call(RequestEntity.get).invoke()
    if !req.registered then throw HttpProblem.notFound(s"no request '$id'")
    facetsOf(req)
  }

  get("/requests/{id}/proposal") { (id: String) =>
    val req = requestEntity(id).call(RequestEntity.get).invoke()
    if !req.registered then throw HttpProblem.notFound(s"no request '$id'")
    FacetRules.proposalStatus(id, facetsOf(req))
  }

  postBody("/facets/{id}/review") { (id: String, body: ReviewBody) =>
    val action = FacetAction.values
      .find(_.toString == body.action)
      .getOrElse(
        throw HttpProblem.badRequest(s"action must be one of ${FacetAction.values.mkString(", ")}")
      )
    reviewerOrRefuse(body.reviewer)
    Respond(
      facetEntity(id)
        .call(FacetEntity.review)
        .invoke(ReviewFacet(action, body.reviewer, body.rationale, body.statement)),
      status = 201
    )
  }

  postBody("/facets/{id}/assume") { (id: String, body: AssumeBody) =>
    reviewerOrRefuse(body.reviewer)
    Respond(
      facetEntity(id)
        .call(FacetEntity.assume)
        .invoke(AssumeFacet(body.value, body.reviewer, body.rationale)),
      status = 201
    )
  }

  postBody("/requests/{id}/facets:confirmed") { (id: String, body: ReviewerBody) =>
    reviewerOrRefuse(body.reviewer)
    ReviewEndpoint.confirm(client, id, body.reviewer)
    investigation(id)
  }

  post("/requests/{id}/round-trip:rerun") { (id: String) =>
    client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.rerunRoundTrip).invoke()
    Respond(investigation(id), status = 202)
  }

  // ── questions (US2) ──────────────────────────────────────────────────────────

  private def questionInvestigation(id: String): Option[QuestionInvestigationState] =
    val st = client.forWorkflow(EntityId(id)).call(QuestionInvestigationWorkflow.status).invoke()
    Option.when(st.started)(st)

  private def investigationOfQuestion(id: String): WorkflowView =
    val lifecycle =
      client.forWorkflow(EntityId(id)).lifecycle(QuestionInvestigationWorkflow).invoke()
    val state = client.forWorkflow(EntityId(id)).call(QuestionInvestigationWorkflow.status).invoke()
    WorkflowView(
      "question-investigation",
      id,
      lifecycle.status,
      lifecycle.pendingStep,
      lifecycle.failure,
      lifecycle.isTerminal,
      None,
      writeToString(state)
    )

  get("/questions/{id}") { (id: String) =>
    val q = client.forEventSourcedEntity(EntityId(id)).call(QuestionEntity.get).invoke()
    if !q.registered then throw HttpProblem.notFound(s"no question '$id'")
    val inv = questionInvestigation(id)
    val assessments = inv.toVector
      .flatMap(_.assessmentIds)
      .map(a => client.forEventSourcedEntity(EntityId(a)).call(AssessmentEntity.get).invoke())
    val sufficiency = inv.toVector
      .flatMap(_.sufficiencyIds)
      .map(sid => client.forEventSourcedEntity(EntityId(sid)).call(SufficiencyEntity.get).invoke())
    val gaps = inv.toVector
      .flatMap(s => s.gapIds ++ s.coverageGapIds)
      .distinct
      .map(g => client.forEventSourcedEntity(EntityId(g)).call(GapEntity.get).invoke())
    val plan       = inv.flatMap(_.planId).map(planOf).filter(_.registered)
    val executions = inv.toVector.flatMap(_.executionIds).map(executionOf)
    QuestionView(q, inv, plan, executions, assessments, sufficiency, gaps)
  }

  // ── packages, candidates and the decision (US7) ──────────────────────────────

  postBody("/requests/{id}/packages") { (id: String, body: ReviewerBody) =>
    val by = if body.reviewer.isBlank then "operator" else body.reviewer
    Configured.actor(by).left.foreach(p => throw HttpProblem.badRequest(p))
    client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.packageEvidence).invoke(by)
    Respond(investigation(id), status = 202)
  }

  get("/packages/{id}") { (id: String) =>
    val p = client.forEventSourcedEntity(EntityId(id)).call(PackageEntity.get).invoke()
    if !p.registered then throw HttpProblem.notFound(s"no package '$id'")
    val passages = p.includedSpans.map { span =>
      source(span.sourceId).call(SourceEntity.resolveSpan).invoke(span) match
        case SpanResolution.Resolved(text) => SpanView(span, true, Some(text))
        case SpanResolution.Unresolved(_)  => SpanView(span, false, None)
    }
    PackageView(p, passages)
  }

  private def approachesOf(id: String): ApproachesView =
    val state = client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.status).invoke()
    val approaches =
      state.approachIds.map(a =>
        client.forEventSourcedEntity(EntityId(a)).call(ApproachEntity.get).invoke()
      )
    val decision = state.decisionId
      .map(d => client.forEventSourcedEntity(EntityId(d)).call(DecisionEntity.get).invoke())
      .filter(_.registered)
    ApproachesView(approaches, state.comparison, decision)

  get("/requests/{id}/approaches")((id: String) => approachesOf(id))

  postBody("/requests/{id}/decide") { (id: String, body: DecideBody) =>
    try
      Respond(
        Decisions.decide(
          client,
          id,
          body.selectedApproachId,
          body.rejected.map(r => r.approachId -> r.reason),
          body.decidedBy,
          body.rationale
        ),
        status = 201
      )
    catch
      case Decisions.Problem(409, m) => throw HttpProblem.conflict(m)
      case Decisions.Problem(_, m)   => throw HttpProblem.badRequest(m)
  }

  // ── impact (US10) ────────────────────────────────────────────────────────────

  /** Whether a record's flags name the changed record (any version of it). */
  private def flaggedBy(kind: RecordKind, id: String, changed: Ref): Boolean =
    def named(
        flags: Vector[com.thinkmorestupidless.experimentproposals.domain.provenance.ReviewFlag]
    ) =
      flags.exists(f => f.changedRef.kind == changed.kind && f.changedRef.id == changed.id)
    val entity = client.forEventSourcedEntity(EntityId(id))
    try
      kind match
        case RecordKind.finding | RecordKind.claim =>
          Records.companionFor(id).exists(c => named(entity.call(c.get).invoke().flags))
        case RecordKind.assessment  => named(entity.call(AssessmentEntity.get).invoke().flags)
        case RecordKind.sufficiency => named(entity.call(SufficiencyEntity.get).invoke().flags)
        case RecordKind.`package`   => named(entity.call(PackageEntity.get).invoke().flags)
        case RecordKind.approach    => named(entity.call(ApproachEntity.get).invoke().flags)
        case RecordKind.question    => named(entity.call(QuestionEntity.get).invoke().flags)
        case RecordKind.design =>
          entity
            .call(DesignEntity.get)
            .invoke()
            .flags
            .exists(f => f.changedRef.kind == changed.kind && f.changedRef.id == changed.id)
        case _ => false
    catch case e: CommandError if e.code == ErrorCode.NotFound => false

  /** The dependents reachable from a ref, breadth first, with whether each is flagged by it. */
  get("/impact") { () =>
    val ref =
      Ref.parse(query.required[String]("ref")).fold(p => throw HttpProblem.badRequest(p), identity)
    val seen     = scala.collection.mutable.LinkedHashMap.empty[String, (DependentRow, Int)]
    var frontier = Vector(ref.unversioned -> 0)
    var visited  = Set(ref.unversioned)
    while frontier.nonEmpty && frontier.head._2 < 8 do
      val next = Vector.newBuilder[(String, Int)]
      frontier.foreach { (unversioned, depth) =>
        views.forView(Dependents).ask(Dependents.ofAnyVersion, 5000, "ref" -> unversioned).foreach {
          row =>
            if !seen.contains(row.dependentKey) then seen += row.dependentKey -> (row, depth + 1)
            val u = s"${row.dependentKind}:${row.dependentId}"
            if !visited
                .contains(u) && ImpactWorkflow.expandable(RecordKind.valueOf(row.dependentKind))
            then
              visited += u
              next += u -> (depth + 1)
        }
      }
      frontier = next.result()
    val dependents = seen.values.toVector.map { (row, depth) =>
      DependentView(
        row.dependentKind,
        row.dependentId,
        row.dependentVersion,
        flaggedBy(RecordKind.valueOf(row.dependentKind), row.dependentId, ref),
        depth
      )
    }
    ImpactView(ref.key, dependents, dependents.nonEmpty && dependents.forall(_.flagged))
  }

  // ── evaluation (US9) ─────────────────────────────────────────────────────────

  get("/evals/latest") { () =>
    val dir = java.nio.file.Path.of(settings.cassettes).resolveSibling("reports")
    Report.latest(dir) match
      case None => throw HttpProblem.notFound("no evaluation report yet; run `just eval`")
      case Some(file) =>
        Respond(
          java.nio.file.Files.readString(file),
          headers = Vector("Content-Type" -> "application/json; charset=UTF-8")
        )
  }

  /** A reviewed failure becomes a regression case file (FR-053). */
  postBody("/evals/cases:from-record") { (body: FromRecordBody) =>
    reviewerOrRefuse(body.reviewer)
    val companion = Records
      .companionFor(body.id)
      .getOrElse(throw HttpProblem.notFound(s"not a record id: '${body.id}'"))
    val r = client.forEventSourcedEntity(EntityId(body.id)).call(companion.get).invoke()
    if !r.registered then throw HttpProblem.notFound(s"no record '${body.id}'")
    val failure =
      r.status == RecordStatus.rejected || r.status == RecordStatus.quarantined || r.flags.nonEmpty || r.reviews
        .exists(_.action == ReviewActionKind.complain_citation)
    if !failure then
      throw HttpProblem.conflict(
        "only a rejected, quarantined, flagged or complained-about record becomes a case"
      )
    val caseId = s"regression-${r.id.replaceAll("[^A-Za-z0-9]+", "-")}-${r.attempt}"
    val dir    = java.nio.file.Path.of(settings.cassettes).resolveSibling("cases")
    java.nio.file.Files.createDirectories(dir)
    val file    = dir.resolve(s"$caseId.json")
    val defect  = r.defect.map(d => s"${d.kind}: ${d.detail}").getOrElse("")
    val content = r.content.map(c => writeToString(c)(using recordContentCodec)).getOrElse("")
    val json =
      s"""{
         |  "id": ${quoteJson(caseId)},
         |  "category": "insufficient_evidence",
         |  "request": "regression from record ${r.id}",
         |  "fixtures": [${r.sourceRef
          .map(s => quoteJson(s.id.stripPrefix("src:file:") + ".xml"))
          .getOrElse("")}],
         |  "expected": {"criticalFailures": [${quoteJson(defect)}], "notes": ${quoteJson(
          s"${body.note} (reviewed by ${body.reviewer}; record status ${r.status}; content $content)"
        )}},
         |  "labelledBy": ${quoteJson(body.reviewer)},
         |  "runnable": false
         |}
         |""".stripMargin
    java.nio.file.Files.writeString(file, json)
    Respond(CaseWritten(file.toString, caseId), status = 201)
  }

  private val recordContentCodec
      : com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[RecordContent] =
    com.thinkmorestupidless.ankka.core.Codecs.make[RecordContent]

  private def quoteJson(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

  // ── the design and its approval (US8) ────────────────────────────────────────

  private def designOf(id: String): DesignState =
    val d = client.forEventSourcedEntity(EntityId(id)).call(DesignEntity.get).invoke()
    if !d.registered then throw HttpProblem.notFound(s"no design '$id'")
    d

  /** The design as a record, or one version rendered as Markdown with `format=md`. */
  get("/designs/{id}") { (id: String) =>
    val d       = designOf(id)
    val version = query.optional[Int]("version")
    val chosen = version
      .map(v => d.version(v).getOrElse(throw HttpProblem.notFound(s"no version $v")))
      .orElse(d.latest)
    query.optional[String]("format") match
      case Some("md") =>
        val v = chosen.getOrElse(throw HttpProblem.notFound("no version yet"))
        Respond(
          Rendering.render(DesignState.requestOf(id), v, d.approvals),
          headers = Vector("Content-Type" -> "text/markdown; charset=UTF-8")
        )
      case _ =>
        Respond(
          writeToString(d.copy(versions = d.versions.filter(v => version.forall(_ == v.version)))),
          headers = Vector("Content-Type" -> "application/json; charset=UTF-8")
        )
  }

  get("/designs") { () =>
    query.optional[String]("flagged") match
      case Some("true") => views.forView(DesignRows).ask(DesignRows.flagged)
      case _            => views.forView(DesignRows).all()
  }

  postBody("/designs/{id}/submit") { (id: String, body: SubmitBody) =>
    designOf(id)
    client
      .forEventSourcedEntity(EntityId(id))
      .call(DesignEntity.submit)
      .invoke(SubmitDesign(body.version, body.by))
  }

  postBody("/designs/{id}/approval") { (id: String, body: ApprovalBody) =>
    designOf(id)
    try
      Respond(
        Approvals.record(
          client,
          id,
          body.version,
          body.decision,
          body.reviewer,
          body.rationale,
          body.requestedChanges
        ),
        status = 201
      )
    catch
      case Approvals.Problem(409, m) => throw HttpProblem.conflict(m)
      case Approvals.Problem(404, m) => throw HttpProblem.notFound(m)
      case Approvals.Problem(_, m)   => throw HttpProblem.badRequest(m)
  }

  // ── search (US6) ─────────────────────────────────────────────────────────────

  private def planOf(id: String): SearchPlan =
    client.forEventSourcedEntity(EntityId(id)).call(SearchPlanEntity.get).invoke()
  private def executionOf(id: String): SearchExecution =
    client.forEventSourcedEntity(EntityId(id)).call(SearchExecutionEntity.get).invoke()

  post("/questions/{id}/plan") { (id: String) =>
    client.forWorkflow(EntityId(id)).call(QuestionInvestigationWorkflow.replan).invoke()
    Respond(investigationOfQuestion(id), status = 202)
  }

  get("/questions/{id}/executions") { (id: String) =>
    views.forView(ExecutionRows).ask(ExecutionRows.byQuestion, "question" -> id)
  }

  get("/search-plans/{id}") { (id: String) =>
    val p = planOf(id)
    if !p.registered then throw HttpProblem.notFound(s"no search plan '$id'")
    p
  }

  postBody("/search-plans/{id}/execute") { (id: String, body: ExecuteBody) =>
    if !planOf(id).registered then throw HttpProblem.notFound(s"no search plan '$id'")
    val questionId = SearchPlan.questionOf(id)
    client
      .forWorkflow(EntityId(questionId))
      .call(QuestionInvestigationWorkflow.execute)
      .invoke(ExecutePlan(body.sources))
    Respond(investigationOfQuestion(questionId), status = 202)
  }

  get("/search-executions/{id}") { (id: String) =>
    val e = executionOf(id)
    if !e.registered then throw HttpProblem.notFound(s"no search execution '$id'")
    e
  }

  /** The same queries against the recorded snapshot; `matches` compares the results in order. */
  post("/search-executions/{id}/rerun") { (id: String) =>
    val original = executionOf(id)
    if !original.registered then throw HttpProblem.notFound(s"no search execution '$id'")
    if original.source != "local" then
      throw HttpProblem.conflict("only a local execution is re-run against its snapshot")
    val plan       = planOf(original.planRef.map(_.id).getOrElse(""))
    val settings   = Searcher.Settings()
    val store      = Searcher.store(views).restrictedTo(original.snapshot)
    val inSnapshot = original.snapshot.map(r => (r.id, r.version)).toSet
    def run(q: Query) =
      Searcher
        .run(q, store, settings, Some(views))
        .filter(h => inSnapshot.contains((h.sourceId, h.sourceVersion)))
    val results =
      if original.fused then Searcher.fuse(plan.queries.map(q => q -> run(q)), store, settings)
      else original.query.map(run).getOrElse(Vector.empty)
    def identity(hits: Vector[Hit]) = hits.map(h => (h.chunkId, h.sourceId, h.sourceVersion))
    val matches                     = identity(results) == identity(original.results)
    val n                           = plan.executionIds.count(_.startsWith(s"$id:r")) + 1
    val rerunId                     = s"$id:r$n"
    val recorded = client
      .forEventSourcedEntity(EntityId(rerunId))
      .call(SearchExecutionEntity.execute)
      .invoke(
        RecordExecution(
          original.planRef.get,
          original.questionId,
          original.query,
          original.fused,
          original.source,
          original.parameters + ("rerun" -> "snapshot"),
          original.snapshot,
          results,
          None,
          Some(id),
          original.gapId
        )
      )
    client
      .forEventSourcedEntity(EntityId(plan.id))
      .call(SearchPlanEntity.recordExecution)
      .invoke(rerunId)
    RerunView(recorded, matches)
  }

  /** A supplemental search for a gap; refused once the gap's budget is spent (FR-032). */
  post("/gaps/{id}/search") { (id: String) =>
    val gap = client.forEventSourcedEntity(EntityId(id)).call(GapEntity.get).invoke()
    if !gap.registered then throw HttpProblem.notFound(s"no gap '$id'")
    if gap.supplementalExecutionIds.size >= Configured.supplementalBudget then
      throw HttpProblem.conflict(
        s"the supplemental search budget (${Configured.supplementalBudget}) for $id is spent"
      )
    val questionId =
      gap.questionRef.map(_.id).getOrElse(throw HttpProblem.notFound("the gap names no question"))
    client
      .forWorkflow(EntityId(questionId))
      .call(QuestionInvestigationWorkflow.supplemental)
      .invoke(id)
    Respond(investigationOfQuestion(questionId), status = 202)
  }

  get("/chunks/{chunkId}/context") { (chunkId: String) =>
    Context
      .resolve(client, chunkId)
      .getOrElse(throw HttpProblem.notFound(s"no chunk '$chunkId'"))
  }

  postBody("/questions/{id}/assess") { (id: String, body: AssessBody) =>
    client
      .forWorkflow(EntityId(id))
      .call(QuestionInvestigationWorkflow.assess)
      .invoke(AssessRecords(body.recordIds))
    Respond(investigationOfQuestion(id), status = 202)
  }

  postBody("/questions/{id}/sufficiency") { (id: String, body: SufficiencyBody) =>
    client
      .forWorkflow(EntityId(id))
      .call(QuestionInvestigationWorkflow.judge)
      .invoke(body.intendedDecision)
    Respond(investigationOfQuestion(id), status = 202)
  }

  get("/questions/{id}/gaps") { (id: String) =>
    questionInvestigation(id).toVector
      .flatMap(s => s.gapIds ++ s.coverageGapIds)
      .distinct
      .map(g => client.forEventSourcedEntity(EntityId(g)).call(GapEntity.get).invoke())
  }

  get("/assessments/{id}") { (id: String) =>
    val a = client.forEventSourcedEntity(EntityId(id)).call(AssessmentEntity.get).invoke()
    if !a.registered then throw HttpProblem.notFound(s"no assessment '$id'")
    a
  }

  postBody("/assessments/{id}/review") { (id: String, body: ReviewRecordBody) =>
    reviewerOrRefuse(body.reviewer)
    Respond(
      client
        .forEventSourcedEntity(EntityId(id))
        .call(AssessmentEntity.review)
        .invoke(ReviewRecord(body.action, body.reviewer, body.rationale)),
      status = 201
    )
  }

  get("/requests/{id}/questions") { (id: String) =>
    val req = requestEntity(id).call(RequestEntity.get).invoke()
    if !req.registered then throw HttpProblem.notFound(s"no request '$id'")
    questionsOf(req)
  }

  // ── findings and claims (US3) ────────────────────────────────────────────────

  private def recordView(id: String): RecordView =
    val companion =
      Records.companionFor(id).getOrElse(throw HttpProblem.notFound(s"not a record id: '$id'"))
    val r = client.forEventSourcedEntity(EntityId(id)).call(companion.get).invoke()
    if !r.registered then throw HttpProblem.notFound(s"no record '$id'")
    val spans = r.spans.map { span =>
      source(span.sourceId).call(SourceEntity.resolveSpan).invoke(span) match
        case SpanResolution.Resolved(text) => SpanView(span, resolves = true, Some(text))
        case SpanResolution.Unresolved(_)  => SpanView(span, resolves = false, None)
    }
    RecordView(r, spans)

  get("/findings/{id}")((id: String) => recordView(id))
  get("/claims/{id}")((id: String) => recordView(id))

  private def reviewRecord(id: String, body: ReviewRecordBody): Respond[EvidenceRecord] =
    reviewerOrRefuse(body.reviewer)
    val companion =
      Records.companionFor(id).getOrElse(throw HttpProblem.notFound(s"not a record id: '$id'"))
    Respond(
      client
        .forEventSourcedEntity(EntityId(id))
        .call(companion.review)
        .invoke(ReviewRecord(body.action, body.reviewer, body.rationale)),
      status = 201
    )

  postBody("/findings/{id}/review")((id: String, body: ReviewRecordBody) => reviewRecord(id, body))
  postBody("/claims/{id}/review")((id: String, body: ReviewRecordBody) => reviewRecord(id, body))

  /**
   * A reviewer-authored claim: `generatedBy` names the reviewer (FR-046); verification starts as
   * for any record.
   */
  postBody("/claims") { (body: ReviewerClaimBody) =>
    reviewerOrRefuse(body.reviewer)
    val kind = ClaimKind.values
      .find(_.toString == body.kind)
      .getOrElse(
        throw HttpProblem.badRequest(s"kind must be one of ${ClaimKind.values.mkString(", ")}")
      )
    val doc = source(body.sourceId).call(SourceEntity.get).invoke()
    val v = doc
      .version(body.version)
      .getOrElse(throw HttpProblem.notFound(s"no version ${body.version} of ${body.sourceId}"))
    val spans = body.spans.map { sb =>
      val section = v
        .section(sb.sectionId)
        .getOrElse(throw HttpProblem.badRequest(s"no section ${sb.sectionId}"))
      (sb.start, sb.end) match
        case (Some(a), Some(b)) =>
          SourceSpan(body.sourceId, body.version, sb.sectionId, a, b, sb.quotation)
        case _ =>
          val located = FacetRules
            .locate(section.text, sb.quotation)
            .getOrElse(
              throw HttpProblem.badRequest(
                s"the quotation is not in section ${sb.sectionId}: '${sb.quotation}'"
              )
            )
          SourceSpan(
            body.sourceId,
            body.version,
            sb.sectionId,
            located.start,
            located.end,
            located.quotation
          )
    }
    val id =
      ClaimEntity.idForReviewerClaim(body.sourceId, body.version, Ids.short(body.proposition))
    val sourceRef   = Ref(RecordKind.source, body.sourceId, body.version)
    val findingRefs = body.findingIds.map(f => Ref(RecordKind.finding, f, 1))
    val content = com.thinkmorestupidless.experimentproposals.domain.evidence.RecordContent.OfClaim(
      ClaimContent(
        body.proposition,
        kind,
        findingRefs,
        Vector.empty,
        body.qualifications,
        None,
        None
      )
    )
    val record = client
      .forEventSourcedEntity(EntityId(id))
      .call(ClaimEntity.propose)
      .invoke(
        ProposeRecord(
          "claim",
          sourceRef,
          content,
          spans,
          s"reviewer:${body.reviewer}",
          sourceRef +: findingRefs
        )
      )
    Respond(record, status = 201)
  }

  get("/review-queue") { () =>
    query.optional[String]("kind") match
      case Some("finding") =>
        views.forView(FindingRows).ask(FindingRows.byStatus, "status" -> "requires_review")
      case Some("claim") =>
        views.forView(ClaimRows).ask(ClaimRows.byStatus, "status" -> "requires_review")
      case _ =>
        views.forView(FindingRows).ask(FindingRows.byStatus, "status" -> "requires_review") ++ views
          .forView(ClaimRows)
          .ask(ClaimRows.byStatus, "status" -> "requires_review")
  }

  get("/sources/{id}/records") { (id: String) =>
    views.forView(FindingRows).ask(FindingRows.bySource, "source" -> id) ++ views
      .forView(ClaimRows)
      .ask(ClaimRows.bySource, "source" -> id)
  }

  postBody("/sources/{id}/extract") { (id: String, body: ExtractBody) =>
    val doc = source(id).call(SourceEntity.get).invoke()
    val workflowId = doc.ingestions.lastOption.getOrElse(
      throw HttpProblem.notFound(s"no ingestion of '$id' to extract from")
    )
    val state = client
      .forWorkflow(EntityId(workflowId))
      .call(IngestionWorkflow.extractCommand)
      .invoke(ExtractFromSource(body.questionIds))
    Respond(IngestStarted(workflowId, state.sourceId, state.status), status = 202)
  }

  /**
   * A fault is an attempt: the operator proposes the altered record again and verification runs on
   * it (FR-052).
   */
  postBody("/faults:inject") { (body: FaultBody) =>
    val companion = Records
      .companionFor(body.target.id)
      .getOrElse(throw HttpProblem.notFound(s"not a record id: '${body.target.id}'"))
    val r = client.forEventSourcedEntity(EntityId(body.target.id)).call(companion.get).invoke()
    if !r.registered then throw HttpProblem.notFound(s"no record '${body.target.id}'")
    val content = r.content.getOrElse(throw HttpProblem.conflict("the record has no content"))
    val (mutated, rawSpans) = Mutations(body.mutation, content, r.spans)
      .fold(problem => throw HttpProblem.badRequest(problem), identity)
    val spans = rawSpans.map { span =>
      if span.quotation.nonEmpty then span
      else
        // A span the mutation moved: give it the text it now points at, so it resolves and the verifier must notice.
        val section = source(span.sourceId)
          .call(SourceEntity.section)
          .invoke(SectionRef(span.sourceVersion, span.sectionId))
        val end = math.min(span.endOffset, section.text.length)
        span.copy(endOffset = end, quotation = section.text.substring(span.startOffset, end))
    }
    val defect = Rules.defect(
      com.thinkmorestupidless.experimentproposals.domain.Enumerations.DefectKind.other,
      s"fault injected: ${body.mutation}",
      retryable = false
    )
    val after = client
      .forEventSourcedEntity(EntityId(body.target.id))
      .call(companion.repropose)
      .invoke(ReproposeRecord(mutated, spans, defect, s"operator:fault:${body.mutation}"))
    Respond(
      FaultStarted(
        after.id,
        after.attempt,
        VerificationWorkflow.idFor(after.id, after.attempt),
        Mutations.expectedDetection(body.mutation).map(_.toString)
      ),
      status = 202
    )
  }

  // ── sources (foundation and US5) ─────────────────────────────────────────────

  postBody("/sources:ingest") { (request: IngestRequest) =>
    val workflowId = IngestionWorkflow.idFor(request.adapter, request.identifier)
    val state = client
      .forWorkflow(EntityId(workflowId))
      .call(IngestionWorkflow.start)
      .invoke(
        StartIngestion(request.adapter, request.identifier, request.extract, request.questionIds)
      )
    Respond(IngestStarted(workflowId, state.sourceId, state.status), status = 202)
  }

  get("/sources") { () =>
    query.optional[String]("status") match
      case Some(status) => views.forView(SourceRows).ask(SourceRows.byStatus, "status" -> status)
      case None         => views.forView(SourceRows).all()
  }

  /** One ingestion per file the manifest names (contracts/http-api.md). */
  postBody("/sources:ingest-internal") { (body: IngestInternalBody) =>
    val adapter = Configured.adapters("internal") match
      case Right(a: InternalAdapter) => a
      case _ => throw HttpProblem.notFound("no internal adapter is configured")
    val identifiers =
      try adapter.records(body.manifestPath)
      catch case e: AdapterPermanent => throw HttpProblem.badRequest(e.getMessage)
    val started = identifiers.map { identifier =>
      val workflowId = IngestionWorkflow.idFor("internal", identifier)
      val state = client
        .forWorkflow(EntityId(workflowId))
        .call(IngestionWorkflow.start)
        .invoke(StartIngestion("internal", identifier))
      IngestStarted(workflowId, state.sourceId, state.status)
    }
    Respond(started, status = 202)
  }

  /** The retained text stays in the store; a reader asks for a section. */
  private def withoutContent(doc: SourceDocument, version: Option[Int]): SourceDocument =
    doc.copy(versions =
      doc.versions.filter(v => version.forall(_ == v.version)).map(_.copy(content = None))
    )

  get("/sources/{id}") { (id: String) =>
    val doc = source(id).call(SourceEntity.get).invoke()
    if !doc.registered then throw HttpProblem.notFound(s"no source '$id'")
    withoutContent(doc, query.optional[Int]("version"))
  }

  /** A reviewer or the operator changes a status; eligibility follows in the same command. */
  postBody("/sources/{id}/status") { (id: String, body: StatusBody) =>
    val status = SourceStatus.values
      .find(_.toString == body.status)
      .getOrElse(throw HttpProblem.badRequest(s"unknown status '${body.status}'"))
    source(id).call(SourceEntity.changeStatus).invoke(ChangeStatus(status, body.reason, body.by))
    withoutContent(source(id).call(SourceEntity.get).invoke(), None)
  }

  get("/measurements") { () =>
    (query.optional[String]("kind"), query.optional[String]("invalid")) match
      case (_, Some("true")) => views.forView(MeasurementRows).ask(MeasurementRows.invalidUnits)
      case (Some(kind), _) =>
        views
          .forView(MeasurementRows)
          .ask(
            MeasurementRows.byKindAndRange,
            "kind" -> kind,
            "min"  -> query.optional[String]("min").getOrElse("-1e30"),
            "max"  -> query.optional[String]("max").getOrElse("1e30")
          )
      case _ => views.forView(MeasurementRows).all()
  }

  get("/sources/{id}/sections/{sectionId}") { (id: String, sectionId: String) =>
    val doc = source(id).call(SourceEntity.get).invoke()
    val version = query
      .optional[Int]("version")
      .orElse(doc.latest.map(_.version))
      .getOrElse(throw HttpProblem.notFound(s"no versions of '$id'"))
    val section = source(id).call(SourceEntity.section).invoke(SectionRef(version, sectionId))
    val chunks =
      doc.version(version).map(_.chunks.filter(_.sectionId == sectionId)).getOrElse(Vector.empty)
    SectionView(section, chunks)
  }

  get("/spans:resolve") { () =>
    val span = SourceSpan(
      sourceId = query.required[String]("source"),
      sourceVersion = query.required[Int]("version"),
      sectionId = query.required[String]("section"),
      startOffset = query.required[Int]("start"),
      endOffset = query.required[Int]("end"),
      quotation = query.required[String]("quotation")
    )
    source(span.sourceId).call(SourceEntity.resolveSpan).invoke(span)
  }
