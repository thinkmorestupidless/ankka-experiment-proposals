# Feature Specification: A conversational interface — the chat and the canvas

**Feature Branch**: `002-chat-canvas-interface`

**Created**: 2026-11-04

**Status**: Draft

**Input**: User description: "A conversational interface for experiment proposals: a chat and a canvas instead of API calls and separate pages. Short name: chat-canvas-interface. Who it is for: the operator and the named scientific reviewer, the same two people the service already knows; later a customer scientist. Reviewer decisions remain affirmative acts by a configured reviewer (human authority), and every factual thing shown traces to a record (provenance over plausible prose). Submit a request in plain words from the chat; watch it travel through the stages the investigation diagram draws, as a graph on a canvas whose nodes light up as the workflow reaches them; select any node to see what that stage produced as a styled card in an inspector; ask questions in the chat and get answers that cite records, with the relevant cards inline; trigger what the operator could trigger before, each confirmed; make the reviewer's decisions on their cards as a chosen configured reviewer, never through the assistant alone. After the facets are confirmed, the investigation runs on its own to the next reviewer pause; ingestion stays an explicit act. An assistant inside the service answers from the records and acts through the existing commands, streams its reply, carries typed references to the records it talks about, never invents a record, states a probability or decides, and is unavailable with a clear message when no model is configured while the stages keep replaying. A separate web application beside the backend, hosting the platform console's dark treatment (slate mesh, ember glow, dot grid, glass, one amber accent), laid out as a flow builder: rail, canvas, inspector, chat panel. Keyboard reachable, zero accessibility violations at WCAG 2.1 AA. Out of scope: sign-in and accounts, editing records by hand, changing any rule, gate, prompt or stage, the external adapters, native apps, replacing the operator's API, a light theme beyond what comes for free."

## Overview

Feature 001 built the investigation pipeline and proved it through an API, a script and four plain
pages for the reviewer. A person who wants to use it today needs a terminal: the operator's stages
between the reviewer's decisions are started by hand, every record is read as JSON, and the review
pages are reached by typing an identifier into an address. This feature gives the same pipeline one
place to be used from, by the same two people: a **conversation** in which a person submits a
request, asks about it and is told what the records say, and a **canvas** on which the request's
journey through the stages is drawn as it happens, each stage opening the artefact it produced as a
**card**.

Two of the product's rules decide the shape of everything here. **Human authority**: the assistant
in the conversation explains, summarises and recommends, and can fill a decision in, but every
reviewer decision is still a click by a named reviewer on the decision's own card. **Provenance over
plausible prose**: a card shows the record as it is stored, never the assistant's retelling of it,
and the assistant's answers point at records rather than paraphrase them.

A third change makes the first two usable: the investigation now **runs itself** between the
reviewer's pauses. Search, assessment, sufficiency and packaging no longer wait for an operator to
ask; the workflow pauses only where a person decides or where a stage has failed after its retries,
exactly as feature 001 defines those pauses.

### Actors

- **Scientific reviewer**: as in feature 001, a named entry on the configured list. Confirms facets,
  reviews records, selects an approach, approves or rejects a design, now on cards.
- **Operator**: as in feature 001. Submits requests on the customer's behalf, ingests sources,
  injects reference faults, changes a source's status, reads provenance, now from the conversation
  and the canvas. The reference walkthrough is the operator's job.
- **The assistant**: the conversational agent inside the system. It reads records and starts
  actions a person has confirmed. It decides nothing.
- **Customer scientist** (later): a person who submits a request and follows it. Not admitted
  separately in this feature; the operator acts for them.

### Reference scenario

The eleven-step demonstration of feature 001 (`quickstart.md` there), "Can you make foie gras?" to
an approved design, a caught fault and a retraction, performed by one person in the browser with no
terminal, in the replay tier, with each of feature 001's success criteria SC-001 to SC-010 visible on
a card at the step that proves it.

## Clarifications

### Session 2026-11-04

