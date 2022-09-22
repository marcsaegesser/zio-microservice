package zio.web.http

import zio._

import zio.web.http.model._
import zio.web._

import java.io.IOException

trait HttpProtocolModule extends ProtocolModule {
  type ServerConfig       = HttpServerConfig
  type ClientConfig       = HttpClientConfig
  type ServerService      = HttpServer
  type ClientService[Ids] = HttpClient[Ids]
  type Middleware[-R, +E] = HttpMiddleware[R, E]
  type MinMetadata[+A]    = HttpAnn[A]

  val defaultProtocol: codec.Codec

  val allProtocols: Map[String, codec.Codec]

  override def makeServer[M[+_] <: MinMetadata[_], R <: ServerConfig: Tag, E, Ids: Tag](
    middleware: Middleware[R, E],
    endpoints: Endpoints[M, Ids],
    handlers: Handlers[M, R, Ids]
  ): ZLayer[R, IOException, ServerService] = {
    val _ = middleware

    ZLayer.scoped[R](
      for {
        config <- ZIO.service[ServerConfig]
        env    <- ZIO.service[R]
        server <- HttpServer.build(config, endpoints, handlers, env)
      } yield server
    )
  }

  override def makeClient[M[+_] <: MinMetadata[_], Ids: Tag](
    endpoints: Endpoints[M, Ids]
  ): ZLayer[ClientConfig, IOException, ClientService[Ids]] =
    ZLayer.scoped(
      for {
        config <- ZIO.service[ClientConfig]
        client <- HttpClient.build(config, endpoints)
      } yield client
    )

  override def makeDocs[R, M[+_] <: MinMetadata[_]](endpoints: Endpoints[M, _]): ProtocolDocs =
    ???
}
