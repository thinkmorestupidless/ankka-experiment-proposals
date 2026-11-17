package com.thinkmorestupidless.experimentproposals.domain.conversation

/**
 * The marker grammar an answer cites records with (contracts/assistant.md, research.md R9):
 * `[[kind:id]]`, `[[kind:id@version]]`, and one `[[prefill:decision:target|field=value|…]]`. A
 * marker inside a backtick code span is text, not a marker; an unknown kind is not a marker.
 */
object ReferenceMarkers:
  private val Marker = raw"\[\[([a-z-]+):([^\]\s|@]+)(?:@(\d+))?\]\]".r
  private val Fill   = raw"\[\[prefill:([a-z-]+):([^\]\s|]+)((?:\|[^\]|]*)*)\]\]".r
  private val Code   = raw"`[^`]*`".r

  /** Code spans blanked to spaces, so offsets stay where they were. */
  private def withoutCode(text: String): String =
    Code.replaceAllIn(text, m => " " * m.matched.length)

  /** The references in order of first appearance, each once, and the first prefill if any. */
  def parse(text: String): (Vector[Reference], Option[Prefill]) =
    val plain = withoutCode(text)
    val references = Marker
      .findAllMatchIn(plain)
      .flatMap { m =>
        CardKind
          .fromWire(m.group(1))
          .map(k => Reference(k.wire, m.group(2), Option(m.group(3)).map(_.toInt)))
      }
      .toVector
      .distinctBy(r => (r.kind, r.id, r.version))
    val prefill = Fill.findFirstMatchIn(plain).flatMap { m =>
      DecisionKind.fromWire(m.group(1)).map { d =>
        val fields = m
          .group(3)
          .split('|')
          .toVector
          .filter(_.nonEmpty)
          .flatMap { pair =>
            pair.split("=", 2) match
              case Array(k, v) if k.nonEmpty => Some(k.trim -> v.trim)
              case Array(k) if k.nonEmpty    => Some(k.trim -> "")
              case _                         => None
          }
          .toMap
        Prefill(d.wire, m.group(2), fields)
      }
    }
    (references, prefill)
