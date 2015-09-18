package com.github.dunnololda.simplenet.tests

import com.github.dunnololda.simplenet._
import play.api.libs.json.Json

object EchoServer extends App {
  val server = TcpNetServer(port = 9000)

  while(true) {
    server.waitNewEvent {
      case NewTcpClientData(client_id, message) => server.sendToAll(message)
    }
  }
}

object ArithmeticServer extends App {
  val server = TcpNetServer(port = 9000)

  case class ArithmeticTask(a:Float, b:Float, op:String)
  implicit val ArithmeticTask_reader = Json.reads[ArithmeticTask]

  while(true) {
    server.waitNewEvent {
      case NewTcpClientData(client_id, message) =>
        message.validate[ArithmeticTask].asOpt.foreach {
          case ArithmeticTask(a, b, op) =>
            op match {
              case "+" => server.sendToClient(client_id, Json.obj("result" -> (a + b)))
              case "-" => server.sendToClient(client_id, Json.obj("result" -> (a - b)))
              case "*" => server.sendToClient(client_id, Json.obj("result" -> (a * b)))
              case "/" => server.sendToClient(client_id, Json.obj("result" -> (a / b)))  // no division by zero checking to keep example simple
              case _   => server.sendToClient(client_id, Json.obj("result" -> ("unknown op: " + op)))
            }
        }
    }
  }
}

object ArithmeticClient extends App {
  val client = TcpNetClient("localhost", 9000, 0)

  case class ServerAnswer(result:Float)
  private implicit val ServerAnswer_reader = Json.reads[ServerAnswer]

  while(true) {
    val (a, b) = ((math.random*100).toFloat, (math.random*100).toFloat)
    val (op, answer) = (math.random*4).toInt match {
      case 0 => ("+", a+b)
      case 1 => ("-", a-b)
      case 2 => ("*", a*b)
      case 3 => if (b != 0) ("/", a/b) else ("+", a+b)
      case _ => ("+", a+b)
    }
    client.send(Json.obj("a" -> a, "b" -> b, "op" -> op))
    client.waitNewEvent {
      case NewTcpServerData(message) =>
        message.validate[ServerAnswer].asOpt.foreach {
          case ServerAnswer(result) =>
            println("answer: "+answer+"; server answer: "+result)
        }
    }
    Thread.sleep(5000)
  }
}
