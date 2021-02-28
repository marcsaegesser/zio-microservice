package zio.web

/**
 * An `Handlers[M, R, Ids]` represents an unordered collection of handlers for endpoints with identifiers `Ids`
 * and minimum metadata `M` that collectively require an environment `R`.
 */
sealed trait Handlers[-M[_], -R, Ids] { self =>
  final def +[M1[_] <: M[_], R1 <: R, Id](
    that: Handler[M1, _, R1, _, _]
  ): Handlers[M1, R1, Ids with that.Id] = {
    type Actual   = Handlers[M, R, Ids]
    type Expected = Handlers[M1, R1, Ids]

    def cast(actual: Actual): Expected = actual.asInstanceOf[Expected]

    Handlers.Cons[M1, R1, that.Id, Ids, that.type, Handlers[M1, R1, Ids]](that, cast(self))
  }
}

object Handlers {

  final case class Cons[M1[_], R1, Id, Ids, E <: Handler.Aux[M1, _, R1, _, _, Id], T <: Handlers[M1, R1, Ids]] private[web] (
    handler: E,
    tail: T
  ) extends Handlers[M1, R1, Ids with Id]
  sealed trait Empty extends Handlers[AnyF, Any, Any]

  private[web] case object Empty extends Empty

  def apply[M1[_], R1](e1: Handler[M1, _, R1, _, _]): Handlers[M1, R1, e1.Id] =
    empty + e1

  def apply[M1[_], R1](
    e1: Handler[M1, _, R1, _, _],
    e2: Handler[M1, _, R1, _, _]
  ): Handlers[M1, R1, e1.Id with e2.Id] =
    empty + e1 + e2

  val empty: Empty = Empty
}
