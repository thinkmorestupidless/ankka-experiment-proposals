---

description: "Task list for the Evidence-Driven Scientific Experimental Design POC on ankka"
---

# Tasks: Evidence-Driven Scientific Experimental Design POC

**Input**: Design documents from `/specs/001-evidence-experiment-design/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (http-api.md, stages.md, features-and-tiers.md), quickstart.md

**Tests**: Included. The specification makes the test suite a deliverable (FR-047 to FR-054, SC-005, SC-006, SC-013). The acceptance scenarios live only in the Gherkin features under `features/`, so each story's phase ends with the step definitions that run its feature file through the Gherkin suite in the replay tier (and, unchanged, in the live tier). Integration tests cover behaviours the features do not express (resumption after a restart, refusals, traceability queries); the independent suite is written apart from the prompts; fault injection and the labelled evaluation run complete the tiers.

**Organization**: Phases 1 and 2 build the shared skeleton: the domain base, the `source` entity with fixture ingestion, the agents' scaffolding and replay provider, the API skeleton, the integration and feature harnesses. Phases 3 to 12 are one user story each in spec priority order (US1 to US4 are P1, US5 to US8 are P2, US9 and US10 are P3). Phase 13 is polish. Every entity is created with its `flag-for-review` command (data-model.md, R-flag) in the phase that creates it, so US10 adds only the view, the consumers and the workflow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1 to US10)
- Every task names the file or files it creates or changes

## Path Conventions

One ankka Scala service per plan.md. `S` = `src/main/scala/com/thinkmorestupidless/experimentproposals`, `T` = `src/test/scala/com/thinkmorestupidless/experimentproposals`, `R` = `src/main/resources`. Living features in `features/<area>/*.feature` with step definitions in `T/features/steps/`; fixtures, cassettes, cases and reports in `evals/`; prompts in `R/prompts/<stage>/<version>.md`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Build, formatting, configuration and the tree the plan names.

- [X] T001 Extend `build.sbt`: add `ankka-agent` and `ankka-telemetry-otlp` beside the template's ankka lines, `scala-xml`, `com.microsoft.onnxruntime:onnxruntime` and `ai.djl.huggingface:tokenizers`; serialise tests (`Tags.limit(Tags.Test, 1)`, `Test / parallelExecution := false`, `Test / fork := true`); `-Wunused` and `-Werror` in `scalacOptions`; pass `EDP_*` through `Test / envVars`; keep the `schema` task and image packaging
- [X] T002 [P] Copy `.scalafmt.conf` and `.githooks/pre-commit` from `../ankka`, add `sbt-scalafmt` to `project/plugins.sbt`, and write `Justfile` with one-command recipes: `hooks`, `run`, `test-unit`, `test-integration`, `independent`, `features-replay`, `features-live`, `features-record`, `eval`, `eval-compare`, `check-bdd`, `mutants`, `fmt`, `pr`
- [X] T003 [P] Write `.env.example` (every `EDP_*` name from contracts/stages.md, `EDP_TIER=replay`, no values for secrets), `evals/reviewers.example.json` (two entries, `demo-reviewer` with `standIn: true`), and extend `.gitignore` with `.env`, `reviewers.json`, `internal/*` (keep `internal/.gitkeep`), `evals/reports/*` (keep `.gitkeep`), `.bsp/`, `.metals/`
- [X] T004 [P] Create the tree with `.gitkeep` files: `evals/{fixtures/papers,fixtures/internal,cases,passages,cassettes,reports}`, `scripts/{cassettes,mutants}`, `internal/`, `docs/`, `notes/`, `R/prompts/`
- [X] T005 [P] Rewrite `README.md` for this service (what it is, the flow, links to the spec directory, `quickstart.md`, `features/`, `GLOSSARY.md`, `docs/limitations.md`) and set `service.json` to name the `EDP_*` environment variables and `ANTHROPIC_API_KEY` (names only) with `minInstances: 1`
- [X] T006 [P] Write `R/application.conf` (the service name; ask timeout left default; the model timeout is set in code, T031) and `R/logback.xml` with a pattern carrying MDC keys `runId`, `stage`, `record`
- [X] T007 [P] Write `docs/limitations.md` (PDF sources are abstract-only; no pgvector, semantic retrieval is in-service; judgments not used; no graph projection; Markdown rendering only; one installation) and `notes/ankka-requests.md` (empty table: date, what, what would remove it)
- [X] T008 Update `.github/workflows/ci.yml`: JDK 21, `just check-bdd` with `uvx`, `just test-unit`, `just test-integration` (Docker), `just features-replay`, `just independent`; path filters that include `features/`, `evals/`, `GLOSSARY.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The domain base, provenance, reviewers, the agent scaffolding and replay provider, the `source` entity with fixture ingestion, the API skeleton with its ACLs, registration, and the integration and feature harnesses that every story's tests use.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Domain base and provenance

- [X] T009 Write `S/domain/provenance/Refs.scala`: `Ref(kind, id, version)`, `RecordKind` enumeration, `ReviewAction`, `ReviewFlag(changedRef, reason, at)`, `Defect(kind, severity, detail, retryable)` with its closed `DefectKind`, `ValidationResult(gate, passed, defects, ruleVersions, runAt)`, `ModelCall`, `StageExecution` and `StageStatus`, each with `Codecs.serializer` under its manifest
- [X] T010 [P] Write `S/domain/Enumerations.scala`: every closed list in data-model.md (facet origin, importance, facet status, missing-value status, question status, source status, retention restriction, parse completeness, section kind, parse confidence, outcome direction, record status, claim kind, verification label, check name, gate, relationship, applicability, dimension match, sufficiency status, coverage judgement, escalation, gap kind, gap status, package quality, approach status, design status, approval decision, review action) as Scala 3 enums with their wire strings
- [X] T011 [P] Write `S/domain/provenance/Reviewers.scala`: `Reviewer(name, role, standIn)`, `Reviewers.load(env)` from `EDP_REVIEWERS` or `EDP_REVIEWERS_FILE`, `check(name): Either[String, Reviewer]`; unit test `T/unit/ReviewersSuite.scala` (listed, unlisted, stand-in, malformed entry fails at load)
- [X] T012 [P] Write `S/rules/Rules.scala`: `Rule(id, version)`, `RuleOutcome`, `RuleSet(name, version, rules)`, `Gate` naming, and `S/rules/Deterministic.scala` with the `deterministic@v1` checks that do not need a record type yet (enum membership, required metadata, unit table in `S/rules/Units.scala`); unit test `T/unit/UnitsSuite.scala`
- [X] T013 Write `S/application/entities/StageRunEntity.scala` (`stage-run`): events `StageStarted`, `StageCompleted`, `StageFailed`; `start` replies `AlreadyDone(outputRef)` with no event for a completed `(stage, stageVersion, inputHash)`; commands `start`, `complete`, `fail`; query `get`; unit test `T/unit/StageRunEntitySuite.scala`

### Agent scaffolding, replay and tiers

- [X] T014 Write `S/config/Settings.scala`: every `EDP_*` variable with its default (contracts/stages.md), `Tier` enumeration, `decisions` list parsing; unit test `T/unit/SettingsSuite.scala`
- [X] T015 [P] Write `S/application/agents/Prompts.scala`: loads `R/prompts/<stage>/<version>.md`, asserts the first line is `# prompt_version: <stage>@<version>`, builds the user message with `# case: <marker>` first, and `S/application/agents/Sections.scala` rendering sections as `[S<n>] <title>` blocks for `withContext`
- [X] T016 [P] Write `S/replay/Cassettes.scala` (the cassette file shape, `key(promptVersion, caseMarker)`, read and write under `EDP_CASSETTES`) and `S/replay/ReplayModelProvider.scala` implementing `ModelProvider`: `complete` answers from the cassette or fails naming the key; a cassette with `refusal` answers a refusal; `stream` emits the reply whole; unit test `T/unit/ReplayModelProviderSuite.scala`
- [X] T017 [P] Write `S/replay/RecordingModelProvider.scala` wrapping any `ModelProvider` and writing a cassette per `complete` with `recorded: true`, and `S/replay/ModelProviders.scala`: `forTier(settings)` returning replay, live or live-with-recording
- [X] T018 Write `S/application/StageCall.scala`: the helper every model step uses: `start` the stage on `stage-run`, skip on `AlreadyDone`, `invokeAsync` the agent handler and `ComponentClient.await(_, 3.minutes)`, read usage, map a `Forbidden` to defect `model_refused`, a codec failure to `schema_invalid`, record `complete` or `fail`; integration-tested through the first workflow (T045)

### The source entity and fixture ingestion

- [X] T019 Write `S/domain/sources/Sources.scala`: `SourceDocument`, `SourceVersion`, `Section`, `Chunk`, `Measurement`, `SourceSpan` (with whitespace-tolerant `resolve(sectionText)`), `Eligibility`, `SourceRecord`, `ParseIssue`, `InternalDetails`, and their serializers; unit test `T/unit/SourceSpanSuite.scala` (resolves, offsets off by one fails, whitespace tolerance)
- [X] T020 Write `S/application/entities/SourceEntity.scala` (`source`): events and commands from data-model.md (`register`, `record-content` replying `Duplicate` with no event on a known hash, `record-parse`, `record-chunks`, `record-measurements`, `record-extraction` (idempotent per prompt version), `quarantine`, `change-status(by)` with the reviewer or `operator` check, `assess-eligibility`), queries `get`, `section`, `resolve-span`, `context`; unit test `T/unit/SourceEntitySuite.scala` (duplicate by hash, a second version keeps the first's sections resolvable, status change event, quarantine)
- [X] T021 [P] Write `S/adapters/Adapter.scala` (`search`, `fetch`, `AdapterTransient`, `AdapterPermanent`, `Adapters.byName`) and `S/adapters/FileAdapter.scala` reading a JATS XML or text file with its `<name>.meta.json` (identifiers, licence, status, kind, abstract); unit test `T/unit/FileAdapterSuite.scala`
- [X] T022 [P] Write `S/adapters/jats/JatsParser.scala`: sections with parent chain and ordinals, kinds from `sec-type` or titles, tables as raw text with `tableResolved`, figure captions, methods-to-results `relatedSectionIds` from `xref` or heading order, parse issues for anything unresolved, canonical text with stable offsets; unit test `T/unit/JatsParserSuite.scala` against `evals/fixtures/papers/paper-a.xml`
- [X] T023 [P] Write `S/retrieval/Chunker.scala` (about 400 whitespace-delimited words as the token count, 50 overlap, sentence boundaries, never across sections, offsets into section text, batches of at most 200) and `S/retrieval/Embedder.scala` with `HashEmbedder` (deterministic, 384 dims) and `OnnxEmbedder` (bge-small-en-v1.5 through ONNX Runtime and the DJL tokenizer, cache under `EDP_EMBEDDING_CACHE`, loaded lazily) selected by `EDP_EMBEDDING_MODEL`; unit test `T/unit/ChunkerSuite.scala` (offsets resolve, no chunk crosses a section)
- [X] T024 Write `S/application/views/ChunkRows.scala`: keyed view over `source` events, one row per chunk from `Chunked`, row `ChunkRow(chunkId, sourceId, sourceVersion, sectionId, ordinal, start, end, text, embeddingModel, embeddingRevision, embedding)`, declared queries `lexical` (full-text search ranked by `ts_rank_cd`, top 20; R20 item 1 verified here, with the `ILIKE` fallback if refused), `by-source`, `by-model`; unit test `T/unit/ChunkRowsSuite.scala` with `KeyedViewTestKit`
- [X] T025 [P] Write `S/rules/Eligibility.scala` (`eligibility@v1`: retracted blocks, superseded blocks unless successor reviewed, parse failed blocks, duplicate version downgrades, licence recorded never a gate); unit test `T/unit/EligibilitySuite.scala`
- [X] T026 Write `S/application/workflows/IngestionWorkflow.scala` (`ingestion`, id `ingest:<adapter>:<identifier>`): state, `start` command (refuses a second start with `Conflict`), steps `fetch` (adapter), `record` (register, record-content; `Duplicate` records it in state and ends), `parse`, `chunk` (batches), `measurements` (internal kinds; no-op otherwise), `eligibility`, then `end` (the `extract` and `await-verification` steps come in US3); settings: `fetch` retries twice then fails over to `record-failure`, which records the failure in state and pauses; every step goes through `stage-run`
- [X] T027 [P] Author the fixtures: `evals/fixtures/papers/paper-a.xml` (avian hepatic cells proliferated; methods, results, a results table), `paper-b.xml` (lipid accumulation in a non-avian species, species named throughout), `paper-c.xml` (a culture method was unsuccessful; "did not increase"), `retracted.xml` (any topic, `status: retracted` in its meta), each with `<name>.meta.json` (identifiers, licence, status, kind `literature`); `evals/fixtures/internal/manifest.json` and `measurements.csv` (lipid content measurements with units, one row with an unrecognised unit, responsible researcher, protocol version)
- [X] T028 [P] Write `S/application/views/SourceRows.scala` (identifiers, title, kind, status, latest version, eligibility, quarantined; queries `by-identifier`, `by-status`, `by-kind`) and `S/application/views/StageRows.scala` (`by-workflow`, `failed`)

### The edge, registration and harnesses

- [X] T029 Write `S/api/Auth.scala`: `Acl.Authenticate` admitting `Authorization: Bearer <EDP_API_TOKEN>` or the `edp_session` cookie (R20 item 6; the query-parameter fallback if the cookie cannot be read) as principal `operator`, else `Unauthenticated`; `S/api/HealthEndpoint.scala` (`/healthz`, `Acl.AllowAll`, `{status, tier, model}`); unit-level test through the integration kit in T031
- [X] T030 Write `S/api/ApiEndpoint.scala` (`/api/v1`) with the codecs file `S/api/Json.scala` and the first routes: `GET /reviewers`, `GET /runs/{runId}`, `GET /workflows/{kind}/{id}` (lifecycle plus state), `POST /sources:ingest` (starts `ingestion`, `202`), `GET /sources/{id}`, `GET /sources/{id}/sections/{sectionId}`, `GET /spans:resolve`; a reviewer field that is not configured is refused `400 unknown_reviewer` before any call
- [X] T031 Replace the template: delete `S/domain/Item.scala`, `S/application/ItemEntity.scala`, `S/application/ItemRows.scala`, `S/api/ItemEndpoint.scala`, `T/ItemEntitySuite.scala`, `T/ItemHttpSuite.scala`, `T/ItemIntegrationSuite.scala`; write `S/Main.scala` registering every component built so far plus `AgentRuntime.descriptors`, `ProjectionRuntime()`, `AgentRuntime.withDefaultModel(ModelProviders.forTier(settings), modelTimeout = 3.minutes)`, `HttpServer.of(...)` with the three endpoints; `sbt compile` warning-free
- [X] T032 Write `T/integration/ServiceFixture.scala`: starts `AnkkaTestKit` with every descriptor `Main` registers, a `TestModelProvider` the test scripts, `HttpServer.at("127.0.0.1", 0)`, a small HTTP client with the bearer token, `ingestFixture(name)` helper, `eventually` helpers; `T/integration/IngestionSuite.scala`: ingest paper A through `POST /sources:ingest` (file adapter), sections and chunk rows appear, `restartService()`, still there; a second ingest is a duplicate with no new version
- [X] T033 Write `T/features/FeatureSuite.scala` (`GherkinSuite("features") with LogCapturing`; `beforeAll` starts the service as `ServiceFixture` does but with `ModelProviders.forTier` from `EDP_TIER`; `afterAll` stops it) and `T/features/steps/Common.scala` (ids from `scenarioId`, the HTTP client, `ingest the fixtures`, waiting for a workflow's lifecycle, retry on a changing value, the drift file writer for the live tier); run `just features-replay` and confirm the suite fails only on undefined steps, naming them
- [X] T034 Write `scripts/cassettes/README.md` and `S/replay/CassetteAuthor.scala` (a `@main` that writes an authored cassette for a stage, case and reply file after checking every quotation in the reply against the parsed fixture; `recorded: false`, `authored_by`); `Justfile` recipe `cassette`

**Checkpoint**: Foundation ready; `just test-unit`, `just test-integration` and `just check-bdd` pass; the feature suite runs and reports undefined steps only.

---

## Phase 3: User Story 1 - Interpret a customer request into reviewable requirement facets (Priority: P1) 🎯 MVP

**Goal**: A request is stored unchanged; facets with origins, importance and status are proposed; the round-trip check reports omissions, additions, contradictions and ambiguities; a reviewer confirms, amends, rejects or leaves facets unresolved with history kept.

**Independent Test**: Enter "Can you make foie gras?" and verify the stored text is byte-identical, the eight reference facets carry the right origins, and a confirmation records the reviewer and keeps the prior state visible (features/request/facets.feature).

- [X] T035 [P] [US1] Write `S/domain/request/Request.scala`: `CustomerRequest`, `RoundTripCheck`, `Issue`, request events; `S/domain/request/Facet.scala`: `Facet`, `FacetProposal`, `FacetSet` (the agent's reply type with its `JsonValueCodec`), facet events, the transition table `FacetTransitions.allowed(from, action)`
- [X] T036 [US1] Write `S/application/entities/RequestEntity.scala` (`request`): `submit` (second submit `Conflict`), `record-facet-set`, `record-round-trip`, `confirm-facets(by, facetStates)` refusing while a mandatory facet is `proposed`, `record-questions`, `advance`; queries `get`, `round-trip`; unit test `T/unit/RequestEntitySuite.scala` (text unchanged across events, confirm refused with a proposed mandatory facet)
- [X] T037 [US1] Write `S/application/entities/FacetEntity.scala` (`facet`): `propose`, `review(action, by, rationale, statement?)` with the reviewer check and the transition table, `assume(value, by, rationale)`, `flag-for-review`; history kept in state; unit test `T/unit/FacetEntitySuite.scala` (confirm sets `human_confirmed` and keeps the prior origin; unlisted reviewer refused `BadRequest` with no event; illegal transition `Conflict`; a second flag for the same ref persists nothing)
- [X] T038 [P] [US1] Write `S/rules/Facets.scala`: the facet gate (every `explicit` facet has a span that resolves into the request text; mandatory set present) and the round-trip pass rule (passes only when all four lists are empty); unit test `T/unit/FacetRulesSuite.scala`
- [X] T039 [P] [US1] Write `R/prompts/facets/v1.md` and `R/prompts/roundtrip/v1.md` (the reply JSON shape stated; origins and importance defined; the round-trip prompt is given the facets alone, never the model's own extraction prose) and `S/application/agents/RequestAgent.scala` (`request-agent`) with handlers `extract-facets` → `FacetSet` and `round-trip` → `RoundTripCheck`, `MemoryProvider.none`, effort medium, request text in `withContext`
- [X] T040 [US1] Write `S/application/workflows/InvestigationWorkflow.scala` (`investigation`, id = request id): state, `start`, steps `extract-facets` (through `StageCall`; writes `facet` entities with derived ids and `request.record-facet-set`; retries `schema_invalid` within `EDP_RETRY_BUDGET` in state), `round-trip`, `await-facet-review` (`thenPause()`, no timeout), command `facets-confirmed(by)` (reads the facets, calls `request.confirm-facets`, transitions to `derive-questions`, a stub that ends until US2); settings with 3-minute step timeouts for model steps
- [X] T041 [P] [US1] Write `S/application/views/RequestRows.scala` and `S/application/views/FacetRows.scala` (`by-request` declared query)
- [X] T042 [US1] Add to `S/api/ApiEndpoint.scala`: `POST /requests` (submit, start the workflow, `201`), `GET /requests/{id}` (request, facets from the entities, round-trip, workflow lifecycle), `GET /requests/{id}/facets`, `POST /facets/{id}/review`, `POST /facets/{id}/assume`, `POST /requests/{id}/facets:confirmed`
- [X] T043 [P] [US1] Write `S/api/ReviewEndpoint.scala` (`/review`, the cookie ACL from `Auth`): `GET /review/session?token=`, `GET /review/requests/{id}/facets` rendering each facet with origin, importance, status, history and the round-trip issues, with forms posting to the review routes; `S/api/pages/Layout.scala` and `S/api/pages/FacetsPage.scala` as pure functions returning `Html`
- [X] T044 [P] [US1] Author cassettes `evals/cassettes/facets@v1/reference.json` (the eight reference facets with the source document's origins and statuses, spans for the explicit product facet) and `evals/cassettes/roundtrip@v1/reference.json` (passes), plus `evals/cassettes/facets@v1/unsupported-addition.json`, `omission.json` and `roundtrip@v1/similar-fails.json` for the feature's other scenarios, through `CassetteAuthor`
- [X] T045 [US1] Write `T/integration/InvestigationFacetsSuite.scala`: with `TestModelProvider`, a submit runs `extract-facets` and `round-trip` and pauses; `restartService()` while paused, the workflow is still `Paused` and the facets still there; confirming a mandatory facet as an unlisted reviewer is `400`; confirming all resumes the workflow; a `schema_invalid` reply is retried twice then the run records `failed` and the workflow pauses at `record-failure`
- [X] T046 [US1] Write `T/features/steps/RequestSteps.scala` for `features/request/facets.feature` (six scenarios) using glossary words only; `just features-replay` passes the six and `just check-bdd` reports no findings

**Checkpoint**: US1 is demonstrable: steps 1 and 2 of the walkthrough.

---

## Phase 4: User Story 2 - Derive scientific questions with evidence acceptance criteria (Priority: P1)

**Goal**: Confirmed facets yield explicit questions linked to facets, with evidence needed, mandatory answer fields, sufficiency criteria, a status and missing information; every mandatory facet is investigated or flagged.

**Independent Test**: With the reference facets confirmed, the six reference questions are produced with their links and criteria; a question with no eligible evidence reads `unresolved` with its missing information (features/request/questions.feature).

- [X] T047 [P] [US2] Write `S/domain/request/Question.scala`: `ScientificQuestion`, `QuestionSet` (reply type and codec), question events, `QuestionTransitions`
- [X] T048 [US2] Write `S/application/entities/QuestionEntity.scala` (`question`): `derive`, `record-plan`, `advance(status, byRef, by?)` with the `answered` rule (a sufficiency ref with the right status and a reviewer), `flag-for-review`; unit test `T/unit/QuestionEntitySuite.scala`
- [X] T049 [P] [US2] Write `R/prompts/questions/v1.md` and add handler `derive-questions` → `QuestionSet` to `S/application/agents/RequestAgent.scala`; write `S/rules/Questions.scala` (coverage rule: every mandatory facet investigated by at least one question or listed as uninvestigated); unit test `T/unit/QuestionRulesSuite.scala`
- [X] T050 [US2] Extend `S/application/workflows/InvestigationWorkflow.scala`: step `derive-questions` (through `StageCall`; `question` entities with ids `<request>:q<n>`, `request.record-questions`; `coverage_gap` is a retryable defect) then `investigate-questions` (starts one `question-investigation` per question with `invokeAsync` and awaits each with `awaitEnd`; until US4 the `question-investigation` workflow is a stub that ends at once, registered in `Main`)
- [X] T051 [P] [US2] Write `S/application/views/QuestionRows.scala` (`by-request`) and add `GET /questions/{id}` and the questions to `GET /requests/{id}` in `S/api/ApiEndpoint.scala`
- [X] T052 [P] [US2] Author `evals/cassettes/questions@v1/reference.json` (the six reference questions, each with facet links, evidence needed, answer fields, sufficiency criteria; the lipid accumulation question requires species, cell type, endpoint, measurement, observed outcome and limitations)
- [X] T053 [US2] Write `T/integration/QuestionsSuite.scala` (derivation after confirmation; coverage rule retry; a question's status reads `unresolved` with missing information before any evidence) and `T/features/steps/QuestionSteps.scala` for `features/request/questions.feature`; `just features-replay` passes eight scenarios

**Checkpoint**: Walkthrough step 3.

---

## Phase 5: User Story 3 - Extract source-grounded findings and claims and verify them against the source (Priority: P1)

**Goal**: Findings, author statements, normalised claims and hypotheses are extracted as distinct kinds with resolvable spans; a separate verification labels each against the full source context; only verified claims count; retries are classified and bounded; rejected and uncertain records are kept.

**Independent Test**: Ingest Papers A, B and C; proliferation and lipid accumulation stay distinct endpoints, Paper B keeps its species, Paper C is a failure, every span resolves; a mismatched span is not admitted, a broken span fails before verification, a retry is bounded, an ambiguous claim waits for a reviewer (features/evidence/extraction.feature, verification.feature).

- [X] T054 [P] [US3] Write `S/domain/evidence/Records.scala`: `Finding`, `Claim`, `Attempt`, `ClaimVerification` with its five `Check`s and the `entailed` invariant, `ExtractionSet` (the extractor's reply: records with verbatim quotations keyed by section), `MissingValueStatus`, record events; the transition table
- [X] T055 [US3] Write `S/application/entities/FindingEntity.scala` (`finding`): `propose`, `record-validation`, `verified`, `rejected`, `requires-review`, `quarantine`, `repropose(content, defect)` refused `Conflict` at the budget, `review(action: verify | reject | complain_citation, by, rationale)` (a citation complaint moves the record to `requires_review`, FR-053), `flag-for-review`; queries `get`, `attempt`; unit test `T/unit/FindingEntitySuite.scala` (every attempt kept; budget refusal; verified content never changed by a later event; review by an unlisted reviewer refused)
- [X] T056 [US3] Write `S/application/entities/ClaimEntity.scala` (`claim`): as the finding, plus `generatedBy`, `kind`, and `verified` refused `Conflict` for a `system_hypothesis`; unit test `T/unit/ClaimEntitySuite.scala`
- [X] T057 [P] [US3] Extend `S/rules/Deterministic.scala` for records (`deterministic@v1`: source version exists, every span resolves through `source.resolve-span`, required metadata, units, status, no extractor-set `verified`, hypothesis never verified) and write `S/rules/HighImpact.scala` (`high-impact@v1`: ambiguous or insufficiently supported; the only affirmative evidence for a question or mandatory facet; a system hypothesis → `requires_review`) and `S/rules/Quotations.scala` (map a verbatim quotation onto a `SourceSpan` by whitespace-tolerant match; no match is `span_unresolved`); unit tests `T/unit/DeterministicSuite.scala`, `T/unit/QuotationsSuite.scala`
- [X] T058 [P] [US3] Write `R/prompts/extract/v1.md` (distinct kinds; quote verbatim with section keys; record missing values as not established; never infer an endpoint not reported) and `R/prompts/verify/v1.md` (given the proposed record and the original sections only; the five checks with evidence quotes; label rules) and `S/application/agents/EvidenceAgent.scala` (`evidence-agent`) with handlers `extract` → `ExtractionSet` and `verify` → `ClaimVerification`, effort high, sections in `withContext`
- [X] T059 [US3] Write `S/application/workflows/VerificationWorkflow.scala` (`verification`, id `<record>:a<attempt>`): steps `validate` (deterministic gate; failure records and quarantines with no model call), `verify` (through `StageCall`, with the cited and related sections read from `source`), `record` (`verified`, `rejected`, `requires-review` by label and `high-impact@v1` conditions (a) and (c)), `decide-retry` (`repropose` on a retryable defect within budget, else `quarantine`); 3-minute step timeout on `verify`
- [X] T060 [US3] Write `S/application/consumers/VerificationTrigger.scala`: two consumers, over `finding` and over `claim` events, starting `verification` with the derived id on `Proposed` and `Reproposed` (`Conflict` from a second start is ignored); unit test `T/unit/VerificationTriggerSuite.scala` with `ConsumerTestKit` and `TestTransport`
- [X] T061 [US3] Extend `S/application/workflows/IngestionWorkflow.scala`: step `extract` (when `extract = true`; skipped without a model call when `source` already records an extraction for this version and prompt version; through `StageCall`; each record through `Quotations` and the deterministic gate; `finding` and `claim` entities proposed with derived ids; `span_unresolved` and `schema_invalid` retried within budget, then the drafts kept as quarantined findings) and `await-verification` (pauses with `thenPause(5.seconds, onTimeout = itself)`, at most 60 times, until every record of the version left `proposed`; ends with the counts in state)
- [X] T062 [P] [US3] Write `S/application/views/FindingRows.scala` and `S/application/views/ClaimRows.scala` (`by-source`, `by-status`) and add to `S/api/ApiEndpoint.scala`: `POST /sources/{id}:extract`, `GET /findings/{id}`, `GET /claims/{id}` (spans resolved, attempts, verifications, flags), `POST /findings/{id}/review`, `POST /claims/{id}/review`, `POST /claims` (reviewer-authored, `generatedBy = reviewer:<name>`), `GET /review-queue`
- [X] T063 [P] [US3] Add `GET /review/queue` to `S/api/ReviewEndpoint.scala` with `S/api/pages/QueuePage.scala`: each record in `requires_review` beside its resolved source passage and verification checks, with verify and reject forms
- [X] T064 [P] [US3] Author cassettes `evals/cassettes/extract@v1/{paper-a,paper-b,paper-c}.json` (A: a proliferation finding and no lipid claim, lipid outcome `not_established_by_assessed_material`; B: the species kept on the finding and as a qualification on the normalised claim; C: outcome `failure` under the tested conditions) and `evals/cassettes/verify@v1/` for each record plus `mismatched-span.json` (`insufficiently_supported`), `ambiguous.json`, `retry-first-attempt.json` (a retryable defect) and `retry-second-attempt.json`, every quotation checked by `CassetteAuthor`
- [X] T065 [US3] Write `T/integration/ExtractionSuite.scala`: ingest the three papers with `extract = true`, the records reach `verified` with their endpoints, species and outcomes; a broken span is quarantined with `span_unresolved` and the model was never called (`TestModelProvider.callCount`); a retry is bounded at the budget and keeps every attempt; `restartService()` between `validate` and `verify` resumes the verification; an ambiguous verification lands in the review queue and a listed reviewer's `verify` moves it on
- [X] T066 [US3] Write `T/features/steps/EvidenceSteps.scala` for `features/evidence/extraction.feature` and `features/evidence/verification.feature` (eight scenarios); `just features-replay` passes sixteen

**Checkpoint**: Walkthrough steps 5 and 6 (verification half).

---

## Phase 6: User Story 4 - Assess evidence per question and declare sufficiency or abstain (Priority: P1)

**Goal**: Each verified record is assessed against each question it bears on with relationship, applicability and its dimensions, rationale and limitations; the species rule holds; sufficiency is judged for a named decision with assumptions, gaps and a coverage judgement.

**Independent Test**: With the three verified papers, Paper A is not supports/direct for lipid accumulation, Paper B is indirect by the species rule, Paper C contradicts or qualifies; production feasibility is insufficient, a feasibility experiment is justified; an empty search is a coverage gap (features/evidence/assessment.feature, sufficiency.feature).

- [x] T067 [P] [US4] Write `S/domain/evidence/Assessment.scala` (`EvidenceAssessment`, `Dimensions`, `AssessmentSet` reply type, events), `S/domain/evidence/Sufficiency.scala` (`EvidenceSufficiency`, `Sufficiency` reply type, events) and `S/domain/evidence/Gap.scala` (`KnowledgeGap`, events)
- [x] T068 [US4] Write `S/application/entities/AssessmentEntity.scala` (`assessment`, id `<question>:<record id>`): `propose` refusing a record that violates the species rule after the rule set ran, `apply-rules`, `review`, `flag-for-review`; unit test `T/unit/AssessmentEntitySuite.scala` (species mismatch with `direct` refused; no truth score field exists)
- [x] T069 [P] [US4] Write `S/application/entities/SufficiencyEntity.scala` (`sufficiency`, id `<question>:<decision slug>`: `judge` as a new version, `flag-for-review`) and `S/application/entities/GapEntity.scala` (`gap`: `open`, `record-supplemental` refused `Conflict` at `EDP_SUPPLEMENTAL_BUDGET`, `escalate`, `close`); unit tests `T/unit/SufficiencyEntitySuite.scala`, `T/unit/GapEntitySuite.scala`
- [x] T070 [P] [US4] Write `S/rules/Applicability.scala` (`applicability@v1`: species mismatch forbids direct, endpoint mismatch forbids direct, unknown species → unknown; rewrites and records outcomes); unit test `T/unit/ApplicabilitySuite.scala`
- [x] T071 [P] [US4] Write `R/prompts/assess/v1.md` (one question, the verified records with their spans resolved; relationship, applicability per dimension, rationale naming mismatches, limitations; no scores) and `R/prompts/sufficiency/v1.md` (a named decision; proposition, assumptions, supporting assessments, critical gaps, coverage judgement, escalation) and add handlers `assess` → `AssessmentSet` and `judge-sufficiency` → `Sufficiency` to `S/application/agents/EvidenceAgent.scala`
- [x] T072 [US4] Write `S/application/workflows/QuestionInvestigationWorkflow.scala` (`question-investigation`, id = question id), replacing the US2 stub: steps `collect` (until US6: every verified record of every eligible ingested source, recorded as such in state), `assess` (through `StageCall`; `assessment` entities; `applicability@v1`), `sufficiency` (one call per decision in `EDP_DECISIONS`; `sufficiency` entities; `high-impact@v1` condition (b): a record that is the only affirmative evidence is sent `requires-review` and the judgement is `requires_expert_review`), `gaps` (opens `gap` entities from the critical gaps; a coverage gap is left for US6's supplemental step), end; `question.advance` to `partial` or `requires_review`
- [x] T073 [P] [US4] Write `S/application/views/AssessmentRows.scala` (`by-question`, `by-status`) and add to `S/api/ApiEndpoint.scala`: `GET /assessments/{id}`, `POST /assessments/{id}/review`, `POST /questions/{id}/sufficiency` (`202`: transitions the question's workflow to `sufficiency` for that decision), `GET /questions/{id}/gaps`; assessments and sufficiency in `GET /questions/{id}`
- [x] T074 [P] [US4] Author cassettes `evals/cassettes/assess@v1/lipid-{paper-a,paper-b,paper-c}.json` (A: `background` or `not_applicable`, rationale names the endpoint mismatch; B: `supports`, `indirect`, species mismatch; C: `qualifies`) and `evals/cassettes/sufficiency@v1/lipid-production-feasibility.json` (`insufficient_for_recommendation`, the direct avian gap), `lipid-feasibility-experiment.json` (`sufficient_for_proposed_next_step` with assumptions), `empty-search.json` (`search_inadequate`, escalation `supplemental_search`)
- [x] T075 [US4] Write `T/integration/AssessmentSuite.scala`: the three assessments for the lipid question; a scripted `direct` for Paper B is rewritten to `indirect` with the rule outcome recorded; sufficiency differs by decision; a question with nothing retrieved is judged with coverage `search_inadequate`; `restartService()` between `assess` and `sufficiency` resumes
- [x] T076 [US4] Write `T/features/steps/AssessmentSteps.scala` for `features/evidence/assessment.feature` and `features/evidence/sufficiency.feature` (seven scenarios); `just features-replay` passes twenty-three

**Checkpoint**: The P1 stories are complete: walkthrough steps 1 to 3 and 5 to 7 on fixtures.

---

## Phase 7: User Story 5 - Ingest external literature and internal records with provenance (Priority: P2)

**Goal**: Literature from the approved sources and internal records are ingested with identifiers, licence, status, hash and versions; parses are flagged, units validated, duplicates detected, corrupt extractions quarantined; measurements stay queryable.

**Independent Test**: Ingest one literature source and one internal measurement record; each traces from any chunk or span back to its version, section and location; re-ingesting is a duplicate (features/sources/ingestion.feature).

- [x] T077 [P] [US5] Write `S/adapters/EuropePmcAdapter.scala` (REST `search` with `resultType=core`; `fullTextXML` by PMCID; licence, open access, retraction from `pubType`; at most five requests a second) and `T/unit/EuropePmcAdapterSuite.scala` against a loopback stub serving recorded responses from `T/resources/adapters/europepmc/`
- [x] T078 [P] [US5] Write `S/adapters/PubMedAdapter.scala` (`esearch`, `efetch` XML, `tool` and `email`, optional key, 3 requests per second) and `S/adapters/CrossrefAdapter.scala` (`works?query=`, `license[]`, `update-to[]` for retraction and correction, `mailto`); unit tests `T/unit/PubMedAdapterSuite.scala`, `T/unit/CrossrefAdapterSuite.scala` against loopback stubs
- [x] T079 [P] [US5] Write `S/adapters/InternalAdapter.scala` (`manifest.json` naming CSV files, columns, units, source system, responsible researcher, protocol version; rows become `SourceRecord`s of internal kinds with `Measurement`s; a unit outside `S/rules/Units.scala` is `unitValid = false` with a parse issue, never converted) and `S/application/views/MeasurementRows.scala` (keyed view, `by-kind-and-range`, `invalid-units`); unit tests `T/unit/InternalAdapterSuite.scala`, `T/unit/MeasurementRowsSuite.scala`
- [x] T080 [US5] Extend `S/application/workflows/IngestionWorkflow.scala` and `S/application/entities/SourceEntity.scala` for the remaining rules: `abstract_only` when the full text cannot be fetched (metadata and abstract retained), `Quarantined` with a reason for a corrupt or materially ambiguous extraction (nothing dropped), the parse-completeness flag naming an unresolved table, `StatusChanged` surfaced to `assess-eligibility`, the internal `measurements` step; `change-status` takes `by` (reviewer or `operator`)
- [x] T081 [US5] Add to `S/api/ApiEndpoint.scala`: `POST /sources:ingest-internal`, `POST /sources/{id}/status`, `GET /measurements`; extend `GET /sources/{id}` with versions, parse issues, eligibility and quarantine
- [x] T082 [P] [US5] Write `scripts/ingest-collection.sh` (a list of at least ten real PMCIDs covering the six questions, one known abstract-only, ingested through Europe PMC in the live tier for SC-003; never run in CI) and document it in `quickstart.md`
- [x] T083 [US5] Write `T/integration/ProvenanceSuite.scala`: a literature fixture and the internal dataset ingested; a chunk traces to its section, offsets and source version; a measurement row has its unit and `unitValid`; ingesting again creates no version; a fixture with an unparseable table carries the flag; a retracted meta surfaces in eligibility; a fetch that fails for text leaves `abstract_only`; `restartService()` and every read repeats
- [x] T084 [US5] Write `T/features/steps/SourceSteps.scala` for `features/sources/ingestion.feature` (seven scenarios, the status outline's three rows); `just features-replay` passes thirty-two

**Checkpoint**: Walkthrough step 4's ingestion half; SC-003's collection script ready for the live tier.

---

## Phase 8: User Story 6 - Plan and execute reproducible searches with eligibility separate from ranking (Priority: P2)

**Goal**: Each question gets a search plan; executions record everything returned with the snapshot searched; lexical and semantic retrieval are fused; eligibility is applied after ranking with reasons; empty results are coverage gaps; supplemental searches are bounded.

**Independent Test**: For the lipid accumulation question, a plan with two lexical and one semantic query is produced; executing it against the seeded store returns the three fixture papers; the retracted paper ranked first is excluded with a logged reason; a rerun returns the same identifiers (features/search/retrieval.feature).

- [x] T085 [P] [US6] Write `S/domain/search/Search.scala`: `SearchPlan`, `Query`, `SearchExecution`, `Hit`, `Exclusion`, the `SearchPlan` reply type and codec, events
- [x] T086 [US6] Write `S/application/entities/SearchPlanEntity.scala` (`search-plan`: `plan` as a new version with the two-kinds gate, `record-execution`) and `S/application/entities/SearchExecutionEntity.scala` (`search-execution`: `execute` once, `apply-eligibility`); unit tests `T/unit/SearchPlanEntitySuite.scala`, `T/unit/SearchExecutionEntitySuite.scala`
- [x] T087 [P] [US6] Write `R/prompts/plan/v1.md` and `S/application/agents/SearchAgent.scala` (`search-agent`, handler `plan` → `SearchPlan`, `effects.model(claude-haiku-5-5, effort low)`)
- [x] T088 [P] [US6] Write `S/retrieval/Lexical.scala` (the `chunk-rows` `lexical` declared query), `S/retrieval/Semantic.scala` (embed the query; stream the current model's rows; cosine; top 20), `S/retrieval/Fusion.scala` (RRF `k = 60`, deduplicate by chunk id, keep both ranks), `S/retrieval/Context.scala` (`source.context` for each hit: parent section, adjacent chunks, related sections, metadata); unit tests `T/unit/FusionSuite.scala`, `T/unit/SemanticSuite.scala` (hash embedder)
- [x] T089 [US6] Extend `S/application/workflows/QuestionInvestigationWorkflow.scala`: steps `plan` (through `StageCall`), `execute` (one `search-execution` per query and target: local lexical, local semantic, and each adapter named; the snapshot of source versions searched recorded; adapter transient failures retried twice by `RecoverStrategy`), `eligibility` (`eligibility@v1` after ranking; `Exclusion` per removal or downgrade; `coverageGap` when nothing eligible), `resolve`, replacing `collect`; `gaps` now transitions to `supplemental` for an open coverage gap within the gap's budget, which runs the plan's queries again with the escalation's expansions and links the execution to the gap, then back to `assess`
- [x] T090 [P] [US6] Write `S/application/views/ExecutionRows.scala` (`by-question`) and add to `S/api/ApiEndpoint.scala`: `POST /questions/{id}/plan` and `POST /search-plans/{id}:execute` (both `202`, transitioning the question's workflow), `GET /search-executions/{id}`, `POST /search-executions/{id}:rerun` (the same queries against the recorded snapshot; `matches`), `POST /gaps/{id}:search` (`409` when the budget is spent)
- [x] T091 [P] [US6] Author `evals/cassettes/plan@v1/lipid.json` (two lexical expansions, one semantic query, targets `local` and `europepmc`, filters, a stopping criterion)
- [x] T092 [US6] Write `T/integration/RetrievalSuite.scala`: seed the four fixtures; the lipid plan's local executions return A, B and C; the retracted paper, scripted to rank first by its text, is excluded with `rule: retracted`; a rerun matches; an unmatched query is a coverage gap and the supplemental search is bounded at two; `restartService()` between `execute` and `eligibility` resumes
- [x] T093 [US6] Write `T/features/steps/SearchSteps.scala` for `features/search/retrieval.feature` (seven scenarios); `just features-replay` passes thirty-nine

**Checkpoint**: Walkthrough step 4 complete; SC-002.

---

## Phase 9: User Story 7 - Generate and compare candidate experimental approaches (Priority: P2)

**Goal**: From validated evidence packages, at least two candidates with their evidence and unknowns; a comparison on the eight criteria with no invented probabilities; a decision record keeping the rejected alternatives.

**Independent Test**: With the reference packages, two candidates with the required fields are produced, no probability is asserted, and a decision names the selected candidate and the reasons for the others (features/design/candidates.feature).

- [x] T094 [P] [US7] Write `S/domain/design/Package.scala` (`EvidencePackage`, `Trim`, events) and `S/domain/design/Approach.scala` (`ExperimentalApproach`, `Assumption`, `Comparison`, `Criterion`, `ApproachSet` reply type, `DecisionRecord`, events)
- [x] T095 [US7] Write `S/rules/Package.scala` (`package@v1`: spans resolve, qualifiers present, same-study deduplication by shared identifiers, contradictions included, mandatory facets covered or assumed or flagged, eligibility and verification enforced, no critical trim) and `S/application/entities/PackageEntity.scala` (`package`: `build`, `validate`, `flag-for-review`; `qualityStatus = valid` only when every check passed); unit tests `T/unit/PackageRulesSuite.scala`, `T/unit/PackageEntitySuite.scala`
- [x] T096 [P] [US7] Write `S/application/entities/ApproachEntity.scala` (`approach`: `propose`, `compared`, `selected`, `rejected-in-decision`, `review`, `flag-for-review`; no probability field) and `S/application/entities/DecisionEntity.scala` (`decision`: `decide` once with the reviewer check, a second is `Conflict`); unit tests `T/unit/ApproachEntitySuite.scala`, `T/unit/DecisionEntitySuite.scala`
- [x] T097 [P] [US7] Write `R/prompts/candidates/v1.md` (at least two; every field; every assumption labelled; no probabilities; the eight criteria with rationale) and `S/application/agents/DesignAgent.scala` (`design-agent`, handler `candidates` → `ApproachSet`, output guardrail `no-probability`)
- [x] T098 [US7] Extend `S/application/workflows/InvestigationWorkflow.scala`: steps `build-packages` (one `package` per question from the assessments, spans, facets and constraints; `package@v1`; trims logged, never a mandatory facet, contradiction or qualification), `candidates` (through `StageCall`; `approach` entities with ids `<request>:c<n>`; `fewer_than_two` retryable), `await-decision` (`thenPause()`); command `decided(decision)` recording the `decision` entity, `selected` and `rejected-in-decision` on the approaches, transitioning to `design` (until US8 a step that ends the workflow)
- [x] T099 [P] [US7] Add to `S/api/ApiEndpoint.scala`: `GET /packages/{id}` (included passages resolved), `GET /requests/{id}/approaches`, `POST /requests/{id}/decide`; add `GET /review/requests/{id}/candidates` to `S/api/ReviewEndpoint.scala` with `S/api/pages/CandidatesPage.scala`
- [x] T100 [P] [US7] Author `evals/cassettes/candidates@v1/reference.json` (two approaches: a lipid accumulation feasibility study in a primary avian hepatocyte model, and an alternative cell model, each with supporting, contrary, indirect and missing evidence, assumptions labelled, the comparison with rationale per criterion and no numbers presented as probabilities)
- [x] T101 [US7] Write `T/integration/CandidatesSuite.scala`: packages valid with trims logged; a package that would drop a contradiction is `invalid`; two approaches with evidence and unknowns; a scripted reply asserting "70% chance of success" is refused by the guardrail and recorded as a defect; a decision by a listed reviewer resumes the workflow; the rejected approaches and reasons remain retrievable after `restartService()`
- [x] T102 [US7] Write `T/features/steps/DesignSteps.scala` for `features/design/candidates.feature` (three scenarios); `just features-replay` passes forty-two

**Checkpoint**: Walkthrough step 8; SC-007.

---

## Phase 10: User Story 8 - Produce a structured experimental design and record the reviewer's decision (Priority: P2)

**Goal**: A typed, versioned twelve-section design whose references resolve; a rendering derived from it; approval, rejection or change requests recorded against the version; an unapproved design is a proposal.

**Independent Test**: Generate a design from the selected candidate, render it, verify every background reference resolves, and record an approval and a rejection on separate versions with rationale (features/design/experimental-design.feature).

- [x] T103 [P] [US8] Write `S/domain/design/Design.scala` (`ExperimentalDesign` reply type with the twelve sections, `DesignVersion`, `Grounded`, `Manifest`, `Approval`, events, the transition table per version)
- [x] T104 [US8] Write `S/rules/Design.scala` (`design@v1`: twelve sections non-empty, every claim ref `verified`, every span resolves, mandatory facets accounted for, transition legal) and `S/application/entities/DesignEntity.scala` (`design`, id `<request>:design`: `generate` as a new version superseding the previous, `submit`, `approve`, `reject`, `request-changes` with the reviewer check, `flag-for-review(changedRef, version)`); unit tests `T/unit/DesignRulesSuite.scala`, `T/unit/DesignEntitySuite.scala` (an approved version's content is unchanged by any later event; approving a version not under review is `Conflict`)
- [x] T105 [P] [US8] Write `S/domain/design/Rendering.scala` (`render(DesignVersion): String` Markdown, marked "Proposal awaiting review" unless approved, the approval history, every claim reference rendered from the manifest) and `T/unit/RenderingSuite.scala` (every claim id in the output exists in the manifest; no section is asserted that the design lacks)
- [x] T106 [P] [US8] Write `R/prompts/design/v1.md` (the twelve sections; background grounded in verified claims and spans only; no wet-lab procedure presented as authorised) and add handler `design` → `ExperimentalDesign` to `S/application/agents/DesignAgent.scala`
- [x] T107 [US8] Extend `S/application/workflows/InvestigationWorkflow.scala`: step `design` (through `StageCall`; `design@v1`; `unverified_claim_ref` retryable; `design.generate`), `await-approval` (`thenPause()`); command `approval-recorded(version, decision)`: `approved` → end with `request.advance(designed)`; `rejected` → end with the rejection recorded; `changes_requested` → `design` again with the requested changes in the next prompt's context
- [x] T108 [P] [US8] Write `S/application/views/DesignRows.scala` (`all`, `flagged`) and add to `S/api/ApiEndpoint.scala`: `GET /designs/{id}?version=&format=json|md`, `POST /designs/{id}:submit`, `POST /designs/{id}/approval`; add `GET /review/designs/{id}` to `S/api/ReviewEndpoint.scala` with `S/api/pages/DesignPage.scala`
- [x] T109 [P] [US8] Author `evals/cassettes/design@v1/reference.json` (twelve sections; background references the verified Paper A and Paper B claims with their spans; success, failure and inconclusive criteria; alternatives naming the rejected approach) and `evals/cassettes/design@v1/after-changes.json`
- [x] T110 [US8] Write `T/integration/DesignSuite.scala`: a design with twelve sections whose references resolve; the rendering contains every manifest claim and nothing else; a rejection on version 1 with rationale, a regenerated version 2 approved by a listed reviewer; version 1 still readable with its rejection after `restartService()`; an approval by an unlisted name is `400` and persists nothing; a design never submitted is presented as a proposal
- [x] T111 [US8] Extend `T/features/steps/DesignSteps.scala` for `features/design/experimental-design.feature` (four scenarios); `just features-replay` passes forty-six

**Checkpoint**: Walkthrough steps 9 and 11 (approval half); SC-008.

---

## Phase 11: User Story 9 - Measure quality per stage and prove that injected faults are blocked (Priority: P3)

**Goal**: An expert-labelled dataset; per-stage measurements with critical failures as a blocking category; six reference fault injections caught at their gates; independent tests that fail when a safeguard is disabled.

**Independent Test**: Run the six fault injections and verify each is caught at the stated gate; run the dataset and produce a per-stage report with critical failures listed separately; each of three patches makes an independent test fail (features/assurance/fault-injection.feature, evaluation.feature).

- [x] T112 [P] [US9] Write `S/domain/evidence/Mutations.scala`: the six named mutations (`endpoint_swap`, `negation_flip`, `species_drop`, `failure_to_success`, `span_break`, `citation_mismatch`) as pure functions on a record's content, each naming the defect kind that should catch it; unit test `T/unit/MutationsSuite.scala`
- [x] T113 [US9] Add `POST /faults:inject` to `S/api/ApiEndpoint.scala`: applies the mutation through `finding.repropose` or `claim.repropose` as `operator` (a fault is an attempt, so the `verification-trigger` starts the verification), returns the attempt and the workflow id; author `evals/cassettes/verify@v1/fault-*.json` for the five model-checked faults (each labelled with its detection) through `CassetteAuthor`
- [x] T114 [US9] Write `T/faults/FaultInjectionSuite.scala`: each of the six faults on its paper is caught at the stated gate (`span_break` before verification without a model call; the five others by the verifier's checks), the altered attempt is `rejected` or `quarantined`, and no package or sufficiency built afterwards cites it (zero leakage, SC-005); the endpoint fault leaves the lipid gap open
- [x] T115 [P] [US9] Author the evaluation dataset: `evals/cases/*.json` (at least 20 end-to-end cases, one per adversarial category at least: species mismatch, negation, proliferation versus lipid accumulation, negative result, superseded protocol, retracted paper, hypothesis framed as result, correlation versus causation, missing or misconverted unit, absent table, resource unavailability, genuinely insufficient evidence; expected labels with acceptable alternatives and uncertain cases) and `evals/passages/*.json` (at least 30 passages with the expected verification label and failing checks); `evals/README.md` describing the labelling and the stand-in
- [x] T116 [US9] Write `S/evaluation/Dataset.scala` (loaders and validation of the case and passage files), `S/evaluation/Metrics.scala` (per stage: facet precision and recall, missed questions, parse completeness, retrieval recall and ranking quality, contradictory-evidence recall, ineligible-evidence leakage, critical-error recall, false-rejection rate, extraction fidelity, assessment misclassification, unsafe recommendation rate, appropriate abstention, package coverage, reviewer acceptance), `S/evaluation/Report.scala` (JSON and Markdown: critical failures first as a blocking category, per-stage sections, the feature results and drift, limitations: authored cassettes, cases not run, stand-in review), `S/evaluation/Baseline.scala` (compare against `evals/baseline.json`; "no baseline yet"); unit test `T/unit/MetricsSuite.scala`
- [x] T117 [US9] Write `T/evaluation/EvaluationSuite.scala` (runs every case and passage through the service in the replay tier with pinned prompt, model, embedding and cassette versions and the source snapshot, writes `evals/reports/<timestamp>.json` and `.md`) and `S/evaluation/EvalRun.scala` (`@main` for `just eval` and `just eval-compare`); `GET /evals/latest` and `POST /evals/cases:from-record` (promotes a reviewed failure into a case file under `evals/cases/`, FR-053) in `S/api/ApiEndpoint.scala`
- [x] T118 [P] [US9] Write `T/independent/IndependentSuite.scala` from the spec's requirements and the glossary alone (no prompt text, no cassette internals): a retracted source is never eligible; a species mismatch is never direct; a span that does not match its text is never admitted; a hypothesis is never verified; an unlisted reviewer never approves; a rendering never adds a claim
- [x] T119 [P] [US9] Write `scripts/mutants/bypass-eligibility.patch`, `remove-species-rule.patch`, `accept-mismatched-span.patch` and `scripts/mutants.sh` (copies the tree to a temporary directory, applies one patch, runs `sbt "testOnly *IndependentSuite"`, reports killed or survived, restores nothing in the working tree); `just mutants`
- [x] T120 [US9] Write `T/features/steps/AssuranceSteps.scala` for `features/assurance/fault-injection.feature` (the five-row outline, the gap scenario, and the three-row disabled-safeguard outline reading `scripts/mutants.sh`'s last result file) and `features/assurance/evaluation.feature`; `just features-replay` passes fifty-six

**Checkpoint**: Walkthrough step 10 (fault half); SC-005, SC-006, SC-012, SC-013.

---

## Phase 12: User Story 10 - Propagate the impact of a changed finding or source status (Priority: P3)

**Goal**: A change to a source's status or a record's verification flags every dependent assessment, sufficiency, package, candidate and design for review, without rewriting anything.

**Independent Test**: Mark Paper B retracted after a design is approved; its assessments, package, candidates and the design are flagged while the approved version remains intact (features/assurance/impact.feature).

- [x] T121 [US10] Write `S/application/views/Dependents.scala`: keyed view over `finding`, `claim`, `assessment`, `sufficiency`, `package`, `approach` and `design` events, one row per `(on, dependent)` from each record's `dependsOn` on `Proposed`, `Judged`, `Built` and `DraftGenerated`, keyed `<on>|<dependent>`; declared queries `of` (an exact `kind:id:version`) and `of-any-version` (`kind:id`); unit test `T/unit/DependentsSuite.scala` with `KeyedViewTestKit`
- [x] T122 [US10] Write `S/application/workflows/ImpactWorkflow.scala` (`impact`, id `impact:<kind>:<id>:v<version>`): state (changedRef, reason, visited, flagged), step `walk` (breadth-first over `dependents.of` and the later versions deriving from the changed one, `flag-for-review(changedRef, reason)` to each dependent's entity, progress in state, safe to repeat), end
- [x] T123 [US10] Write `S/application/consumers/ImpactTrigger.scala`: three consumers (over `source` `StatusChanged` and a new `ContentFetched` version; over `finding` and `claim` events that change verification status or repropose), each starting `impact` under the derived id and ignoring `Conflict`; unit test `T/unit/ImpactTriggerSuite.scala` with `ConsumerTestKit`
- [x] T124 [US10] Add `GET /impact?ref=` to `S/api/ApiEndpoint.scala` (dependents grouped by kind with whether each is flagged) and the flags to every record's `GET`; the design page and the queue show the flag naming the changed source version
- [x] T125 [US10] Write `T/integration/ImpactSuite.scala`: after an approved design that depends on Paper B, `POST /sources/{B}/status retracted` flags every dependent assessment, package, approach and design (100% of `dependents.of`), the approved version's content is byte-identical, the flag names Paper B's version; a second status change adds no duplicate flag; `restartService()` mid-walk resumes; a finding reproposed by a fault flags its assessments
- [x] T126 [US10] Write `T/features/steps/ImpactSteps.scala` for `features/assurance/impact.feature` (two scenarios); `just features-replay` passes all fifty-eight and reports none ignored

**Checkpoint**: All ten stories; walkthrough steps 10 and 11 complete; SC-009, SC-010.

---

## Phase 13: Polish & Cross-Cutting Concerns

- [ ] T127 Write `scripts/walkthrough.sh`: the eleven steps of quickstart.md over the HTTP API with `--reviewer` and `--auto-review`, printing what each step proved and the run ids; run it end to end in the replay tier within the two-hour bound (SC-011) and record the time in `research.md` Measurements
- [ ] T128 [P] Record every live-tier cassette once (`just features-record` with a credential), run `just features-live`, and write `evals/reports/drift.json`; note the drift, if any, in `research.md` Measurements
- [ ] T129 [P] Run `just eval` in both tiers, review the report with the stand-in reviewer, and record the first measured values in `research.md` Measurements; do not write `evals/baseline.json` until a customer reviewer has agreed it
- [ ] T130 [P] Fill `research.md` "Found during implementation" from the R20 list (each item confirmed or replaced), and `notes/ankka-requests.md` with anything ankka could not do (pgvector, a cookie in `Authenticate`, anything else found)
- [ ] T131 [P] Finalise `docs/limitations.md` and `README.md` against what was built; `GET /healthz` and the console checked against quickstart.md
- [ ] T132 [P] `just fmt` across the tree, `sbt compile` warning-free, the pre-commit hook enabled with `just hooks`
- [ ] T133 Deploy to a local kind installation once (`sbt Docker/publishLocal`, `kind load docker-image`, `ankka services apply -f service.json` with the `EDP_*` values as project secrets, `ankka services expose`) and run `scripts/walkthrough.sh` against the exposed hostname; record the outcome in `research.md`
- [ ] T134 Run quickstart.md top to bottom on a clean checkout and fix anything it says that is not so

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup; blocks every story. Inside it: T009 to T013 first (domain base), then T014 to T018 (scaffolding) and T019 to T028 (source and fixtures) in parallel, then T029 to T034 (edge, registration, harnesses).
- **User stories (Phases 3 to 12)**: each depends on Foundational. US2 depends on US1 (questions derive from confirmed facets). US4 depends on US3 (assessments take verified records). US6 depends on US4 (it replaces `collect` in the question workflow) and US5's eligibility rules. US7 depends on US4 and US6 (packages take assessments and executions). US8 depends on US7. US9 depends on US3 and US4 (faults and metrics). US10 depends on US8 (the approved design to flag). US5 depends only on Foundational and can run beside US1 to US4.
- **Polish (Phase 13)**: depends on every story.

### Within Each User Story

- Domain types before entities; entities and rules before agents and workflows; workflows before routes and pages; cassettes before the integration suite; the feature steps last, once the story's routes exist.
- Every entity ships with its unit test in the same task; every story ends with its feature file passing in replay.

### Parallel Opportunities

- Phase 1: T002 to T007 in parallel after T001.
- Phase 2: T010, T011, T012 beside T009; T015, T016, T017 beside T014; T021, T022, T023, T025, T027, T028 beside T019 and T020.
- Each story's `[P]` tasks (domain types, prompts and agent handlers, views and routes, cassettes, pages) run beside each other; the entity, workflow and suite tasks are sequential.
- US5 (ingestion adapters) runs beside the P1 stories once Foundational is done.

---

## Parallel Example: User Story 3

```bash
# After T054 (domain types), launch together:
Task: "T057 deterministic, high-impact and quotation rules in S/rules/"
Task: "T058 extract and verify prompts and EvidenceAgent handlers"
Task: "T062 FindingRows, ClaimRows and the record routes"
Task: "T063 the review queue page"
Task: "T064 the extract and verify cassettes"
# Then sequentially: T055, T056 (entities), T059 (verification workflow), T060 (trigger), T061 (ingestion extract step), T065 (integration suite), T066 (feature steps)
```

---

## Implementation Strategy

### MVP First (User Stories 1 to 4)

1. Complete Phase 1 and Phase 2.
2. Complete US1, then US2, US3, US4: the three-paper case on fixtures, from request to sufficiency, with the review queue. This is what the spec calls the acceptance test of the whole POC.
3. Validate with `just features-replay` (twenty-three scenarios) and the walkthrough's steps 1 to 3 and 5 to 7.

### Incremental Delivery

- US5 and US6 add real ingestion and retrieval (walkthrough step 4; SC-002, SC-003).
- US7 and US8 add candidates, the design and the approval trail (steps 8, 9, 11; SC-007, SC-008).
- US9 and US10 add the assurance: faults, evaluation, independence, impact (step 10; SC-005, SC-006, SC-009, SC-012, SC-013).
- Each story leaves every earlier scenario passing; `just pr` is the gate.

### The six-week map

Weeks 1 to 2: Phases 1 to 4 (US1, US2) and US5's adapters in parallel. Week 3: US6. Week 4: US3 and US4 (the three-paper case). Week 5: US7 and US8. Week 6: US9, US10 and the demonstration. The task order above (US3 and US4 before US5 and US6) is the alternative that reaches the three-paper case on fixtures first; either order respects the dependencies.

---

## Notes

- `[P]` tasks touch different files and depend on nothing incomplete.
- Every entity gets `flag-for-review` when it is created; US10 adds no command to any entity.
- Every model step goes through `StageCall`, so provenance and the skip-on-repeat come for free.
- Commit after each task or logical group, in the family's style ("Feature 001: …", "Tasks: T0nn").
- Stop at any checkpoint to validate the story on its own.
