package zio.web.docs

import zio.test.Assertion._
import zio.test._
import zio.web.docs.Doc.Text.{ bold, italic, strikethrough, stringToInlineElement }
import zio.test.ZIOSpecDefault

object DocTest extends ZIOSpecDefault {
  import Doc._

  override def spec =
    /**
     * - Markdown spec https://spec.commonmark.org/0.29/#preliminariesz
     * - Markdown examples taken from https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet
     */
    suite("asMarkdown(doc)")(
      test("Headings") {
        val actual =
          h1("H1") <>
            h2("H2") <>
            h3("H3") <>
            h4("H4") <>
            h5("H5") <>
            h6("H6")

        val expected =
          """# H1
	          |## H2
	          |### H3
	          |#### H4
	          |##### H5
	          |###### H6
		        |""".stripMargin

        assert(asMarkdown(actual))(equalTo(expected))
      },
      suite("Emphasis")(
        test("Italic") {
          val actual =
            p(italic("italic"))

          val expected = "*italic*\n\n"

          assert(asMarkdown(actual))(equalTo(expected))
        },
        test("Bold") {
          val actual =
            p(bold("bold"))

          val expected = "__bold__\n\n"

          assert(asMarkdown(actual))(equalTo(expected))
        },
        test("Striketrhough") {
          val actual =
            p(strikethrough("striketrhoug"))

          val expected = "~~striketrhoug~~\n\n"

          assert(asMarkdown(actual))(equalTo(expected))
        },
        test("Combined emphasis") {
          val actual =
            p(
              "Combined emphasis with " <> strikethrough("sriketrhough " <> bold("bold and " <> italic("italic")))
            )

          val expected = "Combined emphasis with ~~sriketrhough __bold and *italic*__~~\n\n"

          assert(asMarkdown(actual))(equalTo(expected))
        }
      ),
      suite("Lists")(
        )
    )
}
