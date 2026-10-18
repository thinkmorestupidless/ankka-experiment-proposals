# Evaluation data

The expert-labelled dataset FR-047 asks for, in the shape `data-model.md` ("Evaluation data") gives.

- `cases/*.json`: end-to-end cases. `category` is one of the twelve adversarial categories the
  specification lists; `expected` carries the labels, `acceptableAlternatives` the readings a reviewer
  would also accept, `uncertain` says the label itself is contested. `runnable` marks the cases whose
  model replies are recorded, which the evaluation suite runs through the service; the others are
  reported as not run until their replies are recorded in the live tier.
- `passages/*.json`: focused extraction passages. Each cites a quotation from a fixture under
  `fixtures/papers/`; the deterministic gate checks the quotation exists, and `expectedLabel`,
  `failingChecks` and `expectedDefect` say what verification should conclude.
- `cassettes/<stage>@<version>/<case>.json`: the model replies the replay tier answers with.
- `baseline.json`: written once a reviewer has read a report and agreed the thresholds.
- `reports/`: evaluation reports and `drift.json`; ignored by git.

## Labelling

Every label here was written by the stand-in (`labelledBy`), from the fixtures and the specification,
and awaits a customer reviewer. A label that a reviewer changes is changed in the file, with the reviewer
named; a reviewed failure in operation becomes a case through `POST /evals/cases:from-record` (FR-053).
