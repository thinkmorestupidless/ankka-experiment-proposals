---
title: How the application is built
description: The ankka components the application is made of, the two workflows that do the work, and how records, views, consumers, agents and endpoints divide the responsibilities between them.
kind: concept
related: [concepts/motivation.md, concepts/stages.md, concepts/data-types.md, concepts/evidence.md]
---

# How the application is built

The application is one ankka service. Its components fall into six kinds, and each part of the problem
is given to the kind that can hold its guarantee.

| Component kind | What it holds here | Examples |
|---|---|---|
| Event sourced entity | every record, with its full history | request, facet, question, source, finding, claim, assessment, sufficiency, gap, search plan, search execution, package, approach, decision, design, stage run |
| Workflow | a multi-step process that must resume after a crash | ingestion, verification, investigation, question investigation, impact |
| Agent | one model call per handler, with a versioned prompt and a typed reply | request agent, search agent, evidence agent, design agent |
| View | a read model for search and listing | the chunk store, measurements, the dependency graph, listings of sources, records, assessments, executions and designs |
| Consumer | a reaction to a change | start verification when a record is proposed; start an impact walk when a source or record changes |
| HTTP endpoint | the edge, each with its own access rule | the operator's API, the reviewer's pages, a health probe |

Pure logic with no state, such as the rule sets, the parser, the chunker and the rank fusion, is plain
Scala in the `rules`, `retrieval` and `adapters` packages, called from workflow steps.

## The two processes

Two workflows carry almost all the work.

**Ingestion** runs once per source. It fetches the source through an adapter, records the content under
its hash (the same content again is a duplicate and adds nothing), parses it into sections with stable
character offsets, cuts and embeds chunks, records any measurements, judges the source's eligibility,
and extracts findings and claims. Each extracted record then gets its own **verification** workflow,
started by a consumer, which checks it deterministically and then against the source passage. Evidence
is therefore extracted and verified before any question asks for it.

**Investigation** runs once per customer request. It interprets the request into facets, checks the
interpretation by reconstructing the request from it, waits for a reviewer to confirm, derives the
scientific questions, and starts one **question investigation** per question. Each of those plans and
runs its search, applies eligibility, resolves the hits' context, and waits; assessment and sufficiency
judgements are asked for per question. When the evidence is judged enough, the investigation builds one
evidence package per question, proposes candidate approaches, waits for a reviewer's decision, writes
the design, and waits for approval.

![The investigation from request to approved design. A customer request goes to facet extraction by the request agent, then a facet gate that sends a defect back for up to two retries. A round-trip check rebuilds the request from the facets, and a reviewer confirms, amends or leaves each facet unresolved. Questions are derived by the request agent and checked by a coverage gate, again with up to two retries. For each question in parallel, the search agent plans the search, a gate requires a lexical and a semantic query, the search executes over the chunk store and external indexes, the ranks are fused, and eligibility is applied after ranking: removed sources are recorded as exclusions, nothing eligible becomes a coverage gap with a supplemental search bounded at two, and eligible hits have their context resolved. Each verified record is assessed by the evidence agent, applicability rules forbid a direct reading across a species or endpoint mismatch, sufficiency is judged per decision, and sole affirmative evidence goes to a reviewer. Then one evidence package per question is checked by the package rules, the design agent proposes at least two candidate approaches compared on eight criteria, a gate and guardrail require labelled assumptions and refuse any probability of success, and a reviewer selects one approach with reasons for the others. The design agent writes a twelve-section design, the design rules check every claim and span, and a reviewer approves, rejects or requests changes, which loop back to the design.](../assets/diagrams/investigation.svg)

The diagram uses four colours. Blue is a model proposing, amber is a deterministic check, green is a
named reviewer deciding, and grey is an outcome that is recorded and kept.

## Who is responsible for what

Each responsibility has one owner, and the boundaries are where ankka's guarantees are.

- **An entity enforces the rules about itself.** A search plan refuses a plan without both kinds of
  query; a decision refuses a second decision and an unlisted reviewer; a design refuses approval of a
  version that is not under review, and never changes an approved version. These checks run inside the
  entity's command handler, so no caller can bypass them, and a refusal persists nothing.
- **A rule set decides about a proposal.** Facet, coverage, eligibility, applicability, package and
  design rules are pure functions in the `rules` package. A workflow step calls them with the proposal
  and the records it needs, and acts on the result. They are tested without the runtime.
- **An agent only proposes.** An agent handler builds a prompt, calls the model, and decodes the reply
  into a case class. It writes nothing. Its only defence of its own is an output guardrail, used once:
  the design agent refuses a reply that asserts a percentage chance of success.
- **A workflow sequences and retries.** It calls the agent through one helper that records the call on
  the run's provenance entity, applies the gate, writes the accepted records, and decides the next step:
  continue, retry with the defect, or record the failure and pause.
- **A view answers questions across records.** Retrieval reads the chunk view; the impact walk reads the
  dependency view; the API's listings read their views. Nothing that must be exact is decided from a
  view, because a view is eventually consistent.
- **An endpoint is thin.** It translates HTTP to commands and back. The API and the review pages call
  the same commands, so a reviewer's form and an operator's request are checked by the same code.

[Proposals, records and rules](data-types.md) shows the types that carry these responsibilities.

## Provenance

Every workflow run has a `stage-run` entity. Each stage starts by asking it whether this stage has
already completed for exactly these inputs, identified by a hash of the input and the prompt version;
if so the stage is skipped, which is what makes a resumed workflow safe. When the stage completes or
fails, the entity records the attempt, the input references, the output reference, the checks that ran
with their results, and the model calls with prompt version, model and latency. `GET
/api/v1/runs/{id}` returns it.

## Reactions to change

Two consumers keep the records consistent with each other. When a finding or claim is proposed or
reproposed, one starts its verification. When a source's status changes, or a record is rejected,
quarantined or reproposed, another starts an impact walk: a workflow that follows the dependency view
from the changed record to everything built on it and flags each dependent for review. A flag names the
changed record and version; an approved design keeps its approved content and carries the flag beside
it. [How evidence is verified](evidence.md) shows both.
