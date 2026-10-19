---
title: Proposals, records and rules
description: The three families of data types, model replies, entity records and rule results, and how keeping them apart puts each decision in exactly one place.
kind: concept
related: [concepts/architecture.md, concepts/stages.md, build/add-a-stage.md]
---

# Proposals, records and rules

The code keeps three families of types apart, and the boundary between them is where a model's output
stops and the application's decisions start.

| Family | Lives in | Written by | Example |
|---|---|---|---|
| Proposal | `domain/*`, named `…Proposal`, `…Set` or the agent's reply type | a model, decoded from JSON | `SearchPlanProposal`, `ExtractionSet`, `AssessmentProposal`, `ApproachSet` |
| Record | an entity's state and events | the entity, from a command | `SearchPlan`, `EvidenceRecord`, `EvidenceAssessment`, `DesignState` |
| Rule result | `rules/*` | a pure function | `Defect`, `PackageCheck`, `DesignCheck`, `Eligibility`, `RuleOutcomeRecord` |

## A proposal is loose on purpose

A proposal's fields are mostly strings, because it is what a model wrote. The search agent's reply is an
example:

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

The agent handler that produces it builds the prompt and decodes the reply; it does nothing else:

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

## A gate turns a proposal into typed input, or a defect

The gate is a pure function. It parses the strings into closed enumerations and checks what the stage
requires. Its result is either the typed values the record needs or a classified `Defect`, which says
whether a retry might help:

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

## A record enforces its own invariants

The entity checks again, because a gate is only one caller. The search plan entity refuses a plan
without both kinds of query whoever sends it, and a refusal persists nothing:

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/application/entities/SearchEntities.scala#plan-invariant -->
```scala
def plan(request: RecordPlan): Effect[SearchPlan] =
  if request.queries.isEmpty then effects.error("a plan has at least one query")
  else if !SearchPlan.twoKinds(request.queries) then
    effects.error("a plan has at least one lexical and one semantic query")
  else if request.targetSources.isEmpty then effects.error("a plan names its target sources")
  else if request.stoppingCriterion.isBlank then effects.error("a plan states when to stop")
  else
    effects
      .persist(
        SearchPlanEvent.Planned(
          request.questionRef,
          request.queries,
          request.targetSources,
          request.filters,
          request.parameters,
          request.stoppingCriterion,
          request.escalation,
          request.expansions,
          request.plannedBy,
          Instant.now()
        )
      )
      .thenReplyState
```

Records refer to each other by `Ref(kind, id, version)`, never by embedding. A record keeps the
references it was made from in `dependsOn`, which the dependency view reads to answer "what was built on
this?".

## A rule rewrites a proposal and says so

Some rules correct rather than refuse. The applicability rule set rewrites a model's "direct" reading of
evidence from another species to "indirect", and records the rewrite on the assessment, so a reader can
see what the model proposed and what the rule changed:

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/rules/Applicability.scala#species-rule -->
```scala
if species == DimensionMatch.mismatch && applicability == Applicability.direct then
  applicability = Applicability.indirect
  results += RuleOutcomeRecord(
    speciesRule.key,
    "adjusted",
    "direct rewritten to indirect: the species does not match"
  )
else results += RuleOutcomeRecord(speciesRule.key, "passed", s"species ${species}")
```

## Every stage reports one of three outcomes

The helper every model-backed stage uses returns one of three outcomes, so a workflow step handles a
skipped, completed or failed call explicitly:

<!-- include: src/main/scala/com/thinkmorestupidless/experimentproposals/application/StageCall.scala#stage-outcome -->
```scala
/** What a stage's model call came to. The caller records the outcome's records, then completes. */
enum StageOutcome[+O]:
  case Skipped(outputRef: Option[Ref])
  case Done(attempt: Int, value: O, modelCall: ModelCall)
  case Failed(attempt: Int, defect: Defect, modelCall: Option[ModelCall])
```

## Closed lists

Every value with a fixed set of meanings is a Scala 3 enum whose case names are the wire strings:
relationship (`supports`, `contradicts`, `qualifies`, `background`, `not_applicable`), applicability
(`direct`, `indirect`, `unknown`), record status, sufficiency status, defect kind and the
rest. A model reply carries strings; the gate maps them to an enum or refuses the reply as
`schema_invalid`. Inside the application, nothing is matched against free text.

## Identifiers

Identifiers are derived from what a record is, so a retried step addresses the same record instead of
creating a second one: a facet is `<request>:f<n>`, a question `<request>:q<n>`, a finding
`<source>:v<version>:fd<n>`, an assessment `<question>:<record>`, a package `<question>:pk<n>`, an
approach `<request>:c<n>`, a design `<request>:design`. A workflow's id is derived the same way, which is
what makes a redelivered event start the run already started rather than a new one.
