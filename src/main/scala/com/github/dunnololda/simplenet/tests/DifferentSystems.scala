package com.github.dunnololda.simplenet.tests

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import scala.concurrent.duration._

case class Message(message:String)
case object ThankYou

object DifferentSystems extends App {
  val system1 = ActorSystem("system1")
  val actor1 = system1.actorOf(Props(new Actor1))

  val system2 = ActorSystem("system2")
  val actor2 = system2.actorOf(Props(new Actor2(actor1)))
}

class Actor1 extends Actor {
  def receive = {
    case Message(message) =>
      println("received: "+message)
      sender ! ThankYou
  }
}

class Actor2(other:ActorRef) extends Actor {
  import scala.concurrent.ExecutionContext.Implicits.global
  context.system.scheduler.schedule(initialDelay = 0.seconds, interval = 10.seconds) {
    other ! Message("hello!")
  }

  def receive = {
    case ThankYou =>
      println("received thankyou")
  }
}