- Q: Adopt the glossary's 25 proposed terms for the interface (interface, investigation, stage, stage run, automatic stage, pause, journey, card, inspector, rail, review queue, conversation, assistant, person, operator, answer, reference, action, prefilled decision, model, approval, importance, quotation, shared token, operator's route) and their refused synonyms as written? → A: Yes, all adopted; the *Proposed* marks are removed. "Workflow" and "agent" stay refused in the features even though the platform uses them for the implementation.
- Q: What does the automatic assessment stage do when a retrieved source's records are still being verified? → A: It waits, bounded, until every retrieved record's verification has settled (verified, rejected, quarantined or routed to a reviewer), then assesses the verified ones; if the bound passes, the stage fails with the defect "verification unsettled" and the investigation pauses there as for any failed stage.
- Q: What does a conversation belong to? → A: Both kinds: one conversation per request, opened from its journey with that request as its subject, and one free-standing conversation from the rail for questions across requests and for submitting a new request. A request's conversation keeps the journey and the chat in step.
- Q: Is there a bound on what the assistant may spend? → A: A configured budget per conversation, in tokens. When it is reached the assistant declines further questions in that conversation and says so; the cards, the journey and the decisions keep working, and a new conversation starts fresh. No daily cap: that belongs to the installation.

### Session 2026-11-05

- Q: Does the interface keep the platform console's look? → A: No. The product owner chose a look
  of its own: a near-black ground, quiet panels with hairline edges, a rail of named areas in groups, a bar with
  the breadcrumb at the start and the request's own words as its title, a single violet accent, and
  cards tagged by kind. The journey is drawn as a constellation: each stage a light whose
  brightness is its state, with a label beside it, the spine along the top and the bottom and the
  questions' lanes hanging between them; the chosen question's lane is labelled and selectable and
  the others are faint. FR-025 and the Assumptions are changed to match; FR-026 and FR-027 stand.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Follow a request's journey on the canvas and read each stage's card (Priority: P1)

A person opens the interface, picks a request (or has just submitted one), and sees its journey as a
graph of stages: the request, its facets and the round-trip check, the facet review, the scientific
questions, each question's search and eligibility, the findings and claims and their verification,
assessments, sufficiency, packages, the candidates and the decision, the design and its approval,
and any impact flags. Stages the workflow has reached are lit; the one it is at now is marked; a
stage that paused or failed says why. Selecting a stage opens what it produced as a card in the
inspector beside the canvas, in a form made for that kind of record. Progress reaches the page as it
happens, without reloading.

**Why this priority**: it is the whole point. Without the canvas and the cards there is nothing to
converse about, and every later story reads records through them.

**Independent Test**: with a request already run to an approved design through the existing API,
open it in the interface; every stage of the journey is lit, selecting each opens its card, and the
card's content equals the record the API returns. Submit a second request through the API while the
canvas shows it; its facet stage lights within a few seconds with no reload.

**Acceptance Scenarios**:

- added `features/interface/journey.feature`: a journey shows which stages are reached, where the investigation waits and what is unreached
- added `features/interface/journey.feature`: the facets card shows every facet with its origin, its quotation and the round-trip check
- added `features/interface/journey.feature`: a search card shows the plan, the results, the exclusions and the re-run
- added `features/interface/journey.feature`: a verification card shows the finding beside its passages and every check
- added `features/interface/journey.feature`: the candidates card compares the approaches on every criterion and asserts no probability
- added `features/interface/journey.feature`: the design card is marked a proposal until an approval is recorded
- added `features/interface/journey.feature`: the design card shows the approval once it is recorded
- added `features/interface/journey.feature`: a journey follows the investigation without being asked again
- added `features/interface/journey.feature`: a failed stage shows its defect and where the investigation waits
- added `features/interface/journey.feature`: the impact card lists every flagged dependent and the design keeps its approved version
- added `features/interface/journey.feature`: a card is the stored record
- added `features/interface/journey.feature`: a running stage says so

---

### User Story 2 - The investigation runs itself between the reviewer's pauses (Priority: P1)

Once a reviewer confirms the facets, the system derives the questions, plans and executes each
question's search, assesses every verified eligible record the search retrieved against its
question, judges sufficiency for each configured intended decision, builds the packages and proposes
the candidates, and pauses for the reviewer's choice; after the choice it writes the design and
pauses for approval. Nobody asks for any of these stages. Ingesting a source stays a person's act.

