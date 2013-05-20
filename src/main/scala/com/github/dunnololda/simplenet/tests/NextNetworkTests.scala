package com.github.dunnololda.simplenet.tests

import com.github.dunnololda.simplenet._
import com.github.dunnololda.simplenet.NewMessage
import scala.Some

object NextNetworkTests extends App {
  val server = NetServer(9000, 60000)
  val client = NetClient("localhost", server.listenPort, 60000)

  val errors = (1 to 10).foldLeft(0) { case (res, i) =>
    val i1 = (math.random*100).toInt
    val i2 = (math.random*100).toInt
    val (str_op, answer) = (math.random*4).toInt match {
      case 0 => ("+", 1f*i1+i2)
      case 1 => ("-", 1f*i1-i2)
      case 2 => ("*", 1f*i1*i2)
      case 3 => if(i2 != 0) ("/", 1f*i1/i2) else ("+", 1f*i1+i2)
      case _ => ("+", 1f*i1+i2)
    }
    val question = State("a" -> i1, "b" -> i2, "op" -> str_op)
    client.send(question)
    server.waitNewEvent match {
      case NewMessage(client_id, client_question) =>
        (for {
          a <- client_question.value[Float]("a")
          b <- client_question.value[Float]("b")
          op <- client_question.value[String]("op")
        } yield (a, b, op)) match {
          case Some((a, b, op)) =>
            op match {
              case "+" => server.sendToClient(client_id, State("result" -> (a + b)))
              case "-" => server.sendToClient(client_id, State("result" -> (a - b)))
              case "*" => server.sendToClient(client_id, State("result" -> (a * b)))
              case "/" => server.sendToClient(client_id, State("result" -> (a / b)))
              case _   => server.sendToClient(client_id, State("result" -> ("unknown op: " + op)))
            }
          case None => server.sendToClient(client_id, State("result" -> "unknown data"))
        }
      case _ =>
    }
    client.waitNewEvent match {
      case NewServerMessage(server_answer) =>
        server_answer.value[Float]("result") match {
          case Some(result) => if(result != answer) res+1 else res
          case None => res
        }
      case _ => res
    }
  }
  println(errors)
}
