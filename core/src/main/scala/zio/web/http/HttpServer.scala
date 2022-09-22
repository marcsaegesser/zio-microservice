package zio.web.http

import java.io.{ IOException, StringReader }
import java.net.URI

import zio.{ Chunk, IO, Promise, UIO, ZIO }

import zio.nio.{ InetSocketAddress }
import zio.nio.channels.{ SelectionKey, Selector, ServerSocketChannel, SocketChannel }
import zio.nio.channels.SelectionKey.Operation
import zio.stream.ZStream
import zio.web.{ AnyF, Endpoint, Endpoints, Handlers }
import zio.web.codec.JsonCodec
import zio.web.http.internal.{ ChannelReader, HttpController, HttpLexer, HttpRouter }
import zio._
import zio.ZIO.blocking

final class HttpServer private (
  router: HttpRouter,
  controller: HttpController[Any],
  selector: Selector,
  serverChannel: ServerSocketChannel,
  socketAddress: InetSocketAddress,
  closed: Promise[Throwable, Unit]
) {
  val awaitOpen: UIO[Unit] = serverChannel.isOpen.repeatUntil(identity).unit

  val awaitShutdown: IO[Throwable, Unit] = closed.await

  val shutdown: UIO[Unit] =
    for {
      _ <- ZIO.logInfo("Stopping server...")
      _ <- ZIO.whenZIO(serverChannel.isOpen)(serverChannel.close).unit.intoPromise(closed)
      _ <- ZIO.logInfo("Server stopped")
    } yield ()

  private val startup: ZIO[Any, IOException, Unit] =
    for {
      _       <- ZIO.logInfo("Starting server...")
      _       <- awaitOpen
      _       <- serverChannel.configureBlocking(false)
      ops     <- serverChannel.validOps
      _       <- serverChannel.register(selector, ops)
      address <- uri
      _       <- ZIO.logInfo(s"Server started at $address")
      _       <- run
    } yield ()

  val localAddress: UIO[InetSocketAddress] = awaitOpen.as(socketAddress)

  val uri: UIO[URI] =
    for {
      inet <- localAddress
      host <- inet.hostName
    } yield new URI("http", null, host, inet.port, "/", null, null)

  private val select: ZIO[Scope, IOException, Unit] =
    selector.select(10.millis).onInterrupt(selector.wakeup).flatMap {
      case 0 => ZIO.unit
      case _ =>
        selector.selectedKeys.flatMap { keys =>
          ZIO.foreachParDiscard(keys) { key =>
            key.readyOps.flatMap { ops =>
              for {
                _ <- ZIO.when(ops contains Operation.Accept)(accept)
                _ <- ZIO.when(ops contains Operation.Read)(read(key))
                _ <- ZIO.when(ops contains Operation.Write)(write(key))
                _ <- selector.removeKey(key)
              } yield ()
            }
          }
        }
    }

  private val accept: ZIO[Scope, IOException, Unit] =
    for {
      _ <- ZIO.logInfo("Accepting connection...")
      _ <- serverChannel.flatMapNonBlocking { c =>
        c.accept.flatMap {
          case Some(channel) => ZIO.logInfo("Accepted connection") *> HttpServer.Connection(router, controller, channel, selector)
          case None          => ZIO.logDebug("No connection is currently available to be accepted")
        }.refineToOrDie[IOException]
      }
    } yield ()

  private def read(key: SelectionKey): ZIO[Any, IOException, Unit] =
    for {
      _ <- ZIO.logDebug("Reading connection...")
      _ <- key.attachment.flatMap {
            case Some(attached) => attached.asInstanceOf[HttpServer.Connection].read
            case None           => ZIO.logError("Connection is not ready to be read")
          }
    } yield ()

  private def write(key: SelectionKey): ZIO[Any, IOException, Unit] =
    for {
      _ <- ZIO.logDebug("Writing connection")
      _ <- key.attachment.flatMap {
            case Some(attached) => attached.asInstanceOf[HttpServer.Connection].write
            case None           => ZIO.logError("Connection is not ready to be written")
          }
    } yield ()

  private val run: ZIO[Any, IOException, Nothing] =
    ZIO.scoped(
      (select *> ZIO.yieldNow).forever
        .onInterrupt(ZIO.logDebug("Selector loop interrupted"))
    )
}

object HttpServer {

  val run: ZIO[HttpServer, IOException, HttpServer] =
    ZIO.service[HttpServer].tap(_.startup.orDie)

  def build[M[+_], R, Ids](
    config: HttpServerConfig,
    endpoints: Endpoints[M, Ids],
    handlers: Handlers[M, R, Ids],
    env: R
  )(implicit arg0: Tag[R]): ZIO[Scope, IOException, HttpServer] =
    for {
      closed     <- Promise.make[Throwable, Unit]
      address    <- InetSocketAddress.hostName(config.host, config.port)
      channel    <- openChannel(address, 0)
      selector   <- Selector.open
      router     <- HttpRouter.make[M](endpoints)
      controller <- HttpController.make[M, R, Ids](handlers, env)
    } yield new HttpServer(
      router,
      controller.asInstanceOf[HttpController[Any]],
      selector,
      channel,
      address,
      closed
    )

  private def openChannel(
    address: InetSocketAddress,
    maxPending: Int
  ): ZIO[Scope, IOException, ServerSocketChannel] =
    ServerSocketChannel.open
      .tap(_.configureBlocking(false))
      .tap(channel => blocking { channel.bind(Some(address), maxPending) })

