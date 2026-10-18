Feature: Finding and claim extraction
  Findings, author statements, normalised claims and hypotheses are extracted from a source as distinct kinds, each grounded in source spans.

  Scenario: a proliferation finding yields no lipid accumulation claim
    Given the source "Paper A" reporting "avian hepatic cells proliferated under specified conditions"
    When findings and claims are extracted
    Then a finding with the endpoint "proliferation" is proposed
    And no claim of lipid accumulation is derived from the source
    And the finding records the lipid accumulation outcome as "not established by assessed material"

  Scenario: a finding keeps the species of its source
    Given the source "Paper B" reporting "lipid accumulation in a non-avian species"
    When findings and claims are extracted
    Then the finding records the species of the source
    And the normalised claim retains that species as a qualification

  Scenario: an unsuccessful experiment is retained as a failure
    Given the source "Paper C" reporting "a culture method was unsuccessful"
    When findings and claims are extracted
    Then the finding records the outcome "failure" under the tested conditions
    And the finding is retained

  Scenario: every extraction cites source spans that resolve
    Given a source with findings extracted
    When the cited source spans are read
    Then every source span resolves to the text of the source
