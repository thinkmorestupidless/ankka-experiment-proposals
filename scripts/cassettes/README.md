# Authoring cassettes

A cassette is one model reply the replay tier answers with, keyed by the prompt version (the system
message's first line) and the case marker (the user message's first line):
`evals/cassettes/<stage@vN>/<case>.json`.

Two ways a cassette comes to exist:

- **Recorded**: `just features-record` runs the live tier with `EDP_RECORD=1`; every reply the model
  gives is written with `"recorded": true`.
- **Authored**: `just cassette <stage@vN> <case marker> <reply.json> [author]` writes a reply you
  wrote, with `"recorded": false` and your name. When the case names a fixture
  (`src:file:paper-a:v1`), every `"quotation"` in the reply is checked against the parsed fixture's
  sections first; a quotation that is in no section stops the write.

The evaluation report lists which cassettes are authored (research.md R15).
