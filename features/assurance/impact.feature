Feature: Impact propagation
  A change to a finding, a claim or a source flags everything that depended on it, without rewriting it.

  Background:
    Given an approved experimental design that depends on the source "Paper B"

  Scenario: a retracted source flags every dependent for review
    When the source "Paper B" is marked "retracted"
    Then every dependent assessment, evidence package, candidate approach and experimental design is flagged for review

  Scenario: a flagged design keeps its approved version unchanged
    Given the source "Paper B" has been marked "retracted"
    When the flagged experimental design is inspected
    Then the approved version is unchanged
    And the flag names the changed source version
