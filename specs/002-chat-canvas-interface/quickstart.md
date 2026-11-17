# Quickstart: feature 002

How to run the interface beside the service, walk the reference request through in the browser,
and run the suites. Needs Docker, JDK 21, sbt, Node 24 and the `ankka` CLI (0.14.2 or later).

## Run it

```bash
cp .env.example .env                 # EDP_API_TOKEN, EDP_TIER=replay, EDP_EMBEDDING_MODEL=hash
just up-app                          # Postgres and the service in Docker, from a fresh image
cd web && npm ci && cd ..
just web                             # ankka local web --service ankka-experiment-proposals=http://127.0.0.1:9000 -- npm run dev
```

Open the address `ankka local web` prints (port 3000). Enter the token from `.env`. Expected: the
requests listing, empty on a fresh database, within a minute of `just web` (SC-009).

For the assistant without a key, add to `.env`:
`EDP_ASSISTANT_SCRIPT=/evals/assistant/walkthrough.json` (the compose file mounts `evals/`). With
`ANTHROPIC_API_KEY` set the assistant answers live while the stages still replay.

## Walk it through in the browser (SC-001, SC-002)

1. Conversation area: type `Can you make foie gras?`, confirm the proposal "submit a request".
   Expected: the request's card, then its journey; `facets` reached and `facet-review` paused
   within seconds (replay).
2. Conversation: ask to ingest `paper-a.xml`, `paper-b.xml`, `paper-c.xml` and the retracted paper
   by the `file` adapter; confirm each. Expected: a source card each, records appearing as
   verified.
3. Select `facets` on the canvas; in the inspector confirm each mandatory facet as `demo-reviewer`
   with a rationale, then "confirm the facets". Expected: `questions` reached, the lanes run on
   their own through search, verification, assessment and sufficiency; `packages` and `candidates`
   reached; `decision` paused. No terminal, no API call.
4. Select `candidates`; select one approach with a reason for the other. Expected: `design` reached,
   `approval` paused; the design card marked "proposal".
5. Select `design`; approve version 1 with a rationale. Expected: `approval` reached; the mark gone;
   the history shows the approval.
6. Conversation: ask to inject the fault `endpoint swap` on a finding; confirm. Expected: the
   finding's card shows the verification detecting `unsupported endpoint`.
7. Conversation: ask to set Paper C's status to `retracted`; confirm. Expected: `impact` reached;
   the flag names the source version.
8. Ask: "why is Paper B indirect?", "what blocked the recommendation?", "what did the search
   exclude?", "show me the facets". Expected: each answer cites records and shows their cards, none
   unresolved (SC-005). Ask "approve it" on a design awaiting approval: a prefilled form, nothing
   recorded (SC-006).

Exactly three reviewer decisions were made (SC-002).

## Run the suites

```bash
just test-unit                       # + JourneySuite, ReferenceMarkersSuite, ActionsSuite, ConversationEntitySuite
just test-integration                # + AutomationSuite, AssistantSuite, JourneyStreamSuite, ConversationBudgetSuite
just features-replay                 # the Scala living features, one suite per area, replay tier
just web-test                        # web/: typecheck, node --test
just web-features                    # web/: the interface features in the browser, against the running compose stack
just web-check                       # web/: walkthrough, cards (SC-003), accessibility (SC-008)
just check-bdd                       # the checker over features, glossary and specs
scripts/walkthrough.sh               # still passes, with the automated stages' calls removed (SC-010)
```

Expected: every suite green; `check-bdd` clean; the walkthrough script's step count reduced by the
stages the automation now runs.

## Deploy beside the service

```bash
cd web && npm run build && docker build -t experiment-proposals-web:latest . && cd ..
ankka services apply -f service.json          # the service, as before
ankka services apply -f web/service.json      # the interface, mount /backend
ankka services get experiment-proposals-web   # shows the mount and its service
```

The interface answers at its own hostname; the service's records are under `/backend` of it.
