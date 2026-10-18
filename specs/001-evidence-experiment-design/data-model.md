# Data Model: Evidence-Driven Scientific Experimental Design POC

**Feature**: `001-evidence-experiment-design` | **Date**: 2026-10-16

Source: spec.md Key Entities and FR-001 to FR-054; source document section 15; the Python build's
`data-model.md` for the record shapes, which are kept where they were platform-neutral. Platform
decisions are in research.md R3 and R10.

## Conventions

- **Names**: each record notes its glossary term (`GLOSSARY.md`) where it differs from the class
  name. The glossary is the vocabulary of the features and the spec; class names are the code's.
- **Entities**: every record below marked *entity* is an event sourced entity. Its **id** is derived
  from its parents (never random), its **events** are the only way its state changes, its **state** is
  the fold, and its **commands** are the only writers. A command names its wire name. Queries are
  read-only. A refusal is `effects.error(message, code)` and persists nothing.
- **Versions**: a record's `version` counts the events that changed its content (not its review
  flags). A reference is `Ref(kind, id, version)`; where the referenced record is written once (a
  span, an execution, a decision) `version` is 1.
- **Common fields on every record's state**: `id`, `version`, `createdAt`, `createdBy` (`reviewer:<name>`,
  `model:<prompt_version>` or `system:<stage>@<version>`), `provenance` (the `stage-run` ref that wrote
  it), `flags: Seq[ReviewFlag]` where impact propagation applies (R-flag below).
- **Enumerations** are closed; a value outside the list fails the deterministic gate.
- **Transitions** are listed per entity. A command for a transition not listed is refused `Conflict`.
- **Reviewer**: every command that records a reviewer takes `by: String` and refuses a name not in
  `Reviewers` with `BadRequest`, before anything is persisted.
- **R-flag**: `flagForReview(changedRef, reason)` is a command on finding, claim, assessment,
  sufficiency, package, approach and design. It appends `FlaggedForReview(changedRef, reason, at)`
  unless a flag for the same `changedRef` exists, in which case it replies `Done` and persists
  nothing. A flag changes no version and no content.
- **Serializers**: every state and event type has a `Codecs.serializer` under a manifest named after
  the record (`request`, `request-event`, …). Field names are protocol; a change is an added optional
  field, never a rename.
  An enumeration value is encoded as `{"type": "<case>"}` and a field holding its default value is
  omitted (found during implementation; research.md). A view row queried by an enumeration stores it
  as text.

## Entities

### CustomerRequest — entity `request`, id `rq-<ulid>` (glossary: customer request)

| Field | Notes |
|---|---|
| text | verbatim; byte-identical to the input (FR-001); never changed |
| submittedBy, submittedAt, contentHash | sha256 of `text` |
| facetIds: Seq[String] | bounded (the facets proposed), with the facet set version |
| questionIds: Seq[String] | bounded |
| roundTrip: Option[RoundTripCheck] | the latest check |
| status | `submitted`, `facets_proposed`, `facets_confirmed`, `questions_derived`, `investigating`, `designed`, `closed` |

Events: `Submitted(text, submittedBy, at, hash)`, `FacetSetProposed(facetIds, setVersion)`,
`RoundTripChecked(check)`, `FacetsConfirmed(by, at)`, `QuestionsDerived(questionIds)`,
`StatusChanged(status)`.

Commands: `submit` (refuses a second submit: `Conflict`), `record-facet-set`, `record-round-trip`,
`confirm-facets(by)` (refused unless every mandatory facet is confirmed, assumed with approval, or
unresolved and flagged — the facet states are passed in by the workflow, which read them; the request
records the decision), `record-questions`. Queries: `get`, `round-trip`.

**RoundTripCheck**: `reconstruction`, `omissions`, `unsupportedAdditions`, `contradictions`,
`ambiguities` (each `Seq[Issue(description, facetId | span)]`), `passed` (true only when all four are
empty; semantic similarity is not consulted, FR-003).

