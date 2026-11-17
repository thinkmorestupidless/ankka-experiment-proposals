# Contract: the interface

`web/` is a web-hosted service. This page is what it promises: its descriptor, its layout, its
cards, its decisions, its admission and its accessibility. The look is the interface's own; its
tokens are at the top of `web/src/app.css`.

## Descriptor

```json
{
  "name": "experiment-proposals-web",
  "service": {
    "image": "experiment-proposals-web:latest",
    "hosting": "web",
    "processPort": 3000,
    "mounts": [{ "path": "/backend", "service": "ankka-experiment-proposals" }]
  }
}
```

The process listens on `PORT`, serves `index.html` with `Cache-Control: no-cache` and `assets/`
as immutable, and has no route of its own. Locally:
`ankka local web --service ankka-experiment-proposals=http://127.0.0.1:9000 -- npm run dev`
(`just web`).

## Admission

The first screen asks for the shared token. It is kept in `localStorage` as `edp.token`, sent as
`Authorization: Bearer` on every request to `/backend`, and never placed in a URL. A `401` shows
"the token was refused" and the field again; nothing of the records is shown before a `200`. A
"forget the token" item on the rail clears it.

## Layout

The rail at the left; to its right the bar across the top, the page, and the inspector at the
right of the page.

| Part | Holds |
|---|---|
| bar | a step back and the breadcrumb (area / request / stage) at the start; the request's own words and its status in the middle; the page's actions at the right (discuss this request; submit a request; confirm the facets; decide; approve) |
| rail | the mark and name; four areas with line icons and names in two groups (investigate: requests, review queue, sources; assistant: conversation); the current one marked; "forget the token" at the foot |
| page | the area's content: the requests listing, the canvas of the selected request, the review queue, the sources listing, or the conversation |
| inspector | the selected stage's card, or the selected record's card in the queue and the sources |
| conversation panel | beside the canvas on a wide screen (toggled from the bar), the whole page in the conversation area |

Below 900 px the inspector is a sheet over the page. Below 720 px the rail is a bar of icons
along the bottom and the conversation is its own page.

## The canvas

Draws the `Journey` (`journey.md`): the spine as a row of nodes joined by curved edges, and the
selected question's lane below it with a selector listing the questions. A node is a button (`ac-part`)
with a line icon, the stage's title, a subtitle (the `detail`), and an inset row with its state as a
word and a glyph: tick (reached), ring that pulses (current; still under `prefers-reduced-motion`),
pause bars (paused), cross (failed), nothing (unreached). Selecting a node puts its card in the
inspector; the breadcrumb follows. The stream `journey:watch` is opened when a request is shown and
closed when it is left; a change redraws the nodes in place, and the selected node stays selected.

## Cards

One component per kind, each rendering every field of the API's record. The `Card` frame shows the
kind, the id, the version where there is one, and the time, and offers "open in the inspector" and
"show its stage" (which selects the node on the canvas, in a request's conversation).

| Kind | Shows |
|---|---|
| request | the text verbatim, who submitted it and when, its status |
| facets | each facet: name, value, origin (explicit, inferred, derived), importance, status, its quotation from the request; the round-trip check's result and its differences; the review form |
| question | the text, the facets it investigates, its status |
| search plan | the lexical and the semantic queries, the escalation, the version |
| search execution | each result with its source and rank, each exclusion with its rule, whether the rerun matched |
| source | the identifier, adapter, versions, status, the records extracted with their status as they are verified (fetched again on each `journey` change while the card is shown) |
| finding, claim | the statement, each span beside the passage it resolves to (`Passage`), each verification check with result, verifier's rationale (`Check`), status and attempts; the review form |
| assessment | applicability (direct, indirect by the species rule, background, qualifying), the rationale, the record and question it links; the review form |
| sufficiency | per intended decision: status, critical gaps, assumptions, the rationale |
| package | the records included with their applicability, the gaps carried |
| candidates | the approaches side by side on the eight criteria, each criterion a row; no probability anywhere; the decision form |
| decision | the selected approach, each rejected one with its reason, the reviewer and time |
| design | the twelve sections; "proposal" marked until an approval is recorded; the approval history by version; the approval form |
| impact | each flag: the changed source and version, what it affects |
| stage run | each stage execution: stage, attempt, status, actor, inputs read, outputs produced, checks run, defect |

## Decisions

Each form names a reviewer chosen from `GET /reviewers`, takes a rationale, and posts the same
body to the same route as the operator's API (`http-api.md`). The route's refusal is shown as the
message it gave. After a decision the card is fetched again. A prefill from the assistant sets the
form's fields and leaves the reviewer unset.

## The conversation

A list of turns; the person's turn and the assistant's. The assistant's text appears as it
streams; each marker becomes the record's card inline when its text is complete, or an
"unresolved reference" chip when the `done` event says so. A `proposal` event appends a proposal
card with confirm and decline; a confirmed proposal shows its result's card. A `prefill` shows the
decision's form. The budget is shown as `used of budget tokens` under the composer; at the budget
the composer is disabled with the reason. With `GET /assistant` saying unavailable, the composer is
replaced by the reason and the rest of the panel works. A request's conversation is opened from a
"discuss" item on the canvas's bar; the free-standing one is the rail's conversation area.

## Accessibility

Every control is a native button, link or input; every card is a region named by its kind and id;
the canvas is a list of buttons; focus is visible in the amber accent; the streaming turn is
`aria-live="polite"`. axe with the `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` tags reports zero
violations on: the admission screen, the requests listing, the canvas with each kind of card in the
inspector, the review queue, the sources listing, the conversation with a streamed answer, a
proposal and a prefill. The walkthrough is completed by keyboard alone and recorded as a Playwright
trace.

## Testing

- `node --test`: the server (serves the app, `no-cache` on the index, immutable assets), the marker
  parser (markers, prefill, code spans).
- `playwright-bdd`: every scenario of `features/interface/*.feature`, against the compose profile
  `app` in the replay tier with `EDP_ASSISTANT_SCRIPT`.
- `walkthrough.spec.ts`: SC-001 and SC-002 by keyboard, with a trace.
- `cards.spec.ts`: SC-003: for each kind, fetch the record from `/backend/api/v1` and assert every
  scalar leaf appears in the card's text.
- `accessibility.spec.ts`: SC-008.
- SC-004's ten trials are `JourneyStreamSuite`'s on the service; the walkthrough test checks in the
  browser that each of its three decisions changes the node on the open canvas within 5 seconds.
