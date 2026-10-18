# Requests to ankka

What this service needed from the platform and could not have, each with what would remove it.
Filled as implementation finds them (research.md R20); nothing here is a promise by ankka.

| Date | What | What would remove it |
|---|---|---|

## A view client in a workflow step

`WorkflowContext` offers `componentClient`, `services` and `secrets` but no view client, so a step
that must read a view (retrieval over the chunk view in `question-investigation.execute`) has no
sanctioned way to. This project sets `Configured.views` from `service.viewClient` after start and
reads it in the step. A `viewClient` on the workflow (and consumer) context, or `forView` on the
component client, would remove the global. ankka 0.12.0, found while building US6.
