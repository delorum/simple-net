package samples

import com.github.dunnololda.simplenet._
import org.junit.Assert._
import org.junit._
import play.api.libs.json.Json

case class ClientQuestion(a:Float, b:Float, op:String)
case class ServerAnswer(result:Option[Float], error:Option[String])

@Test
class AppTest {

    @Test
    def testOK() {
      // arithmetic server and client
      val server = TcpNetServer(9000, 60000)
      val client = TcpNetClient("localhost", server.listenPort, 60000)


      implicit val ClientQuestion_reader = Json.reads[ClientQuestion]
      implicit val ServerAnswer_reader = Json.reads[ServerAnswer]

      client.waitConnection()
      (1 to 20).foreach { i =>
        val i1 = (math.random*100).toInt
        val i2 = (math.random*100).toInt
        val (str_op, answer) = (math.random*4).toInt match {
          case 0 => ("+", 1f*i1+i2)
          case 1 => ("-", 1f*i1-i2)
          case 2 => ("*", 1f*i1*i2)
          case 3 => if(i2 != 0) ("/", 1f*i1/i2) else ("+", 1f*i1+i2)
          case _ => ("+", 1f*i1+i2)
        }
        val question = Json.obj("a" -> i1, "b" -> i2, "op" -> str_op)
        client.send(question)
        server.waitNewEvent {
          case NewMessage(client_id, client_question) =>
            client_question.validate[ClientQuestion].asOpt match {
              case Some(ClientQuestion(a, b, op)) =>
                op match {
                  case "+" => server.sendToClient(client_id, Json.obj("result" -> (a + b)))
                  case "-" => server.sendToClient(client_id, Json.obj("result" -> (a - b)))
                  case "*" => server.sendToClient(client_id, Json.obj("result" -> (a * b)))
                  case "/" => server.sendToClient(client_id, Json.obj("result" -> (a / b)))
                  case _   => server.sendToClient(client_id, Json.obj("error" -> ("unknown op: " + op)))
                }
              case None =>
                server.sendToClient(client_id, Json.obj("error" -> "unknown data"))
            }
        }
        client.waitNewEvent {
          case NewServerMessage(server_answer) =>
            server_answer.validate[ServerAnswer].asOpt match {
              case Some(ServerAnswer(result, error)) =>
                assertTrue(result.exists(r => r == answer))
              case None => assertTrue(false)
            }
        }
      }

      assertTrue(server.clientIds.length == 1)
      assertTrue(client.isConnected)
    }
}


