package com.thinkmorestupidless.experimentproposals.replay

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString,
  JsonValueCodec,
  WriterConfig
}
import com.thinkmorestupidless.experimentproposals.application.agents.Prompts
import com.thinkmorestupidless.ankka.agent.{ChatMessage, MessageContent, ModelRequest}
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * One recorded or authored model reply, keyed by the prompt version (the system message's first
 * line) and the case marker (the user message's first line). The file is
 * `<dir>/<promptVersion>/<case>.json`.
 */
final case class Cassette(
    promptVersion: String,
    `case`: String,
    reply: String,
    recorded: Boolean,
    authoredBy: Option[String] = None,
    checkedQuotations: Boolean = false,
    refusal: Option[String] = None,
    model: Option[String] = None
)

final case class CassetteKey(promptVersion: String, caseMarker: String):
  def slug: String              = CassetteKey.slugOf(caseMarker)
  def path(dir: Path): Path     = dir.resolve(promptVersion).resolve(s"$slug.json")
  override def toString: String = s"$promptVersion / $caseMarker"

object CassetteKey:
  def slugOf(text: String): String = text.trim.replaceAll("[^A-Za-z0-9._-]", "_")

  /** The key a request carries, when its messages carry the two marker lines. */
  def of(request: ModelRequest): Either[String, CassetteKey] =
    val version = request.systemMessage.flatMap(Prompts.versionOf)
    val marker = request.messages.collectFirst {
      case ChatMessage.User(content) if content.exists(userCase(_).isDefined) =>
        content.flatMap(userCase).head
    }
    (version, marker) match
      case (Some(v), Some(m)) => Right(CassetteKey(v, m))
      case (None, _) =>
        Left("the system message does not start with '# prompt_version: <stage>@v<n>'")
      case (_, None) => Left("no user message starts with '# case: <marker>'")

  private def userCase(content: MessageContent): Option[String] = content match
    case MessageContent.Text(text) => Prompts.caseOf(text)
    case _                         => None

final class Cassettes(val dir: Path):
  private given JsonValueCodec[Cassette] = Codecs.make[Cassette]

  def read(key: CassetteKey): Option[Cassette] =
    val file = key.path(dir)
    Option.when(Files.exists(file))(readFromString[Cassette](Files.readString(file)))

  def write(cassette: Cassette): Path =
    val file = CassetteKey(cassette.promptVersion, cassette.`case`).path(dir)
    Files.createDirectories(file.getParent)
    Files.writeString(file, writeToString(cassette, WriterConfig.withIndentionStep(2)) + "\n")
    file

  /** Every cassette under one prompt version. */
  def list(promptVersion: String): Vector[Cassette] =
    val folder = dir.resolve(promptVersion)
    if !Files.isDirectory(folder) then Vector.empty
    else
      Files
        .list(folder)
        .iterator
        .asScala
        .toVector
        .filter(_.toString.endsWith(".json"))
        .sortBy(_.getFileName.toString)
        .map(p => readFromString[Cassette](Files.readString(p)))
