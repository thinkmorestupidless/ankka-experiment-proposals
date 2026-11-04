# Research: A chat and a canvas for experiment proposals

Decisions for feature 002, numbered, each with its rationale and the alternatives weighed. Read
with feature 001's `research.md`: nothing decided there is reopened here. "Verify at
implementation" lists what a task must check before relying on it; "Found during implementation"
is filled as the build goes.

## R1. The automation lives in the two workflows that already run the stages

**Decision**: `QuestionInvestigationWorkflow` gains three steps after `resolve`:
`await-verification`, `assess-all`, `judge-all`, and transitions to them instead of resting. Its
last step sends `question-reported(questionId)` to `InvestigationWorkflow`, which records the id and
transitions to `build-packages` once every question in its state has reported; `build-packages` and
`candidates` then run as they do for the operator's `package` command, with the actor
`system:investigation`. The existing manual commands (`plan`, `execute`, `supplemental`, `assess`,
`judge`, `package`) stay and are accepted from every rest the automation adds, each recording a
further stage run.

**Rationale**: the stages are already steps with declared timeouts, retries and defect
classification; `thenTransitionTo` between them reuses every rule feature 001 proved, including
the pause on a failed stage (`record-failure`) and the resumption without duplicates (idempotent
record ids). A command is how a paused parent moves; a set of reported ids makes a repeated report
harmless.

**Alternatives**: a consumer on verification events (fires per record, never knows the set is
complete, no retry budget); the parent awaiting each question's end (the question workflow must
not end, because the manual routes keep working on it afterwards); a separate orchestration
workflow (a third place that knows the stage order).

## R2. Waiting for verification is bounded by awaiting the verification workflows

**Decision**: `await-verification` lists the records the search retrieved whose status is still
`proposed`, and awaits the end of each record's verification workflow with the bound remaining
(`EDP_VERIFICATION_BOUND`, default 3 minutes; the step's timeout is the bound plus a margin). A
record whose workflow ended but whose status is still `proposed`, or whose wait timed out, is
unsettled; if any remains, the step fails the stage with the defect `verification unsettled`
(classified `transient`, retried within the budget, then `record-failure` pauses the investigation
as for any failed stage). Settled is `verified`, `rejected`, `quarantined` or `requires_review`;
only `verified` records that passed eligibility are assessed. With no eligible verified record the
workflow skips `assess-all` and goes to `judge-all`, so an empty corpus still reaches sufficiency,
which abstains.

**Rationale**: the platform's rule is to await a workflow's end, not poll; the verification
workflows exist per record and are started by the triggers feature 001 has. The bound is the
spec's (FR-008a).

**Alternatives**: `thenPause(after, onTimeout)` in a loop with a count (polling in disguise); a
timer (for things that are not workflows); assessing whatever is verified at the moment (assesses
too few, non-deterministically).

## R3. The console's treatment is imported, not copied; the shell is hosted, the bar and rail are drawn

**Decision**: `web/` depends on `ankka-console@0.15.1` and imports `ankka-console/styles.css`,
which carries the tokens, the backdrop, the glass surfaces, the rail and inspector columns, the
shape, part and edge classes of the topology figure, and Inter from the package's own `fonts/`, so
the font is served from the interface's origin. The interface renders `Shell` (theme `dark`) and
`Backdrop` from the package's index and draws its own bar, rail and inspector with the package's
classes (`ac-bar`, `ac-rail`, `ac-rail-item`, `ac-inspector`, `ac-page`, `ac-body`, `ac-card`,
`ac-part*`, `ac-edge`), because the package's `Bar` and `Rail` read the console's page data
through React Router's `useMatches` and the console's areas. The package's peers (`react-router`,
`@react-router/dev`) are installed; the interface uses React Router 8 in library mode for its own
routes. Licences: React, Vite, React Router, lucide-react, playwright-bdd, Playwright: MIT;
axe-core: MPL-2.0; `ankka-console`: the family's. None is BSL.

