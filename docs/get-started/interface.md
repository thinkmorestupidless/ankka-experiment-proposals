---
title: Use the interface
description: Run the web interface beside the service, admit it with the installation's token, and take the reference request from a typed question to an approved design in the browser.
kind: tutorial
related: [get-started/run-locally.md, get-started/walkthrough.md, deploy/interface.md, reference/assistant-script.md]
---

# Use the interface

The interface is a web application in `web/`. It draws a request's journey on a canvas and shows
each stage's record as a card. Reviewers make their decisions on those cards. A conversation answers
from the records and proposes the operator's acts for you to confirm. It runs beside the service,
which it reaches through the mount `/backend`.

You need Node 24 and the `ankka` CLI, and the service running on port 9000, either in Docker or from
sbt.

## Start it

```bash
cp .env.example .env    # if you have not already: EDP_API_TOKEN, EDP_TIER=replay
just up-app             # Postgres and the service in Docker; or `just up` and `just run`
cd web && npm ci && cd ..
just web                # the interface on port 3000, with the service mounted at /backend
```

`just web` runs `ankka local web`, which starts the interface's server as the platform would in a
cluster and passes requests under `/backend` to the service on port 9000. If port 3000 is taken, run
`cd web && ankka local web --port 3210 --service ankka-experiment-proposals=http://127.0.0.1:9000 -- npm run dev`.

Open the address it prints. The interface asks once for the service's token, the value of
`EDP_API_TOKEN` in `.env`, and keeps it in this browser. A wrong token shows nothing and says it was
refused.

## The assistant

The conversation needs a model or a script. To use it without a key, add this line to `.env` and
restart the service:

```bash
EDP_ASSISTANT_SCRIPT=/evals/assistant/walkthrough.json    # with `just run`: evals/assistant/walkthrough.json
```

With `ANTHROPIC_API_KEY` set and no script, the assistant answers live while the stages still replay.
With neither, the conversation says the assistant is unavailable. Everything else still works.

## Take the reference request through

1. In the conversation, type `Can you make foie gras?` and confirm the proposal to submit it. The
   request's journey opens, and it pauses at facet review within seconds.
2. Ask the conversation to ingest `paper-a.xml`, `paper-b.xml`, `paper-c.xml` and `retracted.xml` by
   the `file` adapter, and confirm each one. Each appears as a source card, and its records become
   verified.
3. Select the facets node. In the inspector, choose a reviewer, confirm each mandatory facet with a
   rationale, then confirm the facets. The investigation runs by itself through the questions, their
   searches, assessment and sufficiency, the packages and the candidates. It pauses for the decision.
4. Select the candidates node. Choose one approach, and give a reason for rejecting the other. The
   design is written and waits for approval, marked as a proposal.
5. Select the design node and approve version 1 with a rationale. The mark goes, and the design's
   history shows the approval.
6. In the conversation, ask for the endpoint swap fault on a finding and confirm it. The finding's
   card shows the verification catching the swapped endpoint.
7. Ask the conversation to retract Paper C and confirm it. The impact node lights. The flag names the
   source version that changed.
8. Ask "why is Paper B indirect?", "what blocked the recommendation?", "what did the search exclude?"
   and "show me the facets". Each answer cites records and shows them as cards. Ask "approve it": the
   approval form opens prefilled, and nothing is recorded until a reviewer submits it.

The reviewer makes exactly three decisions. The facets, the choice of approach and the design are
all made on their cards, never in the conversation.

## Check it

```bash
just web-test       # typecheck and the server's and parsers' tests
just web-features   # the interface's living features, in the browser, against the running service
just web-check      # the walkthrough by keyboard, every card against the API, and accessibility
```
