Feature: The journey and its cards
  A customer request's path through the investigation is shown as a journey of stages, and each stage opens the record it produced as a card.

  Background:
    Given the customer request "Can you make foie gras?"

  Scenario: a journey shows which stages are reached, where the investigation waits and what is unreached
    Given the investigation waits at the facet review
    When the journey is shown
    Then the stages "request", "facets", "round-trip check" and "facet review" are reached
    And the stage "facet review" is where the investigation waits
    And every later stage is unreached

  Scenario: the facets card shows every facet with its origin, its quotation and the round-trip check
    Given the stage "facets" is selected
    When its card is shown
    Then the card lists every facet with its origin, its importance and its status
    And each facet with the origin "explicit" shows its quotation and its source span in the customer request
    And the card shows the omissions and unsupported additions of the round-trip check

  Scenario: a search card shows the plan, the results, the exclusions and the re-run
    Given the stage "search" of the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?" is selected
    When its card is shown
    Then the card shows the lexical queries and semantic queries of the search plan
    And the card shows every result of each search execution in rank order
    And the card shows each exclusion with the reason that removed it
    And the card shows whether the re-run returned the same identifiers

  Scenario: a verification card shows the finding beside its passages and every check
    Given the stage "verification" of a finding is selected
    When its card is shown
    Then the finding is shown beside the text each of its source spans resolves to
    And every verification check is named as passed or failed
    And the rationale of the verification is shown

  Scenario: the candidates card compares the approaches on every criterion and asserts no probability
    Given the stage "candidates" is selected
    When its card is shown
    Then the candidate approaches are shown side by side on every comparison criterion
    And each candidate approach lists its supporting, contrary, indirect and missing evidence
    And no numeric success probability is shown

  Scenario: the design card is marked a proposal until an approval is recorded
    Given the stage "design" is selected
    And the experimental design is not approved
    When its card is shown
    Then the twelve sections of the experimental design are rendered
    And the card is marked as a proposal awaiting review

  Scenario: the design card shows the approval once it is recorded
    Given the stage "design" is selected
    And the reviewer has approved the experimental design
    When its card is shown
    Then the card is not marked as a proposal
    And the approval is shown with the reviewer, the time and the rationale

  Scenario: a journey follows the investigation without being asked again
    Given the journey is shown
    When the investigation reaches the next stage
    Then the journey shows that stage as reached within five seconds
    And the journey was not asked for again

  Scenario: a failed stage shows its defect and where the investigation waits
    Given a stage that failed after its retries
    When the journey is shown
    Then the stage is marked as failed with its classified defect
    And the stage is where the investigation waits

  Scenario: the impact card lists every flagged dependent and the design keeps its approved version
    Given the reviewer has approved the experimental design
    And the source "Paper B" has been marked "retracted"
    When the card of the stage "impact" is shown
    Then every dependent flagged for review is listed
    And each flag names the changed source version
    And the card of the stage "design" shows the approved version unchanged

  Scenario: a card is the stored record
    Given any stage that produced a record
    When its card is shown
    Then the content of the card is identical to the stored record at its version

  Scenario: a running stage says so
    Given a stage that is running
    When its card is shown
    Then the card says the stage is running
    And the card shows what earlier attempts recorded