  private[HttpServer] class Connection private (
    router: HttpRouter,
    controller: HttpController[Any],
    selector: Selector,
    channel: SocketChannel,
    response: Promise[IOException, Chunk[Byte]],
    closed: Promise[Throwable, Unit]
  ) { self =>

    val awaitOpen: UIO[Unit] = channel.isOpen.repeatUntil(identity).unit

    val awaitShutdown: IO[Throwable, Unit] = closed.await

    val read: UIO[Unit] =
      (for {
        _           <- awaitOpen
        reader      = ChannelReader(channel, 32)
        data        <- reader.readUntilNewLine()
        firstLine   = new String(data.value.toArray)
        _           <- ZIO.logInfo(s"Read start line:\n$firstLine")
        startLine   = HttpLexer.parseStartLine(new StringReader(firstLine))
        _           <- ZIO.logInfo(s"Parsed ${startLine}")
        endpoint    <- router.route(startLine)
        _           <- ZIO.logInfo(s"Request matched to [${endpoint.endpointName}].")
        data        <- reader.readUntilEmptyLine(data.tail)
        headerLines = new String(data.value.toArray)
        _           <- ZIO.logInfo(s"Read headers:\n$headerLines")
        // TODO: extract headers parsing and wrap them in HttpHeaders to tidy up a little here
        headerNames  = Array("Content-Length") // TODO: handle when not given then do not read the content
        headerValues = HttpLexer.parseHeaders(headerNames, new StringReader(headerLines))
        headers = headerNames
          .zip(headerValues.map(_.headOption))
          .collect { case (key, Some(value)) => key -> value }
          .toMap
        _          <- ZIO.logInfo(s"Parsed headers:\n$headers")
        bodyLength = headers("Content-Length").toInt
        data       <- reader.read(bodyLength, data.tail)
        bodyLines  = new String(data.toArray)
        _          <- ZIO.logInfo(s"Read body:\n$bodyLines")
        input      <- Connection.decodeInput(data, endpoint)
        _          <- ZIO.logInfo(s"Parsed body:\n$input")
        output     <- controller.handle(endpoint)(input, ())
        _          <- ZIO.logInfo(s"Handler returned $output")
        success    <- Connection.sucessResponse(output, endpoint)
        _          <- channel.register(selector, Set(Operation.Write), Some(self))
      } yield success).tapError(e => ZIO.logError(s"read:  $e")).intoPromise(response).unit

    val write: ZIO[Any, IOException, Unit] =
      for {
        bytes <- response.await
        _     <- channel.flatMapNonBlocking{ _.writeChunk(bytes) }
        _     <- ZIO.logInfo(s"Sent response data")
        _     <- shutdown
      } yield ()

    val shutdown: UIO[Unit] =
      for {
        _ <- ZIO.logDebug("Stopping connection...")
        _ <- ZIO.whenZIO(channel.isOpen)(channel.close).unit.intoPromise(closed)
        _ <- ZIO.logDebug("Connection stopped")
      } yield ()
  }

  private[HttpServer] object Connection {

    def apply[R](
      router: HttpRouter,
      controller: HttpController[Any],
      channel: SocketChannel,
      selector: Selector
    ): UIO[Unit] =
      ZIO.scoped[Any] {
        (for {
          _        <- ZIO.logInfo(s"Connection.apply:  IN")
          response <- Promise.make[IOException, Chunk[Byte]]
          closed   <- Promise.make[Throwable, Unit]
          c        <- ZIO.acquireRelease((ZIO.succeed(new Connection(router, controller, selector, channel, response, closed))))(_.shutdown)
          _        <- register(channel, selector)(c)
          _        <- c.awaitShutdown
        } yield ())
      }.forkDaemon.unit

    def register(channel: SocketChannel, selector: Selector)(
      connection: Connection
    ): ZIO[Scope, IOException, SelectionKey] =
      (for {
        _   <- channel.configureBlocking(false)
        key <- ZIO.acquireRelease(channel.register(selector, Set(Operation.Read), Some(connection)))(_.cancel)
      } yield key)

    def sucessResponse[O](output: O, endpoint: Endpoint[AnyF, _, _, _]): UIO[Chunk[Byte]] =
      for {
        body <- encodeOutput(output, endpoint.asInstanceOf[Endpoint[AnyF, _, _, O]])
        headers = httpHeaders(
          "HTTP/1.0 200 OK",
          "Content-Type: text/plain",
          s"Content-Length: ${body.size}"
        )
      } yield headers ++ body

    // TODO: make codec configurable
    def decodeInput[I](bytes: Chunk[Byte], endpoint: Endpoint[AnyF, _, I, _]): IO[IOException, I] =
      ZStream
        .fromChunk(bytes)
        .via(JsonCodec.decoder(endpoint.request))
        .runHead
        .catchAll(_ => ZIO.none)
        .someOrElseZIO(ZIO.fail(new IOException("Could not decode input")))

    def encodeOutput[O](output: O, endpoint: Endpoint[AnyF, _, _, O]): UIO[Chunk[Byte]] =
      ZStream(output)
        .via(JsonCodec.encoder(endpoint.response))
        .runCollect

    def httpHeaders(headers: String*): Chunk[Byte] =
      Chunk.fromArray(
        headers
          .mkString("", "\r\n", "\r\n\r\n")
          .getBytes
      )
  }
}
