# Quickstart: validating the POC end to end

This guide runs the reference scenario and proves the acceptance criteria in spec.md on the ankka
service. It does not describe implementation; see contracts/ and data-model.md for shapes.

## Prerequisites

- JDK 21, sbt 1.12.x, Docker (Postgres for the service and for the integration tests)
- the `ankka` CLI, for the local console and, optionally, a local kind installation
- `uvx` on PATH, for the bdd checker; `just` (optional, for the recipes)
- For the live tier only: an Anthropic credential in `ANTHROPIC_API_KEY`. The replay tier needs none.

## Setup

```bash
cp .env.example .env                       # EDP_API_TOKEN, EDP_REVIEWERS_FILE, EDP_TIER=replay
cp evals/reviewers.example.json reviewers.json   # demo-reviewer is a stand-in
sbt schema && docker compose up -d         # Postgres on 5432 with ankka's schema
sbt run                                    # the service on :9000; EDP_* read from .env by the Justfile
ankka local console                        # http://localhost:9889: components, routes, traces
```

Expected: `GET /healthz` answers `{status: ok, tier: replay}`; the console lists the entities, views,
workflows, agents and the three endpoints.

## Pull-request tier

```bash
just test-unit              # entity, rule, renderer, parser and keyed-view tests; no Docker
just test-integration       # AnkkaTestKit suites with TestModelProvider; Docker
just check-bdd              # 0 findings in the glossary, features and spec
just features-replay        # the 58 scenarios through HTTP against the cassettes
just independent            # the suite written from the spec
```

Expected: every suite passes; the checker reports 0 findings; the feature suite reports 58 scenarios
passed and 0 ignored (a run with 0 scenarios fails the suite). `just pr` runs them all.

## The demonstration walkthrough

```bash
scripts/walkthrough.sh --reviewer demo-reviewer     # against the running service; replay by default
```

Each step calls the API, prints what it proved, and waits for the workflow to pause where a reviewer
decides; with `--auto-review` the script acts as the named reviewer.

1. `POST /requests` with "Can you make foie gras?"; `GET /requests/{id}` returns the text byte for
   byte (SC-001).
2. Facets: product explicit; approach and objective inferred and proposed; species domain-derived and
   unresolved; the round-trip check passes. The reviewer confirms the mandatory facets on
   `/review/requests/{id}/facets`; the workflow resumes.
3. Six questions linked to facets; the lipid accumulation question requires species, cell type,
   endpoint, measurement, outcome and limitations; its plan has lexical and semantic queries.
4. The fixtures are ingested (`POST /sources:ingest` with the `file` adapter); the search finds Papers
   A, B and C; the retracted paper, ranked first, is excluded with its reason; `rerun` returns the same
   identifiers against the same versions (SC-002).
5. Paper A's endpoint is proliferation, Paper B keeps its species, Paper C is a failure; every cited
   span resolves (SC-003).
6. Verification: Paper A is not supports/direct for lipid accumulation, Paper B is indirect by the
   species rule, Paper C qualifies (SC-004). Claims that would be sole evidence wait in the review
   queue (FR-025).
7. Production feasibility: insufficient; a feasibility experiment: justified, with assumptions.
8. A valid package; two approaches with supporting, contrary and missing evidence; eight criteria and
   no probabilities; the reviewer decides on `/review/requests/{id}/candidates` (SC-007).
9. A twelve-section design rendered from the record; every cited claim is in the manifest; the
   reviewer approves it on `/review/designs/{id}` (SC-008).
10. Six injected faults (`POST /faults:inject`), each caught at its gate; `POST /sources/{B}/status`
    retracted flags the dependents (SC-005, SC-009).
11. The approved version is unchanged; the flag names the changed source version; a regenerated
    design is rejected with its reason (SC-010).

Every step's provenance is on `GET /runs/{runId}`; every request's trace is in the local console.

## A real collection through Europe PMC (live tier, SC-003)

```bash
EDP_API_TOKEN=... scripts/ingest-collection.sh            # against a running service in the live tier
EDP_API_TOKEN=... scripts/ingest-collection.sh --extract  # and extract findings and claims from each
```

The script names fifteen real articles (PMCIDs, one PMID with no full text) covering the six
questions, ingests each through the `europepmc` adapter and prints its status, retention and
eligibility. It reaches the network and, with `--extract`, the model; it never runs in CI.

## Evaluation run

```bash
just eval                   # the runnable cases through the service, every passage through the gate; Docker
just eval-offline           # the dataset's validity and the passages' gate alone; no service
just eval-compare evals/reports/<timestamp>.json
```

Expected: per-stage sections (requests, questions, ingestion, retrieval, extraction, assessment,
sufficiency, packages, design); critical failures first as a blocking category; the replay feature
results and any drift; a limitations section naming authored cassettes, cases that did not run, and
stand-in review (SC-006, SC-012). Without `evals/baseline.json`, compare says there is no baseline yet.

Live: `ANTHROPIC_API_KEY=... just features-record` records cassettes; `EDP_TIER=live just eval`
measures against the model. After a customer reviewer has read that report and agreed thresholds, copy it
to `evals/baseline.json`.

## Live tier of the features (FR-054)

```bash
ANTHROPIC_API_KEY=... just features-live
```

A scenario passes only when it passes in both tiers; `evals/reports/drift.json` lists any replay pass
that failed live.

## Independent defect checks (SC-013)

```bash
just mutants                # scripts/mutants.sh
```

Expected: each of the three patches (bypass the retraction rule, remove the species rule, accept a
mismatched span) is reported as killed: at least one independent test failed under it, and the suite
passes again once the patch is reverted.

## Deploying to a local installation (optional)

```bash
sbt Docker/publishLocal && kind load docker-image ankka-experiment-proposals:latest
ankka services apply -f service.json        # the descriptor names EDP_* variables; set them as project secrets
ankka services expose ankka-experiment-proposals
```

The same walkthrough runs against the exposed hostname with the token. Nothing in the POC needs more
than one instance.
