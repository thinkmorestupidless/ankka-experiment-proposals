Feature: The conversation
  A person asks the assistant about a request and its records and is answered from the records, with each cited record shown as a card; the assistant acts only on a confirmed action and never decides.

  Background:
    Given the customer request "Can you make foie gras?" has an approved experimental design
    And a model is configured for the assistant

  Scenario: why a finding is indirect is answered with the species rule and its assessment
    When the person wants to know why the finding from the source "Paper B" is indirect
    Then the answer names the species rule and the assessment
    And the card of the assessment is shown with its applicability and its rationale

  Scenario: what blocked the recommendation is answered with the critical gap and the sufficiency judgement
    When the person wants to know what blocked the decision "recommend production feasibility"
    Then the answer names the critical gap
    And the card of the sufficiency judgement is shown with its status, its critical gaps and its assumptions

  Scenario: what the search excluded is answered with the retracted source and its rule
    When the person wants to know what the search for the scientific question "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?" excluded
    Then the answer names the retracted source and the eligibility rule
    And the card of the search execution is shown with its exclusions

  Scenario: showing the facets shows their card
    When the person wants to see the facets
    Then the card of the facets is shown

  Scenario: every record an answer cites is shown as its stored card
    When the person puts any question the records answer
    Then each record the answer cites is named by a reference
    And each reference is shown as the card of the stored record

  Scenario: a wish to approve becomes a prefilled decision, not an approval
    Given an experimental design awaiting approval
    When the person tells the assistant to approve it
    Then no approval is recorded
    And a prefilled decision is shown for the reviewer to complete
    And the answer says a reviewer must decide

  Scenario: a question the records do not answer is answered with what is not established
    When the person puts a question the records do not answer
    Then the answer states what is not established
    And the answer supplies no record that does not exist

  Scenario: a reference that does not resolve is shown as unresolved
    Given an answer that cites a record that does not exist
    When the answer is shown
    Then the reference is marked as unresolved
    And no card is shown for it

  Scenario: an answer appears as it is written
    When the person puts a question
    Then the answer appears as it is written

  Scenario: an answer cut short by a failure records nothing
    Given the model fails while an answer is being written
    When the answer ends
    Then the answer is marked as failed
    And nothing is recorded

  Scenario: two conversations do not share a history
    Given two conversations
    When a question is put in each
    Then each answer belongs to its own conversation
    And neither conversation sees the history of the other

  Scenario: a request's conversation has that request as its subject
    Given the conversation of the customer request is opened from its journey
    When the person wants to know why the finding from the source "Paper B" is indirect without naming the customer request
    Then the answer is about that customer request
    And the card of the assessment selects the stage "assessment" on the journey

  Scenario: the free-standing conversation answers across requests
    Given two customer requests
    And the free-standing conversation is opened from the rail
    When the person wants to know which customer requests are waiting for a reviewer
    Then the answer names both customer requests that are waiting
    And the card of each customer request is shown

  @no-model
  Scenario: without a model the assistant says so and everything else works
    Given no model is configured for the assistant
    When the conversation is opened
    Then the conversation says the assistant is unavailable and why
    And the journey, the cards and the decisions still work

  Scenario: a conversation that reaches its budget declines further questions
    Given a conversation that has spent its budget
    When the person puts a question
    Then the assistant declines and says the budget is reached
    And the journey, the cards and the decisions still work
    And a new conversation starts with a fresh budget

  Scenario: a conversation shows what it has spent of its budget
    Given a conversation with a budget
    When an answer is written
    Then the conversation shows the tokens used against the total

  Scenario: a request typed in the conversation is submitted on confirmation
    When the person types "Can you make foie gras?" and confirms the action "submit a request"
    Then the customer request is stored identical to the typed text
    And the journey of the customer request is shown

  Scenario: a source is ingested from the conversation on confirmation
    When the person has the assistant ingest the source "paper-a.xml" and confirms the action
    Then the source is ingested
    And the card of the source is shown with its version, its status and its records as they are verified

  Scenario: a fault is injected from the conversation on confirmation
    When the person has the assistant inject the fault "endpoint swap" on a finding and confirms the action
    Then the fault is recorded as a new attempt of the finding
    And the card of the finding shows the verification detecting "unsupported endpoint"

  Scenario: an action that is not confirmed records nothing
    When the person has the assistant propose an action and does not confirm it
    Then nothing is recorded

  Scenario: an action the assistant is not given is declined with where it is done
    When the person tells the assistant to approve an experimental design or to edit a record
    Then the assistant declines
    And the answer names where the decision is made
