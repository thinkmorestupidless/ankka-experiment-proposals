Feature: Question-specific evidence assessment
  A verified finding is judged against one scientific question at a time, with its relationship and applicability stated.

  Background:
    Given the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
    And the target species is "duck or goose"

  Scenario: a proliferation finding is not direct lipid accumulation evidence
    Given the verified finding from the source "Paper A" with the endpoint "proliferation"
    When the finding is assessed against the scientific question
    Then the relationship is not "supports" with the applicability "direct"
    And the rationale names the endpoint mismatch

  Scenario: a finding in another species is indirect evidence
    Given the verified finding from the source "Paper B" with the species "non-avian"
    When the finding is assessed against the scientific question
    Then the applicability is "indirect"
    And the species rule prevents the applicability "direct"

  Scenario: an unsuccessful experiment is contradicting or qualifying evidence
    Given the verified finding from the source "Paper C" with the outcome "failure"
    When the finding is assessed against the scientific question
    Then the relationship is "contradicts" or "qualifies"

  Scenario: an assessment presents no single truth score
    Given a verified finding
    When the finding is assessed against the scientific question
    Then the assessment states a relationship, an applicability, a rationale and limitations
    And no single numeric truth score is presented as the judgement