**Rationale**: the stylesheet is the treatment (spec 035's tokens, radii, blur and contrast at the
glow's peak) and importing it keeps the interface on the console's line when the package moves;
the two components hosted are two elements each, and hosting them proves the package is hostable.

**Alternatives**: copying the token values (the spec's fallback; taken only if importing the
package's index outside the console fails, see Verify at implementation); hosting `Bar` and
`Rail` (requires the console's loaders and areas); Tailwind with `ankka-console/theme.css` (a second
styling system for no gain).

## R4. The interface is a web-hosted service with the backend mounted under `/backend`

**Decision**: `web/service.json` names `hosting: web`, `processPort: 3000`, and one mount
`{ "path": "/backend", "service": "ankka-experiment-proposals" }`. The browser calls
`/backend/api/v1/...` and the proxy passes `/api/v1/...` to the service, so the service's routes are
unchanged and the interface needs no cross-origin request. The service's ACL (`Auth.acl`, the
shared token) admits the request as it admits any other: it checks a token, not a caller. The
interface's own server serves the built app and nothing else; it makes no call at the calling
address. Locally, `ankka local web --service ankka-experiment-proposals=http://127.0.0.1:9000 --
npm run dev` gives the same shape against the compose profile `app` or `sbt run`.

**Rationale**: the platform's documented shape for a user interface; the mount strips its path,
so the prefix must be one the service does not use.

**Alternatives**: a Vite dev proxy (differs from deployment); the interface's server forwarding
requests at the calling address (a second hop and a copy of every route).

## R5. Admission: the bearer token on every fetch, kept by the browser

**Decision**: the interface asks for the shared token once, keeps it in `localStorage` under
`edp.token`, and sends `Authorization: Bearer <token>` on every request to `/backend`, including
the two streams, which are read with `fetch` and a `ReadableStream` parser of `text/event-stream`
rather than `EventSource`. A `401` clears nothing and shows "the token was refused" with the field
again; a `401` on any later request returns to the admission screen. The token is never put in a
URL.

**Rationale**: `EventSource` cannot carry a header; the review pages' cookie is scoped to
`/review` on the service's origin, which is not the interface's. A second admission mechanism on
the service is out of scope (sign-in is a later feature).

**Alternatives**: a cookie set by the interface's server (the server never sees mounted
requests); a `?token=` query on the stream routes (a token in a URL is logged).

## R6. The journey is derived on the server and streamed

**Decision**: `domain/journey/Journey.scala` is a pure derivation from `InvestigationState`, the
investigation workflow's lifecycle, each `QuestionInvestigationState` and lifecycle, and the run's
`StageRun`, into a `Journey` of stage nodes in a fixed order with a state each (`unreached`,
`reached`, `current`, `paused`, `failed`), the question lanes, and for each node the stage runs it
is derived from (`contracts/journey.md`). `GET /requests/{id}/journey` returns it;
`GET /requests/{id}/journey:watch` streams it as server-sent events: the journey now, then a new
`journey` event whenever the derivation changes, triggered by the `stage-rows` watch on the run's
row (watchable query `by-run`, selecting `row_key`) and by a re-read every 2 seconds while the
stream is open, so a pause, which records no stage run, is seen within the bound. Equal
derivations are not re-sent. The assistant's `get_request` tool returns the same derivation.

**Rationale**: FR-001 says the journey is derived, never stored, and the derivation belongs in one
place; a workflow's state cannot be watched, a stage run can, and a 2 s re-read of two in-process
entity queries per open stream is cheap and bounded by the number of open streams.

