Feature: Experimental design and approval
  The selected approach becomes a structured experimental design that only a reviewer can approve.

  Background:
    Given a selected candidate approach

  Scenario: a design has every required section and every reference resolves
    When the experimental design is generated
    Then the experimental design has all twelve required sections
    And every evidence reference in it resolves to a verified claim and a source span

  Scenario: a rendering is derived from the design and adds nothing
    Given a generated experimental design
    When the experimental design is rendered
    Then the rendering is produced from the structured experimental design
    And the rendering contains no claim absent from the experimental design

  Scenario: a rejection stays visible with its reason
    Given an experimental design under review
    When the reviewer rejects the experimental design with a reason
    Then the rejection, the reviewer, the time and the reason are visible
    And the experimental design is not presented as approved

  Scenario: an unapproved design is presented as a proposal
    Given an experimental design that is not approved
    When the experimental design is presented
    Then it is marked as a proposal awaiting review
    And no detailed wet-lab procedure is presented as authorised
