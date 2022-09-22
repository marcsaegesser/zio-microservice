package zio.web.codec

import zio.schema._
import zio.stream.ZPipeline

object JsonCodec extends Codec {

  override def encoder[A](schema: Schema[A]): ZPipeline[Any, Nothing, A, Byte] = codec.JsonCodec.encoder[A](schema)

  override def decoder[A](schema: Schema[A]): ZPipeline[Any, String, Byte, A] = codec.JsonCodec.decoder[A](schema)
}
