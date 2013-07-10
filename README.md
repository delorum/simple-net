simple-net
==========

Scala wrapper on java sockets implements a very simple text/json network protocol. 

Simple-net is built upon Akka Framework. 

On the server-side: one future is created to accept new connections and one additional actor to
be created for every new connection to handle events from it. Also one actor is created to handle general network
events (new connections, new data from clients, client disconnections) and presents them to user.

On the client-side: one actor is created to handle all network stuff.

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
          <version>1.1</version>
      </dependency>
      
Currently only Scala 2.10.1 is supported.

SBT
---

Add to resolvers:

    resolvers += "dunnololda's repo" at "https://raw.github.com/dunnololda/mvn-repo/master"
    
Add to dependencies:

    libraryDependencies ++= Seq(
      // ...
      "com.github.dunnololda" % "simple-net" % "1.1",
      // ...
    )
    
Code: Server
------------

Let's start with this simple echo server:

    import com.github.dunnololda.simplenet._
    
    object EchoServer extends App {
      val server = NetServer(port = 9000)
      
      while(true) {
        server.waitNewEvent {
          case NewMessage(client_id, message) =>
            server.sendToAll(message)
        }
      }
    }
    
`message` is of type `State`. See below to learn more about this class or check the source code:
https://github.com/dunnololda/simple-net/blob/master/src/main/scala/com/github/dunnololda/simplenet/State.scala 

If the port we are trying to bind on is busy, server tries to bind on any port higher then that until it found a
free one.

