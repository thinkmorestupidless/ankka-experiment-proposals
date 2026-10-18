# Implementation Plan: Evidence-Driven Scientific Experimental Design POC

**Branch**: `001-evidence-experiment-design` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-evidence-experiment-design/spec.md`

## Summary

Build the staged scientific investigation pipeline as one ankka service in Scala 3: every record the
spec names (customer request, facet, scientific question, source, finding, claim, assessment,
sufficiency, evidence package, candidate approach, decision record, experimental design, stage result)
is an event sourced entity whose journal is the append-only, versioned history FR-041 asks for; the
pipeline's two processes (ingestion and investigation) are workflows whose journalled steps give
resumability (FR-044) and whose pauses are where a reviewer decides; every model-dependent stage is an
agent handler returning a structured reply, called from a workflow step and never trusted until the
deterministic and source-context gates have run; listing and search are views over the entities,
including a keyed view of retrieval chunks that the lexical and semantic retrieval read; impact
propagation is a consumer that starts a workflow walking a dependents view. The living features under
`features/` run as a Gherkin suite through the service's HTTP routes in two tiers, against recorded
model replies and against the live model. Decisions and alternatives are in [research.md](research.md);
the component table below is the design the code is held to.

An earlier build of this specification exists in Python (`../experiment-design-poc/`). Its research,
data model and contracts were read as input; its storage, orchestration and testing decisions are
replaced here by ankka's, and its domain shapes are kept where they were platform-neutral.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (the family's line; the template built here on JDK 25 and
the plan pins 21 in CI), sbt 1.12.x, one sbt project created by `ankka init`

**Primary Dependencies**: ankka 0.12.0 (`ankka-sdk`, `ankka-runtime`, `ankka-http`, `ankka-agent`,
`ankka-telemetry-otlp`, `ankka-testkit`); jsoniter-scala through ankka's `Codecs`; `scala-xml` for
JATS; `java.net.http` for the source adapters; ONNX Runtime with the DJL Hugging Face tokenizer for
local embeddings (optional at runtime, a hash embedder in tests); munit

**Storage**: the service's own Postgres, provisioned by the platform or `docker-compose.yml` locally;
the journal holds every record and the full text of every fetched source; view tables hold the
listings, the retrieval chunks with their vectors, the measurements and the dependency links; no
pgvector and no second store; fixtures, cassettes, evaluation cases and reports under `evals/`

**Testing**: unit test kits for entities, consumers and the keyed views; `AnkkaTestKit` integration
suites with `TestModelProvider` for workflows, agents and routes, with restarts to prove durability;
`GherkinSuite("features")` running the 58 living scenarios through HTTP in the `replay` tier
(recorded replies) and the `live` tier (the model); an evaluation suite over the labelled dataset; an
independent suite written from the spec; a mutation script for SC-013; the `speckit-bdd` checker

**Target Platform**: a developer's macOS or Linux machine (`sbt run` beside Docker Postgres, watched
in `ankka local console`); deployable unchanged to a local kind installation of ankka with
`ankka services apply` as the demonstration host

**Project Type**: a single ankka service exposing an HTTP API for the operator and the tests, and a
minimal set of server-rendered review pages for the reviewer

**Performance Goals**: a full replay walkthrough on the reference fixtures in under 10 minutes; a live
walkthrough bounded by model latency, with model-calling steps given 3-minute timeouts; lexical and
semantic retrieval over thousands of chunks in under a second

**Constraints**: no unbounded autonomous loops (every model call is one agent handler inside a
workflow step with a timeout and a bounded, defect-classified retry); nothing a model produced is
admitted without the deterministic and source-context gates; no in-place edit of a stored record (a
change is a new event, a new version); no quantitative quality thresholds in code; retained full text
and internal material never leave the installation; every reviewer action names a reviewer from the
configured list; a feature scenario passes only in both tiers; ankka's own rules (handlers return
effects, wire names and manifests are protocol, one database per service, consumers tolerate repeats)

**Scale/Scope**: one reference investigation; a literature collection in the tens to low hundreds of
sources; one internal dataset; 20 to 30 end-to-end evaluation cases and 30 to 50 extraction
passages; one reviewer; six weeks

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, as in every repo of the family. Following
the family's convention, this plan holds itself to the family's principles (`ankka-brain/principles.md`)
where they apply to an application, and to the source document's design principles, which the spec
carries as requirements. Each is a gate checked against the Phase 1 design.

| Gate | Status | Where satisfied |
|---|---|---|
| Effects are inert data; the runtime interprets them (family 3) | pass | No entity handler does I/O; adapters and model calls live in workflow steps and agent handlers; `data-model.md` lists each entity's commands as pure decisions over its state |
| Explicit registration, never scanning (family 4) | pass | `Main.scala` registers every component; `contracts/stages.md` lists the inventory |
| Be your own customer; what ankka cannot do becomes a request (family 6) | pass | `research.md` R20 keeps the list; `notes/ankka-requests.md` is created at the first entry |
| The honest not-implemented list (family 8) | pass | `docs/limitations.md` is a deliverable (PDF parsing, pgvector, judgments, graph projection) |
| Same conventions across repos (family 10) | pass | scalafmt from ankka, pre-commit hook, serialised tests, thin Justfile, spec-kit with the bdd extension |
| Typed does not mean true: structural validity and source-context verification are separate gates (source) | pass | `verification` workflow: `validate` step (deterministic) then `verify` step (agent), `data-model.md` gates table |
| Relevance, eligibility, applicability and sufficiency recorded independently (source) | pass | `search-execution` exclusions, `source` eligibility events, `assessment`, `sufficiency` are separate records |
| No unsupported upgrades: species, endpoint, failure as success (source) | pass | `rules` package (`applicability@v1`, verifier checks), fault-injection suite |
| Bounded explicit workflows, no autonomous search loops (source) | pass | workflows with step timeouts; retries counted in workflow state against a budget; supplemental search budget on the `gap` entity |
| Original material retained; downstream never relies on summaries alone (source) | pass | the verifier is given section text from the `source` entity, not the extractor's prose; packages carry span ids resolved to text |
| Failures are data (source) | pass | rejected and quarantined statuses are events; every attempt is a `stage-run` event |
| Humans approve scientific and operational decisions (source) | pass | facet review, decision record and approval are entity commands that require a configured reviewer; the investigation workflow pauses with no timeout |
| Only measured thresholds (source) | pass | `evals/baseline.json` written after the first measured run; nothing in code compares to a threshold |
| One installation, no separate graph store (spec, Out of Scope) | pass | one service, one database; graph projection is a later `GraphConsumer`, not built |

Post-design re-check: no violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-evidence-experiment-design/
├── plan.md                      # This file
├── research.md                  # Phase 0: decisions R1 to R20
├── data-model.md                # Phase 1: entities, events, views, workflows, gates
├── quickstart.md                # Phase 1: validation walkthrough
├── contracts/
│   ├── http-api.md              # routes, bodies, statuses, review pages
│   ├── stages.md                # workflows, steps, agent handlers, prompts, cassettes, rule sets, adapters
│   └── features-and-tiers.md    # how the living features run, and the two tiers
├── checklists/requirements.md   # spec quality checklist
└── tasks.md                     # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
build.sbt                        # ankka 0.12.0, serialised tests, scalafmt, the schema task
service.json                     # the descriptor; env names (no values) for the token, reviewers, model
docker-compose.yml               # Postgres for running locally
Justfile                         # thin: test-unit, test-integration, features-replay, features-live, eval, check-bdd, mutants, run
.scalafmt.conf, .githooks/       # the family's formatting and hook
features/<area>/*.feature        # the living features (unchanged)
GLOSSARY.md                      # the vocabulary
evals/
├── fixtures/papers/             # Papers A, B, C and the retracted paper, as JATS XML with .meta.json
├── fixtures/internal/           # the internal dataset: manifest.json and CSV files
├── cases/*.json                 # end-to-end labelled cases
├── passages/*.json              # focused extraction passages
├── cassettes/<stage>@<v>/*.json # recorded or authored model replies for the replay tier
├── reports/                     # evaluation reports (ignored)
└── baseline.json                # the agreed baseline, once measured
scripts/
├── walkthrough.sh               # the eleven-step demonstration over the HTTP API
├── mutants.sh, mutants/*.patch  # SC-013: three defects applied to a disposable copy
└── cassettes/                   # authoring helpers that check quotations against fixtures
docs/limitations.md              # what the POC does not do
notes/ankka-requests.md          # what ankka could not do and what would remove it
src/main/resources/
├── application.conf, logback.xml
└── prompts/<stage>/<version>.md # versioned prompts; never edited in place
src/main/scala/com/thinkmorestupidless/experimentproposals/
├── Main.scala                   # registration: the complete inventory
├── config/                      # Settings: every EDP_* variable and the tier
├── domain/                      # plain data and rules: records, events, enumerations, transitions
│   ├── request/  facets, round-trip check, questions
│   ├── sources/  source, section, span, chunk, measurement, eligibility
│   ├── search/   search plan, execution, hit, exclusion
│   ├── evidence/ finding, claim, verification, defect, assessment, sufficiency, gap, package
│   ├── design/   approach, comparison, decision, design, approval, rendering
│   └── provenance/ ref, link, stage result, reviewers
├── rules/                       # deterministic gates and rule sets, each with an id and a version
├── application/
│   ├── entities/                # one file per event sourced entity
│   ├── views/                   # listing views, chunk-rows and measurement-rows keyed views, dependents
│   ├── consumers/               # verification-trigger, impact-trigger
│   ├── workflows/               # ingestion, verification, investigation, question-investigation, impact
│   └── agents/                  # request-agent, search-agent, evidence-agent, design-agent; prompts loader
├── retrieval/                   # lexical and semantic search, fusion, context resolution, embedder
├── adapters/                    # europepmc, pubmed, crossref, file, internal; JATS and CSV parsing
├── evaluation/                  # dataset loading, metrics per stage, report, baseline compare
├── replay/                      # ReplayModelProvider, RecordingModelProvider, cassette store
└── api/                         # ApiEndpoint (/api/v1), ReviewEndpoint (/review), HealthEndpoint; pages/ renders Html
src/test/scala/com/thinkmorestupidless/experimentproposals/
├── unit/                        # entity, rule, renderer, adapter-parsing and keyed-view tests
├── integration/                 # workflows, agents with TestModelProvider, routes, restarts
├── features/                    # FeatureSuite (GherkinSuite) and step definitions by area
├── independent/                 # tests written from the spec, not the prompts
├── faults/                      # fault injection
└── evaluation/                  # the evaluation run as a suite
```

