package bmps.core.api.protocol

import io.circe._
import io.circe.generic.semiauto._
import io.circe.generic.auto._
import io.circe.syntax._
import bmps.core.Event
import bmps.core.models.SystemState

sealed trait ClientCommand
object ClientCommand {
  case class StartPhase(phase: String, options: Option[Map[String, String]] = None) extends ClientCommand
  case object Status extends ClientCommand

  implicit val startPhaseDecoder: Decoder[StartPhase] = Decoder.instance { c =>
    for {
      phase <- c.downField("phase").as[String]
      optJson <- c.downField("options").as[Option[Json]]
      optMap = optJson.flatMap { j =>
        j.asObject.map { obj =>
          obj.toMap.map { case (k, v) => (k, v.noSpaces) }
        }.orElse {
          // If options is present but not an object, represent it as a single key "value"
          Some(Map("value" -> j.noSpaces))
        }
      }
    } yield StartPhase(phase, optMap)
  }

  implicit val startPhaseEncoder: Encoder[StartPhase] = Encoder.instance { sp =>
    val base = Json.obj("phase" -> Json.fromString(sp.phase))
    sp.options match {
  case Some(m) => base.deepMerge(Json.obj("options" -> Json.obj(m.toSeq.map { case (k,v) => (k, Json.fromString(v)) }: _*)))
      case None => base
    }
  }

  implicit val commandDecoder: Decoder[ClientCommand] = Decoder.instance { c =>
    c.downField("command").as[String].flatMap {
      case "startPhase" => c.as[StartPhase]
      case "status" => Right(Status)
      case other => Left(DecodingFailure(s"Unknown command $other", c.history))
    }
  }

  implicit val commandEncoder: Encoder[ClientCommand] = Encoder.instance {
    case sp: StartPhase => sp.asJson deepMerge Json.obj("command" -> Json.fromString("startPhase"))
    case Status => Json.obj("command" -> Json.fromString("status"))
  }
}

sealed trait ServerMessage
object ServerMessage {
  case class PhaseEvent(phase: String, event: Event) extends ServerMessage
  case class Lifecycle(phase: String, status: String) extends ServerMessage
  case class Error(message: String) extends ServerMessage

  implicit val eventEncoder: Encoder[Event] = deriveEncoder
  implicit val phaseEventEncoder: Encoder[PhaseEvent] = deriveEncoder
  implicit val lifecycleEncoder: Encoder[Lifecycle] = deriveEncoder
  implicit val errorEncoder: Encoder[Error] = deriveEncoder

  implicit val serverEncoder: Encoder[ServerMessage] = Encoder.instance {
    case e: PhaseEvent => e.asJson.deepMerge(Json.obj("type" -> Json.fromString("event")))
    case l: Lifecycle => l.asJson.deepMerge(Json.obj("type" -> Json.fromString("lifecycle")))
    case err: Error => err.asJson.deepMerge(Json.obj("type" -> Json.fromString("error")))
  }
}

