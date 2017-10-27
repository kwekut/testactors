package actors

import javax.inject.Inject
import com.google.inject.name.Named
import akka.actor.{ ActorRef, ActorSystem, Props, Actor }
import java.util.UUID
import org.joda.time.LocalDateTime
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import actors.CommunicateActor._
import actors.ProcessActor._
import play.api.Logger
import akka.event.LoggingReceive
import models._


object WebSocketActor {
  def props(user: Option[User] = None, commActor: Option[ActorRef] = None, prossActor: Option[ActorRef] = None, sparkActor: Option[ActorRef] = None)(out: Option[ActorRef] = None) = Props(new WebSocketActor(user, commActor, prossActor, sparkActor, out))
}

class WebSocketActor(user: Option[User] = None, commActor: Option[ActorRef] = None, prossActor: Option[ActorRef] = None, sparkActor: Option[ActorRef] = None, out: Option[ActorRef] = None, listener: Option[ActorRef] = None) extends Actor {

  import WebSocketActor._

  override def preStart() {
    commActor map (_ ! Join(user.userid, out))
  }
  override def postStop() {
    commActor map (_ ! UnJoin(user.userid, out))
  }

  def receive = LoggingReceive {

    case js: JsValue => Logger.info(s"websocket recieved some json: $js")
      
      js.validate[Msg] match {
        case error: JsError => Logger.error("couldn't parse json as Msg") //; out ! Msg("Notification")
        case sucess: JsSuccess[Msg] =>  val msg = sucess.getOrElse(Msg("id", "from", "to", "typ", "activity", "draftid", "category", "title", "summary", "detail", "options", "price", "expiry", "picurl", "fdback", false, "likes", "template", "created"))
                    prossActor map (_ ! SocToProcUsrMsg(msg.activity, out, user, msg))
                    listener.map(_ ! msg)
      }

      js.validate[Shp] match {
        case error: JsError => Logger.error("couldn't parse json as Shp") //; out ! Msg("Notification")
        case sucess: JsSuccess[Shp] =>  val shp = sucess.getOrElse(Shp("id", "name", "category", "typ", "activity", "summary", "about", "email", "phone", "address", "latitude", "longitude", "picurl", "likes", "template", "date"))
                    sparkActor map (_ ! SocToSparkUsrShp(shp.activity, out, user, shp))
                    listener.map(_ ! shp)
      }                                 

      js.validate[Links] match {
        case error: JsError => Logger.error("couldn't parse json in as Links")//Notify
        case sucess: JsSuccess[Links] =>  val lnk = sucess.getOrElse(Links("id", "userid", "shopid", "activity", "feedid", "filter"))
                    sparkActor map (_ !  SocToSparkUsrLnk(lnk.activity, out, user, lnk))
                    listener.map(_ ! lnk)
      }


  }
}



