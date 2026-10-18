package com.thinkmorestupidless.experimentproposals.domain.provenance

import java.security.{MessageDigest, SecureRandom}

/** Ids and short hashes. A record's id is derived from its parents; only a request's is minted. */
object Ids:
  private val alphabet = "0123456789abcdefghjkmnpqrstvwxyz"
  private val random   = new SecureRandom()

  /** `<prefix>-<time><random>`, sortable by time, Crockford base32, no ambiguity in a URL. */
  def mint(prefix: String): String =
    val time = System.currentTimeMillis()
    val sb   = new StringBuilder
    var t    = time
    for _ <- 1 to 10 do
      sb.insert(0, alphabet((t & 31).toInt))
      t >>= 5
    for _ <- 1 to 8 do sb.append(alphabet(random.nextInt(32)))
    s"$prefix-${sb.toString}"

  /** The first twelve hex characters of the SHA-256 of the text: a case marker's stable part. */
  def short(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .take(6)
      .map("%02x".format(_))
      .mkString

  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
