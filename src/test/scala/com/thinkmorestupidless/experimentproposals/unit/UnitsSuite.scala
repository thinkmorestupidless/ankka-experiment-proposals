package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.rules.Units

class UnitsSuite extends munit.FunSuite:

  test("a unit in the table is valid, whatever its case or micro sign") {
    assert(Units.valid("mg/mL"))
    assert(Units.valid("MG/ML"))
    assert(Units.valid("μg/mL"))
  }

  test("a unit outside the table is invalid and is not guessed") {
    assert(!Units.valid("parsecs"))
    assert(!Units.valid("mg per litre"))
  }
