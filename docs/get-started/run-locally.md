---
title: Run it locally
description: Start Postgres and the service on your machine in the replay tier, with no model credential, and check that the API and the review pages answer.
kind: tutorial
related: [get-started/walkthrough.md, reference/configuration.md, build/test.md]
---

# Run it locally

This runs the service on your machine against a local Postgres, in the replay tier: every model call is
answered from the recorded replies in `evals/cassettes`, so you need no model credential and no network
for the model.

## What you need

- JDK 21 and sbt
- Docker, for Postgres and for the integration tests
- the `ankka` CLI, for the local console
- `just`, optionally; every recipe is one command you can run without it
- `uvx`, for the feature-file checker, and `uv`, for the documentation build

## Configure

```bash
cp .env.example .env
cp evals/reviewers.example.json reviewers.json
```

`.env` holds the `EDP_*` settings; the Justfile loads it, and sbt passes the `EDP_*` variables to the
service and the tests. Set `EDP_API_TOKEN` to any value; it is the bearer token the API and the review
pages require. `reviewers.json` is the list of names allowed to make a reviewer's decision; the example
has a stand-in reviewer, `demo-reviewer`, and a placeholder for a real one. Both files are ignored by
git. [Configuration](../reference/configuration.md) lists every setting.

## Start Postgres and the service

```bash
just up     # sbt schema, then docker compose up -d
just run    # the service on http://localhost:9000
```

`just up` copies ankka's database schema out of the runtime into `target/ddl`, which Postgres applies
when its volume is created. After an ankka upgrade, run `just down` first, because Postgres applies the
schema only to an empty volume.

## Check it answers

```bash
curl -s http://localhost:9000/healthz
curl -s -H "Authorization: Bearer $EDP_API_TOKEN" http://localhost:9000/api/v1/reviewers
```

The first needs no token and answers with the tier and the model in use. The second lists the configured reviewers.

```text
{"status":"ok","tier":"replay","model":"replay"}
```

The review pages are under `/review`. Open
`http://localhost:9000/review/session?token=<your token>` once in a browser; it sets a session cookie and
takes you to the review queue.

## Watch it

```bash
ankka local console
```

The console at `http://localhost:9889` lists the service's entities, workflows, views, consumers, agents
and routes, and shows a trace for every request, including each model call.

## Next

[The demonstration walkthrough](walkthrough.md) takes one request from submission to an approved design
against the running service.
