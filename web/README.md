# The interface

The web interface of ankka-experiment-proposals: a request's journey on a canvas, each stage's
record as a card, the reviewer's decisions on their cards, and a conversation with the assistant.
It is a web-hosted ankka service. A React app built by Vite and a small server on `node:http` serve
the application. The platform's proxy passes `/backend` to the service `ankka-experiment-proposals`.

Needs Node 24 and the `ankka` CLI. The specification is `../specs/002-chat-canvas-interface/`.

## Layout

- `service.json`: the descriptor, which mounts the service at `/backend`.
- `server/`: listens on `PORT` and serves the built application.
- `src/shell/`: the console's shell, the rail and the top bar. `src/token/` admits the browser with the
  service's token.
- `src/areas/`: the requests list, a request's journey, the review queue, the sources and the
  conversation.
- `src/journey/`: the canvas, its nodes, edges and lanes, and which card each stage shows.
- `src/cards/`: one card per kind of record, each fetched from `/backend`.
- `src/decisions/`: the reviewer's forms, one per decision.
- `src/conversation/`: the conversation's turns, the composer and proposals.
- `test/`: node tests, the living features of `../features/interface/` in the browser, and the
  Playwright specs for the walkthrough, the cards and accessibility.

## Run it

From the repository root, with the service on port 9000:

```bash
cd web && npm ci && cd ..
just web
```

See `../docs/get-started/interface.md` for the token, the assistant and the walkthrough.

## Test

```bash
npm run typecheck && npm test   # or `just web-test` from the root
npm run features                # the living features, against the service on port 9000
npm run check                   # the walkthrough, the cards and the accessibility checks
```

## Deploy

```bash
just web-image
ankka services apply -f web/service.json
```

See `../docs/deploy/interface.md`.
