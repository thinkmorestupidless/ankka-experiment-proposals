# ankka-experiment-proposals

An [ankka](https://github.com/thinkmorestupidless/ankka) service: the evidence-driven scientific
experimental design proof of concept for a cultivated-food company. It turns a customer request such as
"Can you make foie gras?" into reviewable requirement facets, scientific questions, reproducible
searches, source-grounded findings and claims verified against the original text, question-specific
evidence assessments with claim-relative sufficiency, at least two candidate experimental approaches,
and a typed experimental design that a named scientific reviewer approves or rejects. It can answer
"insufficient evidence".

- Specification, plan, research, data model, contracts and tasks:
  `specs/001-evidence-experiment-design/`
- How to run and validate it: `specs/001-evidence-experiment-design/quickstart.md`
- The acceptance scenarios, in Gherkin: `features/`, in the vocabulary of `GLOSSARY.md`
- What it does not do: `docs/limitations.md`

## How it is built

Every record (request, facet, question, source, finding, claim, assessment, sufficiency, package,
candidate approach, decision, design, stage run) is an event sourced entity, so its history is kept
and nothing is edited in place. The two processes, ingestion and investigation, are workflows whose
steps are journalled, so a failure resumes rather than restarts and a reviewer's decision is a pause
with no timeout. Every model-dependent stage is an agent handler returning a structured reply that a
deterministic gate and a separate source-context verification must pass before it counts. Listings
and retrieval are views; impact propagation is a consumer that starts a workflow.

The component table in `specs/001-evidence-experiment-design/plan.md` is the design the code is held to.

Needs: JDK 21, sbt, Docker, the `ankka` CLI, `uvx` (for the bdd checker), `just` (optional).

## Test

```bash
just test-unit          # no Docker
just test-integration   # a throwaway Postgres in Docker
just features-replay    # the living features through HTTP, against recorded model replies
just faults             # the six reference faults, each caught at its gate
just independent        # the suite written from the spec alone
just mutants            # three safeguards disabled in disposable copies; each must fail a test
just eval               # the labelled evaluation; writes evals/reports/
just check-bdd          # the features, the glossary and the spec
just pr                 # all of the above and the independent suite
```

The live tier (`just features-live`, `EDP_TIER=live just eval`) needs `ANTHROPIC_API_KEY`.

## Run locally

```bash
cp .env.example .env && cp evals/reviewers.example.json reviewers.json
just up                 # sbt schema, then Postgres from target/ddl
just run                # http://localhost:9000; GET /healthz needs no token
ankka local console     # http://localhost:9889: components, routes, traces
```

Postgres applies its init directory only to an empty volume: after `sbt schema` changes anything,
`just down` first.

## Deploy to an ankka platform

`service.json` is the descriptor `ankka services apply` takes. It names the image, the ankka version,
and the `EDP_*` variables; the token and the model key come from a Kubernetes Secret named
`edp-config` with keys `api-token` and `anthropic-api-key`, which you create in the project's namespace.

```bash
sbt Docker/publishLocal
kind load docker-image ankka-experiment-proposals:latest --name ankka   # a local kind cluster
ankka services apply -f service.json
ankka services expose ankka-experiment-proposals
```

`/api/v1` and `/review` admit only the shared token; `/healthz` is open and read-only.

## Work with a coding agent

This project carries ankka's documentation for the version it was made with, as Agent Skills in
`.claude/skills/`, the spec-kit skills (`speckit-*`) with the bdd extension, and a `.mcp.json` that
starts `ankka mcp`.

## Upgrading ankka

The ankka version lives in two places that must move together: `ankkaVersion` in `build.sbt` and
`runtime` in `service.json`. Then `sbt schema` and `just down && just up` for the local database.
