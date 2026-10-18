package com.thinkmorestupidless.experimentproposals.domain.design

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** A background statement grounded in verified claims and the spans they cite (FR-040). */
final case class Grounded(text: String, claimRefs: Vector[Ref], spans: Vector[SourceSpan])

/** Everything the design cites, in one place; a rendering may reference nothing outside it. */
final case class Manifest(
    claimRefs: Vector[Ref],
    spans: Vector[SourceSpan],
    approvalIds: Vector[String]
)

/** The twelve sections, in order (data-model.md). */
final case class DesignSections(
    questionAndScope: String,
    background: Vector[Grounded],
    hypothesis: String,
    modelAndPrerequisites: String,
    variablesControlsComparators: String,
    endpointsAndMeasurementPlan: String,
    successFailureInconclusiveCriteria: String,
    resourcesTimelineDependencies: String,
    limitations: String,
    alternatives: String,
    outcomesAndDecisionPoints: String,
    evidenceManifest: Manifest
)

object DesignSections:
  val Names: Vector[String] = Vector(
    "questionAndScope",
    "background",
    "hypothesis",
    "modelAndPrerequisites",
    "variablesControlsComparators",
    "endpointsAndMeasurementPlan",
    "successFailureInconclusiveCriteria",
    "resourcesTimelineDependencies",
    "limitations",
    "alternatives",
    "outcomesAndDecisionPoints",
    "evidenceManifest"
  )

  /** The sections that are empty, by name. */
  def missing(s: DesignSections): Vector[String] =
    Vector(
      "questionAndScope"                   -> s.questionAndScope.isBlank,
      "background"                         -> s.background.isEmpty,
      "hypothesis"                         -> s.hypothesis.isBlank,
      "modelAndPrerequisites"              -> s.modelAndPrerequisites.isBlank,
      "variablesControlsComparators"       -> s.variablesControlsComparators.isBlank,
      "endpointsAndMeasurementPlan"        -> s.endpointsAndMeasurementPlan.isBlank,
      "successFailureInconclusiveCriteria" -> s.successFailureInconclusiveCriteria.isBlank,
      "resourcesTimelineDependencies"      -> s.resourcesTimelineDependencies.isBlank,
      "limitations"                        -> s.limitations.isBlank,
      "alternatives"                       -> s.alternatives.isBlank,
      "outcomesAndDecisionPoints"          -> s.outcomesAndDecisionPoints.isBlank,
      "evidenceManifest"                   -> s.evidenceManifest.claimRefs.isEmpty
    ).collect { case (name, true) => name }

// ── the model's reply ──────────────────────────────────────────────────────────

final case class GroundedProposal(text: String, claimIds: Vector[String])

/** The design as the model proposes it: eleven texts; the manifest and the spans are derived. */
final case class ExperimentalDesign(
    questionAndScope: String,
    background: Vector[GroundedProposal],
    hypothesis: String,
    modelAndPrerequisites: String,
    variablesControlsComparators: String,
    endpointsAndMeasurementPlan: String,
    successFailureInconclusiveCriteria: String,
    resourcesTimelineDependencies: String,
    limitations: String,
    alternatives: String,
    outcomesAndDecisionPoints: String
)

// ── the entity ─────────────────────────────────────────────────────────────────

final case class DesignCheck(name: String, passed: Boolean, detail: String)

final case class Approval(
    version: Int,
    decision: ApprovalDecision,
    reviewer: String,
    at: Instant,
    rationale: String,
    requestedChanges: Vector[String] = Vector.empty
)

final case class DesignVersion(
    version: Int,
    approachRef: Ref,
    decisionRef: Ref,
    sections: DesignSections,
    status: DesignStatus,
    checks: Vector[DesignCheck],
    ruleSetVersion: String,
    generatedAt: Instant,
    generatedBy: String,
    changesApplied: Vector[String] = Vector.empty
)

final case class DesignFlag(changedRef: Ref, version: Int, reason: String, at: Instant)

/** The request's design (glossary: experimental design): versions, approvals, flags. */
final case class DesignState(
    id: String,
    registered: Boolean = false,
    requestRef: Option[Ref] = None,
    versions: Vector[DesignVersion] = Vector.empty,
    approvals: Vector[Approval] = Vector.empty,
    flags: Vector[DesignFlag] = Vector.empty
):
  def latest: Option[DesignVersion]          = versions.lastOption
  def version(n: Int): Option[DesignVersion] = versions.find(_.version == n)
  def approved: Option[DesignVersion]        = versions.find(_.status == DesignStatus.approved)

object DesignState:
  def empty(id: String): DesignState      = DesignState(id)
  def idFor(requestId: String): String    = s"$requestId:design"
  def requestOf(designId: String): String = designId.stripSuffix(":design")

enum DesignEvent:
  case DraftGenerated(
      requestRef: Ref,
      version: Int,
      approachRef: Ref,
      decisionRef: Ref,
      sections: DesignSections,
      checks: Vector[DesignCheck],
      ruleSetVersion: String,
      generatedBy: String,
      changesApplied: Vector[String],
      at: Instant
  )
  case Submitted(version: Int, by: String, at: Instant)
  case Approved(version: Int, by: String, at: Instant, rationale: String)
  case Rejected(version: Int, by: String, at: Instant, rationale: String)
  case ChangesRequested(
      version: Int,
      by: String,
      at: Instant,
      rationale: String,
      requestedChanges: Vector[String]
  )
  case Superseded(version: Int, byVersion: Int, at: Instant)
  case FlaggedForReview(changedRef: Ref, version: Int, reason: String, at: Instant)

final case class GenerateDesign(
    requestRef: Ref,
    approachRef: Ref,
    decisionRef: Ref,
    sections: DesignSections,
    checks: Vector[DesignCheck],
    ruleSetVersion: String,
    generatedBy: String,
    changesApplied: Vector[String]
)
final case class SubmitDesign(version: Int, by: String)
final case class RecordApproval(
    version: Int,
    decision: ApprovalDecision,
    reviewer: String,
    rationale: String,
    requestedChanges: Vector[String] = Vector.empty
)
final case class FlagDesign(changedRef: Ref, version: Int, reason: String)

object DesignTransitions:
  def allowed(from: DesignStatus, to: DesignStatus): Boolean = (from, to) match
    case (DesignStatus.draft, DesignStatus.under_review) => true
    case (
          DesignStatus.under_review,
          DesignStatus.approved | DesignStatus.rejected | DesignStatus.changes_requested
        ) =>
      true
    case (DesignStatus.approved, _)   => false
    case (_, DesignStatus.superseded) => true
    case _                            => false

object DesignCodecs:
  given Serializer[DesignState]    = Codecs.serializer[DesignState]("design")
  given Serializer[DesignEvent]    = Codecs.serializer[DesignEvent]("design-event")
  given Serializer[GenerateDesign] = Codecs.serializer[GenerateDesign]("generate-design")
  given Serializer[SubmitDesign]   = Codecs.serializer[SubmitDesign]("submit-design")
  given Serializer[RecordApproval] = Codecs.serializer[RecordApproval]("record-approval")
  given Serializer[FlagDesign]     = Codecs.serializer[FlagDesign]("flag-design")
  given Serializer[ExperimentalDesign] =
    Codecs.serializer[ExperimentalDesign]("experimental-design")
  given Serializer[Approval] = Codecs.serializer[Approval]("approval")
