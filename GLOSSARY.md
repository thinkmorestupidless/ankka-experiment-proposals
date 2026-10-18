# Glossary

The words this project's features use, each in exactly one sense.

### customer request
The text a customer submitted, kept exactly as entered; the root of an investigation.

Avoid: prompt, ask

### facet
One interpreted requirement derived from a customer request, with an origin, an importance and a status. Also "requirement facet".

Avoid: requirement

### origin
Where a facet came from: explicit in the request, inferred from context, domain-derived, or human-confirmed.

### status
The current state of a record in its lifecycle, from a closed list per kind of record.


### mandatory
The importance of a facet that a proposal cannot omit or assume.

### round-trip check
Reconstructing the customer request from its facets alone and reporting omissions, unsupported additions, contradictions and ambiguities.

### unsupported addition
A facet that nothing in the customer request supports.

### omission
Part of the customer request that no facet covers.

### reconstruction
The text the round-trip check rebuilds from the facets.

### reviewer
The scientist who confirms facets, reviews claims and assessments, and approves or rejects experimental designs.

Avoid: user, scientist, approver

### proposal
Any output the system presents before a reviewer has acted on it.

### scientific question
An explicit question derived from facets, with the evidence needed to answer it and the fields an answer must carry.

Avoid: research question

### answer field
A field an answer to a scientific question must carry.

### eligible evidence
Evidence from a source whose eligibility assessment allows its use.

### missing information
What a scientific question still lacks before it can be answered.

### source
A literature document or an internal record that evidence is drawn from, at a specific version.

Avoid: document

### finding
A reported observation from a specific experiment or record, with its species, cell type, conditions, endpoint and outcome.

Avoid: observation

### claim
A proposition: an author statement, a normalised claim or a system hypothesis.

Avoid: assertion, statement

### normalised claim
A scoped proposition derived from findings, with its qualifications.

### source span
An exact location in a source version that resolves to the original text.

Avoid: quote

### endpoint
The measured property a finding reports on, such as proliferation or lipid accumulation.

### outcome
What a finding reports happened for its endpoint, such as an increase, no change or a failure.

### species
The organism a finding's cells or tissue came from.

### qualification
A limit on a claim's scope that must travel with it, such as its species or conditions.

Avoid: caveat

### affirmative evidence
A verified finding or claim admitted as support for a recommendation.

### verification
The separate check of a proposed finding or claim against the full source context, labelling it entailed, contradicted, insufficiently supported or ambiguous.

### validation
The deterministic checks on an extraction before verification: schema, source version, resolvable spans, metadata, units, status.

### defect
A classified reason an extraction failed validation or verification.

Avoid: bug

### quarantined
Kept with its defect and excluded from use, rather than dropped.

### retry
A bounded re-run of extraction triggered by a classified defect, keeping the failed attempt.

### extraction
The findings and claims drawn from one source, with their source spans.

### assessment
The judgement of one finding or claim against one scientific question: its relationship, its applicability, a rationale and limitations.

### relationship
How evidence bears on a scientific question: supports, contradicts, qualifies, background or not applicable.

### applicability
How directly evidence transfers to a scientific question: direct, indirect or unknown.

### species rule
The rule that a species mismatch forbids direct applicability.

### rationale
The stated reasoning behind an assessment, a comparison or a decision.

### sufficiency
Whether the assessed evidence is enough for a named decision, with assumptions and critical gaps.

### decision
The named recommendation a sufficiency judgement is made for.

### critical gap
Missing evidence without which a decision cannot be supported.

### coverage gap
A gap caused by inadequate search rather than by absent evidence.

### coverage judgement
The statement in a sufficiency judgement of whether a gap reflects inadequate search or absent evidence.

### escalation
What follows an unresolved gap: a bounded supplemental search, a clarification or an exploratory question.

### literature source
A source from published scientific literature.

### internal measurement
A quantitative value from an internal laboratory record, with its unit.

### unit
The unit of measure of an internal measurement, validated and never converted silently.

### content hash
The digest of a source's retrieved content, used to detect duplicates and versions.

### parse-completeness flag
The mark on a source naming what its parsing could not resolve.

### eligibility
The judgement of whether a source version may be used, from its status and licence.

### search plan
The per-question set of lexical and semantic queries, target sources, filters and stopping criterion.

### search execution
One run of a search plan against one source, with everything it returned.

### lexical query
A query matched on words.

### semantic query
A query matched on meaning.

### chunk
A retrieval unit cut from a section of a source.

### evidence package
The validated, versioned context for a scientific question or a candidate approach.

