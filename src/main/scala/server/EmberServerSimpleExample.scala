package server

import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
// https://github.com/circe/circe-fs2/issues/183#issuecomment-722415848
import fs2.Stream
import fs2.Pipe
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
//import org.http4s.server.middleware.Logger
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import scala.concurrent.duration._

object EmberServerSimpleExample extends IOApp {

  def run(args: List[String]) = {
    val host = host"0.0.0.0"
    val port = port"8989"

    val app = HttpRoutes.of[IO] {
      case GET -> Root / "hello" / name =>
        Ok(s"Hello, $name.")
    }.orNotFound

    //// With Middlewares in place
    // val finalHttpApp = Logger.httpApp(true, true)(app)

    for {
      // Server Level Resources Here
      server <-
        EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          // .withHttpApp(finalHttpApp)
          .withHttpWebSocketApp(service[IO])
          .build
    } yield server
  }.use(server =>
    IO.delay(println(s"Server Has Started at ${server.address}")) >>
      IO.never.as(ExitCode.Success)
  )

  def service[F[_]: Async](wsb: WebSocketBuilder2[F]): HttpApp[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    HttpRoutes
      .of[F] {
        case req @ POST -> Root =>
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
        case GET -> Root / "ws" =>
          val send: Stream[F, WebSocketFrame] = {
            Stream.awakeEvery[F](1.seconds).map(_ => WebSocketFrame.Text("text"))
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