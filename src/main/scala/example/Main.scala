package example

import cats.Monad
import cats.data.Kleisli
import cats.data.OptionT
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.Stream
// import natchez.Kernel
// import natchez.Span
// import natchez.Trace
// import natchez.http4s.NatchezMiddleware
// import natchez.http4s.syntax.EntryPointOps
// import natchez.log.Log
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.server.middleware.{Logger => ServerLogger}
import org.http4s.client.middleware.{Logger => ClientLogger}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.http4s.ember.client.EmberClient
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Otel4s
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.http4s.otel4s.middleware.ServerMiddleware

object Main extends IOApp {

  // just to generate a client with some tracing
  object StubApi {

    def routes[F[_]: Monad]: HttpRoutes[F] = {
      object dsl extends org.http4s.dsl.Http4sDsl[F]
      import dsl._

      HttpRoutes.of[F] { case GET -> Root / "downstream" => Ok("success") }
    }

    def client[F[_]: Async]: Client[F] =
      Client.fromHttpApp[F](routes[F].orNotFound)

  }

  object Routes {

    def apply[F[_]: Monad: Tracer: TracedLogger](
        client: Client[F]
    ): HttpRoutes[F] = {
      object dsl extends org.http4s.dsl.Http4sDsl[F]
      import dsl._

      HttpRoutes.of[F] { case GET -> Root / "operation" =>
        Tracer[F].span("route-operation").use { _ =>
          for {
            _ <- Logger[F].info("log message")
            _ <- client.get("/downstream")(_ => ().pure[F])
            res <- Ok("success")
          } yield res
        }

      }
    }

  }

  override def run(args: List[String]): IO[ExitCode] = {

    val stream = {
      val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

      for {
        implicit0(otel: Tracer[IO]) <- Stream
          .resource(OpenTelemetrySdk.autoConfigured[IO]())
          .evalMap(_.sdk.tracerProvider.get("com.tracer"))
        implicit0(logger: TracedLogger[IO]) = TracedLogger[IO](
          loggerFactory.getLogger
        )
        // client <- Stream.resource(EmberClientBuilder.default[IO].build
        baseClient = StubApi.client[IO]
        // clientTracing = NatchezMiddleware.client[IO](_)
        clientLogging =
          ClientLogger.apply[IO](
            true,
            true,
            logAction = Some(s => logger.info(s))
          )(_)

        client = clientLogging(baseClient)
        routes = Routes[IO](client)

        logMiddleware =
          ServerLogger.httpRoutes[IO](
            true,
            true,
            logAction = Some(s => logger.info(s))
          )(_)

        // neither order of application makes server logging apply trace IDs
        // appRoutes = logMiddleware(traceMiddleware(routes))
        appRoutes = traceMiddleware(logMiddleware(routes))
        // end

        httpApp = appRoutes.orNotFound
        server = EmberServerBuilder.default[IO].withHttpApp(httpApp).build
        _ <-
          Stream
            .resource(server)
            .evalMap(_ => IO.never)
            .void
      } yield ExitCode.Success
    }

    stream.compile.lastOrError
  }

  def traceMiddleware(
      routes: HttpRoutes[IO]
  )(implicit T: Tracer[IO]): HttpRoutes[IO] = {
    ServerMiddleware.default[IO].buildHttpRoutes(routes)
  }

  // for nice syntax in app construction
  implicit class FOps[F[_], A](fa: F[A]) {
    def stream: Stream[F, A] = Stream.eval(fa)
  }

}
