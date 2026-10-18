# Contract: Living features and the two tiers

| Item | Contract |
|---|---|
| Runner | `FeatureSuite extends GherkinSuite("features") with LogCapturing` in `src/test/scala/.../features/`; the `.feature` files under `features/` are the source of truth and are never copied into tests |
| Steps | one set of definitions under `features/steps/`, one file per area (`request`, `sources`, `search`, `evidence`, `design`, `assurance`) plus `common`; bound to the HTTP routes through a small client and to entity queries through the kit's component client; no step names a page, a route or a status code |
| Ids | every scenario derives its request, source and question ids from `scenarioId`, so scenarios share nothing |
| Fixtures | the three papers and the retracted paper are ingested from `evals/fixtures/papers` through the `file` adapter in a step; the reference facets and questions come from cassettes in replay and from the model live |
| Tier | `EDP_TIER` read in `beforeAll`: `replay` starts the service with `ReplayModelProvider(evals/cassettes)`; `live` with `AnthropicProvider.fromEnv()` (and the recorder when `EDP_RECORD=1`) |
| Pass rule | a scenario counts as passing only when it passes in both tiers; `just features-live` writes `evals/reports/drift.json` naming scenarios that passed in replay and failed live, and the evaluation report lists them as drift, never as passes (FR-054) |
| Outlines | each `Examples` row is its own test; the fault-injection outline binds each `<fault>` to a named mutation and each `<detection>` to a defect kind |
| The disabled-safeguard outline | its three rows are run by `scripts/mutants.sh`, not by the suite: each applies one patch in a disposable copy and runs the independent suite, expecting at least one failure; the suite's definitions for those steps call the script's result file so the rows report what the script found |
| Vocabulary | steps use only glossary terms and everyday words; `just check-bdd` runs the checker in CI and fails on any finding |
| Waiting | a step that follows a workflow pause waits for the workflow's lifecycle to reach `Paused` or `Completed` with `AnkkaTestKit.eventually`, bounded by 30 seconds in replay and 10 minutes live; a step that reads a view retries on the value that changes |
| Restarts | the ingestion and impact scenarios restart the service (`restartService()`) between the write and the read, so durability is part of what they prove |

## Commands

```bash
just features-replay        # EDP_TIER=replay sbt "testOnly *FeatureSuite"
just features-live          # EDP_TIER=live   sbt "testOnly *FeatureSuite", then writes drift.json
just features-record        # EDP_TIER=live EDP_RECORD=1, re-records every cassette the run touched
```

## Step vocabulary, by area

| Area | Given | When | Then |
|---|---|---|---|
| request | the customer request "…"; facets confirmed; a facet with status "…" | the facets are extracted; the round-trip check runs; the reviewer confirms the facet; the scientific questions are derived | stored request identical; facet origin and status; unsupported addition; omission; reviewer and time recorded; history visible; proposal lists the facet |
| sources | a literature source with a persistent identifier; a source marked "…"; an internal measurement with a unit | the source is ingested; ingested again | identity, status and provenance recorded; no duplicate; parse-completeness flag; unit validated and not converted; status surfaced to eligibility; abstract-only; quarantined with a reason |
| search | a scientific question; a search plan; an execution whose first result is retracted; a retrieved chunk; a coverage gap | a plan is built; executed; eligibility applied; re-run; a supplemental search runs | plan contents; execution contents; excluded with a reason; context available; same identifiers against the same versions; linked and bounded; coverage gap, not impossibility |
| evidence | the source "Paper A" reporting "…"; a proposed claim whose span does not support it; a proposed claim with a span that does not resolve | findings and claims are extracted; the claim is verified; validated; the extraction is retried; the finding is assessed; sufficiency is judged for the decision "…" | the finding's endpoint, species, outcome; spans resolve; not admitted; quarantined with "span unresolved"; retry bounded and from the source; requires review; relationship and applicability; no truth score; sufficiency status, gaps and assumptions; coverage judgement and escalation |
| design | a valid evidence package; two candidate approaches; a selected candidate approach; a design under review | candidates are generated; compared; the reviewer selects one; the design is generated; rendered; rejected | at least two, each with evidence and unknowns; criteria with rationale, no probability; decision record with rejected reasons; twelve sections and references resolve; rendering adds nothing; rejection visible; proposal awaiting review |
| assurance | the verified extraction from "…" altered so that …; an approved design depending on "Paper B"; the evaluation dataset | the altered extraction is verified; sufficiency is judged; the source is marked retracted; the flagged design is inspected; an evaluation run completes | detected as "…" and not admitted; the gap persists; every dependent flagged; approved version unchanged and the flag names the changed version; per-stage report, critical failures blocking, limitations stated |
