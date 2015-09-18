package com.github.dunnololda

import java.net.{DatagramSocket, InetAddress, ServerSocket}

import akka.actor.ActorRef
import com.github.dunnololda.mysimplelogger.MySimpleLogger

//import com.github.dunnololda.state.State

import play.api.libs.json.JsValue

package object simplenet {

  case object Listen

  case object TcpCheckNewData

  case object UdpCheckIfOffline

  case object Ping

  case class Send(message: JsValue)

  case object Disconnect

  case object ClientIds

  case object IsConnected

  case object WaitConnection

  case object RetrieveEvent

  case object WaitForEvent

  case class SendToClient(client_id: Long, message: JsValue)

  case class DisconnectClient(client_id: Long)

  case class IgnoreEvents(enabled: Boolean)

  case object IgnoreStatus

  case class NewConnection(client_id: Long, client: ActorRef)

  case class AddExternalHandler(external_handler:ActorRef)

  case class RemoveExternalHandler(external_handler:ActorRef)

  sealed abstract class TcpEvent

  case class NewTcpConnection(client_id: Long) extends TcpEvent

  case class NewTcpClientData(client_id: Long, data: JsValue) extends TcpEvent

  case class TcpClientDisconnected(client_id: Long) extends TcpEvent

  case object TcpServerConnected extends TcpEvent

  case object TcpServerDisconnected extends TcpEvent

  case class NewTcpServerData(data: JsValue) extends TcpEvent

  case object NoNewTcpEvents extends TcpEvent

  case class UdpClientLocation(address: InetAddress, port: Int)

  case class UdpClient(id: Long, location: UdpClientLocation, var last_interaction_moment: Long)

  sealed abstract class UdpEvent

  case class NewUdpConnection(client_id: Long) extends UdpEvent

  case class NewUdpClientPacket(location: UdpClientLocation, message: String) extends UdpEvent

  case class NewUdpServerPacket(message: String) extends UdpEvent

  case class NewUdpClientData(client_id: Long, data: JsValue) extends UdpEvent

  case class NewUdpServerData(data: JsValue) extends UdpEvent

  case class UdpClientDisconnected(client_id: Long) extends UdpEvent

  case object UdpServerConnected extends UdpEvent

  case object UdpServerDisconnected extends UdpEvent

  case object NoNewUdpEvents extends UdpEvent

  def nextAvailablePort(log: MySimpleLogger.MySimpleLogger, port: Int): Int = {
    def available(port: Int): Boolean = {
      // TODO: return Option[Int]: None if no available port found within some range
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
      nextAvailablePort(log, port + 1)
    }
  }

  private var _client_id: Long = 0

  def nextClientId: Long = {
    _client_id += 1
    _client_id
  }
}