### candidate approach
One proposed experimental approach, with its hypothesis, evidence, model, endpoints, controls and assumptions.

Avoid: option, alternative

### comparison criterion
One of the stated criteria candidate approaches are compared on.

### decision record
The record of which candidate approach was selected and which were rejected, with reasons.

### experimental design
The typed, versioned twelve-section proposal that instantiates a selected candidate approach.

Avoid: protocol

### rendering
A human-readable document produced from an experimental design.

### fault
A deliberate corruption of an extraction, injected to prove the gates catch it.

### safeguard
A control that stops an invalid claim reaching a recommendation.

### independent test
A test written from the specification rather than from the prompts or the implementation.

### evaluation run
A run of the labelled evaluation dataset producing a per-stage report.

### critical failure
An unsupported scientific assertion, a violated mandatory constraint, an invalid citation or an unreviewed high-impact conclusion.

### dependent
A record that was derived from a specific version of another record.

### flagged for review
Marked as needing a reviewer's attention because something it depends on changed.

### lipid accumulation
The endpoint of interest for the reference scenario: measured intracellular lipid in cultured cells.

### assumption
A statement a candidate approach or design relies on, labelled established, assumed or unknown.

### identifier
A persistent identifier of a source, such as a DOI or an internal record id.

### licence
The terms under which a source's text may be retained and used.

### section
A structural part of a source, such as its methods or results, holding canonical text that spans resolve into.

### table
A tabular part of a source, parsed separately and flagged when unresolved.

### version
One immutable state of a record; a change creates a new version and links name the version they used.

### limitation
A stated limit on what evidence, an assessment or a design can support.

### condition
An experimental circumstance under which a finding was observed.

### truth score
A single number presented as the correctness of evidence; never used as the judgement.

### wet-lab procedure
A detailed operational laboratory protocol, which only authorised scientific review can approve.

### evaluation dataset
The expert-labelled cases and passages the evaluation run measures the stages against.

### history
The earlier versions of a record, kept visible after a change.

### stopping criterion
The condition under which a search plan stops or escalates.

### target source
A source a search plan is directed at, such as a literature index or the local store.

### supplemental search
A bounded extra search run to close a coverage gap.

### retrieval
The stage that finds candidate chunks and sources for a scientific question.

### abstract
The summary section of a literature source, retained even when the full text cannot be fetched.

### comparison
The side-by-side judgement of candidate approaches on the stated criteria.

### exclusion
The record of why a retrieved source was excluded or downgraded after ranking.

### trim
The removal of material from an evidence package to fit a budget, logged and never applied to a mandatory facet, a contradiction or a qualification.

### link
A versioned connection between two records, naming the version of each; impact propagation follows links.

## Everyday words

a, an, the, and, or, not, no, nothing, is, are, was, has, have, been, be, it, its, that, this, those, with, without, in, on, of, to, for, from, by, as, at, against, under, into, than, one, two, every, each, all, at least, least, any, same, other, another, earlier, later, before, after, again, alone, also, only,
absent, accepted, adjacent, admitted, aggregate, altered, ambiguous, applied, approved, asserted, assessed, associated, assumes, attempt, authorised, authors, automatically, available, awaiting, becomes, blocking, bounded, built, carries, category, changed, cited, clarification, classified, compared, completes, confirmation, confirmed, confirms, contains, context, contrary, conversion, copy, corrupt, covers, created, dataset, date, depends, derived, detailed, detected, direct, disabled, disposable, distinguishes, downgraded, dropped, duplicate, entered, excluded, executed, exploratory, extracted, failed, fails, filters, first-ranked, generated, identical, impossible, inadequate, ingested, inspected, interpreted, investigated, investigates, judged, kept, labelled, list, listed, lists, location, marked, materially, metadata, method, mismatch, names, naming, number, numeric, parameters, parent, parse, parsed, part, persistent, persists, presented, prevents, probability, produced, proposed, read, reason, reasons, recognised, reference, regardless, rejected, rejection, rejects, reliably, remain, remains, removed, reported, reporting, required, requires, resembles, resolve, resolved, resolves, retained, retains, retried, retrievable, retrieved, retryable, returned, returns, scores, selected, selects, separately, shown, silently, single, specified, stored, structured, success, suite, summary, support, supporting, supports, surfaced, system, text, time, title, treated, triggered, twelve, unchanged, unresolved, unsuccessful, valid, validated, value, venue, verified, visible, which, whose, stated, states, state, result, avian, re-run, linked, run, fetched, record, full
