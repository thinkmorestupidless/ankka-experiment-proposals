package com.thinkmorestupidless.experimentproposals.domain

/**
 * Every closed list in the data model, as a Scala 3 enum whose case names are the wire strings. A
 * value outside a list cannot be decoded, which is the first deterministic gate.
 */
object Enumerations:

  enum FacetOrigin:
    case explicit, inferred, domain_derived, human_confirmed

  enum Importance:
    case mandatory, preferred, exploratory

  enum FacetStatus:
    case proposed, confirmed, ambiguous, unresolved, rejected, assumed

  enum FacetAction:
    case confirm, amend, reject, leave_unresolved, mark_ambiguous

  enum RequestStatus:
    case submitted, facets_proposed, facets_confirmed, questions_derived, investigating, designed,
      closed

  enum QuestionStatus:
    case unresolved, partial, answered, requires_review

  enum MissingValueStatus:
    case not_established_by_assessed_material, established_absent, not_applicable

  enum SourceStatus:
    case active, corrected, retracted, superseded, restricted

  enum RetentionRestriction:
    case none, abstract_only

  enum ParseCompleteness:
    case complete, partial, failed

  enum SectionKind:
    case abstract_, introduction, methods, results, discussion, table, figure_caption,
      supplementary, body, other

  enum ParseConfidence:
    case high, medium, low

  enum SourceKind:
    case literature, patent, regulatory, internal_protocol, internal_run, internal_measurement,
      internal_inventory

  enum QualityFlag:
    case ok, suspect, rejected

  enum OutcomeDirection:
    case increase, decrease, no_change, failure, qualitative, not_stated

  enum RecordStatus:
    case proposed, verified, rejected, requires_review, quarantined

  enum ClaimKind:
    case author_statement, normalised_claim, system_hypothesis

  enum VerificationLabel:
    case entailed, contradicted, insufficiently_supported, ambiguous

  enum CheckName:
    case negation, scope, species, treatment, endpoint

  enum Gate:
    case deterministic, source_context, eligibility, applicability, `package`, design

  enum DefectKind:
    case schema_invalid, span_unresolved, unit_invalid, status_invalid, negation_error,
      species_overgeneralisation, endpoint_mismatch, failure_as_success, unsupported_by_span,
      scope_loss, context_mismatch, model_refused, coverage_gap, fewer_than_two,
      unverified_claim_ref, other

  enum Severity:
    case critical, major, minor

  enum Relationship:
    case supports, contradicts, qualifies, background, not_applicable

  enum Applicability:
    case direct, indirect, unknown

  enum DimensionMatch:
    case `match`, mismatch, unknown

  enum SufficiencyStatus:
    case sufficient_for_proposed_next_step, plausible_but_unverified,
      insufficient_for_recommendation, requires_expert_review

  enum CoverageJudgement:
    case search_inadequate, evidence_absent, mixed

  enum Escalation:
    case supplemental_search, clarification, exploratory_question, none

  enum GapKind:
    case coverage, absence, contradiction, resource

  enum GapStatus:
    case open, searched, clarified, accepted, closed

  enum PackageQuality:
    case valid, invalid, requires_review

  enum ApproachStatus:
    case proposed, requires_review, rejected, selected

  enum EvidenceStatus:
    case established, assumed, unknown

  enum DesignStatus:
    case draft, under_review, approved, rejected, changes_requested, superseded

  enum ApprovalDecision:
    case approved, rejected, changes_requested

  enum ReviewActionKind:
    case confirm, amend, reject, leave_unresolved, mark_ambiguous, verify, complain_citation,
      approve, request_changes, raise_confidence

  enum StageStatus:
    case passed, failed, requires_review, skipped_idempotent

  enum QueryKind:
    case lexical, semantic

  enum ExclusionAction:
    case excluded, downgraded

  enum RecordKind:
    case request, facet, question, search_plan, search_execution, source, finding, claim,
      assessment, sufficiency, gap, `package`, approach, decision, design, stage_run
