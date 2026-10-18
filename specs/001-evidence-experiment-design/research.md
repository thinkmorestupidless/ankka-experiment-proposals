# Research: Evidence-Driven Scientific Experimental Design POC

**Feature**: `001-evidence-experiment-design` | **Date**: 2026-10-16

This file records each decision, why, and what was considered. Nothing here changes the spec; it only
chooses how to meet it. The earlier Python build's `research.md` (in `../experiment-design-poc/`) was
read first; where a decision is kept from it the entry says so, and where ankka changes it the entry
says why.

## R1. Platform and language

- **Decision**: One ankka service in Scala 3.9.0 on ankka 0.12.0, created from the template with
  `ankka init`, package `com.thinkmorestupidless.experimentproposals`.
- **Rationale**: The platform supplies what the Python build wrote by hand: append-only journals,
  durable multi-step processes that resume, projections for listing and search, the agent loop with
  scripted models for tests, per-request traces in the local console. The source document's section 16
  named a later ankka integration; the owner's decision (2026-10-16) is to build the POC on it now.
  Scala rather than the Python or TypeScript SDK because the Gherkin suite, judgments, blueprints and
  `ankka-auth-oidc` are Scala-only in 0.12, and the family's conventions, hooks and CI are Scala's.
- **Alternatives**: the Python build as it stands (works, but every durability and provenance
  guarantee is hand-written and untraced); a Python service beside the sidecar (no Gherkin suite in
  the testkit, features would need a second runner).
- **The source document's non-goal.** Section 2 lists "full deployment of Ankka/EitherOr distributed
  actors" as a non-goal of the six-week POC, and the spec's Out of Scope keeps the capability form:
  no infrastructure beyond one installation. This plan honours it: the service runs as one process on
  a laptop against one Postgres, exactly as the template's first service does, and is deployed to a
  kind installation only for the demonstration. Nothing in the POC depends on a cluster.

## R2. One service, one project

- **Decision**: One sbt project, packages `domain`, `rules`, `application` (entities, views,
  consumers, workflows, agents), `retrieval`, `adapters`, `evaluation`, `replay`, `api`.
- **Rationale**: Everything must be consistent together; the component client works only inside a
  service; one person operates it. Modules as in ankka-reasoning pay off when something is published.
- **Alternatives**: separate ingestion and investigation services (topics between them, eventual
  consistency across the boundary, no gain at this scale).

## R3. Persistence and versioning

- **Decision**: Every record is an event sourced entity; its journal is the append-only history and a
  version is an event's position in it. Each versioned record exposes `version` in its state and in
  every reference (`Ref(kind, id, version)`), so links name the version they used (FR-042). Full
  text of a fetched source lives in the `source` entity's `ContentFetched` and `Parsed` events (a JATS
  article is a few hundred kilobytes; Postgres holds it as JSON). Retrieval chunks, measurements,
  listings and dependency links are view tables maintained by the runtime. Snapshots every 100 events
  (the default) keep replay cheap.
- **Rationale**: FR-041 and FR-042 fall out of event sourcing; nothing is updated in place because an
  entity cannot. One database per service is ankka's rule and matches the POC's single installation.
- **Alternatives**: key value entities for records with text (ankka-reasoning does this so text can be
  withdrawn): rejected here because every record's history is a requirement and nothing is withdrawn in
  the POC; an external Postgres schema of our own beside the journal: two sources of truth.
- **Kept from the Python build**: the record shapes, enumerations and transitions in `data-model.md`;
  prefixed ids become derived ids (R10).

## R4. Model access, structured replies and replay

- **Decision**: `AgentRuntime.withDefaultModel(AnthropicProvider.fromEnv("claude-opus-5-5"))` with
  `Defaults(effort = Some(Effort.High), maxTokens = 16000)`. Every model-dependent stage is an agent
  handler that returns `thenReplyAs[T]` into a case class with a `JsonValueCodec`, with
  `MemoryProvider.none` (one-shot, no session history) and source text passed with `withContext`.
  The system message's first line is `# prompt_version: <stage>@<version>` and the user message's first
  line a case marker, as before. Prompts are resources under `src/main/resources/prompts/<stage>/
  <version>.md`, never edited in place. `search-agent.plan` names `claude-haiku-5-5` at `Effort.Low`
  with `effects.model(...)`. Every call's prompt version, model, effort, token usage (from the session
  memory's `history` query) and latency are recorded on the `stage-run` event.
  Two more `ModelProvider` implementations: `ReplayModelProvider` answers from cassettes keyed by
  `(prompt_version, case marker)` and fails loudly naming the key when none matches;
  `RecordingModelProvider` wraps the Anthropic provider and writes a cassette per call when
  `EDP_RECORD=1`. The tier (R19) chooses which the runtime gets.
