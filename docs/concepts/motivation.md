---
title: Models inside a deterministic workflow
description: Why every model call in this application returns a typed proposal that code checks before it is recorded, and what that buys over retrieving documents and asking a model to write the answer.
kind: concept
related: [concepts/architecture.md, concepts/data-types.md, concepts/stages.md, concepts/replay.md]
---

# Models inside a deterministic workflow

The common shape of a retrieval-augmented application is short: embed the question, fetch the nearest
passages, put them in a prompt, and return what the model writes. It works for a demonstration and fails
in the ways that matter when the answer has consequences. The model cites a passage that does not say
what it claims. It drops a qualification ("in human cells") and states a result as if it held for the
species the customer cares about. It reads "cell number did not increase" as an increase. It counts a
retracted paper because it ranked first. And when the evidence is thin, it still writes a confident
paragraph, because writing paragraphs is what it was asked to do.

This application is built on the opposite assumption: a model is good at proposing and unreliable at
deciding. So the work is split.

- **A model proposes.** Each model call is one handler of an ankka agent, with a versioned prompt and a
  reply decoded into a Scala case class. The reply is a proposal: a set of facets, a search plan, a
  finding with the exact quotations it rests on, an assessment, a judgement. It is never a record yet.
- **Code checks.** Before a proposal becomes a record, deterministic code checks it: every quoted span
  must resolve to the exact text at its offsets in the stored source; a plan must contain at least one
  lexical and one semantic query; an assessment of evidence from another species can never be "direct";
  a candidate comparison may not assert a probability of success. These checks are pure functions over
  the proposal and the records it refers to, with no model involved.
- **A workflow sequences.** The stages run inside ankka workflows. Each step's outcome is journalled
  before the next step starts, so a crash resumes where it stopped rather than calling the model again.
  A failed check sends the stage back with a classified defect, within a fixed budget; a stage that
  still fails is recorded as failed and the workflow pauses for the operator.
- **A person decides what a person must decide.** The workflow pauses, with no timeout, at the three
  points where a scientist's judgement is the product: confirming what the request means, choosing
  between candidate experiments, and approving the design. Records the checks cannot settle wait in a
  review queue beside the passage they cite.
- **Everything is kept.** Every record is an event sourced entity, so its history is never overwritten.
  Every stage writes what it read, which prompt version and model it used, what it produced and which
  checks ran. Any conclusion can be traced back to the sentence in the source it rests on.

## What this looks like for one finding

A paper reports that oleate loading increased lipid in human HepG2 cells. The extraction stage asks the
model for findings and gets back a finding with the endpoint "lipid accumulation", the outcome
"increased 3.6-fold", the species "human" and a quotation. The deterministic gate resolves the quotation
against the stored section and confirms the characters match. A separate verification call, with its
own prompt, reads the finding beside the passage and checks five things: negation, scope, species,
treatment and endpoint. All pass, so the finding is verified.

Later the lipid-accumulation question for ducks is assessed. The model proposes that the finding
"supports" the question "directly". The applicability rule sees that the finding's species does not
match the question's target species and rewrites "direct" to "indirect", recording that it did. The
sufficiency judgement then has no direct avian evidence and says so: the evidence is insufficient to
recommend production, and a feasibility experiment is the justified next step.

At no point did a model's word decide the outcome. The model did the reading; the rules did the deciding.

## Why ankka

The application needs durable, resumable processes, records whose history cannot be rewritten, typed
model calls with recorded replies, read models for search, and reactions to change. ankka provides each
of these as a component kind, so the application is written as those components and little else:
entities for records, workflows for the processes, agents for model calls, views for retrieval and
listings, consumers for reactions, and endpoints for the API and the review pages. The
[architecture](architecture.md) page maps each part of the problem onto one of them.

## Determinism, precisely

The model is not deterministic, and nothing here pretends it is. What is deterministic is everything
around it: the order of stages, the checks a proposal must pass, the bound on retries, the identity of
every record, and the decision taken on a given proposal. In tests the model is replaced by recorded
replies keyed by the prompt version and a hash of the input, so a whole investigation replays
identically; [Recorded model replies](replay.md) explains how.
