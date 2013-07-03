package com.github.dunnololda.simplenet

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import java.net.{InetAddress, DatagramPacket, DatagramSocket}
import scala.concurrent._
import scala.concurrent.duration._
import akka.pattern.ask
import ExecutionContext.Implicits.global
import java.lang.String
import collection.mutable.ArrayBuffer

object UdpNetClient {
  def apply(address:String, port: Int, buffer_size:Int = 1024, ping_timeout: Long = 1000, check_timeout:Long = 10000, delimiter:Char = '#') =
    new UdpNetClient(address, port, buffer_size, ping_timeout, check_timeout, delimiter)
}

class UdpNetClient(val address:String, val port:Int, val buffer_size:Int = 1024, val ping_timeout:Long = 0, val check_timeout:Long = 0, val delimiter:Char = '#') {
  private val log = MySimpleLogger(this.getClass.getName)
  private val client_socket = new DatagramSocket()

  private val system = ActorSystem("udpclient-listener-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()))
  private val udp_client_listener = system.actorOf(Props(new UdpClientListener(client_socket, address, port, ping_timeout, check_timeout, delimiter)))
  private var current_buffer_size = buffer_size
  private var receive_data = new Array[Byte](buffer_size)
  private var receive_packet = new DatagramPacket(receive_data, receive_data.length)

  private def receive() {
    future {
      try {
        client_socket.receive(receive_packet)
        if (!receive_packet.getData.contains(delimiter)) {
          log.warn("received message is larger than buffer! Increasing buffer by 1000 bytes...")
          current_buffer_size += 1000
          receive_data = new Array[Byte](current_buffer_size)
          receive_packet = new DatagramPacket(receive_data, receive_data.length)
        } else {
          val message = new String(receive_packet.getData.takeWhile(c => c != delimiter))
          udp_client_listener ! NewUdpServerPacket(message)
        }
        receive()
      } catch {
        case e:Exception =>
          log.error(s"error receiving data from server: ${e.getLocalizedMessage}")  // likely we are just closed
      }
    }
  }
  receive()

  def newEvent(func: PartialFunction[UdpEvent, Any]) = {
    val event = Await.result(udp_client_listener.ask(RetrieveEvent)(timeout = 1.minute), 1 minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event)
  }

  def newEventOrDefault[T](default: T)(func: PartialFunction[UdpEvent, T]):T = {
    val event = Await.result(udp_client_listener.ask(RetrieveEvent)(timeout = 1.minute), 1 minute).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else default
  }

  def waitNewEvent[T](func: PartialFunction[UdpEvent, T]):T = {
    val event = Await.result(udp_client_listener.ask(WaitForEvent)(timeout = 1000.days), 1000 days).asInstanceOf[UdpEvent]
    if (func.isDefinedAt(event)) func(event) else waitNewEvent(func)
  }

  def isConnected:Boolean = {
    Await.result(udp_client_listener.ask(IsConnected)(timeout = 1.minute), 1 minute).asInstanceOf[Boolean]
  }

  def waitConnection() {
    Await.result(udp_client_listener.ask(WaitConnection)(timeout = 1000.days), 1000 days)
  }

  def send(message: State) {
    udp_client_listener ! Send(message)
  }

  def disconnect() {
    Await.result(udp_client_listener.ask(Disconnect)(timeout = 1000.days), 1000 days)
  }

  def stop() {
    disconnect()
    system.shutdown()
    client_socket.close()
  }

  def ignoreEvents:Boolean = {
    Await.result(udp_client_listener.ask(IgnoreStatus)(timeout = 1.minute), 1 minute).asInstanceOf[Boolean]
  }

  def ignoreEvents_=(enabled:Boolean) {
    udp_client_listener ! IgnoreEvents(enabled)
  }
}

class UdpClientListener(client_socket:DatagramSocket, address:String, port:Int, ping_timeout:Long, check_timeout:Long, delimiter:Char) extends Actor {
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
      context.system.scheduler.schedule(initialDelay = ping_timeout.milliseconds, interval = ping_timeout.milliseconds) {
        self ! Ping
      }
    }
    if (check_timeout > 0) {
      context.system.scheduler.schedule(initialDelay = check_timeout.milliseconds, interval = check_timeout.milliseconds) {
        self ! Check
      }
    }
  }

  /*private val char_freqs = mutable.HashMap[Char, Int]()
  private def recountFreqs(str:String) {
    str.foreach(c => {
      char_freqs(c) = char_freqs.getOrElse(c, 0) + 1
    })
  }
  private def dumpCodeTableToFile(filename:String) {
    val code_tree = Huffman.createCodeTree(char_freqs.toMap)
    val code_table = Huffman.convert(code_tree)
    val fos = new java.io.FileOutputStream(filename)
    for {
      (char, bits) <- code_table
    } {
      fos.write(s"$char : ${bits.mkString(" ")}\n".getBytes)
    }
    fos.close()
  }*/

  private def _send(message:String) {
    val send_data = new StringBuffer(message).append(delimiter).toString
    //recountFreqs(send_data)
    val send_packet = new DatagramPacket(send_data.getBytes, send_data.length, ip_address, port)
    client_socket.send(send_packet)
  }

  private var ignore_mode = false

  def receive = {
    case NewUdpServerPacket(message) =>
      //log.info(s"received message: $message")
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
          if (!ignore_mode) {
            val received_data = State.fromJsonStringOrDefault(message, State("raw" -> message))
            processUdpEvent(NewUdpServerData(received_data))
          }
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
      //dumpCodeTableToFile("codetable-client.sn")
      sender ! true
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
    case IgnoreEvents(enabled) =>
      ignore_mode = enabled
    case IgnoreStatus =>
      sender ! ignore_mode
  }
}
