package com.github.dunnololda.simplenet.tests

import akka.actor.{ActorRef, Props, Actor, ActorSystem}
import com.github.dunnololda.simplenet._
import collection.mutable.ArrayBuffer
import com.github.dunnololda.simplenet.Send
import com.github.dunnololda.simplenet.NewMessage
import com.github.dunnololda.simplenet.NewClient
import collection.mutable
import com.github.dunnololda.simplenet

object AkkaIoTests extends App {
  val log = MySimpleLogger(this.getClass.getName)

  // echo server
  val ns = NetServer(port = 9000, ping_timeout = 60000, handler_system_name = "echo-server", actor = new Actor {
    private val clients = ArrayBuffer[ActorRef]()
    def receive = {
      case NewClient(id, client) =>
        clients += client
        client ! Send(State("message" -> " this is echo server"))
      case NewMessage(id, client, message) =>
        clients.foreach(client => client ! Send(message))
      case ClientDisconnected(id, client) => clients -= client
    }
  })

  // chat server
  NetServer(port = 9000, ping_timeout = 60000, handler_system_name = "chat-server", actor = new Actor {
    private val clients = mutable.HashMap[Long, ActorRef]()
    def receive = {
      case NewClient(id, client) =>
        AkkaIoTests.log.info("connected client "+id)
        clients += (id -> client)
        client ! Send(State("message" -> " this is chat server"))
      case NewMessage(id, client, message) =>
        for {
          (client_id, client) <- clients
          if id != client_id
        } client ! Send(State("message" -> (message.valueOrDefault("raw", "")), "id" -> id))
      case ClientDisconnected(id, client) =>
        AkkaIoTests.log.info("client "+id+" disconnected")
        clients -= id
    }
  })

  // arithmetic server
  NetServer(port = 9000, ping_timeout = 60000, handler_system_name = "arithmetic-server", actor = new Actor {
    private val clients = mutable.HashMap[Long, ActorRef]()
    def receive = {
      case NewClient(id, client) =>
        AkkaIoTests.log.info("connected client "+id)
        clients += (id -> client)
        client ! Send(State("message" -> " this is arithmetic server"))
      case NewMessage(id, client, message) =>
        (for {
          a <- message.value[Double]("a")
          b <- message.value[Double]("b")
          op <- message.value[String]("op")
        } yield (a, b, op)) match {
          case Some((a, b, op)) =>
            op match {
              case "+" => client ! Send(State("result" -> (a + b)))
              case "-" => client ! Send(State("result" -> (a - b)))
              case "*" => client ! Send(State("result" -> (a * b)))
              case "/" => client ! Send(State("result" -> (a / b)))
              case _   => client ! Send(State("result" -> ("unknown op: "+op)))
            }
          case None => client ! Send(State("result" -> "unknown data"))
        }
      case ClientDisconnected(id, client) =>
        AkkaIoTests.log.info("client "+id+" disconnected")
        clients -= id
    }
  })
}
