package com.thinkmorestupidless.experimentproposals.adapters

import com.thinkmorestupidless.experimentproposals.domain.sources.SourceRecord

import java.security.MessageDigest

/** A failure that may pass: a rate limit, a 5xx, a timeout. The step retries it. */
final case class AdapterTransient(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

/** A failure that will not pass by retrying: not found, malformed, forbidden. */
final case class AdapterPermanent(message: String, cause: Option[Throwable] = None)
    extends RuntimeException(message, cause.orNull)

/** A reference an adapter's search returns, before anything is fetched. */
final case class SourceRef(identifier: String, title: String, score: Option[Double] = None)

/** A discovery and fetch source (contracts/stages.md). */
trait Adapter:
  def name: String
  def search(query: String, filters: Map[String, String]): Vector[SourceRef]
  def fetch(identifier: String): SourceRecord

object Adapter:
  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

/** The adapters this service knows, by name. */
final class Adapters(all: Vector[Adapter]):
  private val byName = all.map(a => a.name -> a).toMap
  def apply(name: String): Either[String, Adapter] =
    byName.get(name).toRight(s"no adapter named '$name'")
  def names: Vector[String] = all.map(_.name)
