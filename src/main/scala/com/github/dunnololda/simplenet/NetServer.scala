package com.github.dunnololda.simplenet

import java.net.{DatagramSocket, ServerSocket, Socket}
import akka.actor.{Props, ActorSystem, Actor, ActorRef}
import java.io.{InputStreamReader, BufferedReader, OutputStreamWriter, PrintWriter}
import scala.concurrent.duration._

case object Listen

case object Check

case object Ping

case class Send(message: State)

case class NewClient(client_id: Long, client: ActorRef)

case class NewMessage(client_id: Long, client: ActorRef, message: State)

case object Disconnect

case class ClientDisconnected(client_id: Long, client: ActorRef)

case object StopServer

case class ServerHandler(server_handler:ActorRef)

object NetServer {
  def apply(port:Int, ping_timeout:Long, handler:ActorRef) = new NetServer(port, ping_timeout, handler)
  /*def apply(port:Int = 9800, ping_timeout:Long = 0, handler_system:ActorSystem = ActorSystem("default-handler-system"), actor:Actor) = new NetServer(port, ping_timeout, handler_system, actor)*/
  def apply(port:Int, ping_timeout:Long, handler_system_name:String, actor: => Actor) = new NetServer(port, ping_timeout, handler_system_name, actor)
}

class NetServer(port: Int, ping_timeout:Long, handler: ActorRef) {
  /*def this(port: Int = 9800, ping_timeout:Long = 0, handler_system: ActorSystem = ActorSystem("default-handler-system"), actor:Actor) {
    this(port, ping_timeout, handler_system.actorOf(Props(actor)))
  }*/

  def this(port: Int, ping_timeout:Long, handler_system_name: String = "default-handler-system", actor: => Actor) {
    this(port, ping_timeout, ActorSystem(handler_system_name).actorOf(Props(actor)))
  }

  private val log = MySimpleLogger(this.getClass.getName)

  def nextAvailablePort(port: Int): Int = {
    def available(port: Int): Boolean = { // TODO: return Option[Int]: None if no available port found within some range
      var ss: ServerSocket = null
      var ds: DatagramSocket = null
      try {
        ss = new ServerSocket(port)
        ss.setReuseAddress(true)
        ds = new DatagramSocket(port)
        ds.setReuseAddress(true)
        return true
      } catch {
        case e: Exception => return false
      } finally {
        if (ds != null) ds.close()
        if (ss != null) ss.close()
      }
      false
    }

    log.info("trying port " + port + "...")
    if (available(port)) {
      log.info("the port is available!")
      port
    } else {
      log.info("the port is busy")
      nextAvailablePort(port + 1)
    }
  }

  private val format = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
  private val moment = format.format(new java.util.Date())

  private val connection_listener = ActorSystem("connection-listener-" + moment)  // is it necessary to have unique names for actor systems and/or actors? what are the issues if they are not?
  connection_listener.actorOf(Props(new Actor {
    override def preStart() {
      log.info("starting actor " + self.path.toString)
      self ! Listen
    }

    private val listen_port = nextAvailablePort(port)
    private val server_socket = new ServerSocket(listen_port)

    private var client_id: Long = 0

    private def nextClientId: Long = {
      client_id += 1
      client_id
    }

    def receive = {
      case Listen =>
        val socket = server_socket.accept()
        val new_client_id = nextClientId
        val new_client = context.actorOf(Props(new ConnectionHandler(new_client_id, socket, ping_timeout, handler)))
        handler ! NewClient(new_client_id, new_client)
        self ! Listen
    }
  }))

  def stop() {
    connection_listener.shutdown()
  }
}

class ConnectionHandler(id: Long, socket: Socket, ping_timeout:Long = 0, handler: ActorRef) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
  private val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))

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
    handler ! ClientDisconnected(id, self)
  }

  private var last_interaction_moment = 0l
  def receive = {
    case Check =>
      if (in.ready) {
        try {
          val message = in.readLine
          val received_data = State.fromJsonStringOrDefault(message, State(("raw" -> message)))
          if (!received_data.contains("ping")) {
            handler ! NewMessage(id, self, received_data)
          }
          last_interaction_moment = System.currentTimeMillis()
        } catch {
          case e: Exception =>
        }
      }
    case Send(message) =>
      out.println(message.toJsonString)
      out.flush()
      if (out.checkError()) {
        self ! Disconnect
      } else {
        last_interaction_moment = System.currentTimeMillis()
      }
    case Ping =>
      if (System.currentTimeMillis() - last_interaction_moment > ping_timeout) {
        out.println(State("ping").toJsonString)
        out.flush()
        if (out.checkError()) {
          self ! Disconnect
        }
      }
    case Disconnect =>
      context.stop(self)
  }
}
