package com.thinkmorestupidless.experimentproposals.features

import com.thinkmorestupidless.experimentproposals.features.steps.{
  AssessmentSteps,
  AssuranceSteps,
  AutomationSteps,
  CommonSteps,
  DesignSteps,
  EvidenceSteps,
  ImpactSteps,
  QuestionSteps,
  RequestSteps,
  SearchSteps,
  SourceSteps
}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

/**
 * Every step this service defines, for a suite of one area's features. Each area is its own suite
 * so `features/interface/`, which the browser suites run, is never read here: a `GherkinSuite`
 * reads one directory and cannot leave one out (notes/ankka-requests.md, 2026-11-04). Each suite
 * starts its own service on ports it chooses, one after another.
 */
trait AllSteps
    extends LogCapturing
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
    with AutomationSteps

/** `features/request/`. */
class RequestFeatures extends GherkinSuite("features/request") with AllSteps

/** `features/search/`. */
class SearchFeatures extends GherkinSuite("features/search") with AllSteps

/** `features/sources/`. */
class SourcesFeatures extends GherkinSuite("features/sources") with AllSteps

/** `features/evidence/`. */
class EvidenceFeatures extends GherkinSuite("features/evidence") with AllSteps

/** `features/design/`. */
class DesignFeatures extends GherkinSuite("features/design") with AllSteps

/** `features/assurance/`. */
class AssuranceFeatures extends GherkinSuite("features/assurance") with AllSteps

/** `features/investigation/`. */
class InvestigationFeatures extends GherkinSuite("features/investigation") with AllSteps:
  // A held model call ends at ankka.ask-timeout, 10 seconds by default, and a verification that
  // ends so is settled. The automation scenarios hold a source's verification until the question
  // has met it, so here a held call waits longer.
  override protected def serviceSettings: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("ankka.ask-timeout = 200s")
