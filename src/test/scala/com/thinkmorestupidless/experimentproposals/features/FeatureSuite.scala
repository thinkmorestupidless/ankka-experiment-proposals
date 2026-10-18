package com.thinkmorestupidless.experimentproposals.features

import com.thinkmorestupidless.experimentproposals.features.steps.{
  AssessmentSteps,
  AssuranceSteps,
  DesignSteps,
  ImpactSteps,
  CommonSteps,
  EvidenceSteps,
  QuestionSteps,
  RequestSteps,
  SearchSteps,
  SourceSteps
}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

/**
 * The living features under `features/`, run through the service's HTTP routes in the tier
 * `EDP_TIER` names (contracts/features-and-tiers.md). Step definitions are the mixed-in traits, one
 * per area.
 */
class FeatureSuite
    extends GherkinSuite("features")
    with LogCapturing
    with CommonSteps
    with RequestSteps
    with QuestionSteps
    with EvidenceSteps
    with AssessmentSteps
    with SourceSteps
    with SearchSteps
    with DesignSteps
    with AssuranceSteps
    with ImpactSteps
