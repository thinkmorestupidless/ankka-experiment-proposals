# Contract: the journey

The journey is derived by `Journey.derive` from the investigation workflow's state and lifecycle,
each question workflow's state and lifecycle, and the run `run-<requestId>`'s stage runs. It is
served by `GET /api/v1/requests/{id}/journey` and streamed by `GET /api/v1/requests/{id}/journey:watch`
(`contracts/http-api.md`). The interface draws it and derives nothing.

## The stages, in order

The spine, one node each:

| Stage | Derived from | Stage runs it names |
|---|---|---|
| `request` | the request entity exists | none |
| `facets` | investigation step `extract-facets` | `extract-facets` |
| `round-trip` | step `round-trip` | `round-trip` |
| `facet-review` | the pause `await-facet-review`; `confirmedBy` when passed | none |
| `questions` | step `derive-questions`, then `investigate-questions` while any question lane is not `reported` | `derive-questions` |
| `packages` | step `build-packages` | the packaging stage |
| `candidates` | step `candidates` | `candidates` |
| `decision` | the pause `await-decision`; `decisionId` when passed | none |
| `design` | step `design` | `design` |
| `approval` | the pause `await-approval`; `lastApproval` when passed; `designVersions` > 1 shows the versions in `detail` | none |
| `impact` | impact flags recorded for the request's packages or design | the impact workflow's stage |

One lane per question, one node each:

| Stage | Derived from |
|---|---|
| `search-plan` | question step `plan`; the plan id in `detail` |
| `executions` | step `execute`; the number of executions in `detail`; `execute-failed` is `failed` |
| `eligibility` | step `eligibility` |
| `verification` | step `resolve` then `await-verification`; `detail` counts settled of retrieved; `verification unsettled` is `failed` |
| `assessment` | step `assess-all` or a manual `assess` |
| `sufficiency` | step `judge-all` or a manual `judge`; `detail` counts decisions judged of configured |

## Node states

| State | When |
|---|---|
| `current` | the workflow's lifecycle is `Running` and its pending step is this node's step |
| `paused` | the lifecycle is `Paused` at this node's pause (`facet-review`, `decision`, `approval`), or at `record-failure` for the node whose stage last failed (then `detail` carries the defect) |
| `failed` | the lifecycle is `Failed` at this node's step |
| `reached` | the node's step has completed at least once (a completed stage run for it, or a later node is `current`, `paused` or `reached`) |
| `unreached` | otherwise |

A lane's nodes follow the question workflow's lifecycle by the same rules. The spine's `questions`
node is `paused` while any lane is `paused` or `failed` and `reached` once every lane is `reported`.
Impact is `reached` when at least one flag exists, `unreached` otherwise; it has no `current`.

## Stage run references

Each node lists `runs: [{ runId, stage, attempt, status, actor, startedAt, endedAt }]` for the stage
executions it is derived from, newest first, so a card for a stage can link to its provenance and the
`stage-run` card can be shown for it. The actor is the reviewer or operator who asked, or
`system:investigation` for an automatic stage.

## Shape

```json
{
  "requestId": "req-…",
  "spine": [
    { "stage": "facets", "state": "reached", "detail": "8 facets", "runs": [ { "runId": "run-req-…", "stage": "extract-facets", "attempt": 1, "status": "completed", "actor": "system:investigation", "startedAt": "…", "endedAt": "…" } ] },
    { "stage": "facet-review", "state": "paused", "detail": null, "runs": [] }
  ],
  "questions": [
    { "questionId": "q-…", "text": "Has relevant lipid accumulation…", "nodes": [ { "stage": "search-plan", "state": "unreached", "detail": null, "runs": [] } ] }
  ]
}
```

## The stream

`journey:watch` sends `event: journey` with the JSON above, first at once, then whenever the
derivation changes, within 2 seconds of a workflow change and at once on a stage run. It never
sends an unchanged journey twice. A `404` is answered before the stream begins for an unknown
request. The stream ends when the client closes it.
