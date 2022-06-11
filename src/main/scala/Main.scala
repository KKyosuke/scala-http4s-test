import _root_.io.circe._
import _root_.org.http4s.ember.server.EmberServerBuilder
import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s._
import fs2._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.middleware.Logger
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val host = host"0.0.0.0"
    val port = port"8080"
    for {

      app <- HttpRoutes.of[IO] {
        case GET -> Root / "hello" / name =>
        Ok(s"Hello, $name.")
      }.orNotFound

      // With Middlewares in place
      finalHttpApp = Logger.httpApp(true, true)(app)

      // Server Level Resources Here
      server <-
        EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)

//          .withHttpWebSocketApp(service[IO])
          .build
    } yield server
  }.use(server =>
    IO.delay(println(s"Server Has Started at ${server.address}")) >>
      IO.never.as(ExitCode.Success)
  )

  def service[F[_]: Async](wsb: WebSocketBuilder[F]): HttpApp[F] = {
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
          val send: Stream[F, WebSocketFrame] =
            Stream.awakeEvery[F](1.seconds).map(_ => WebSocketFrame.Text("text"))
          val receive: Pipe[F, WebSocketFrame, Unit] = _.evalMap {
            case WebSocketFrame.Text(text, _) => Sync[F].delay(println(text))
            case other => Sync[F].delay(println(other))
          }
          wsb.build(send, receive)
      }
      .orNotFound
  }
}