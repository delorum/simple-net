package com.github.dunnololda.simplenet.tests

import java.net.{InetAddress, DatagramPacket, DatagramSocket}
import java.io.{BufferedReader, InputStreamReader}

object UDPServer extends App {
  val serverSocket = new DatagramSocket(9876)
  val receiveData = new Array[Byte](1024)
  while(true)
  {
    val receivePacket = new DatagramPacket(receiveData, receiveData.length)
    serverSocket.receive(receivePacket)
    val sentence = new String(receivePacket.getData).takeWhile(c => c != '#')
    System.out.println("RECEIVED: " + sentence)
    val IPAddress = receivePacket.getAddress
    val port = receivePacket.getPort
    val capitalizedSentence = sentence.toUpperCase+"#"
    val sendData = capitalizedSentence.getBytes
    val sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port)
    serverSocket.send(sendPacket)
  }
}

object UDPClient extends App {
  val inFromUser = new BufferedReader(new InputStreamReader(System.in))
  val clientSocket = new DatagramSocket()
  val IPAddress = InetAddress.getByName("localhost")
  val receiveData = new Array[Byte](1024)
  while(true)
  {
    val sentence = inFromUser.readLine()
    val sendData = (sentence+"#").getBytes
    val sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, 9876)
    clientSocket.send(sendPacket)
    val receivePacket = new DatagramPacket(receiveData, receiveData.length)
    clientSocket.receive(receivePacket)
    val modifiedSentence = new String(receivePacket.getData).takeWhile(c => c != '#')
    System.out.println("FROM SERVER:" + modifiedSentence)
  }
}