- **Rationale**: ankka's agent gives the loop, guardrails, token accounting and traces for nothing;
  `thenReplyAs` is the structural gate (a reply that does not decode fails the call naming the type).
  The provider interface is two methods, so replay and recording are small and sit under the same
  loop the live tier uses, which is what makes the two tiers comparable.
- **Alternatives**: calling the Anthropic SDK from a step (loses everything above); the Python build's
  stub HTTP server speaking the Messages API (a second process; a provider is in-process and simpler);
  structured outputs via the API's `output_format` (ankka's provider does not expose it; the system
  message states the JSON shape and the codec enforces it).
- **Refusals**: a model refusal fails the agent call with `Forbidden`; the step records a `stage-run`
  failure with defect `model_refused`, not retryable, and routes the record to review.
- **Timeouts**: the component client's default ask timeout is 10 seconds; steps call agents with
  `invokeAsync` and `ComponentClient.await(_, 3.minutes)`, and declare a matching step timeout.

## R5. Embeddings and semantic retrieval

- **Decision**: A local embedding model run in the JVM through ONNX Runtime
  (`com.microsoft.onnxruntime:onnxruntime`) with the DJL Hugging Face tokenizer, model
  `BAAI/bge-small-en-v1.5` (384 dimensions), pinned by name and revision, downloaded once to a local
  cache the environment names (`EDP_EMBEDDING_CACHE`). `EDP_EMBEDDING_MODEL=hash` selects a
  deterministic hash embedder, which every test uses. Each chunk row carries its vector, the model
  name and the revision (FR-013); retrieval reads only the current model's rows.
- **Rationale**: The Anthropic API has no embeddings endpoint; a local model has a fixed version and
  no quota, which pinned evaluation runs need. `bge-small` rather than `bge-base` because the vectors
  live in JSON rows and are compared in the service (R6), where 384 dimensions is half the work.