**Structure Decision**: one ankka service, one sbt project. The `domain` package holds no ankka types,
as the template's does; `application` holds the components; `api` is the edge. A split into sbt
modules (as ankka-reasoning has) is not taken: nothing here is published as a library.

## The component table

One row per rule, process or question, as `ankka-design` requires. The last column records the close
alternative where there was one; code implements each row as the component it names, and a change
of component changes this table first.

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| The customer request is stored unchanged and retrievable from every artefact; the round-trip check is recorded against a facet set (FR-001, FR-003) | `request` event sourced entity, id = request id | The text is written once in `Submitted` and never changed; the facet ids and question ids it holds are bounded (eight and six); the round-trip check is a fact about the set | A key value entity: the check and the facet set are history other components and the reviewer read, and a key value entity keeps only the latest value |
| A facet's review transitions, reviewer identity and prior states (FR-002, FR-004, FR-004a) | `facet` event sourced entity, one per facet, id = `<request>:f<n>` | The transition rule and the reviewer check are about one facet; the entity's journal is the history the feature asks to remain visible | Facets inside the `request` entity: every review would queue behind every other on the request, and the request's state would grow with every review |
| A scientific question links to facets and has a status that only sufficiency and a reviewer may advance (FR-005 to FR-007) | `question` event sourced entity, id = `<request>:q<n>` | The status rule is about one question; assessments and plans address it by id | Questions inside the request: the same growth and queueing as facets |
| A source's identity, versions, licence, status, parse and quarantine; a span resolves against a version's canonical text (FR-008, FR-009, FR-012 to FR-014) | `source` event sourced entity, id from the primary identifier | Duplicate detection is a rule about one source (same hash, no event); versions are append-only events; a status change is the event impact propagation reacts to; `resolveSpan` is a query over its own sections | A key value entity: versions must be kept, and nothing could react to a status change. A `section` entity per section: nothing reviews a section on its own, and a span needs one lookup, not many |
| Retrieval chunks queryable by words and by vector (FR-015 to FR-017) | `chunk-rows` keyed view over `source` events, one row per chunk | A view answers "which chunks match"; a keyed view names its rows, so one `Parsed` event writes every chunk of a version; the declared `lexical` query is Postgres full-text search over the row text | A `chunk` entity per chunk: thousands of commands per document and no rule a chunk enforces. A plain view over `source`: one row per source, not per chunk |
| Internal measurements queryable by value and unit, not only embedded (FR-010, FR-011) | `measurement-rows` keyed view over `source` events | One row per measurement, typed value and unit, queried by rule | Only the chunk text: FR-011 forbids a semantic index being the sole representation |
| A finding or claim is proposed, validated, verified, reviewed, retried within a budget or quarantined, never edited (FR-020 to FR-027, FR-046) | `finding` and `claim` event sourced entities, one per record | The status transitions and the retry budget are rules about one record; each attempt is an event, so history is kept and a retry is a new attempt on the same id | One `extraction` entity per source holding every finding: a review of one finding would wait on every other and the state would grow with the source. A key value entity: the attempt history FR-026 requires would be gone |
| Validation then verification, with a classified, bounded retry that re-verifies against the source (FR-022, FR-023, FR-026) | `verification` workflow, id = `<record>:a<attempt>`, started by the `verification-trigger` consumer over `Proposed` and `Reproposed` events | The process has steps that must each complete and be journalled: deterministic validation, the verifier agent, recording the result, deciding the retry; a consumer starting it under a derived id makes a redelivered event address the same run | A step inside ingestion only: a fault injection, a reviewer-authored claim and a retry also need it. The agent writing to the entity directly: no journal of the attempt and the retry rule would live in a handler |
| An assessment of one finding or claim against one question, with the species rule (FR-028, FR-029) | `assessment` event sourced entity, id = `<question>:<record>` | The rule result and the review state are about one assessment; one finding is assessed separately for each question (edge case) | Assessments inside the question: the question's state would grow with the evidence |
| Sufficiency for a named decision; a knowledge gap with a bounded supplemental budget (FR-031, FR-032) | `sufficiency` event sourced entity, id = `<question>:<decision>`; `gap` event sourced entity, id = `<question>:g<n>` | Sufficiency is one judgement per decision, versioned; the supplemental search budget is a rule about one gap, and a supplemental execution links to the gap | Gaps inside sufficiency: the budget rule would be checked across a list, and a supplemental search could not address a gap by id |
| A reproducible search plan per question; each execution recorded whole and re-runnable; exclusions after ranking (FR-015, FR-016, FR-018, FR-019) | `search-plan` event sourced entity, id = `<question>:plan`; `search-execution` event sourced entity, id = `<plan>:e<n>`, written once | A plan is versioned; an execution is immutable and addressed by id by reruns and gaps | Executions as events of the plan: supplemental searches make the list unbounded and a rerun names an execution, not a plan |
| A versioned, validated evidence package with trims and exclusions (FR-033 to FR-035) | `package` event sourced entity, id = `<question>:pk<n>` | The six package checks and the trim log are facts about one package version | Computing the package on demand: FR-033 requires it versioned and its validation recorded |
| Candidate approaches, their comparison, and a decision record (FR-036, FR-037) | `approach` event sourced entity per candidate, id = `<request>:c<n>`; `decision` event sourced entity, id = `<request>:d<n>`, written once | Review status is per approach; the decision is a reviewer's record that names selected and rejected with reasons and is never changed | One `investigation` entity holding the candidates: workable at two to four, but a reviewer rejecting one would rewrite the set's state |
| A typed, versioned design with an approval per version; an approved version never changes (FR-038 to FR-040) | `design` event sourced entity per design lineage, id = `<request>:design`; each version a `DraftGenerated` event; `Approved`, `Rejected`, `ChangesRequested` name the version | The transition rule is about one design; the journal keeps every version and decision; a flag names the version it concerns | One entity per version: the approval history would be spread over ids and "the latest" would need a view |
| A stage result for every stage execution, with inputs, output, validations, model calls and status (FR-043) | `stage-run` event sourced entity, id = run id | Provenance is a record of its own, read by the operator and the evaluation; a workflow's state is the process's memory, not its audit | The workflow's own state: a view could read its standing, but not each attempt's model calls and validations |
| Every link names the versions it joins; "every dependent of this version" (FR-042, FR-045) | `dependents` keyed view over the events of finding, claim, assessment, sufficiency, package, approach and design, one row per (dependency, dependent) | Each derived record's `Proposed` event carries `dependsOn`; the view answers the question whose answer is *which* ids | A `link` entity per link: thousands of writes and no rule a link enforces. Walking entities: the caller does not hold the dependents' ids |
| A changed source status or finding status flags every dependent, without rewriting it (FR-045, SC-009) | `impact-trigger` consumer over `source`, `finding` and `claim` events, starting an `impact` workflow, id = `impact:<ref>`, whose step walks `dependents` and sends `flagForReview(changedRef)` to each | The walk is multi-step and must complete after a crash; the flag is a command the dependent's entity accepts once per changed ref, so a repeated step is harmless | The consumer flagging directly: a crash mid-walk re-flags from the start with no record of how far it got, and nothing answers "is the propagation done" |
| Fetch, record, parse, chunk, judge eligibility, extract and verify a source (FR-008 to FR-014, FR-020 to FR-024) | `ingestion` workflow, id = `ingest:<adapter>:<identifier>` | Minutes long, touches the adapters and several entities, must resume without duplicating records: each step checks the `source` entity before writing | Doing it inline in an endpoint: the gateway bounds a response at fifteen seconds and nothing resumes. A chain of consumers: nothing answers how far an ingestion has got |
| From request to approved design, pausing for the reviewer (FR-002 to FR-007, FR-031, FR-036 to FR-040) | `investigation` workflow, id = request id; per question a `question-investigation` workflow, id = question id, fanned out with `invokeAsync` and awaited | The sequence is fixed and bounded; pauses with no timeout are where a reviewer confirms facets, decides between candidates and approves a design; per-question work in its own workflow keeps each step to one question's model calls and runs the six in parallel | One workflow for all questions: six questions' searches and assessments in one step exceed any step timeout and run serially. An autonomous agent: the spec forbids an open-ended loop, and the steps are known |
| Facet extraction, round-trip check, question derivation (model-dependent) | `request-agent`, handlers `extract-facets`, `round-trip`, `derive-questions`, `MemoryProvider.none`, `thenReplyAs` | Each is one model interaction producing a structured reply; the request text goes in `withContext`, so no prompt assembly leaks into memory | One agent for every stage: ten handlers with contradictory instructions and different models. Calling a `ModelProvider` from a step: loses guardrails, token accounting and the console's record for nothing |
| Search planning (model-dependent) | `search-agent`, handler `plan`, a cheaper model at low effort | Query expansion is cheap work; a separate agent lets it name its own model | Inside `request-agent`: a different model and effort per handler is possible, but the instructions differ in kind |
| Extraction, verification, assessment, sufficiency (model-dependent) | `evidence-agent`, handlers `extract`, `verify`, `assess`, `judge-sufficiency`, each at high effort; the verifier is given section text, never the extractor's output | Four interactions over source text with structured replies; separate handlers keep separate prompt versions | Judgments (a System One model) for relationship and applicability: they answer a choice without the rationale FR-028 requires, and the provider is in early access. Recorded in `docs/limitations.md` as a later experiment |
| Candidate generation and design generation (model-dependent) | `design-agent`, handlers `candidates`, `design`; an output guardrail refusing an asserted success probability | Long structured replies; a guardrail is the right place for a pattern that must never be stored | Streaming to the reviewer: the workflow awaits the reply and the reviewer reads the record; nothing watches tokens |
| Deterministic gates and rule sets (FR-022, FR-029, FR-034, FR-039) | plain Scala in `rules/`, each rule with an id and a version | Pure functions over records; no state, subscription, schedule or route | |
| Reviewer identity (FR-004a) | `Reviewers`, a value loaded once at start from the environment, consulted by entity commands and by the endpoints | A pure in-memory set; an entity command that names an unlisted reviewer is refused `BadRequest` and persists nothing; the endpoint refuses early with the same status | An entity holding the list: nothing changes it at runtime in the POC, and a static list has no history to keep |
| A reviewer who is unavailable leaves records waiting; nothing is promoted on a timeout (edge case) | no timer; the investigation workflow's pauses have no `onTimeout` | The spec forbids auto-promotion | A timer that escalates: not asked for |
| Operators and tests call the service; reviewers use pages; a health probe needs no token | `api` endpoint `/api/v1` with `Acl.Authenticate` (bearer token from the environment, principal = `operator`); `review` endpoint `/review` serving HTML with the same token carried in a cookie; `health` endpoint `/healthz` with `Acl.AllowAll`, read-only | Each endpoint has one ACL, so each audience gets one | One endpoint: the health route would need the token or the API would be open |
| Where the service boundary goes | one service | Everything must be consistent together and one person operates it; splitting ingestion out later means publishing stable messages on a topic, which is cheap once load justifies it | An ingestion service beside an investigation service: no component client across them, and nothing in the POC scales differently |
| Graph projection (source document's later extension) | none built; a `GraphConsumer` over the derived entities is the shape when it comes | Out of scope by the spec | |

Decisions the table implies:

- **A workflow step never trusts an agent's reply.** It passes the reply through the deterministic
  gate, writes the record as `proposed`, and lets the `verification` workflow decide what it becomes.
- **Every step is safe to run twice.** Each step reads the target entity first and skips work already
  recorded (a `stage-run` event with the same stage, version and input hash); entity commands refuse
  or ignore a duplicate.
- **Ids are derived, never random.** Facets, questions, assessments, executions and workflows take ids
  from what they derive from, so a retried step or a redelivered event addresses the record that
  already exists.
- **The reviewer's name is data; the token is who may call.** The ACL admits the shared token; the
  configured list decides which names are valid; the record keeps the name.

## Phases

- **Phase 0** (`research.md`): the platform decision, persistence, model access and replay, embeddings
  and retrieval without pgvector, adapters, parsing, orchestration, verification, the review pages,
  rendering, observability, testing and the two tiers, access control, family conventions, resolved
  open items, and the list to verify at implementation.
- **Phase 1** (`data-model.md`, `contracts/`, `quickstart.md`): every entity with its id scheme, events,
  state, commands and transitions; the views and their queries; the workflows' states and steps; the
  gates; the HTTP routes; the agent handlers, prompts and cassettes; how the features run.
- **Phase 2** (`/speckit-tasks`): tasks by user story, P1 stories first, with the foundational phase
  carrying the entities, the gates, the agents' scaffolding, the replay provider and the feature suite.

## Complexity Tracking

No constitution violations to justify.
