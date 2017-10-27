package actors

import akka.actor._
import javax.inject._
import com.google.inject.name.Named
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Logger
import java.util.UUID
import akka.event.LoggingReceive
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import com.typesafe.config.ConfigFactory
import scala.language.postfixOps
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.Try
import scala.util.{Success, Failure}
import akka.actor.SupervisorStrategy._
import services.mail._
import akka.pattern._
import akka.routing._
import akka.pattern._
import akka.pattern.pipe
import actors.CommunicateActor._
import actors.WebSocketActor._
import actors.SparkActor._
import actors.KafkaProducerActor._
import services.stripe.{ StripeService, StripeImpl }
import models._
import play.api.Play.current

object ProcessActor {
}
object ProcConfig {
  val c = ConfigFactory.load()
  c.checkValid(ConfigFactory.defaultReference(), "processactor")
	val topicName = c.getString("kafka.msgtopic")
	val source = c.getString("kafka.source")
	val genre = c.getString("kafka.genre")
	val initialsize = c.getInt("processactor.startingRouteeNumber")
    val withintimerange = c.getDuration("processactor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
    val maxnrofretries = c.getInt("processactor.supervisorStrategy.maxNrOfRetries") 
    val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z") 
}

class ProcessActor @Inject() (
			@Named("kafkaproducer-actor") kprodActor: ActorRef, 
			@Named("spark-actor") sparkActor: ActorRef,
			@Named("communicate-actor") commActor: ActorRef, 
			stripeSer: StripeService) extends Actor {
    import ProcConfig._
    implicit val ec = context.dispatcher
   var prompt = 0

    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = maxnrofretries, withinTimeRange = withintimerange milliseconds) {
        case aIE: ActorInitializationException => SendEmail("ActorInitializationException - ProcessChildActor Shut:", aIE.getMessage).send; play.api.Play.stop(current); Stop 
        case aKE: ActorKilledException => SendEmail("ActorKilledException - ProcessChildActor Shut:", aKE.getMessage).send; play.api.Play.stop(current); Stop 
        case uE: Exception => prompter(uE); Restart
      }

    val process: ActorRef = context.actorOf(
      BalancingPool(initialsize).props(ProcessChildActor.props(kprodActor, sparkActor, stripeSer)), "processactorrouter")  
    
    def receive = {
            case x => process forward x
    }

  def prompter(ex: Exception) = {
    prompt + 1
    val id = UUID.randomUUID().toString
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      if (prompt > maxnrofretries/maxnrofretries){
        commActor ! BroadCast(Json.toJson(Notification(id, "Server Unresponsive - You may experiece some service interuption", "Error", "NOTIFICATION", "Try Again Later", date)))  
        SendEmail("ProcessChildActor Restarted", ex.getMessage).send                     
      } else if (prompt >= maxnrofretries){
      	commActor ! BroadCast(Json.toJson(Notification(id, "Server Unresponsive - You may experiece some service interuption", "Error", "NOTIFICATION", "Try Again Later", date)))
        SendEmail("ProcessChildActor Died", ex.getMessage).send
      play.api.Play.stop(current)
      }
  }
}

object ProcessChildActor {
  def props(kprodActor: ActorRef, sparkActor: ActorRef, stripeSer: StripeService): Props = 
  	  Props(new ProcessChildActor(kprodActor, sparkActor, stripeSer))
}

