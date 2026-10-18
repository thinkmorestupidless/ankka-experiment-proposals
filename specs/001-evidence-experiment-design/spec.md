# Feature Specification: Evidence-Driven Scientific Experimental Design POC

**Feature Branch**: `001-evidence-experiment-design`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "use the customer's POC specification document as your guide for what to spec"

**Source document**: the customer's Evidence-Driven Scientific Experimental Design POC specification (v2, dated 15 October 2026), which is not part of this repository. This specification restates that document as a feature specification: what the proof of concept must do and why, for whom, and how its success is judged. Where the source document named technologies, this specification names only the capability.

## Overview

A cultivated-food company (the customer) receives customer requests such as "Can you make foie gras?". Today, answering one means a scientist manually reads literature and internal lab records, decides what is and is not established, and drafts an experimental proposal. The proof of concept (POC) is a scientific investigation pipeline that performs that work in explicit, inspectable stages and hands the result to a scientist for approval.

The pipeline preserves the customer's intent, makes explicit what must be established, separates reported observations from author claims and from the system's own hypotheses, assesses whether each piece of evidence actually answers a specific scientific question, and produces a structured experimental design whose reasoning and sources can be inspected claim by claim. It must be able to answer "insufficient evidence" and recommend further research or a narrowly scoped feasibility experiment instead of asserting feasibility that the evidence does not support.

The POC deliverable is a reproducible end-to-end demonstration on the cultivated foie gras question, with at least two candidate experimental approaches, a source-traceable proposal, explicit evidence gaps, human review, an expert-labelled evaluation dataset, and fault-injection tests showing that critical extraction errors cannot silently reach a recommendation.

### Actors

- **Customer**: the person or organisation posing the request. In the POC, the request is entered on their behalf.
- **Scientific reviewer**: a customer scientist, named on a configured list of reviewers, who confirms requirement interpretations, reviews flagged claims and assessments, labels evaluation cases, and approves or rejects experimental designs. No scientific or operational decision is authorised without this role.
- **Operator**: the person running the pipeline, inspecting stage results, injecting test faults and reading quality reports. In the POC this is the delivery team.
- **The system**: the pipeline itself, including any automated reasoning steps. Its outputs are proposals until a reviewer acts on them.

### Reference scenario

Customer input: "Can you make foie gras?". The scenario fixtures include three illustrative papers, each a constructed fixture:

| Source | Reported finding | Correct use | Prohibited inference |
|---|---|---|---|
| Paper A | Avian hepatic cells proliferated under specified conditions | Direct evidence on the proliferation question | Proliferation proves lipid accumulation |
| Paper B | Lipid accumulation in a different (non-avian) species | Indirect mechanistic or plausibility evidence, with transfer limitations | Target avian cells are proven to accumulate lipid |
| Paper C | A cell culture method was unsuccessful | Negative or limiting evidence under the tested conditions | The method always fails, or the failure counts as success |

For the question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?", the correct answer from this material is "not established by the assessed material", and the defensible next step is a feasibility investigation of lipid accumulation, not a claim that production is viable.

## Clarifications

### Session 2026-10-15

- Q: How are the acceptance scenarios proven, given that model output is not deterministic? → A: In both tiers: against recorded model responses in the pull-request tier and against the live model in the pre-release tier; a scenario passes only when both tiers pass.
- Q: What full text may be retained from external sources? → A: The full text of anything the adapters can fetch, held locally under access control, on the basis that the customer's institutional access covers internal research use. The licence is still recorded on every source.
- Q: How is a reviewer identified? → A: From a configured list of named reviewers; every review action, decision and approval must name one of them; access to the POC installation itself is by one shared credential that identifies no one.
- Q: Adopt the glossary's 83 proposed terms and refused synonyms as written? → A: Yes, all adopted; `GLOSSARY.md` is the project's vocabulary and the features and this spec use its terms.
- Q: Are the three reference papers real or constructed? → A: Constructed fixtures only, written to contain exactly the proliferation, non-avian lipid accumulation and failed-culture findings; real papers enter through ingestion (US5) and the evaluation dataset, not through the three-paper case.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Interpret a customer request into reviewable requirement facets (Priority: P1)

A reviewer enters a customer request. The system stores the request unchanged and produces a set of requirement facets, each labelled with where it came from (explicit in the request, inferred from context, derived from domain knowledge, or confirmed by a human), how important it is (mandatory, preferred, exploratory) and whether it is resolved. The reviewer can confirm, amend, reject or leave each facet unresolved. The system checks its own interpretation by reconstructing the request from the facets and flagging omissions, unsupported additions, contradictions and unresolved ambiguities.

**Why this priority**: Every later stage inherits the interpretation of the request. If inferred assumptions are silently promoted to requirements, the whole proposal answers the wrong question. This is the first thing the demonstration shows.

