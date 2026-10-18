# Contract: Workflows, steps, agents, prompts, rule sets and adapters

The component inventory `Main.scala` registers, and what each stage promises. `stageVersion` changes
whenever a stage's prompt, rules or code change behaviour. Prompts live under
`src/main/resources/prompts/<stage>/<version>.md` and are never edited in place.

## Inventory

| Kind | Components |
|---|---|
| Event sourced entities | `request`, `facet`, `question`, `search-plan`, `search-execution`, `source`, `finding`, `claim`, `assessment`, `sufficiency`, `gap`, `package`, `approach`, `decision`, `design`, `stage-run` |
| Views | `request-rows`, `facet-rows`, `question-rows`, `source-rows`, `finding-rows`, `claim-rows`, `assessment-rows`, `execution-rows`, `design-rows`, `stage-rows` |
| Keyed views | `chunk-rows`, `measurement-rows`, `dependents` |
| Consumers | `verification-trigger` (over `finding`), `verification-trigger-claims` (over `claim`), `impact-trigger-sources`, `impact-trigger-findings`, `impact-trigger-claims` |
| Workflows | `ingestion`, `verification`, `investigation`, `question-investigation`, `impact` |
| Agents | `request-agent`, `search-agent`, `evidence-agent`, `design-agent` (plus `AgentRuntime.descriptors`) |
| Endpoints | `ApiEndpoint`, `ReviewEndpoint`, `HealthEndpoint` |
| Extensions | `ProjectionRuntime()`, `AgentRuntime.withDefaultModel(provider)`, `HttpServer` |

No timed action: no deadline exists in the POC.

## Stages

