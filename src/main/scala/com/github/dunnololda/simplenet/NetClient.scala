package com.github.dunnololda.simplenet

import akka.actor.{Props, ActorSystem, ActorRef, Actor}
import java.net.Socket
import java.io.{InputStreamReader, BufferedReader, OutputStreamWriter, PrintWriter}
import scala.concurrent.duration._
import concurrent.Await
import akka.pattern.ask

object NetClient {
  def apply(address:String, port:Int, ping_timeout:Long, handler:ActorRef) = new NetClient(address, port, ping_timeout)
}

class NetClient(address:String, port:Int, ping_timeout:Long) {
  private val log = MySimpleLogger(this.getClass.getName)

  private val format = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
  private val moment = format.format(new java.util.Date())
  private val connection_listener = ActorSystem("netclient-listener-" + moment)
  private val network_events = collection.mutable.ArrayBuffer[NetworkEvent]()
  connection_listener.actorOf(Props(new Actor {
    private var is_connected      = false
    private var socket:Socket     = _
    private var out:PrintWriter   = _
    private var in:BufferedReader = _

    private def connect() {
      is_connected = false
      try {
        socket = new Socket(address, port)
        out    = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
        in     = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))
        is_connected = true
        log.info("connected to server "+address+" at port "+port)
        network_events += ServerConnected
      } catch {
        case e:Exception =>
          log.error("failed to connect to server "+address+" at port "+port+": "+e)
      }
    }

    override def preStart() {
      log.info("starting actor " + self.path.toString)
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.schedule(initialDelay = (0 seconds), interval = (100 milliseconds)) {
        self ! Check
      }
      if (ping_timeout > 0) {
        context.system.scheduler.schedule(initialDelay = (ping_timeout milliseconds), interval = (ping_timeout milliseconds)) {
          self ! Ping
        }
      }
    }

    override def postStop() {
      socket.close()
      is_connected = false
      network_events += ServerDisconnected
    }

    private var last_interaction_moment = 0l
    def receive = {
      case Check =>
        if (is_connected) {
          if (in.ready) {
            try {
              val message = in.readLine
              val received_data = State.fromJsonStringOrDefault(message, State(("raw" -> message)))
              if (!received_data.contains("ping")) {
                network_events += NewServerMessage(received_data)
              }
              last_interaction_moment = System.currentTimeMillis()
            } catch {
              case e: Exception =>
            }
          }
        } else connect()
      case Send(message) =>
        if (is_connected) {
          out.println(message.toJsonString)
          out.flush()
          if (out.checkError()) {
            is_connected = false
            network_events += ServerDisconnected
          }
        } else connect()
      case Ping =>
        if (System.currentTimeMillis() - last_interaction_moment > ping_timeout) {
          out.println(State("ping").toJsonString)
          out.flush()
          if (out.checkError()) {
            is_connected = false
            network_events += ServerDisconnected
          }
        }
      case Disconnect =>
        context.stop(self)
      case RetrieveEvent =>
        if (network_events.isEmpty) sender ! NoNewEvents
        else sender ! network_events.remove(0)
    }

    def newEvent: NetworkEvent = {
      Await.result(self.ask(RetrieveEvent)(timeout = (60000 milliseconds)), 60000 milliseconds).asInstanceOf[NetworkEvent]
    }

    def send(message: State) {
      self ! Send(message)
    }

    def disconnect() {
      self ! Disconnect
    }

    def stop() {
      connection_listener.shutdown()
    }
  }))
}
