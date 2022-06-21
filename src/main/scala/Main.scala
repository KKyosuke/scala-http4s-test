import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, KafkaConsumer}
import org.apache.kafka.common.serialization.StringDeserializer

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
          Ok(Json.obj("root" -> Json.fromString("GET")))
        case GET -> Root / "hello" / name =>
          Ok(show"Hi $name!")
        case GET -> Root / "chunked" =>
          val body = Stream("This IS A CHUNK\n").repeat
            .take(100)
            .through(fs2.text.utf8.encode[F])
          Ok(body).map(_.withContentType(headers.`Content-Type`(MediaType.text.plain)))
        case GET -> Root / "kafka" / "publish" / text =>
          val topicName = "sample-topic"
          println("connecting to %s".format(topicName))

          val props = new Properties()
          props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
          props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
          props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

          val producer = new KafkaProducer[String, String](props)

          val data = new ProducerRecord[String, String](topicName, "one", s"This text[$text] is from my simple producer")
          producer.send(data)
          producer.close()
          Ok(show"your text[$text] was published")
        case GET -> Root / "ws" =>
          val send: Stream[F, WebSocketFrame] = {
            val props = new Properties()
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "scala-consumer-group")
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
            val consumer = new KafkaConsumer[String, String](props)
            consumer.subscribe(util.Arrays.asList("sample-topic"))

            Stream.awakeEvery[F](2.seconds).map({ _ =>
              //              try {
              import java.time.Duration
              val records = consumer.poll(Duration.ofSeconds(2L))
              var message = ""
              records.forEach({ record =>
                message += record.value
                System.out.println(String.format("%s:%s", record.offset, record.value))
              })
              if (message.isEmpty) {
                WebSocketFrame.Text("no message")
              } else {
                WebSocketFrame.Text(s"$message")
              }
              //              } catch {
              //                case e: Exception => println("arg " + e)
              //              }
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