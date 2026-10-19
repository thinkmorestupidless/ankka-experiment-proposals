---
title: Configuration
description: Every environment variable the service reads, what it controls and its default; values are never logged, and secrets come from the platform.
kind: reference
related: [get-started/run-locally.md, deploy/deploy-on-ankka.md, concepts/replay.md]
---

# Configuration

The service reads its settings from environment variables at start-up and refuses to start when a
required one is missing or malformed. No value is ever logged.

| Variable | Meaning | Default |
|---|---|---|
| `EDP_API_TOKEN` | the bearer token the API and the review pages require | required |
| `EDP_REVIEWERS` | the reviewer list inline, `name:role:stand-in,...` | one of these two is required |
| `EDP_REVIEWERS_FILE` | a JSON file with the reviewer list, `[{name, role, standIn}]` | |
| `EDP_TIER` | `replay` answers from recorded replies; `live` calls the model | `replay` |
| `EDP_RECORD` | `1` records every live reply as a cassette | unset |
| `EDP_CASSETTES` | the directory of recorded replies | `evals/cassettes` |
| `EDP_MODEL` | the model for the live tier | `claude-opus-5-5` |
| `ANTHROPIC_API_KEY` | the model key, read by the provider in the live tier | |
| `EDP_EMBEDDING_MODEL` | `BAAI/bge-small-en-v1.5`, run locally with ONNX Runtime, or `hash` for tests | `BAAI/bge-small-en-v1.5` |
| `EDP_EMBEDDING_CACHE` | where the embedding model is downloaded to | `~/.cache/edp` |
| `EDP_RETRY_BUDGET` | retries for a retryable defect, per stage | `2` |
| `EDP_SUPPLEMENTAL_BUDGET` | supplemental search rounds per coverage gap | `2` |
| `EDP_DECISIONS` | the decisions sufficiency is judged for, separated by `;` | `recommend production feasibility;propose a feasibility experiment on lipid accumulation` |
| `EDP_FIXTURES_DIR` | the directory the `file` adapter reads | `evals/fixtures/papers` |
| `EDP_INTERNAL_DIR` | the directory the `internal` adapter reads manifests from; ignored by git | `internal` |
| `EDP_CONTACT_EMAIL` | the contact sent to Europe PMC, Crossref and NCBI | unset |
| `EDP_NCBI_API_KEY` | raises PubMed's limit from three to ten requests a second | unset |

The database connection is provisioned by the platform when deployed, and set by `docker-compose.yml`
locally.

A reviewer marked `standIn` can make every decision, and each decision records that it was made by a
stand-in; the evaluation report says so in its limitations.
