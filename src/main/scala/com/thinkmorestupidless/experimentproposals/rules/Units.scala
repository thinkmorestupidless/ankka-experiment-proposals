package com.thinkmorestupidless.experimentproposals.rules

/**
 * The unit table internal measurements are validated against (FR-012). A unit outside it is
 * recorded as invalid; nothing is ever converted.
 */
object Units:
  val table: Set[String] = Set(
    // mass and concentration
    "g",
    "mg",
    "ug",
    "µg",
    "ng",
    "kg",
    "g/L",
    "mg/mL",
    "mg/L",
    "ug/mL",
    "µg/mL",
    "ng/mL",
    "mg/g",
    "ug/g",
    "µg/g",
    "ug/mg",
    "µg/mg",
    "mg/mg",
    "% w/w",
    "% w/v",
    "% v/v",
    "%",
    // molar
    "M",
    "mM",
    "uM",
    "µM",
    "nM",
    "mol/L",
    "mmol/L",
    // volume
    "L",
    "mL",
    "uL",
    "µL",
    // counts and density
    "cells",
    "cells/mL",
    "cells/cm2",
    "cells/well",
    "fold",
    "ratio",
    // time and temperature
    "s",
    "min",
    "h",
    "d",
    "days",
    "hours",
    "°C",
    "degC",
    // assays
    "RFU",
    "OD",
    "AU",
    "a.u.",
    "pg",
    "pg/mL",
    "U/L",
    "IU/L"
  )

  private val normalised: Set[String] = table.map(normalise)

  def normalise(unit: String): String = unit.trim.replace('μ', 'µ').toLowerCase

  def valid(unit: String): Boolean = normalised.contains(normalise(unit))
