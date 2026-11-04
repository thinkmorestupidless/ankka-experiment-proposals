Feature: Admission and running beside the service
  The interface admits a person with the shared token, runs beside the service wherever the service runs, and shows only what the service holds.

  Scenario: the shared token admits a person once
    Given the interface is running beside the service
    When the person enters the shared token
    Then the requests are listed
    And the token is not asked for again in the same browser

  Scenario: a wrong token shows nothing
    Given the interface is running beside the service
    When the person enters a token that is not the shared token
    Then nothing of the records is shown
    And the refusal says the token was refused

  Scenario: every card comes from the service beside the interface
    Given the interface is deployed beside the service
    When any card is shown
    Then its content is read from the service beside the interface
    And the interface holds no copy of the record beyond what it shows

  Scenario: the interface starts beside a locally running service with one command
    Given the service is running on the same machine
    When the operator starts the interface as documented
    Then the interface is usable within one minute
