package com.github.dunnololda

import java.net.{DatagramSocket, ServerSocket}

package object simplenet {
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
