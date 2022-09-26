package zio.web.example

import zio._
import zio.schema._
import zio.web.{ Handler, endpoint }
import zio.web.codec.JsonCodec
// import zio.web.http.{ HttpMiddleware, HttpProtocolModule }
import zio.web.http.HttpProtocolModule
import zio.web.http.model.{ Method, Route }
import zio.Console
// import java.io.IOException

object UsersExample extends HttpProtocolModule {

  val allProtocols    = Map.empty
  val defaultProtocol = JsonCodec

  sealed case class UserId(id: String)
  sealed case class UserProfile(age: Int, fullName: String, address: String)

  sealed trait Status
  case class Ok(code: Int)                                               extends Status
  case class Failed(code: Int, reason: String, field3: Int, field4: Int) extends Status
  case object Pending                                                    extends Status

  implicit val statusSchema: Schema[Status] = DeriveSchema.gen

  val userJoe: UserId = UserId("123123")

  val userIdSchema: Schema[UserId] = DeriveSchema.gen

  val userProfileSchema: Schema[UserProfile] = DeriveSchema.gen

  lazy val inMemoryDb = scala.collection.mutable.Map(
    UserId("123123") -> UserProfile(42, "Joe Doe", "Fairy Street 13, Fantasyland"),
    UserId("22")     -> UserProfile(18, "Mary Sue", "5 The Elms, Swampland")
  )

  val getUserProfile =
    endpoint("getUserProfile")
      .withRequest(userIdSchema)
      .withResponse(userProfileSchema.?) @@ Route("/users/") @@ Method.GET

  val setUserProfile =
    endpoint("setUserProfile")
      .withRequest(userIdSchema.zip(userProfileSchema))
      .withResponse(Schema[Unit]) @@ Route("/users/{id}") @@ Method.POST

  val userService = getUserProfile + setUserProfile

  val getUserProfileHandler =
    Handler.make(getUserProfile) { id =>
      (for {
        _       <- Console.printLine(s"Handling getUserProfile request for $id")
        profile = inMemoryDb.get(id)
      } yield profile).catchAll(_ => ZIO.succeed(None))
    }

  val setUserProfileHandler =
    Handler.make(setUserProfile) {
      // for Scala 2.12 we need to hint the compiler about the types
      // for 2.13 and above the compiler is able to infer this information
      input: (UserId, UserProfile) =>
        val (id, profile) = input
        (for {
          _ <- Console.printLine(s"Handling setUserProfile request for $id and $profile")
          _ = inMemoryDb.update(id, profile)
        } yield ()).catchAll(_ => ZIO.succeed(()))
    }

  val userServiceHandlers = getUserProfileHandler + setUserProfileHandler

  // client example
  // lazy val userProfile = userService.invoke(getUserProfile)(userJoe).provideLayer(makeClient(userService))

  // server example
  // lazy val userServerLayer = makeServer(HttpMiddleware.none, userService, userServiceHandlers)

  // docs example
  lazy val docs = makeDocs(userService)
}
