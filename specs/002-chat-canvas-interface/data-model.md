# Data model: feature 002

What this feature adds to feature 001's model, and what it changes. Everything of feature 001
stands; a record is never edited in place.

## The conversation (new entity, event sourced)

**Id**: `request:<requestId>` for a request's conversation (one per request); `free:<key>` for a
free-standing one, where `key` is an opaque id the browser generated and keeps. The agent's session
id is the conversation id, so the model's history is the runtime's session memory under the same
id, and a second conversation can never read it.

**Events** (wire names are protocol):

| Event | Fields | Meaning |
|---|---|---|
| `conversation-opened` | `subject: Option[String]` (the request id), `budget: Long` (tokens), `at` | The first turn opened it; the budget is fixed here from `EDP_CONVERSATION_BUDGET`. |
| `turn-answered` | `n: Int`, `question: String`, `answer: String`, `references: Vector[Reference]`, `prefill: Option[Prefill]`, `tokensUsedTotal: Long`, `at` | One completed exchange. Recorded only when the stream completed; a failed answer records nothing. |
| `action-proposed` | `n: Int`, `action: Action`, `arguments: Map[String, String]`, `at` | The assistant proposed an operator's action; nothing was done. |
| `action-performed` | `n: Int`, `result: Reference`, `at` | The person confirmed and the action ran; `result` names what it produced (a request, a source version, a finding attempt, a source). |
| `action-declined` | `n: Int`, `at` | The person declined. |

**State**: `subject`, `budget`, `tokensUsedTotal`, `turns: Vector[Turn]`, `proposals: Vector[Proposal]`
(each with `status: proposed | performed | declined` and `result`), `nextTurn`, `nextProposal`.

**Commands and rules**:

| Command | Rule | Refusal |
|---|---|---|
| `open(subject)` | first call records `Opened` with the configured budget; later calls reply the state | none |
| `ask` | refused when `tokensUsedTotal >= budget` | `Conflict`: `the conversation's budget is reached: <used> of <budget> tokens` |
| `answered(question, answer, references, prefill, tokensUsedTotal)` | appends the turn; `tokensUsedTotal` must not decrease | `Invalid` on a decreasing total |
| `propose(action, arguments)` | validated by `Actions.validate` (see below); numbered | `Invalid` naming the argument |
| `performed(n, result)` | only a `proposed` proposal | `Conflict`: `proposal #n is already <status>` |
| `declined(n)` | only a `proposed` proposal | same |
| `get` (query) | the state with `used` and `budget` | none |

**Lifecycle**: a conversation is never deleted by this feature; forgetting is out of scope
(limitations).

## Reference (value)

`Reference(kind: RecordKind, id: String, version: Option[Int], resolved: Boolean)`. `RecordKind` is
the enumeration of the sixteen card kinds: `request`, `facets`, `question`, `search-plan`,
`search-execution`, `source`, `finding`, `claim`, `assessment`, `sufficiency`, `package`,
`candidates`, `decision`, `design`, `impact`, `stage-run`. The marker form is
`[[kind:id]]` or `[[kind:id@version]]`; `ReferenceMarkers.parse(text)` returns the markers in order
of first appearance, deduplicated; `ReferenceMarkers.resolve` asks the record's reader and sets
`resolved`.

## Prefill (value)

`Prefill(decision: DecisionKind, target: String, fields: Map[String, String])` where `DecisionKind`
is one of `facet-review`, `facets-confirmed`, `record-review`, `decide`, `approval`. Emitted by the
assistant as a marker `[[prefill:<decision>:<target>|<field>=<value>|…]]`, parsed like a reference,
recorded on the turn, and shown by the interface as the decision's form with those fields set. It
records nothing.

## Action and Proposal (values)

`Action`: `submit-request` (`text`), `ingest-source` (`adapter`, `identifier`), `inject-fault`
(`recordId`, `fault` one of the faults feature 001 injects), `set-source-status` (`sourceId`,
`status` one of the source statuses). `Actions.validate(action, arguments)` checks the names and
values and returns the typed action or the message the tool returns to the model. `Proposal(n,
action, arguments, status, result)`.