**Independent Test**: Enter "Can you make foie gras?" and verify that the stored request is byte-identical to the input, that at least the product, production approach, biological source, objective, desired attributes, time, success and resource facets are produced with correct origin labels, and that a reviewer can confirm or amend them with the decision recorded.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/request/facets.feature`: the customer request is kept unchanged and every facet carries its origin
- added `features/request/facets.feature`: a facet that nothing in the request supports is an unsupported addition
- added `features/request/facets.feature`: request content that no facet covers is an omission
- added `features/request/facets.feature`: a similar reconstruction alone does not pass the round-trip check
- added `features/request/facets.feature`: confirming a facet records the reviewer and keeps the earlier state
- added `features/request/facets.feature`: an unresolved mandatory facet is flagged on every proposal

---

### User Story 2 - Derive scientific questions with evidence acceptance criteria (Priority: P1)

From the confirmed facets, the system derives explicit scientific questions. Each question links to the facets it investigates, states what evidence would be needed to answer it, lists the fields an answer must contain, has a status, and declares when missing information applies. Questions are distinct from search queries.

**Why this priority**: Evidence can only be assessed "relative to a specific question". Without explicit questions and acceptance criteria, relevance collapses into generic document similarity, which is the failure mode the POC exists to avoid.

**Independent Test**: With the reference facets confirmed, verify that questions equivalent to the six reference questions (target properties, candidate cell types, proliferation, lipid accumulation, measurements, operational constraints) are produced, each linked to facets and carrying evidence-needed and answer-criteria lists.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/request/questions.feature`: deriving questions covers every mandatory facet and states the fields each answer must carry
- added `features/request/questions.feature`: a question with no eligible evidence is unresolved, not answered

---

### User Story 3 - Extract source-grounded findings and claims and verify them against the source (Priority: P1)

For each ingested source, the system extracts findings (reported observations tied to model, conditions, endpoint and outcome), author statements, normalised claims and, where it synthesises something new, system hypotheses, each as a distinct kind. Every extraction cites one or more exact source spans that resolve to original text or data. A separate verification step compares each proposed claim with the full relevant source context and labels it entailed, contradicted, insufficiently supported or ambiguous, checking negation, scope, species, treatment and endpoint specifically. Only verified claims may be used as affirmative evidence; rejected and uncertain extractions are retained with their status.

**Why this priority**: A plausible but wrong extraction (a dropped negation, a swapped species, a failure recorded as success) is the central threat. The three-paper case is the acceptance test of the whole POC.

**Independent Test**: Ingest fixtures for Papers A, B and C and verify that the extracted findings keep proliferation and lipid accumulation as different endpoints, keep Paper B's species qualifier, record Paper C as unsuccessful, and that every cited span resolves to the fixture text.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/evidence/extraction.feature`: a proliferation finding yields no lipid accumulation claim
- added `features/evidence/extraction.feature`: a finding keeps the species of its source
- added `features/evidence/extraction.feature`: an unsuccessful experiment is retained as a failure
- added `features/evidence/extraction.feature`: every extraction cites source spans that resolve
- added `features/evidence/verification.feature`: a claim whose cited span does not support it is not admitted
- added `features/evidence/verification.feature`: a broken span reference fails before verification
- added `features/evidence/verification.feature`: a retry is bounded, classified and checked against the source
- added `features/evidence/verification.feature`: an ambiguous interpretation is routed to a reviewer

---

### User Story 4 - Assess evidence per question and declare sufficiency or abstain (Priority: P1)

For each verified finding or claim and each scientific question it may bear on, the system records an assessment: the relationship (supports, contradicts, qualifies, background, not applicable), applicability (direct, indirect, unknown) across species, cell model, mechanism, endpoint and condition, a rationale, limitations and a review state. It then judges sufficiency for a named decision, with statuses such as sufficient for the proposed next step, plausible but unverified, insufficient for recommendation, or requires expert review. A sufficiency result states the proposition, the decision, the assumptions, the supporting findings and the critical gaps, and distinguishes poor search coverage from genuinely absent evidence.

**Why this priority**: This is where the system must be able to say "insufficient evidence". Without claim-relative sufficiency, accurate extractions still get upgraded into unsupported feasibility claims.

