package zio.web.example

import zio._
import zio.schema.{ DeriveSchema, Schema }
import zio.web.{ Endpoints, Handler, Handlers, endpoint }
import zio.web.codec.JsonCodec
import zio.web.http.{ HttpMiddleware, HttpProtocolModule, HttpServer, HttpServerConfig }
import zio.web.http.model.{ Method, Route }
import zio.web.http.HttpClientConfig
import zio.{ Console, ZIOAppDefault }

object HelloServer extends ZIOAppDefault with HelloExample {

  // just define handlers for all endpoints
  lazy val sayHelloHandlers =
    Handlers(Handler.make(sayHello) { (req: HelloRequest) =>
      for {
        _ <- ZIO.logInfo(s"Handling sayHello request for ${req.name}")
        _ <- Console.printLine(s"Handling sayHello request for ${req.name}").ignore
      } yield TextPlainResponse(s"Hello ${req.name}!")
    })

  // generate the server
  lazy val helloServerLayer =
    makeServer(HttpMiddleware.none, sayHelloService, sayHelloHandlers)

  // and run it
  def run =
    program.provideSomeLayer(logging.console(logLevel=LogLevel.Debug)).exitCode

  lazy val program =
    for {
      _      <- ZIO.logInfo("Hello server started")
      config = HttpServerConfig("localhost", 8080)
      server <- HttpServer.run.provideLayer(httpServer(config))
      _      <- server.awaitShutdown.orDie
      _      <- ZIO.logInfo("Hello server stopped")
    } yield ()

  def httpServer(config: HttpServerConfig) =
    ZLayer.succeed(config) >+> helloServerLayer
}

object HelloClient extends ZIOAppDefault with HelloExample {

  // just generate the client
  lazy val helloClientLayer =
    makeClient(sayHelloService)

  // and run it
  def run =
    program.provideSomeLayer(logging.console(logLevel=LogLevel.Debug)).exitCode

  lazy val program =
    for {
      _        <- ZIO.logInfo("Hello client started")
      config   = HttpClientConfig("localhost", 8080)
      request  = HelloRequest("Janet", "Hi!")
      response <- sayHelloService.invoke(sayHello)(request).provideLayer(httpClient(config))
      _        <- ZIO.logInfo(s"Got ${response}")
      _        <- ZIO.logInfo("Press [enter] to stop the client")
      _        <- Console.readLine
      _        <- ZIO.logInfo("Hello client stopped")
    } yield ()

  // misc utils
  def httpClient(config: HttpClientConfig) =
    ZLayer.succeed(config) >+> helloClientLayer
}

trait HelloExample extends HttpProtocolModule {

  // code shared between client and server
  val allProtocols    = Map.empty
  val defaultProtocol = JsonCodec

  sealed case class HelloRequest(name: String, message: String)
  sealed case class TextPlainResponse(content: String)

  val helloSchema: Schema[HelloRequest]          = DeriveSchema.gen
  val textPlainSchema: Schema[TextPlainResponse] = DeriveSchema.gen

  val sayHello =
    endpoint("sayHello")
      .withRequest(helloSchema)
      .withResponse(textPlainSchema) @@ Route("/{name}") @@ Method.GET

  lazy val sayHelloService = Endpoints(sayHello)
}
