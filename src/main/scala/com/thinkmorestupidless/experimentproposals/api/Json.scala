package com.thinkmorestupidless.experimentproposals.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.experimentproposals.application.views.{
  DesignRow,
  ExecutionRow,
  MeasurementRow,
  RecordRow,
  SourceRow
}
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  IngestionState,
  InvestigationState,
  QuestionInvestigationState,
  VerificationState
}
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Reviewer, StageRun}
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.search.{SearchExecution, SearchPlan}
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.ankka.core.Codecs

/** The API's request and response shapes beside the records themselves, with their codecs. */
final case class IngestRequest(
    adapter: String,
    identifier: String,
    extract: Boolean = false,
    questionIds: Vector[String] = Vector.empty
)
final case class IngestStarted(workflowId: String, sourceId: Option[String], status: String)
final case class WorkflowView(
    kind: String,
    id: String,
    status: String,
    pendingStep: Option[String],
    failure: Option[String],
    isTerminal: Boolean,
    sourceId: Option[String],
    state: String
)
final case class SectionView(section: Section, chunks: Vector[ChunkSpan])
final case class SubmitRequestBody(text: String, submittedBy: String)
final case class Created(id: String, version: Int, workflowId: Option[String])
final case class RequestView(
    request: CustomerRequest,
    facets: Vector[Facet],
    questions: Vector[ScientificQuestion],
    workflow: WorkflowView
)
final case class ReviewBody(
    action: String,
    reviewer: String,
    rationale: String,
    statement: Option[String] = None
)
final case class AssumeBody(value: String, reviewer: String, rationale: String)
final case class ReviewerBody(reviewer: String)
final case class SpanView(span: SourceSpan, resolves: Boolean, text: Option[String])
final case class RecordView(record: EvidenceRecord, spans: Vector[SpanView])
final case class ReviewRecordBody(action: String, reviewer: String, rationale: String)
final case class SpanBody(
    sectionId: String,
    quotation: String,
    start: Option[Int] = None,
    end: Option[Int] = None
)
final case class ReviewerClaimBody(
    sourceId: String,
    version: Int = 1,
    proposition: String,
    kind: String = "normalised_claim",
    findingIds: Vector[String] = Vector.empty,
    spans: Vector[SpanBody],
    qualifications: Vector[String] = Vector.empty,
    reviewer: String
)
final case class FaultTarget(kind: String, id: String)
final case class FaultBody(target: FaultTarget, mutation: String)
final case class FaultStarted(
    recordId: String,
    attempt: Int,
    workflowId: String,
    expectedDetection: Option[String]
)
final case class ExtractBody(questionIds: Vector[String] = Vector.empty)
final case class IngestInternalBody(manifestPath: String)
final case class StatusBody(status: String, reason: String, by: String)

final case class AssessBody(recordIds: Vector[String])
final case class SufficiencyBody(intendedDecision: String)
final case class QuestionView(
    question: ScientificQuestion,
    investigation: Option[QuestionInvestigationState],
    plan: Option[SearchPlan],
    executions: Vector[SearchExecution],
    assessments: Vector[EvidenceAssessment],
    sufficiency: Vector[EvidenceSufficiency],
    gaps: Vector[KnowledgeGap]
)
final case class ExecuteBody(sources: Vector[String] = Vector.empty)
final case class RerunView(execution: SearchExecution, matches: Boolean)
final case class PackageView(`package`: EvidencePackage, passages: Vector[SpanView])
final case class ApproachesView(
    approaches: Vector[ExperimentalApproach],
    comparison: Option[Comparison],
    decision: Option[DecisionRecord]
)
final case class RejectedBody(approachId: String, reason: String)
final case class SubmitBody(version: Int, by: String = "operator")
final case class FromRecordBody(kind: String, id: String, reviewer: String, note: String)
final case class CaseWritten(file: String, caseId: String)
final case class DependentView(kind: String, id: String, version: Int, flagged: Boolean, depth: Int)
final case class ImpactView(ref: String, dependents: Vector[DependentView], allFlagged: Boolean)
final case class ApprovalBody(
    version: Int,
    decision: String,
    reviewer: String,
    rationale: String,
    requestedChanges: Vector[String] = Vector.empty
)
final case class DecideBody(
    selectedApproachId: String,
    rejected: Vector[RejectedBody] = Vector.empty,
    decidedBy: String,
    rationale: String
)

