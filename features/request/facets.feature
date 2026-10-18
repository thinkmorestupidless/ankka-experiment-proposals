Feature: Requirement facets
  A customer request is kept unchanged and interpreted into requirement facets that a reviewer settles.

  Background:
    Given the customer request "Can you make foie gras?"

  Scenario: the customer request is kept unchanged and every facet carries its origin
    When the facets are extracted
    Then the stored customer request is identical to the entered text
    And the facet "product" has the origin "explicit"
    And the facet "production approach" has the origin "inferred" and the status "proposed"
    And the facet "objective" has the origin "inferred" and the status "proposed"
    And the facet "biological source" has the origin "domain-derived" and the status "unresolved"

  Scenario: a facet that nothing in the request supports is an unsupported addition
    Given an extracted facet that nothing in the customer request supports
    When the round-trip check runs
    Then the facet is reported as an unsupported addition

  Scenario: request content that no facet covers is an omission
    Given a part of the customer request that no extracted facet covers
    When the round-trip check runs
    Then that part is reported as an omission

  Scenario: a similar reconstruction alone does not pass the round-trip check
    Given a reconstruction that resembles the customer request and contains an unsupported addition
    When the round-trip check runs
    Then the round-trip check fails

  Scenario: confirming a facet records the reviewer and keeps the earlier state
    Given a facet with the status "proposed"
    When the reviewer confirms the facet
    Then the facet has the origin "human-confirmed"
    And the reviewer and the time of the confirmation are recorded
    And the earlier state of the facet remains visible in its history

  Scenario: an unresolved mandatory facet is flagged on every proposal
    Given a mandatory facet with the status "unresolved"
    When a proposal is produced
    Then the proposal lists the facet as unresolved
    And the proposal assumes no value for the facet