**Why this priority**: without it, the conversation would have to narrate "now call packages", and
the canvas would stall after every reviewer decision. It also makes the reference walkthrough a
three-decision job for the reviewer.

**Independent Test**: through the existing API alone, with the fixtures ingested, submit the
reference request and confirm its facets; without any further operator call, the investigation
reaches the awaiting-decision pause with six questions assessed, sufficiency judged for both
configured decisions, six packages and two candidates, and provenance records a stage run for each
stage as before.

**Acceptance Scenarios**:

- added `features/investigation/automation.feature`: confirming the facets carries the investigation to the choice of approach
- added `features/investigation/automation.feature`: every verified eligible finding a search retrieved is assessed
- added `features/investigation/automation.feature`: assessment waits for the retrieved records' verification to settle
- added `features/investigation/automation.feature`: assessment fails when verification does not settle within its bound
- added `features/investigation/automation.feature`: sufficiency is judged for every configured decision
- added `features/investigation/automation.feature`: a stage that fails after its retries pauses the investigation and resumes without duplicates
- added `features/investigation/automation.feature`: an empty corpus does not stop the investigation
- added `features/investigation/automation.feature`: a manual stage still works after the automatic stage ran it
- added `features/investigation/automation.feature`: a source is ingested only when a person asks

---

### User Story 3 - Decide on the card, as a named reviewer (Priority: P1)

Each of the reviewer's decisions is made on the card of the thing decided: confirm, amend, reject or
leave unresolved each facet and then confirm the set; verify, reject or raise a citation complaint on
a record in the review queue; select one candidate approach with a reason for each rejected one;
approve, reject or request changes on a design version. Every decision names a reviewer chosen from
the configured list and carries a rationale. The queue of records awaiting review is reachable from
the rail, each beside its passages.

**Why this priority**: with automation, the reviewer's clicks are the only human acts the
walkthrough needs; without cards to click, the reviewer is back on the plain pages.

**Independent Test**: on a request at each pause, make the decision on its card as `demo-reviewer`
and confirm through the API that the record carries that reviewer, the rationale and the time, and
that the workflow resumed.

**Acceptance Scenarios**:

- added `features/interface/decisions.feature`: confirming the facets on their card records each decision and moves the investigation on
- added `features/interface/decisions.feature`: the facets cannot be confirmed while a mandatory facet is unsettled
- added `features/interface/decisions.feature`: selecting an approach on the card keeps the rejected one with its reason
- added `features/interface/decisions.feature`: approving a design version on the card records the approval
- added `features/interface/decisions.feature`: requesting changes on the card keeps the earlier version and its decision
- added `features/interface/decisions.feature`: a name that is not a configured reviewer cannot decide
- added `features/interface/decisions.feature`: rejecting a record in the review queue removes it from the queue
- added `features/interface/decisions.feature`: a decision on a card is recorded exactly as the operator's route records it

---

### User Story 4 - Ask the assistant and be answered from the records (Priority: P2)

A person types a question about a request or any of its records: "why is Paper B indirect?", "what
blocked the recommendation?", "show me the facets", "what did the search exclude and why?". The
assistant answers in words, citing the records it read, and the cards for those records appear
inline in the conversation. The reply appears as it is written. The assistant does not invent a
record, state a probability of success, or decide anything for a reviewer; if the records say the
evidence is insufficient, so does the assistant. A typed wish to decide ("approve it") fills the
decision's card in and asks the reviewer to click it.

**Why this priority**: it is what the feature is named for, but it depends on the cards and on the
records being readable, which stories 1 to 3 provide.

**Independent Test**: with the reference request run to an approved design, ask the four questions
above with a scripted model and check each answer names the right records, that the cards shown
equal the records the API returns, and that "approve the design" produces a filled approval card
and no approval record.

**Acceptance Scenarios**:

- added `features/interface/conversation.feature`: why a finding is indirect is answered with the species rule and its assessment
- added `features/interface/conversation.feature`: what blocked the recommendation is answered with the critical gap and the sufficiency judgement
- added `features/interface/conversation.feature`: what the search excluded is answered with the retracted source and its rule
- added `features/interface/conversation.feature`: showing the facets shows their card
- added `features/interface/conversation.feature`: every record an answer cites is shown as its stored card
- added `features/interface/conversation.feature`: a wish to approve becomes a prefilled decision, not an approval
- added `features/interface/conversation.feature`: a question the records do not answer is answered with what is not established
- added `features/interface/conversation.feature`: a reference that does not resolve is shown as unresolved
- added `features/interface/conversation.feature`: an answer appears as it is written
- added `features/interface/conversation.feature`: an answer cut short by a failure records nothing
- added `features/interface/conversation.feature`: two conversations do not share a history
- added `features/interface/conversation.feature`: a request's conversation has that request as its subject
- added `features/interface/conversation.feature`: the free-standing conversation answers across requests
- added `features/interface/conversation.feature`: without a model the assistant says so and everything else works
- added `features/interface/conversation.feature`: a conversation that reaches its budget declines further questions
- added `features/interface/conversation.feature`: a conversation shows what it has spent of its budget

---

### User Story 5 - Act from the conversation, with confirmation (Priority: P2)

A person asks the assistant to do what the operator could do through the API: submit a request,
ingest a source by adapter and identifier, inject a reference fault, change a source's status.
The assistant states the action it is about to take with its exact arguments and does it only once
the person confirms; the result is a card. The list of ingested sources and their status is
reachable from the rail.

**Why this priority**: it removes the last reason for a terminal in the walkthrough, but each action
also has a manual route that already works.

**Independent Test**: ask to ingest Paper A, confirm, and check the source record and its verified
records through the API; ask to mark Paper B retracted, confirm, and check the impact flags.

**Acceptance Scenarios**:

- added `features/interface/conversation.feature`: a request typed in the conversation is submitted on confirmation
- added `features/interface/conversation.feature`: a source is ingested from the conversation on confirmation
- added `features/interface/conversation.feature`: a fault is injected from the conversation on confirmation
- added `features/interface/conversation.feature`: an action that is not confirmed records nothing
- added `features/interface/conversation.feature`: an action the assistant is not given is declined with where it is done

---

### User Story 6 - Run the interface beside the service, on a laptop and deployed (Priority: P3)

The operator starts the interface on their machine against a service that is running locally,
whether in a container or from the build tool, with one command; and deploys it beside the service
on the platform, reached at its own address, with the service's records available under it. The
person is admitted with the same shared token the review pages take today, entered once.

**Why this priority**: it is how anyone gets to use any of the above, but it is plumbing.

**Independent Test**: start the service in the compose profile, start the interface with the
documented command, open it, enter the token, and perform the reference walkthrough.

**Acceptance Scenarios**:

- added `features/interface/admission.feature`: the shared token admits a person once
- added `features/interface/admission.feature`: a wrong token shows nothing
- added `features/interface/admission.feature`: every card comes from the service beside the interface
- added `features/interface/admission.feature`: the interface starts beside a locally running service with one command

---

### Edge Cases

- A request submitted twice with the same text is two requests with two journeys; the canvas shows
  the one selected.
- A request with many questions: the canvas groups the per-question stages under their question and
  lets the person open one question's branch at a time rather than drawing every question's stages
  at once.
- The conversation's connection drops while an answer is being written: the partial answer is kept,
  marked incomplete, and the next question continues the same conversation.
- The assistant's reply names a record the person is not allowed to see or that no longer resolves:
  the reference is shown as unresolved, never as a card with made-up content.
- A stage is still running when its node is selected: the card says the stage is running and shows
  what earlier attempts recorded, if any.
- A retrieved source's records are still being verified when the assessment stage is reached: the
  stage waits for them within its bound (FR-008a); the journey shows it as current with "waiting for
  verification" rather than as failed.
- The reviewer's list changes between opening a card and deciding: the decision is refused with the
  name, as the API refuses it.
- The model behind the assistant fails or times out mid-answer: the answer is marked failed and the
  person may ask again; nothing is recorded by a failed answer.
- A very long design or passage on a card: the card scrolls within the inspector; nothing is
  truncated silently.