### RequirementFacet — entity `facet`, id `<request>:f<n>` (glossary: facet)

| Field | Notes |
|---|---|
| requestId, statement | |
| origin | `explicit`, `inferred`, `domain_derived`, `human_confirmed` |
| importance | `mandatory`, `preferred`, `exploratory` |
| status | `proposed`, `confirmed`, `ambiguous`, `unresolved`, `rejected`, `assumed` |
| requestSpans | `Seq[Span(start, end, quotation)]` into the request text; required when origin = explicit |
| dependencies | facet ids |
| reviews | `Seq[ReviewAction]` (bounded: a facet is reviewed a handful of times) |
| history | the earlier `(statement, origin, status)` triples, visible after a change |

Events: `Proposed(statement, origin, importance, spans, dependencies)`, `Reviewed(action, by, at,
rationale, statementAfter)`, `AssumedWithApproval(value, by, at, rationale)`.

Commands: `propose` (refused when already proposed: `Conflict`), `review(action, by, rationale,
statement?)` with action in `confirm | amend | reject | leave_unresolved | mark_ambiguous`,
`assume(value, by, rationale)` (mandatory facets only). Query: `get`.

Transitions: proposed → confirmed | ambiguous | unresolved | rejected (reviewer); ambiguous | unresolved
→ confirmed | assumed (reviewer); amend keeps the status and records the new statement. `confirm` and
`amend` set origin = `human_confirmed` and keep the prior origin in `history`.

### ScientificQuestion — entity `question`, id `<request>:q<n>` (glossary: scientific question)

`requestId`, `question`, `facetRefs: Seq[Ref]` (at least one), `evidenceNeeded`, `answerFields`
(glossary: answer field), `sufficiencyCriteria`, `status` (`unresolved`, `partial`, `answered`,
`requires_review`), `missingInformation`, `planId: Option[String]`.

Events: `Derived(...)`, `PlanRecorded(planId)`, `StatusAdvanced(status, byRef, by?)`.

Transitions: unresolved → partial | answered | requires_review; partial → answered | requires_review;
requires_review → any (reviewer). `answered` is set only by `advance(status = answered, sufficiencyRef,
by)` naming a sufficiency with status `sufficient_for_proposed_next_step` and a reviewer.

### SearchPlan — entity `search-plan`, id `<question>:plan` (glossary: search plan)

`questionRef`, `queries: Seq[Query(kind: lexical | semantic, text, expansionOf)]`, `targetSources`
(adapter names and `local`), `filters`, `parameters`, `stoppingCriterion`, `escalation`,
`executionIds` (bounded by the budgets), `version`.

Events: `Planned(...)` (a replan is a new `Planned`, a new version), `ExecutionRecorded(executionId)`.
Invariant at the gate: at least one lexical and one semantic query.

### SearchExecution — entity `search-execution`, id `<plan>:e<n>`, written once (glossary: search execution)

`planRef`, `query`, `source` (adapter name or `local`), `executedAt`, `parameters` (`topK`, `rrfK`, the
semantic cut-off, the embedding model, and for `local` the `snapshot: Seq[Ref]` of source versions
searched), `results: Seq[Hit(ref, version, rank, score, lexicalRank, semanticRank, sourceId, chunkId)]`
before eligibility, `eligibleResults`, `exclusions: Seq[Exclusion(ref, reason, ruleId, action:
excluded | downgraded)]` (glossary: exclusion), `error`, `rerunOf`, `gapId`, `coverageGap: Boolean`
(true when nothing eligible returned; FR-019).

Events: `Executed(...)`, `EligibilityApplied(eligibleResults, exclusions)`. Query: `get`.

### SourceDocument — entity `source`, id `src:<identifier>` (`doi:…`, `pmid:…`, `internal:<system>:<id>`, `file:<name>`) (glossary: source)

