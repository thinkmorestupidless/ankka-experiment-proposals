Feature: The investigation runs itself
  Between the reviewer's pauses the investigation runs every stage without being asked; it pauses only where a reviewer decides or a stage has failed.

  Background:
    Given the customer request "Can you make foie gras?"

  Scenario: an empty corpus does not stop the investigation
    Given no source is ingested
    When the automatic stage "search" runs
    Then every scientific question records a coverage gap
    And the investigation proceeds to sufficiency
    And sufficiency abstains

  Scenario: confirming the facets carries the investigation to the choice of approach
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the reviewer confirms the facets
    When nothing else is done
    Then the investigation reaches the pause for the choice of approach
    And a stage run is recorded for every stage between

  Scenario: every verified eligible finding a search retrieved is assessed
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the search for a scientific question retrieved three verified findings that are eligible evidence
    When the automatic stage "assessment" runs
    Then all three findings are assessed against the scientific question
    And no finding that is unverified or not eligible evidence is assessed

  Scenario: assessment waits for the retrieved records' verification to settle
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the search for a scientific question retrieved a source whose findings are still being verified
    When the automatic stage "assessment" runs
    Then the stage waits until the verification of every retrieved finding has settled
    And only the verified findings that are eligible evidence are assessed

  Scenario: assessment fails when verification does not settle within its bound
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the search for a scientific question retrieved a source whose verification does not settle within the bound
    When the automatic stage "assessment" runs
    Then the stage fails with the defect "verification unsettled"
    And the investigation pauses at the stage

  Scenario: sufficiency is judged for every configured decision
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the decisions "recommend production feasibility" and "propose a feasibility experiment on lipid accumulation" are configured
    When the automatic stage "sufficiency" runs
    Then a sufficiency judgement exists for each decision for every scientific question

  Scenario: a stage that fails after its retries pauses the investigation and resumes without duplicates
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And a stage that fails after its retries
    When the investigation reaches the stage
    Then the investigation pauses at the stage with the defect recorded
    And resuming the investigation runs the stage again
    And no record is duplicated

  Scenario: a manual stage still works after the automatic stage ran it
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the automatic stage "assessment" has run for a scientific question
    When the operator requests the assessment of that scientific question again
    Then the assessment runs
    And a further stage run is recorded

  Scenario: a source is ingested only when a person asks
    Given the sources "Paper A", "Paper B", "Paper C" and the retracted source are ingested
    And the investigation runs
    When the automatic stage "search" finds nothing in the store
    Then no source is ingested by the investigation
