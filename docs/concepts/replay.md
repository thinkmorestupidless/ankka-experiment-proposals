---
title: Recorded model replies and the two tiers
description: How every model call is keyed so that a recorded reply can stand in for it, how the replay and live tiers differ, and how a new reply is authored or recorded.
kind: concept
related: [build/test.md, concepts/motivation.md, build/add-a-stage.md]
---

# Recorded model replies and the two tiers

Every model call carries two marker lines. The system message starts with `# prompt_version:
<stage>@v<n>`, taken from the prompt file. The user message starts with `# case: <marker>`, where the
marker is derived deterministically from the input: a hash of the request text, of the question, of the
record's content, or of the sorted record ids being assessed. The pair is a key that names exactly this
call.

## The replay tier

With `EDP_TIER=replay`, the model provider is `ReplayModelProvider`. It reads the key from the request
and answers with the reply stored at `evals/cassettes/<prompt_version>/<marker>.json`. A missing
cassette fails the call with an error naming the key and the file it expected, so a new or changed input
shows up as a precise failure rather than a silent difference. The replay tier needs no credential and no
network, and the living features, the integration suites and the evaluation all run in it.

Because the marker hashes the input, a changed prompt input is a new key. Because the prompt version is
part of the key, a changed prompt is a new directory. A prompt file is never edited in place: a change is
a new version.

## The live tier

With `EDP_TIER=live`, the provider is Anthropic's, reading `ANTHROPIC_API_KEY`, with the model named by
`EDP_MODEL`; the search agent asks for a smaller model at low effort. With `EDP_RECORD=1` as well, a
recording provider wraps the live one and writes every reply it receives as a cassette, marked
`recorded`. Running the living features live is how drift is measured: a scenario that passes in replay
and fails live is drift, not a pass.

## Authoring a cassette

Some replies are written by hand, for a case the live model has not been run on yet or to script a
fault. `just cassette <stage@vN> <marker> <reply-file>` writes one and, when the marker names a fixture,
checks every quotation in the reply against the parsed fixture first, refusing a quotation that is in no
section. An authored cassette says who authored it; the evaluation report lists authored replies among
its limitations.

## What is not replayed

Embeddings in tests use a hash embedder (`EDP_EMBEDDING_MODEL=hash`) instead of the ONNX model, so
retrieval is deterministic and needs no download. External indexes are not searched in the replay tier:
an execution against Europe PMC, PubMed or Crossref is recorded with that reason, so the plan's targets
stay visible and nothing reaches the network.
