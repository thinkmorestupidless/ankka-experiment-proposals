Feature: Decisions on the card
  Every reviewer decision is made on the card of the thing decided, by a named reviewer with a rationale; the assistant never makes one.

  Background:
    Given the customer request "Can you make foie gras?"
    And the reviewer "demo-reviewer" is configured

  Scenario: confirming the facets on their card records each decision and moves the investigation on
    Given the facets card shows eight facets
    When the reviewer confirms each mandatory facet and then the facets on the card
    Then the history of each facet holds the reviewer, the time and the rationale
    And the investigation proceeds to deriving the scientific questions

  Scenario: the facets cannot be confirmed while a mandatory facet is unsettled
    Given a mandatory facet that is neither confirmed, assumed nor unresolved and flagged
    When the reviewer confirms the facets on the card
    Then the confirmation is refused
    And the refusal names the facet

  Scenario: selecting an approach on the card keeps the rejected one with its reason
    Given the candidates card
    When the reviewer selects one candidate approach on the card with a reason for the other
    Then the decision record keeps the rejected candidate approach with that reason

  Scenario: approving a design version on the card records the approval
    Given the design card
    When the reviewer approves version "1" on the card with a rationale
    Then the approval is recorded against version "1"
    And the experimental design is not marked as a proposal

  Scenario: requesting changes on the card keeps the earlier version and its decision
    Given the design card
    When the reviewer requests changes on the card with a rationale
    Then a new version of the experimental design is written
    And the earlier version and its decision remain visible in the history

  Scenario: a name that is not a configured reviewer cannot decide
    Given the name "nobody" is not a configured reviewer
    When a decision is made on a card in that name
    Then the decision is refused
    And nothing is recorded

  Scenario: rejecting a record in the review queue removes it from the queue
    Given a finding in the review queue
    When the reviewer rejects the finding on its card with a rationale
    Then the status of the finding is "rejected"
    And the finding is no longer in the review queue

  Scenario: a decision on a card is recorded exactly as the operator's route records it
    Given any decision a reviewer can make
    When the decision is made on its card
    Then the record holds the same reviewer, time, rationale and version as the operator's route would record
    And it is refused in exactly the cases the operator's route refuses
