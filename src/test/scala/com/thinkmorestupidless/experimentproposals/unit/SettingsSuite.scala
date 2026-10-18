package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.config.{Settings, Tier}

class SettingsSuite extends munit.FunSuite:

  test("defaults apply when only the token is set") {
    val s = Settings.fromEnv(Map("EDP_API_TOKEN" -> "t")).toOption.get
    assertEquals(s.tier, Tier.replay)
    assertEquals(s.retryBudget, 2)
    assertEquals(s.decisions, Settings.DefaultDecisions)
    assertEquals(s.cassettes, "evals/cassettes")
  }

  test("the tier, budgets and decisions are read, and a bad value is refused") {
    val env = Map(
      "EDP_API_TOKEN"    -> "t",
      "EDP_TIER"         -> "live",
      "EDP_RETRY_BUDGET" -> "3",
      "EDP_DECISIONS"    -> "a; b ;;"
    )
    val s = Settings.fromEnv(env).toOption.get
    assertEquals(s.tier, Tier.live)
    assertEquals(s.retryBudget, 3)
    assertEquals(s.decisions, Vector("a", "b"))
    assert(Settings.fromEnv(Map("EDP_API_TOKEN" -> "t", "EDP_TIER" -> "dev")).isLeft)
    assert(Settings.fromEnv(Map.empty).isLeft)
  }
