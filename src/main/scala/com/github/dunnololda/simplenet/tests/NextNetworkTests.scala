package com.github.dunnololda.simplenet.tests

import com.github.dunnololda.simplenet._
import play.api.libs.json._

object NextNetworkTests extends App {
  val server = UdpNetServer(port = 9000)
  val client = UdpNetClient("localhost", server.listenPort)

  client.waitConnection()

  object  ArithmeticOperation extends Enumeration {
    type ArithmeticOperation = Value
    val Plus, Minus, Multiply, Divide, Unknown = Value
  }
  implicit val ArithmeticOperation_reader = EnumUtils.enumReads(ArithmeticOperation)

  case class ClientQuestion(a: Float, b: Float, op: ArithmeticOperation.Value)

  implicit val ClientQuestion_reader = Json.reads[ClientQuestion]

  case class ServerAnswer(result: Option[Float], error: Option[String])

  implicit val ServerAnswer_reader = Json.reads[ServerAnswer]

  val errors = (1 to 10).foldLeft(0) { case (res, i) =>
    val i1 = (math.random * 100).toInt
    val i2 = (math.random * 100).toInt
    val (str_op, answer) = (math.random * 4).toInt match {
      case 0 => (ArithmeticOperation.Plus, 1f * i1 + i2)
      case 1 => (ArithmeticOperation.Minus, 1f * i1 - i2)
      case 2 => (ArithmeticOperation.Multiply, 1f * i1 * i2)
      case 3 => if (i2 != 0) (ArithmeticOperation.Divide, 1f * i1 / i2) else (ArithmeticOperation.Plus, 1f * i1 + i2)
      case _ => (ArithmeticOperation.Plus, 1f * i1 + i2)
    }
    val question = Json.obj("a" -> i1, "b" -> i2, "op" -> Json.toJson(str_op)(EnumUtils.enumWrites))
    println(s"[client] send question $question")
    client.send(question)
    server.waitNewEvent {
      case NewUdpClientData(client_id, client_question) =>
        println(s"[server] new question: $client_question")
        println(client_question \ "op")
        val x = client_question.validate[ClientQuestion]
        println(x)
        client_question.validate[ClientQuestion].asOpt match {
          case Some(ClientQuestion(a, b, op)) =>
            op match {
              case ArithmeticOperation.Plus     => server.sendToClient(client_id, Json.obj("result" -> (a + b)))
              case ArithmeticOperation.Minus    => server.sendToClient(client_id, Json.obj("result" -> (a - b)))
              case ArithmeticOperation.Multiply => server.sendToClient(client_id, Json.obj("result" -> (a * b)))
              case ArithmeticOperation.Divide   => server.sendToClient(client_id, Json.obj("result" -> (a / b)))
              case _ => server.sendToClient(client_id, Json.obj("error" -> ("unknown op: " + op)))
            }
          case None =>
            server.sendToClient(client_id, Json.obj("error" -> "unknown data"))
        }
    }
    client.waitNewEvent {
      case NewUdpServerData(server_answer) =>
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

  client.stop()
  server.stop()
}
