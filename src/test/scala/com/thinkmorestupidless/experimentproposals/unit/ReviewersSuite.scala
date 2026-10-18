package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers

import java.nio.file.Files

class ReviewersSuite extends munit.FunSuite:

  test("a listed reviewer is found, an unlisted one refused, a stand-in marked") {
    val reviewers =
      Reviewers.fromInline("demo-reviewer:stand-in:stand-in, ada:reviewer").toOption.get
    assert(reviewers.check("ada").isRight)
    assertEquals(reviewers.check("ada").toOption.get.standIn, false)
    assertEquals(reviewers.check("demo-reviewer").toOption.get.standIn, true)
    assertEquals(reviewers.check("mallory"), Left("'mallory' is not a configured reviewer"))
  }

  test("a malformed entry fails the load") {
    assert(Reviewers.fromInline(":role:x").isLeft)
    assert(Reviewers.fromInline("").isLeft)
  }

  test("a JSON file loads, and the variable wins over the file") {
    val file = Files.createTempFile("reviewers", ".json")
    Files.writeString(file, """[{"name":"ada","role":"reviewer","standIn":false}]""")
    val fromFile = Reviewers.load(Map("EDP_REVIEWERS_FILE" -> file.toString)).toOption.get
    assert(fromFile.isListed("ada"))
    val inlineWins =
      Reviewers
        .load(Map("EDP_REVIEWERS" -> "bob:reviewer", "EDP_REVIEWERS_FILE" -> file.toString))
        .toOption
        .get
    assert(inlineWins.isListed("bob") && !inlineWins.isListed("ada"))
    assert(Reviewers.load(Map.empty).isLeft)
  }
