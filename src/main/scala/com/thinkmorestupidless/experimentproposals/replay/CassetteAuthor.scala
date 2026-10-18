package com.thinkmorestupidless.experimentproposals.replay

import com.thinkmorestupidless.experimentproposals.adapters.jats.JatsParser
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan

import java.nio.file.{Files, Path}

/**
 * Writes an authored cassette: `cassetteAuthor <stage@vN> <case marker> <reply file> [author]`.
 * When the case marker names a fixture (`src:file:<name>...`), every `"quotation": "…"` in the
 * reply is checked against the parsed fixture's sections first, whitespace-tolerant; a quotation
 * that is in no section stops the write and names it. Cassettes authored this way say so.
 */
@main def cassetteAuthor(args: String*): Unit =
  args.toList match
    case promptVersion :: caseMarker :: replyFile :: rest =>
      val reply    = Files.readString(Path.of(replyFile))
      val author   = rest.headOption.getOrElse(sys.env.getOrElse("USER", "unknown"))
      val fixtures = Path.of(sys.env.getOrElse("EDP_FIXTURES_DIR", "evals/fixtures/papers"))
      val checked = CassetteAuthor.checkQuotations(caseMarker, reply, fixtures) match
        case Left(problem) =>
          System.err.println(problem)
          sys.exit(1)
        case Right(n) => n
      val store = Cassettes(Path.of(sys.env.getOrElse("EDP_CASSETTES", "evals/cassettes")))
      val file = store.write(
        Cassette(
          promptVersion,
          caseMarker,
          reply,
          recorded = false,
          Some(author),
          checkedQuotations = checked > 0
        )
      )
      println(s"wrote $file ($checked quotation(s) checked)")
    case _ =>
      System.err.println("usage: cassetteAuthor <stage@vN> <case marker> <reply file> [author]")
      sys.exit(2)

object CassetteAuthor:
  private val Quotation = """"quotation"\s*:\s*"((?:[^"\\]|\\.)*)"""".r
  private val Fixture   = """^src:file:([A-Za-z0-9._-]+?)(?::v\d+)?(?::.*)?$""".r

  /**
   * The number of quotations checked, or why one failed. A case that names no fixture checks none.
   */
  def checkQuotations(caseMarker: String, reply: String, fixtures: Path): Either[String, Int] =
    val quotations = Quotation.findAllMatchIn(reply).map(m => unescape(m.group(1))).toVector
    caseMarker match
      case Fixture(name) if quotations.nonEmpty =>
        val xml = Seq(s"$name.xml", s"$name.txt")
          .map(fixtures.resolve)
          .find(Files.exists(_))
          .toRight(s"no fixture named $name under $fixtures")
        xml.flatMap { file =>
          val text = Files.readString(file)
          val sections =
            if file.toString.endsWith(".xml") then JatsParser.parse(text).sections
            else JatsParser.plainText(text).sections
          val texts = sections.map(s => SourceSpan.normalise(s.text))
          quotations.find(q => !texts.exists(_.contains(SourceSpan.normalise(q)))) match
            case Some(missing) => Left(s"the quotation is in no section of $name: '$missing'")
            case None          => Right(quotations.size)
        }
      case _ => Right(0)

  private def unescape(s: String): String =
    s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
