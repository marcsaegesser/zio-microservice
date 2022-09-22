package zio.web.http

import zio._

import zio.stream.{ ZSink, ZStream }
import zio.web.http.HttpMiddleware._
import zio.web.http.auth.BasicAuth
import zio.web.http.auth.BasicAuth.AuthResult.{ Denied, Granted }
import zio.web.http.auth.BasicAuth.{ AuthParams, AuthResult }

import java.io.{ FileOutputStream, IOException, OutputStream }
import java.time.format.DateTimeFormatter
import zio.Clock.currentDateTime

package object middleware {

  def logging[R, E](sink: ZSink[R, E, String, Byte, Long]): HttpMiddleware[R, Nothing] = {
    val formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z")

    HttpMiddleware(
      for {
        queue  <- Queue.bounded[String](128)
        stream = ZStream.fromQueue(queue)
        _      <- stream.run(sink).fork
      } yield Middleware(
        request(HttpRequest.Method.zip(HttpRequest.URI).zip(HttpRequest.Version).zip(HttpRequest.IpAddress)) {
          case (((method, uri), version), ipAddr) =>
            val ipStr = ipAddr.fold("-")(_.getHostAddress)
            currentDateTime
              .map(
                now => {
                  val time = now.format(formatter)
                  s"""$ipStr - - [$time] "$method $uri $version""""
                }
              )
        },
        Response(
          HttpResponse.StatusCode.zip(HttpResponse.Header("Content-Length")),
          (state: String, resp: (Int, String)) => queue.offer(s"$state ${resp._1} ${resp._2}\n").as(Patch.empty)
        )
      )
    )
  }

  def fileLogging[R](path: String): HttpMiddleware[Any, Nothing] =
    logging(fileSink(path))

  private[middleware] def fileSink(path: String): ZSink[Any, Throwable, String, Byte, Long] = {
    val stream: ZIO[Scope, IOException, OutputStream] =
      ZIO
        .fromAutoCloseable(
          ZIO.attempt(new FileOutputStream(path, true))
        )
        .refineToOrDie[IOException]

    ZSink
      .fromOutputStreamScoped(stream)
      .contramapChunks[String](_.flatMap(str => Chunk.fromIterable(str.getBytes)))
  }

  def basicAuth[R, E](realm: String, authenticate: AuthParams => ZIO[R, E, AuthResult]): HttpMiddleware[R, E] =
    HttpMiddleware(
      ZIO.succeed(
        Middleware(
          request(HttpRequest.Header("Authorization").orElseEither(HttpRequest.Succeed)) {
            case Left(header) =>
              AuthParams.create(realm, header) match {
                case Some(params) =>
                  authenticate(params).mapBoth(e => (Option(Denied), e), g => Option(g))
                case None =>
                  ZIO.succeed(None)
              }
            case Right(_) => ZIO.succeed(None)

          },
          Response[R, E, Option[AuthResult], Int](
            HttpResponse.StatusCode,
            (authResult: Option[AuthResult], _: Int) =>
              ZIO.succeed(
                authResult.fold(BasicAuth.unauthorized(realm)) {
                  case Granted => Patch.empty
                  case Denied  => BasicAuth.forbidden
                }
              )
          )
        )
      )
    )

  def rateLimiter(n: Int): HttpMiddleware[Any, None.type] =
    HttpMiddleware(
      Ref
        .make[Int](0)
        .flatMap(
          ref =>
            ZIO.succeed {
              Middleware(
                Request(
                  HttpRequest.Succeed,
                  (_: Unit) =>
                    ref.modify { old =>
                      if (old < n) (ZIO.succeed(true), n + 1) else (ZIO.fail(false -> None), n)
                    }.flatten
                ),
                Response(
                  HttpResponse.Succeed,
                  (flag: Boolean, _: Unit) => ref.update(_ - 1).when(flag).as(Patch.empty)
                )
              )
            }
        )
    )
}
