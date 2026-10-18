Feature: Source ingestion
  Literature and internal records are ingested with identity, status, provenance and resolvable structure.

  Scenario: an ingested source keeps its identity, status and provenance
    Given a literature source with a persistent identifier
    When the source is ingested
    Then its identifier, title, authors, date, venue, location, licence, status, retrieval time, content hash, source version and parse version are recorded

  Scenario: ingesting the same source twice creates no duplicate
    Given a literature source that has been ingested
    When the same source is ingested again
    Then it is recognised by its identifier and content hash
    And no duplicate record is created

  Scenario: an unresolved table is flagged, not dropped
    Given a source with a table that cannot be parsed reliably
    When the source is ingested
    Then the source carries a parse-completeness flag naming the unresolved table

  Scenario: a unit is validated and never converted silently
    Given an internal measurement with a unit
    When the measurement is ingested
    Then the unit is validated
    And no conversion is applied

  Scenario Outline: a source's status is recorded and surfaced to eligibility
    Given a source marked "<status>"
    When the source is ingested
    Then the status is recorded
    And the status is surfaced to eligibility

    Examples:
      | status     |
      | retracted  |
      | corrected  |
      | superseded |

  Scenario: a source whose full text cannot be fetched keeps metadata and abstract
    Given a literature source whose full text cannot be fetched
    When the source is ingested
    Then its metadata and abstract are retained
    And the source is marked "abstract-only"

  Scenario: a corrupt extraction is quarantined with a reason
    Given a source whose extraction is corrupt or materially ambiguous
    When the source is ingested
    Then the extraction is quarantined with a reason
    And nothing is dropped silently
