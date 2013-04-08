package com.github.dunnololda.simplenet

import akka.actor.{ActorRef, Actor}
import java.net.Socket
import java.io.{InputStreamReader, BufferedReader, OutputStreamWriter, PrintWriter}
import scala.concurrent.duration._

case object ServerDisconnected
case class NewServerMessage(data:State)
case object ServerConnected

class NetClient(address:String, port:Int, ping_timeout:Long = 0,  handler:ActorRef) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

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
      handler ! ServerConnected
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
    handler ! ServerDisconnected
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
              handler ! NewServerMessage(received_data)
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
          handler ! ServerDisconnected
        }
      } else connect()
    case Ping =>
      if (System.currentTimeMillis() - last_interaction_moment > ping_timeout) {
        out.println(State("ping").toJsonString)
        out.flush()
        if (out.checkError()) {
          is_connected = false
          handler ! ServerDisconnected
        }
      }
    case Disconnect =>
      context.stop(self)
  }
}
