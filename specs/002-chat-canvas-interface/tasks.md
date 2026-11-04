---

description: "Task list for the chat and canvas interface, the automation of the investigation and the assistant"
---

# Tasks: A chat and a canvas for experiment proposals

**Input**: Design documents from `/specs/002-chat-canvas-interface/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (http-api.md, assistant.md, journey.md, interface.md), quickstart.md

**Tests**: Included. The specification makes the suites deliverables (FR-021, SC-003, SC-004, SC-005, SC-008, SC-010). The acceptance scenarios live only in the Gherkin features: `features/investigation/automation.feature` runs in the Scala suite through HTTP; `features/interface/*.feature` run in the browser through `playwright-bdd` against the compose profile `app` in the replay tier with the scripted assistant (research R17). Integration suites cover what the features do not express (the stream's timing, refusals, the budget's arithmetic); the independent suite checks that no route lets the assistant record a decision.

**Organization**: Phase 1 sets up `web/`, the settings and the recipes; Phase 2 builds what every story reads (the journey derivation and its routes, the reference values, the shell, the client, the two feature runners). Phases 3 to 8 are one user story each: the three P1 stories in the plan's order (US2 automation, US1 journey and cards, US3 decisions), then US4 and US5 (P2), then US6 (P3). Phase 9 is polish and the success criteria that cross stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1 to US6)
- Every task names the file or files it creates or changes

## Path Conventions

`S` = `src/main/scala/com/thinkmorestupidless/experimentproposals`, `T` = `src/test/scala/com/thinkmorestupidless/experimentproposals`, `R` = `src/main/resources`, `W` = `web`. Living features in `features/<area>/*.feature`; Scala steps in `T/features/steps/`; browser steps in `W/test/features/steps/`; the scripted assistant in `evals/assistant/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: the interface's project, the settings the service gains, the recipes and the CI job.

- [X] T001 Create `W/` from the platform's web template (`ankka init --language web` into a scratch directory, then copy `package.json`, `tsconfig.json`, `index.html`, `server/server.ts`, `src/main.tsx`, `src/App.tsx`, `test/server.test.ts`, `Dockerfile`, `README.md` into `W/`); set `name` to `experiment-proposals-web`, `version` to `0.0.0`, `engines.node >= 24`; remove the template's `/summary` route and `src/api.ts`
- [X] T002 Add dependencies to `W/package.json`: `ankka-console@0.15.1`, `react`, `react-dom`, `react-router@^8`, `lucide-react`; dev: `@react-router/dev@^8` (peer), `vite`, `typescript`, `@types/react`, `@types/react-dom`, `@types/node`, `@playwright/test`, `@axe-core/playwright`, `playwright-bdd`; scripts `dev`, `build`, `start`, `typecheck`, `test` (`node --test`), `features` (`bddgen && playwright test --project features`), `check` (`playwright test --project walkthrough --project cards --project accessibility`); run `npm install` and commit `package-lock.json`
- [X] T003 [P] Write `W/service.json` per contracts/interface.md (`hosting: web`, `processPort: 3000`, mount `/backend` to `ankka-experiment-proposals`) and `W/vite.config.ts` (React plugin, `build.outDir dist`, assets hashed under `assets/`)
- [X] T004 [P] Extend `Justfile` with one-command recipes: `web` (`cd web && ankka local web --service ankka-experiment-proposals=http://127.0.0.1:9000 -- npm run dev`), `web-test` (`cd web && npm run typecheck && npm test`), `web-features` (`cd web && npm run features`), `web-check` (`cd web && npm run check`), `web-image` (`cd web && npm run build && docker build -t experiment-proposals-web:latest .`); change `features-replay`, `features-live` and `features-record` to `testOnly {{pkg}}.features.*`
- [X] T005 [P] Extend `S/config/Settings.scala` with `conversationBudget: Long` (`EDP_CONVERSATION_BUDGET`, default 200000), `verificationBound: FiniteDuration` (`EDP_VERIFICATION_BOUND`, default `3m`, parsed as a duration), `assistantModel: String` (`EDP_ASSISTANT_MODEL`, default the value of `EDP_MODEL`), `assistantScript: Option[Path]` (`EDP_ASSISTANT_SCRIPT`), each refused with a message naming the variable when malformed; extend `S/config/Configured.scala` with `assistantModel: Option[ModelProvider]`, `conversationBudget`, `verificationBound`; document the four names in `.env.example` and `docs/reference/configuration.md`
- [X] T006 [P] Add `evals/assistant/.gitkeep` and, on the `app` service in `docker-compose.yml`, the environment `EDP_ASSISTANT_SCRIPT=${EDP_ASSISTANT_SCRIPT:-}` and `EDP_CONVERSATION_BUDGET=${EDP_CONVERSATION_BUDGET:-200000}` (the `evals/` mount already carries the script)
- [X] T007 [P] Add a job `interface` to `.github/workflows/ci.yml`: `ubuntu-24.04`, path-filtered to `web/**`, `features/interface/**`, `evals/assistant/**`, `src/**`; Node 24 with `cache: npm`; `npm ci`, `npm run typecheck`, `npm test`; then JDK 21 and sbt, `sbt schema Docker/publishLocal`, `docker compose --profile app up -d --wait` with `EDP_TIER=replay`, `EDP_EMBEDDING_MODEL=hash`, `EDP_API_TOKEN=ci-token`, `EDP_ASSISTANT_SCRIPT=/evals/assistant/walkthrough.json`; `npx playwright install --with-deps chromium`; `npm run features`; `npm run check`; then `docker compose --profile app down` and `up -d --wait` again with `EDP_ASSISTANT_SCRIPT` unset, and `npx playwright test --project no-model`; upload `W/test-results` as an artifact on failure
- [X] T008 [P] Add three rows to `notes/ankka-requests.md`: a `GherkinSuite` that excludes files or runs by tag (R17); a watch over a workflow's state or lifecycle (R6); the usage of a streamed reply reported with the stream's end (R8)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the values, the derivation, the routes, the shell and the runners every story stands on.

**CRITICAL**: no user story work can begin until this phase is complete.

- [X] T009 [P] Write `S/domain/conversation/Reference.scala`: `RecordKind` (the sixteen kinds, wire names per contracts/assistant.md), `Reference(kind, id, version, resolved)`, `Prefill(decision, target, fields)` with `DecisionKind`; and `S/domain/conversation/ReferenceMarkers.scala`: `parse(text): (Vector[Reference], Option[Prefill])` for `[[kind:id]]`, `[[kind:id@v]]` and `[[prefill:...]]`, ignoring markers inside backtick spans, deduplicated in order of first appearance
- [X] T010 [P] Write `S/domain/conversation/Actions.scala`: `Action` (`submit-request`, `ingest-source`, `inject-fault`, `set-source-status`) with each action's arguments, `validate(action: String, arguments: Map[String, String]): Either[String, Action]` returning messages the model can act on (`unknown action 'x'; the actions are ...`, `ingest-source needs 'adapter' and 'identifier'`), the fault names from feature 001's fault injection and the source statuses from `Enumerations`
- [X] T011 [P] Write `T/unit/ReferenceMarkersSuite.scala` (markers, versions, prefill fields, code spans, duplicates, an unknown kind is not a marker) and `T/unit/ActionsSuite.scala` (each action accepted with its arguments; each missing or unknown argument refused with its message)
- [X] T012 [P] Write `S/domain/journey/Journey.scala`: `Stage` (the spine's eleven and the lane's six, wire names per contracts/journey.md), `NodeState`, `StageRunRef`, `StageNode`, `QuestionLane`, `Journey`, and `Journey.derive(investigation: InvestigationState, lifecycle: WorkflowLifecycle, questions: Vector[(QuestionInvestigationState, WorkflowLifecycle)], run: StageRun, impactFlags: Int): Journey` as a pure function implementing the node-state table, with `detail` strings per the contract
- [X] T013 [P] Write `T/unit/JourneySuite.scala`: a fresh request (facets current, rest unreached); paused at facet review; questions investigating with one lane paused at `record-failure` (spine `questions` paused with the defect in detail); a lane at `await-verification` (current, detail counts settled of retrieved); every lane reported and packages current; awaiting decision; awaiting approval with two design versions; approved with an impact flag; a failed investigation; equality of two derivations from the same inputs
- [X] T014 Add to `S/application/views/StageRows.scala` the watchable query `byRun = query("by-run")(s"SELECT row_key, payload FROM $table WHERE row_key = :run").watched` and bump `override def version` so the view rebuilds
- [X] T015 Write `S/application/journey/JourneyReader.scala`: `journey(requestId): Future[Journey]` reading the investigation workflow's status and lifecycle, each question workflow's, the run `run-<requestId>` and the request's impact flags, calling `Journey.derive`; `watch(requestId): Source[Journey, NotUsed]` merging `views.forView(StageRows).watch(StageRows.byRun, "run" -> runId)` events and a 2-second tick while the stream is open, re-deriving on each and emitting only when the journey differs from the last emitted; `404` when the request does not exist
- [X] T016 Add to `S/api/ApiEndpoint.scala` the routes `GET /requests/{id}/journey` and `sse("/requests/{id}/journey:watch")` emitting `event: journey`; add `Journey`, `StageNode`, `QuestionLane`, `StageRunRef`, `Reference`, `Prefill` codecs to `S/api/Json.scala`; document both routes in `docs/reference/http-api.md`
- [X] T017 Write `T/integration/JourneyStreamSuite.scala`: with the pipeline's `TestModelProvider`, submit the reference request and read the stream: the first event is the journey now; confirming the facets through the API produces a new `journey` event within 5 seconds in which `facet-review` is reached and `questions` is current; an unchanged state sends nothing; ten confirm-and-measure trials across ten requests all under 5 seconds (SC-004); an unknown request answers `404`
- [X] T018 Write `S/replay/ScriptedModelProvider.scala` per contracts/assistant.md (rules from a JSON file matched by substring of the last user message, tool-calling turns then text, a fixed line when nothing matches) and `T/unit/ScriptedModelProviderSuite.scala` (a text rule, a tools-then-text rule, no match)
- [ ] T019 Split `T/features/FeatureSuite.scala` into one `GherkinSuite` per directory in `T/features/`: `RequestFeatures("features/request")`, `SearchFeatures("features/search")`, `SourcesFeatures("features/sources")`, `EvidenceFeatures("features/evidence")`, `DesignFeatures("features/design")`, `AssuranceFeatures("features/assurance")`, `InvestigationFeatures("features/investigation")`, each mixing in every step trait; delete `FeatureSuite`; check R20.8 (one service per suite on the kit's ports) and record the answer in research.md
- [X] T020 [P] Write `W/src/backend/client.ts`: `backend(path, init)` prefixing `/backend/api/v1`, adding `Authorization: Bearer` from `token.ts`, parsing `HttpProblem` refusals into `BackendError(status, message)`, and `readEvents(path, init, onEvent)` reading `text/event-stream` from a `fetch` body (events, `data:` lines, multi-line data, abort on unmount); `W/src/token/token.ts` (`localStorage` `edp.token`: get, set, forget) and `W/src/token/Admission.tsx` (the field, "the token was refused" on `401`, a `401` on any later request returns here)
- [X] T021 [P] Write `W/src/backend/types.ts`: the TypeScript types of every record the cards show (request, facets with round-trip, question, search plan, search execution, source, finding, claim, assessment, sufficiency, package, candidates with comparison, decision, design with approvals, impact flag, stage run, journey, conversation, reference, prefill, proposal), one interface each, named as the API's JSON fields; and `W/src/backend/records.ts`: one function per route in contracts/http-api.md
- [X] T022 [P] Write `W/src/conversation/markers.ts` and `W/test/markers.test.ts`: the same marker grammar as `ReferenceMarkers` (references, versions, prefill, code spans) splitting streamed text into text and marker segments, tolerant of a marker split across chunks
- [X] T023 Write `W/src/shell/Shell.tsx` hosting `Shell` (theme `dark`) and `Backdrop` from `ankka-console` with `import "ankka-console/styles.css"`, and the interface's own `W/src/shell/Bar.tsx` (wordmark, centred breadcrumb, primary action slot), `Rail.tsx` (four areas with lucide icons, current marked, "forget the token" at the foot; a bottom bar below 720 px), `Inspector.tsx` (the `ac-inspector` column; a sheet below 720 px), `Page.tsx`; verify R20.1 (the package's index imports outside the console) and, if it fails, copy the tokens to `W/src/theme.css` and record it in research.md
- [X] T024 Write `W/src/routes.tsx` and `W/src/App.tsx`: React Router 8 (`createBrowserRouter`) with `/` (requests), `/requests/:id` (canvas), `/requests/:id/chat`, `/queue`, `/sources`, `/chat`; the admission gate around every route; `W/src/main.tsx` mounting it; the breadcrumb derived from the route
- [X] T025 [P] Write `W/src/cards/Card.tsx` (the frame: kind, id, version, time, "open in the inspector", "show its stage"), `W/src/cards/Field.tsx`, `Passage.tsx` (a span beside its resolved text), `Check.tsx` (a verification check with result by glyph and colour), `State.tsx` (tick, pulsing ring, pause bars, cross; `prefers-reduced-motion` honoured), and `W/src/cards/index.ts` mapping each `RecordKind` to its component
- [X] T026 [P] Write `W/playwright.config.ts` with projects `features` (playwright-bdd over `../features/interface/*.feature`, steps `W/test/features/steps/`, excluding the scenario tagged `@no-model`), `no-model` (the one scenario tagged `@no-model`, run against a stack started without `EDP_ASSISTANT_SCRIPT`; add that tag to "without a model the assistant says so and everything else works" in `features/interface/conversation.feature`, changing no step), `walkthrough`, `cards`, `accessibility`; `baseURL` from `EDP_WEB_URL` (default `http://127.0.0.1:3000`); `EDP_API_TOKEN` from the environment; a `webServer` running `ankka local web --service ankka-experiment-proposals=${EDP_SERVICE_URL:-http://127.0.0.1:9000} -- npm run dev`; `W/test/features/support/world.ts` (the token, a fresh-database check, helpers calling `/backend/api/v1` directly for Given steps: submit, ingest the fixtures, confirm facets, run to a stage); `W/test/features/support/axe.ts`
- [X] T027 Extend `W/server/server.ts` and `W/test/server.test.ts`: no route of its own; `index.html` served with `Cache-Control: no-cache`; `assets/` immutable; `SIGTERM` stops within ten seconds

**Checkpoint**: `just test-unit`, `just test-integration` (JourneyStreamSuite) and `just web-test` green; `just features-replay` runs the split suites unchanged; `just web` shows the admission screen and an empty requests listing.

---

## Phase 3: User Story 2 - The investigation runs itself between the reviewer's pauses (Priority: P1)

**Goal**: after the facets are confirmed, the investigation reaches the choice of approach with no operator call, pausing only where a reviewer decides or a stage failed.

**Independent Test**: through the API alone, with the fixtures ingested, submit the reference request and confirm its facets; the investigation reaches `awaiting-decision` with every question assessed, sufficiency judged for both decisions, the packages and two candidates, and a stage run per stage.

- [ ] T028 [US2] Extend `S/application/workflows/QuestionInvestigationWorkflow.scala`: state fields `settled`, `unsettled`, `decisionsPending`, `reportedToInvestigation` and the statuses `awaiting-verification`, `assessing`, `judging`, `reported` (data-model.md); the step `step("await-verification")`: for each retrieved record still `proposed`, `forWorkflow(recordId).awaitEnd(VerificationWorkflow, remaining)` within `Configured.verificationBound`, then read every status; settled leads to `assess-all` or, with no verified eligible record, `judge-all`; unsettled fails the stage with the defect `verification unsettled` (transient) through `StageCall`; declare `stepTimeout(awaitVerification, bound + 30.seconds)` and `stepRecovery` with `Configured.retryBudget` retries then `record-failure`; check R20.2 (awaitEnd on a workflow not yet started) and record the answer in research.md
- [ ] T029 [US2] Add to `S/application/workflows/QuestionInvestigationWorkflow.scala` the steps `assess-all` (the `assess` stage over the verified eligible record ids, reusing `assessStepRun`'s body with the actor `system:investigation`), `judge-all` (the `sufficiency` stage for the head of `decisionsPending`, seeded from `Configured.decisions`, transitioning to itself while any remain), and `report` (sends `question-reported(questionId)` to `InvestigationWorkflow`, then `waiting` with status `reported`); change `resolveStep`'s transition from `waiting` to `await-verification`; accept `plan`, `execute`, `supplemental`, `assess` and `judge` from `reported` and from the rest after a failure
- [ ] T030 [US2] Extend `S/application/workflows/InvestigationWorkflow.scala`: state `reportedQuestions`; `command("question-reported")` recording the id and transitioning to `build-packages` with `packagedBy = Some("system:investigation")` when every `questionIds` entry has reported and the status is `questions-investigating`, otherwise replying the state; keep `package(by)` accepted before or after; make `buildPackagesStep` and `candidatesStep` record their stage runs with the actor from `packagedBy`
- [ ] T031 [US2] Write `T/integration/AutomationSuite.scala` with the pipeline's `TestModelProvider`: facets confirmed leads to `awaiting-decision` with no further call, counting stage runs per stage; the verification wait (a source whose verification is scripted to complete after the assessment stage is reached); the unsettled bound (`Configured.verificationBound` 2 seconds, a verification whose model reply never comes, the defect `verification unsettled`, the investigation paused at `record-failure`, resumed by the manual `assess` without a duplicate assessment); an empty corpus (gap, sufficiency abstains, packages built); a manual `assess` after `assess-all` records a further stage run; a manual `package` after automatic packaging adds no duplicate package; a restart between `judge-all` steps resumes at the next decision; no ingestion workflow is started by the investigation at any point (FR-010)
- [ ] T032 [US2] Check R20.4: run the reference walkthrough with the automation and list every cassette miss; where a `candidates` or `design` marker moved because the assessed set differs from the script's, author the cassette with `just cassette` from the recorded reply and the fixture's quotations into `evals/cassettes/`; record the markers in research.md "Found during implementation"
- [ ] T033 [US2] Write `T/features/steps/AutomationSteps.scala` for every step of `features/investigation/automation.feature` (the reviewer confirms the facets; nothing else is done; the investigation reaches the pause; a stage run is recorded for every stage between; the automatic stage runs; findings assessed; verification settles or does not; sufficiency per decision; a stage failing after its retries; an empty corpus; a manual stage after the automatic one; no source ingested) and mix it into `T/features/InvestigationFeatures.scala`; `just features-replay` green
- [ ] T034 [US2] Remove from `scripts/walkthrough.sh` the calls the automation now makes (`assess`, `sufficiency`, `packages`) and replace them with waits on `GET /workflows/investigation/{id}` for `awaiting-decision`; keep the fault and retraction steps; run it to green from an empty database (SC-010) and update its step count in `README.md`
- [ ] T035 [US2] Update `docs/concepts/stages.md`, `docs/get-started/walkthrough.md` and `docs/reference/http-api.md`: the stages the investigation runs itself, the actor `system:investigation`, `EDP_VERIFICATION_BOUND`, the defect `verification unsettled`, and that the manual routes still work; update `docs/limitations.md` where it said the operator calls each stage

**Checkpoint**: US2 complete and testable through the API alone.

---

## Phase 4: User Story 1 - Follow a request's journey on the canvas and read each stage's card (Priority: P1) - MVP

**Goal**: the canvas of a request's journey with live progress, and a card for every record kind in the inspector.

**Independent Test**: with a request run to an approved design through the API, open it: every stage lit, each card equal to the API's record; submit another request through the API while the canvas shows it: its facets light within a few seconds without a reload.

- [ ] T036 [P] [US1] Write `W/src/areas/Requests.tsx`: the requests listing from `GET /requests` (newest first, text, submitted by, status, where it stands from the journey's current or paused node), each row a link to `/requests/:id`; the bar's primary action "submit a request" opening `/chat`
- [ ] T037 [US1] Write `W/src/journey/Canvas.tsx`, `W/src/journey/Node.tsx`, `Edge.tsx`, `Lane.tsx`: the spine as a row of `ac-part` buttons joined by `ac-edge` curves, the selected question's lane below with a question selector; each node's icon, title, `detail` subtitle and inset state row with `State`; `role="list"`, each node a button named "<stage>, <state>"; horizontal scroll on narrow screens; selection kept across redraws
- [ ] T038 [US1] Write `W/src/journey/useJourney.ts` (fetch `GET /requests/{id}/journey`, then `readEvents` on `journey:watch`, replacing the journey on each event and closing the stream when the request is left) and `W/src/areas/Request.tsx` composing the canvas, the inspector with the selected node's card, the breadcrumb (requests, request, stage) and the bar's primary action by the paused node (confirm the facets, decide, approve)
- [ ] T039 [P] [US1] Write the cards `W/src/cards/RequestCard.tsx`, `FacetsCard.tsx` (facets with origin, importance, status, quotation; the round-trip check's result and differences; a slot for the review form), `QuestionCard.tsx`, `SearchPlanCard.tsx`, `SearchExecutionCard.tsx` (results with source and rank; exclusions with their rule; the rerun's match), `SourceCard.tsx` (identifier, adapter, versions, status; records with status, refetched on each journey change while shown)
- [ ] T040 [P] [US1] Write `W/src/cards/RecordCard.tsx` (finding and claim: statement, each span as `Passage` beside its resolved text, each check as `Check` with the verifier's rationale, status, attempts; a slot for the review form), `W/src/cards/AssessmentCard.tsx` (applicability, rationale, links; a slot), `SufficiencyCard.tsx` (per decision: status, critical gaps, assumptions, rationale), `PackageCard.tsx`
- [ ] T041 [P] [US1] Write `W/src/cards/CandidatesCard.tsx` (approaches side by side, one row per criterion, no probability field rendered and a typecheck guard that fails the build if the type ever gains one; a slot for the decision form), `W/src/cards/DecisionCard.tsx`, `DesignCard.tsx` (the twelve sections; "proposal" marked until an approval exists; approval history by version; a slot for the approval form), `ImpactCard.tsx`, `StageRunCard.tsx` (each execution: stage, attempt, status, actor, inputs, outputs, checks, defect)
- [ ] T042 [US1] Write `W/src/journey/stageCard.ts`: which record(s) a selected node fetches (contracts/http-api.md's table by card: facets fetch facets and proposal; a lane's `verification` the question's records; `assessment` the assessments; `sufficiency` the question's judgements; `candidates` the approaches; `design` the latest design; `impact` the `/impact` flags filtered to the request; the pause nodes `facet-review`, `decision` and `approval` the card of the thing decided, which is the facets, candidates or design card with its form, and the decision's record once it is made, since a pause names no stage run) and the inspector's "running" state when the node is current and no record exists yet, showing the earlier attempts from the stage-run card when there are any
- [ ] T043 [US1] Write `W/test/cards.spec.ts` (SC-003): seed the reference request to an approved design through the API (the world's helpers), open it, select each node and for each card kind fetch the same record from `/backend/api/v1` and assert every scalar leaf of the record's JSON appears in the card's text; the conversation's inline cards as a `todo` until US4
- [ ] T044 [US1] Write `W/test/features/steps/journey.steps.ts` for every step of `features/interface/journey.feature` (stages reached, where the investigation waits, unreached; the facets card; a search card; a verification card; the candidates card and no probability; the design card marked a proposal then showing the approval; the journey following without being asked; a failed stage's defect; the impact card and the approved version kept; a card is the stored record; a running stage says so); `just web-features` green for `journey.feature`

**Checkpoint**: US1 complete: the MVP a person can use, with the API for decisions.

---

## Phase 5: User Story 3 - Decide on the card, as a named reviewer (Priority: P1)

**Goal**: every reviewer decision made on its card, as a configured reviewer with a rationale, recorded by the operator's route; the review queue in the rail.

**Independent Test**: at each pause, decide on the card as `demo-reviewer` and confirm through the API that the record carries the reviewer, rationale and time and the workflow resumed.

- [ ] T045 [P] [US3] Write `W/src/decisions/Reviewer.tsx` (a select from `GET /reviewers`, remembered for the session in component state, never in storage) and `W/src/decisions/Form.tsx` (the shared frame: reviewer, rationale, submit, the route's refusal shown verbatim, refetch of the card on success)
- [ ] T046 [P] [US3] Write `W/src/decisions/FacetReview.tsx` (per facet: confirm, amend with the amendment, reject, leave unresolved and flagged, assume, posting to `/facets/{id}/review` and `/facets/{id}/assume`) and `W/src/decisions/FacetsConfirmed.tsx` (`POST /requests/{id}/facets:confirmed`; the refusal naming the unsettled facet shown) and mount them in `FacetsCard`'s slot and the bar's primary action
- [ ] T047 [P] [US3] Write `W/src/decisions/RecordReview.tsx` (verify, reject, citation complaint, posting to `/findings|claims|assessments/{id}/review`) and mount it in `RecordCard` and `AssessmentCard`
- [ ] T048 [P] [US3] Write `W/src/decisions/Decide.tsx` (select one approach, a reason per rejected, `POST /requests/{id}/decide`) mounted in `CandidatesCard`, and `W/src/decisions/Approval.tsx` (approve, reject, request changes with the version, `POST /designs/{id}/approval`) mounted in `DesignCard`
- [ ] T049 [US3] Write `W/src/areas/Queue.tsx`: the review queue from `GET /review-queue`, each record beside its passages as `RecordCard` with `RecordReview`; a reviewed record leaves the list on refetch; the inspector shows the selected record's source card
- [ ] T050 [US3] Write `W/test/features/steps/decisions.steps.ts` for every step of `features/interface/decisions.feature` (eight facets on the card; confirming each mandatory facet then the set; the history holds reviewer, time, rationale; the refusal naming an unsettled facet; selecting an approach keeps the rejected one's reason; approving version 1; requesting changes writes a new version and keeps the earlier; `nobody` refused and nothing recorded; rejecting in the queue removes it; the record equals what the operator's route records, checked by making the same decision through the API on a second request and comparing the records field by field except id and time); `just web-features` green for `decisions.feature`

**Checkpoint**: US1 to US3 complete: the walkthrough's three decisions are clicks; SC-002 holds with the API for actions.

---

## Phase 6: User Story 4 - Ask the assistant and be answered from the records (Priority: P2)

**Goal**: the conversation: an agent answering from the records with typed references rendered as cards, streamed, budgeted, per request or free-standing, unavailable plainly without a model.

**Independent Test**: with the reference request at an approved design and a scripted model, the four questions of SC-005 each cite the right records and show their cards; "approve the design" yields a prefilled approval form and no approval record.

- [ ] T051 [P] [US4] Write `S/application/entities/ConversationEntity.scala` per data-model.md: events `conversation-opened`, `turn-answered`, `action-proposed`, `action-performed`, `action-declined` with serializers in the companion; state; commands `open`, `ask` (refuses at the budget with `Conflict` and the exact message), `answered`, `propose`, `performed`, `declined`; query `get`; register it in `S/Main.scala`
- [ ] T052 [P] [US4] Write `T/unit/ConversationEntitySuite.scala` with the entity kit: open is idempotent and fixes the budget; `ask` refused at the budget; `answered` refuses a decreasing total; a proposal numbered; `performed` and `declined` only once; two ids are two states
- [ ] T053 [P] [US4] Write `R/prompts/assistant/v1.md` with the instructions contracts/assistant.md requires (answer from tools, markers only, no probabilities, no decisions, prefill wording, proposals need confirmation, the subject from context, brevity) and load it through the prompts loader in `S/application/agents/Prompts.scala`
- [ ] T054 [US4] Extract the record readers `ApiEndpoint` uses into `S/api/Readers.scala`, then write `S/application/agents/AssistantAgent.scala`: handler `answer(question)` with `effects.systemMessage(prompt).withContext(the subject's summary when the conversation has one).userMessage(question).model(Configured.assistantModel.get).thenStream()` registered with `stream`, `maxToolCallSteps = 12`; the eight read tools (`list_requests`, `get_request`, `get_question`, `get_record`, `get_evidence`, `get_design`, `list_sources`, `review_queue`) returning the API's records through `Readers`, with descriptions and error messages per the contract; `propose_action` stubbed to return the validation message (completed in US5); register in `S/Main.scala`; set `Configured.assistantModel` in `Main` per research R12 (`AnthropicProvider` with `EDP_ASSISTANT_MODEL` when `ANTHROPIC_API_KEY` is set, `ScriptedModelProvider` when `EDP_ASSISTANT_SCRIPT` is set, otherwise `None`)
- [ ] T055 [US4] Add to `S/api/ApiEndpoint.scala` the routes of contracts/http-api.md: `GET /assistant`; `GET /conversations/{id}`; `POST /conversations/{id}` (validates the id form and the subject); `sseBody("/conversations/{id}/turns")`: `503` without a model, `ask` on the entity (`409` at the budget), then the agent's stream mapped to `token` events, `proposal` events as the tool records them, and on completion `ReferenceMarkers.parse`, resolution of each reference through `Readers`, the session's usage from `forSessionMemory(id).history`, `answered` on the entity and a `done` event; on failure an `error` event and nothing recorded; a busy session answers `409`; check R20.5 and R20.6 and record the answers in research.md; add the codecs to `S/api/Json.scala`; document in `docs/reference/http-api.md`
- [ ] T056 [US4] Write `T/integration/AssistantSuite.scala` with a second `TestModelProvider` set as `Configured.assistantModel`: each of SC-005's four questions scripted as tool calls then text with markers, asserting the tools called with the right arguments and the `done` event's references all resolved; an unknown id gives `resolved: false`; a prefill marker gives `prefill` in `done` and no approval record; a scripted failure mid-stream gives `error` and `GET /conversations/{id}` shows no turn; two conversations do not share memory (the second's model sees no history); a request's conversation receives its subject in context (assert on `model.lastRequest`); no model gives `503` and `GET /assistant` says why; and `T/integration/ConversationBudgetSuite.scala`: `Configured.conversationBudget` small, usage reported by the scripted provider, the second turn refused `409` with the message, `used` and `budget` on the conversation, a new id starts fresh
- [ ] T057 [US4] Write `W/src/conversation/Conversation.tsx`, `W/src/conversation/Turn.tsx`, `Composer.tsx`, `useConversation.ts`: open with `POST /conversations/{id}` (id `request:<id>` on `/requests/:id/chat`, `free:<key>` from `localStorage` `edp.conversation` on `/chat`), load turns, send a question through `readEvents` on `turns`, append `token` text as it streams with `aria-live="polite"`, split markers with `markers.ts` and render each as its card from `records.ts` (an "unresolved reference" chip when `done` says so or the fetch fails), a prefill as the decision's form with fields set, the failed turn marked and kept until reload; `used of budget tokens` under the composer; the composer disabled with the reason at the budget or when `GET /assistant` is unavailable
- [ ] T058 [US4] Add to `W/src/areas/Request.tsx` the "discuss" item opening the request's conversation as a panel beside the canvas (toggle in the bar; the whole page below 720 px) and the card's "show its stage" selecting the node; route the rail's conversation area to `/chat` in `W/src/shell/Rail.tsx`
- [ ] T059 [US4] Write `evals/assistant/walkthrough.json`: rules for the four questions of SC-005 with tool calls and markers to the reference request's deterministic ids, "show me the facets", a question the records do not answer, "approve it" with a prefill, a reference that does not resolve, the free-standing "which requests are waiting", and the US5 actions (`propose_action` tool turns); and `docs/reference/assistant-script.md` describing the file
- [ ] T060 [US4] Write `W/test/features/steps/conversation.steps.ts` for the US4 steps of `features/interface/conversation.feature` (the four answers and their cards; every cited record a card; the prefilled decision and no approval; what is not established; an unresolved reference; the answer appearing as written, checked by observing at least two `token` renders; a failure recording nothing, driven by a script rule whose text is empty and the backend's `error`; two conversations; the request's subject and the card selecting the stage; the free-standing conversation across two requests; no model, as the scenario tagged `@no-model` in the `no-model` project of T026, run against a stack started without `EDP_ASSISTANT_SCRIPT`; the budget reached and a fresh one; the spend shown); `just web-features` green for `conversation.feature` except the US5 scenarios

**Checkpoint**: US4 complete; SC-005, SC-006 and SC-007 hold.

---

## Phase 7: User Story 5 - Act from the conversation, with confirmation (Priority: P2)

**Goal**: the operator's four actions proposed by the assistant with exact arguments, performed only on confirmation, their result a card; the sources area in the rail.

**Independent Test**: ask to ingest Paper A, confirm, check the source and its verified records through the API; ask to mark Paper B retracted, confirm, check the impact flags.

- [ ] T061 [US5] Complete `propose_action` in `S/application/agents/AssistantAgent.scala`: `Actions.validate`, `propose` on the conversation entity (the conversation id captured in the handler's closure), the tool's reply `proposed as #n; the person must confirm it`; the `proposal` SSE event in `S/api/ApiEndpoint.scala`'s turn stream
- [ ] T062 [US5] Extract `submitRequest`, `ingestSource`, `injectFault` and `setSourceStatus` from `S/api/ApiEndpoint.scala` into `S/api/Operations.scala`, used by the routes and by the new `POST /conversations/{id}/proposals/{n}:confirm` and `:decline` in `ApiEndpoint`: perform the action, record `performed(n, reference)` or `declined(n)`, answer per the contract, pass the action's own refusal through, `409` on a repeat
- [ ] T063 [US5] Extend `T/integration/AssistantSuite.scala`: a scripted `propose_action` records a proposal and nothing else (no request, no source); confirm performs it and the result's reference resolves; a second confirm `409`; decline records `declined`; an invalid action's message reaches the model as the tool result; the four actions each perform exactly what their route performs (compare the record with one made through the route)
- [ ] T064 [P] [US5] Write `W/src/conversation/Proposal.tsx`: the action, each argument, confirm and decline buttons, the result's card after confirmation, the refusal shown; rendered on the `proposal` event and from the conversation's `proposals` on load
- [ ] T065 [P] [US5] Write `W/src/areas/Sources.tsx`: `GET /sources` with versions and status, each row opening `SourceCard` in the inspector with its records; a "set status" form posting `POST /sources/{id}/status` as the operator (no reviewer, per the route)
- [ ] T066 [US5] Write the US5 steps in `W/test/features/steps/conversation.steps.ts` (a typed request submitted on confirmation and its journey shown; a source ingested and its card with records as they are verified; a fault injected and the finding's card showing `unsupported endpoint`; an unconfirmed action recording nothing; an action not given declined with where it is done); `just web-features` green for all of `conversation.feature`

**Checkpoint**: US5 complete: the walkthrough needs no terminal.

---

## Phase 8: User Story 6 - Run the interface beside the service, on a laptop and deployed (Priority: P3)

**Goal**: one command locally against the container or the build; a web-hosted service beside the service on the platform; the token entered once.

**Independent Test**: `just up-app`, `just web`, open, enter the token, perform the walkthrough.

- [ ] T067 [US6] Write `W/Dockerfile` (Node 24 alpine; a build stage running `npm ci` and `npm run build`; a runtime stage with `npm ci --omit=dev`, `CMD npm start`, listening on `PORT`) and check `just web-image` builds on macOS and Linux; add `web/dist`, `web/node_modules`, `web/test-results`, `web/.features-gen` to `.gitignore`
- [ ] T068 [US6] Check R20.9 (`ankka local web` passes `Authorization` to the mounted service) and time the compose path (`just up-app` then `just web`, usable within one minute, recorded in research.md "Measurements"); fix the `EDP_DECISIONS` line in `.env.example` to a quoted value so `just`'s dotenv loader accepts it, and say so in `README.md`
- [ ] T069 [US6] Write `W/test/features/steps/admission.steps.ts` for `features/interface/admission.feature` (the token admits once and is not asked again in the same browser context; a wrong token shows nothing and says it was refused; every card's content comes from the service, checked by intercepting `/backend` requests and asserting each card's record was fetched; the interface usable within one minute, asserted on the `webServer` start and first paint); `just web-features` green for all four files
- [ ] T070 [US6] Write `docs/get-started/interface.md` (run it beside the compose profile or `sbt run`, the token, the walkthrough in the browser per quickstart.md) and `docs/deploy/interface.md` (`web/service.json`, the mount, the image, `ankka services apply`, what `ankka services get` shows); link both from `README.md` and the docs navigation in `mkdocs.yml`; rewrite `W/README.md` for this interface

**Checkpoint**: US6 complete; SC-009 holds.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: the success criteria that span stories, the honest list, and the record of what was found.

- [ ] T071 [P] Write `W/test/accessibility.spec.ts` (SC-008): axe with `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` on the admission screen, the requests listing, the canvas with each card kind in the inspector, the queue, the sources, the conversation with a streamed answer, a proposal and a prefill, and the narrow layout at 390 px; zero violations; check R20.10 and record it in research.md
- [ ] T072 [P] Write `W/test/walkthrough.spec.ts` (SC-001, SC-002): the whole reference walkthrough by keyboard only (`Tab`, `Enter`, `Space`, arrows; no `click`), with a trace recorded to `W/test-results/walkthrough.zip`; assert each of feature 001's SC-001 to SC-010 on the card that proves it, that exactly three reviewer decisions were made (count the decision records through the API), and that after each of the three decisions the journey's node changed on the open canvas within 5 seconds without a reload (SC-004 in the browser)
- [ ] T073 [P] Write `T/independent/NoDecisionThroughTheAssistantSuite.scala`, from the spec and not the code: every route under `/conversations` and every tool of the assistant, driven with a scripted model that tries each reviewer decision by name, records no facet review, confirmation, record review, decision or approval (query each record after); add to `scripts/mutants/` a patch that gives the assistant a decision tool, which this suite must catch, and extend `scripts/mutants.sh`
- [ ] T074 [P] Extend `docs/limitations.md`: no sign-in; the free-standing conversation is one browser's; conversations are never forgotten; one question lane at a time on the canvas; the assistant needs a key or a script and answers nothing otherwise; the canvas needs script, the review pages do not; the assistant's prose is not evaluated
- [ ] T075 [P] Update `README.md` (the interface in the overview; the diagrams' caption: the automatic stages, the three decisions), `docs/index.md` and `docs/reference/http-api.md` (every route of contracts/http-api.md with its events)
- [ ] T076 Complete the conversation half of `W/test/cards.spec.ts` (cards inline in an answer equal the API's records); run the four Playwright projects and every Scala suite from an empty database; fill research.md "Found during implementation" and "Measurements" (walkthrough time in the browser, stream latency trials, interface start time); run `just check-bdd` and `just fmt`

---

## Dependencies & Execution Order

- **Phase 1, then Phase 2, then the stories.** T019 (the suite split) before T033; T023 and T024 before any `W/src` story task; T015 and T016 before T038.
- **US2 (Phase 3)** depends only on Phase 2's service side (T014 to T016) and runs in parallel with the interface's Phase 2 tasks.
- **US1 (Phase 4)** depends on Phase 2's interface side and on T016; it does not depend on US2 (the journey shows whatever the workflow does), except that journey.feature's "follows without being asked" scenario needs US2 to pass.
- **US3 (Phase 5)** depends on US1's cards (the slots) and on T045.
- **US4 (Phase 6)** depends on US1's cards (inline rendering) and on T009 and T018; its service tasks T051 to T056 can start as soon as Phase 2 is done, in parallel with US1 and US3.
- **US5 (Phase 7)** depends on US4 (T054, T055, T057).
- **US6 (Phase 8)** depends on everything a walkthrough touches; T067 and T068 can start after Phase 2.
- **Phase 9** after every story.

## Parallel Execution Examples

- Phase 2: T009, T010, T012, T018, T020, T021, T022, T025, T026 in parallel (different files); T011 and T013 once their subjects exist; T014, T015, T016, T017 in sequence.
- After Phase 2: US2's T028 to T031 (service) beside US1's T036, T039, T040, T041 (interface).
- US3: T045 to T048 in parallel, then T049, T050.
- US4: T051, T052, T053 in parallel, then T054, T055, T056 in sequence; T057 can be written against the contract in parallel with T054 to T056 and tested once T055 lands.
- Phase 9: T071 to T075 in parallel; T076 last.

## Implementation Strategy

1. **Foundation first** (Phases 1 and 2): nothing a person sees yet, but the journey route, the stream, the shell and the runners are proven by their own suites.
2. **MVP = US2 + US1** (Phases 3 and 4): the investigation runs itself and a person watches it on the canvas, deciding through the API. This is demonstrable and proves the hardest decisions (R1, R2, R3, R6).
3. **US3** makes the three decisions clicks: the walkthrough is then free of the API except for actions.
4. **US4 and US5** add the assistant and the actions: the walkthrough needs no terminal.
5. **US6 and polish** close the success criteria and the honest list.

Each phase ends green on the suites it names before the next begins, and each task is committed on its own with a `Tasks: Tnnn` footer.
