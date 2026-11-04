# Contract: the HTTP routes

All routes are under `/api/v1` on the service, reached by the interface as `/backend/api/v1/…`
through its mount, and admitted by the shared token as a bearer header. Refusals are the
`HttpProblem` shape of feature 001. JSON bodies use the service's codecs; a record returned here is
the record a card shows.

## New routes

### The journey

| Route | Answers |
|---|---|
| `GET /requests/{id}/journey` | the `Journey` (`journey.md`); `404` for an unknown request |
| `GET /requests/{id}/journey:watch` | server-sent events: `event: journey`, `data: <Journey JSON>`; the current journey first, then each change |

### The assistant and conversations

| Route | Body | Answers |
|---|---|---|
| `GET /assistant` | | `{ "available": true }` or `{ "available": false, "reason": "the assistant has no model: set ANTHROPIC_API_KEY (or EDP_ASSISTANT_SCRIPT)" }`, plus `"budget": <tokens>` |
| `GET /conversations/{id}` | | the conversation: `subject`, `budget`, `used`, `turns[]` (`n`, `question`, `answer`, `references[]`, `prefill`, `at`), `proposals[]` (`n`, `action`, `arguments`, `status`, `result`, `at`); `404` when never opened |
| `POST /conversations/{id}` | `{ "subject": "req-…" }` or `{}` | opens it (idempotent) and answers the conversation; `400` when `id` is neither `request:<existing request>` nor `free:<key>`, or the subject does not match the id |
| `POST /conversations/{id}/turns` | `{ "question": "…" }` | server-sent events (below); `503` when no model; `409` when the budget is reached; `409` when the session is busy (one turn at a time) |
| `POST /conversations/{id}/proposals/{n}:confirm` | `{}` | performs the proposal; answers `{ "n": n, "status": "performed", "result": <Reference> }`; `409` when already performed or declined; the action's own refusal passes through with its status |
| `POST /conversations/{id}/proposals/{n}:decline` | `{}` | `{ "n": n, "status": "declined" }`; `409` as above |

The turn stream's events, in order:

| Event | Data | When |
|---|---|---|
| `token` | a JSON string | each chunk of the answer as the model writes it, markers included |
| `proposal` | `{ "n", "action", "arguments" }` | when the `propose_action` tool recorded one, as soon as it did |
| `done` | `{ "n", "references": [ { "kind", "id", "version", "resolved" } ], "prefill": … or null, "used", "budget" }` | the answer completed and the turn was recorded |
| `error` | `{ "reason": "…" }` | the model or a tool failed, or the output guardrail refused; nothing was recorded, and the stream ends |

A client that lost the stream re-reads `GET /conversations/{id}`: a turn is there only if it
completed.

Conversation ids are `request:<requestId>` or `free:<key>`; `key` is `[A-Za-z0-9_-]{8,64}`.

## Existing routes the interface uses, by card

| Card | Route(s) |
|---|---|
| requests listing | `GET /requests` (newest first, with status), `POST /requests` |
| request | `GET /requests/{id}`, `GET /workflows/investigation/{id}` |
| facets (with round-trip) | `GET /requests/{id}/facets`, `GET /requests/{id}/proposal`; decisions: `POST /facets/{id}/review`, `POST /facets/{id}/assume`, `POST /requests/{id}/facets:confirmed`, `POST /requests/{id}/round-trip:rerun` |
| question | `GET /requests/{id}/questions`, `GET /questions/{id}`, `GET /workflows/question-investigation/{id}` |
| search plan | `GET /search-plans/{id}`; manual: `POST /questions/{id}/plan` |
| search execution | `GET /questions/{id}/executions`, `GET /search-executions/{id}`; manual: `POST /search-plans/{id}/execute`, `POST /search-executions/{id}/rerun`, `POST /gaps/{id}/search` |
| source | `GET /sources`, `GET /sources/{id}`, `GET /sources/{id}/records`, `GET /sources/{id}/sections/{sectionId}`, `GET /workflows/ingestion/{id}`; actions: `POST /sources:ingest`, `POST /sources:ingest-internal`, `POST /sources/{id}/status`, `POST /sources/{id}/extract` |
| finding, claim | `GET /findings/{id}`, `GET /claims/{id}` (with resolved spans and verification), `GET /chunks/{chunkId}/context`, `GET /spans:resolve`; review: `POST /findings/{id}/review`, `POST /claims/{id}/review`; `POST /faults:inject` |
| assessment | `GET /assessments/{id}`; review: `POST /assessments/{id}/review`; manual: `POST /questions/{id}/assess` |
| sufficiency | `GET /questions/{id}` (its sufficiency judgements), `GET /questions/{id}/gaps`; manual: `POST /questions/{id}/sufficiency` |
| package | `GET /packages/{id}`; manual: `POST /requests/{id}/packages` |
| candidates | `GET /requests/{id}/approaches`; decision: `POST /requests/{id}/decide` |
| decision | in `GET /requests/{id}/approaches` |
| design | `GET /designs/{id}`, `GET /designs`; decisions: `POST /designs/{id}/approval`; `POST /designs/{id}/submit` |
| impact | `GET /impact` |
| stage run | `GET /runs/{runId}` |
| review queue | `GET /review-queue`, `GET /reviewers` |

None of these changes. Where a route today answers a page or a shape the interface finds
incomplete, the fix is a new field on the route's record, never a second route for the interface.
