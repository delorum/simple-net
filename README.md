simple-net
==========

Scala wrapper on java sockets implementing very simple text/json network protocol

Usage
=====

Maven
-----

Add to your `<repositories>` section:

      <repository>
          <id>dunnololda's maven repo</id>
          <url>https://raw.github.com/dunnololda/mvn-repo/master</url>
      </repository>
      
Add to your `<dependencies>` section:

      <dependency>
          <groupId>com.github.dunnololda</groupId>
          <artifactId>simple-net</artifactId>
          <version>1.0</version>
      </dependency>
      
Currently only Scala 2.10.1 supported.

SBT
---

Add to resolvers:

    resolvers += "dunnololda's repo" at "https://raw.github.com/dunnololda/mvn-repo/master"
    
Add to dependencies:

    libraryDependencies ++= Seq(
      // ...
      "com.github.dunnololda" % "cli" % "1.0",
      // ...
    )
    
Code
----

Server
------

Lets start with this simple echo server:

    import com.github.dunnololda.simplenet._
    
    object EchoServer extends App {
      val server = NetServer(port = 9000)
      
      while(true) {
        server.waitNewEvent match {
          case NewMessage(client_id, message) =>
            server.sendToAll(message)
        }
      }
    }
    
`message` is of type `State`. See 
https://github.com/dunnololda/simple-net/blob/master/src/main/scala/com/github/dunnololda/simplenet/State.scala 
to learn more about this class. 

There is JSONParser class to parse strings in json format to `State`. When sending 
messages, framework converts them to string json representation. When receiving, it convert them back
from json string to State. 

To test EchoServer from code example above, you can start telnet from console and try to print 
something to it:


    
`waitNewEvent` blocks until event is received. There are these server event types:
    
    case class NewClient(client_id: Long) extends NetworkEvent
    case class NewMessage(client_id: Long, message: State) extends NetworkEvent
    case class ClientDisconnected(client_id: Long) extends NetworkEvent
    
`newEvent` returns result immediately. If no messages received from clients, `NoNewEvents` will be returned.

Use `sendToClient(client_id: Long, message: State)` and `sendToAll(message: State)` to send data to clients.
    
