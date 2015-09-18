package com.github.dunnololda.simplenet

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.github.dunnololda.mysimplelogger.MySimpleLogger
import play.api.libs.json.{JsBoolean, JsObject, JsValue, Json}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object TcpNetServer {
  def apply(port: Int,
            ping_timeout: Long = 0,
            check_new_data_msek:Long = 100,
            connection_listener:ActorSystem = ActorSystem("netserver-listener-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()))) = {
    new TcpNetServer(port, ping_timeout, check_new_data_msek, connection_listener)
  }
}

class TcpNetServer(val port: Int,
                   val ping_timeout: Long = 0,
                   val check_new_data_msek:Long = 100,
                   connection_listener:ActorSystem = ActorSystem("netserver-listener-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()))) {
  private val log = MySimpleLogger(this.getClass.getName)

  private val listen_port = nextAvailablePort(log, port)

  def listenPort = listen_port

  //private val connection_listener = ActorSystem("netserver-listener-" + new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date()))
  // is it necessary to have unique names for actor systems and/or actors? what are the issues if they are not?
  private val all_clients_handler = connection_listener.actorOf(Props(new AllClientsHandler))

  private val server_socket = new ServerSocket(listen_port)

  future {
    try {
      while (true) {
        val socket = server_socket.accept()
        val new_client_id = nextClientId
        val new_client = connection_listener.actorOf(Props(new ClientHandler(new_client_id, socket, ping_timeout, check_new_data_msek, all_clients_handler)))
        all_clients_handler ! NewConnection(new_client_id, new_client)
      }
    } catch {
      case e: Exception =>
        if(isStopped) {
          log.info("tcp server socket closed")
        } else {
          log.error("error receiving data from server:", e) // likely we are just closed
        }
    }
  }

  def newEvent(func: PartialFunction[TcpEvent, Any]) = {
    val event = Await.result(all_clients_handler.ask(RetrieveEvent)(timeout = 1.minute), 1.minute).asInstanceOf[TcpEvent]
    if (func.isDefinedAt(event)) func(event)
  }

  def fromNewEventOrDefault[T](default: T)(func: PartialFunction[TcpEvent, T]): T = {
    val event = Await.result(all_clients_handler.ask(RetrieveEvent)(timeout = 1.minute), 1.minute).asInstanceOf[TcpEvent]
    if (func.isDefinedAt(event)) func(event) else default
  }

  def waitNewEvent[T](func: PartialFunction[TcpEvent, T]): T = {
    val event = Await.result(all_clients_handler.ask(WaitForEvent)(timeout = 100.days), 100.days).asInstanceOf[TcpEvent]
    if (func.isDefinedAt(event)) func(event) else waitNewEvent(func)
  }

  def sendToClient(client_id: Long, message: JsValue) {
    all_clients_handler ! SendToClient(client_id, message)
  }

  def sendToAll(message: JsValue) {
    all_clients_handler ! Send(message)
  }

  def disconnectClient(client_id: Long) {
    all_clients_handler ! DisconnectClient(client_id)
  }

  def disconnectAll() {
    Await.result(all_clients_handler.ask(Disconnect)(timeout = 100.days), 100.days)
  }

  private var _stop = false

  def isStopped:Boolean = synchronized {
    _stop
  }

  def stop() {
    synchronized {
      _stop = true
    }
    disconnectAll()
    server_socket.close()
    connection_listener.shutdown()
  }

  def clientIds: List[Long] = {
    Await.result(all_clients_handler.ask(ClientIds)(timeout = 1.minute), 1.minute).asInstanceOf[List[Long]]
  }

  def addExternalHandler(external_handler:ActorRef): Unit = {
    all_clients_handler ! AddExternalHandler(external_handler)
  }

  def removeExternalHandler(external_handler:ActorRef): Unit = {
    all_clients_handler ! RemoveExternalHandler(external_handler)
  }

  def addExternalHandler(external_handler:ActorSelection): Unit = {
    external_handler.resolveOne()(Timeout(5.seconds)).onComplete {
      case Success(actor_ref) =>
        all_clients_handler ! AddExternalHandler(actor_ref)
      case Failure(error) =>
        log.warn(s"addExternalHandler failed for actor_selection $external_handler", error)
    }
  }

  def removeExternalHandler(external_handler:ActorSelection): Unit = {
    external_handler.resolveOne()(Timeout(5.seconds)).onComplete {
      case Success(actor_ref) =>
        all_clients_handler ! RemoveExternalHandler(actor_ref)
      case Failure(error) =>
        log.warn(s"removeExternalHandler failed for actor_selection $external_handler", error)
    }
  }
}

class ClientHandler(id: Long, socket: Socket, ping_timeout: Long, check_new_data_msek:Long, all_clients_handler: ActorRef) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
  private val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))

  override def preStart() {
    log.info("starting actor " + self.path.toString)
    import scala.concurrent.ExecutionContext.Implicits.global
    context.system.scheduler.schedule(initialDelay = 0.seconds, interval = check_new_data_msek.milliseconds) {
      self ! TcpCheckNewData
    }
    if (ping_timeout > 0) {
      context.system.scheduler.schedule(initialDelay = ping_timeout.milliseconds, interval = ping_timeout.milliseconds) {
        self ! Ping
      }
    }
  }

  override def postStop() {
    socket.close()
    all_clients_handler ! TcpClientDisconnected(id)
  }

  private var last_interaction_moment = 0l

  def receive = {
    case TcpCheckNewData =>
      if (in.ready) {
        try {
          val message = in.readLine
          val received_data = Json.parse(message)
          if (!received_data.asOpt[JsObject].exists(x => x.keys.size == 1 && x.keys.contains("ping"))) {
            all_clients_handler ! NewTcpClientData(id, received_data)
          }
          last_interaction_moment = System.currentTimeMillis()
        } catch {
          case e: Exception =>
            log.error(s"error while doing Check for client $id:", e)
        }
      }
    case Send(message) =>
      out.println(message.toString())
      out.flush()
      if (out.checkError()) {
        self ! Disconnect
      } else {
        last_interaction_moment = System.currentTimeMillis()
      }
    case Ping =>
      if (System.currentTimeMillis() - last_interaction_moment > ping_timeout) {
        out.println(JsObject(Seq("ping" -> JsBoolean(value = true))).toString())
        out.flush()
        if (out.checkError()) {
          self ! Disconnect
        } else {
          last_interaction_moment = System.currentTimeMillis()
        }
      }
    case Disconnect =>
      sender ! true
      context.stop(self)
    case x =>
      log.warn(s"ClientHandler $id unknown event $x")
  }
}