| Field | Notes |
|---|---|
| identifiers | doi, pmid, pmcid, internalId as available |
| title, authors, publishedAt, venue, sourceUrl | |
| licence | SPDX-like or URL, recorded on every version; never limits retention |
| retentionRestriction | `none`, `abstract_only` |
| status | `active`, `corrected`, `retracted`, `superseded`, `restricted`; `statusCheckedAt` |
| versions: Seq[SourceVersion] | each `(version, contentHash, retrievedAt, sourceVersion, parseVersion, parseCompleteness, parseIssues, sectionsVersion, quarantined, quarantineReason)` |
| sections (of the latest parsed version) | `Seq[Section(id, parentId, ordinal, kind, title, text, relatedSectionIds, parseConfidence, tableResolved)]` (glossary: section, table) |
| eligibility | `Option[Eligibility(eligible, reasons: Seq[(ruleId, detail)], assessedAt, assessedBy, ruleSetVersion)]` |
| kind | `literature`, `patent`, `regulatory`, `internal_protocol`, `internal_run`, `internal_measurement`, `internal_inventory` |
| internal | for internal kinds: `sourceSystem`, `originalId`, `responsibleResearcher`, `recordedAt`, `protocolVersion`, `conditions` |

Events: `Registered(identifiers, metadata, licence, kind, internal?)`, `ContentFetched(version, hash,
retrievedAt, sourceVersion, retentionRestriction, raw: String | abstract)`, `Parsed(version,
parseVersion, sections, issues, completeness)`, `Chunked(version, embeddingModel, embeddingRevision,
chunks: Seq[Chunk])` (one or several per version, R9), `MeasurementsRecorded(version, measurements: Seq[Measurement])`, `Extracted(version, promptVersion,
recordIds)`, `Quarantined(version, reason)`, `StatusChanged(status, reason,
checkedBy, at)`, `EligibilityAssessed(eligibility)`.

Commands: `register` (idempotent on identical metadata), `record-content(version proposal)` — refuses
nothing but replies `Duplicate(existingVersion)` with no event when the hash is already a version
(FR-012); `record-parse`, `record-chunks`, `record-measurements`, `record-extraction(version, promptVersion, recordIds)` (idempotent for one prompt
version: a source a new request returns is reused, not re-extracted), `quarantine`, `change-status(status,
reason, by)` (reviewer or `operator`), `assess-eligibility`. Queries: `get`, `section(sectionId)`,
`resolve-span(span)` → `Resolved(text) | Unresolved(reason)`, `context(chunkId)` → the chunk's section,
adjacent chunks, related sections and metadata.