| Stage (workflow.step) | Inputs | Model call | Output | Gate after | Retry |
|---|---|---|---|---|---|
| `investigation.extract-facets` | request text | `request-agent.extract-facets`, effort medium, `FacetSet` | `facet` entities (proposed), `request.record-facet-set` | codec; every explicit facet has a span that resolves into the request text | `schema_invalid` ×2 |
| `investigation.round-trip` | request text, facets | `request-agent.round-trip`, separate prompt, `RoundTripCheck` | `request.record-round-trip` | none; a failing check is shown to the reviewer, nothing regenerates | none |
| `investigation.await-facet-review` | | no | pause | `facets-confirmed` command: every mandatory facet confirmed, assumed or unresolved-and-flagged | |
| `investigation.derive-questions` | confirmed facets | `request-agent.derive-questions`, effort medium, `QuestionSet` | `question` entities, `request.record-questions` | coverage rule: every mandatory facet investigated or flagged uninvestigated | `schema_invalid`, `coverage_gap` ×2 |
| `question-investigation.plan` | question | `search-agent.plan`, haiku, effort low, `SearchPlan` | `search-plan` | at least one lexical and one semantic query | `schema_invalid` ×2 |
| `question-investigation.execute` | plan | no | `search-execution` per query and source (local: lexical via the view's declared query, semantic in-service; adapters over HTTP), with the snapshot | | adapter transient: step retries ×2 |
| `question-investigation.eligibility` | executions | no | `EligibilityApplied` with exclusions and reasons, after ranking | `eligibility@v1` | none |
| `question-investigation.resolve` | eligible hits | no | context per chunk from `source.context` | | none |
| `question-investigation.assess` | question, verified findings and claims among the hits | `evidence-agent.assess`, effort high, `AssessmentSet` | `assessment` entities | `applicability@v1` | `schema_invalid` ×2 |
| `question-investigation.sufficiency` | question, assessments, gaps, executions | `evidence-agent.judge-sufficiency`, effort high, `Sufficiency` (one call per configured decision) | `sufficiency` entities, `gap` entities | status cites assessments; coverage judgement present; FR-025 (b): a record that is the only affirmative evidence is sent `requires-review` and the judgement is `requires_expert_review` | `schema_invalid` ×2 |
| `question-investigation.supplemental` | an open coverage gap | no | an execution linked to the gap | the gap's budget (default 2) | none |
| `ingestion.fetch` | adapter, identifier | no | a `SourceRecord` | | transient ×2, then `record-failure` |
| `ingestion.record` | the record | no | `source.register`, `source.record-content`; `Duplicate` ends the workflow | duplicate by identifier and hash; licence recorded | none |
| `ingestion.parse` | content | no | `source.record-parse` | completeness flag; unresolved tables named | none |
| `ingestion.chunk` | sections | no (embedder local) | `source.record-chunks` in batches | offsets resolve | none |
| `ingestion.measurements` | internal rows | no | `source.record-measurements` | unit table; nothing converted | none |
| `ingestion.eligibility` | source version | no | `source.assess-eligibility` | `eligibility@v1` | none |
| `ingestion.extract` | sections, optional questions | `evidence-agent.extract`, effort high, one `ExtractionSet` call; each record quotes its sections verbatim by section key | `finding` and `claim` entities (proposed, with missing-value statuses and `generatedBy`) | skipped without a model call when `source` already records an extraction for this version and prompt version; `deterministic@v1` on each; a quotation not found in its section is `span_unresolved` | `schema_invalid`, `span_unresolved` ×2; on exhaustion the drafts are kept as quarantined findings |
| `verification.validate` | the proposed record | no | `Validated` | `deterministic@v1`; failure quarantines before any model call | none |
| `verification.verify` | the record, the cited sections and related sections (text from `source`) | `evidence-agent.verify`, separate prompt, effort high, `ClaimVerification` | `Verified`, `Rejected`, `RequiresReview` | a failed check forbids `entailed`; critical defects quarantine; FR-025 (a) and (c) routing | none: a failure is a result |
| `verification.decide-retry` | the result | no | `repropose` or `quarantine` | the retry budget on the entity | |
| `investigation.build-packages` | questions, assessments, spans, facets, constraints | no | `package` entities | `package@v1` | none |
| `investigation.candidates` | packages | `design-agent.candidates`, effort high, `ApproachSet` (min 2) | `approach` entities and the comparison | no probability field; every assumption labelled; output guardrail `no-probability` | `schema_invalid`, `fewer_than_two` ×2 |
| `investigation.await-decision` | | no | pause | `decide` command (reviewer) | |
| `investigation.design` | decision, selected approach, packages | `design-agent.design`, effort high, `ExperimentalDesign` | `design.DraftGenerated` | `design@v1` | `schema_invalid`, `unverified_claim_ref` ×2 |
| `investigation.await-approval` | | no | pause | `approval` command (reviewer); `changes_requested` → `design` | |
| `impact.walk` | changed ref | no | `flag-for-review` on every dependent | | the step is safe to repeat |

Every step first sends `StageStarted` to the run's `stage-run` entity with `(stage, stageVersion,
inputHash)`; `AlreadyDone` skips the step's work and transitions on.

## Agent handlers

| Agent | Handler (wire name) | Reply type | Memory | Model | Guardrails |
|---|---|---|---|---|---|
| `request-agent` | `extract-facets` | `FacetSet` | none | default, effort medium | input length |
| `request-agent` | `round-trip` | `RoundTripCheck` | none | default, effort medium | |
| `request-agent` | `derive-questions` | `QuestionSet` | none | default, effort medium | |
| `search-agent` | `plan` | `SearchPlan` | none | `claude-haiku-5-5`, effort low | |
| `evidence-agent` | `extract` | `ExtractionSet` | none | default, effort high | |
| `evidence-agent` | `verify` | `ClaimVerification` | none | default, effort high | |
| `evidence-agent` | `assess` | `AssessmentSet` | none | default, effort high | |
| `evidence-agent` | `judge-sufficiency` | `Sufficiency` | none | default, effort high | |
| `design-agent` | `candidates` | `ApproachSet` | none | default, effort high | output `no-probability` (refuses `\d+\s?% (chance|probability|likelihood)`) |
| `design-agent` | `design` | `ExperimentalDesign` | none | default, effort high | output `no-probability` |

Rules every handler follows:

- The system message's first line is `# prompt_version: <stage>@<version>`; the user message's first
  line is `# case: <marker>` (the request id, the source id and version, or the question id). The
  replay provider keys cassettes on both.
- Source text goes in `withContext`, headed `[S<n>] <title>` per section, never in the user message.
- The reply shape is stated in the system message; `thenReplyAs[T]` decodes it or fails naming the type
  (`schema_invalid`).
- Session id = the case marker; with `MemoryProvider.none` nothing is read or written, and the id only
  serialises requests about one case.
- A refusal (`Forbidden`) is recorded as defect `model_refused`, not retryable, routed to review.
- Steps call agents with `invokeAsync` and `ComponentClient.await(_, 3.minutes)` under a 3-minute step
  timeout.

## Model providers and cassettes

