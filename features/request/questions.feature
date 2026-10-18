Feature: Scientific questions
  Confirmed requirement facets are turned into scientific questions with evidence acceptance criteria.

  Background:
    Given the customer request "Can you make foie gras?"
    And the facets "product", "production approach", "biological source", "desired attributes", "time" and "resources" are confirmed

  Scenario: deriving questions covers every mandatory facet and states the fields each answer must carry
    When the scientific questions are derived
    Then every mandatory facet is investigated by at least one scientific question
    And every scientific question investigates at least one facet
    And the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?" requires the answer fields "species", "cell type", "endpoint", "measurement", "observed outcome" and "limitations"

  Scenario: a question with no eligible evidence is unresolved, not answered
    Given a scientific question with no eligible evidence
    When the status of the scientific question is read
    Then the status is "unresolved"
    And the missing information is stated
