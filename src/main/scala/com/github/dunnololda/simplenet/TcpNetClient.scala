package com.github.dunnololda.simplenet

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.Socket

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import com.github.dunnololda.mysimplelogger.MySimpleLogger
import play.api.libs.json.{JsBoolean, JsObject, JsValue, Json}

import scala.concurrent.Await
import scala.concurrent.duration._

object TcpNetClient {
  def apply(address: String, port: Int, ping_timeout: Long = 0) = new TcpNetClient(address, port, ping_timeout)
}

class TcpNetClient(val address: String, val port: Int, val ping_timeout: Long = 0) {
  private val log = MySimpleLogger(this.getClass.getName)

  private val connection_listener = ActorSystem("netclient-listener-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()))
  private val connection_handler = connection_listener.actorOf(Props(new Actor {
    private var is_connected = false
    private var socket: Socket = _
    private var out: PrintWriter = _
    private var in: BufferedReader = _

    private val network_events = collection.mutable.ArrayBuffer[NetworkEvent]()
    private var event_waiter: Option[ActorRef] = None

    private def processNetworkEvent(event: NetworkEvent) {
      if (event_waiter.nonEmpty) {
        event_waiter.get ! event
        event_waiter = None
      } else network_events += event
    }

    private var connection_waiter: Option[ActorRef] = None

    private def connect() {
      is_connected = false
      try {
        socket = new Socket(address, port)
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
        in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
        is_connected = true
        log.info("connected to server " + address + " at port " + port)
        processNetworkEvent(ServerConnected)
        if (connection_waiter.nonEmpty) {
          connection_waiter.get ! true
          connection_waiter = None
        }
      } catch {
        case e: Exception =>
          log.error(s"failed to connect to server $address at port $port:", e)
      }
    }

    override def preStart() {
      log.info("starting actor " + self.path.toString)
      connect()
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.schedule(initialDelay = 0.seconds, interval = 100.milliseconds) {
        self ! Check
      }
      if (ping_timeout > 0) {
        context.system.scheduler.schedule(initialDelay = ping_timeout.milliseconds, interval = ping_timeout.milliseconds) {
          self ! Ping
        }
      }
    }

    override def postStop() {
      socket.close()
      is_connected = false
      processNetworkEvent(ServerDisconnected)
    }

    private var last_interaction_moment = 0l

    def receive = {
      case Check =>
        if (is_connected) {
          if (in.ready) {
            try {
              val message = in.readLine
              val received_data = Json.parse(message)
              if (!received_data.asOpt[JsObject].exists(_.keys.contains("ping"))) {
                processNetworkEvent(NewServerMessage(received_data))
              }
              last_interaction_moment = System.currentTimeMillis()
            } catch {
              case e: Exception =>
            }
          }
        } else connect()
      case Send(message) =>
        if (is_connected) {
          out.println(message.toString())
          out.flush()
          if (out.checkError()) {
            is_connected = false
            processNetworkEvent(ServerDisconnected)
          }
        } else connect()
      case Ping =>
        if (System.currentTimeMillis() - last_interaction_moment > ping_timeout) {
          out.println(JsObject(Seq("ping" -> JsBoolean(value = true))).toString())
          out.flush()
          if (out.checkError()) {
            is_connected = false
            processNetworkEvent(ServerDisconnected)
          }
        }
      case Disconnect =>
        sender ! true
        context.stop(self)
      case RetrieveEvent =>
        if (network_events.isEmpty) sender ! NoNewEvents
        else sender ! network_events.remove(0)
      case WaitForEvent =>
        if (network_events.nonEmpty) sender ! network_events.remove(0)
        else event_waiter = Some(sender())
      case IsConnected =>
        sender ! is_connected
      case WaitConnection =>
        if (is_connected) sender ! true
        else connection_waiter = Some(sender())
    }
  }))

  def newEvent(func: PartialFunction[NetworkEvent, Any]) = {
    val event = Await.result(connection_handler.ask(RetrieveEvent)(timeout = 1.minute), 1.minute).asInstanceOf[NetworkEvent]
    if (func.isDefinedAt(event)) func(event)
  }

  def fromNewEventOrDefault[T](default: T)(func: PartialFunction[NetworkEvent, T]): T = {
    val event = Await.result(connection_handler.ask(RetrieveEvent)(timeout = 1.minute), 1.minute).asInstanceOf[NetworkEvent]
    if (func.isDefinedAt(event)) func(event) else default
  }

  def waitNewEvent[T](func: PartialFunction[NetworkEvent, T]): T = {
    val event = Await.result(connection_handler.ask(WaitForEvent)(timeout = 100.days), 100.days).asInstanceOf[NetworkEvent]
    if (func.isDefinedAt(event)) func(event) else waitNewEvent(func)
  }

  def isConnected: Boolean = {
    Await.result(connection_handler.ask(IsConnected)(timeout = 1.minute), 1.minute).asInstanceOf[Boolean]
  }

  def waitConnection() {
    Await.result(connection_handler.ask(WaitConnection)(timeout = 100.days), 100.days)
  }

  def send(message: JsValue) {
    connection_handler ! Send(message)
  }

  def disconnect() {
    Await.result(connection_handler.ask(Disconnect)(timeout = 100.days), 100.days)
  }

  def stop() {
    connection_listener.shutdown()
  }
}