| Provider | Chosen by | Behaviour |
|---|---|---|
| `AnthropicProvider.fromEnv("claude-opus-5-5")` | `EDP_TIER=live` | the model; `EDP_RECORD=1` wraps it in `RecordingModelProvider`, which writes `evals/cassettes/<stage>@<version>/<case>.json` with `"recorded": true` |
| `ReplayModelProvider(dir)` | `EDP_TIER=replay` (default) | answers from the cassette for `(prompt_version, case)`; a missing cassette fails the call naming the key; a cassette may name `"refusal"` to script one |
| `TestModelProvider` | integration suites | scripted in the test |

A cassette: `{ "prompt_version": "...", "case": "...", "reply": "<text>", "recorded": true|false,
"authored_by": "...", "checked_quotations": true }`. Authored cassettes are written by
`scripts/cassettes/` helpers that check each quotation against the parsed fixture before writing.

## Rule sets

| Rule set | Rules |
|---|---|
| `deterministic@v1` | codec; source version exists; every span resolves; required metadata; units valid; status in enum; no extractor-set `verified`; hypothesis never `verified` |
| `eligibility@v1` | retracted blocks; superseded blocks unless the successor is reviewed; parse failed blocks; duplicate version downgrades; licence is recorded and never a gate |
| `applicability@v1` | species mismatch forbids direct; endpoint mismatch forbids direct; unknown species sets applicability unknown |
| `high-impact@v1` | ambiguous or insufficiently supported; the only affirmative evidence for a question or mandatory facet; a system hypothesis → requires review |
| `package@v1` | spans resolve; qualifiers present; same-study deduplication; contradictions included; mandatory facets covered; eligibility and verification enforced; no critical trim |
| `design@v1` | twelve sections present; claims verified; spans resolve; mandatory facets accounted for; transition legal |

Each rule has an id and a version recorded on the `ValidationResult`. `scripts/mutants/` holds the
three patches that disable `eligibility@v1`'s retraction rule, `applicability@v1`'s species rule and
the span check in `deterministic@v1` (SC-013).

## Source adapters

| Adapter | Discovery | Full text | Status and licence | Rate limit |
|---|---|---|---|---|
| `europepmc` | REST `search`, `resultType=core` | `fullTextXML` by PMCID (JATS) | `isOpenAccess`, `license`, retraction via `pubType` | polite |
| `pubmed` | E-utilities `esearch`, `efetch` (XML) | none; abstract only unless Europe PMC has the text | publication types for retraction notices | 3/s without a key |
| `crossref` | REST `works?query=` | none | `license[]`, `update-to[]` (retraction, correction) | polite pool with `mailto` |
| `file` | a local JATS XML or text file | the file | `<name>.meta.json` | none |
| `internal` | `manifest.json` naming CSV files | rows become records and measurements | the manifest | none |

Each adapter implements `search(query, filters): Seq[SourceRecordRef]` and `fetch(identifier):
SourceRecord`, and fails with `AdapterTransient` (429, 5xx, timeout) or `AdapterPermanent`. A
`SourceRecord` carries identifiers, metadata, licence, status, the raw content or the abstract, and
the content hash.

## Configuration (names, never values)

| Variable | Meaning | Default |
|---|---|---|
| `EDP_API_TOKEN` | the shared bearer token | required |
| `EDP_REVIEWERS` / `EDP_REVIEWERS_FILE` | the configured reviewer list | required (one or the other) |
| `EDP_TIER` | `replay` or `live` | `replay` |
| `EDP_RECORD` | `1` records cassettes in the live tier | unset |
| `EDP_CASSETTES` | the cassette directory | `evals/cassettes` |
| `ANTHROPIC_API_KEY` | read by the provider in the live tier | |
| `EDP_MODEL` | the default model | `claude-opus-5-5` |
| `EDP_EMBEDDING_MODEL` / `EDP_EMBEDDING_CACHE` | `BAAI/bge-small-en-v1.5` or `hash`; the download cache | the model; `~/.cache/edp` |
| `EDP_RETRY_BUDGET`, `EDP_SUPPLEMENTAL_BUDGET` | the bounds | 2, 2 |
| `EDP_DECISIONS` | the decisions sufficiency is judged for | `recommend production feasibility;propose a feasibility experiment on lipid accumulation` |
| `EDP_INTERNAL_DIR` | where internal material is read from (ignored by git) | `internal` |
| `EDP_CONTACT_EMAIL` | the contact the external adapters send: Europe PMC's and Crossref's `mailto`, NCBI's `email`; unset sends none | unset |
| `EDP_NCBI_API_KEY` | optional; raises PubMed from three to ten requests a second | unset |
| `ANKKA_DB_*` | the database, when not provisioned by the platform | from `docker-compose.yml` locally |
