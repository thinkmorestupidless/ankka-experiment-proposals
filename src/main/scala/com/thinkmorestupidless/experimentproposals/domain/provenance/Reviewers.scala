package com.thinkmorestupidless.experimentproposals.domain.provenance

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.{Files, Path}

/** A configured reviewer (FR-004a). A stand-in is a listed entry marked so. */
final case class Reviewer(name: String, role: String, standIn: Boolean)

/**
 * The configured list of reviewers, loaded once at start. Every command that records a reviewer and
 * every endpoint consults it; a name not on it is refused before anything is persisted.
 */
final class Reviewers(val all: Vector[Reviewer]):
  private val byName = all.map(r => r.name -> r).toMap

  def check(name: String): Either[String, Reviewer] =
    byName.get(name).toRight(s"'$name' is not a configured reviewer")

  def isListed(name: String): Boolean = byName.contains(name)

object Reviewers:
  val Operator = "operator"

  private given JsonValueCodec[Vector[Reviewer]] = Codecs.make[Vector[Reviewer]]

  /**
   * `EDP_REVIEWERS` is `name:role:stand-in,...` (`stand-in` as `stand-in`, `true` or `yes`);
   * `EDP_REVIEWERS_FILE` names a JSON file of `{name, role, standIn}`. The variable wins when both
   * are set. A malformed entry fails the load, so a mistake is found at start, not at the first
   * approval.
   */
  def load(env: Map[String, String]): Either[String, Reviewers] =
    (env.get("EDP_REVIEWERS").map(_.trim).filter(_.nonEmpty), env.get("EDP_REVIEWERS_FILE")) match
      case (Some(inline), _)  => fromInline(inline)
      case (None, Some(file)) => fromFile(Path.of(file))
      case (None, None) => Left("no reviewers configured: set EDP_REVIEWERS or EDP_REVIEWERS_FILE")

  def fromInline(text: String): Either[String, Reviewers] =
    val parsed = text.split(',').toVector.map(_.trim).filter(_.nonEmpty).map { entry =>
      entry.split(':').toVector.map(_.trim) match
        case Vector(name, role, standIn) if name.nonEmpty =>
          Right(Reviewer(name, role, Set("stand-in", "true", "yes").contains(standIn.toLowerCase)))
        case Vector(name, role) if name.nonEmpty => Right(Reviewer(name, role, standIn = false))
        case Vector(name) if name.nonEmpty => Right(Reviewer(name, "reviewer", standIn = false))
        case _ => Left(s"malformed reviewer entry '$entry': expected name:role:stand-in")
    }
    parsed.collectFirst { case Left(e) => e } match
      case Some(error) => Left(error)
      case None        => validate(parsed.collect { case Right(r) => r })

  def fromFile(path: Path): Either[String, Reviewers] =
    if !Files.exists(path) then Left(s"reviewers file not found: $path")
    else
      scala.util
        .Try(readFromString[Vector[Reviewer]](Files.readString(path)))
        .toEither
        .left
        .map(e => s"reviewers file $path could not be read: ${e.getMessage}")
        .flatMap(validate)

  private def validate(reviewers: Vector[Reviewer]): Either[String, Reviewers] =
    if reviewers.isEmpty then Left("the reviewer list is empty")
    else if reviewers.exists(_.name.isBlank) then Left("a reviewer has no name")
    else if reviewers.map(_.name).distinct.size != reviewers.size then
      Left("two reviewers share a name")
    else Right(new Reviewers(reviewers))
