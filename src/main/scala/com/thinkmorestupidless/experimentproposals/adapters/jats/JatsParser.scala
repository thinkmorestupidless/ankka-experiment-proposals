package com.thinkmorestupidless.experimentproposals.adapters.jats

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.{ParseIssue, Section}

import com.thinkmorestupidless.experimentproposals.adapters.SafeXml

import scala.xml.{Node, NodeSeq}

/** What a parse produced: sections with stable text, issues, and how complete it was. */
final case class ParsedDocument(
    sections: Vector[Section],
    issues: Vector[ParseIssue],
    completeness: ParseCompleteness,
    title: Option[String],
    abstractText: Option[String]
)

/**
 * JATS XML into sections with canonical text and stable offsets (research.md R8). Tables are their
 * own sections, kept as raw text and flagged when their rows could not be read; figure captions are
 * sections too. Methods and results sections are related by heading order and by `xref`.
 */
object JatsParser:
  val Version = "jats@v1"

  def parse(xml: String): ParsedDocument =
    scala.util.Try(SafeXml.load(xml)) match
      case scala.util.Failure(e) =>
        // Corrupt: the content is retained as fetched and the version is quarantined, not dropped.
        ParsedDocument(
          Vector.empty,
          Vector(ParseIssue("corrupt", "content", s"the XML could not be parsed: ${e.getMessage}")),
          ParseCompleteness.failed,
          None,
          None
        )
      case scala.util.Success(root) => parseRoot(root)

  private def parseRoot(root: Node): ParsedDocument =
    val front        = root \ "front"
    val title        = (front \\ "article-title").headOption.map(text)
    val abstractText = (front \\ "abstract").headOption.map(a => paragraphs(a)).filter(_.nonEmpty)
    val builder      = new Builder
    abstractText.foreach(a => builder.add(None, SectionKind.abstract_, "Abstract", a))
    val body = root \ "body"
    (body \ "sec").foreach(sec => builder.section(None, sec))
    // Paragraphs directly under the body, outside any section.
    val loose = paragraphs(body, direct = true)
    if loose.nonEmpty then builder.add(None, SectionKind.body, "Body", loose)
    val sections = builder.related
    val noText   = sections.nonEmpty && sections.forall(_.text.isBlank)
    val completeness =
      if noText then ParseCompleteness.failed
      else if body.isEmpty || sections.forall(_.kind == SectionKind.abstract_) then
        if abstractText.isDefined then ParseCompleteness.partial else ParseCompleteness.failed
      else if builder.issues.nonEmpty then ParseCompleteness.partial
      else ParseCompleteness.complete
    val issues =
      if noText then
        builder.issues :+ ParseIssue(
          "no_text",
          "body",
          "the document has structure but no readable text: materially ambiguous"
        )
      else if body.isEmpty then
        builder.issues :+ ParseIssue("no_body", "body", "the article has no body")
      else builder.issues
    ParsedDocument(sections, issues, completeness, title, abstractText)

  /** Plain text becomes one body section. */
  def plainText(text: String): ParsedDocument =
    val trimmed = text.trim
    if trimmed.isEmpty then
      ParsedDocument(
        Vector.empty,
        Vector(ParseIssue("empty", "text", "no text")),
        ParseCompleteness.failed,
        None,
        None
      )
    else
      ParsedDocument(
        Vector(Section("sec-1", None, 1, SectionKind.body, "Body", trimmed)),
        Vector.empty,
        ParseCompleteness.complete,
        None,
        None
      )

  private def text(n: Node): String = n.text.replaceAll("\\s+", " ").trim

  private def paragraphs(n: NodeSeq, direct: Boolean = false): String =
    val ps = if direct then n \ "p" else n \\ "p"
    ps.map(text).filter(_.nonEmpty).mkString("\n\n")

  private def kindOf(sec: Node, title: String): SectionKind =
    val secType = (sec \ "@sec-type").text.toLowerCase
    val t       = title.toLowerCase
    if secType.contains("method") || t.contains("method") || t.contains("materials") then
      SectionKind.methods
    else if secType.contains("result") || t.contains("result") then SectionKind.results
    else if secType.contains("discussion") || t.contains("discussion") || t.contains("conclusion")
    then SectionKind.discussion
    else if secType.contains("intro") || t.contains("introduction") || t.contains("background") then
      SectionKind.introduction
    else if secType.contains("supplement") || t.contains("supplement") then
      SectionKind.supplementary
    else SectionKind.other

  private final class Builder:
    private var counter            = 0
    private val out                = Vector.newBuilder[Section]
    private val issuesOut          = Vector.newBuilder[ParseIssue]
    def issues: Vector[ParseIssue] = issuesOut.result()

    private def nextId(): String = { counter += 1; s"sec-$counter" }

    def add(
        parent: Option[String],
        kind: SectionKind,
        title: String,
        text: String,
        confidence: ParseConfidence = ParseConfidence.high,
        tableResolved: Boolean = true
    ): Section =
      val s = Section(
        nextId(),
        parent,
        counter,
        kind,
        title,
        text,
        Vector.empty,
        confidence,
        tableResolved
      )
      out += s
      s

    def section(parent: Option[String], sec: Node): Unit =
      val title =
        (sec \ "title").headOption.map(text).filter(_.nonEmpty).getOrElse("Untitled section")
      val own  = paragraphs(sec, direct = true)
      val kind = kindOf(sec, title)
      val s    = add(parent, kind, title, own)
      (sec \ "table-wrap").foreach(tw => table(Some(s.id), tw))
      (sec \ "fig").foreach(fig => caption(Some(s.id), fig))
      (sec \ "sec").foreach(child => section(Some(s.id), child))

    private def table(parent: Option[String], tw: Node): Unit =
      val label = (tw \ "label").headOption.map(text).getOrElse("Table")
      val cap   = (tw \ "caption").headOption.map(text).getOrElse("")
      val rows = (tw \\ "tr").map(tr =>
        (tr \\ "_").filter(n => n.label == "td" || n.label == "th").map(text).mkString(" | ")
      )
      val resolved = rows.nonEmpty
      val body     = if resolved then rows.mkString("\n") else text(tw)
      val s = add(
        parent,
        SectionKind.table,
        s"$label: $cap".trim.stripSuffix(":"),
        body,
        if resolved then ParseConfidence.high else ParseConfidence.low,
        resolved
      )
      if !resolved then
        issuesOut += ParseIssue("table_unresolved", s.id, s"$label could not be read into rows")

    private def caption(parent: Option[String], fig: Node): Unit =
      val label = (fig \ "label").headOption.map(text).getOrElse("Figure")
      val cap   = (fig \ "caption").headOption.map(text).getOrElse("")
      if cap.nonEmpty then add(parent, SectionKind.figure_caption, label, cap): Unit

    /**
     * Methods and results relate to each other; an `xref` to a table relates the table's section.
     */
    def related: Vector[Section] =
      val all     = out.result()
      val methods = all.filter(_.kind == SectionKind.methods).map(_.id)
      val results = all.filter(_.kind == SectionKind.results).map(_.id)
      all.map { s =>
        val byKind =
          if s.kind == SectionKind.methods then results
          else if s.kind == SectionKind.results then methods
          else Vector.empty
        val tables =
          if s.kind == SectionKind.results then
            all.filter(t => t.parentId.contains(s.id) && t.kind == SectionKind.table).map(_.id)
          else Vector.empty
        s.copy(relatedSectionIds = (byKind ++ tables).distinct)
      }
