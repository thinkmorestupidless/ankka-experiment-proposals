---
title: The stages
description: Every stage of ingestion, verification and investigation, with what it reads, whether it calls a model, what it produces, the check it must pass and what happens when the check fails.
kind: concept
related: [concepts/architecture.md, concepts/data-types.md, build/add-a-stage.md]
---

# The stages

A stage is one step of a workflow. A model-backed stage follows the same sequence every time: start on
the run's provenance entity (skip if already done for these inputs), call the agent, decode the typed
reply, apply the gate, write the accepted records, complete. A failed gate records the attempt with a
classified defect; if the defect is retryable and the budget (two retries by default) is not spent, the
step runs again, otherwise the stage is recorded as failed.

## Ingestion and verification

| Stage | Reads | Model | Produces | Check | On failure |
|---|---|---|---|---|---|
| fetch | an adapter and an identifier | no | the source's metadata and content | adapter errors classified transient or permanent | transient: two retries, then the failure is recorded and the workflow pauses |
| record | the fetched content | no | a source version, unless the hash is already a version | duplicate by content hash | a duplicate ends the workflow; nothing is added |
| parse | the version's content | no | sections with stable offsets, parse issues, completeness | corrupt or text-less content | the version is quarantined with its reason; the content is kept |
| chunk | the sections | no | chunks with embeddings, into the chunk view | | |
| measurements | internal rows | no | measurements with units | the unit table | a unit outside it is kept as written and marked invalid; nothing is converted |
| eligibility | the source and version | no | an eligibility judgement with reasons | retracted, superseded, restricted, quarantined, failed parse | the source is ineligible, which every later stage reads |
| extract | the sections, keyed for quotation | evidence agent | findings and claims, each with quotations | each quotation must be found in its section | retryable: up to two retries |
| validate | one proposed record | no | a validation result | every span resolves to its text; required fields present; no self-asserted status | quarantined with the defect; no model is called |
| verify | the record beside its resolved passages | evidence agent, separate prompt | a label and five checks: negation, scope, species, treatment, endpoint | a failed check forbids "entailed" | critical defects reject; ambiguous or thin goes to the review queue |
| retry | a record with a retryable defect | evidence agent | a new attempt re-extracted from the source | the same validation and verification | quarantined once the budget is spent |

## The investigation

| Stage | Reads | Model | Produces | Check | On failure |
|---|---|---|---|---|---|
| extract facets | the request text | request agent | eight facets with origin and importance | an explicit facet cites a span of the request that resolves | retry up to two |
| round trip | the request and the facets | request agent, separate prompt | a reconstruction with omissions, additions, contradictions | none; the result is shown to the reviewer | |
| await facet review | | no | pause | every mandatory facet confirmed, assumed or left unresolved and flagged | the confirm command is refused |
| derive questions | the confirmed facets | request agent | questions linked to facets, with answer fields | every mandatory facet investigated or named as uninvestigated | coverage gap: retry up to two |
| plan | one question | search agent, small model | a search plan | at least one lexical and one semantic query; targets; stopping criterion | retry up to two |
| execute | the plan | no | one execution per query and target, and one fused execution, each with the snapshot searched | | adapter transient failures: two retries |
| eligibility | the round's executions | no | eligible results and exclusions, after ranking | eligibility rules per source version | nothing eligible opens a coverage gap |
| supplemental | a coverage gap | no | another round with the plan's expansions, linked to the gap | the gap's budget, two rounds | refused once spent |
| resolve | the eligible hits | no | each chunk's context; the verified records of the hit sources | | |
| assess | the question and verified records | evidence agent | relationship and applicability per record | applicability rules: a species or endpoint mismatch is never direct; unknown species is unknown | retry up to two |
| sufficiency | the question, assessments, gaps, one decision | evidence agent | a status, assumptions, critical gaps, coverage judgement | a record that is the only affirmative evidence sends the judgement to review | retry up to two |
| build packages | each question's assessments | no | one package per question | spans resolve, contradictions included, only verified and eligible records, no critical trim | the package is invalid, and says why |
| candidates | the packages | design agent | at least two approaches and a comparison on eight criteria | every assumption labelled; every criterion rated with a rationale; no probability of success | fewer than two or unlabelled: retry; a probability: refused and paused |
| await decision | | no | pause | a configured reviewer, a reason for each rejected approach | the decide command is refused |
| design | the decision, the approach, verified claims | design agent | a twelve-section design | every cited claim verified, every span resolves, mandatory facets accounted for | retry up to two |
| await approval | | no | pause | a configured reviewer, a rationale | changes requested run the design stage again with the changes |

## What a reviewer sees

The review pages under `/review` show the same records the API returns. The facet page lists each facet
with its origin, importance and history, and the round-trip check's findings. The queue shows each record
in review beside the passage it cites and the verifier's checks. The candidate page shows the approaches
side by side on the eight criteria. The design page shows the rendering, marked as a proposal until a
version is approved, with the approval history.
