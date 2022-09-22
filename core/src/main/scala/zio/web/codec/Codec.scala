package zio.web.codec

import zio.schema._

import zio.stream.ZPipeline

trait Codec {
  def encoder[A](schema: Schema[A]): ZPipeline[Any, Nothing, A, Byte]
  def decoder[A](schema: Schema[A]): ZPipeline[Any, String, Byte, A]
}