**Alternatives**: the browser polling (the spec wants the service's notification); a view over a
journey entity (a stored copy of derived state); a consumer that re-derives on every event into a
row (cannot see workflow pauses either).

## R7. The conversation is an entity; the session memory is the model's

**Decision**: `ConversationEntity`, event sourced, id `request:<requestId>` for a request's
conversation and `free:<key>` for the free-standing one (`key` a random id the browser keeps in
`localStorage`). Events: `Opened(subject, budget, at)`, `Answered(question, answer, references,
tokensUsedTotal, at)`, `Proposed(n, action, arguments, at)`, `Performed(n, result reference, by, at)`,
`Declined(n, at)`. The agent's session id is the conversation id, so the model's history is the
runtime's session memory; the entity holds what the interface shows and what the rules need. A turn
is recorded only when its answer completed (`Answered` at the end of the stream), so a failed
answer records nothing, as FR-018 says; the interface keeps the failed turn on screen, marked, until
the page reloads.

**Rationale**: the budget is a rule about one thing with an id; references and proposals are
typed product data the session memory does not hold; `Answered` as one event keeps "nothing
recorded" literal.

**Alternatives**: the session memory alone (no typed references, no proposals, no rule); a key
value entity (loses the history the spec asks a person to return to).

## R8. The budget is the session's token total against a configured number

**Decision**: `EDP_CONVERSATION_BUDGET` (tokens, default 200000) is read into `Configured` and
recorded on `Opened`. Before each turn the endpoint sends `ask` to the entity, which refuses with
`Conflict` and the message `the conversation's budget is reached: <used> of <budget> tokens` when
`tokensUsedTotal >= budget`; the endpoint answers `409`. After each completed stream the endpoint
reads the session's total usage from `forSessionMemory(id).history` and records it on `Answered`.
`GET /conversations/{id}` returns `budget` and `used`. An action's own stages (an ingestion's
extraction, a fault's verification) run under the pipeline's session ids and are not counted.

**Rationale**: the runtime counts and does not cap; the service must count in an entity of its own
(designing-agents, "Failure and cost"); the session's total is the authoritative figure.

**Alternatives**: counting characters (wrong units); a per-turn `maxTokens` only (no total);
refusing in a tool (the refusal must come before the model is called).

## R9. References are typed markers in the streamed text

**Decision**: the assistant cites a record by writing `[[<kind>:<id>]]` or `[[<kind>:<id>@<version>]]`
inline, where `kind` is one of the sixteen record kinds (`contracts/assistant.md`) and `id` is the
identifier the tools returned. The interface parses markers as the text streams and renders each
as the record's card, fetched from the backend; the service parses the completed text, resolves each
marker (a `GET` of the record), and records on `Answered` the references with `resolved: true|false`;
an unresolved one is shown as unresolved and no card is fetched for it (the `done` event carries the
list, so the interface corrects a card it could not fetch). Markers are the only citation form; the
instructions forbid pasting a record's content in place of a marker.

**Rationale**: a structured reply would end streaming; a second extraction call doubles cost; a
marker is a typed reference the scripted model can emit verbatim and a test can assert on.

**Alternatives**: `thenReplyAs[Answer]` with a references field (no streaming); tool calls that
"show" a card (a tool the model may call twice, and a card shown without being cited).

## R10. Proposals are recorded by a tool and performed by a confirmation call

**Decision**: one tool, `propose_action(action, arguments)`, validates `action` against the four
allowed (`submit-request`, `ingest-source`, `inject-fault`, `set-source-status`) and the arguments
against each action's schema (`domain/conversation/Actions.scala`), records `Proposed(n, …)` on
the conversation, and returns `proposed as #n; the person must confirm it` to the model. The reply
tells the person what will be done. `POST /conversations/{id}/proposals/{n}:confirm` performs it
by calling the same code the API route calls (the ingestion workflow's start, the request entity's
submit, the fault injection, the source status command), records `Performed(n, reference)` with the
result's reference, and answers the reference; the interface shows the result's card. A proposal
confirmed twice is refused (`Conflict`); a proposal the person declines records `Declined`. A
`submit-request` proposal carries the text verbatim.

**Rationale**: the model never performs; a tool may be called twice, and a proposal is harmless
to repeat (it is a new number); the confirmation is an act only a person can make.

**Alternatives**: four tools that perform after the model "asked" (nothing stops a second call);
a workflow pausing for confirmation (an agent handler cannot wait).

## R11. The assistant's tools: eight that read, one that proposes

**Decision**: `list_requests`, `get_request` (request, facets with round-trip, questions, journey),
`get_question` (plan, executions with exclusions, gaps, assessments, sufficiency), `get_record`
(finding or claim with resolved spans and verification), `get_evidence` (packages, approaches,
comparison, decision), `get_design` (design with approval history, impact flags), `list_sources`
(sources with versions and status), `review_queue`, `propose_action`. Each returns the record as
the API returns it (same codecs), so the ids the model cites are the ids the cards fetch.
`maxToolCallSteps` 12. A request's conversation passes its subject as `withContext` and the tools
default their `requestId` to it.

**Rationale**: a model chooses among tools by reading their descriptions every turn; nine with
distinct intentions is within the "ten or so" line; returning the API's records keeps citation
honest.

**Alternatives**: one tool per route (over twenty); one `query` tool with an operation name (the
anti-pattern named in the design guide).

## R12. The assistant's model: live whenever a key is present, separate from the pipeline's

**Decision**: `Configured.assistantModel: Option[ModelProvider]`. `Main` sets it to an
`AnthropicProvider` for `EDP_ASSISTANT_MODEL` (default: `EDP_MODEL`) when `ANTHROPIC_API_KEY` is
set, in either tier; to a `ScriptedModelProvider` when `EDP_ASSISTANT_SCRIPT` names a file; to
`None` otherwise. The handler names it with `effects.model(provider)`; with `None` the endpoint
answers `503` with `the assistant has no model: set ANTHROPIC_API_KEY (or EDP_ASSISTANT_SCRIPT)`
and `GET /assistant` reports `available: false` with the same reason, which the conversation panel
shows. The pipeline's default provider is unchanged (`ModelProviders.forTier`). Suites set
`Configured.assistantModel` to a second `TestModelProvider`.

**Rationale**: one provider per consumer; the replay tier must stay free for the stages while the
assistant answers live; a scripted file makes the browser suites and a keyless demonstration
deterministic.

**Alternatives**: the assistant on the replay provider (its cassettes key on stage markers, not on
free questions); recording the assistant (out of scope).

## R13. The scripted assistant provider

**Decision**: `replay/ScriptedModelProvider.scala` reads a JSON file: an array of rules, each
`{"when": "<substring of the last user message>", "tools": [{"name": …, "arguments": {…}}],
"text": "…"}`; the first rule whose substring matches answers; a rule with `tools` is a tool-calling
turn followed by the `text` turn; no match answers a fixed line saying the script has no answer. The
file for the browser suites and the demonstration is `evals/assistant/walkthrough.json`, with the
answers of SC-005 (markers to the reference request's records by their deterministic ids).

**Rationale**: the browser suites run against a real process where `TestModelProvider` cannot be
injected; the sidecar's `ANKKA_MODEL_SCRIPT` is the precedent.

**Alternatives**: driving the browser suites against the live model (non-deterministic, costs);
mocking `/backend` in the browser (would not test the service).

## R14. The journey's canvas is a fixed layout, not a graph library

**Decision**: the canvas draws the request's spine (request, facets, round-trip, facet review,
questions, packages, candidates, decision, design, approval, impact) as a row of nodes joined by
edges, and one question's lane below it (search plan, executions, eligibility, extraction and
verification, assessment, sufficiency) for the selected question, with a selector for the question.
Nodes use the console's `ac-part` classes (icon, title, subtitle, inset row with the state); edges
are the console's `ac-edge` SVG curves. States are shown by shape as well as colour: current
pulses with a ring, paused shows a pause glyph, failed shows a cross, reached a tick. The canvas
is a list for assistive technology (`role="list"`, each node a button with its state in its
accessible name) and scrolls horizontally on a narrow screen.

