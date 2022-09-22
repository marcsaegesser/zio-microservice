package zio.web.http.internal

import zio.test.Assertion._
import zio.test.TestAspect.samples
import zio.test._
import zio.web.http.internal.HttpLexer.HeaderParseError._
import zio.web.http.internal.HttpLexer.{ TokenChars, parseHeaders }
import zio.web.http.model.{ Method, Version }
import zio.{ ZIO, Chunk, ZLayer }

import java.io.{ Reader, StringReader }
import scala.util.{ Random => ScRandom }
import zio.Random
import zio.test.ZIOSpecDefault

object HttpLexerSpec extends ZIOSpecDefault {

  // override def spec =
  //   suite("All tests")(List(startLineSuite, headerSuite))

  override def spec = Spec.multiple(Chunk(startLineSpec, headerSpec))

  def startLineSpec = suite("HTTP start line parsing")(
    test("check OPTIONS method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("OPTIONS /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.OPTIONS))
    },
    test("check GET method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("GET /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.GET))
    },
    test("check HEAD method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("HEAD /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.HEAD))
    },
    test("check POST method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("POST /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.POST))
    },
    test("check PUT method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("PUT /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.PUT))
    },
    test("check PATCH method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("PATCH /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.PATCH))
    },
    test("check DELETE method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("DELETE /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.DELETE))
    },
    test("check TRACE method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("TRACE /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.TRACE))
    },
    test("check CONNECT method") {
      val startLine = HttpLexer.parseStartLine(new StringReader("CONNECT /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.method)(equalTo(Method.CONNECT))
    },
    test("check HTTP 1.1 version") {
      val startLine = HttpLexer.parseStartLine(new StringReader("POST /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.version)(equalTo(Version.V1_1))
    },
    test("check HTTP 2 version") {
      val startLine = HttpLexer.parseStartLine(new StringReader("POST /hello.htm HTTP/2.0\r\nheaders and body"))
      assert(startLine.version)(equalTo(Version.V2))
    },
    test("check long URI") {
      val longString = "a" * 2021
      val startLine = HttpLexer.parseStartLine(
        new StringReader(s"POST https://absolute.uri/$longString HTTP/2.0\r\nheaders and body")
      )
      assert(startLine.version)(equalTo(Version.V2))
    },
    test("check too long URI") {
      val longString = "a" * 2028
      val result = ZIO.attempt(
        HttpLexer
          .parseStartLine(new StringReader(s"POST https://absolute.uri/$longString HTTP/2.0\r\nheaders and body"))
      ).exit
      assertZIO(result)(fails(isSubtype[IllegalStateException](hasMessage(equalTo("Malformed HTTP start-line")))))
    },
    test("check corrupted HTTP request (no space)") {
      val result = ZIO.attempt(HttpLexer.parseStartLine(new StringReader("POST/hello.htm HTTP/2.0\r\nheaders and body"))).exit
      assertZIO(result)(fails(isSubtype[IllegalStateException](hasMessage(equalTo("Malformed HTTP start-line")))))
    },
    test("check corrupted HTTP request (double CR)") {
      val result =
        ZIO.attempt(HttpLexer.parseStartLine(new StringReader("POST /hello.htm HTTP/2.0\r\r\nheaders and body"))).exit
      assertZIO(result)(fails(isSubtype[IllegalStateException](hasMessage(equalTo("Malformed HTTP start-line")))))
    },
    test("check corrupted HTTP request (random string)") {
      val result = ZIO.attempt(HttpLexer.parseStartLine(new StringReader(new ScRandom().nextString(2048)))).exit
      assertZIO(result)(fails(isSubtype[IllegalStateException](hasMessage(equalTo("Malformed HTTP start-line")))))
    },
    test("check corrupted HTTP request (very long random string)") {
      val result = ZIO.attempt(HttpLexer.parseStartLine(new StringReader(new ScRandom().nextString(4096000)))).exit
      assertZIO(result)(fails(isSubtype[IllegalStateException](hasMessage(equalTo("Malformed HTTP start-line")))))
    },
    test("check invalid HTTP method") {
      val result = ZIO.attempt(HttpLexer.parseStartLine(new StringReader("GRAB /hello.htm HTTP/2.0\r\nheaders and body"))).exit
      assertZIO(result)(fails(isSubtype[IllegalArgumentException](hasMessage(equalTo("Unable to handle method: GRAB")))))
    },
    test("check invalid HTTP version") {
      val result = ZIO.attempt(HttpLexer.parseStartLine(new StringReader("POST /hello.htm HTTP2.0\r\nheaders and body"))).exit
      assertZIO(result)(
        fails(isSubtype[IllegalArgumentException](hasMessage(equalTo("Unable to handle version: HTTP2.0"))))
      )
    },
    test("check empty input") {
      val result = ZIO.attempt(HttpLexer.parseStartLine(new StringReader(""))).exit
      assertZIO(result)(fails(isSubtype[IllegalStateException](hasMessage(equalTo("Malformed HTTP start-line")))))
    },
    test("check URI") {
      val startLine = HttpLexer.parseStartLine(new StringReader("OPTIONS /hello.htm HTTP/1.1\r\nheaders and body"))
      assert(startLine.uri.toString)(equalTo("/hello.htm"))
    }
  )

  final case class HeaderLines(s: String) extends AnyVal {
    def toStringWithCRLF: String = s.stripMargin.replaceAll("\n", "\r\n") + "\r\n\r\n"
  }

  private val TestHeaderSizeLimit = 50

  private lazy val failureScenarios =
    Gen.fromIterable(
      Seq(
        ""                              -> UnexpectedEnd,
        "\r"                            -> ExpectedLF(-1),
        "a"                             -> UnexpectedEnd,
        "a:"                            -> UnexpectedEnd,
        "a: "                           -> UnexpectedEnd,
        "a: b"                          -> UnexpectedEnd,
        "a: b\r"                        -> ExpectedLF(-1),
        "a: b\r\n"                      -> UnexpectedEnd,
        "a: b\r\na"                     -> UnexpectedEnd,
        "space-after-header-name : ..." -> InvalidCharacterInName(' '),
        "X-EnormousHeader: " +
          "x" * TestHeaderSizeLimit -> HeaderTooLarge,
        // TODO: handling of this case could be improved, as the spec allows for
        //       multiline headers, even though that construct is deprecated
        // "A server that receives an obs-fold in a request message that is not
        //  within a message/http container MUST either reject the message by
        //  sending a 400 (Bad Request), preferably with a representation
        //  explaining that obsolete line folding is unacceptable, or replace
        //  each received obs-fold with one or more SP octets prior to
        //  interpreting the field value or forwarding the message downstream."
        // https://tools.ietf.org/html/rfc7230#section-3.2.4
        multilineHeader.toStringWithCRLF -> InvalidCharacterInName(' ')
      )
    )

  private lazy val multilineHeader =
    HeaderLines("""foo: obsolete
                  |     multiline
                  |     header""")

  private lazy val headerName = Gen.string1(Gen.elements(TokenChars: _*))

  private lazy val headerValue = Gen.string(whitespaceOrPrintableOrExtended).map(_.trim)

  private lazy val whitespaceOrPrintableOrExtended =
    Gen.elements('\t' +: (' ' to 0xff): _*)

  private lazy val optionalWhitespace = Gen.string(Gen.elements(' ', '\t'))

  private def duplicateSome[R1, R2 >: R1, A](
    as: Iterable[A],
    factor: Gen[R2, Int]
  ): Gen[R2, List[A]] = {
    val listOfGenDuplicates: Iterable[Gen[R2, List[A]]] =
      as.map { a =>
        Gen
          .const(a)
          .zipWith(factor)((a, factor) => List.fill(factor)(a))
      }
    Gen
      .collectAll(listOfGenDuplicates)
      .map(_.flatten)
  }

  private lazy val duplicationFactor =
    Gen.weighted(
      Gen.const(1) -> 90,
      Gen.const(2) -> 8,
      Gen.const(3) -> 2
    )

  private def selectSome[A](as: Iterable[A], decide: Gen[Random, Boolean]) =
    as.map(Gen.const(_))
      .foldLeft(Gen.const(List.empty[A]): Gen[Random, List[A]]) { (acc, genA) =>
        for {
          decision <- decide
          a        <- genA
          as       <- acc
        } yield if (decision) a :: as else as
      }

  private def selectSome1[A](as: Iterable[A], decide: Gen[Random, Boolean] = Gen.boolean) =
    selectSome(as, decide).flatMap {
      case Nil        => Gen.elements(as.toSeq: _*).map(List(_))
      case selectedAs => Gen.const(selectedAs)
    }

  val testGen =
    for {
      distinctHeaderNames <- Gen.setOf(headerName)
      headerNamesWithDups <- duplicateSome(distinctHeaderNames, duplicationFactor)
      headerNames         <- Gen.fromRandom(_.shuffle(headerNamesWithDups))
      headerValues        <- Gen.listOfN(headerNames.size)(headerValue)
      owss                <- Gen.listOfN(headerNames.size * 2)(optionalWhitespace)
      body                <- Gen.alphaNumericString
      absentHeaderNames   <- Gen.setOf(headerName)
      headersToExtract <- selectSome1(distinctHeaderNames ++ absentHeaderNames)
                           .map(headerNames => headerNames.map(_.toLowerCase).distinct)
    } yield {
      val pairedOwss = owss.grouped(2).map(list => list.head -> list.tail.head).toSeq

      val headerLines =
        headerNames
          .zip(headerValues)
          .zip(pairedOwss)
          .map {
            case ((name, value), (leftOws, rightOws)) =>
              s"$name:$leftOws$value$rightOws\r\n"
          }

      val headerNameToValuesMap: Map[String, List[String]] =
        headerNames
          .zip(headerValues)
          .groupBy(_._1.toLowerCase)
          .map { case (k, kvs) => k -> kvs.map(_._2) }

      val extractedHeaders =
        headersToExtract.map { k =>
          Chunk.fromIterable(
            headerNameToValuesMap.getOrElse(k, List.empty)
          )
        }

      (
        headerLines.mkString + "\r\n" + body,
        headersToExtract,
        body,
        extractedHeaders
      )
    }

  def headerSpec =
    suite("http header lexer")(
      test("generated positive cases") {
        check(testGen) {
          case (msg, headersToExtract, expectedBody, expectedHeaders) =>
            val reader        = new StringReader(msg)
            val actualHeaders = parseHeaders(headersToExtract.toArray, reader).toSeq
            val actualBody    = mkString(reader)
            assert(actualHeaders)(hasSameElements(expectedHeaders)) &&
            assert(actualBody)(equalTo(expectedBody))
        }
      } @@ samples(1000),
      test("failure scenarios") {
        check(failureScenarios) {
          case (request, expectedError) =>
            assertZIO(
              ZIO.attempt(
                parseHeaders(
                  Array("some-header"),
                  new StringReader(request),
                  TestHeaderSizeLimit
                )
              ).exit
            )(fails(equalTo(expectedError)))
        }
      }
    ).provideLayer(ZLayer.succeed(Random.RandomLive))

  private def mkString(reader: Reader) = {
    var c       = -1
    val builder = new StringBuilder()
    while ({ c = reader.read(); c != -1 }) builder.append(c.toChar)
    builder.toString
  }
}
