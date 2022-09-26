package zio.web

import java.io.IOException

import zio.{ Tag, Task, ZIO, ZLayer }

trait ProtocolModule {
  type ServerConfig
  type ClientConfig
  type ServerService
  type ClientService[Ids] <: ProtocolModule.Client[Ids]
  type ProtocolDocs
  type Middleware[-R, +E]
  type MinMetadata[+_]

  def makeServer[M[+_] <: MinMetadata[_], R <: ServerConfig: Tag, E, Ids: Tag](
    middleware: Middleware[R, E],
    endpoints: Endpoints[M, Ids],
    handlers: Handlers[M, R, Ids]
  ): ZLayer[R with Any, IOException, ServerService]

  def makeDocs[R, M[+_] <: MinMetadata[_]](endpoints: Endpoints[M, _]): ProtocolDocs

  def makeClient[M[+_] <: MinMetadata[_], Ids: Tag](
    endpoints: Endpoints[M, Ids]
  ): ZLayer[ClientConfig, IOException, ClientService[Ids]]

  /**
   * Extension methods on Endspoints available for all protocols.
   */
  implicit class EndpointsOps[M[+_], Ids](endpoints: Endpoints[M, Ids]) {

    def invoke[I, O](endpoint: Endpoint[M, Unit, I, O])(input: I)(
      implicit ev: Ids <:< endpoint.Id,
      tt: Tag[ClientService[Ids]]
    ): ZIO[ClientService[Ids], Throwable, O] =
      ZIO.environmentWithZIO[ClientService[Ids]](_.get.invoke(endpoint)(input))

    def invoke[P, I, O](endpoint: Endpoint[M, P, I, O])(input: I, params: P)(
      implicit ev: Ids <:< endpoint.Id,
      tt: Tag[ClientService[Ids]]
    ): ZIO[ClientService[Ids], Throwable, O] =
      ZIO.environmentWithZIO[ClientService[Ids]](_.get.invoke(endpoint)(input, params))
  }
}

object ProtocolModule {

  trait Client[Ids] {
    final def invoke[M[+_], I, O](endpoint: Endpoint[M, Unit, I, O])(input: I)(
      implicit ev: Ids <:< endpoint.Id
    ): Task[O] = invoke[M, Unit, I, O](endpoint)(input, ())

    def invoke[M[+_], P, I, O](endpoint: Endpoint[M, P, I, O])(input: I, params: P)(
      implicit ev: Ids <:< endpoint.Id
    ): Task[O]
  }
}
