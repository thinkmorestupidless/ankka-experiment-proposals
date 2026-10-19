---
title: How evidence is verified
description: How a source becomes findings and claims, the two independent checks each record must pass before it can be assessed, and how a later change to a source flags everything built on it.
kind: concept
related: [concepts/stages.md, concepts/architecture.md, reference/limitations.md]
---

# How evidence is verified

A finding or claim is evidence only once it has survived two checks of different kinds: a deterministic
check that the text it quotes is really in the source at the offsets it names, and a model check, with
its own prompt, that the record says what the passage says. Retrieval never decides what is true; it
only decides what to look at.

![How one piece of evidence earns its place. In the ingestion workflow, run per source, a fetch from Europe PMC, PubMed, Crossref, local files or internal CSVs goes to a content-hash check: a duplicate adds no new version, and new content is parsed into sections with stable offsets. Corrupt content, or content with no text, quarantines the version with its reason while keeping the content. Otherwise the sections are chunked and embedded, measurements are recorded with units validated and never converted, the source's eligibility is judged from its status, parse and quarantine, and the evidence agent extracts findings and claims, each citing exact quotations. In the verification workflow, run per record and attempt, a deterministic gate checks that every span resolves to its text and the required fields are present; a failure quarantines the record without calling a model. A pass goes to the evidence agent, which verifies the record against the source passage on negation, scope, species, treatment and endpoint. The decision is verified when the record is entailed and every check passes, rejected on a critical defect, sent to the reviewer queue beside its passage when ambiguous or thin, or re-extracted from the source, never from a summary, when the defect is retryable and the budget allows. A verified record is eligible for assessment. If its source or record changes later, an impact walk visits everything built on it, assessments, packages, approaches and designs, and flags each for review without rewriting any approved version.](../assets/diagrams/evidence.svg)

## Spans and offsets

The parser turns JATS XML or plain text into sections whose text is canonical: whitespace is
normalised once, at parse time, and never again. A span is `(source, version, section, start, end,
quotation)`. It resolves when the section's text between the offsets equals the quotation, ignoring
whitespace differences. Because a new content hash is a new version and old versions keep their
sections, a span recorded against version 1 still resolves after version 2 arrives.

The extraction prompt shows the model each section under a short key and asks for quotations, not
offsets; the extraction step finds each quotation in its section and computes the offsets itself. A
quotation that is not in the section is a defect before any record exists.

## The five verification checks

The verifier reads the record beside the passages its spans resolve to and answers with a label
(`entailed`, `contradicted`, `insufficiently_supported`, `ambiguous`) and five checks, each passed or
failed with a note and the quote it relied on:

| Check | Catches |
|---|---|
| negation | "did not increase" recorded as an increase; a failed method recorded as a success |
| scope | a result stated more broadly than the passage supports |
| species | a qualification such as "in human cells" dropped, or the wrong species |
| treatment | the wrong condition, dose or exposure |
| endpoint | proliferation recorded as lipid accumulation, or any other change of what was measured |

Any failed check forbids `entailed`, whatever the label says. Negation, species and endpoint failures
are critical and reject the record; scope and treatment failures are retryable, and the retry asks the
model to extract the record again from the source sections, not to edit the failed attempt. Every
attempt is kept on the record.

## Records that need a person

A record that verification cannot settle, an ambiguous label or thin support, goes to the review queue
and waits. So does a record that turns out to be the only affirmative evidence for a question: the
sufficiency judgement built on it is marked as requiring expert review until a reviewer has looked.

## Faults are a test of the checks

`POST /api/v1/faults:inject` applies one of six named mutations to a record and reproposes it, as an
operator: swap the endpoint, flip a negation, drop the species, present a failure as a success, break a
span, or point a span at a passage that does not support the record. Each is caught at a known check,
and the altered attempt never becomes evidence. The fault suite runs all six.

## When a source changes

Every record keeps the references it was made from. A keyed view holds one row per edge, from the record
depended on to the dependent. When a source is retracted, or a record is rejected or reproposed, a
consumer starts an impact workflow that walks the edges outward and flags each dependent through its own
entity. The walk takes small journalled steps, so a large store takes more steps rather than one long
one, and a flag is idempotent per changed record. `GET /api/v1/impact?ref=source:<id>:v1` lists the
dependents and whether each carries the flag.
