package com.thinkmorestupidless.experimentproposals.application.agents

import scala.io.Source
import scala.util.Using

/**
 * A versioned prompt: `src/main/resources/prompts/<stage>/<version>.md`, whose first line is
 * `# prompt_version: <stage>@<version>`. A prompt is never edited in place; a change is a new file.
 */
final case class Prompt(stage: String, version: Int, system: String):
  def promptVersion: String = s"$stage@v$version"

object Prompts:
  private val Header = """^# prompt_version: ([a-z][a-z0-9-]*)@v(\d+)\s*$""".r

  def load(stage: String, version: Int): Prompt =
    val path = s"/prompts/$stage/v$version.md"
    val stream = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw new IllegalStateException(s"no prompt at $path"))
    val text = Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)
    text.linesIterator.nextOption() match
      case Some(Header(s, v)) if s == stage && v.toInt == version => Prompt(stage, version, text)
      case other =>
        throw new IllegalStateException(
          s"prompt $path must start with '# prompt_version: $stage@v$version', found ${other.getOrElse("nothing")}"
        )

  /**
   * The user message: the case marker first, so the replay provider can key on it, then the ask.
   */
  def userMessage(caseMarker: String, ask: String): String = s"# case: $caseMarker\n\n$ask"

  val CaseLine    = """^# case: (.+)$""".r
  val VersionLine = """^# prompt_version: (.+)$""".r

  def caseOf(userMessage: String): Option[String] =
    userMessage.linesIterator.nextOption().collect { case CaseLine(c) => c.trim }

  def versionOf(systemMessage: String): Option[String] =
    systemMessage.linesIterator.nextOption().collect { case VersionLine(v) => v.trim }

/** Source text for `withContext`: one block per section, headed by a key the model quotes by. */
object Sections:
  final case class Block(key: String, title: String, text: String)

  def render(blocks: Seq[Block]): String =
    blocks.map(b => s"[${b.key}] ${b.title}\n${b.text}").mkString("\n\n")

  def keyed[A](sections: Seq[A])(title: A => String, text: A => String): Seq[Block] =
    sections.zipWithIndex.map { case (s, i) => Block(s"S${i + 1}", title(s), text(s)) }
