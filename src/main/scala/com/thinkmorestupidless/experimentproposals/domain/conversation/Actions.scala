package com.thinkmorestupidless.experimentproposals.domain.conversation

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.SourceStatus
import com.thinkmorestupidless.experimentproposals.domain.evidence.Mutations

/**
 * The operator's acts the assistant may propose (FR-019); each is performed only when the person
 * confirms, through the same code as its API route. The messages are written for the model.
 */
enum Action(val wire: String):
  case SubmitRequest(text: String)                       extends Action("submit-request")
  case IngestSource(adapter: String, identifier: String) extends Action("ingest-source")
  case InjectFault(recordId: String, fault: String)      extends Action("inject-fault")
  case SetSourceStatus(sourceId: String, status: String, reason: String)
      extends Action("set-source-status")

  /** The arguments as the conversation records and the interface shows them. */
  def arguments: Map[String, String] = this match
    case SubmitRequest(text)               => Map("text" -> text)
    case IngestSource(adapter, identifier) => Map("adapter" -> adapter, "identifier" -> identifier)
    case InjectFault(recordId, fault)      => Map("recordId" -> recordId, "fault" -> fault)
    case SetSourceStatus(sourceId, status, reason) =>
      Map("sourceId" -> sourceId, "status" -> status, "reason" -> reason)

object Actions:
  val names: Vector[String] =
    Vector("submit-request", "ingest-source", "inject-fault", "set-source-status")

  /** The source statuses `set-source-status` accepts. */
  val statuses: Vector[String] = SourceStatus.values.toVector.map(_.toString)

  def validate(action: String, arguments: Map[String, String]): Either[String, Action] =
    def arg(name: String): Either[String, String] =
      arguments.get(name).map(_.trim).filter(_.nonEmpty).toRight(s"$action needs '$name'")
    def needs(names: String*): Either[String, Unit] =
      val missing = names.filterNot(n => arguments.get(n).exists(_.trim.nonEmpty))
      if missing.isEmpty then Right(())
      else Left(s"$action needs ${missing.map(n => s"'$n'").mkString(" and ")}")
    action match
      case "submit-request" =>
        // The text is kept exactly as typed: only its presence is checked.
        arguments.get("text").filter(_.trim.nonEmpty) match
          case Some(text) => Right(Action.SubmitRequest(text))
          case None =>
            Left("submit-request needs 'text', the request exactly as the person typed it")
      case "ingest-source" =>
        for
          _          <- needs("adapter", "identifier")
          adapter    <- arg("adapter")
          identifier <- arg("identifier")
        yield Action.IngestSource(adapter, identifier)
      case "inject-fault" =>
        for
          _        <- needs("recordId", "fault")
          recordId <- arg("recordId")
          fault    <- arg("fault")
          _ <-
            if Mutations.names.contains(fault) then Right(())
            else Left(s"unknown fault '$fault'; the faults are ${Mutations.names.mkString(", ")}")
        yield Action.InjectFault(recordId, fault)
      case "set-source-status" =>
        for
          _        <- needs("sourceId", "status")
          sourceId <- arg("sourceId")
          status   <- arg("status")
          _ <-
            if statuses.contains(status) then Right(())
            else Left(s"unknown status '$status'; the statuses are ${statuses.mkString(", ")}")
        yield Action.SetSourceStatus(sourceId, status, arguments.getOrElse("reason", "").trim)
      case other =>
        Left(s"unknown action '$other'; the actions are ${names.mkString(", ")}")