**Independent Test**: With the three verified papers, verify that the lipid accumulation question is assessed as not established for the target avian model, that Paper A is "supports, direct" for proliferation but "background or not applicable" for lipid accumulation, that Paper B is "supports, indirect" with a species limitation, and that Paper C is recorded as contradicting or qualifying evidence.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/evidence/assessment.feature`: a proliferation finding is not direct lipid accumulation evidence
- added `features/evidence/assessment.feature`: a finding in another species is indirect evidence
- added `features/evidence/assessment.feature`: an unsuccessful experiment is contradicting or qualifying evidence
- added `features/evidence/assessment.feature`: an assessment presents no single truth score
- added `features/evidence/sufficiency.feature`: the three-paper evidence is insufficient to recommend production feasibility
- added `features/evidence/sufficiency.feature`: the three-paper evidence can support proposing a feasibility experiment
- added `features/evidence/sufficiency.feature`: an empty search is a coverage gap, not absent evidence

---

### User Story 5 - Ingest external literature and internal records with provenance (Priority: P2)

An operator ingests scientific literature from approved discovery sources and representative internal laboratory records. Every source document keeps its persistent identifiers, bibliographic metadata, access and licence details, correction, retraction or supersession status, retrieval time, content hash and version. Documents are parsed into sections, tables, figures and spans with stable locations, and split into retrieval chunks that keep their parent section. Internal records keep original identifiers, responsible researcher, timestamps, units, conditions and protocol versions, and quantitative data stays structured and queryable rather than only embedded.

**Why this priority**: Grounding depends on resolvable spans and known source status. The three-paper case can be demonstrated on fixtures, so ingestion ranks just below the reasoning stories, but the demonstration must include at least one real internal dataset and a representative literature collection.

**Independent Test**: Ingest one literature document and one internal measurement record and verify that each can be traced from any derived chunk or span back to its original document version, section and location, and that re-ingesting the same content is detected as a duplicate.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/sources/ingestion.feature`: an ingested source keeps its identity, status and provenance
- added `features/sources/ingestion.feature`: ingesting the same source twice creates no duplicate
- added `features/sources/ingestion.feature`: an unresolved table is flagged, not dropped
- added `features/sources/ingestion.feature`: a unit is validated and never converted silently
- added `features/sources/ingestion.feature`: a source's status is recorded and surfaced to eligibility
- added `features/sources/ingestion.feature`: a source whose full text cannot be fetched keeps metadata and abstract
- added `features/sources/ingestion.feature`: a corrupt extraction is quarantined with a reason

---

### User Story 6 - Plan and execute reproducible searches with eligibility separate from ranking (Priority: P2)

For each scientific question the system builds a search plan: the originating question, lexical expansions, a semantic query, target sources, filters, parameters and a stopping or escalation criterion. Searches run against native discovery sources for new material and against the local store for ingested material, combining lexical and semantic retrieval. Each execution records query, source, time, parameters and returned identifiers and versions. After retrieval, eligibility is applied separately from ranking, and exclusions or downgrades of high-ranked sources are recorded with reasons.

**Why this priority**: Reproducible search records let a missing paper, a wrong ranking and a context omission be told apart. Ranking score must never stand in for scientific authority.

**Independent Test**: For the lipid accumulation question, verify a search plan with at least two lexical and one semantic query is produced, that executing it against a seeded local store returns the three fixture papers, and that an ineligible seeded source is excluded with a logged reason.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/search/retrieval.feature`: a search plan carries its question, queries, sources and stopping criterion
- added `features/search/retrieval.feature`: a search execution records everything it returned
- added `features/search/retrieval.feature`: a retracted source ranked first is excluded with a reason
- added `features/search/retrieval.feature`: a retrieved chunk is resolved with its context
- added `features/search/retrieval.feature`: a search execution can be re-run from its record
- added `features/search/retrieval.feature`: a coverage gap triggers a bounded supplemental search
- added `features/search/retrieval.feature`: an empty search result is a coverage gap

---

### User Story 7 - Generate and compare candidate experimental approaches (Priority: P2)

From validated evidence packages the system proposes at least two candidate approaches. Each states its hypothesis and rationale, the facets it does and does not address, affirmative, contrary, indirect and missing evidence, the biological model and its limitations, endpoints, measurements and controls, resource and safety constraints, uncertainties and failure modes, the distinct information each possible result would give, prerequisites and review status. Candidates are compared on transparent criteria (scientific support, requirement coverage, feasibility, cost, timeline, risk, information gain, follow-on value) and a decision record keeps the selected and rejected alternatives with rationale.

**Why this priority**: Alternatives and rationale are what make the proposal reviewable rather than a single generated answer.

**Independent Test**: With the reference evidence packages, verify that at least two candidates are produced, each with the required fields, that no success probability is invented, and that a decision record names the selected candidate and the reasons the others were rejected.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/design/candidates.feature`: at least two candidates are generated, each with its evidence and its unknowns
- added `features/design/candidates.feature`: candidates are compared on stated criteria without invented probabilities
- added `features/design/candidates.feature`: a decision keeps the rejected alternatives and their reasons

---

### User Story 8 - Produce a structured experimental design and record the reviewer's decision (Priority: P2)

The selected candidate is instantiated as a typed, versioned experimental design with twelve required sections: original question and scope, grounded background, hypothesis, experimental model and prerequisites, variables and controls, endpoints and measurement plan, pre-agreed success, failure and inconclusive criteria, resources and timeline, limitations, alternatives, potential outcomes and decision points, and an evidence manifest with approval history. A human-readable rendering is produced from it. A scientific reviewer approves, rejects or requests changes, with rationale, and that decision is recorded against the design version.

