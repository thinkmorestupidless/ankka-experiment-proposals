Feature: Search planning and retrieval
  Each scientific question gets a reproducible search plan; eligibility is judged after ranking, never by it.

  Scenario: a search plan carries its question, queries, sources and stopping criterion
    Given a scientific question
    When a search plan is built
    Then the search plan names the scientific question
    And the search plan has at least one lexical query and one semantic query
    And the search plan names its target sources, filters and stopping criterion

  Scenario: a search execution records everything it returned
    Given a search plan
    When the search plan is executed
    Then the search execution records the query, the source, the time, the parameters and every returned result with its version

  Scenario: a retracted source ranked first is excluded with a reason
    Given a search execution whose first-ranked result is a source marked "retracted"
    When eligibility is applied
    Then the source is excluded or downgraded
    And the reason is recorded against the search execution

  Scenario: a retrieved chunk is resolved with its context
    Given a retrieved chunk
    When the chunk is resolved for assessment
    Then its parent section, adjacent context, associated tables and source metadata are available

  Scenario: a search execution can be re-run from its record
    Given a search execution
    When the search execution is re-run from its record
    Then the same identifiers are returned against the same source versions

  Scenario: a coverage gap triggers a bounded supplemental search
    Given a coverage gap for a scientific question
    When a supplemental search is run for the coverage gap
    Then the search execution is linked to the coverage gap
    And the supplemental search is bounded

  Scenario: an empty search result is a coverage gap
    Given a search plan whose execution returns nothing
    When the result is interpreted
    Then it is recorded as a coverage gap
    And it is not treated as evidence that the approach is impossible
