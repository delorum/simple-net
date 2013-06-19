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

sealed class UdpEvent
case class NewUdpConnection(client_id:Long) extends UdpEvent
case class NewUdpPacket(packet:DatagramPacket) extends UdpEvent
case class NewUdpMessage(client_id:Long, data:State) extends UdpEvent
case class NewUdpServerMessage(data:State) extends UdpEvent
case class UdpClientDisconnected(client_id:Long) extends UdpEvent
case object UdpServerConnected extends UdpEvent
case object UdpServerDisconnected extends UdpEvent
case object NoNewUdpEvents extends UdpEvent

case class UdpClientLocation(address:InetAddress, port:Int)
case class UdpClient(id:Long, location:UdpClientLocation, var last_interaction_moment:Long)

object UdpNetServer {
  def apply(port: Int, buffer_size:Int = 1024, ping_timeout: Long = 0, check_timeout:Long = 0) = new UdpNetServer(port, buffer_size, ping_timeout, check_timeout)
}

class UdpNetServer(port:Int, val buffer_size:Int = 1024, val ping_timeout: Long = 0, val check_timeout: Long = 0) {
  private val log = MySimpleLogger(this.getClass.getName)

  private val listen_port = nextAvailablePort(log, port)
  def listenPort = listen_port

  private val system = ActorSystem("udpserver-listener-" + (new java.text.SimpleDateFormat("yyyyMMddHHmmss")).format(new java.util.Date()))
  private val server_socket = new DatagramSocket(listen_port)
  private val udp_server_listener = system.actorOf(Props(new UdpServerListener(server_socket, ping_timeout, check_timeout)))

  private def receive() {
    future {
      val receive_data = new Array[Byte](buffer_size)
      val receive_packet = new DatagramPacket(receive_data, receive_data.length)
      server_socket.receive(receive_packet)
      udp_server_listener ! NewUdpPacket(receive_packet)
      receive()
    }
  }
  receive()

  def sendToClient(client_id: Long, message: State) {
    udp_server_listener ! SendToClient(client_id, message)
  }

  def sendToAll(message: State) {
    udp_server_listener ! Send(message)
  }

  def disconnectClient(client_id:Long) {
    udp_server_listener ! DisconnectClient(client_id)
  }

  def disconnectAll() {
    udp_server_listener ! Disconnect
  }

  def newEvent(func: PartialFunction[UdpEvent, Any]) = {
    val event = Await.result(udp_server_listener.ask(RetrieveEvent)(timeout = (1 minute)), 1 minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event)
  }

  def newEventOrDefault[T](default: T)(func: PartialFunction[UdpEvent, T]):T = {
    val event = Await.result(udp_server_listener.ask(RetrieveEvent)(timeout = (1 minute)), 1 minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else default
  }

  def waitNewEvent[T](func: PartialFunction[UdpEvent, T]):T = {
    val event = Await.result(udp_server_listener.ask(WaitForEvent)(timeout = (1000 days)), 1000 days).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else waitNewEvent(func)
  }

  def clientIds:List[Long] = {
    Await.result(udp_server_listener.ask(ClientIds)(timeout = (1 minute)), 1 minute).asInstanceOf[List[Long]]
  }
}

class UdpServerListener(server_socket:DatagramSocket, ping_timeout:Long, check_timeout:Long) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val clients_by_id = mutable.HashMap[Long, UdpClient]()
  private val clients_by_location = mutable.HashMap[UdpClientLocation, UdpClient]()

  private val udp_events = ArrayBuffer[UdpEvent]()
  private var event_waiter:Option[ActorRef] = None

  private def processUdpEvent(event:UdpEvent) {
    if (event_waiter.nonEmpty) {
      event_waiter.get ! event
      event_waiter = None
    } else udp_events += event
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

  private def _send(message:String, location:UdpClientLocation) {
    val send_data = (message + "#").getBytes
    val send_packet = new DatagramPacket(send_data, send_data.length, location.address, location.port)
    server_socket.send(send_packet)
  }

  def receive = {
    case NewUdpPacket(packet) =>
      val location = UdpClientLocation(packet.getAddress, packet.getPort)
      val message = new String(packet.getData).takeWhile(c => c != '#')
      message match {
        case "SN PING" =>
          clients_by_location.get(location) match {
            case Some(client) =>
              client.last_interaction_moment = System.currentTimeMillis()
            case None =>
              val new_client_id = nextClientId
              val new_client = UdpClient(new_client_id, location, System.currentTimeMillis())
              clients_by_id.find {case (id, client) => client.location == location} match {
                case Some((id, client)) => clients_by_id -= id
                case None =>
              }
              clients_by_id(new_client_id) = new_client
              clients_by_location(location) = new_client
              processUdpEvent(NewUdpConnection(new_client_id))
              _send("SN PING", location)
          }
        case "SN BYE" =>
          clients_by_location.get(location) match {
            case Some(client) =>
              clients_by_location -= location
              clients_by_id -= client.id
              processUdpEvent(UdpClientDisconnected(client.id))
            case None =>
          }
        case _ =>
          val received_data = State.fromJsonStringOrDefault(message, State(("raw" -> message)))
          clients_by_location.get(location) match {
            case Some(client) =>
              processUdpEvent(NewUdpMessage(client.id, received_data))
              client.last_interaction_moment = System.currentTimeMillis()
            case None =>
          }
      }
    case SendToClient(client_id, message) =>
      clients_by_id.get(client_id) match {
        case Some(client) =>
          _send(message.toJsonString, client.location)
        case None =>
      }
    case Send(message) =>
      clients_by_id.values.foreach(client => {
        _send(message.toJsonString, client.location)
      })
    case Ping =>
      clients_by_id.values.foreach(client => {
        _send("SN PING", client.location)
      })
    case Check =>
      clients_by_id.foreach {
        case (_, client) => if(System.currentTimeMillis() - client.last_interaction_moment > check_timeout) {
          processUdpEvent(UdpClientDisconnected(client.id))
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
        case None =>
      }
    case Disconnect =>
      clients_by_id.values.foreach(client => {
        _send("SN BYE", client.location)
      })
    case RetrieveEvent =>
      if (udp_events.isEmpty) sender ! NoNewUdpEvents
      else sender ! udp_events.remove(0)
    case WaitForEvent =>
      if (udp_events.nonEmpty) sender ! udp_events.remove(0)
      else event_waiter = Some(sender)
    case ClientIds =>
      sender ! clients_by_id.keys.toList
  }
}
