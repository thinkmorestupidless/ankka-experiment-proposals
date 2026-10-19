---
title: Test the application
description: The test tiers, what each proves, the commands that run them, and the order to run them in because the integration suites share one database per run.
kind: guide
related: [concepts/replay.md, build/add-a-stage.md, get-started/run-locally.md]
---

# Test the application

| Tier | Command | Needs | Proves |
|---|---|---|---|
| unit | `just test-unit` | nothing | entities, rule sets, gates, the parser, adapters against recorded HTTP responses, rendering, keyed views |
| independent | `just independent` | nothing | properties written from the requirements alone: a retracted source is never eligible, a species mismatch is never direct, a mismatched span is never admitted, a hypothesis is never verified, an unlisted reviewer never approves, a rendering never adds a claim |
| integration | `just test-integration` | Docker | each workflow through the whole service and its HTTP routes, with a scripted model, including restarts mid-process |
| faults | `just faults` | Docker | each of the six injected faults is caught at its check and never becomes evidence |
| living features | `just features-replay` | Docker | the Gherkin scenarios under `features/`, through HTTP, against the recorded replies |
| evaluation | `just eval` | Docker | the labelled dataset through the service; writes a report under `evals/reports/` |
| mutants | `just mutants` | Docker | disabling each of three safeguards in a copy of the tree makes an independent test fail |

`just pr` runs the feature checker, unit, integration, independent and living-feature tiers, which is
what continuous integration runs on a pull request.

## One database per run

The integration test kit starts one throwaway Postgres per `sbt testOnly` invocation, and every suite in
that invocation shares it. Suites are written to tolerate each other's data, and the fault suite, which
alters fixture records, runs in its own invocation after the integration suites. Run heavy tiers one at
a time: two sbt test runs at once compete for the machine and slow the asynchronous parts enough to fail
waits that pass on an idle machine.

## The scripted model

Integration suites use ankka's `TestModelProvider`. `scriptFromCassettes("plan@v1", ...)` loads every
recorded reply for those prompt versions as a standing rule keyed by its case marker, so a suite answers
the calls it expects and fails on one it does not. A suite that needs a bad reply first scripts it with
a rule that fires once.

## The living features

The scenarios under `features/` are written in the vocabulary of `GLOSSARY.md` and checked against it by
`just check-bdd`. Their step definitions call the HTTP API only. In the replay tier they run against the
cassettes; `just features-live` runs them against the live model, and a scenario that passes in replay
and fails live is reported as drift.

## The evaluation

`evals/cases` holds labelled end-to-end cases, each in one adversarial category such as species
mismatch, negation, a retracted paper or genuinely insufficient evidence, with acceptable alternative
labels where reviewers might differ. `evals/passages` holds focused extraction passages with the
expected verification label. The report measures each stage separately and lists critical failures
first, as a category that blocks regardless of the aggregate scores.
