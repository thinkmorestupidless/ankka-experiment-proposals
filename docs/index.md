---
title: ankka-experiment-proposals
description: A retrieval-augmented application built on ankka, in which models propose and a deterministic workflow decides what counts, from a customer request to a reviewed experimental design.
kind: concept
related: [concepts/motivation.md, concepts/architecture.md, get-started/run-locally.md]
---

# ankka-experiment-proposals

ankka-experiment-proposals is one [ankka](https://docs.ankka.cloud/) service that turns a customer
request such as "Can you make foie gras?" into a reviewed experimental design. On the way it interprets
the request, derives the scientific questions that must be answered, searches the literature and
internal records, extracts findings and claims and checks each one against the text it came from,
judges whether the evidence is enough for a stated decision, and proposes candidate experiments that a
named scientist chooses between and approves.

It is a retrieval-augmented generation (RAG) application, and it is written as an example of doing RAG
in a way that holds up: a language model is called at eleven points, and at every one of them its reply
is a typed proposal that deterministic code checks before anything is recorded. Nothing a model says is
taken as true because the model said it. The application can, and does, answer "insufficient evidence".

## Where to start

| You want to | Read |
|---|---|
| understand why it is built this way | [Models inside a deterministic workflow](concepts/motivation.md) |
| see how the parts fit together | [How the application is built](concepts/architecture.md) |
| see every stage, its check and its retry | [The stages](concepts/stages.md) |
| understand the data types and who owns which decision | [Proposals, records and rules](concepts/data-types.md) |
| run it on your machine | [Run it locally](get-started/run-locally.md) |
| follow a request end to end | [The demonstration walkthrough](get-started/walkthrough.md) |
| add a model-backed stage of your own | [Add a model-backed stage](build/add-a-stage.md) |
| deploy it | [Deploy on ankka](deploy/deploy-on-ankka.md) |

## What it is not

It is a proof of concept for one research team, running in one installation with one instance. Its
reviewers are names from a configured list rather than logins, its model replies in tests are recorded
rather than live, and it parses JATS XML and plain text but not PDF.
[Limitations](reference/limitations.md) lists every such boundary.