**Why this priority**: The design is the deliverable the customer sees, and the approval trail is what makes it authorised.

**Independent Test**: Generate a design from a selected candidate, render it, verify every background reference resolves to a source span, and record both an approval and a rejection on separate versions with rationale.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/design/experimental-design.feature`: a design has every required section and every reference resolves
- added `features/design/experimental-design.feature`: a rendering is derived from the design and adds nothing
- added `features/design/experimental-design.feature`: a rejection stays visible with its reason
- added `features/design/experimental-design.feature`: an unapproved design is presented as a proposal

---

### User Story 9 - Measure quality per stage and prove that injected faults are blocked (Priority: P3)

An operator maintains an expert-labelled evaluation dataset of end-to-end cases and focused extraction passages, including adversarial fixtures (species mismatch, negation, proliferation versus lipid accumulation, negative results, superseded protocols, retracted papers, hypotheses framed as results, correlation versus causation, missing or misconverted units, absent tables, resource unavailability, genuinely insufficient evidence). Each stage reports its own measurements, and critical failures are a separate blocking category from aggregate quality. Fault injection deliberately corrupts an extraction and shows it cannot become affirmative recommendation evidence.

**Why this priority**: The source document states that a safeguard is not proved effective by being implemented. Measured results are required for the demonstration report, but can only be produced once the stages exist.

**Independent Test**: Run the six reference fault injections and verify each is caught at the stated gate, then run the labelled dataset and produce a per-stage report with critical failures listed separately.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/assurance/fault-injection.feature`: an injected extraction fault is caught at its gate
- added `features/assurance/fault-injection.feature`: a caught endpoint fault leaves the lipid accumulation gap open
- added `features/assurance/evaluation.feature`: an evaluation report measures each stage separately and blocks on critical failures
- added `features/assurance/fault-injection.feature`: a disabled safeguard makes an independent test fail

---

### User Story 10 - Propagate the impact of a changed finding or source status (Priority: P3)

When a finding's verification status, a claim's content or a source's eligibility changes, the system identifies every assessment, sufficiency judgement, evidence package, candidate and design that depended on the changed version and marks them for review, without silently rewriting prior reasoning.

**Why this priority**: Required by the acceptance criteria and the demonstration, and the foundation of later incremental impact analysis, but it depends on every other story existing.