- Narrow screens: the conversation is the whole page, the canvas and inspector are reached from it and
  return to it; nothing is only reachable with a pointer.

## Requirements *(mandatory)*

### Functional Requirements

**The journey and its cards**

- **FR-001**: The interface MUST show, for a selected request, its journey as a graph of the stages
  of feature 001's investigation (request, facets, round-trip check, facet review, questions, and per
  question: search plan, executions, eligibility, extraction and verification of the records
  retrieved, assessment, sufficiency; then packages, candidates, decision, design, approval, impact),
  with each stage shown as reached, current, paused, failed or unreached according to the recorded
  workflow state and stage runs.
- **FR-002**: Selecting a stage MUST show what it produced as a card whose content is the stored
  record (or records) for that stage, read from the system at the time of showing, and the card
  MUST equal the record the API returns for the same identifier and version.
- **FR-003**: There MUST be a card form for each kind of record: request, facet set with round-trip
  check, scientific question, search plan, search execution, source and source version, finding,
  claim, assessment, sufficiency, evidence package, candidate approach set with its comparison,
  decision record, experimental design with its approval history, impact flag, and stage run.
- **FR-004**: A finding's or claim's card MUST show each cited span beside the passage it resolves
  to, and MUST show every verification check with its result and the verifier's rationale.
- **FR-005**: The candidates card MUST show the approaches side by side on the eight comparison
  criteria and MUST show no probability of success; the design card MUST render the twelve sections
  and MUST be marked a proposal until an approval is recorded.
- **FR-006**: The journey MUST reflect a change in the request's workflow state or stage runs
  without the person reloading, within a few seconds of the change being recorded.
- **FR-007**: The interface MUST list every request, newest first, with where each stands, and MUST
  list every ingested source with its status, and MUST show the review queue with each record
  beside its passages.

**Automation of the investigation**

- **FR-008**: After the facets of a request are confirmed, the system MUST derive the questions and,
  for each question, plan and execute the search, run assessment for every verified eligible record
  the search retrieved, and judge sufficiency for each configured intended decision, then build the
  packages and propose the candidates, pausing only for the reviewer's choice of approach, and after
  it write the design and pause for approval, with no operator call in between.
- **FR-008a**: Before assessing, the automatic assessment stage MUST wait, within a bounded time,
  until the verification of every record the search retrieved has settled (verified, rejected,
  quarantined or routed to a reviewer), and MUST then assess exactly the verified eligible ones; if
  the bound passes first, the stage MUST fail with the defect "verification unsettled" and the
  investigation MUST pause there as for any failed stage.
- **FR-009**: Each automatic stage MUST record its stage run to the run's provenance as the manual
  routes do, and the pauses, retry budgets, defect classification and resumption of feature 001 MUST
  apply unchanged.
- **FR-010**: Ingesting a source MUST remain an explicit act by a person (directly or through a
  confirmed action in the conversation); the system MUST NOT ingest a source on its own.
- **FR-011**: The manual routes of feature 001 MUST keep working on any request, before or after the
  automation has run the stage, each recording a further stage run.

**Decisions**

- **FR-012**: Every reviewer decision (facet confirm, amend, reject, leave unresolved, assume; facet
  set confirmed; record verify, reject, citation complaint; approach selection with reasons for the
  rejected; design approve, reject, request changes) MUST be made on the card of the thing decided,
  by an affirmative act of the person, naming a reviewer from the configured list and a rationale.
- **FR-013**: The assistant MUST NOT be able to record any reviewer decision; it MAY fill a
  decision's card in from what the person typed, and MUST then tell the person that a reviewer must
  complete it.
- **FR-014**: A decision made on a card MUST be recorded exactly as the equivalent API call records
  it, including reviewer, time, rationale and version, and MUST be refused in exactly the same
  cases.

**The assistant**

- **FR-015**: The interface MUST offer two kinds of conversation: one per request, opened from its
  journey, whose subject is that request and whose answers' cards select the matching stage on the
  canvas; and one free-standing conversation from the rail, in which a person submits a request in
  plain words and asks questions across requests. Each conversation MUST have its own history, and
  two conversations MUST NOT share history.
