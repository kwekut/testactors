package controllers

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import com.mohiva.play.silhouette.api.{ Environment, LogoutEvent, Silhouette }
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator
//import com.mohiva.play.silhouette.api.services.AuthInfoService
import models.services.UserService
import models.User
import play.api.Logger
import play.api.i18n.MessagesApi
import javax.inject.Inject
import com.google.inject.name.Named
import akka.actor.ActorRef
import java.util.UUID
import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import actors.WebSocketActor
import actors.CommunicateActor._
import actors.SparkActor._
import models.daos._
import models._

class WebSocketController @Inject() (
            val env: Environment[User, JWTAuthenticator],
            val userService: UserService,
            val messagesApi: MessagesApi,
            val authenticatorService: TailoredJWTAuthenticatorService,
                @Named("process-actor") prossActor: ActorRef,
                @Named("spark-actor") sparkActor: ActorRef,
                @Named("communicate-actor") commActor: ActorRef )
                              extends Silhouette[User, JWTAuthenticator] {


  def socket(token: String) = WebSocket.tryAcceptWithActor[JsValue, JsValue] { request =>
    implicit val req = Request(request, AnyContentAsText(token))
    authenticatorService.retrieve flatMap {
      case None => Future(Left(Forbidden))
      case Some(auth) => 
          userService.retrieve(auth.loginInfo).map {
              case Some(user) =>
                  Right(WebSocketActor.props(Some(user), Some(commActor), Some(prossActor), Some(sparkActor)) _.toOpton)
              case None => Left(Forbidden)
          }
           
    }
  }

}  








