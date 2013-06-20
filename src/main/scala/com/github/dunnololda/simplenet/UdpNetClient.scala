package com.github.dunnololda.simplenet

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import java.net.{InetAddress, DatagramPacket, DatagramSocket}
import scala.concurrent._
import scala.concurrent.duration._
import akka.pattern.ask
import collection.mutable
import ExecutionContext.Implicits.global
import java.lang.String
import collection.mutable.ArrayBuffer

object UdpNetClient {
  def apply(address:String, port: Int, buffer_size:Int = 1024, ping_timeout: Long = 0, check_timeout:Long = 0) = new UdpNetClient(address, port, buffer_size, ping_timeout, check_timeout)
}

class UdpNetClient(val address:String, val port:Int, val buffer_size:Int = 1024, val ping_timeout:Long = 0, val check_timeout:Long = 0) {
  private val client_socket = new DatagramSocket()

  private val system = ActorSystem("udpclient-listener-" + (new java.text.SimpleDateFormat("yyyyMMddHHmmss")).format(new java.util.Date()))
  private val udp_client_listener = system.actorOf(Props(new UdpClientListener(client_socket, address, port, ping_timeout, check_timeout)))

  private def receive() {
    future {
      val receive_data = new Array[Byte](buffer_size)
      val receive_packet = new DatagramPacket(receive_data, receive_data.length)
      client_socket.receive(receive_packet)
      udp_client_listener ! NewUdpPacket(receive_packet)
      receive()
    }
  }
  receive()

  def newEvent(func: PartialFunction[UdpEvent, Any]) = {
    val event = Await.result(udp_client_listener.ask(RetrieveEvent)(timeout = (1 minute)), 1 minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event)
  }

  def newEventOrDefault[T](default: T)(func: PartialFunction[UdpEvent, T]):T = {
    val event = Await.result(udp_client_listener.ask(RetrieveEvent)(timeout = (1 minute)), 1 minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else default
  }

  def waitNewEvent[T](func: PartialFunction[UdpEvent, T]):T = {
    val event = Await.result(udp_client_listener.ask(WaitForEvent)(timeout = (1000 days)), 1000 days).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else waitNewEvent(func)
  }

  def isConnected:Boolean = {
    Await.result(udp_client_listener.ask(IsConnected)(timeout = (1 minute)), 1 minute).asInstanceOf[Boolean]
  }

  def waitConnection() {
    Await.result(udp_client_listener.ask(WaitConnection)(timeout = (1000 days)), 1000 days)
  }

  def send(message: State) {
    udp_client_listener ! Send(message)
  }

  def disconnect() {
    udp_client_listener ! Disconnect
  }
}

class UdpClientListener(client_socket:DatagramSocket, address:String, port:Int, ping_timeout:Long, check_timeout:Long) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val ip_address = InetAddress.getByName(address)

  private val udp_events = ArrayBuffer[UdpEvent]()
  private var event_waiter:Option[ActorRef] = None

  private var is_connected = false
  private var last_interaction_moment = 0l
  private var connection_waiter:Option[ActorRef] = None

  private def processUdpEvent(event:UdpEvent) {
    if (event_waiter.nonEmpty) {
      event_waiter.get ! event
      event_waiter = None
    } else udp_events += event
  }

  private def processConnectionWaiter() {
    if (connection_waiter.nonEmpty) {
      connection_waiter.get ! true
      connection_waiter = None
    }
  }

  override def preStart() {
    log.info("starting actor " + self.path.toString)
    import scala.concurrent.ExecutionContext.Implicits.global
    if (ping_timeout > 0) {
      context.system.scheduler.schedule(initialDelay = (ping_timeout milliseconds), interval = (ping_timeout milliseconds)) {
        self ! Ping
      }
    }
    if (check_timeout > 0) {
      context.system.scheduler.schedule(initialDelay = (check_timeout milliseconds), interval = (check_timeout milliseconds)) {
        self ! Check
      }
    }
  }

  private def _send(message:String) {
    val send_data = (message + "#").getBytes
    val send_packet = new DatagramPacket(send_data, send_data.length, ip_address, port)
    client_socket.send(send_packet)
  }

  def receive = {
    case NewUdpPacket(packet) =>
      val message = new String(packet.getData).takeWhile(c => c != '#')
      log.info(s"received message: $message")
      message match {
        case "SN PING" =>
          last_interaction_moment = System.currentTimeMillis()
          if (!is_connected) {
            processUdpEvent(UdpServerConnected)
            is_connected = true
            processConnectionWaiter()
          }
        case "SN BYE" =>
          is_connected = false
          processUdpEvent(UdpServerDisconnected)
        case _ =>
          val received_data = State.fromJsonStringOrDefault(message, State(("raw" -> message)))
          processUdpEvent(NewUdpServerMessage(received_data))
          last_interaction_moment = System.currentTimeMillis()
          if (!is_connected) {
            processUdpEvent(UdpServerConnected)
            is_connected = true
            processConnectionWaiter()
          }
      }
    case Send(message) =>
      _send(message.toJsonString)
    case Ping =>
      _send("SN PING")
    case Check =>
      if(System.currentTimeMillis() - last_interaction_moment > check_timeout) {
        is_connected = false
        processUdpEvent(UdpServerDisconnected)
      }
    case Disconnect =>
      _send("SN BYE")
      is_connected = false
      processUdpEvent(UdpServerDisconnected)
    case RetrieveEvent =>
      if (udp_events.isEmpty) sender ! NoNewUdpEvents
      else sender ! udp_events.remove(0)
    case WaitForEvent =>
      if (udp_events.nonEmpty) sender ! udp_events.remove(0)
      else event_waiter = Some(sender)
    case IsConnected =>
      sender ! is_connected
    case WaitConnection =>
      if (is_connected) sender ! true
      else connection_waiter = Some(sender)
  }
}
