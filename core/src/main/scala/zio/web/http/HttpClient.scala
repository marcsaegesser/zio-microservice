package zio.web.http

import java.io.{ IOException, StringReader }

import zio.{ Chunk, IO, ZIO }

import zio.nio.{ InetSocketAddress }
import zio.nio.channels.{ AsynchronousChannelGroup, AsynchronousSocketChannel }
import zio.stream.ZStream
import zio.web.{ AnyF, Endpoint, Endpoints, ProtocolModule }
import zio.web.codec.JsonCodec
import zio.web.http.model.{ Method, Version }
import zio.web.http.internal.{ ChannelReader, HttpLexer }
import zio._

final class HttpClient[Ids](
  address: InetSocketAddress,
  channelGroup: AsynchronousChannelGroup,
) extends ProtocolModule.Client[Ids] {

  import HttpClient._

  def invoke[M[+_], P, I, O](endpoint: Endpoint[M, P, I, O])(input: I, params: P)(
    implicit ev: Ids <:< endpoint.Id
  ): ZIO[Any, Exception, O] =
    ZIO.scoped(
      (for {
        _           <- ZIO.logInfo(s"Invoking [${endpoint.endpointName}].")
        channel     <- AsynchronousSocketChannel.open(channelGroup)
        _           <- channel.connect(address)
        request     <- encodeRequest(endpoint)(input)
        _           <- channel.writeChunk(request)
        _           <- ZIO.logInfo(s"Sent request")
        reader      = ChannelReader(channel, 32, 30.seconds)
        data        <- reader.readUntilNewLine()
        statusLine  = new String(data.value.toArray)
        _           <- ZIO.logInfo(s"Read status line:\n$statusLine")
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
        output     <- HttpClient.decodeResponse(endpoint)(data)
      } yield output)
    )
}

object HttpClient {

  def build[M[+_], Ids](
    config: HttpClientConfig,
    endpoints: Endpoints[M, Ids]
  ): ZIO[Scope, IOException, HttpClient[Ids]] =
    for {
      address <- InetSocketAddress.hostName(config.host, config.port)
      group   <- ZIO.executor.flatMap(k => AsynchronousChannelGroup(k.asExecutionContextExecutorService))
      client  <- ZIO.succeed(new HttpClient[Ids](address, group))
      _       = endpoints
    } yield client

  final private[http] case class ResponseMessage(headers: Chunk[Byte], body: Chunk[Byte])

  private def encodeRequest[I](endpoint: Endpoint[AnyF, _, I, _])(input: I): IO[IOException, Chunk[Byte]] =
    for {
      body <- ZStream.succeed(input).via(JsonCodec.encoder(endpoint.request)).runCollect
      header <- ZIO.succeed(
                 Seq(
                   s"${Method.POST.name} /${endpoint.endpointName} ${Version.V1_1.name}",
                   "Content-Type: application/json",
                   s"Content-Length: ${body.size}"
                 ).mkString("", "\r\n", "\r\n\r\n")
               )
    } yield charSequenceToByteChunk(header) ++ body

  private def decodeResponse[O](endpoint: Endpoint[AnyF, _, _, O])(bytes: Chunk[Byte]): IO[IOException, O] =
    ZStream
      .fromChunk(bytes)
      .via(JsonCodec.decoder(endpoint.response))
      .runHead
      .catchAll(_ => ZIO.none)
      .someOrElseZIO(ZIO.fail(new IOException("Could not decode output")))

  private def charSequenceToByteChunk(chars: CharSequence): Chunk[Byte] = {
    val bytes: Seq[Byte] = for (i <- 0 until chars.length) yield chars.charAt(i).toByte
    Chunk.fromIterable(bytes)
  }
}
