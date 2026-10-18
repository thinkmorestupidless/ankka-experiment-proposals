Feature: Candidate experimental approaches
  Validated evidence yields several candidate approaches, compared openly, with the rejected ones kept.

  Background:
    Given a valid evidence package for the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"

  Scenario: at least two candidates are generated, each with its evidence and its unknowns
    When candidate approaches are generated
    Then at least two candidate approaches are recorded
    And each candidate approach lists its supporting, contrary and missing evidence
    And each assumption without evidence is marked "unknown"

  Scenario: candidates are compared on stated criteria without invented probabilities
    Given two candidate approaches
    When the candidate approaches are compared
    Then each comparison criterion is shown with its rationale
    And no numeric success probability is asserted

  Scenario: a decision keeps the rejected alternatives and their reasons
    Given two candidate approaches that have been compared
    When the reviewer selects one candidate approach
    Then the decision record names the selected candidate approach
    And the rejected candidate approaches and their reasons remain retrievable
