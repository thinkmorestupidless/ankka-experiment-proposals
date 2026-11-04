---
title: The demonstration walkthrough
description: Follow the reference request "Can you make foie gras?" from submission to an approved design over the HTTP API, with the script or by hand, and see what each step proves.
kind: tutorial
related: [get-started/run-locally.md, concepts/stages.md, reference/http-api.md]
---

# The demonstration walkthrough

The walkthrough runs the reference request through every stage against a running service in the replay
tier. Start the service first, as [Run it locally](run-locally.md) describes.

## With the script

```bash
EDP_API_TOKEN=<your token> scripts/walkthrough.sh --reviewer demo-reviewer --auto-review
```

Without `--auto-review` the script stops at each reviewer decision and prints the review page to open.
With it, the script makes each decision as the named reviewer. It prints what each step proved and, at
the end, the ids of the run records to inspect.

## What happens, step by step

1. **Sources.** The fixture papers are ingested with `POST /api/v1/sources:ingest`, and their findings
   and claims are verified against the text. Ingestion is always a person's act: the investigation
   searches what is in the store and never ingests by itself.
2. **The request.** `POST /api/v1/requests` with `{"text":"Can you make foie gras?","submittedBy":"customer"}`
   records the text byte for byte and starts the investigation.
3. **Facets.** The request agent proposes eight facets. "Product: foie gras" is explicit, quoted from
   the request; the production approach and objective are inferred; the biological source is derived
   from domain knowledge and left unresolved, because the request does not say which species. A
   separate call rebuilds the request from the facets and reports any omission or addition. A reviewer
   confirms the mandatory facets on `/review/requests/<id>/facets`. From here the investigation runs
   by itself until the reviewer chooses an approach.
4. **Questions and search.** Six scientific questions are derived, each linked to the facets it
   investigates. Each question's search plan has lexical and semantic queries. A retracted paper ranks
   first for the lipid question and is excluded after ranking, with the rule recorded on the execution.
   A rerun against the recorded snapshot returns the same results.
5. **Findings and claims.** Paper A's finding is about proliferation, not lipid accumulation. Paper B's
   keeps its species, human. Paper C's is a failed culture. Every quotation resolves to its section.
   Each question waits for the verification of the records it retrieved before it assesses them.
6. **Assessment.** The investigation assesses every verified eligible record each question retrieved.
   Paper A is background for the lipid question. Paper B supports it, but the species rule makes it
   indirect evidence for ducks. Paper C qualifies it.
7. **Sufficiency.** The investigation judges each configured decision for every question. For
   "recommend production feasibility" the evidence is insufficient, with the missing direct avian
   evidence named as a critical gap. For "propose a feasibility experiment on lipid accumulation" it is
   sufficient, with its assumptions stated.
8. **Candidates.** When every question has reported, the investigation builds one evidence package per
   question and proposes two approaches, compared on eight criteria with no probabilities. A reviewer
   selects one on `/review/requests/<id>/candidates`, with a reason for the other.
9. **Design.** A twelve-section design is written and rendered, marked as a proposal. Every claim it
   cites is verified and every span resolves. A reviewer approves it on `/review/designs/<id>:design`.
10. **Faults.** A copy of Paper A has its endpoint swapped by `POST /api/v1/faults:inject`; verification
    catches it as an endpoint mismatch. Paper B is marked retracted with `POST
    /api/v1/sources/src:file:paper-b/status`.
11. **Impact.** Everything built on Paper B, its findings and claims, the assessment, the package, both
    approaches and the design, is flagged for review. The approved design's content is unchanged, and
    the flag names Paper B's version.

Every step's provenance is at `GET /api/v1/runs/run-<id>`, and every request's trace is in the local
console.
