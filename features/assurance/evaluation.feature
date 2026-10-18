Feature: Evaluation reporting
  Quality is measured at each stage, and critical failures block regardless of aggregate scores.

  Scenario: an evaluation report measures each stage separately and blocks on critical failures
    Given the evaluation dataset
    When an evaluation run completes
    Then retrieval, extraction, assessment and sufficiency are reported separately
    And critical failures are listed as a blocking category regardless of aggregate scores
    And the limitations of the evaluation are stated
