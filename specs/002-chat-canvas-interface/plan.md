# Implementation Plan: A chat and a canvas for experiment proposals

**Branch**: `002-chat-canvas-interface` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-chat-canvas-interface/spec.md`

## Summary

Three pieces of work, all in this repository, each with its own suites:

1. **The investigation runs itself.** The question workflow no longer rests after retrieval: it
   waits, within a bound, for the verification of every record its search retrieved to settle,
   assesses the verified eligible ones, judges sufficiency for each configured decision, and reports
   to the investigation workflow, which builds the packages and proposes the candidates when every
   question has reported. The manual commands keep working from every rest. Nothing is changed in
   any stage, gate, prompt or cassette; the automation calls the same steps the operator's routes
   call, under the actor `system:investigation`.
2. **An assistant inside the service.** One agent, one session per conversation, nine tools: eight
   that read the records and one that proposes an operator's action. It streams prose carrying
   typed reference markers; a conversation entity records each answered turn with its references,
   each proposal and its confirmation, and the tokens spent against a configured budget. The journey
   of a request is derived on the server from the workflow states and the stage runs and served once
   and as a stream.
3. **The interface**, `web/`: a web-hosted service made from the platform's web template, hosting
   its own dark look (research R21), with the backend mounted under
   `/backend`. A rail, a canvas of the journey, an inspector of the selected stage's card and the
   conversation as a panel. One card component per record kind, each showing the record the mounted
   backend returns and nothing else. Decisions are forms on the cards that call the backend's own
   routes as a named reviewer. Admitted by the shared token, kept by the browser. Checked by
   Playwright with axe, and the interface's living features run in the browser against the replay
   tier with the assistant's model scripted from a file.

The design keeps feature 001's two rules at the centre: every factual thing on a screen is a stored
record read at the time of showing (provenance over prose), and the assistant can propose and
prefill but never record a decision (human authority).

## Technical Context

**Language/Version**: service: Scala 3.9.0 on JDK 21, sbt 1.12.x, as feature 001. Interface:
TypeScript 7 and Vite 8 as the platform's web template pins them, on Node 24, React 19, React
Router 8 (library mode), from `ankka init --language web` (CLI 0.14.2).

**Primary Dependencies**: service: ankka 0.12.0 unchanged (`ankka-sdk`, `-runtime`, `-http`,
`-agent`, `-testkit`); nothing new. Interface: `react`, `react-dom`, `react-router`; Inter served
from the interface's own files; `lucide-react` for line icons; `@playwright/test`,
`@axe-core/playwright` and `playwright-bdd` for the browser suites. No chart or graph library: the
canvas is a fixed layout of lights and labels with SVG curves (research R21).

**Storage**: the service's Postgres as before. New: one event sourced entity (`conversation`) and the
agent runtime's session memory per conversation; no new view tables except a watchable query on
`stage-rows`. The interface stores nothing on a server; the browser keeps the shared token and the
free-standing conversation's key in `localStorage`.

**Testing**: service: unit kits for the journey derivation, the reference markers, the action
arguments and the conversation entity; `AnkkaTestKit` integration suites with two `TestModelProvider`s
(one the pipeline's, one the assistant's) for the automation, the assistant, the journey stream and
the budget; the Scala living features split into one `GherkinSuite` per area directory, so that
`features/interface/` is left to the browser. Interface: `node --test` for the server and the
reference parser, Playwright for `features/interface/*.feature` through `playwright-bdd`, axe on
every view, a keyboard-only walkthrough recorded as a trace, and the card comparison of SC-003.

**Target Platform**: a developer's macOS or Linux machine (`just up-app` then `just web`), and an
ankka installation where the interface is a web-hosted service beside `ankka-experiment-proposals`.

**Project Type**: one ankka service (unchanged shape) plus one web-hosted interface in `web/`.

**Performance Goals**: a workflow change visible on an open canvas within 5 s (SC-004; the stream
re-derives on every stage-row event and on a 2 s poll of the workflow states while the stream is
open); the first token of an answer within the model's latency; the reference walkthrough in the
browser under 10 minutes in replay (SC-001); the interface usable within one minute of its command
(SC-009).

**Constraints**: the assistant's model calls are bounded by `maxToolCallSteps` (12) and the
conversation's budget; no reviewer decision has a path through the assistant; every card is a
fetch of the record at the time of showing, with no cache beyond the component's state; the
console's tokens are imported, not copied, and the glow is not raised; the interface needs script
and the review pages of feature 001 remain for a browser without it; the operator's routes and the
walkthrough script keep working; a feature scenario passes in the tier it is written for; ankka's
own rules (handlers return effects, wire names are protocol, one database per service).

**Scale/Scope**: one reference request with eight facets, three questions and a few dozen records;
sixteen card kinds; four rail areas; one assistant; a conversation of a few dozen turns; two people.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, as the family's conventions say. The
gates are the ankka family's principles (`ankka-brain/principles.md`) and the product's own, as in
feature 001's plan.

| Principle | Gate | How this plan meets it |
|---|---|---|
| Family 1: no BSL on the path | Every new dependency is MIT, Apache or similar | React, Vite, React Router, Playwright, axe-core, playwright-bdd, lucide-react: all MIT or MPL-2.0 (axe-core). `ankka-console` is the family's own. Checked again in `research.md` R3. |
| Family 3: effects are inert data | Handlers describe; nothing does I/O when built | The new entity, command and steps return effects; the journey derivation is a pure function in `domain`; the stream is a `Source` the endpoint returns. |
| Family 4: explicit registration | Every component is listed in `Main.descriptors` | `ConversationEntity` and `AssistantAgent` are added to the inventory; the interface has no components. |
| Family 6: be your own customer | What ankka cannot do becomes a request | `notes/ankka-requests.md` gains: a `GherkinSuite` that excludes files or runs by tag; a watch over a workflow's state; a usage figure on a streamed reply. |
| Family 7: only a tag publishes | No version in the tree; downstream pins releases | `web/package.json` carries `0.0.0`; the console package is pinned at `0.15.1`; nothing is published by this feature. |
| Family 8: the honest not-implemented list | `docs/limitations.md` is extended | Adds: no sign-in; one browser's free-standing conversation; one question lane on the canvas at a time; the assistant unavailable without a key. |
| Family 10: same conventions | Justfile, spec-kit, scalafmt, serialised tests, docs tool | `web/` gets thin Justfile recipes, the same hook, its own `npm test`; the Scala suites stay serialised; docs pages added for the interface. |
| Product: provenance over plausible prose (spec 001 §2, principle 6) | Nothing shown that is not a record | Cards render the API's record; the assistant's references resolve to cards or are shown unresolved; the journey is derived, not stored, and names the stage runs it is derived from. |
| Product: human authority (principle 9) | No decision without a named reviewer's act | The assistant has no tool that calls a reviewer command; decisions are forms on cards that call the existing routes; a proposal is performed only by a separate confirmation call. |
| Product: no probabilities, no in-place edits, bounded loops | As feature 001 | The candidates card shows no probability; every change is a new event; the assistant loop is bounded by steps and budget; the verification wait is bounded by `EDP_VERIFICATION_BOUND`. |

No gate fails. Re-checked after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/002-chat-canvas-interface/
├── plan.md              # this file
├── research.md          # Phase 0: R1 to R20, verify at implementation, found during implementation
├── data-model.md        # Phase 1: the conversation entity, the workflow changes, the journey, settings
├── quickstart.md        # Phase 1: run it, walk it through in the browser, run the suites
├── contracts/
│   ├── http-api.md      # the routes the interface and the assistant use, new and existing
│   ├── assistant.md     # the agent: instructions, tools, reference markers, proposals, scripting
│   ├── journey.md       # the stages, their order, and how each node's state is derived
│   └── interface.md     # the descriptor, layout, cards, decisions, admission, accessibility
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
service.json                         # unchanged; the interface has its own
docker-compose.yml                   # unchanged (profile app)
Justfile                             # + web, web-test, web-features, features-replay running the split suites
docs/                                # + interface pages; limitations extended; http-api extended
features/
├── interface/*.feature              # run in the browser (web/test/features)
└── investigation/automation.feature # run by the Scala suite
notes/ankka-requests.md              # + three requests (see Constitution Check)
src/main/resources/prompts/assistant/v1.md   # the assistant's instructions, versioned like the stages'
src/main/scala/com/thinkmorestupidless/experimentproposals/
├── Main.scala                       # + ConversationEntity, AssistantAgent; the assistant's provider
├── config/Settings.scala            # + EDP_CONVERSATION_BUDGET, EDP_VERIFICATION_BOUND, EDP_ASSISTANT_MODEL, EDP_ASSISTANT_SCRIPT
├── config/Configured.scala          # + assistantModel: Option[ModelProvider], conversationBudget, verificationBound
├── domain/
│   ├── journey/Journey.scala        # the stages, node states, and the pure derivation (contracts/journey.md)
│   └── conversation/                # Turn, Reference, ReferenceMarkers (parse, validate), Proposal, the four actions
├── application/
│   ├── entities/ConversationEntity.scala
│   ├── views/StageRows.scala        # + watchable byRun query (selects row_key)
│   ├── workflows/QuestionInvestigationWorkflow.scala  # + await-verification, assess-all, judge-all steps; questionDone report
│   ├── workflows/InvestigationWorkflow.scala          # + question-reported command; automatic build-packages when all reported
│   ├── agents/AssistantAgent.scala  # the agent and its nine tools
│   └── journey/JourneyReader.scala  # reads the states and stage runs, derives, and the change stream
├── replay/ScriptedModelProvider.scala   # the assistant's model from EDP_ASSISTANT_SCRIPT (browser suites, demos)
└── api/
    ├── ApiEndpoint.scala            # + journey, journey stream, conversations, turns (SSE), proposals, assistant status
    └── Json.scala                   # + codecs for Journey, Conversation, Turn, Reference, Proposal
src/test/scala/com/thinkmorestupidless/experimentproposals/
├── unit/                            # JourneySuite, ReferenceMarkersSuite, ActionsSuite, ConversationEntitySuite
├── integration/                     # AutomationSuite, AssistantSuite, JourneyStreamSuite, ConversationBudgetSuite
├── features/                        # one GherkinSuite per area directory; InvestigationFeatures runs automation.feature
└── independent/                     # a test from the spec: no route lets the assistant record a decision
web/
├── package.json (0.0.0), tsconfig.json, vite.config.ts, index.html, Dockerfile, README.md
├── service.json                     # hosting web, processPort 3000, mount /backend → ankka-experiment-proposals
├── server/server.ts                 # the template's server: serves the app; no route of its own
├── src/
│   ├── main.tsx, App.tsx, routes.tsx # the router: /, /requests/:id, /queue, /sources, /chat, /requests/:id/chat
│   ├── backend/                     # fetch with the bearer token, the SSE reader, the record types, one client per route
│   ├── shell/                       # host of Shell and Backdrop; the bar, the rail, the inspector, narrow-screen panel
│   ├── token/                       # the admission screen and the token in localStorage
│   ├── journey/                     # the canvas: Journey → nodes and edges; selection; the stream subscription
│   ├── cards/                       # one file per record kind (contracts/interface.md), and Card, Field, Passage, Check
│   ├── decisions/                   # the forms: facet review, facets confirmed, record review, decide, approval
│   ├── conversation/                # the panel: turns, streaming, markers → cards, proposals, budget, unavailable
│   └── areas/                       # requests, review queue, sources listings
└── test/
    ├── server.test.ts, markers.test.ts            # node --test
    ├── features/                                  # playwright-bdd: steps for features/interface/*.feature
    ├── walkthrough.spec.ts                        # SC-001 in the browser, keyboard only, traced
    ├── cards.spec.ts                              # SC-003: every card kind against the API
    └── accessibility.spec.ts                      # SC-008: axe on every view
```

**Structure Decision**: the service keeps feature 001's layout and gains two packages (`domain/journey`,
`domain/conversation`) and one agent. The interface is its own npm project in `web/`, with its own
descriptor, image and tests, beside the service rather than in a second repository (spec Assumptions).
It is not an sbt module: sbt builds nothing of it, and CI runs it as a separate job.

## The component table

| Requirement | Component | Why this one | The close alternative, and why not |
|---|---|---|---|
| FR-008, FR-008a: run the question's stages after retrieval | steps added to `QuestionInvestigationWorkflow`: `await-verification`, `assess-all`, `judge-all` | the stages already are workflow steps with timeouts, retries and defect classification; adding transitions reuses all of it | a consumer on record verification events starting assessment: fires per record, cannot know when the set is complete, and has no retry budget of its own |
| FR-008: build packages when every question has reported | a `question-reported` command on `InvestigationWorkflow`, sent by the question workflow's last step; transitions to `build-packages` when all questions have reported | the parent is paused and a command is how a paused workflow moves; a repeat of the command is harmless because it records a set | the parent awaiting each question's end: the question workflow must not end, because the manual routes keep working on it |
| FR-008a: bounded wait | `await-verification` awaits each unsettled record's verification workflow with the remaining bound, then checks statuses | the platform's way to wait for a workflow is to await its end, not to poll | a timer: a timer is for things that are not workflows |
| FR-015, FR-021a: conversations with history and a budget | `ConversationEntity`, event sourced, id `request:<id>` or `free:<key>` | the product's record of a conversation (references, proposals, spend) is distinct from the model's memory and must survive and be queried; the budget is a rule about one thing with an id | the session memory alone: it holds the model's messages, not typed references or proposals, and has no rule |
| FR-016 to FR-019: the assistant | `AssistantAgent`, one handler `answer` streaming, nine tools | an agent is the component when the decision needs a model and tools; streaming is a handler mode | a workflow calling an agent: nothing here waits for a person inside the model call; the confirmation is a separate HTTP call |
| FR-019: actions only on confirmation | the `propose_action` tool records a proposal on the conversation; `POST …/proposals/{n}:confirm` performs it through the same commands as the route | the model never performs; the person's confirmation is an HTTP call the model cannot make | tools that perform and ask the model to ask first: the loop retries nothing, and a repeated tool call would act twice |
| FR-001, FR-006: the journey and its live changes | `Journey` derived in `domain`; `JourneyReader` reads two workflows and the stage run; a stream merging the `stage-rows` watch with a 2 s re-read while open, emitting on change | workflow state cannot be watched; stage runs can, and a pause is at most 2 s behind | the browser polling: the spec says the update comes from the service's notification |
| FR-022: admission | the bearer token on every fetch, including the SSE reads done with `fetch` | `EventSource` cannot send a header; the review pages' cookie is scoped to `/review` on the service's origin, not the interface's | a cookie set by the interface's server: the backend is mounted, so the server never sees the mounted requests |
| FR-023: run beside the service | `web/service.json` with `hosting: web`, mount `/backend`; `ankka local web --service ankka-experiment-proposals=http://127.0.0.1:9000 -- npm run dev` | exactly the platform's shape for a user interface; one command locally | a Vite proxy configured by hand: would differ from deployment |
| FR-025: the look | the interface's own stylesheet, tokens at the top of `web/src/app.css`; the journey as a constellation of lights (R21) | chosen by the product owner on 2026-11-05 over the console's treatment | the console package's stylesheet and shell (R3, superseded) |
| FR-021: testable with a scripted model | `TestModelProvider` as the assistant's provider in suites; `ScriptedModelProvider` from a file for the browser suites and demonstrations without a key | one provider per consumer: the pipeline's and the assistant's are separate | cassettes for the assistant: out of scope by the spec |
| FR-027: accessibility | axe in Playwright on every view; keyboard-only walkthrough traced | the console's own gate, reused | manual checks: not repeatable |

## Phases

- **Phase 0** (`research.md`): the automation's shape inside the two workflows and its interaction
  with the cassettes; the conversation's storage and budget; the reference markers; proposals and
  confirmation; the journey derivation and its stream; hosting the console package; admission under
  a mount; the two feature runners; the scripted assistant; the browser suites and CI; resolved
  open items; the list to verify at implementation.
- **Phase 1** (`data-model.md`, `contracts/`, `quickstart.md`): the conversation entity's id scheme,
  events, state, commands and transitions; the workflow steps and commands added; the journey's
  stages and node states; the settings added; the HTTP routes and SSE event shapes; the agent's
  instructions, tools and marker syntax; the interface's descriptor, layout, cards, decisions,
  token and accessibility contract; how to run and prove it.
- **Phase 2** (`/speckit-tasks`): tasks by user story, P1 first: the automation (US2), the journey
  and cards (US1), the decisions (US3), then the assistant (US4, US5), then running beside the
  service and the browser suites (US6).

## Complexity Tracking

No constitution violations to justify. One deliberate addition: a second feature runner
(`playwright-bdd` in `web/`) beside the Scala `GherkinSuite`, because the interface's scenarios are
observable only in a browser; the checker still reads every feature from one `features/` directory.
