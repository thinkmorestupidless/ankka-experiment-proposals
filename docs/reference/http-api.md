---
title: HTTP API
description: Every route of the operator's API, the reviewer's pages and the health probe, with the body each takes and what it returns.
kind: reference
related: [reference/configuration.md, get-started/walkthrough.md, concepts/stages.md]
---

# HTTP API

The service has three endpoints. Paths under `/api/v1` and `/review` require the token configured as
`EDP_API_TOKEN`; `/healthz` is open. Bodies and replies are JSON. A path parameter is a whole segment, so
an action on one record is a trailing segment (`/sources/{id}/extract`) and an action on a collection
keeps a colon (`/sources:ingest`). Every `reviewer`, `by` and `decidedBy` field must name a configured
reviewer, or `operator` where the route allows it; any other name is refused with `400` and nothing is
recorded. Routes under "Requests and facets" and later are relative to `/api/v1`.

| Endpoint | Prefix | ACL | Audience |
|---|---|---|---|
| `ApiEndpoint` | `/api/v1` | `Acl.Authenticate`: `Authorization: Bearer <EDP_API_TOKEN>` → principal `operator`; otherwise `401` | the operator, the walkthrough, the tests |
| `ReviewEndpoint` | `/review` | the same token, carried in the `edp_session` cookie set by `GET /review/session?token=`; otherwise `401` | the reviewer, in a browser |
| `HealthEndpoint` | `/healthz` | `Acl.AllowAll`; read-only | probes |

JSON bodies and responses. A path parameter is a whole segment, so an action on a record is a trailing literal segment (`/sources/{id}/extract`); an action on a collection keeps the colon form (`/sources:ingest`). Every `reviewer`, `by` and `decidedBy` field must name an entry of the
configured reviewer list; otherwise `400` with `unknown_reviewer` and no write. Every write returns
the record's `{id, version}` (or the workflow it started). Record shapes are those in
`data-model.md`; this file lists routes, inputs and statuses only. Statuses come from the entities'
refusals through ankka's mapping (`BadRequest` 400, `NotFound` 404, `Conflict` 409, `Forbidden` 403,
`Unavailable` 503, `Timeout` 504); the endpoint maps nothing itself.

A route that starts or resumes a workflow returns at once with the workflow id; the caller reads
progress from `/runs/{runId}` or the workflow's lifecycle. The gateway bounds a response at fifteen
seconds, so nothing waits for a model inside a request.

## Health, reviewers and runs

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/healthz` | | `{status, tier, model}` (no secrets) |
| GET | `/api/v1/reviewers` | | the configured list: `[{name, role, standIn}]` |
| GET | `/api/v1/runs/{runId}` | | the `stage-run` record: ordered stage executions |
| GET | `/api/v1/workflows/{kind}/{id}` | | the workflow's lifecycle (`Running`, `Paused`, `Completed`, `Failed`, pending step, failure) and its recorded state |

## Requests and facets
| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/requests` | `{text, submittedBy}` | `{id, version}` 201; starts the `investigation` workflow (id = request id) |
| GET | `/requests/{id}` | | the request, its facets (from the entities), the latest round-trip check, the workflow status |
| GET | `/requests/{id}/facets` | | facets, each with its history |
| POST | `/facets/{id}/review` | `{action: confirm \| amend \| reject \| leave_unresolved \| mark_ambiguous, reviewer, rationale, statement?}` | new facet version 201; 409 on an illegal transition |
| POST | `/facets/{id}/assume` | `{value, reviewer, rationale}` | new version 201 (mandatory facets) |
| POST | `/requests/{id}/facets:confirmed` | `{reviewer}` | resumes the workflow; 409 when a mandatory facet is neither confirmed, assumed nor unresolved-and-flagged |

## Questions and search
| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/questions/{id}` | | the question, its plan, executions, gaps, latest sufficiency |
| POST | `/questions/{id}/plan` | `{}` | `202` with the `question-investigation` workflow id: its `plan` step writes a new plan version; read it from `GET /questions/{id}` |
| POST | `/search-plans/{id}/execute` | `{sources?: [names]}` | `202`: the workflow's `execute` step runs the plan; the executions appear on `GET /questions/{id}` |
| GET | `/search-executions/{id}` | | the execution: query (none for the fused execution, `fused: true`), source, time, parameters, snapshot, results, eligible results, exclusions, `coverageGap`, `gapId`, `rerunOf` |
| POST | `/search-executions/{id}/rerun` | `{}` | a new execution against the recorded snapshot and `{matches: bool}` |
| POST | `/gaps/{id}/search` | `{}` | `202`: the question's workflow runs a supplemental round whose executions carry the gap's id; 409 when the gap's budget is spent |
| GET | `/search-plans/{id}` | | the plan: queries, targets, filters, parameters, stopping criterion, expansions, execution ids |
| GET | `/questions/{id}/executions` | | the question's execution rows (the `execution-rows` view) |
| GET | `/chunks/{chunkId}/context` | | a retrieved chunk resolved for assessment: its section, adjacent chunks, related sections and source metadata |

## Ingestion
| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/sources:ingest` | `{adapter, identifier, extract?: bool, questionIds?}` or `{adapter: "file", path, …}` | `{workflowId, sourceId}` 202; a second call for the same identifier addresses the running workflow |
| POST | `/sources:ingest-internal` | `{manifestPath}` | `202`, the workflows started, one per CSV file the manifest names; each file is one source `internal:<system>:<file stem>` whose rows are its measurements |
| GET | `/sources/{id}` | `?version=` | the source: versions (narrowed to one with `version`), parse issues, eligibility, quarantine; the retained text is not in the reply |
| GET | `/sources/{id}/sections/{sectionId}` | | section text and its chunks |
| POST | `/sources/{id}/status` | `{status, reason, by}` | the source with its new status and the eligibility re-assessed in the same command; `by` is a configured reviewer or `operator`; starts an impact walk |
| GET | `/spans:resolve` | `?source=&version=&section=&start=&end=&quotation=` | `{resolves, text}` |
| GET | `/measurements` | `?kind=&min=&max=` or `?invalid=true` | measurement rows: of one quantity kind within a value range, or every row whose unit is outside the unit table; without parameters, all |

