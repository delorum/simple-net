package com.github.dunnololda

import java.net.{InetAddress, DatagramSocket, ServerSocket}
import akka.actor.ActorRef

package object simplenet {
  case object Listen
  case object Check
  case object Ping
  case class Send(message: State)
  case object Disconnect
  case object ClientIds
  case object IsConnected
  case object WaitConnection
  case object RetrieveEvent
  case object WaitForEvent
  case class SendToClient(client_id: Long, message: State)
  case class DisconnectClient(client_id: Long)

  sealed abstract class NetworkEvent
  case class NewConnection(client_id: Long, client: ActorRef)
  case class NewClient(client_id: Long) extends NetworkEvent
  case class NewMessage(client_id: Long, message: State) extends NetworkEvent
  case class ClientDisconnected(client_id: Long) extends NetworkEvent
  case object ServerConnected extends NetworkEvent
  case object ServerDisconnected extends NetworkEvent
  case class NewServerMessage(data:State) extends NetworkEvent
  case object NoNewEvents extends NetworkEvent

  sealed abstract class UdpEvent
  case class NewUdpConnection(client_id:Long) extends UdpEvent
  case class NewUdpClientPacket(location:UdpClientLocation, message:String) extends UdpEvent
  case class NewUdpServerPacket(message:String) extends UdpEvent
  case class NewUdpClientData(client_id:Long, data:State) extends UdpEvent
  case class NewUdpServerData(data:State) extends UdpEvent
  case class UdpClientDisconnected(client_id:Long) extends UdpEvent
  case object UdpServerConnected extends UdpEvent
  case object UdpServerDisconnected extends UdpEvent
  case object NoNewUdpEvents extends UdpEvent
  case class IgnoreEvents(enabled:Boolean) extends UdpEvent
  case object IgnoreStatus

  case class UdpClientLocation(address:InetAddress, port:Int)
  case class UdpClient(id:Long, location:UdpClientLocation, var last_interaction_moment:Long)

  def nextAvailablePort(log:MySimpleLogger.MySimpleLogger, port: Int): Int = {
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
