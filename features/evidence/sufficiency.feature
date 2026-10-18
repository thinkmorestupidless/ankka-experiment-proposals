Feature: Evidence sufficiency
  The same evidence can be enough for one decision and not for another; the judgement names the decision.

  Background:
    Given the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
    And the verified findings from the sources "Paper A", "Paper B" and "Paper C" are assessed

  Scenario: the three-paper evidence is insufficient to recommend production feasibility
    When sufficiency is judged for the decision "recommend production feasibility"
    Then the sufficiency status is "insufficient for recommendation"
    And the critical gaps list the missing direct avian lipid accumulation evidence

  Scenario: the three-paper evidence can support proposing a feasibility experiment
    When sufficiency is judged for the decision "propose a feasibility experiment on lipid accumulation"
    Then the sufficiency status is "sufficient for the proposed next step" or "plausible but unverified"
    And the assumptions are listed

  Scenario: an empty search is a coverage gap, not absent evidence
    Given a scientific question for which no source was retrieved
    When sufficiency is judged for that scientific question
    Then the coverage judgement distinguishes an inadequate search from absent evidence
    And the escalation is a bounded supplemental search, a clarification or an exploratory question