object Json:
  given reviewersCodec: JsonValueCodec[Vector[Reviewer]]   = Codecs.make[Vector[Reviewer]]
  given JsonValueCodec[StageRun]                           = Codecs.make[StageRun]
  given JsonValueCodec[IngestRequest]                      = Codecs.make[IngestRequest]
  given JsonValueCodec[IngestStarted]                      = Codecs.make[IngestStarted]
  given JsonValueCodec[WorkflowView]                       = Codecs.make[WorkflowView]
  given JsonValueCodec[IngestionState]                     = Codecs.make[IngestionState]
  given JsonValueCodec[InvestigationState]                 = Codecs.make[InvestigationState]
  given JsonValueCodec[QuestionInvestigationState]         = Codecs.make[QuestionInvestigationState]
  given JsonValueCodec[SourceDocument]                     = Codecs.make[SourceDocument]
  given JsonValueCodec[SectionView]                        = Codecs.make[SectionView]
  given JsonValueCodec[SpanResolution]                     = Codecs.make[SpanResolution]
  given sourceRowsCodec: JsonValueCodec[Vector[SourceRow]] = Codecs.make[Vector[SourceRow]]
  given JsonValueCodec[SubmitRequestBody]                  = Codecs.make[SubmitRequestBody]
  given JsonValueCodec[Created]                            = Codecs.make[Created]
  given JsonValueCodec[RequestView]                        = Codecs.make[RequestView]
  given facetsCodec: JsonValueCodec[Vector[Facet]]         = Codecs.make[Vector[Facet]]
  given JsonValueCodec[Facet]                              = Codecs.make[Facet]
  given JsonValueCodec[ProposalStatus]                     = Codecs.make[ProposalStatus]
  given JsonValueCodec[ReviewBody]                         = Codecs.make[ReviewBody]
  given JsonValueCodec[AssumeBody]                         = Codecs.make[AssumeBody]
  given JsonValueCodec[ReviewerBody]                       = Codecs.make[ReviewerBody]
  given JsonValueCodec[ScientificQuestion]                 = Codecs.make[ScientificQuestion]
  given questionsCodec: JsonValueCodec[Vector[ScientificQuestion]] =
    Codecs.make[Vector[ScientificQuestion]]
  given JsonValueCodec[VerificationState]                        = Codecs.make[VerificationState]
  given JsonValueCodec[EvidenceRecord]                           = Codecs.make[EvidenceRecord]
  given JsonValueCodec[RecordView]                               = Codecs.make[RecordView]
  given JsonValueCodec[ReviewRecordBody]                         = Codecs.make[ReviewRecordBody]
  given JsonValueCodec[ReviewerClaimBody]                        = Codecs.make[ReviewerClaimBody]
  given JsonValueCodec[FaultBody]                                = Codecs.make[FaultBody]
  given JsonValueCodec[FaultStarted]                             = Codecs.make[FaultStarted]
  given JsonValueCodec[ExtractBody]                              = Codecs.make[ExtractBody]
  given recordRowsCodec: JsonValueCodec[Vector[RecordRow]]       = Codecs.make[Vector[RecordRow]]
  given JsonValueCodec[AssessBody]                               = Codecs.make[AssessBody]
  given JsonValueCodec[SufficiencyBody]                          = Codecs.make[SufficiencyBody]
  given JsonValueCodec[QuestionView]                             = Codecs.make[QuestionView]
  given JsonValueCodec[EvidenceAssessment]                       = Codecs.make[EvidenceAssessment]
  given gapsCodec: JsonValueCodec[Vector[KnowledgeGap]]          = Codecs.make[Vector[KnowledgeGap]]
  given JsonValueCodec[IngestInternalBody]                       = Codecs.make[IngestInternalBody]
  given JsonValueCodec[SearchPlan]                               = Codecs.make[SearchPlan]
  given JsonValueCodec[PackageView]                              = Codecs.make[PackageView]
  given JsonValueCodec[DesignState]                              = Codecs.make[DesignState]
  given JsonValueCodec[FromRecordBody]                           = Codecs.make[FromRecordBody]
  given JsonValueCodec[CaseWritten]                              = Codecs.make[CaseWritten]
  given JsonValueCodec[ImpactView]                               = Codecs.make[ImpactView]
  given JsonValueCodec[SubmitBody]                               = Codecs.make[SubmitBody]
  given JsonValueCodec[ApprovalBody]                             = Codecs.make[ApprovalBody]
  given designRowsCodec: JsonValueCodec[Vector[DesignRow]]       = Codecs.make[Vector[DesignRow]]
  given JsonValueCodec[ApproachesView]                           = Codecs.make[ApproachesView]
  given JsonValueCodec[DecideBody]                               = Codecs.make[DecideBody]
  given JsonValueCodec[DecisionRecord]                           = Codecs.make[DecisionRecord]
  given JsonValueCodec[SearchExecution]                          = Codecs.make[SearchExecution]
  given JsonValueCodec[ExecuteBody]                              = Codecs.make[ExecuteBody]
  given JsonValueCodec[RerunView]                                = Codecs.make[RerunView]
  given JsonValueCodec[ChunkContext]                             = Codecs.make[ChunkContext]
  given executionRowsCodec: JsonValueCodec[Vector[ExecutionRow]] = Codecs.make[Vector[ExecutionRow]]
  given JsonValueCodec[StatusBody]                               = Codecs.make[StatusBody]
  given ingestedCodec: JsonValueCodec[Vector[IngestStarted]] = Codecs.make[Vector[IngestStarted]]
  given measurementRowsCodec: JsonValueCodec[Vector[MeasurementRow]] =
    Codecs.make[Vector[MeasurementRow]]