class ProcessChildActor (kprodActor: ActorRef, sparkActor: ActorRef, 
							stripeSer: StripeService) extends Actor {
	import ProcConfig._
	import context.dispatcher

  override def preRestart(reason: Throwable, message: Option[Any]) {
    val id = UUID.randomUUID().toString
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
    message match {
      case Some(SocToProcUsrMsg(activity, out, user, msg)) => notify(out)
      case Some(SparkToProcUsrMsgShop(str, out, user, shop, msg)) => notify(out)
      case None => Logger.info("Internal object Failure - ProcessChildActor Unprocessesable message:" + Try(message.toString).toOption.toString)
      case x => Logger.info("Internal object Failure - ProcessChildActor Unprocessesable message:" + Try(message.toString).toOption.toString)
    }
    def notify(out: ActorRef) =  out ! Json.toJson(Notification(id, "Unprocessesable Request - Check you entries and try again", "Error", "NOTIFICATION", "Try Again Later", date))
    	Logger.info("Socket send Sucess  - ProcessChildActor Unprocessesable message:" + Try(message.toString).toOption.toString) 
  }

  def receive = LoggingReceive {  

    case HealthCheck => 
        val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        sender ! ("ChildProcessActor" + "=" + date + ":")
//Admin- Save draft/Preview draft/Edit draft/Make Live Draft/Delete Draft
//Admin- List Templates/List saved drafts/
// NB: Shop Owner doesn't need to reply to his own forms
//Home - Only needs to see his Live Forms, Reply to customer Mails and customer Replies to his Forms
// Event is ommited because - you dont reply to Event messages
// Reply- Using a shop's  DRAFT form to send a message to shop 
    case SocToProcUsrMsg(activity, out, user, msg) => 
		val id = UUID.randomUUID().toString
		val ids = Seq(msg.from, msg.to)
		val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString

		if (msg.activity == "REPLY" && msg.typ == "MAIL") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )
		
		} else if (msg.activity == "REPLY" && msg.typ == "RESERVATION") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )

		} else if (msg.activity == "REPLY" && msg.typ == "PROMO") {
			if (msg.expiry == "eternal" || msg.expiry == "Eternal"){ 
				sparkActor ! ProcToSparkUsrMsg("GetShopToken", out, self, user, msg) 
			} else if (msg.expiry != "expired" || msg.expiry == "Expired") {
				out ! Json.toJson(Notification(id, "Promo is no more running", "Cannot reply to expired promo", "NOTIFICATION", "Search for another promo", date))
			} else {
				val now: DateTime = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z"))
				val expires: DateTime = dtz.parseDateTime(msg.expiry)
					if (expires.isBefore(now)) {
						sparkActor ! ProcToSparkUsrMsg("GetShopToken", out, self, user, msg)
					} else {
						out ! Json.toJson(Notification(id, "Offer is no more running", "Cannot reply to expired offer", "NOTIFICATION", "Search for another product", date))
					}				
			}
		} else if (msg.activity == "REPLY" && msg.typ == "ORDER") {
			if (msg.expiry == "eternal" || msg.expiry == "Eternal"){ 
				sparkActor ! ProcToSparkUsrMsg("GetShopToken", out, self, user, msg) 
			} else if (msg.expiry != "expired" || msg.expiry == "Expired") {
				out ! Json.toJson(Notification(id, "Offer is no more running", "Cannot reply to expired product offer", "NOTIFICATION", "Search for another product", date))
			} else {
				out ! Json.toJson(Notification(id, "Unknown expiry status", "Contact the seller for product expiry status", "NOTIFICATION", "Contact seller", date))
			}
		} else if (msg.activity == "REPLY" && msg.typ == "EVENT") {
			// Do nothing - Cant reply to events
			
		} else if (msg.activity == "BROADCASTFEED") {
			sparkActor ! ProcToSparkUsrMsg("BROADCASTFEED", out, self, user, msg) 
              //Broadcast newly created promos/events to followers via kafka        
        } else if (msg.activity == "PREVIEWFEED") {
            sparkActor ! ProcToSparkUsrMsg("PREVIEWFEED", out, self, user, msg)  

        } else if (msg.activity == "SAVEFEED") {
            sparkActor ! ProcToSparkUsrMsg("SAVEFEED", out, self, user, msg)
            Logger.info("SAVEFEED in ProcessActor Called")              		
		} else {}
/////////////////////////////////////FORMS////////////////////////////////////////
// Create a form for customers to contact shop. Save in shops forms
// out is the socket actoref - replying directly to device,While self is processactor actoref - expecting a reply
// Event forms, sent globally. Events have no replies
// Create a promo form. Save it locally(shops forms) and globally (shops post and followers posts)
// Tell spark to create promo and also get the shops followers who will recieve the promo after its created

// Sent throguh kafka so both sides have a copy of the exchange									
//  Reply-   Apply Promo - only if promo is not expired, then get token
//  Only called if the above  get promo token is sucessfull	
// Pushed through kafka so its saved by both parties
// //Reply - Order	gets token first, then calls stripe below										
// Pushed through kafka so its saved by both parties		
 	case SparkToProcUsrMsgShop("RecieveShopToken", out, user, shop, msg) =>
    	val shoptoken = shop.shoptoken.get
    	val usertoken = user.token
    	val amt = java.lang.Integer.parseInt(msg.price)
			stripeSer.chargeCustomer(shoptoken, amt).map { cCResponse =>
				val fdback = cCResponse.status + " : " + cCResponse.amount + " : " + cCResponse.created
				val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
				val id = UUID.randomUUID().toString
				val ids = Seq(msg.from, msg.to)

		if (msg.activity == "REPLY" && msg.typ == "PROMO") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )		
	 		
	 	} else if (msg.activity == "REPLY" && msg.typ == "ORDER") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )		
	 	} else {}	

	 	}

// After saving of a Promo or Event (in SparkActor), it sent here to be forwarded to all
// followers via kafka
// Return from above message calls this. Followers include ownself
// You can only broadcst promotions and events. Use promo's for new product intro.
// Reason: To have control of feeds which should not be saved by user as posts- 
// Events and promo have expiry so witrawal is automatic/ else bulk mail to inform
 	case SparkToProcUsrMsgShop("BroadcastFeedToFollowers", out, user, shop, msg) =>
		val id = UUID.randomUUID().toString
		val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
		val ids = shop.shopfollowers.toSeq

		if (msg.typ == "PROMO") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			msg.id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )

		} else if (msg.typ == "EVENT") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			msg.id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )
		} else if (msg.typ == "MAIL") {
		kprodActor ! JsonProduce( topicName, KafkaMessage( ids, source, genre, 
			msg.id, msg.from, msg.to, msg.typ, msg.activity, msg.id, msg.category, msg.title, 
			msg.summary, msg.detail, msg.options, msg.price, msg.expiry, 
			msg.picurl, msg.fdback, msg.read, msg.likes, msg.template, date) )
		} else {out ! Json.toJson(Notification(id, "Feed cannot be broadcast", "Feed type not eligible for broadcast", "NOTIFICATION", "Try a different type", date))}				
// NB: the followers returned include ownself
// Reservation forms
// Saved only locally in shops forms so owner has complete control over, deleting, editing, withdrawal.
// Feedback on creation is a direct notification to users device




	}
}
