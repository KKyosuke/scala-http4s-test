import cats.effect._
import com.comcast.ip4s._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger

object Main extends IOApp {

  def run(args: List[String]) = {
    val host = host"0.0.0.0"
    val port = port"8080"

    val app = HttpRoutes.of[IO] {
      case GET -> Root / "hello" / name =>
        Ok(s"Hello, $name.")
    }.orNotFound

    // With Middlewares in place
    val finalHttpApp = Logger.httpApp(true, true)(app)

    for {
      // Server Level Resources Here
      server <-
        EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(finalHttpApp)
          //          .withHttpWebSocketApp(service[IO])
          .build
    } yield server
  }.use(server =>
    IO.delay(println(s"Server Has Started at ${server.address}")) >>
      IO.never.as(ExitCode.Success)
  )
}