# Limitations

What this POC does not do, stated plainly. Each is a decision recorded in
`specs/001-evidence-experiment-design/research.md`, not an oversight.

- **PDF sources are abstract-only.** Only JATS XML and plain text are parsed into sections with
  resolvable spans. A source whose only text is a PDF keeps its metadata and abstract, is marked
  `abstract_only`, and carries the parse issue `pdf_not_parsed` (R8).
- **Semantic retrieval runs in the service, not in the database.** Chunk vectors are stored in the
  retrieval view's rows and compared by cosine similarity in memory; there is no pgvector index. At
  the POC's scale (thousands of chunks) this is well under a second (R6).
- **The replay tier searches no external index.** An execution against Europe PMC, PubMed or
  Crossref is recorded with that reason; the live tier searches them. Semantic retrieval reads the
  chunk view in one page of at most 50,000 rows (R6).
- **No judgments.** Relationship and applicability are produced by the text model with a rationale
  and checked by rules; a System One model is not used (plan, component table).
- **No graph projection.** Links between records are rows of a view; projecting them to a graph
  store is a later extension through a graph consumer.
- **Markdown rendering only.** A design renders to Markdown from its record; producing a PDF from
  that Markdown is the operator's step (R13).
- **One installation, one instance.** Nothing in the POC needs a cluster; access control is the
  installation's shared token and a configured list of reviewer names (R16).
- **Reviewer identity is a name from a list, not a login.** The token says who may call; the name
  says who is recorded; an identity provider is the first thing to add after the POC.
- **Only Europe PMC supplies full text.** The PubMed and Crossref adapters return metadata, status
  and abstract, so every source they fetch is `abstract_only`; Europe PMC's full text covers its
  open-access subset. Nothing is scraped from publishers (R7).
- **Internal records are CSV files described by a manifest.** One source per file, one measurement
  per row; other formats go through the `file` adapter as text (R7).
- **Twenty-one of the twenty-two evaluation cases await recorded model replies.** Only the reference
  request has cassettes; the others are labelled and reported as not run until the live tier records
  them. Every label is the stand-in's until a customer reviewer reads it (`evals/README.md`).
- **The mutant run is not part of CI.** `just mutants` copies the tree three times and builds each;
  its result file is committed and read by the living features.
- **Experimental runs and observations are not recorded.** An observation enters as an internal
  record through the `internal` adapter.