- **Alternatives**: a hosted embeddings API (a vendor, and its versioning); a semantic query served by
  the text model rewriting it into lexical queries (not "matched on meaning", which the glossary
  defines a semantic query as); pgvector (not in the platform's provisioned Postgres; see R20).

## R6. Hybrid retrieval over the local store

- **Decision**: Lexical retrieval is a declared query on the `chunk-rows` keyed view:
  `to_tsvector('english', payload::jsonb->>'text') @@ websearch_to_tsquery('english', :q)`, ordered by
  `ts_rank_cd`, top 20. Semantic retrieval streams the current model's chunk rows from the view
  (`allStream`) and computes cosine similarity in the service, top 20. Results are fused with
  Reciprocal Rank Fusion, `k = 60`, deduplicated by chunk id, and only then is eligibility applied as a
  separate pass that writes an `Exclusion` for every result it removes or downgrades. Parent section,
  adjacent chunks, linked tables and source metadata are resolved from the `source` entity.
- **Rationale**: Matches section 6.2 of the source. Brute-force cosine over a few thousand 384-vectors
  is well under a second; it keeps the POC to one database and no extension. The split lets each
  ranker be measured separately (FR-048).
- **Alternatives**: pgvector with an HNSW index (the right answer at scale; a later optimisation
  if the provisioned Postgres gains the extension, recorded in `notes/ankka-requests.md`); one hybrid
  SQL query (cannot measure the rankers apart); a cross-encoder reranker (deferred, pending benchmark).

## R7. External source adapters

- **Decision**: Three adapters as before: Europe PMC REST (search and `fullTextXML`, JATS; at most five requests a second), PubMed
  E-utilities (`esearch`, `efetch`, `tool` and `email` parameters, optional key), Crossref REST
  (metadata, licence URLs, `update-to` for retractions, polite pool). Plus `file` (local JATS XML or
  plain text with a `.meta.json`) and `internal` (a `manifest.json` naming CSV files, columns, units
  and the responsible researcher). Adapters are plain Scala over `java.net.http.HttpClient`, called
  from workflow steps; each returns a `SourceRecord` and raises `AdapterTransient` or
  `AdapterPermanent`. Patents and regulatory sources remain interfaces without implementations.
- **Rationale**: Free, programmatic, within rate limits; Europe PMC's JATS is where resolvable spans
  come from. Steps that call them declare `RecoverStrategy.maxRetries(2)` because a fetch is safe to
  repeat and the `source` entity refuses a duplicate by hash.
- **Retention**: unchanged from the clarification of 2026-10-15: everything an adapter can fetch is
  retained under access control, the licence recorded, `abstract_only` when the text could not be
  fetched; no allow-list.

## R8. Document parsing and structure

- **Decision**: JATS XML through `scala-xml` into sections (title, kind, canonical text, parent,
  ordinal), tables (raw text, `tableResolved`), figure captions, with methods-to-results links from
  cross-references or heading order. Plain text files become one `body` section. PDF parsing is not
  built in the POC: a source whose only text is a PDF is recorded `abstract_only` with a parse issue
  `pdf_not_parsed`, and `docs/limitations.md` says so. The fixtures and the Europe PMC open-access
  subset are JATS.
- **Rationale**: Resolvable spans (FR-021, FR-022) need canonical text with stable offsets; JATS gives
  it, PDF heuristics do not. Dropping PDF removes a native dependency and a class of low-confidence
  sections from a six-week POC whose demonstration does not need it.
- **Alternatives**: PDFBox with heading heuristics (kept as the first extension); GROBID (a service to
  run and tune).

## R9. Chunking

- **Decision**: Child chunks of about 400 tokens (counted as whitespace-delimited words) with 50-token overlap, cut at sentence boundaries
  within a section, never across sections; each chunk keeps `sectionId`, `startOffset`, `endOffset`
  into the section's canonical text. Chunking happens in the `ingestion` workflow's `chunk` step, which
  persists `Chunked(version, embeddingModel, chunks)` on the `source` entity; the `chunk-rows` keyed
  view writes one row per chunk from that event. One source version's chunk rows are written together
  (a keyed view writes a change's rows atomically) and must weigh under 4 MiB; a version with more is
  chunked into several `Chunked` events of at most 200 chunks each.
- **Rationale**: Unchanged from the Python build; the event-per-batch rule comes from the keyed view's
  limit.

## R10. Orchestration, idempotency and provenance

- **Decision**: Processes are workflows (`ingestion`, `verification`, `investigation`,
  `question-investigation`, `impact`). Every step reads the entity it would write before writing and
  skips what is already recorded; every entity id is derived from the record's parents, so a step run
  twice addresses the same record. Each stage execution appends `StageStarted` and `StageCompleted` or
  `StageFailed` to the `stage-run` entity of its run, with `(stage, stageVersion, inputHash)`; a step
  that finds a completed result for the same key skips its work. Retries by defect class are counted
  in the workflow's state against `EDP_RETRY_BUDGET` (default 2); `RecoverStrategy` retries are used
  only for transient adapter and model failures, with a failover step that records the failure and
  pauses the workflow for the operator.
- **Rationale**: FR-026, FR-043 and FR-044 map onto the workflow's journal and the entity's refusal of
  duplicates. The source forbids unbounded loops; a retry triggered by a classified defect with a
  budget is the bound.
- **Alternatives**: the Python build's `StageRunner` with a hash-keyed skip (what the workflow journal
  now provides); an autonomous agent with tasks (the sequence is fixed; the spec forbids an open-ended
  loop); blueprints (patterns as data, but a blueprint has no conditions and the pipeline branches on
  gates).

## R11. Verification design

- **Decision**: Verification is its own workflow per attempt, started by the `verification-trigger`
  consumer when a `finding` or `claim` records `Proposed` or `Reproposed`. Steps: `validate`
  (deterministic gate: codec, source version exists, every span resolves through the `source` entity's
  `resolve-span` query, metadata, units, no forbidden status), `verify` (the `evidence-agent.verify`
  handler, given the proposed record and the full text of the cited sections plus related methods and
  results sections, never the extractor's prose), `record` (the entity's `verified`, `rejected`,
  `requiresReview` or `quarantined` command with the `ClaimVerification`), `decide-retry` (a retryable
  defect within budget sends `repropose` with the defect to the entity, which starts another attempt;
  otherwise quarantine). The five named checks (negation, scope, species, treatment, endpoint) each
  carry `passed`, `evidenceQuote`, `note`; a failed check forbids `entailed`. Rule-based constraints run
  before and after the model as plain functions with ids and versions.
- **Rationale**: Separating the prompt, the call and the record reduces shared error modes, and the
  workflow journals every attempt. FR-025's three high-impact conditions route to review here.

## R12. The review interface

- **Decision**: Minimal server-rendered pages from a `review` endpoint (`/review/*`): the facet
  confirmation page, the review queue with the source passage beside each claim, the candidate
  comparison, the design and its approval form. Pages are plain HTML forms posting to the same routes
  the API exposes, rendered as `Html(markup)` by small Scala functions; the token is carried in a
  cookie set by `/review/session`. No front-end build.
- **Rationale**: The source asks for a minimal interface; a scientist needs to read passages beside
  claims; the same actions must be drivable by tests without a browser.
- **Alternatives**: `ankka init --language web` (a second service for a six-week POC); the local
  console alone (it runs queries and shows traces, but cannot carry a reviewer's form).

## R13. Rendering

- **Decision**: Markdown rendered from the `ExperimentalDesign` record by a pure function;
  `GET /api/v1/designs/{id}?format=md` and the design page. A renderer test checks that every claim id
  in the Markdown exists in the design's manifest and that nothing else is asserted. PDF is not built;
  `docs/limitations.md` says a Markdown-to-PDF step is the operator's.
- **Rationale**: FR-038. The Python build's pandoc step added nothing the demonstration needs.

## R14. Observability

- **Decision**: ankka's traces, the local console and `/ankka/metrics` as they come; logback with a
  JSON-ish layout carrying `runId`, `stage` and record ids through MDC; `ankka-telemetry-otlp` on the
  classpath so a collector, when the installation names one, receives the spans. The `stage-run`
  entity is the durable record of model calls, latency and token usage.
- **Rationale**: Section 16 asks for structured logs, stage provenance and OpenTelemetry where
  practical; the platform supplies the last.

## R15. Testing and evaluation

- **Decision**: Five suites, all munit, run serialised (`Tags.limit(Tags.Test, 1)` and
  `parallelExecution := false` as the family does):
  - `unit`: entity kits for every entity's transitions and refusals; rule sets; the renderer; JATS and
    CSV parsing; the keyed views through `KeyedViewTestKit`; consumers through `ConsumerTestKit`.
  - `integration`: `AnkkaTestKit` with `TestModelProvider` scripts: each workflow's happy path and its
    failure paths, restarts between steps to prove resumption, the routes and their ACL, the review
    pages.
  - `features`: `FeatureSuite extends GherkinSuite("features")`, step definitions bound to the HTTP
    routes and the entity queries, one definition set in both tiers (R19).
  - `independent`: tests written from the spec's requirements and the glossary with no reference to a
    prompt; `scripts/mutants.sh` applies three patches (bypass eligibility, remove the species rule,
    accept a mismatched span) to a disposable copy and expects failures (SC-013).
  - `evaluation`: runs `evals/cases` and `evals/passages` in the replay tier, writes
    `evals/reports/<timestamp>.json` and `.md` with per-stage metrics, critical failures first, a
    comparison against `evals/baseline.json`, and the limitations (which cassettes are authored, which
    cases did not run, stand-in review).
- **Fixtures**: Papers A, B, C and a retracted paper as constructed JATS with `.meta.json`; one internal
  dataset (CSV plus manifest); 20 to 30 cases and 30 to 50 passages as JSON. Cassettes are authored by
  `scripts/cassettes/` helpers that check every quotation against the parsed fixture; recorded ones
  carry `"recorded": true`.
- **Rationale**: Section 14 of the source; FR-047 to FR-054. The Gherkin suite in ankka's testkit means
  the features need no second runner.

## R16. Access control, reviewer identity and secrets

- **Decision**: The `api` and `review` endpoints use `Acl.Authenticate`: a request whose bearer token
  (or `edp_session` cookie, for pages) equals `EDP_API_TOKEN` proceeds with principal `operator`;
  otherwise `401`. The reviewer list comes from `EDP_REVIEWERS` (`name:role:stand-in,...`) or a file
  named by `EDP_REVIEWERS_FILE`, loaded once into `Reviewers`; entity commands that record a reviewer
  refuse an unlisted name with `BadRequest` before persisting, and endpoints refuse the same early. A
  stand-in is a listed entry marked so, and the evaluation report lists every record a stand-in acted
  on. Internal material is loaded only from a local, ignored directory. The Anthropic key is read by
  the provider from the environment; the descriptor names the variable and never a value.
- **Rationale**: FR-004a and the clarification: the token is who may call; the name is what is
  recorded; neither is the other.
- **Alternatives**: `ankka-auth-oidc` with an identity provider (outside six weeks unless the customer runs
  one; the pages would then read `principal.name`, a small change).

## R17. Family context

- **Decision**: The repo follows the family's conventions: `.scalafmt.conf` and the pre-commit hook
  from ankka, serialised tests, warning-free compile, a thin `Justfile`, `GLOSSARY.md` and living
  features with the bdd checker in CI, `docs/limitations.md`, `notes/ankka-requests.md`. No dependency
  on ankka-reasoning: its domain (beliefs and markets) is not this one, and its graph projection is
  the later extension here too, through a `GraphConsumer` and ankka-contrib's sink.
- **Rationale**: Principle 10 (same conventions) and principle 6 (be your own customer).

## R18. Resolved open items

| Item | Resolution for the POC |
|---|---|
| Meaning of "make foie gras" | Not resolved by the system; surfaced as an unresolved facet for the reviewer (US1). |
| Species, cell types, lab capabilities | Facets with `domain_derived` origin and `unresolved` status until confirmed. |
| Internal data format | CSV files with `manifest.json` describing source system, columns, units and the responsible researcher. |
| Usable sources and licences | Any source an adapter can fetch, retained under access control with its licence recorded; `abstract_only` when the fetch fails. |
| Who labels and signs off | A named reviewer from the configured list on every review action, decision and approval. |
| Quality thresholds | Measured and written to `evals/baseline.json`; none hard-coded. |
| PDF sources | Metadata and abstract only in the POC (R8). |

## R19. Living features as executable tests (two tiers)

- **Decision**: `FeatureSuite extends GherkinSuite("features") with LogCapturing`. Step definitions
  live under `src/test/scala/.../features/steps/`, one file per area, bound to the HTTP routes and to
  entity queries through the kit's component client; no step names a page, a route or a status code
  (they call helpers that do). `beforeAll` starts the service with the extensions `Main` uses and a
  model provider chosen by `EDP_TIER`: `replay` (default) gives `AgentRuntime` the
  `ReplayModelProvider` over `evals/cassettes`; `live` gives `AnthropicProvider.fromEnv()` and, with
  `EDP_RECORD=1`, the recording wrapper. A scenario passes only when both tiers pass; `just
  features-live` writes a `drift.json` naming scenarios that passed in replay and failed live, which
  the evaluation report includes (FR-054). `scenarioId` gives every scenario its own request and
  source ids.
- **Rationale**: The spec's acceptance scenarios live only in the features; ankka's suite runs them
  against the whole service, which is what the spec means by proof.
- **Alternatives**: Cucumber-JVM directly (a second runner and its own lifecycle); binding steps to
  pages through a browser (slow, and the same scenario must run against one component and the whole
  system).

## R20. Verify at implementation

Things this plan assumes about ankka 0.12.0 that the first task touching each must confirm, and
record under "Found during implementation" below; what cannot be done becomes an entry in
`notes/ankka-requests.md`.

1. A declared view query may call `to_tsvector`, `websearch_to_tsquery` and `ts_rank_cd` and order
   by rank (R6). If not, lexical retrieval is `ILIKE` over the row text with in-service ranking.
2. A keyed view handler may write about 200 rows (chunks with 384-float vectors) in one change under
   the 4 MiB limit (R9); otherwise lower the batch size.
3. ONNX Runtime and the DJL tokenizer load on macOS arm64 and Linux x86_64 and under `sbt run`
   (R5); the hash embedder is the fallback for tests either way.
4. `AnkkaTestKit.start` accepts a custom `ModelProvider` through `AgentRuntime.withDefaultModel`
   (R4, R19), and the model timeout can be raised for the live tier.
5. `GherkinSuite` reads `features/` from the forked test's working directory and the tier reaches it
   through `Test / envVars` or a system property.
6. `Acl.Authenticate` can read a cookie from the request context for the review pages (R12, R16);
   otherwise pages take the token as a query parameter on first visit and keep it in a hidden form
   field.
7. A `Parsed` event of a few hundred kilobytes is accepted by the journal and replays in reasonable
   time with snapshots (R3).
8. The `stage-run` entity's `history` of model usage is readable from the session memory entity
   (`forSessionMemory(id).history`) for a `MemoryProvider.none` interaction; if usage is not recorded
   without memory, record what the provider reports on the agent's reply instead.
9. The maximum step timeout a workflow accepts is at least 3 minutes (R4).
10. A consumer over several entities' events: one consumer reads one source, so `impact-trigger` is
    three consumers (`source`, `finding`, `claim`) starting the same workflow (table row stays one).

## Found during implementation

- 2026-10-16, R20 item 1: a declared query with `to_tsvector`, `websearch_to_tsquery` and `ts_rank_cd`
  passes ankka's statement check (`KeyedViewTestKit` runs it; `ChunkRowsSuite`). Whether Postgres
  runs it is proved by the retrieval suite in US6.
- 2026-10-16, R20 item 4: `AgentRuntime.withDefaultModel(provider, modelTimeout)` takes any
  `ModelProvider`; the replay and recording providers are plain implementations of its two methods
  (`ReplayModelProviderSuite`).
- 2026-10-16, R20 item 6: `Acl.Authenticate` reads any header through the request context, so the
  review pages' cookie needs no fallback (`Auth.cookie`).
- 2026-10-16: `AnkkaTestKit.eventually` is a method of a started kit, not of the companion; the
  fixtures call `testKit.eventually`.
- 2026-10-16: ankka's codecs encode a Scala 3 enum value, fields or not, as an object with the case
  name under `type` (`"status":{"type":"retracted"}`), and omit a field holding its default value.
  The wire strings in `Enumerations` are the case names; a view row that is queried by such a value
  stores it as a `String` (`SourceRow.status`), so the SQL reads `payload->>'status'`. API readers
  expect the object form. Two anonymous `given JsonValueCodec[Vector[_]]` in one object clash by
  synthesized name and must be named.
- 2026-10-16: a source id carries its identifier with `/` as `~` so it can travel in a path segment
  (`SourceRecord.sourceIdOf`); the data model's `src:<identifier>` is read with that rule.
- 2026-10-16: an ingestion workflow's `start` on a completed workflow replies the recorded state
  rather than fetching again, which is what the duplicate scenario needs; a changed source needs a
  new run id, left for US5's re-ingest route to decide.

- 2026-10-17: a paused workflow's lifecycle reports no `pendingStep`, so a reader that needs to know
  where a workflow waits reads the status the workflow records in its state (`awaiting-facet-review`).
- 2026-10-17: effort is a property of the Anthropic provider instance, not of one interaction, so
  every stage runs at the provider's effort (high); a cheaper model for search planning is a second
  provider the `search-agent` names with `effects.model`.
- 2026-10-17: the reference facet set makes `product`, `production approach`, `biological source` and
  `resources` mandatory and the other four preferred, so the question feature's background (six
  facets confirmed, `objective` and `success` not) can confirm the set.

- 2026-10-17: a path template's parameter must be a whole segment, so `/{id}:action` routes became
  `/{id}/action`; the colon form stays for actions on a collection (`/sources:ingest`).

- 2026-10-17: the extractor quotes by section key (`S<n>`), so every model call that must locate a
  quotation is shown the whole version's sections in the same order; the verifier alone is shown the
  cited and related sections, since it locates nothing.
- 2026-10-17: a verification is keyed in the replay tier by the record's content fingerprint, not
  its attempt number, so a fault and its correction each find their own cassette and a restored
  record finds the original's.

- 2026-10-17, deviation from T061: when extraction exhausts its retry budget the ingestion ends with
  the failure recorded on the run and the workflow state; the drafts are not kept as quarantined
  findings, because a draft whose quotations do not locate has no spans to keep. Revisit if a
  reviewer wants to see what the extractor proposed.
- 2026-10-17: `POST /faults:inject` and the named mutations (plus a test-only `condition_drop`) were
  built in US3, because the bounded-retry scenario needs a classified, retryable defect to arrive;
  US9 adds the suite that runs the six reference faults.

## Measurements

(empty until the evaluation run)

- **The investigation fan-out does not await the question workflows (US4).** Each question's
  investigation starts and then pauses, with no timeout, in a `waiting` step until records arrive or a
  reviewer asks for a sufficiency judgement, so a parent that awaited their end would hang. The
  parent starts each asynchronously, waits only for the start to be accepted (a `Conflict` means it
  already runs), and ends in `questions-investigating`. The question's own workflow carries the state.
- **`GET /questions/{id}` returns the question and its investigation.** The reply is a view
  `{"question": …, "investigation": …}` (status, assessments, sufficiency), not the bare question, so
  a reader sees where the investigation stands without a second call. Tests that assert on the
  question's own fields read the nested object.
- **Feature wording and enum spelling.** The glossary says "sufficient for the proposed next step";
  the closed list spells it `sufficient_for_proposed_next_step`. The step definition maps the one to
  the other in one place rather than renaming either.

- **Internal records: one source per CSV file (US5).** The data model's `internal:<system>:<id>`
  takes the file stem as the id; each row is a `Measurement` on that source with its own id
  (`<source identifier>:<row id>`), conditions, `recordedAt` and raw line, so the `measurement-rows`
  view is as fine-grained as rows while the entity count stays one per file, which is what
  `POST /sources:ingest-internal` promises ("one workflow per record file"). The file's identifier
  for the adapter is `<manifest path>::<file>`; `#` would be a URL fragment in the workflow's id.
- **What an adapter could not read reliably joins the parse.** A `SourceRecord` carries `issues`;
  the ingestion state keeps them from `fetch` and the `parse` step records them with the parser's
  own, downgrading `complete` to `partial`. A unit outside the table is `unit_invalid` naming the
  row; a value that is not a number is `value_invalid` and the row stays in the retained text.
- **A status change re-assesses eligibility in the same entity command.** `change-status` persists
  `StatusChanged` and then `EligibilityAssessed` for the latest version, computed by the pure rule
  set over the state as it will be; eligibility can therefore never disagree with the status, and
  `corrected` is surfaced as a note (`eligibility.corrected`), not a block. The impact consumer (US10)
  reacts to the same event.
- **Corrupt XML is a parse outcome, not an exception.** The parser answers `failed` with a `corrupt`
  issue (and `no_text` for structure without text); the version is quarantined with that reason and
  its content and hash stay recorded: nothing is dropped. Real JATS and PubMed XML carry a DOCTYPE,
  so the loader never fetches the DTD it names.
- **Crossref records a retraction on the notice.** The adapter fetches the work and then
  `works?filter=updates:<doi>`, reading `update-to` on the notices; PubMed and Europe PMC carry it on
  the article (`Retracted Publication`, `Retraction in`). PubMed and Crossref have no full text and
  so every record of theirs is `abstract_only`.

- **Retrieval runs on start; assessment and sufficiency stay explicit (US6).** A question's
  investigation now runs `plan` → `execute` → `eligibility` → `resolve` as soon as it starts and
  then rests at `retrieved`; `assess` (defaulting to the verified records retrieval collected) and
  `judge` are still asked for by the API, and in US7 by the request's packaging. Running them
  automatically would call the model for every question of every request; the explicit step keeps
  the cost and the cassettes bounded.
- **One execution per query and target, plus one fused execution for `local`.** Each lexical and
  semantic query is its own `search-execution` so the rankers can be measured apart (FR-048); the
  fused execution (`fused: true`, RRF with `k = 60`) is the one eligibility, resolution and
  assessment act on. All local executions of a round share the snapshot: every source version in the
  chunk view at that moment.
- **A rerun is the same engine on the recorded snapshot.** `POST /search-executions/{id}/rerun`
  runs the query again (the database's ranking for lexical, cosine for semantic) and keeps only the
  snapshot's source versions, so sources ingested since do not change the answer; `matches` compares
  the ordered chunk ids and versions. Rerun ids are `<execution>:r<n>` so they never collide with a
  later round's ids.
- **Retrieval in a workflow step reads the view client from `Configured.views`.** The workflow
  context offers the component client and service clients but no view client; `Main` and the test
  fixtures set it once the service has started. Requested of ankka in `notes/ankka-requests.md`.
- **External indexes are not searched in the replay tier.** An execution against `europepmc`,
  `pubmed` or `crossref` is recorded with the error "not searched in the replay tier" so the plan's
  targets are visible and nothing reaches the network; `file` and `internal` are local and run in
  both tiers. A plan that targets only `internal` with no internal directory returns nothing, which
  is how the coverage-gap scenarios are driven.
- **A coverage gap is opened by eligibility, and the supplemental search is linked and bounded.**
  Nothing eligible in the fused execution opens a `coverage` gap (escalation `supplemental_search`)
  whose description says it is not evidence of absence. A supplemental round runs the plan's queries
  plus its `expansions`, records its executions with the gap's id, counts once against the gap's
  budget through `record-supplemental`, and never opens a second gap. A judgement that escalates to
  a supplemental search starts one round itself; `POST /gaps/{id}/search` starts another and answers
  409 once the budget (two) is spent.
- **Chunk rows wait in tests.** The chunk view lags the source entity, and retrieval reads the view,
  so the fixtures' `ingest` helpers wait for a source's chunk rows before a scenario searches.

- **Packaging is asked for (US7).** The request's investigation pauses at `questions-investigating`
  after starting the question workflows; `POST /requests/{id}/packages` (the operator or a reviewer)
  moves it through `build-packages` → `candidates` → `await-decision`. Assessment and sufficiency stay
  explicit per question, so the caller decides when the evidence is judged enough to package; running
  packaging on its own would call the model for every question whether or not it was assessed.
- **What a package trims and what it refuses.** An assessment whose record is not verified or whose
  source is not eligible is refused (`excluded`, with the reason), never trimmed. Trims are for size
  and are logged: `not_applicable` assessments always, `background` ones beyond the first ten. A
  contradiction, a qualification or a mandatory facet is never trimmed, and a critical trim would make
  the package invalid (FR-034); the entity computes the status from the checks and its own trims.
- **The comparison is keyed by approach id and kept on the decision.** The model rates approaches by
  title; the gate maps titles to the minted ids (`<request>:c<n>`), refuses a comparison missing one
  of the eight criteria or an approach's rating, and the workflow state carries the comparison until
  the decision records it. The decision's one door, `Decisions.decide`, serves the API and the review
  page: it checks the reviewer, that every rejected approach has a reason, writes the `decision`
  entity once, marks the approaches, and resumes the workflow.
- **The no-probability guardrail is an output guardrail on the agent.** A reply asserting
  `<n>% chance|probability|likelihood` is refused before it is returned; the stage records
  `model_refused`, which is not retried, and the investigation pauses for the operator.

- **The design cites the verified claims of the packaged sources (US8).** A package carries the
  assessed records, which are findings; the design's background is grounded in claims, so the design
  step collects every verified claim of the sources behind the packaged records, hands the model
  their ids, and fills each background statement's spans from the claims it cites. A cited id that
  is not one of them is `unverified_claim_ref`, retried within the budget.
- **The workflow submits the draft it generates.** `design` generates a version and submits it as
  `operator`, so the investigation pauses at `awaiting-approval` with the version `under_review`;
  `POST /designs/{id}/submit` remains for a draft made another way. A new version supersedes every
  earlier version that is not approved; an approved version keeps its status and its content, and a
  later flag names it without changing it (SC-009).
- **One door for the reviewer's decision on a design.** `Approvals.record` serves the API and the
  review page: the entity checks the reviewer, the version's status and the rationale, then the
  investigation's `approval-recorded` command moves it to `close`, which ends it as `designed`
  (advancing the request), ends it as `design-rejected`, or runs `design` again with the requested
  changes in the prompt's context and the marker.
- **A rendering is a route format, not a stored artefact.** `GET /designs/{id}?format=md` renders
  the version on request from the record; the JSON form of the same route is answered as a
  pre-encoded body because one route cannot return two reply types.

- **A fault works on its own copy of the source (US9).** `POST /faults:inject` reproposes the record
  it is pointed at, so a fault on the shared fixture would leave every later scenario a rejected
  record. The file adapter therefore accepts `<file>@<alias>`, which ingests the same file as a
  distinct source (`src:file:paper-a@endpoint_swap`); the fault scenarios ingest one copy per fault,
  the recorded extraction and verification replies are copied under the alias (the verification
  marker is a fingerprint of the content, so only the id changes), and the five model-checked faults
  have their own authored replies. `failure_as_success` is classified by the verifier's negation
  check together with the record itself: an outcome that claims success while still negating the
  endpoint.
- **The evaluation has an offline half and a service-backed half.** The dataset's validity, the
  passages through the deterministic gate, the pins and the report need no service (`just
  eval-offline`); the runnable cases go through the service in the evaluation suite (`just eval`),
  which writes the same report with the case results. Cases whose model replies are not recorded
  are reported as not run, in the report's limitations; a reviewed failure becomes a case file through
  `POST /evals/cases:from-record`.
- **Mutants run in disposable copies, outside CI.** `scripts/mutants.sh` copies the tree, applies one
  patch, runs the independent suite there and writes `scripts/mutants/last-run.json`, which the
  living feature reads; a mutant counts as killed only when a test failed, never when the copy did
  not run. The first run caught a real defect the incremental build had hidden: a codec declared
  twice in one package.
- **Impact propagation (US10).** The `dependents` keyed view holds one edge per `(on, dependent)`
  from the `dependsOn` every record is proposed, judged, built or generated with, plus the sources
  behind cited spans. The `impact` workflow walks it breadth first from the changed record through
  any version (`of-any-version`), flags each dependent through its entity's `flag-for-review`
  (idempotent per changed record). A step either flags a bounded batch of the dependents already
  listed or lists the dependents of the next record, and journals that progress: in a store where
  many requests depend on one source, a walk that flagged everything in one step outran the step
  timeout and lost its progress on the retry. The view is one projection per source kind and is
  eventually consistent, so a walk sees the edges projected by the time it expands a record; a
  dependent made in the same second as the change can be missed, which `GET /impact` shows as
  unflagged. The tests wait for the dependents to be projected before changing the source. Its id carries the event's
  sequence number (`impact:<kind>:<id>:v<n>:s<seq>`) so a later change to the same version walks
  again while a redelivered event does not. A design is flagged by version; the approved version's
  content is never touched.

- **One Postgres for every suite of one `sbt testOnly` invocation.** The test kit starts one
  throwaway container per JVM run, so suites run after each other share its data: a later suite sees
  the earlier suites' requests, sources and flags. The suites are written to tolerate that (each
  request is new; the fixtures are idempotent; the impact walk covers whatever depends on Paper B),
  and a suite that alters the shared fixtures (`faults`) runs after the integration suites, as CI
  orders them. `/impact` walks the dependents view and reads each dependent's flags from its entity,
  one call at a time: a version that carried flags on the view's rows (a projection handler querying
  its own table) stalled the projection runtime, and one that read entities concurrently answered
  inconsistently; the sequential reads are the version that holds.

## Measurements (2026-10-17, replay tier)

- Living features: 58 scenarios, 58 passed, 0 ignored, through HTTP against the cassettes
  (`just features-replay`); about 2 minutes on an idle machine.
- Mutants (SC-013): `bypass-eligibility`, `remove-species-rule`, `accept-mismatched-span` each
  killed by exactly one independent test (`scripts/mutants/last-run.json`).
- Unit and independent suites: 110 tests; integration, faults and evaluation suites run against a
  throwaway Postgres. The live tier (`just features-live`, drift) and the baseline await a credential
  and a customer reviewer (T128, T129); the walkthrough script is written and not yet timed (T127); the
  kind deployment (T133) and the clean-checkout run (T134) are not done.
