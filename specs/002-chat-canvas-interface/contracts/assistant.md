# Contract: the assistant

One agent, `assistant`, one handler `answer(question)` streaming, session id = conversation id.
Its instructions are `src/main/resources/prompts/assistant/v1.md`, versioned like the stages'
prompts and never edited in place.

## Instructions (what v1 must say)

- Answer only from what the tools return. Cite every record relied on with a marker, and never
  paste a record's content in place of a marker. Say what is not established when the records do
  not answer; never invent an id.
- Never state a probability of success, a likelihood or a confidence about an experiment.
- Never make, record or promise a reviewer's decision. A wish to confirm, select, approve, reject
  or request changes is answered with a prefill marker for the matching form and the words that a
  reviewer must complete it on the card.
- An operator's action (submit a request, ingest a source, inject a fault, change a source's
  status) is proposed with `propose_action`, stating the exact arguments, and is performed only
  when the person confirms; say so.
- In a request's conversation the subject is the request given in context; do not ask which.
- Be brief; the cards carry the detail.

## Tools

| Tool | Parameters | Returns |
|---|---|---|
| `list_requests` | | every request, newest first: id, text, status, submitted at |
| `get_request` | `requestId?` (defaults to the subject) | the request, its facets with origin, importance, status and quotation, the round-trip check, its questions, and its journey |
| `get_question` | `questionId` | the question, its facets, its plan with queries, each execution with results and exclusions and the rule for each, its gaps, its assessments, its sufficiency per decision |
| `get_record` | `recordId` | a finding or claim with its spans resolved to passages, its verification checks with results and rationale, its status and attempts |
| `get_evidence` | `requestId?` | the packages, the candidate approaches with the comparison on the eight criteria, the decision with reasons for the rejected |
| `get_design` | `requestId?` | the design's versions with the twelve sections, the approval history, impact flags naming the changed source version |
| `list_sources` | | every source with versions and status |
| `review_queue` | | the records awaiting review with their passages |
| `propose_action` | `action`, `arguments` (JSON object as a string) | `proposed as #n; the person must confirm it`, or the validation message |

Every read tool returns the same JSON the API route returns for the record, so the ids it shows
are the ids the markers must use. `maxToolCallSteps = 12`. Errors are messages the model can act
on (`no record 'x'; record ids look like fnd-…`).

## Reference markers

`[[<kind>:<id>]]` or `[[<kind>:<id>@<version>]]`, `kind` one of `request`, `facets`, `question`,
`search-plan`, `search-execution`, `source`, `finding`, `claim`, `assessment`, `sufficiency`,
`package`, `candidates`, `decision`, `design`, `impact`, `stage-run`. For `facets`, `candidates`,
`decision` and `impact` the id is the request id. The interface renders each marker as the record's
card inline, in place; the `done` event's `references` says which resolved. Markers inside a code
span are not markers.

## Prefill markers

`[[prefill:<decision>:<target>|<field>=<value>|…]]` with `decision` one of `facet-review`
(`target` a facet id; fields `outcome`, `rationale`, `amendment`), `facets-confirmed` (`target` a
request id), `record-review` (`target` a finding, claim or assessment id; fields `outcome`,
`rationale`), `decide` (`target` a request id; fields `selected`, `reasons.<approachId>`),
`approval` (`target` a design id; fields `outcome`, `version`, `rationale`). The interface shows the
decision's form with the fields set and the reviewer unset; nothing is recorded.

## Proposals

`propose_action` validates (`Actions.validate`) and records a proposal on the conversation; the
stream sends `proposal`; the interface shows a card with the action, each argument and two
buttons: confirm (`POST …/proposals/{n}:confirm`) and decline. Confirmation performs the action
through the same code as its API route and answers the result's reference, shown as a card. The
actions and their arguments:

| Action | Arguments | Performs | Result reference |
|---|---|---|---|
| `submit-request` | `text` | `POST /requests` with the text verbatim | `request` |
| `ingest-source` | `adapter`, `identifier` | `POST /sources:ingest` | `source` |
| `inject-fault` | `recordId`, `fault` | `POST /faults:inject` | `finding` or `claim` |
| `set-source-status` | `sourceId`, `status` | `POST /sources/{id}/status` | `source` |

## The model

`Configured.assistantModel`: an `AnthropicProvider` for `EDP_ASSISTANT_MODEL` when
`ANTHROPIC_API_KEY` is set; a `ScriptedModelProvider` when `EDP_ASSISTANT_SCRIPT` names a file;
otherwise none, and `GET /assistant` says so. Suites set a `TestModelProvider` of their own.

## The scripted file

A JSON array; the first rule whose `when` is a substring of the last user message answers:

```json
[
  { "when": "why is paper b indirect", "tools": [ { "name": "get_question", "arguments": { "questionId": "q-…" } } ],
    "text": "Paper B's finding is indirect by the species rule: [[assessment:asm-…]] records the applicability and its rationale; the finding is [[finding:fnd-…]]." },
  { "when": "approve it", "text": "A reviewer must approve the design on its card; I have filled the form in. [[prefill:approval:dsg-…|outcome=approve|version=1|rationale=]]" }
]
```

A rule with `tools` produces one tool-calling turn, then the `text` turn. No matching rule answers
`the script has no answer for that`. `evals/assistant/walkthrough.json` holds the answers the
browser suites and SC-005 need.

## Testing

`AssistantSuite` (integration): with a scripted assistant model, asserts which tools ran with
which arguments; that markers resolved and unresolved are recorded as such; that a proposal
records nothing until confirmed and performs through the entity on confirmation; that the second
confirmation is refused; that a failed model call records no turn and ends the stream with
`error`; that two conversations do not share memory; that the budget refuses with `409` and a new
conversation starts fresh; that with no model the route answers `503` and `GET /assistant` says
why. Prose is never asserted on.