- **FR-016**: The assistant MUST answer from the records: it MUST read what it cites, MUST cite each
  record it relies on by a typed reference (kind and identifier, and version where records are
  versioned), and the interface MUST render each cited record as its card, read from the system,
  inline in the conversation.
- **FR-017**: The assistant MUST NOT invent a record, MUST NOT state a probability of success, and
  MUST say what is not established when the records do not answer; a reference to a record that
  does not resolve MUST be shown as unresolved.
- **FR-018**: The assistant's reply MUST appear as it is written, and a reply cut short by a failure
  MUST be marked so and MUST record nothing.
- **FR-019**: The assistant MAY start the operator's actions (submit a request, ingest a source by
  adapter and identifier, inject a reference fault, change a source's status) only after stating the
  action and its exact arguments and receiving the person's confirmation; the action MUST go through
  the same commands and rules as the API route, and its result MUST be shown as a card.
- **FR-020**: When no model is configured for the assistant, the conversation MUST say so plainly
  and everything else in the interface MUST keep working; the pipeline's own stages MUST keep
  running in whichever tier is configured, so the replay tier still needs no model for them.
- **FR-021**: The assistant's conversations MUST be testable with a scripted model, as the pipeline's
  stages are.
- **FR-021a**: Every conversation MUST have a configured budget, in tokens, and MUST show what it
  has used of it; when the budget is reached the assistant MUST decline further questions in that
  conversation and say why, while the cards, the journey and the decisions keep working, and a new
  conversation MUST start with a fresh budget. An action's own model stages (an ingestion's
  extraction, a fault's verification) count against the pipeline, not the conversation.

**Admission, access and deployment**

- **FR-022**: The interface MUST admit a person with the shared token the review pages take today,
  entered once per browser, and MUST show nothing of the records to anyone without it.
- **FR-023**: The interface MUST run on a developer's machine beside a locally running service,
  whether the service runs in a container or from the build, with one documented command; and MUST
  deploy beside the service on the platform as a separate web-hosted application at its own address,
  with the service's records reached under that address.
- **FR-024**: Every record a card shows MUST be fetched from the service; the interface MUST hold no
  copy of records beyond what it is showing.

**Look and accessibility**

- **FR-025**: The interface MUST be dark, with one accent for selection, focus and the primary
  action, and MUST draw the journey as lights joined by curves whose brightness is the stage's state;
  green and red states MUST be carried by a shape as well as a colour.
- **FR-026**: The layout MUST be a rail of areas (requests, review queue, sources, conversation), a
  canvas of the selected request's journey, an inspector for the selected stage's card, and the
  conversation as a panel, which on a narrow screen is the whole page with the others reachable from
  it.
- **FR-027**: Every view MUST be reachable and operable by keyboard, MUST have zero violations at
  WCAG 2.1 AA under an automated accessibility check, and text on every surface MUST keep 4.5:1
  contrast at the backdrop's brightest point.

### Key Entities *(each a term in the project glossary)*

- **conversation**: one person's exchange with the assistant; has its own history; holds turns.
  Either a request's conversation (one per request, its subject fixed) or the free-standing
  conversation (one, opened from the rail).
- **turn**: one message in a conversation, from the person or the assistant; an assistant's turn
  carries references and may propose an action.
- **budget**: the tokens a conversation may spend, configured; shown on the conversation as used
  against total; reaching it ends the assistant's answers in that conversation.
- **reference**: a typed pointer in an assistant's turn to a record: kind, identifier, version where
  the record is versioned; resolved to a card or shown unresolved.
- **card**: the rendering of one record, in the form made for its kind; its content is the record.
- **journey**: a request's path through the stages, drawn as a graph; derived from the workflow
  state and the stage runs, never stored separately.
- **stage node**: one stage on the journey with its state: unreached, reached, current, paused,
  failed.
- **inspector**: the panel beside the canvas showing the selected stage's card.
- **rail**: the column of areas: requests, review queue, sources, conversation.
- **action**: an operator's act the assistant proposes with its arguments and performs only on
  confirmation: submit a request, ingest a source, inject a fault, change a source's status.
