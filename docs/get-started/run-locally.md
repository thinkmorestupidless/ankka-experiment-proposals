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

## Or run the service in Docker too

With the `app` profile, Compose runs the service itself beside Postgres, from the image
`sbt Docker/publishLocal` builds, with no cluster:

```bash
just up-app    # sbt schema, sbt Docker/publishLocal, then docker compose --profile app up -d
just logs-app  # follow the service's log
just down      # stop and remove both, with their data
```

The container reads `.env`, `reviewers.json`, `evals/` and `internal/` from the checkout, so it
replays the same cassettes, ingests the same fixtures and admits the same reviewers as `just run`, and
`EDP_RECORD=1` in `.env` writes new cassettes back into `evals/cassettes`. Rebuild with `just up-app`
after a code change. Only the local console is missing: it finds services through the loopback
address each one announces in `~/.ankka/running`, which a container cannot write for the host. For
the console, run the service with `just run`.

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
takes you to the list of requests, from which every reviewer page is a link away.

## Watch it

```bash
ankka local console
```

The console at `http://localhost:9889` lists the service's entities, workflows, views, consumers, agents
and routes, and shows a trace for every request, including each model call.

## Next

[The demonstration walkthrough](walkthrough.md) takes one request from submission to an approved design
against the running service.
