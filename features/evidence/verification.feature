Feature: Source-context verification
  Every proposed finding and claim is checked against the full source context before it can count as evidence.

  Scenario: a claim whose cited span does not support it is not admitted
    Given a proposed claim whose cited source span resolves but does not support it
    When the claim is verified
    Then the claim is labelled "insufficiently supported" or "contradicted"
    And the claim is not admitted as affirmative evidence

  Scenario: a broken span reference fails before verification
    Given a proposed claim with a source span that does not resolve
    When the claim is validated
    Then validation fails before verification
    And the claim is quarantined with the defect "span unresolved"

  Scenario: a retry is bounded, classified and checked against the source
    Given a proposed finding that failed verification with a retryable defect
    When the extraction is retried
    Then the retry is triggered by the classified defect
    And the number of retries is bounded
    And the failed attempt is kept in the history
    And the retry is verified against the source and not against an earlier summary

  Scenario: an ambiguous interpretation is routed to a reviewer
    Given a proposed claim whose verification is ambiguous
    When verification completes
    Then the claim requires review by a reviewer
    And the claim is not accepted automatically