**Rationale**: the stages are fixed and few; a graph library brings layout and bundle for nothing,
and its canvases are rarely accessible. The spec's out-of-scope line excludes every question at once.

**Alternatives**: React Flow or similar (MIT, but a 150 kB canvas with no keyboard story by
default); drawing every question (out of scope).

## R15. Cards: one component per record kind, rendering the API's record whole

**Decision**: `web/src/cards/<kind>.tsx`, sixteen files, each taking the record type of the API's
JSON and rendering every field (`contracts/interface.md` lists them); a `Card` frame with the kind,
the id, the version and the time; `Passage` for a resolved span beside its text; `Check` for a
verification check with its result by shape and colour. The same component is used in the
inspector, inline in the conversation, in the review queue and as an action's result. A card
fetches nothing itself: the page or the conversation turn fetches the record and passes it, and
holds it only in component state.

**Rationale**: FR-002 and SC-003: the card is the record; one component per kind keeps the
comparison test mechanical.

**Alternatives**: a generic JSON renderer (no form made for the kind, which was the request); cards
fetching themselves (caches by accident).

## R16. Decisions are forms on the cards, calling the backend's routes

**Decision**: the facet set card holds the facet review form (per facet: confirm, amend, reject,
leave unresolved, assume, with the reviewer and rationale) and the "confirm the facets" action; a
finding's, claim's or assessment's card holds verify, reject and citation complaint; the candidates
card holds the selection with a reason per rejected approach; the design card holds approve,
reject and request changes. Each form posts the same body to the same `/api/v1` route the
operator's API takes, with the reviewer chosen from `GET /reviewers`, and shows the refusal's
message as the route gave it. The assistant's prefilled decision is the same form with its fields
set from a `prefill` the reply carries (`contracts/assistant.md`), and nothing else.

**Rationale**: FR-014 is met by construction: the same route records the same record and refuses
the same cases.

**Alternatives**: new routes for the interface (two ways to record a decision).

## R17. Two feature runners; the Scala suite is split by area

**Decision**: `features/interface/*.feature` run in the browser with `playwright-bdd` from `web/`,
steps in `web/test/features/steps/`, against the compose profile `app` with
`EDP_ASSISTANT_SCRIPT=/evals/assistant/walkthrough.json`. `features/investigation/automation.feature`
and feature 001's areas run in the Scala suite, which becomes one `GherkinSuite` per directory
(`RequestFeatures("features/request")`, …, `InvestigationFeatures("features/investigation")`), all
mixing in the same step traits, because `GherkinSuite` takes a directory or one file and cannot
exclude. `just features-replay` runs `testOnly …features.*`. The checker still reads one
`features/` directory. A request for an exclusion or a tag filter goes to `notes/ankka-requests.md`.

**Rationale**: a scenario about a card or a canvas is observable only in a browser; a scenario
about the workflow is best run through HTTP in the Scala tier; an undefined step fails a suite.