class AllClientsHandler extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val network_events = collection.mutable.ArrayBuffer[TcpEvent]()
  private val clients = mutable.HashMap[Long, ActorRef]()
  private var event_waiter: Option[ActorRef] = None
  private val external_handlers = mutable.ArrayBuffer[ActorRef]()

  private def processNetworkEvent(event: TcpEvent) {
    if (external_handlers.nonEmpty) {
      external_handlers.foreach(h => h ! event)
      if (event_waiter.nonEmpty) {
        event_waiter.get ! event
        event_waiter = None
      }
    } else {
      if (event_waiter.nonEmpty) {
        event_waiter.get ! event
        event_waiter = None
      } else network_events += event
    }
  }

  def receive = {
    case event@NewConnection(client_id, client_actor) =>
      clients += (client_id -> client_actor)
      processNetworkEvent(NewTcpConnection(client_id))
    case event@TcpClientDisconnected(client_id) =>
      clients -= client_id
      processNetworkEvent(event)
    case event: TcpEvent =>
      processNetworkEvent(event)
    case RetrieveEvent =>
      if (network_events.isEmpty) sender ! NoNewTcpEvents
      else sender ! network_events.remove(0)
    case WaitForEvent =>
      if (network_events.nonEmpty) sender ! network_events.remove(0)
      else event_waiter = Some(sender())
    case ClientIds =>
      sender ! clients.keys.toList
    case SendToClient(client_id, message) =>
      clients.get(client_id).foreach(client => client ! Send(message))
    case Send(message) =>
      clients.values.foreach(client => client ! Send(message))
    case DisconnectClient(client_id) =>
      clients.get(client_id).foreach(client => client ! Disconnect)
    case Disconnect =>
      clients.values.foreach(client => Await.result(client.ask(Disconnect)(timeout = 100.days), 100.days))
      sender ! true
    case AddExternalHandler(external_handler) =>
      external_handlers += external_handler
      if (network_events.nonEmpty) {
        network_events.foreach(event => external_handler ! event)
        network_events.clear()
      }
    case RemoveExternalHandler(external_handler) =>
      external_handlers -= external_handler
    case x =>
      log.warn(s"AllClientsHandler unknown event $x")
  }
}
