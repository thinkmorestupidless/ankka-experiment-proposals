---
title: The assistant's script
description: The file of scripted answers the assistant gives instead of a model, for the browser suites and a demonstration without a key.
kind: reference
related: [reference/http-api.md, reference/configuration.md]
---

# The assistant's script

With `EDP_ASSISTANT_SCRIPT` naming a file, the assistant answers from that file instead of a model. The
browser suites use `evals/assistant/walkthrough.json`, and so can a demonstration with no model key. A
named script is used even when `ANTHROPIC_API_KEY` is set.

The file is a JSON array of rules. The first rule whose `when` appears in the person's question answers
it; the match ignores case. A question no rule matches is answered `the script has no answer for that`.

```json
[
  {
    "when": "from paper b indirect",
    "tools": [{ "name": "get_question", "arguments": { "questionId": "{subject}:q4" } }],
    "text": "The finding from Paper B is indirect by the species rule: [[assessment:{subject}:q4:src:file:paper-b:v1:fd1]]."
  }
]
```

| Field | Meaning |
|---|---|
| `when` | text the question must contain |
| `tools` | optional; each `name` and `arguments` is one call of the assistant's tools, made before the answer |
| `text` | the answer, with its reference and prefill markers, streamed word by word |

A rule with tools answers in two turns, as a model would: the tool calls, then the text. The answer's
markers are resolved and recorded exactly as a model's are.

Three placeholders keep a rule's ids right for whichever request a run submitted:

| Placeholder | Becomes |
|---|---|
| `{subject}` | the id of the conversation's request; in a free-standing conversation it stays as written, so its markers do not resolve |
| `{requests}` | a reference to each request the turn's tools returned, separated by commas |
| `{fail}` | the point at which the answer fails: the words before it arrive, then the stream ends with `error` and nothing is recorded |

Usage is estimated at about four characters a token, so a conversation's budget moves as it would with a
model.
