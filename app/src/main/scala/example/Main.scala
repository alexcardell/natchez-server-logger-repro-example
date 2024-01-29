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
import natchez.Kernel
import natchez.Span
import natchez.Trace
import natchez.http4s.NatchezMiddleware
import natchez.http4s.syntax.EntryPointOps
import natchez.log.Log
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

    def apply[F[_]: Monad: Trace: TracedLogger](
        client: Client[F]
    ): HttpRoutes[F] = {
      object dsl extends org.http4s.dsl.Http4sDsl[F]
      import dsl._

      HttpRoutes.of[F] { case GET -> Root / "operation" =>
        Trace[F].span("route-operation") {
          for {
            _   <- Logger[F].info("log message")
            _   <- client.get("/downstream")(_ => ().pure[F])
            res <- Ok("success")
          } yield res
        }

      }
    }

  }

  override def run(args: List[String]): IO[ExitCode] = {

    val stream = {
      val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

      val entrypoint = {
        implicit val natchezLogger: Logger[IO] = loggerFactory
          .getLoggerFromName("natchez_log")
        Log.entryPoint[IO]("example-service", _.spaces2)
      }

      for {
        implicit0(trace: Trace[IO]) <-
          Trace.ioTraceForEntryPoint(entrypoint).stream
        implicit0(logger: TracedLogger[IO]) = TracedLogger[IO](
          loggerFactory.getLogger
        )
        // client <- Stream.resource(EmberClientBuilder.default[IO].build
        baseClient       = StubApi.client[IO]
        clientTracing = NatchezMiddleware.client[IO](_)
        clientLogging = 
         ClientLogger.apply[IO](
          true,
          true,
          logAction = Some(s => logger.info(s))
        )(_)

        client = clientTracing(clientLogging(baseClient))
        routes = Routes[IO](client)

        logMiddleware =
          ServerLogger.httpRoutes[IO](
            true,
            true,
            logAction = Some(s => logger.info(s))
          )(_)

        // neither order of application makes server logging apply trace IDs
        appRoutes = logMiddleware(traceMiddleware(routes))
        // end

        httpApp = appRoutes.orNotFound
        server  = EmberServerBuilder.default[IO].withHttpApp(httpApp).build
        _ <-
          Stream
            .resource(server)
            .evalMap(_ => IO.never)
            .void
      } yield ExitCode.Success
    }

    stream.compile.lastOrError
  }

  // using `Trace.ioTraceForEntrypoint`
  // does not guarantee a starting span, so we must initialise,
  // and set the parent kernel from request headers for
  // distributed tracing
  def traceMiddleware(
      routes: HttpRoutes[IO]
  )(implicit T: Trace[IO]): HttpRoutes[IO] = {

    val tracedRoutes = NatchezMiddleware.server[IO](routes)

    val unsafeHeaders =
      EntryPointOps.ExcludedHeaders ++ Set(
        "api-key",
        "x-api-key",
        "apikey",
        "x-apikey"
      )

    def isKernelHeader(name: CIString): Boolean = !unsafeHeaders.contains(name)

    def kernelFromHeaders(headers: Headers): Kernel =
      Kernel(headers.headers.collect {
        case h if isKernelHeader(h.name) => h.name -> h.value
      }.toMap)

    Kleisli { req =>
      val kernel = kernelFromHeaders(req.headers)
      val spanOptions = Span.Options.Defaults
        .withSpanKind(Span.SpanKind.Server)
        .withParentKernel(kernel)

      OptionT {
        Trace[IO].span("http4s-server-request", spanOptions) {
          tracedRoutes.run(req).value
        }
      }
    }
  }

  // for nice syntax in app construction
  implicit class FOps[F[_], A](fa: F[A]) {
    def stream: Stream[F, A] = Stream.eval(fa)
  }

}
