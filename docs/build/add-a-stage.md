---
title: Add a model-backed stage
description: The five pieces of a new stage that uses a model, a versioned prompt, a typed reply, an agent handler, a gate and a workflow step, using the search-planning stage as the worked example.
kind: guide
related: [concepts/data-types.md, concepts/stages.md, concepts/replay.md, build/test.md]
---

# Add a model-backed stage

Every model-backed stage in the application is built from the same five pieces. The search-planning
stage is the smallest complete example, and this guide follows it.

## 1. A versioned prompt

A prompt is a file at `src/main/resources/prompts/<stage>/v<n>.md` whose first line is
`# prompt_version: <stage>@v<n>`. `Prompts.load("plan", 1)` reads it and refuses a file whose header does
not match. The prompt describes the reply's JSON shape exactly. A prompt is never edited in place: a
change is `v2`, which also gives its recorded replies a new directory.

## 2. A typed reply

The reply is a case class with the fields the prompt asks for, mostly strings. It is a proposal, not a
record. Give it a JSON codec at the top level of the agent's file, because `thenReplyAs` needs it inside
the agent class.

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/domain/search/Search.scala#plan-proposal -->
```scala
/** The search agent's reply: queries, targets, filters, a stopping criterion, and expansions. */
final case class SearchPlanProposal(
    queries: Vector[QueryProposal],
    targetSources: Vector[String],
    filters: Map[String, String] = Map.empty,
    stoppingCriterion: String,
    escalation: String = "supplemental_search",
    expansions: Vector[String] = Vector.empty
)
```

## 3. An agent handler

One handler per stage, on an ankka agent. It sets the system message from the prompt, starts the user
message with the case marker, gives the source material as context, uses no session memory, and decodes
the reply. It performs no checks and writes nothing.

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/application/agents/SearchAgent.scala#plan-handler -->
```scala
def plan(request: PlanRequest): Effect[SearchPlanProposal] =
  val prompt = SearchAgent.planPrompt
  effects
    .systemMessage(prompt.system)
    .userMessage(
      Prompts.userMessage(
        SearchAgent.planMarker(request.question),
        "Write the search plan for this scientific question."
      )
    )
    .withContext(
      s"Scientific question:\n${request.question}\n\nEvidence needed:\n${request.evidenceNeeded
          .map("- " + _)
          .mkString("\n")}\n\nAnswer fields:\n${request.answerFields.mkString(", ")}\n\nAvailable sources:\n${request.availableSources
          .mkString(", ")}"
    )
    .memory(MemoryProvider.none)
    .thenReplyAs[SearchPlanProposal]
```

The case marker must be derived from the input alone, here a hash of the question, so the same input
always produces the same key and a recorded reply can answer it. Register the agent in `Main.descriptors`.

## 4. A gate

A pure function from the proposal to the typed values a record needs, or a classified defect. Mark a
defect `retryable` when asking again might produce a better reply, such as a missing field, and not
when it would not.

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/application/workflows/QuestionInvestigationWorkflow.scala#plan-gate -->
```scala
/** The plan gate: known kinds, both kinds present, a known escalation. */
def gate(p: SearchPlanProposal): Either[Defect, (Vector[Query], Escalation)] =
  val queries = p.queries.flatMap(q =>
    QueryKind.values
      .find(_.toString == q.kind.trim.toLowerCase)
      .map(k => Query(k, q.text.trim, q.expansionOf))
  )
  if queries.size != p.queries.size then
    Left(
      Rules.defect(
        DefectKind.schema_invalid,
        "a query kind is not lexical or semantic",
        retryable = true
      )
    )
  else if queries.exists(_.text.isEmpty) then
    Left(Rules.defect(DefectKind.schema_invalid, "a query has no text", retryable = true))
  else if !SearchPlan.twoKinds(queries) then
    Left(
      Rules.defect(
        DefectKind.schema_invalid,
        "a plan has at least one lexical and one semantic query",
        retryable = true
      )
    )
  else if p.targetSources.forall(_.isBlank) then
    Left(
      Rules.defect(DefectKind.schema_invalid, "a plan names its target sources", retryable = true)
    )
  else if p.stoppingCriterion.isBlank then
    Left(
      Rules.defect(
        DefectKind.schema_invalid,
        "a plan states its stopping criterion",
        retryable = true
      )
    )
  else
    Right(
      (
        queries,
        Escalation.values
          .find(_.toString == p.escalation.trim.replace(' ', '_'))
          .getOrElse(Escalation.supplemental_search)
      )
    )
```

If the record the stage writes has an invariant, enforce it in the entity's command handler too. The
gate protects this stage; the entity protects every caller.

## 5. A workflow step

The step calls the agent through `StageCall.model`, which records the call on the run's provenance
entity and skips the call if this stage already completed for the same input hash. It then handles the
three outcomes:

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/application/StageCall.scala#stage-outcome -->
```scala
/** What a stage's model call came to. The caller records the outcome's records, then completes. */
enum StageOutcome[+O]:
  case Skipped(outputRef: Option[Ref])
  case Done(attempt: Int, value: O, modelCall: ModelCall)
  case Failed(attempt: Int, defect: Defect, modelCall: Option[ModelCall])
```

- `Skipped`: the stage already ran for these inputs; move to the next step.
- `Done`: apply the gate. On a defect, record it with `stages.fail` and retry within the budget; on
  success, write the records through their entities, record completion with `stages.complete`, and move
  on.
- `Failed`: the call itself failed (a timeout, an unparseable reply, a refusal); retry within the
  budget when the defect is retryable, otherwise record the failure and pause.

`QuestionInvestigationWorkflow.planStepRun` is the complete step.

## 6. A recorded reply

Run the stage once in the replay tier: it fails with an error naming the missing cassette's key and
file. Record it from the live model with `EDP_TIER=live EDP_RECORD=1`, or author it with `just cassette
plan@v1 <marker> <reply-file>`. Commit the file under `evals/cassettes/plan@v1/`.

## 7. Tests

A unit test for the gate, with a good proposal and each kind of bad one. An integration test that runs
the stage through the service with a scripted model, including a bad reply followed by a good one, to
prove the retry. If the stage is part of an acceptance scenario, its step definitions call the API, not
the code.
