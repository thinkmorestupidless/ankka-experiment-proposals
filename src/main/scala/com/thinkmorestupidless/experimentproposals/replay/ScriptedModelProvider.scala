package com.thinkmorestupidless.experimentproposals.replay

import com.thinkmorestupidless.ankka.agent.*
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.nio.file.{Files, Path}
import scala.concurrent.Future

/**
 * The assistant's model when no live model is wanted (contracts/assistant.md, the scripted file):
 * the first rule whose `when` is in the last thing the person said answers, with one tool-calling
 * turn first when the rule names tools. Matching ignores case. Usage is estimated from the text, so
 * a conversation's budget moves as it would.
 */
final class ScriptedModelProvider(rules: Vector[ScriptedModelProvider.Rule]) extends ModelProvider:
  import ScriptedModelProvider.*

  def name: String      = "scripted"
  def modelName: String = "scripted"

  def complete(request: ModelRequest): Future[ModelResponse] =
    val said      = lastUserText(request).getOrElse("").toLowerCase
    val sinceUser = request.messages.reverse.takeWhile(!_.isInstanceOf[ChatMessage.User])
    val toolsDone = sinceUser.exists(_.isInstanceOf[ChatMessage.ToolResults])
    val fill      = placeholders(request, sinceUser)
    val response = rules.find(r => said.contains(r.when.toLowerCase)) match
      case Some(rule) if rule.tools.nonEmpty && !toolsDone =>
        ModelResponse(
          "",
          rule.tools.zipWithIndex.map((t, i) =>
            ToolCall(
              s"scripted-${i + 1}",
              t.name,
              Json.parse(fill(t.arguments.render)).getOrElse(t.arguments)
            )
          ),
          StopReason.ToolUse
        )
      case Some(rule) => ModelResponse(fill(rule.text))
      case None       => ModelResponse(NoAnswer)
    if response.text.contains(Fail) then
      Future.failed(ModelCallFailed(name, "the script fails this answer"))
    else Future.successful(response.copy(usage = estimate(request, response)))

  /** Word by word, as a live model's stream arrives. */
  override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
    val failing = Source
      .future(
        complete(request).recover { case _: ModelCallFailed => ModelResponse(Fail) }(using
          scala.concurrent.ExecutionContext.parasitic
        )
      )
    failing.flatMapConcat {
      case response if response.text == Fail =>
        // The words before the marker arrive, then the call fails: an answer cut short.
        val said = scriptedText(request)
          .getOrElse("")
          .split(java.util.regex.Pattern.quote(Fail), 2)
          .head
          .trim
        Source(
          said.split(" ").toVector.filter(_.nonEmpty).zipWithIndex.map { (w, i) =>
            ModelChunk.TextDelta(if i == 0 then w else s" $w")
          } :+ ModelChunk.Failed("the script fails this answer")
        )
      case response =>
        val words =
          if response.text.isEmpty then Vector.empty
          else
            response.text.split(" ").toVector.zipWithIndex.map { (w, i) =>
              ModelChunk.TextDelta(if i == 0 then w else s" $w")
            }
        Source(
          words ++ response.toolCalls.map(ModelChunk.ToolCallStarted(_)) :+ ModelChunk
            .Completed(response)
        )
    }

  private def scriptedText(request: ModelRequest): Option[String] =
    val said = lastUserText(request).getOrElse("").toLowerCase
    rules.find(r => said.contains(r.when.toLowerCase)).map(_.text)

object ScriptedModelProvider:
  val NoAnswer = "the script has no answer for that"

  /** In a rule's text, the point at which the answer fails. */
  val Fail = "{fail}"

  private val Subject  = raw"subject is the customer request (rq-[A-Za-z0-9]+)".r
  private val Requests = raw"\"id\":\"(rq-[A-Za-z0-9]+)\"".r

  /**
   * A rule's ids depend on the request a demonstration submitted, so a rule may write `{subject}`
   * for the conversation's request and `{requests}` for a reference to each request the turn's
   * tools returned.
   */
  private def placeholders(request: ModelRequest, sinceUser: Seq[ChatMessage]): String => String =
    val seen    = request.toString
    val subject = Subject.findFirstMatchIn(seen).map(_.group(1))
    val listed = sinceUser
      .collect { case ChatMessage.ToolResults(rs) => rs.map(_.content).mkString("\n") }
      .flatMap(t => Requests.findAllMatchIn(t).map(_.group(1)))
      .distinct
      .reverse
    text =>
      val withSubject = subject.fold(text)(id => text.replace("{subject}", id))
      withSubject.replace("{requests}", listed.map(id => s"[[request:$id]]").mkString(", "))

  final case class ScriptedTool(name: String, arguments: Json)
  final case class Rule(when: String, tools: Vector[ScriptedTool], text: String)

  def fromFile(path: Path): Either[String, ScriptedModelProvider] =
    if !Files.isRegularFile(path) then Left(s"no assistant script at $path")
    else parse(Files.readString(path)).left.map(problem => s"$path: $problem")

  def parse(text: String): Either[String, ScriptedModelProvider] =
    Json.parse(text).flatMap {
      case Json.Arr(items) =>
        items.zipWithIndex
          .foldLeft[Either[String, Vector[Rule]]](Right(Vector.empty)) { case (acc, (item, i)) =>
            acc.flatMap(rules => rule(item).left.map(p => s"rule ${i + 1}: $p").map(rules :+ _))
          }
          .map(ScriptedModelProvider(_))
      case _ => Left("the script is a JSON array of rules")
    }

  private def rule(item: Json): Either[String, Rule] =
    for
      when <- item("when").flatMap(_.asString).filter(_.nonEmpty).toRight("needs 'when'")
      text <- item("text").flatMap(_.asString).toRight("needs 'text'")
      tools <- item("tools") match
        case None | Some(Json.Null) => Right(Vector.empty)
        case Some(Json.Arr(ts)) =>
          ts.foldLeft[Either[String, Vector[ScriptedTool]]](Right(Vector.empty)) { (acc, t) =>
            for
              done <- acc
              name <- t("name").flatMap(_.asString).toRight("a tool needs 'name'")
            yield done :+ ScriptedTool(name, t("arguments").getOrElse(Json.obj()))
          }
        case Some(_) => Left("'tools' is an array")
    yield Rule(when, tools, text)

  private[replay] def lastUserText(request: ModelRequest): Option[String] =
    request.messages.reverseIterator
      .collectFirst { case u: ChatMessage.User => u }
      .map(_.content.collect { case MessageContent.Text(t) => t }.mkString("\n"))

  /** About four characters a token, as a live model's tokenizer roughly counts English. */
  private def estimate(request: ModelRequest, response: ModelResponse): TokenUsage =
    val in = request.systemMessage.fold(0)(_.length) + request.messages.map {
      case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t.length }.sum
      case ChatMessage.Assistant(text, cs) => text.length + cs.map(_.arguments.render.length).sum
      case ChatMessage.ToolResults(rs)     => rs.map(_.content.length).sum
    }.sum
    val out = response.text.length + response.toolCalls.map(_.arguments.render.length).sum
    TokenUsage(inputTokens = in / 4 + 1, outputTokens = out / 4 + 1)