**Alternatives**: Scala step definitions for the interface scenarios driving the API only (would
not check what the scenario says); moving the interface features under `web/` (the checker reads
one directory).

## R18. The browser suites and CI

**Decision**: `web/test/` holds `node --test` unit tests (the server, the marker parser), and
Playwright projects: `features` (playwright-bdd), `walkthrough` (SC-001, keyboard only, with a
trace), `cards` (SC-003: for each record kind on the reference request, every scalar leaf of the
API's record appears in the card's text), `accessibility` (SC-008: axe on every view with the
WCAG 2.1 AA tags, zero violations). CI gains a job `interface` on `ubuntu-24.04`, Node 24, path
filtered to `web/**`, `features/interface/**`, `evals/assistant/**`: `npm ci`, typecheck, unit
tests, then `sbt schema Docker/publishLocal`, `docker compose --profile app up -d --wait` with the
scripted assistant and the replay tier, `npx playwright install --with-deps chromium`, the four
projects. The Scala job is unchanged except for the suite names.

**Rationale**: the suites must run against the real service in the replay tier; the compose
profile already builds and starts it from an empty database in under a minute.

**Alternatives**: mocking the backend in the browser (tests nothing of SC-003); running the
browser suites in the sbt job (two toolchains in one job).

## R19. Resolved open items

- **Scripts-off**: the console's rule that every operation works without script is not adopted
  for the interface; the canvas and the conversation need script, and feature 001's review pages
  remain for a browser without it (spec Assumptions). `docs/limitations.md` says so.
- **Where the free-standing conversation's key lives**: the browser, `localStorage` `edp.conversation`;
  a new browser is a new conversation with a fresh budget.
- **The actor on automatic stage runs**: `system:investigation`, so provenance shows what the
  operator did and what the investigation did.
- **The investigation's `packagedBy` for automatic packaging**: the same actor.
- **The journey when a question workflow failed**: its lane's node is `failed` and the spine's
  `questions` node is `paused`, with the failure's defect in the node's detail.
- **Narrow screens**: below 720 px the conversation is the page and the rail is a bottom bar; the
  canvas and the inspector are reached from it as pages.

## R20. Verify at implementation

1. That importing `ankka-console`'s index from a Vite app outside the console resolves with only
   `react`, `react-dom` and `react-router` installed, and that `Shell` and `Backdrop` render without
   `ConsoleProvider`; otherwise take the spec's fallback and copy the tokens into `web/src/theme.css`.
2. That `forWorkflow(id).awaitEnd(VerificationWorkflow, timeout)` on a workflow that has not yet
   started waits rather than fails; if it fails, `await-verification` first reads the record's
   status and treats a missing workflow as unsettled until the bound.
3. That the question workflow's `assess` and `judge` commands are accepted from the rests the
   automation adds (`assessed`, `judged`) and that a manual `package` after automatic packaging
   records a further stage run and no duplicate package.
4. That the walkthrough's cassettes for `assess`, `sufficiency`, `candidates` and `design` are hit
   when the automation chooses the record set: compare `candidatesMarker` inputs between the
   script's calls and the automatic path on the reference request; re-author a cassette whose
   marker moved, through `just cassette`.
5. That the session's total usage is readable from `forSessionMemory(id).history` immediately after
   the streamed `Source` completes; otherwise record the usage on the next turn's `Opened`-style
   catch-up and say so in the budget's `used`.
6. That `sseBody` is the right route form for a streamed reply with a JSON body, and the exact SSE
   framing of each chunk (a JSON string), so the interface's parser matches.
7. The stage names the question workflow records and the run id it records them under, for
   `contracts/journey.md`'s table.
8. That a `GherkinSuite` per directory shares the step traits without each suite starting a second
   service on the same ports (the integration kit chooses ports).
9. That `ankka local web` passes `Authorization` through to the mounted service unchanged (the
   reference says only `X-Ankka-*`, `Forwarded` and hop-by-hop headers are removed).
10. axe's result on the `ac-part` buttons and the pulsing ring (`prefers-reduced-motion` honoured).

## Found during implementation

(filled as the build goes)

- **R20.1, answered (interface)**: `ankka-console` 0.15.1's index is browser-safe and bundles with
  Vite 8; its server-only code is under `ankka-console/server`. The interface takes `Shell`,
  `Backdrop` and `Inspector` and `ankka-console/styles.css` from it; the tokens are not copied. Its
  `Bar`, `Rail` and `Breadcrumbs` read the console's router data through `useConsole`, so the
  interface draws its own bar and rail with the same classes (`ac-bar`, `ac-crumbs`, `ac-rail`,
  `ac-rail-item` with `aria-current`, `ac-mark`). Inter and the mark ship with the stylesheet and are
  served from the interface's own origin under `assets/`.
