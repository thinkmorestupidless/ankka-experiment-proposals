package com.thinkmorestupidless.experimentproposals.api

import com.thinkmorestupidless.experimentproposals.application.entities.{
  Records,
  RequestEntity,
  SourceEntity
}
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  IngestionWorkflow,
  InvestigationWorkflow,
  StartIngestion,
  StartInvestigation,
  VerificationWorkflow
}
import com.thinkmorestupidless.experimentproposals.domain.conversation.{Action, Reference}
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.experimentproposals.domain.request.SubmitRequest
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.SourceStatus
import com.thinkmorestupidless.experimentproposals.rules.Rules
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.HttpProblem
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/**
 * The operator's four acts, performed by their routes and by a confirmed proposal in a conversation
 * through the same code (contracts/assistant.md, proposals). A refusal is the route's
 * `HttpProblem`, which a confirmation passes through with its status.
 */
final class Operations(client: ComponentClient):
  private def source(id: String) = client.forEventSourcedEntity(EntityId(id))

  def submitRequest(text: String, submittedBy: String): Created =
    val id = Ids.mint("rq")
    val req = client
      .forEventSourcedEntity(EntityId(id))
      .call(RequestEntity.submit)
      .invoke(SubmitRequest(text, submittedBy))
    client
      .forWorkflow(EntityId(id))
      .call(InvestigationWorkflow.start)
      .invoke(StartInvestigation(id, req.text))
    Created(id, 1, Some(id))

  def ingest(request: IngestRequest): IngestStarted =
    val workflowId = IngestionWorkflow.idFor(request.adapter, request.identifier)
    val state = client
      .forWorkflow(EntityId(workflowId))
      .call(IngestionWorkflow.start)
      .invoke(
        StartIngestion(request.adapter, request.identifier, request.extract, request.questionIds)
      )
    IngestStarted(workflowId, state.sourceId, state.status)

  def injectFault(recordId: String, mutation: String): FaultStarted =
    val companion = Records
      .companionFor(recordId)
      .getOrElse(throw HttpProblem.notFound(s"not a record id: '${recordId}'"))
    val r = client.forEventSourcedEntity(EntityId(recordId)).call(companion.get).invoke()
    if !r.registered then throw HttpProblem.notFound(s"no record '${recordId}'")
    val content = r.content.getOrElse(throw HttpProblem.conflict("the record has no content"))
    val (mutated, rawSpans) = Mutations(mutation, content, r.spans)
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
      s"fault injected: ${mutation}",
      retryable = false
    )
    val after = client
      .forEventSourcedEntity(EntityId(recordId))
      .call(companion.repropose)
      .invoke(ReproposeRecord(mutated, spans, defect, s"operator:fault:${mutation}"))
    FaultStarted(
      after.id,
      after.attempt,
      VerificationWorkflow.idFor(after.id, after.attempt),
      Mutations.expectedDetection(mutation).map(_.toString)
    )

  /** The retained text stays in the store; a reader asks for a section. */
  def withoutContent(doc: SourceDocument, version: Option[Int]): SourceDocument =
    doc.copy(versions =
      doc.versions.filter(v => version.forall(_ == v.version)).map(_.copy(content = None))
    )

  def setStatus(id: String, body: StatusBody): SourceDocument =
    val status = SourceStatus.values
      .find(_.toString == body.status)
      .getOrElse(throw HttpProblem.badRequest(s"unknown status '${body.status}'"))
    source(id).call(SourceEntity.changeStatus).invoke(ChangeStatus(status, body.reason, body.by))
    withoutContent(source(id).call(SourceEntity.get).invoke(), None)

  /** An ingestion names its source once it has fetched it; a confirmation waits that long. */
  private def sourceOf(workflowId: String): Option[String] =
    val until = System.nanoTime() + 30_000_000_000L
    var found = Option.empty[String]
    while found.isEmpty && System.nanoTime() < until do
      found =
        client.forWorkflow(EntityId(workflowId)).call(IngestionWorkflow.status).invoke().sourceId
      if found.isEmpty then Thread.sleep(200)
    found

  /**
   * A confirmed proposal, performed as its route would perform it, as the operator; the reference
   * names what it produced.
   */
  def perform(action: Action): Reference = action match
    case Action.SubmitRequest(text) =>
      Reference("request", submitRequest(text, "operator").id)
    case Action.IngestSource(adapter, identifier) =>
      val started = ingest(IngestRequest(adapter, identifier, extract = true))
      Reference(
        "source",
        started.sourceId
          .orElse(sourceOf(started.workflowId))
          .getOrElse(
            throw HttpProblem.conflict(
              s"the ingestion ${started.workflowId} has not fetched its source yet"
            )
          )
      )
    case Action.InjectFault(recordId, fault) =>
      val started = injectFault(recordId, fault)
      Reference(if recordId.contains(":cl") then "claim" else "finding", started.recordId)
    case Action.SetSourceStatus(sourceId, status, reason) =>
      val why = if reason.isEmpty then "confirmed in a conversation" else reason
      val doc = setStatus(sourceId, StatusBody(status, why, "operator"))
      Reference("source", doc.id)
