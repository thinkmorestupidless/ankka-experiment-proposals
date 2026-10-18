Feature: Fault injection
  An injected error in an extraction is caught at a gate and cannot become affirmative recommendation evidence.

  # The sixth reference fault, a broken source span, is caught before verification:
  # see features/evidence/verification.feature, "a broken span reference fails before verification".

  Scenario Outline: an injected extraction fault is caught at its gate
    Given the verified extraction from the source "<source>"
    And the extraction is altered so that <fault>
    When the altered extraction is verified
    Then the fault is detected as "<detection>"
    And the altered extraction is not admitted as affirmative evidence

    Examples:
      | source  | fault                                                                | detection                    |
      | Paper A | the endpoint "proliferation" becomes "lipid accumulation"            | unsupported endpoint         |
      | Paper C | the outcome "did not increase" becomes "increased"                   | critical negation error      |
      | Paper B | the species qualification is removed                                 | overgeneralisation           |
      | Paper C | the outcome "failure" becomes "success"                              | failure presented as success |
      | Paper B | the source span resolves but does not support the claim              | unsupported by span          |

  Scenario: a caught endpoint fault leaves the lipid accumulation gap open
    Given the extraction from the source "Paper A" altered so that the endpoint "proliferation" becomes "lipid accumulation"
    And the fault has been detected
    When sufficiency is judged for the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
    Then the lipid accumulation gap persists

  Scenario Outline: a disabled safeguard makes an independent test fail
    Given the safeguard "<safeguard>" is disabled in a disposable copy of the system
    When the independent test suite runs
    Then at least one independent test fails

    Examples:
      | safeguard            |
      | claim eligibility    |
      | the species rule     |
      | source span matching |