## Extraction and verification
| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/sources/{id}/extract` | `{questionIds?}` | starts extraction on an ingested source  |
| GET | `/findings/{id}` | `?attempt=` | the finding, its spans resolved, attempts, validations, verifications, flags |
| GET | `/claims/{id}` | `?attempt=` | the claim, its findings, attempts, verifications |
| POST | `/findings/{id}/review` | `{action: verify \| reject \| complain_citation, reviewer, rationale}` | new version; a citation complaint moves the record to `requires_review` |
| POST | `/claims/{id}/review` | the same | new version |
| POST | `/claims` | `{proposition, kind, findingIds, spans, qualifications, reviewer}` | a reviewer-authored claim, `generatedBy = reviewer:<name>`, status proposed; starts `verification` |
| GET | `/review-queue` | `?kind=finding \| claim \| assessment \| design` | records in `requires_review`, with the source passage beside each |

## Assessment and sufficiency
| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/assessments/{id}` | | the assessment with rule results |
| POST | `/assessments/{id}/review` | `{action: verify \| reject, reviewer, rationale}` | new version |
| POST | `/questions/{id}/sufficiency` | `{intendedDecision}` | `202`: transitions the question's workflow to its `sufficiency` step for that decision; the judgement appears on `GET /questions/{id}` |
| GET | `/questions/{id}/gaps` | | knowledge gaps |

## Packages, candidates, design
| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/requests/{id}/packages` | `{reviewer}` (or the operator) | `202`: the investigation builds one package per question, proposes the candidates and pauses for the decision |
| GET | `/packages/{id}` | | the package with included passages resolved, trims, exclusions and its checks |
| GET | `/requests/{id}/approaches` | | the candidates, the comparison and the decision once made |
| POST | `/requests/{id}/decide` | `{selectedApproachId, rejected: [{approachId, reason}], decidedBy, rationale}` | the decision record 201; resumes the workflow |
| GET | `/designs/{id}` | `?version=&format=json \| md` | the design version or its rendering |
| POST | `/designs/{id}/submit` | `{version}` | the version in `under_review` |
| POST | `/designs/{id}/approval` | `{version, decision: approved \| rejected \| changes_requested, reviewer, rationale, requestedChanges?}` | the approval; resumes the workflow |

## Impact and faults
| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/impact` | `?ref=<kind>:<id>:v<version>` | the dependents reachable from the ref (any version of it), each with its kind, version, depth and whether it carries a flag naming the ref; `allFlagged` |
| GET | `/designs` | `?flagged=true` | the design rows (the `design-rows` view) |
| POST | `/faults:inject` | `{target: {kind, id}, mutation}` | the new attempt (status proposed) and the `verification` workflow id |
| GET | `/evals/latest` | | the newest report under `evals/reports/` (`just eval` or `just eval-offline` writes one) |
| POST | `/evals/cases:from-record` | `{kind, id, reviewer, note}` | writes a regression case under `evals/cases/` from a reviewed failure (a rejected or flagged record with its rationale); returns the file name |

Named mutations (glossary: fault): `endpoint_swap`, `negation_flip`, `species_drop`,
`failure_to_success`, `span_break`, `citation_mismatch`.

## Review pages (`/review`)

| Path | Shows | Posts to |
|---|---|---|
| `GET /review/session?token=` | sets the cookie and redirects to the queue | |
| `GET /review/requests/{id}/facets` | each facet with origin, importance, status, history; confirm, amend, reject, leave unresolved; the round-trip check's issues | the facet review routes above |
| `GET /review/queue` | records in `requires_review`, each beside its resolved source passage and verification checks | the finding, claim and assessment review routes |
| `GET /review/requests/{id}/candidates` | the candidates side by side on the eight criteria, each with its evidence and unknowns; select one with reasons for the rest | `/requests/{id}/decide` |
| `GET /review/designs/{id}` | the rendering, marked *proposal awaiting review* unless approved; the approval history; approve, reject, request changes with rationale | `/designs/{id}/approval` |

Pages render `Html(markup)` from the same records the API returns and add nothing to them.

## Errors

An error is ankka's problem reply: the status and a message saying what was refused and why.

| Status | When |
|---|---|
| `400` | a body that does not parse, an unknown enumeration value, a name that is not a configured reviewer, or a command an entity refuses as invalid |
| `401` | no token, or the wrong one |
| `404` | no record with that id |
| `409` | a command the record's current state forbids: an illegal status transition, a second decision, approval of a version not under review, a spent supplemental budget |

Nothing is recorded when a command is refused. A stage whose model reply fails its gate is not an HTTP
error: the failure is recorded on the run's provenance record, `GET /api/v1/runs/{runId}`, and on the
workflow's state, `GET /api/v1/workflows/{kind}/{id}`.