- **R20.6, the event-stream framing (interface)**: the service writes `data:` before `event:` inside
  an event (`data:{…}` then `event:journey` then a blank line), which the protocol allows. The
  interface's parser (`web/src/backend/events.ts`) dispatches each event at its blank line, in any
  field order, joins several `data:` lines with a newline, and holds a line cut anywhere, a CRLF
  included, until it is whole. A plain `sse` route sends each chunk unnamed, as a JSON string, so the
  turn stream's reader takes an unnamed event as a `token`.
- **Absent fields (interface)**: the service's codecs leave out a field equal to its default (no
  value, an empty list, a zero), so the journey's `detail` and `runs` and most record fields may be
  missing rather than `null` or `[]`. `web/src/backend/types.ts` makes every defaulted field
  optional, and an enum with fields (a record's content) carries its case in `type`
  (`OfFinding`, `OfClaim`).
- **Refusals (interface)**: a refusal's body is `{"status", "error", "details"}`; `BackendError`
  carries the `error` text as its message. A `401` on any call sends the admission screen back.
- **Every field on a card (interface)**: SC-003 is held by the card frame itself: each card lists
  every scalar leaf of the record it was given under "every field", so a kind whose own card is not
  written yet still shows everything the service returned.
- **playwright-bdd 9 (interface)**: `createBdd` needs the `test` extended from `playwright-bdd`, not
  from `@playwright/test`, and `importTestFrom` is no longer wanted. With `missingSteps:
  "fail-on-run"` the 45 scenarios generate before their steps exist: 44 in `features`, the one
  tagged `@no-model` in `no-model`.
- **Node runs TypeScript by stripping types (interface)**: a parameter property
  (`constructor(private x)`) is refused at run time by `node --test`, as `erasableSyntaxOnly` in
  `web/tsconfig.json` says; the interface declares its fields instead.

- **R20.7, the question workflow's stage names and run id.** A question's run is `run-<questionId>`, and
  a question id is `<requestId>:q<n>`, so every run of a request shares the prefix `run-<requestId>`.
  The question workflow records the stages `plan`, `execute`, `eligibility`, `assess` and `sufficiency`;
  `resolve` records none. The automation adds `verification`, the wait for the retrieved records. The
  journey's watch therefore reads one view query, `row_key = :run OR row_key LIKE :lanes`, with
  `lanes` = `run-<requestId>:%`, and a two-second tick for workflow changes that record no stage.
- **R20.8, one service per feature suite.** Each `GherkinSuite` mixes in `CommonSteps`, which binds port
  0 and starts its own kit, so the seven area suites run one after another without sharing ports. The
  split surfaced a view race: `features/sources/ingestion.feature`'s duplicate scenario read the
  sources listing once, and on a freshly started service the view had not projected yet. The step
  now waits for the source to be listed before counting.
- **Who asked for a stage.** A stage execution recorded no actor, so `StartStage`, the event
  `StageStarted` and `StageExecution` gained `actor: Option[String] = None`. An older journal reads as
  no actor. The automatic stages record `system:investigation`, and packaging and candidates record
  `packagedBy`.
- **The journey's JSON omits empty fields.** `Codecs.make` leaves out a `None` and an empty vector, so
  a node without a detail has no `detail` key and a node without runs no `runs` key, where the contract
  showed `null` and `[]`. The interface treats both as absent.

- **R20.4, the cassettes the automation needed.** `assess-all` assesses every verified eligible record a
  question retrieved, which for the reference corpus is the three findings and the three claims, not the
  walkthrough's three findings. The record set's hash is `6afacf9b794a` (three findings only:
  `1bc6c32958a3`; nothing: `e3b0c44298fc`; Papers B and C only: `ca8f503f0ef2`). Authored with
  `CassetteAuthor`: `assess` for the five questions that retrieve anything over the six records; `sufficiency`
  for those five and both configured decisions over the six; `sufficiency` over the empty set for all six
  questions and both decisions (the empty corpus); the lipid question over B and C (an unverified Paper A).
  The `candidates` marker hashes the assessed record ids, so it moved, to `candidates:889b0328b6d6:de060bcfbcb0`;
  its reply is the earlier one, which cites only the three findings. The `design` marker does not depend on
  the records and did not move. No cassette exists for candidates over the empty set, so an empty corpus
  pauses at candidates, which the scenario does not require.
- **A record under verification is not always `proposed`.** While its verification workflow retries a failed
  model call, a record reads `requires_review`. The automation first counted only `proposed` records as
  unsettled and assessed past a verification still running. It now asks the latest verification workflow
  too: a record is unsettled while that workflow is `Running` or `Paused`.
- **A held model call lasts ten seconds in the tests.** The agent runtime's call timeout is 10 s under
  `ServiceFixture`, so a test cannot hold a call across an ingestion. The verification tests hold every
  verification call of Paper A, retries included, so its verification keeps running long enough for the
  question to meet it, and settles as requiring review. The verification workflow shares
  `Configured.retryBudget`, so a test that lowers the budget also shortens verification.

- **A defect kind needs a severity.** `Rules.defect` matches on the defect kinds without an exhaustiveness
  warning, so the new `verification_unsettled` threw a `MatchError` inside the wait step. The workflow's step
  recovery retried it and failed over, and the lane rested with "the wait overran its step" and no defect on
  the stage run. It is now `major`, and the unsettled tests assert the stage run's defect.
- **The automation feature has no shared Background ingestion.** "An empty corpus" cannot follow a
  Background that ingests the sources, and one service serves the whole directory. Each scenario that needs the
  sources ingests them, the empty-corpus scenario runs first, and its Given asserts the store is empty, so a
  reordering fails loudly rather than passing on a full store.

- **R20.5, answered: the session's usage is readable as soon as the stream ends.** The turn route reads
  `forSessionMemory(id).history` once the agent's `Source` completes and the total is there
  (`ConversationBudgetSuite`: 80 in and 40 out read as 120). The trap was elsewhere: `concat` materializes
  the source it appends at once, so a `Source.lazySource` of the turn's ending ran before the first
  token and recorded an empty answer with no usage. The route uses `concatLazy`.
- **R20.6, answered (service): `sseEventsBody` is the form.** The turn is a `POST` with a JSON body
  answered with named events, so the route is `sseEventsBody`; each chunk is a `token` event whose data
  is a JSON string, then `proposal` events, then `done`, or `error`.
- **An agent has no view client.** `AgentContext` gives a component client only, so the assistant's read
  tools build `Readers` from it and `Configured.views`, which the service sets once it has started. The
  API's read routes and the tools share `Readers`, so an answer's ids and JSON are the cards'.
- **Fields at their defaults are left out on the conversation's wire too.** An unresolved reference has
  no `resolved`, and a proposal waiting for confirmation has no `status`; the interface reads an absent
  field as its default.
- **A named script wins over a key.** R12 lists the key first; `EDP_ASSISTANT_SCRIPT` is the more
  deliberate setting, so when both are present the assistant answers from the script.
- **A request's conversation spends its budget quickly.** The read tools return the API's whole record,
  and the lipid question's is about 38 kB, some 9,500 tokens. The history goes back to the model on
  every call, so each later call counts it again. With the default budget of 200,000 tokens, a request's
  conversation lasts a handful of questions that read the question. A request has one conversation, so
  it cannot start a fresh one; the free-standing conversation can. Recorded in the limitations.
- **Proposals are sent when the answer ends.** The `proposal` events go just before `done`, read from
  the conversation's new proposals, rather than as the tool records each one. A failed turn sends none.
- **Every enumeration is an object on the wire.** A status, an origin, an importance, an action, a
  decision or an escalation arrives as `{"type": "<value>"}`, not as a string; the interface's types
  name the values and `tag()` reads either form. The review queue's rows are the exception: their
  `status` is a plain string.
- **`GET /designs/{id}` answers JSON as `text/plain`.** The route can also render Markdown, so its
  content type is text in both cases; the interface's client parses a text answer that begins with `{`
  or `[` as JSON.
- **A stage that fails after its retries is `paused`, not `failed`.** The journey keeps the investigation
  waiting at it, and its runs carry the failures; the canvas draws such a node failed, with the last
  run's classified defect, and says the investigation waits there. In the replay tier a request no
  cassette answers fails its facets stage this way within a second.
- **No stage stays running in the replay tier.** Every model call answers at once, so the browser suite
  shows "a running stage says so" on a failed stage presented as its next attempt (the journey the page
  reads is rewritten in the test; every record it fetches is the service's). A hold on a model call,
  like the integration suites', would let the scenario read a real running stage.
- **The review queue lags a review.** It is a view; right after a review it still lists the record. The
  queue page reads each record and leaves out those no longer awaiting review, so a reviewed record
  leaves at once.
- **Only claims can be authored by a reviewer.** `POST /claims` exists and `POST /findings` does not, so
  the browser scenario "a finding in the review queue" queues a reviewer's unsupported claim. A
  proposition with no cassette still settles `requires_review`, so each run names its own.
- **The facets refusal names facets by name.** `facets:confirmed` refuses with "mandatory facets still
  proposed: product, production approach; …", listing names, not ids.
- **A retraction's cost grows with the database.** Every request that used Paper B adds some thirty
  dependents. With about thirty requests in the browser suites' database `GET /impact` takes 10 to 18
  seconds over 390 dependents, and flagging them all after a retraction took minutes. The impact card
  shows the stage at once and its card when the route answers; the impact scenario waits up to five
  minutes for its own request's dependents.

- **`resolved` is written only when it is true (interface).** A turn's reference that did not resolve
  comes back with no `resolved` field at all, never `resolved: false`; the interface takes an absent
  flag as unresolved. A fresh conversation has no `used` either. The conversation's `proposals` are
  the only place a proposal's arguments come back once the page reloads.
- **A token drawn per event (interface).** The scripted stream sends a whole answer in under a second,
  so React batches the `token` events into one render unless each is drawn with `flushSync`; the
  answer's `data-tokens` counts the drawn events, and the US4 step observes its successive values to
  hold "the answer appears as it is written".
- **The fault rule in the script names the original finding.** `evals/assistant/walkthrough.json`'s
  `inject` rule proposes `endpoint_swap` on `src:file:paper-a:v1:fd1`. In the replay tier that attempt
  has no verification cassette: the finding is left `requires_review`, never shows `endpoint_mismatch`,
  and every request submitted afterwards assesses a different record set and misses its `assess`
  cassette. The walkthrough script faults the copy `src:file:paper-a@endpoint_swap:v1:fd1`, ingested
  after the design is approved; the rule should name that id. Until it does, the US5 fault scenario and
  step 5 of `walkthrough.spec.ts` stop before confirming, so the corpus is left as it was.
- **The copy of Paper A must come after the reference request.** In the store before a request is
  submitted, `paper-a@endpoint_swap` joins the records its questions assess, and the `assess`
  cassettes miss. The browser steps ingest it only once a proposal names it.
- **A request's conversation spends about 300,000 tokens a turn after a few turns.** Each turn sends
  the history again with the tools' whole records, so the browser stack runs with
  `EDP_CONVERSATION_BUDGET=5000000` and a scenario takes a new approved request when the last one's
  conversation is past 60 % of its budget; the budget scenario spends a free-standing conversation
  through the API first.
- **`toDecision` confirms only a review already waiting.** It reads the journey once; called straight
  after a submission it skips the facets and waits for candidates that never come. The conversation's
  support submits with `submitWaiting` first.
- **R20.9, answered: `ankka local web` passes `Authorization` through.** Every browser suite reaches the
  service under `/backend` with a bearer header through it; a call without one is refused `401`.
- **R20.10, answered: two violations, both fixed.** axe found `label-content-name-mismatch` on every
  node (an `aria-label` that did not contain the node's visible detail) and
  `scrollable-region-focusable` on the scrolling tables. A node is now named by its visible text with
  visually hidden separators ("facet review, paused, the investigation waits here"), and each scrolling
  table is a focusable labelled region. The pulsing ring and the `ac-part` buttons raise nothing.
- **"Discuss" opens a panel or a page.** Beside the canvas the conversation is `?discuss=1` on the
  request's page; below 720 px the same button goes to `/requests/:id/chat`. A card's "show its stage"
  selects the node on the open canvas, or opens the request's canvas from the free-standing
  conversation.

## Measurements

| What | Result |
|---|---|
| Facets confirmed to the `journey` event showing facet review reached, ten requests (`JourneyStreamSuite`, SC-004) | 335, 2019, 718, 2019, 887, 2013, 778, 2019, 807, 2017 ms; the worst 2019 ms, under the 5 s bound. The two-second cases are the tick: the first change after the confirmation is the investigation's state, which the view does not see. |
| A scripted turn's stream, ten trials on one free-standing conversation (`EDP_ASSISTANT_SCRIPT`, replay tier, the 9200 browser stack) | first `token` event 102, 8, 10, 7, 7, 54, 7, 8, 6, 20 ms; `done` 185, 111, 65, 197, 63, 117, 30, 240, 38, 89 ms. The script answers at once, so this is the route and the stream, not a model. |
| The interface started beside the service with `ankka local web -- npm run dev`, to its first page (admission.feature) | 1452 ms and 1184 ms, against the one-minute bound. |
| The reference walkthrough in the browser by keyboard alone, submission to approved design (`walkthrough.spec.ts`, fixtures already ingested) | about 10 s for steps 1 to 4, the three decisions included, in the replay tier; step 5 (the fault) stops at the script's fault id, so the whole walkthrough is not yet timed. |