**Independent Test**: Mark Paper B as retracted after a design has been approved and verify that its assessments, the affected evidence package, candidates and the design are listed as requiring review while the approved version remains intact and readable.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/assurance/impact.feature`: a retracted source flags every dependent for review
- added `features/assurance/impact.feature`: a flagged design keeps its approved version unchanged

---

### Edge Cases

*(each is a rule the features do not yet cover; once answered, it becomes a scenario, or a row of a `Scenario Outline`'s `Examples`, referenced above)*

- The customer request is a bare question with no explicit constraints: all facets beyond the product are inferred or domain-derived and must be marked so.
- Two scientific questions share the same finding: the finding is assessed separately for each, with possibly different relationships.
- A paper already ingested is returned by a search for a new request: it is reused, not re-extracted, but its applicability and status are reassessed.
- A finding's measurement is missing from the extracted material: status is "not established by assessed material", never "the source reports none".
- Results from several papers come from the same underlying study or dataset: they are not counted as independent support.
- Context trimming is required to fit a budget: mandatory facets, contradictory evidence and methodological qualifiers cannot be dropped silently; omissions are logged.
- A pipeline stage fails midway: it can be resumed without duplicating scientific records or altering approved evidence.
- A source's full text cannot be fetched: metadata and abstract are retained, spans can only point into those, and the source is marked as abstract-only (scenario in `features/sources/ingestion.feature`).
- A reviewer is unavailable: records requiring review stay in that state and are not auto-promoted after a timeout.
- The retry budget for a classified defect is exhausted: the record is quarantined with the defect classification and history, not re-generated indefinitely.
- An internal measurement arrives with an unrecognised unit: it is rejected or quarantined, not converted by guess.
- A search returns many high-ranked but ineligible sources: each exclusion is logged with reason so that coverage reports do not read as "no evidence".

## Requirements *(mandatory)*

### Functional Requirements

**Request interpretation**

- **FR-001**: System MUST store every customer request unmodified and keep it retrievable from every derived artefact.
- **FR-002**: System MUST produce requirement facets, each with an identifier, a statement, source spans where the facet is explicit, an origin (explicit, inferred, domain-derived, human-confirmed), an importance (mandatory, preferred, exploratory) and a resolution status (proposed, confirmed, ambiguous, unresolved).
- **FR-003**: System MUST check facet extraction by round-trip reconstruction with explicit detection of omissions, unsupported additions, contradictions and unresolved ambiguity. Semantic similarity alone MUST NOT constitute a pass.
- **FR-004**: Reviewers MUST be able to confirm, amend, reject or leave unresolved any facet, with their identity, time and rationale recorded and prior states kept in history.
- **FR-004a**: Every review action, decision record and approval MUST name a reviewer from a configured list of named reviewers; an action naming anyone else is rejected. The POC does not manage accounts.

**Scientific questions**

- **FR-005**: System MUST derive scientific questions from confirmed facets, each linked to at least one facet and carrying evidence needed, mandatory answer fields, sufficiency criteria, a status and an explicit missing-information state.
- **FR-006**: System MUST ensure every mandatory facet is investigated by at least one question or is explicitly flagged as uninvestigated.
- **FR-007**: System MUST keep scientific questions distinct from search queries.

**Ingestion**

- **FR-008**: System MUST record, for each external source, persistent identifiers where available, title, authors, date, venue, source location, access and licence terms, correction, retraction and supersession status, retrieval time, content hash, source version and parse version.
- **FR-009**: System MUST parse documents into a hierarchy of sections, tables, figures and captions where accessible, with stable locations, and into retrieval chunks that retain their parent section and the links between methods and results.
- **FR-010**: System MUST ingest internal records (protocol versions, experiment runs, cell line and materials records, assay measurements, results, failures, annotations, inventory) preserving original identifiers, responsible researcher, timestamps, source system, units, conditions and protocol version.
- **FR-011**: System MUST keep quantitative internal data structured and queryable by value and rule; a semantic index entry MUST NOT be the sole representation of a measurement.
- **FR-012**: System MUST detect duplicates and versions by identifier and checksum, flag incomplete parses and unresolved tables, validate measurement types and units without silent conversion, and quarantine corrupt or materially ambiguous extractions with a reason.
- **FR-013**: System MUST record parser and semantic index versions with every ingested artefact.
- **FR-014**: System MUST retain the full text of any external source its adapters can fetch, held locally under access control for the customer's internal research use, MUST record the licence and access terms on every source version, and MUST control access to internal customer material. Public redistribution of retained text is out of scope.

**Search and retrieval**

- **FR-015**: System MUST produce, per scientific question, a search plan holding the originating question, lexical expansions, a semantic query, target sources, filters, parameters and a stopping or escalation criterion.
- **FR-016**: System MUST record each search execution with query, source, retrieval time, parameters, and all returned result identifiers and versions.
- **FR-017**: System MUST combine lexical and semantic retrieval over ingested material, deduplicate results, and resolve parent sections, adjacent context, tables and metadata for selected chunks.
- **FR-018**: System MUST apply source eligibility after ranking, as a separate judgement, and record why any high-ranked source was excluded or downgraded.
- **FR-019**: System MUST treat an empty or sparse search result as a coverage gap, never as evidence of impossibility.

**Findings, claims and verification**

- **FR-020**: System MUST extract and store reported findings, author statements, normalised claims and system hypotheses as distinct kinds, each with status.
- **FR-021**: Every finding and claim MUST cite one or more source spans that resolve to the original document version and location, and MUST record model or species, cell type, condition, endpoint, outcome, qualifications, limitations, author interpretation where present, and extraction uncertainty.
- **FR-022**: System MUST run deterministic validation on every extraction: structural validity (every required field present and of the right kind), existence of the source and version, resolvable spans and quotations, required metadata, valid units, and no forbidden or missing statuses.
- **FR-023**: System MUST run a verification step, separate from extraction, that compares each proposed claim with the full relevant source context and labels it entailed, contradicted, insufficiently supported or ambiguous, checking negation, scope, species, treatment and endpoint specifically.
- **FR-024**: System MUST NOT admit unverified, rejected or uncertain claims as affirmative evidence to candidate generation or design generation, and MUST retain them with their status.
- **FR-025**: System MUST route to a scientific reviewer every interpretation that is (a) verified as ambiguous or insufficiently supported, (b) a claim that would be the only affirmative evidence for a scientific question or a mandatory facet, or (c) a system hypothesis. These three conditions define "high-impact" for the POC.
- **FR-026**: System MUST NOT regenerate an extraction until a judge passes. A retry MUST be triggered by a classified defect, bounded in count, preserve history and re-verify against the original source.
- **FR-027**: System MUST record a missing extracted value on the finding with a missing-value status of "not established by assessed material", and MUST NOT record it as absent unless the full relevant material has been reviewed and a reviewer confirms the absence.

**Evidence assessment and sufficiency**

- **FR-028**: System MUST record, for each verified finding or claim and each question it bears on, a relationship (supports, contradicts, qualifies, background, not applicable), an applicability (direct, indirect, unknown) with species, cell model, mechanism, endpoint and condition dimensions, a rationale, limitations, the assessor identity or model and version, and a review state.
- **FR-029**: System MUST enforce rule-based constraints where they are simple and certain, including that a species mismatch blocks classification as a direct same-species result.
- **FR-030**: System MUST NOT present an uncalibrated single numeric truth score as the evidence judgement.
- **FR-031**: System MUST judge sufficiency relative to a named decision, with statuses including sufficient for proposed next step, plausible but unverified, insufficient for recommendation, and requires expert review, stating the proposition, decision, assumptions, supporting findings and critical gaps.
- **FR-032**: System MUST distinguish inadequate search coverage from genuinely absent evidence and escalate unresolved gaps by bounded supplemental search, clarification request or an exploratory experimental question.

**Evidence packages**

- **FR-033**: System MUST assemble a versioned evidence package per question or candidate containing the original intent, confirmed facets, question and criteria, verified source-grounded findings and claims, assessments, contradictory evidence, exclusions with reasons, resource constraints, open questions, a provenance manifest and a quality status.
- **FR-034**: System MUST validate each package: every passage resolves to its source, methodological qualifiers survive summarisation, results from one underlying study are not counted as independent, contradictory and failed experiments are preserved, every mandatory facet has evidence, a declared assumption or a flagged gap, eligibility and verification states are enforced, and budget trimming cannot silently remove critical constraints.
- **FR-035**: System MUST make the selected original passages, not only derived claim objects, available to downstream generation and to reviewers, and MUST log coverage and exclusions.

**Candidates and design**

- **FR-036**: System MUST generate at least two candidate experimental approaches per investigation, each with hypothesis, rationale, facets addressed and not addressed, affirmative, contrary, indirect and missing evidence identifiers, biological model and limitations, endpoints, measurements and controls, resource, safety and regulatory constraints, uncertainties and failure modes, the information each possible result would yield, prerequisites and review status.
- **FR-037**: System MUST compare candidates on stated criteria (scientific support, requirement coverage, feasibility, cost, timeline, risk, information gain, follow-on value), MUST NOT assert invented success probabilities, and MUST keep rejected alternatives and the decision rationale.
- **FR-038**: System MUST produce the experimental design as a typed, versioned object with the twelve required sections, and MUST derive any human-readable rendering from it.
- **FR-039**: Every reference in a design MUST resolve to a verified claim and source span; every mandatory facet MUST appear as confirmed, explicitly assumed with approval, or unresolved and flagged.
- **FR-040**: Reviewers MUST be able to approve, reject or request changes on a design version, with identity, time and rationale recorded, and no design MUST be presented as approved or operationally authorised without such a record.

**Provenance, versioning and impact**

- **FR-041**: System MUST give every persisted artefact a durable identity, a version, origin or provenance, timestamps and relevant reviewer or model metadata, and MUST keep source versions immutable or append-only.
- **FR-042**: Links between artefacts MUST identify the assessed version so later edits do not rewrite prior reasoning.
- **FR-043**: System MUST record a stage result for each pipeline stage with stage version, run identifier, input references, output reference, validations and status.
- **FR-044**: A failed stage MUST be resumable without duplicating scientific records or changing approved evidence.
- **FR-045**: When a finding, claim or source status changes, system MUST identify every dependent assessment, sufficiency judgement, package, candidate and design and flag it for review.
- **FR-046**: System MUST record whether an inferred claim was generated by the system or by a scientist.

**Evaluation**

- **FR-047**: The project MUST maintain an expert-labelled evaluation dataset of roughly 20 to 30 end-to-end cases and, where reviewer capacity allows, 30 to 50 focused extraction passages, covering the listed adversarial categories, with labels that admit acceptable alternative interpretations and uncertain cases.
- **FR-048**: System MUST report measurements per stage, including facet precision and recall, missed questions, parse completeness, retrieval recall and ranking quality, contradictory-evidence recall, ineligible-evidence leakage, critical-error recall, false-rejection rate, extraction fidelity, assessment misclassification, unsafe recommendation rate, appropriate abstention, package coverage and reviewer acceptance.
- **FR-049**: Critical failures (unsupported scientific assertions, violated mandatory constraints, invalid citations, unreviewed high-impact conclusions) MUST be reported as a separate blocking category, and no release MUST pass on aggregate quality alone.
- **FR-050**: Evaluation runs MUST pin source snapshots, prompt versions, model versions and semantic index versions, record results for comparison against a baseline, and be repeatable where variance matters.
- **FR-051**: The test suite MUST include tests derived independently of the generation prompts, and inserting a known defect (bypassed eligibility, removed species guard, accepted mismatched span) MUST cause the corresponding test to fail.
- **FR-052**: Operators MUST be able to inject a corrupted claim or change a source's eligibility and observe either validation blocking it or impact propagation flagging dependents.
- **FR-053**: During operation, system MUST record reviewer overrides, proposal rejections, error categories, citation complaints, evaluation cost and latency, and allow reviewed failures to be promoted to regression cases.
- **FR-054**: Every scenario in the living features MUST be proven in two tiers: against recorded model responses in the pull-request tier and against the live model in the pre-release tier. A scenario counts as passing only when both tiers pass; a live failure with a replay pass is reported as drift, not as a pass.

### Key Entities *(each a term in the project glossary)*

The canonical names below are the glossary's (`GLOSSARY.md`). In particular: a *source* is any literature document or internal record (not "document"); a *facet* is one interpreted requirement (not a bare "requirement"); a *reviewer* is the named customer scientist (not "user", "scientist" or "approver").

- **Customer Request**: the unmodified input, with time and submitter; the root of every investigation.
- **Requirement Facet**: one interpreted requirement, with origin, importance, status, request spans and dependencies on other facets.
- **Scientific Question**: an explicit question linked to facets, with evidence needed, mandatory answer fields, sufficiency criteria and status.
- **Search Plan** and **Search Execution**: the per-question queries and parameters, and each run's source, time, parameters and returned identifiers.
- **Source Document**, **Document Section**, **Retrieval Chunk**, **Source Span**: the container, its structural parts, its retrieval units and an exact resolvable location in a given document version.
- **Source Eligibility Assessment**: the status and usability judgement on a source version (retracted, superseded, licence-restricted, eligible).
- **Scientific Finding**: a reported observation from a specific experiment or record, with species, cell type, model, condition, endpoint, outcome, limitations, spans and extraction status.
- **Scientific Claim**: an author statement, normalised claim or system hypothesis, derived from findings or other claims, with qualifications and status.
- **Claim Verification** and **Validation Result**: the outcome of deterministic and source-context checks on an extraction, with the classified defect where failed.
- **Evidence Assessment**: a question-specific judgement on a finding or claim: relationship, applicability, rationale, limitations, assessor and review state.
- **Evidence Sufficiency**: a decision-relative judgement over a set of assessments, with status, assumptions and critical gaps.
- **Knowledge Gap**: a named missing piece of evidence, with whether it reflects coverage or absence, and its escalation.
- **Evidence Package**: the versioned, validated context for a question or candidate, with inclusions, exclusions and reasons, gaps and a provenance manifest.
- **Experimental Approach**: a candidate, with hypothesis, evidence, model, endpoints, controls, resources, assumptions, limitations and status.
- **Decision Record**: the selection or rejection of approaches with rationale and decider.
- **Experimental Design**: the typed, versioned twelve-section proposal instantiating a selected approach.
- **Approval**: a reviewer's approve, reject or change request on a design version, with rationale.
- **Experimental Run**, **Measurement**, **Observation**: the records of a design being carried out and what it produced, which can feed back as findings.
- **Stage Result**: the provenance record of one stage execution, with inputs, output, validations and status.
- **Round-trip Check**: the result of reconstructing the request from its facets, with omissions, unsupported additions, contradictions and ambiguities.
- **Comparison**: the side-by-side judgement of candidate approaches on the eight stated criteria, feeding the decision record.
- **Exclusion**: why a retrieved source was excluded or downgraded after ranking, recorded against the search execution.
- **Trim**: one removal from an evidence package to fit a budget, logged with its reason; never a mandatory facet, a contradiction or a qualification.
- **Link**: a versioned connection between two records, naming the version of each; impact propagation follows links.

Relationships: a request has facets; facets are investigated by questions; questions are searched by executions that return documents; documents have sections and chunks; spans are located in sections or chunks; findings are grounded in spans; claims derive from findings or claims; assessments assess a question against a finding or claim; sufficiency summarises assessments; packages include verified assessments and spans; approaches address facets and are motivated by packages; decision records select or reject approaches; a design instantiates a selected approach; a run follows an approved design; observations are produced by runs.

## Success Criteria *(mandatory)*

The source document is explicit that quantitative model-quality thresholds must be agreed with customer reviewers after a labelled baseline is measured, not invented in advance. The criteria below are therefore structural guarantees, counts and demonstrable behaviours, plus the obligation to measure and report.

### Measurable Outcomes

- **SC-001**: For the reference request, the original text is retrievable unchanged from every derived artefact, and 100% of mandatory facets are in one of three states at proposal time: confirmed, explicitly assumed with approval, or unresolved and flagged.
- **SC-002**: Every scientific question in the demonstration links to at least one facet and at least one search execution, and any execution can be re-run from its record to return the same identifiers against the same source snapshot.
- **SC-003**: At least one internal dataset and a literature collection of at least 10 real sources beyond the fixtures, covering all six scientific questions and including at least one abstract-only source, are ingested with provenance such that 100% of spans cited anywhere in the demonstration resolve to the original document version and location.
- **SC-004**: In the three-paper case, zero prohibited inferences occur: proliferation is never assessed as direct lipid accumulation evidence, Paper B is never classified as a direct same-species result, and Paper C is never recorded as a success.
- **SC-005**: All six reference fault injections are caught at the stated gate, and in each case the corrupted claim contributes nothing to affirmative recommendation evidence (zero downstream leakage on the injected cases).
- **SC-006**: Retrieval, extraction, assessment and sufficiency are each evaluated separately against the expert-labelled dataset, with results, baseline comparison and stated limitations in a report; critical failures are listed as a separate blocking category.
- **SC-007**: At least two candidate approaches are recorded for the reference investigation, each with supporting, contrary and missing evidence, and a decision record preserves the rejected alternatives with rationale.
- **SC-008**: A typed experimental design with all twelve sections is rendered, and at least one scientific approval or rejection decision is recorded against it with rationale.
- **SC-009**: Changing one finding's status or one source's eligibility flags 100% of its dependent assessments, packages, candidates and designs for review while leaving prior approved versions unchanged.
- **SC-010**: Every abstention ("insufficient evidence", "requires expert review") and every critical failure in the demonstration is visible in the record, and no conclusion is presented as scientifically authorised without a reviewer decision.
- **SC-011**: A scientific reviewer can follow the eleven-step demonstration walkthrough from request to recorded approval decision end to end on the reference scenario, reproducibly, within two hours of reviewer time in replay mode.
- **SC-012**: The evaluation dataset contains at least 20 end-to-end labelled cases covering every listed adversarial category, with expert labels that admit alternative interpretations.
- **SC-013**: Inserting each of the three listed known defects in a disposable copy of the system causes at least one independent test to fail, and restoring the control makes it pass.

## Assumptions

- The six-week delivery window and the reference scenario (cultivated foie gras) define POC scope. The delivery plan in the source document (model and baseline, ingestion, retrieval, claims and evidence, alternatives and proposal, evaluation and demonstration) is treated as planning input, not as a requirement.
- A customer scientific reviewer is available for facet confirmation, claim review, labelling and design approval. If not, review steps are exercised by a stand-in and the report says so.
- Reviewers are identified by name from a configured list; a stand-in reviewer is a named entry on that list, marked as a stand-in in the evaluation report.
- External discovery is limited to approved scientific sources (bibliographic databases, full-text repositories, publisher full text reachable through the customer's institutional access, patents and regulatory sources where relevant). Full text that an adapter can fetch is retained locally under access control; the licence is recorded, and a source whose text could not be fetched keeps metadata and abstract only.
- Representative internal laboratory data is provided in a structured form with permission to process. If unavailable, reproducible fixtures stand in and the demonstration states that no unverified scientific validity is claimed.
- The three illustrative papers are constructed fixtures, written so that each contains exactly its role's finding (proliferation, non-avian lipid accumulation, failed culture) in realistic scientific prose with methods, results and a results table. The evaluation report states that the three-paper case ran on fixtures. Real papers are exercised through ingestion and the evaluation dataset.
- Reviewer interaction needs only a minimal interface sufficient for claim and source review, candidate comparison and design approval; polish is out of scope.
- Automated reasoning steps are used inside bounded, explicit stages, never as an open-ended autonomous search loop, and are never the sole validator of their own output.
- Recorded model responses are an accepted proof of a scenario in the pull-request tier only; the pre-release tier re-proves every scenario live before a demonstration.
- Quality thresholds for model-dependent stages are set with customer reviewers after the baseline is measured; this specification sets none.
- Access control for internal material is at the level of the POC installation (who can run it and read its store), not multi-tenant.

## Out of Scope

- Autonomous wet-lab execution or unrestricted protocol optimisation.
- Exhaustive ingestion of all relevant literature.
- Replacing scientific expert judgement or regulatory review.
- Claiming commercial manufacturing feasibility from an exploratory experiment.
- Infrastructure beyond one installation, and a separate graph store as a dependency of the POC: links between records are sufficient, with a graph projection a later extension.
- Validating generated scientific reasoning with another unevaluated automated judge.
- Incremental impact analysis beyond flagging dependents, reviewer calibration across model releases, laboratory scheduling and inventory integration, multi-tenant permissions, and longitudinal outcome evaluation (listed as future extensions).

## Dependencies and Risks

- Availability of a customer scientific reviewer for claims, constraints, labels and approval.
- Access to representative internal experimental data with permission to process.
- Rights and technical access to adequate external literature and full text.
- Ability to parse scientific tables, methods and supplementary material accurately; uncertain parses must be flagged, not guessed.
- Agreement on a realistic definition of success for six weeks: learning and evaluation, not proof of manufacturing viability.
- Model and review budget, and operational security appropriate to client materials.
- Open decisions for the customer that shape the reference scenario but not this specification's structure: the intended meaning of "make foie gras"; confirmable species, cell types and lab capabilities; mandatory versus exploratory target properties; available internal records and their format; usable sources and licences; who labels and signs off; acceptable budget, latency and quality thresholds; and the criteria separating a valid feasibility recommendation from an unsupported production claim. These are handled by the facet and question stages as unresolved items until confirmed.
