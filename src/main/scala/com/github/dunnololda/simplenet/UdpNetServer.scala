package com.github.dunnololda.simplenet

import java.net.{DatagramPacket, DatagramSocket}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import com.github.dunnololda.mysimplelogger.MySimpleLogger
import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object UdpNetServer {
  def apply(port: Int, buffer_size: Int = 1024, ping_timeout: Long = 1000, check_timeout: Long = 10000, delimiter: Char = '#') =
    new UdpNetServer(port, buffer_size, ping_timeout, check_timeout, delimiter)
}

class UdpNetServer(port: Int, val buffer_size: Int = 1024, val ping_timeout: Long = 1000, val check_timeout: Long = 10000, val delimiter: Char = '#') {
  private val log = MySimpleLogger(this.getClass.getName)

  private val listen_port = nextAvailablePort(log, port)

  def listenPort = listen_port

  private val system = ActorSystem("udpserver-listener-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()))
  private val server_socket = new DatagramSocket(listen_port)
  private val udp_server_listener = system.actorOf(Props(new UdpServerListener(server_socket, ping_timeout, check_timeout, delimiter)))
  private var current_buffer_size = buffer_size
  private var receive_data = new Array[Byte](buffer_size)
  private var receive_packet = new DatagramPacket(receive_data, receive_data.length)

  private def receive() {
    future {
      try {
        server_socket.receive(receive_packet)
        val location = UdpClientLocation(receive_packet.getAddress, receive_packet.getPort)
        if (!receive_packet.getData.contains(delimiter)) {
          log.warn("received message is larger than buffer! Increasing buffer by 1000 bytes...")
          current_buffer_size += 1000
          receive_data = new Array[Byte](current_buffer_size)
          receive_packet = new DatagramPacket(receive_data, receive_data.length)
        } else {
          val message = new String(receive_packet.getData).takeWhile(c => c != delimiter)
          udp_server_listener ! NewUdpClientPacket(location, message)
        }
        receive()
      } catch {
        case e: Exception =>
          log.error("error receiving data from server:", e) // likely we are just closed
      }
    }
  }

  receive()

  def sendToClient(client_id: Long, message: JsValue) {
    udp_server_listener ! SendToClient(client_id, message)
  }

  def sendToAll(message: JsValue) {
    udp_server_listener ! Send(message)
  }

  def disconnectClient(client_id: Long) {
    udp_server_listener ! DisconnectClient(client_id)
  }

  def disconnectAll() {
    Await.result(udp_server_listener.ask(Disconnect)(timeout = 100.days), 100.days)
  }

  def newEvent(func: PartialFunction[UdpEvent, Any]) = {
    val event = Await.result(udp_server_listener.ask(RetrieveEvent)(timeout = 1.minute), 1.minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event)
  }

  def newEventOrDefault[T](default: T)(func: PartialFunction[UdpEvent, T]): T = {
    val event = Await.result(udp_server_listener.ask(RetrieveEvent)(timeout = 1.minute), 1.minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else default
  }

  def waitNewEvent[T](func: PartialFunction[UdpEvent, T]): T = {
    val event = Await.result(udp_server_listener.ask(WaitForEvent)(timeout = 100.days), 100.days).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else waitNewEvent(func)
  }

  def clientIds: List[Long] = {
    Await.result(udp_server_listener.ask(ClientIds)(timeout = 1.minute), 1.minute).asInstanceOf[List[Long]]
  }

  def stop() {
    disconnectAll()
    system.shutdown()
    server_socket.close()
  }

  def ignoreEvents: Boolean = {
    Await.result(udp_server_listener.ask(IgnoreStatus)(timeout = 1.minute), 1.minute).asInstanceOf[Boolean]
  }

  def ignoreEvents_=(enabled: Boolean) {
    udp_server_listener ! IgnoreEvents(enabled)
  }
}

private class UdpServerListener(server_socket: DatagramSocket, ping_timeout: Long, check_timeout: Long, delimiter: Char) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val clients_by_id = mutable.HashMap[Long, UdpClient]()
  private val clients_by_location = mutable.HashMap[UdpClientLocation, UdpClient]()

  private val udp_events = ArrayBuffer[UdpEvent]()
  private var event_waiter: Option[ActorRef] = None

  private def processUdpEvent(event: UdpEvent) {
    if (event_waiter.nonEmpty) {
      event_waiter.get ! event
      event_waiter = None
    } else udp_events += event
  }

  override def preStart() {
    log.info("starting actor " + self.path.toString)
    import scala.concurrent.ExecutionContext.Implicits.global

    val checked_ping_timeout = if (ping_timeout > 0) ping_timeout else 1000
    context.system.scheduler.schedule(initialDelay = checked_ping_timeout.milliseconds, interval = checked_ping_timeout.milliseconds) {
      self ! Ping
    }

    val checked_check_timeout = if (check_timeout > checked_ping_timeout) check_timeout else checked_ping_timeout * 10
    context.system.scheduler.schedule(initialDelay = checked_check_timeout.milliseconds, interval = checked_check_timeout.milliseconds) {
      self ! Check
    }
  }

  private def _send(message: String, location: UdpClientLocation) {
    val send_data = new StringBuffer(message).append(delimiter).toString
    val send_packet = new DatagramPacket(send_data.getBytes, send_data.length, location.address, location.port)
    server_socket.send(send_packet)
  }

  private var ignore_mode = false

  def receive = {
    case NewUdpClientPacket(location, message) =>
      //log.info(s"received message: $message from ${location.address}:${location.port}")
      message match {
        case "SN PING" =>
          clients_by_location.get(location) match {
            case Some(client) =>
              client.last_interaction_moment = System.currentTimeMillis()
            case None =>
              val new_client_id = nextClientId
              val new_client = UdpClient(new_client_id, location, System.currentTimeMillis())
              clients_by_id.find { case (id, client) => client.location == location } match {
                case Some((id, client)) => clients_by_id -= id
                case None =>
              }
              clients_by_id(new_client_id) = new_client
              clients_by_location(location) = new_client
              processUdpEvent(NewUdpConnection(new_client_id))
              log.info(s"new client connected from $location")
              _send("SN PING", location)
          }
        case "SN BYE" =>
          clients_by_location.get(location) match {
            case Some(client) =>
              clients_by_location -= location
              clients_by_id -= client.id
              processUdpEvent(UdpClientDisconnected(client.id))
              log.info(s"client from $location disconnected")
            case None =>
          }
        case _ =>
          clients_by_location.get(location) match {
            case Some(client) =>
              if (!ignore_mode) {
                val received_data = Json.parse(message)
                processUdpEvent(NewUdpClientData(client.id, received_data))
              }
              client.last_interaction_moment = System.currentTimeMillis()
            case None =>
          }
      }
    case SendToClient(client_id, message) =>
      clients_by_id.get(client_id) match {
        case Some(client) =>
          _send(message.toString(), client.location)
        case None =>
      }
    case Send(message) =>
      clients_by_id.values.foreach(client => {
        _send(message.toString(), client.location)
      })
    case Ping =>
      clients_by_id.values.foreach(client => {
        _send("SN PING", client.location)
      })
    case Check =>
      clients_by_id.foreach {
        case (_, client) => if (System.currentTimeMillis() - client.last_interaction_moment > check_timeout) {
          _send("SN BYE", client.location)
          processUdpEvent(UdpClientDisconnected(client.id))
          log.info(s"client from ${client.location} disconnected")
        }
      }
      clients_by_id.retain {
        case (_, client) => System.currentTimeMillis() - client.last_interaction_moment <= check_timeout
      }
      clients_by_location.retain {
        case (_, client) => System.currentTimeMillis() - client.last_interaction_moment <= check_timeout
      }
    case DisconnectClient(client_id) =>
      clients_by_id.get(client_id) match {
        case Some(client) =>
          _send("SN BYE", client.location)
          clients_by_id -= client_id
          clients_by_location -= client.location
          processUdpEvent(UdpClientDisconnected(client.id))
          log.info(s"client from ${client.location} disconnected")
        case None =>
      }
    case Disconnect =>
      clients_by_id.values.foreach(client => {
        _send("SN BYE", client.location)
        processUdpEvent(UdpClientDisconnected(client.id))
        log.info(s"client from ${client.location} disconnected")
      })
      clients_by_id.clear()
      clients_by_location.clear()
      sender ! true
    case RetrieveEvent =>
      if (udp_events.isEmpty) sender ! NoNewUdpEvents
      else sender ! udp_events.remove(0)
    case WaitForEvent =>
      if (udp_events.nonEmpty) sender ! udp_events.remove(0)
      else event_waiter = Some(sender())
    case ClientIds =>
      sender ! clients_by_id.keys.toList
    case IgnoreEvents(enabled) =>
      ignore_mode = enabled
    case IgnoreStatus =>
      sender ! ignore_mode
  }
}
