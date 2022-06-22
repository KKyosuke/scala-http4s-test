import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, KafkaConsumer}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import java.util
import java.util.Properties
// https://github.com/circe/circe-fs2/issues/183#issuecomment-722415848
import fs2.Stream
import fs2.Pipe
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import scala.concurrent.duration._

object Main extends IOApp {

  def run(args: List[String]) = {
    val host = host"0.0.0.0"
    val port = port"8989"

    for {
      // Server Level Resources Here
      server <-
        EmberServerBuilder
          .default[IO]
          .withIdleTimeout(90.seconds)
          .withHost(host)
          .withPort(port)
          .withHttpWebSocketApp(service[IO])
          .build
    } yield server
  }.use(server =>
    IO.delay(println(s"Server Has Started at ${server.address}")) >>
      IO.never.as(ExitCode.Success)
  )

  def service[F[_] : Async](wsb: WebSocketBuilder[F]): HttpApp[F] = {
    val topicName = "sample-topic"
    println("connecting to %s".format(topicName))

    val producerProps = new Properties()
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])

    val producer = new KafkaProducer[String, String](producerProps)

    val consumerProps = new Properties()
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "scala-consumer-group")
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer])
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer])
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")

    val dsl = new Http4sDsl[F] {}
    import dsl._
    HttpRoutes
      .of[F] {
        case req@POST -> Root =>
          for {
            json <- req.decodeJson[Json]
            resp <- Ok(json)
          } yield resp
        case GET -> Root =>
          val body =
            """
              |<!DOCTYPE html>
              |<html lang="ja">
              |<head>
              |    <meta charset="UTF-8">
              |    <meta name="viewport" content="width=device-width, initial-scale=1.0">
              |    <meta http-equiv="X-UA-Compatible" content="ie=edge">
              |    <title>Document</title>
              |</head>
              |<body>
              |
              |<button type="button" id="startButton">開始</button>
              |<button type="button" id="sendText">テキスト送信</button>
              |<button type="button" id="sendPing">PING送信</button>
              |<button type="button" id="publish">publish</button>
              |<div>
              |    <ul id="listItem">
              |    </ul>
              |</div>
              |<script>
              |    document.addEventListener('DOMContentLoaded', function (e) {
              |        let sock = null;
              |
              |        document.getElementById('startButton').addEventListener('click', function (e) {
              |            sock = new WebSocket('ws://localhost:8989/ws');
              |            // 接続
              |            let starttime = 0;
              |            let endtime = 0;
              |            sock?.addEventListener('open', function (e) {
              |                starttime = Date.now()
              |                const item = document.createElement("li");
              |                item.innerHTML = `Socket 接続成功 [time:${starttime}ms]`;
              |                document.getElementById("listItem").append(item);
              |                console.log(e.data);
              |            });
              |
              |            // サーバーからデータを受け取る
              |            sock?.addEventListener('message', function (e) {
              |                const item = document.createElement("li");
              |                item.innerHTML = `${e.data} [time:${Date.now()}ms]`;
              |                document.getElementById("listItem").append(item);
              |                console.log(e.data);
              |                sock?.ping();
              |            });
              |
              |            // サーバーにデータを送る
              |            document.getElementById('sendText').addEventListener('click', function (e) {
              |                sock?.send('hello');
              |            });
              |
              |            // サーバーにデータを送る
              |            document.getElementById('sendPing').addEventListener('click', function (e) {
              |                sock?.ping();
              |            });
              |
              |            // サーバーにデータを送る
              |            document.getElementById('publish').addEventListener('click', function (e) {
              |                fetch('http://localhost:8989/kafka/publish/messaging')
              |            });
              |
              |            sock?.addEventListener('close', function (e) {
              |                endtime = Date.now()
              |                const item = document.createElement("li");
              |                item.innerHTML = `Disconnected!! [time:${Date.now()}ms]`;
              |                document.getElementById("listItem").append(item);
              |                console.log(e.data);
              |
              |                const item2 = document.createElement("li");
              |                item2.innerHTML = `sum [time:${endtime - starttime}ms]`;
              |                document.getElementById("listItem").append(item2);
              |                console.log(e.data);
              |
              |                // document.getElementById('startButton').click();
              |            });
              |        });
              |    });
              |</script>
              |</body>
              |</html>
              |""".stripMargin
          Ok(body).map(_.withContentType(headers.`Content-Type`(MediaType.text.html)))
        case GET -> Root / "hello" / name =>
          Ok(show"Hi $name!")
        case GET -> Root / "chunked" =>
          val body = Stream("This IS A CHUNK\n").repeat
            .take(100)
            .through(fs2.text.utf8.encode[F])
          Ok(body).map(_.withContentType(headers.`Content-Type`(MediaType.text.html)))
        case GET -> Root / "kafka" / "publish" / text =>
          val data = new ProducerRecord[String, String](topicName, "one", s"This text[$text] is from my simple producer")
          producer.send(data)
          // producer.close() TODO: close when app is finished
          Ok(show"your text[$text] was published")
        case GET -> Root / "ws" =>
          val send: Stream[F, WebSocketFrame] = {
            val consumer = new KafkaConsumer[String, String](consumerProps)
            consumer.subscribe(util.Arrays.asList("sample-topic"))

            Stream.awakeEvery[F](3.seconds).map({ _ =>
              import java.time.Duration
              val records = consumer.poll(Duration.ofSeconds(1L))
              var message = ""
              records.forEach({ record =>
                message += record.value
              })
              if (message.isEmpty) {
                WebSocketFrame.Text("no message")
              } else {
                WebSocketFrame.Text(s"$message")
              }
            })
          }
          val receive: Pipe[F, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) => {
              Sync[F].delay(println(s"$text from client"))
            }
            case WebSocketFrame.Ping(data) => {
              Sync[F].delay(println(s"ping from client"))
            }
            case other => Sync[F].delay(println(other))
          }
          wsb.build(send, receive)
      }
      .orNotFound
  }
}