- **prefilled decision**: a decision card filled in from what a person typed, awaiting the reviewer's
  act.
- **automatic stage**: a stage the investigation runs without being asked, between pauses.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One person completes the reference walkthrough of feature 001, from "Can you make foie
  gras?" to an approved design plus a caught fault and a retraction, in the browser with no
  terminal, in the replay tier, and each of feature 001's SC-001 to SC-010 is visible on a card at
  the step that proves it.
- **SC-002**: With the fixtures ingested, the reviewer makes exactly three decisions (facets,
  approach, design) between submission and an approved design; no other person's act is needed.
- **SC-003**: For every record kind listed in FR-003, a card's content equals the record the API
  returns for the same identifier and version, checked by an automated comparison on the reference
  request.
- **SC-004**: A change to a request's workflow state is reflected on an open canvas within five
  seconds without a reload, in ten of ten trials.
- **SC-005**: Asked why Paper B is indirect, what blocked the recommendation, what the search
  excluded, and to show the facets, the assistant's answers each cite the correct records and show
  their cards, with no reference that fails to resolve, under a scripted model.
- **SC-006**: An attempt to approve, select or confirm through the conversation alone records
  nothing, in every case tried, and produces a prefilled card.
- **SC-007**: With no model configured, or with a conversation's budget reached, every view except
  the assistant's answers works, and the walkthrough's three decisions can still be made.
- **SC-008**: Every view passes an automated accessibility check with zero violations at WCAG 2.1 AA
  and every operation is completed by keyboard alone in a recorded run.
- **SC-009**: The interface starts beside the service running in the compose profile with one
  command and is usable within one minute of that command.
- **SC-010**: The existing suites of feature 001 pass unchanged except where a test asked for a stage
  the automation now runs, and the walkthrough script passes with those calls removed.

## Assumptions

- The interface lives in this repository beside the service, as a separate application in its own
  directory with its own build and descriptor, the way the family keeps a product's interface beside
  its service; it is not a second repository.
- The look is the interface's own (Clarifications, 2026-11-05); it does not depend on the platform
  console's package.
- Admission by the shared token is enough for this feature, because the installation already
  identifies nobody; a sign-in with named people is a later feature. The token is entered once and
  kept by the browser.
- The assistant uses a live model whenever one is configured, in whichever tier the pipeline runs;
  the pipeline's stages keep replaying from cassettes in the replay tier. The assistant's answers are
  not recorded as cassettes; its tests use a scripted model.
- The canvas needs script to draw and to update; a person without script keeps the review pages of
  feature 001, which remain.
- The conversation keeps its own history on the service, keyed by conversation, and a person may
  return to it; conversations are not shared between browsers in this feature. A request's
  conversation is identified by the request; the free-standing one is one per browser.
- "Within a few seconds" for live progress means the journey is updated from the service's own
  notification of change, not by the person; five seconds is the bound tested.
- Automation assesses every verified eligible record each question's search retrieved; a record a
  person wants assessed against a question it did not retrieve is still asked for through the manual
  route.
- The flow-builder inspiration gives the layout (rail, canvas, inspector, top bar with a centred
  breadcrumb and the primary action) and the console gives the look; the inspiration's own colours
  are not adopted.

## Out of Scope

- Sign-in, accounts, roles or a customer's own access; editing any record by hand; any change to a
  rule, gate, prompt, stage or adapter of feature 001; the external literature adapters' interfaces;
  native mobile applications; replacing or removing the operator's API or the review pages; a light
  theme; recording the assistant's conversations as
  cassettes; the assistant acting without a person's confirmation; labelling every question's stages
  on one canvas at once (the lanes not chosen are drawn faint, without labels).

## Dependencies and Risks

- The platform's web-hosted service support.
- A live model for the assistant, and its cost, outside the replay tier's zero-cost demonstration.
- The reference walkthrough's recorded model replies must cover the stages as the automation orders
  them; a stage whose recorded reply keys on an order the automation changes needs re-recording.
- Accessibility where the journey's lights glow: text over a glow keeps 4.5:1 because every label
  sits on its own opaque pill; a brighter glow is refused if it breaks that.