## The question investigation workflow (changed)

New state fields: `settled: Vector[String]` (record ids found settled), `unsettled: Vector[String]`,
`decisionsPending: Vector[String]`, `reportedToInvestigation: Boolean`. New statuses:
`awaiting-verification`, `assessing`, `judging`, `reported`.

New steps (wire names):

| Step | Does | Then |
|---|---|---|
| `await-verification` | for each retrieved record still `proposed`, awaits its verification workflow's end within the bound remaining; reads every status | all settled → `assess-all`, or `judge-all` when no verified eligible record; any unsettled → fails the stage with defect `verification unsettled` |
| `assess-all` | runs the `assess` stage over the verified eligible records, exactly as the `assess` command's step does, actor `system:investigation` | `judge-all` |
| `judge-all` | runs the `sufficiency` stage for the next decision in `decisionsPending` (from `Configured.decisions`), as the `judge` command's step does | itself while decisions remain, then `report` |
| `report` | sends `question-reported(questionId)` to the investigation workflow | `waiting` with status `reported` |

The existing steps `plan`, `execute`, `eligibility`, `resolve` are unchanged; `resolve` now
transitions to `await-verification` instead of `waiting`. The existing commands (`plan`, `execute`,
`supplemental`, `assess`, `judge`) are accepted from `reported` and from the rest after a failure, as
they are accepted from `retrieved` today. `execute-failed` and `record-failure` behave as before.

Settings on the workflow: `stepTimeout(await-verification, bound + 30 s)`, retries on
`await-verification` within `Configured.retryBudget`, as the model stages have.

## The investigation workflow (changed)

New state fields: `reportedQuestions: Vector[String]`. New command `question-reported(questionId)`:
records the id (a repeat is harmless); when `reportedQuestions` covers `questionIds` and the status
is `questions-investigating`, transitions to `build-packages` with `packagedBy =
system:investigation`; otherwise replies the state. The manual `package(by)` command is unchanged
and still accepted while `questions-investigating`, before or after the automatic packaging,
recording a further stage run. Nothing else changes: `candidates` pauses at `await-decision`,
`decided` runs `design`, `design` pauses at `await-approval`.

## The journey (derived, never stored)

`Journey(requestId, spine: Vector[StageNode], questions: Vector[QuestionLane])`;
`StageNode(stage: Stage, state: NodeState, detail: Option[String], runs: Vector[StageRunRef])`;
`QuestionLane(questionId, text, nodes: Vector[StageNode])`; `NodeState`: `unreached`, `reached`,
`current`, `paused`, `failed`. `Stage` is the fixed enumeration in `contracts/journey.md`, and
`Journey.derive(investigation, lifecycle, questions, run)` is the pure function the reader and the
stream call. The stream emits a new `Journey` only when it differs from the last emitted.

## Views (changed)

`stage-rows` gains `byRun = query("by-run")("SELECT row_key, payload FROM … WHERE row_key = :run").watched`;
the row is unchanged.

## Settings (added)

| Variable | Default | Meaning |
|---|---|---|
| `EDP_CONVERSATION_BUDGET` | `200000` | tokens a conversation may spend |
| `EDP_VERIFICATION_BOUND` | `3m` | how long `await-verification` waits for the retrieved records to settle |
| `EDP_ASSISTANT_MODEL` | the value of `EDP_MODEL` | the assistant's model when `ANTHROPIC_API_KEY` is set |
| `EDP_ASSISTANT_SCRIPT` | unset | a scripted assistant from a file, used when no key is set |

`Configured` gains `assistantModel: Option[ModelProvider]`, `conversationBudget: Long`,
`verificationBound: FiniteDuration`.

## The interface (no stored data)

The browser keeps, in `localStorage`: `edp.token` (the shared token), `edp.conversation` (the
free-standing conversation's key), and nothing else. Every record shown is fetched from `/backend`
when shown and held only in component state.
