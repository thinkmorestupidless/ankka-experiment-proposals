# Requests to ankka

What this service needed from the platform and could not have, each with what would remove it.
Filled as implementation finds them (research.md R20); nothing here is a promise by ankka.

| Date | What | What would remove it |
|---|---|---|
| 2026-11-04 | `GherkinSuite` takes a directory or one file, so a directory of features run elsewhere (the interface's, in a browser) cannot be left out; the suite is split into one per area | an exclusion list, or running only scenarios with or without a tag |
| 2026-11-04 | a workflow's state and lifecycle cannot be watched, so the journey stream re-reads them every two seconds beside its watch on the stage runs | a watch on a workflow's state, or a change source for workflows a view can project |
| 2026-11-04 | a streamed reply does not say what it cost, so the conversation reads the session's total after the stream ends | the usage of the interaction on the stream's completion |

## A view client in a workflow step

`WorkflowContext` offers `componentClient`, `services` and `secrets` but no view client, so a step
that must read a view (retrieval over the chunk view in `question-investigation.execute`) has no
sanctioned way to. This project sets `Configured.views` from `service.viewClient` after start and
reads it in the step. A `viewClient` on the workflow (and consumer) context, or `forView` on the
component client, would remove the global. ankka 0.12.0, found while building US6.