There is [JSONParser](https://github.com/dunnololda/simple-net/blob/master/src/main/scala/com/github/dunnololda/simplenet/JSONParser.scala) 
class to parse strings in json format to `State`. While sending 
messages, framework converts them to string json representation. When receiving, it converts them back
from json string to State. 

To test EchoServer from the code example above, you can start telnet from console and try to print 
something to it:

    $ telnet localhost 9000
    Trying ::1...
    Connected to localhost.
    Escape character is '^]'.
    test
    {"raw":"test"}
    {"message":"pewpew"}
    {"message":"pewpew"}
    {"a":1, "b":2, "c":"test"}
    {"b":2.0, "a":1.0, "c":"test"}

The server sends back every received message (as it states for "echo"). If the message is not a valid json, then 
server puts it to the "raw" field of State object. 

Server is multiclient. It listens, accepts and then handles any incoming connections. There is no limit on 
connections amount. There is `clientIds:List[Long]` method to return ids of clients that are currently 
connected to server.
    
`waitNewEvent[T](func:PartialFunction[NetworkEvent, T]):T` blocks until event compatible with provided 
PartialFunction is received. There are these server event types:
    
    case class NewClient(client_id: Long) extends NetworkEvent
    case class NewMessage(client_id: Long, message: State) extends NetworkEvent
    case class ClientDisconnected(client_id: Long) extends NetworkEvent
    
`newEvent(func:PartialFunction[NetworkEvent, Any]):Any` returns result immediately. If no messages was received 
from clients, `NoNewEvents` object is returned and passed to the PartialFunction provided.

`fromNewEventOrDefault[T](default:T)(func:PartialFunction[NetwortkEvent, T]):T` is used to immediately calculate
and return some result.

Use `sendToClient(client_id: Long, message: State)` and `sendToAll(message: State)` to send data to clients.

Important note: currently all `send*` methods are asynchronous and there is no explict verification 
that the message was actually received by the other side. This is up to you!
    
Here is another example: arithmetic server. It gets two numbers, a and b and operation to perform with them and 
returns the result of the operation:

    import com.github.dunnololda.simplenet._
    
    object ArithmeticServer extends App {
      val server = NetServer(port = 9000)
      
      while(true) {
        server.waitNewEvent {
          case NewMessage(client_id, client_question) =>
            (for {
              a <- client_question.value[Float]("a")
              b <- client_question.value[Float]("b")
              op <- client_question.value[String]("op")
            } yield (a, b, op)) match {
              case Some((a, b, op)) =>
                op match {
                  case "+" => server.sendToClient(client_id, State("result" -> (a + b)))
                  case "-" => server.sendToClient(client_id, State("result" -> (a - b)))
                  case "*" => server.sendToClient(client_id, State("result" -> (a * b)))
                  case "/" => server.sendToClient(client_id, State("result" -> (a / b)))  // no division by zero 
                  checking to keep example simple
                  case _   => server.sendToClient(client_id, State("result" -> ("unknown op: " + op)))
                }
              case None => server.sendToClient(client_id, State("result" -> "unknown data"))
            }
        }
      }
    }

Test our server:

    $ telnet localhost 9000
    Trying ::1...
    Connected to localhost.
    Escape character is '^]'.
    {"a":5, "b":6, "op":"*"}
    {"result":30.0}
    {"a":120, "b":20, "op":"/"}
    {"result":6.0}

Another way to parse State objects is like this:

    object ArithmeticServer extends App {
      val server = NetServer(port = 9000)
      
      while(true) {
        server.waitNewEvent {
          case NewMessage(client_id, State(("a", a:Float), ("b", b:Float), ("op", op:String))) =>
            op match {
              case "+" => server.sendToClient(client_id, State("result" -> (a + b)))
              case "-" => server.sendToClient(client_id, State("result" -> (a - b)))
              case "*" => server.sendToClient(client_id, State("result" -> (a * b)))
              case "/" => server.sendToClient(client_id, State("result" -> (a / b)))  // no division by zero checking to keep example simple
              case _   => server.sendToClient(client_id, State("result" -> ("unknown op: " + op)))
            }
        }
      }
    }

Tuples inside State must be sorted in ascending order by keys:

CORRECT: ("a", a:Float), ("b", b:Float), ("op", op:String)

WRONG: ("b", b:Float), ("a", a:Float), ("op", op:String)

The `NetServer` constructor have additional parameter `ping_timeout` of type Int which is zero by default. You can 
set it to any non-zero positive value. If parameter is set, server sends "ping" messages ({"ping":true}) to all 
connected clients every `ping_timeout` milliseconds. If sending is failed, such client will be disconnected 
automatically.

    val server = NetServer(9000, 60000) // server will send ping messages every 60 seconds

Code: client
------------

Let's create the client for our arithmetic server which is sending random integers every 5 seconds and checking 
the answers.

    import com.github.dunnololda.simplenet._

    object ArithmeticClient extends App {
      val client = NetClient(host = "localhost", port = 9000)  // ping_timeout parameter is also supported
      client.waitConnection()
      while(true) {
        val (a, b) = ((math.random*100).toFloat, (math.random*100).toFloat)
        val (op, answer) = (math.random*4).toInt match {
          case 0 => ("+", a+b)
          case 1 => ("-", a-b)
          case 2 => ("*", a*b)
          case 3 => if (b != 0) ("/", a/b) else ("+", a+b)
          case _ => ("+", a+b)
        }
        client.send(State("a" -> a, "b" -> b, "op" -> op))
        client.waitNewEvent {
          case NewServerMessage(State(("result", server_answer:Float))) =>
            println("answer: "+answer+"; server answer: "+server_answer)
          case _ =>  // here we match any other event like server disconnecting or reconnecting
        }
        Thread.sleep(5000)
      }
    }

As in server, there is a `waitNewEvent` method which blocks until event is received and `newEvent` and 
`fromNewEventOrDefault` which return immediately. `send` method is used to send data to server.
These are client event types:

    case object ServerConnected extends NetworkEvent
    case object ServerDisconnected extends NetworkEvent
    case class NewServerMessage(data:State) extends NetworkEvent
    
If client is failed to connect to a server or server is disconnected, client will try to reconnect in background. 
No additional user concerns are required. There is `isConnected:Boolean` method to check if client is currently 
connected.

Class State
-----------

`State` is a universal immutable container to store data of any kind, able to represent it as JSON strings. Create State objects this way:

    val s = State("one" -> 1, "two" -> 2, "three" -> State("four" -> 4))

Also you can create them in iterative way using builder:

    val b = State.newBuilder
    b += ("one" -> 1)
    b += ("two" -> 2)
    b += ("three" -> State("four" -> 4))
    val s = b.toState

To serialize State to JSON String use toJsonString method:

    scala> val s = State("one" -> 1, "two" -> 2, "three" -> State("four" -> 4))
    s: State = State(one -> 1, two -> 2, three -> State(four -> 4))

    scala> s.toJsonString
    res0: String = {"one":1, "two":2, "three":{"four":4}}

To create State from JSON string you can use these methods from object State:

    def fromJsonString(json:String):Option[State]
    def fromJsonStringOrDefault(json:String, default_state:State = State()):State

For example:

    scala> State.fromJsonString("""{"one":1, "two":2, "three":{"four":4}}""")
    res1: Option[State] = Some(State(one -> 1.0, two -> 2.0, three -> State(four -> 4.0)))

To retrieve data from State you can use pattern matching or `value`, `valueOrDefault` as shown in section above:

    scala> s.value[Int]("one")
    res4: Option[Int] = Some(1)

UDP Client and Server
---------------------

Since version 1.2 client and server able to communicate via udp. Classes to handle udp: `UdpNetServer` and `UdpNetclient` were added. Mostly api is the very same except few differences. Since udp is a stateless protocol, constant ping/checks required. First ping received from some location considered as New Connection event. No more pings for some period considered as Client or Server Disconnected event.

Create udp server this way:
    
    val server = UdpNetServer(port = 10000, ping_timeout= 1000, check_timeout = 5000)

There are two more params: `buffer_size:Int` and `delimiter:Char`. Defaults are 1024 and '#'. `buffer_size` is the size of the array, in which
messages accumulates. If the message received is more then buffer size, the size would be increased automatically (although the message would be gone). Delimiter is the char to mark the end of the message, so the message itself must not contain such char.

Udp client may be created this way:

    val client = UdpNetClient(address = "localhost", port = 10000, ping_timeout= 1000, check_timeout = 5000)

These types of events can be received in udp server using `newEvent`, `newEventOrDefault` or `waitNewEvent` methods:

    case class NewUdpConnection(client_id:Long) extends UdpEvent
    case class NewUdpClientPacket(location:UdpClientLocation, message:String) extends UdpEvent
    case class NewUdpClientData(client_id:Long, data:State) extends UdpEvent
    case class UdpClientDisconnected(client_id:Long) extends UdpEvent
    case object NoNewUdpEvents extends UdpEvent

These types of events can be received in udp client:

    case class NewUdpConnection(client_id:Long) extends UdpEvent
    case class NewUdpServerPacket(message:String) extends UdpEvent
    case class NewUdpServerData(data:State) extends UdpEvent
    case object UdpServerConnected extends UdpEvent
    case object UdpServerDisconnected extends UdpEvent
    case object NoNewUdpEvents extends UdpEvent

It was a brief overview. To learn more about the simple-net please check the source code or email me.
