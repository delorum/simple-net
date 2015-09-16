package com.github.dunnololda.simplenet.tests

import com.github.dunnololda.simplenet.{NewMessage, _}
import play.api.libs.json.Json

object NextNetworkTests extends App {
  val server = TcpNetServer(9000, 60000)
  val client = TcpNetClient("localhost", server.listenPort, 60000)

  case class ClientQuestion(a: Float, b: Float, op: String)

  implicit val ClientQuestion_reader = Json.reads[ClientQuestion]

  case class ServerAnswer(result: Option[Float], error: Option[String])

  implicit val ServerAnswer_reader = Json.reads[ServerAnswer]

  val errors = (1 to 10).foldLeft(0) { case (res, i) =>
    val i1 = (math.random * 100).toInt
    val i2 = (math.random * 100).toInt
    val (str_op, answer) = (math.random * 4).toInt match {
      case 0 => ("+", 1f * i1 + i2)
      case 1 => ("-", 1f * i1 - i2)
      case 2 => ("*", 1f * i1 * i2)
      case 3 => if (i2 != 0) ("/", 1f * i1 / i2) else ("+", 1f * i1 + i2)
      case _ => ("+", 1f * i1 + i2)
    }
    val question = Json.obj("a" -> i1, "b" -> i2, "op" -> str_op)
    println(s"[client] send question $question")
    client.send(question)
    server.waitNewEvent {
      case NewMessage(client_id, client_question) =>
        println(s"[server] new question: $client_question")
        client_question.validate[ClientQuestion].asOpt match {
          case Some(ClientQuestion(a, b, op)) =>
            op match {
              case "+" => server.sendToClient(client_id, Json.obj("result" -> (a + b)))
              case "-" => server.sendToClient(client_id, Json.obj("result" -> (a - b)))
              case "*" => server.sendToClient(client_id, Json.obj("result" -> (a * b)))
              case "/" => server.sendToClient(client_id, Json.obj("result" -> (a / b)))
              case _ => server.sendToClient(client_id, Json.obj("error" -> ("unknown op: " + op)))
            }
          case None =>
            server.sendToClient(client_id, Json.obj("error" -> "unknown data"))
        }
    }
    client.waitNewEvent {
      case NewServerMessage(server_answer) =>
        println(s"[client] new answer: $server_answer")
        server_answer.validate[ServerAnswer].asOpt match {
          case Some(ServerAnswer(result, error)) =>
            if (result.exists(r => r == answer)) res
            else res + 1
          case None => res + 1
        }
    }
  }
  println(errors)
}