A new content hash is a new version; earlier versions keep their sections resolvable (the state keeps
each parsed version's sections; a status-only change adds no version).

**Chunk** (glossary: chunk): `chunkId` (`<source>:v<n>:<section>:<ordinal>`), `sectionId`,
`startOffset`, `endOffset`, `text`, `tokenCount`, `embedding: Seq[Float]`, `embeddingModel`,
`embeddingRevision`.

**Measurement** (glossary: internal measurement): `measurementId`, `quantityKind`, `value: BigDecimal`,
`unit`, `unitValid: Boolean`, `method`, `conditions`, `qualityFlag` (`ok`, `suspect`, `rejected`),
`rawText`, `recordedAt?`. A unit outside the unit table sets `unitValid = false` and a parse issue `unit_invalid`;
nothing is converted.

**SourceSpan** (glossary: source span): `sourceRef` (id and version), `sectionId`, `chunkId?`,
`startOffset`, `endOffset`, `quotation`. Resolves when `section.text.slice(start, end)` equals the
quotation, whitespace-tolerant. A span that does not resolve can be referenced by nothing.

### ScientificFinding — entity `finding`, id `<source>:v<n>:fd<k>` (glossary: finding)

| Field | Notes |
|---|---|
| sourceRef, spans: Seq[SourceSpan] | at least one span |
| species, cellType, experimentalModel | text or absent |
| conditions, observedEndpoint, reportedOutcome | glossary: condition, endpoint, outcome |
| outcomeDirection | `increase`, `decrease`, `no_change`, `failure`, `qualitative`, `not_stated` |
| qualifications, limitations, authorInterpretation, extractorUncertainty | |
| missingValues: Map[answerField, MissingValueStatus] | `not_established_by_assessed_material`, `established_absent` (reviewer-confirmed only), `not_applicable` (FR-027) |
| status | `proposed`, `verified`, `rejected`, `requires_review`, `quarantined` |
| attempts: Seq[Attempt(n, content, validation?, verification?, defect?, at)] | bounded by the retry budget |
| defect | set when rejected or quarantined |
| dependsOn | `Seq[Ref]`: the source version |
| flags | R-flag |

Events: `Proposed(attempt = 1, content, generatedBy)`, `Validated(attempt, result)`,
`Verified(attempt, verification)`, `Rejected(attempt, defect, verification?)`,
`RequiresReview(attempt, reason)`, `Quarantined(attempt, defect)`, `Reproposed(attempt, content,
triggeredBy: defect)`, `Reviewed(action: verify | reject, by, at, rationale)`, `FlaggedForReview`.

Commands: `propose`, `record-validation`, `verified`, `rejected`, `requires-review`, `quarantine`,
`repropose(content, defect)` (refused `Conflict` when `attempts.size >= budget`: the caller quarantines
instead), `review(action: verify | reject | complain_citation, by, rationale)` (a citation complaint records
the action and moves the record to `requires_review`, FR-053), `flag-for-review`. Queries: `get`, `attempt(n)`.

Transitions: proposed → verified | rejected | requires_review | quarantined (the verification
workflow); requires_review → verified | rejected (reviewer); rejected with a retryable defect →
proposed (a new attempt, by `repropose`). Verified content is never edited; a correction is a new
attempt.

### ScientificClaim — entity `claim`, id `<source>:v<n>:cl<k>` or `rq-…:cl<k>` for a reviewer-authored claim (glossary: claim)

`proposition`, `kind` (`author_statement`, `normalised_claim`, `system_hypothesis`), `findingRefs`,
`derivedFromClaimRefs`, `spans`, `qualifications` (glossary: qualification), `speciesScope`,
`endpointScope`, `generatedBy` (`model:<prompt_version>` or `reviewer:<name>`, FR-046), `status`,
`attempts`, `defect`, `dependsOn` (findings and source), `flags`, `testedByDesignRef` (hypotheses).

Same events, commands and transitions as `finding`. A `system_hypothesis` is never `verified`: its
terminal statuses are `proposed`, `requires_review` and `rejected`; `verified` on a hypothesis is
refused `Conflict`.

**ClaimVerification** (glossary: verification), written once per attempt: `label` (`entailed`,
`contradicted`, `insufficiently_supported`, `ambiguous`), `checks: Map[negation | scope | species |
treatment | endpoint, Check(passed, evidenceQuote, note)]`, `contextSectionIds`, `promptVersion`,
`model`, `rationale`, `verifiedAt`. Invariant: any `passed = false` forbids `entailed`.

**ValidationResult** (glossary: validation): `gate` (`deterministic`, `source_context`, `eligibility`,
`applicability`, `package`, `design`), `passed`, `defects: Seq[Defect]`, `ruleVersions`, `runAt`.

**Defect** (glossary: defect): `kind` (`schema_invalid`, `span_unresolved`, `unit_invalid`,
`status_invalid`, `negation_error`, `species_overgeneralisation`, `endpoint_mismatch`,
`failure_as_success`, `unsupported_by_span`, `scope_loss`, `context_mismatch`, `model_refused`,
`other`), `severity` (`critical`, `major`, `minor`), `detail`, `retryable`.

### EvidenceAssessment — entity `assessment`, id `<question>:<record id>` (glossary: assessment)

| Field | Notes |
|---|---|
| questionRef; findingRef or claimRef | at least one |
| relationship | `supports`, `contradicts`, `qualifies`, `background`, `not_applicable` |
| applicability | `direct`, `indirect`, `unknown` |
| dimensions | `species`, `cellModel`, `mechanism`, `endpoint`, `condition`: each `match`, `mismatch`, `unknown` |
| rationale, limitations, assessor | |
| reviewStatus | `proposed`, `verified`, `requires_review`, `rejected` |
| ruleResults | `Seq[(ruleId, outcome)]`, e.g. `species_mismatch_blocks_direct` |
| dependsOn | the question, the finding or claim version |

Events: `Proposed(...)`, `RulesApplied(ruleResults, adjusted)`, `Reviewed(action, by, at, rationale)`,
`FlaggedForReview`. Invariant (FR-029, the species rule): `dimensions.species = mismatch` forbids
`applicability = direct`; the rule set rewrites `direct` to `indirect` and records it, and the
`propose` command refuses a record that still violates it. No numeric truth score field exists
(FR-030).

### EvidenceSufficiency — entity `sufficiency`, id `<question>:<decision slug>` (glossary: sufficiency)

`questionRef`, `intendedDecision` (glossary: decision), `proposition`, `status`
(`sufficient_for_proposed_next_step`, `plausible_but_unverified`, `insufficient_for_recommendation`,
`requires_expert_review`), `assumptions`, `assessmentRefs`, `criticalGapIds` (glossary: critical gap),
`coverageJudgement` (`search_inadequate`, `evidence_absent`, `mixed`; glossary: coverage judgement),
`rationale`, `escalation` (`supplemental_search`, `clarification`, `exploratory_question`, `none`),
`dependsOn` (the assessments and the executions), `flags`. Events: `Judged(...)` (a new judgement is a
new version), `FlaggedForReview`.

### KnowledgeGap — entity `gap`, id `<question>:g<n>` (glossary: coverage gap, critical gap)

`questionRef`, `description`, `kind` (`coverage`, `absence`, `contradiction`, `resource`),
`escalation`, `status` (`open`, `searched`, `clarified`, `accepted`, `closed`),
`supplementalExecutionIds`. Events: `Opened`, `SupplementalSearched(executionId)`, `Escalated`,
`Closed`. Command `record-supplemental(executionId)` is refused `Conflict` once
`supplementalExecutionIds.size >= EDP_SUPPLEMENTAL_BUDGET` (default 2): the supplemental search is
bounded by the gap.

### EvidencePackage — entity `package`, id `<question>:pk<n>` (glossary: evidence package)

`questionRefs`, `approachRef?`, `requestRef`, `facetRefs`, `assessmentRefs`, `includedSpans`,
`excluded: Seq[(ref, reason)]`, `contradictoryAssessmentRefs`, `resourceConstraints`,
`openQuestionIds`, `gapIds`, `provenanceManifest: Seq[stage-run refs]`, `validations`,
`qualityStatus` (`valid`, `invalid`, `requires_review`), `trims: Seq[Trim(ref, reason)]` (glossary:
trim), `dependsOn`, `flags`. Events: `Built(...)`, `Validated(result)`, `FlaggedForReview`. Invariant
(FR-034): `qualityStatus = valid` only when every package check passed and no trim removed a mandatory
facet, a contradiction or a qualification.

### ExperimentalApproach — entity `approach`, id `<request>:c<n>` (glossary: candidate approach)

`packageRefs`, `hypothesis`, `rationale`, `facetRefsAddressed`, `facetRefsNotAddressed`,
`affirmativeClaimRefs`, `contraryClaimRefs`, `indirectClaimRefs`, `missingEvidence: Seq[gap id]`,
`biologicalModel`, `modelLimitations`, `endpoints`, `measurements`, `controls`, `resources`,
`safetyRegulatory`, `assumptions: Seq[Assumption(statement, evidenceStatus: established | assumed |
unknown)]` (glossary: assumption), `uncertainties`, `failureModes`, `possibleResults: Seq[(result,
informationGained)]`, `prerequisites`, `status` (`proposed`, `requires_review`, `rejected`,
`selected`), `dependsOn`, `flags`. No probability field exists (FR-037); the `design-agent`'s output
guardrail refuses a reply asserting one. Events: `Proposed`, `Compared(comparisonId)`, `Selected(by,
decisionId)`, `RejectedInDecision(by, decisionId, reason)`, `Reviewed`, `FlaggedForReview`.

**Comparison** (glossary: comparison), kept on the decision: `approachRefs`, `criteria: Seq[Criterion(
name, perApproach: Map[approachId, (rating: String, rationale)])]` with names `scientific_support`,
`requirement_coverage`, `feasibility`, `resource_cost`, `timeline`, `risk`, `information_gain`,
`follow_on_value` (glossary: comparison criterion).

### DecisionRecord — entity `decision`, id `<request>:d<n>`, written once (glossary: decision record)

`comparison`, `selectedApproachRef`, `rejected: Seq[(approachRef, reason)]`, `decidedBy` (configured
reviewer), `decidedAt`, `rationale`. Event: `Decided(...)`. A second `decide` is `Conflict`.

### ExperimentalDesign — entity `design`, id `<request>:design` (glossary: experimental design)

| Field | Notes |
|---|---|
| versions: Seq[DesignVersion] | each: `version`, `approachRef`, `decisionRef`, the twelve sections, `status`, `validations`, `generatedAt`, `generatedBy` |
| twelve sections, in order | `questionAndScope`, `background: Seq[Grounded(text, claimRefs, spans)]`, `hypothesis`, `modelAndPrerequisites`, `variablesControlsComparators`, `endpointsAndMeasurementPlan`, `successFailureInconclusiveCriteria`, `resourcesTimelineDependencies`, `limitations`, `alternatives`, `outcomesAndDecisionPoints`, `evidenceManifest: Manifest(claimRefs, spans, approvalIds)` |
| status (per version) | `draft`, `under_review`, `approved`, `rejected`, `changes_requested`, `superseded` |
| approvals | `Seq[Approval(version, decision, reviewer, at, rationale, requestedChanges)]` (glossary: history) |
| flags | each names the `changedRef` and the version it concerns |

Events: `DraftGenerated(version, content, validations)`, `Submitted(version)`, `Approved(version,
by, at, rationale)`, `Rejected(version, by, at, rationale)`, `ChangesRequested(version, by, at,
rationale, requestedChanges)`, `Superseded(version, byVersion)`, `FlaggedForReview(changedRef,
version)`.

Transitions (per version): draft → under_review (operator); under_review → approved | rejected |
changes_requested (reviewer); changes_requested → a new draft version; any → superseded when a new
version is generated. An approved version's content is never changed; the latest version may be
flagged, and the flag names the approved version it concerns without changing it (SC-009).

**Rendering** (glossary: rendering): a pure function `render(DesignVersion): Markdown`; every claim
reference in the output exists in `evidenceManifest`; no claim appears that is not in the design.

### StageResult — entity `stage-run`, id = run id `run-<workflow>:<instance>` (glossary: none; FR-043)

`stages: Seq[StageExecution(stage, stageVersion, attempt, inputRefs, inputHash, outputRef,
validationRefs, modelCalls: Seq[ModelCall(promptVersion, model, effort, inputTokens, outputTokens,
latencyMs, refused)], status: passed | failed | requires_review | skipped_idempotent, defect, startedAt,
endedAt)]`. Events: `StageStarted(stage, stageVersion, attempt, inputRefs, inputHash, at)`,
`StageCompleted(...)`, `StageFailed(...)`. A `StageStarted` for a `(stage, stageVersion, inputHash)`
already completed replies `AlreadyDone(outputRef)` and persists nothing, which is how a step run twice
skips its work.

### ExperimentalRun and Observation (not built)

The spec names them for completeness. The POC records no runs: an observation from a run enters as a
`source` of kind `internal_run` through the `internal` adapter and becomes findings like any other
record. Nothing else refers to them.

### ReviewAction (embedded)

`reviewer` (a configured name), `action` (`confirm`, `amend`, `reject`, `leave_unresolved`,
`mark_ambiguous`, `verify`, `complain_citation`, `approve`, `request_changes`, `raise_confidence`), `at`, `rationale`,
`before`, `after` (snapshots of what changed).

### Reviewers (configuration, not an entity)

`Seq[Reviewer(name, role, standIn: Boolean)]` from `EDP_REVIEWERS` or `EDP_REVIEWERS_FILE`, loaded
once. `Reviewers.check(name): Either[String, Reviewer]`. Entity commands and endpoints consult it.

## Views

| View | Kind | Source | Row | Queries |
|---|---|---|---|---|
| `request-rows` | view | `request` events | id, submittedAt, status, facet and question counts | `all`, `where status` |
| `facet-rows` | view | `facet` events | requestId, statement, origin, importance, status | `by-request` (declared: `WHERE payload->>'requestId' = :request ORDER BY row_key`) |
| `question-rows` | view | `question` events | requestId, question, status | `by-request` |
| `source-rows` | view | `source` events | identifiers, title, kind, status, latest version, eligibility, quarantined | `by-identifier`, `by-status`, `by-kind` |
| `chunk-rows` | keyed view | `source` events (`Chunked`), rows keyed by chunk id | sourceId, sourceVersion, sectionId, ordinal, offsets, text, embeddingModel, embedding | `lexical(q, model)` (full-text search, ranked, top 20; R6), `by-source`, `all` streamed for semantic retrieval |
| `measurement-rows` | keyed view | `source` events (`MeasurementsRecorded`), rows keyed by measurement id | sourceId, quantityKind, value, unit, unitValid, conditions | `by-kind-and-range(kind, min, max)`, `invalid-units` |
| `record-rows` | view | `finding` and `claim` events (two views, same row shape) | sourceId, kind, status, endpoint, species, outcome, generatedBy, flagged | `by-source`, `by-status` (the review queue reads `requires_review`) |
| `assessment-rows` | view | `assessment` events | questionId, recordRef, relationship, applicability, reviewStatus, flagged | `by-question`, `by-status` |
| `execution-rows` | view | `search-execution` events | planId, questionId, source, executedAt, coverageGap, exclusionCount | `by-question` |
| `design-rows` | view | `design` events | requestId, latestVersion, latestStatus, approvedVersion, flagged | `all`, `flagged` |
| `dependents` | keyed view | `finding`, `claim`, `assessment`, `sufficiency`, `package`, `approach`, `design` events (`Proposed`, `Judged`, `Built`, `DraftGenerated`), rows keyed `<on>|<dependent>` | on: Ref, dependent: Ref, kind | `of(onKindIdVersion)`; `of-any-version(onKindId)` |
| `stage-rows` | view | `stage-run` events | runId, workflow, stage counts by status, lastStage, failed | `by-workflow`, `failed` |

A view reads one source; the queue page reads two views and merges in the endpoint. Listing never
decides anything: every decision reads the entity.

## Workflows

| Workflow | Id | State | Steps (each journalled; model steps time out at 3 minutes) |
|---|---|---|---|
| `ingestion` | `ingest:<adapter>:<identifier>` | adapter, identifier, sourceId, version, `extract: Boolean`, questionIds, stage run id, failures | `fetch` → `record` (register, record-content; a duplicate ends the workflow with `duplicateOf`) → `parse` → `chunk` → `measurements` (internal kinds) → `eligibility` → `extract` (agent; optional) → `await-verification` (starts nothing itself; the `verification-trigger` starts per record; this step polls the record statuses with a pause-and-resume loop bounded by a count) → end. Recovery: `fetch` retries twice then fails over to `record-failure`, which records the stage failure and pauses |
| `verification` | `<record>:a<attempt>` | recordRef, attempt, validation, verification, decision | `validate` (deterministic gate; a failure records `Validated(failed)` and `quarantine`s with its defect, no model call) → `verify` (agent) → `record` (`verified`, `rejected`, `requires-review` per label and the high-impact rules) → `decide-retry` (`repropose` on a retryable defect within budget, else `quarantine`) → end |
| `investigation` | request id | requestId, facet set version, questionIds, decisionId, designVersion, run id, status | `extract-facets` (agent) → `round-trip` (agent) → `await-facet-review` (`thenPause()`; `facets-confirmed` command resumes) → `derive-questions` (agent) → `investigate-questions` (starts one `question-investigation` per question with `invokeAsync`, awaits each with `awaitEnd`) → `build-packages` → `candidates` (agent) → `await-decision` (`thenPause()`; `decided` resumes) → `design` (agent) → `await-approval` (`thenPause()`; `approval-recorded` resumes; `changes_requested` transitions back to `design`) → end |
| `question-investigation` | question id | questionId, planId, executionIds, gapIds, assessmentIds, sufficiencyIds | `plan` (agent) → `execute` (local lexical and semantic, then the adapters named) → `eligibility` → `resolve` → `assess` (agent, per verified record returned) → `sufficiency` (agent, for the configured decisions) → `gaps` (opens gaps; a coverage gap with budget transitions to `supplemental` → `execute`; otherwise end) |
| `impact` | `impact:<kind>:<id>:v<version>` | changedRef, reason, visited, flagged | `walk` (reads `dependents.of(changedRef)` and `of-any-version` for later versions deriving from it, breadth-first, sends `flag-for-review` to each; records progress in state) → end |

Every step reads the `stage-run` entity first and skips a completed `(stage, stageVersion,
inputHash)`; every step passes derived ids, so a repeat addresses the same records.

## Validation rules by gate

| Gate | Applies to | Checks |
|---|---|---|
| deterministic (`deterministic@v1`) | finding, claim | codec parse; source and version exist; every span resolves (through `source.resolve-span`); required metadata present; units valid; status in enum; no `verified` set by the extractor; a hypothesis is not `verified` |
| source_context | finding, claim | `ClaimVerification` label; five checks; a failed check forbids `entailed`; critical defects quarantine; ambiguous or insufficiently supported → `requires_review` |
| eligibility (`eligibility@v1`) | source version | status active, or corrected with the correction applied; not a duplicate; parse not failed; licence recorded, never a gate |
| applicability (`applicability@v1`) | assessment | species mismatch forbids direct; endpoint mismatch forbids direct; unknown species → applicability unknown; review status set |
| package (`package@v1`) | package | every span resolves; qualifiers present on every included claim; same-study deduplication; contradictory assessments included; mandatory facets covered; eligibility and verification states enforced; no critical trim |
| design (`design@v1`) | design version | twelve sections non-empty; every claim ref verified; every span resolves; mandatory facets accounted for (confirmed, assumed with approval, or unresolved and flagged); transition legal |
| high-impact routing (FR-025) | finding, claim; sufficiency | (a) ambiguous or insufficiently supported and (c) a system hypothesis are routed to `requires_review` by the `verification` workflow's `record` step; (b) the only affirmative evidence for a question or a mandatory facet is known only once the question's assessments exist, so the `question-investigation.sufficiency` step sends `requires-review` to the sole supporting record and judges the sufficiency `requires_expert_review` |

## Evaluation data (files under `evals/`, not entities)

- `cases/*.json`: request text, expected facet origins, expected question set, fixture sources,
  expected relationships and applicability, expected sufficiency per decision, expected critical
  failures (adversarial), acceptable alternatives.
- `passages/*.json`: a section text, a proposed extraction, the expected verification label and the
  checks expected to fail.
- `cassettes/<stage>@<version>/<case>.json`: the model's reply text for `(prompt_version, case
  marker)`, `authored` or `recorded`.
- `baseline.json`: the last agreed per-stage metrics, the pinned prompt, model, embedding and
  cassette versions, and the source snapshot (the `Ref`s the run read).
- `reports/`: evaluation reports and `drift.json` from the live tier (ignored).
- `reviewers.example.json` (committed) and the file `EDP_REVIEWERS_FILE` names (ignored): the
  configured reviewer list, each `name`, `role`, `standIn`.
