package actors

import akka.actor._
import akka.actor.{ ActorRef, ActorSystem, Props, Actor }
import akka.actor.{ActorKilledException, ActorInitializationException}
import javax.inject._
import com.google.inject.name.Named
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import java.util.UUID
import org.joda.time.LocalTime
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.LocalDateTime
import scala.util.Random
import play.api.Logger
import akka.event.LoggingReceive
import play.api.libs.concurrent.Execution.Implicits._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.{Success, Failure}
import services.kafkas.{Consumer, Producer}
import services.mail._
import actors.KafkaConsumerActor._
import models._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import akka.routing._
import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import akka.pattern._
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import scala.util.Try
import scala.util.{Success, Failure}
import akka.actor.Actor
import akka.actor.ActorLogging
import scala.concurrent.Future
import akka.event.Logging
import play.api.Play.current
import org.apache.kafka.clients.producer.ProducerRecord
import akka.pattern.CircuitBreaker
import akka.pattern.pipe
import scala.concurrent.Future
import scala.language.postfixOps

object CommunicateActor{
 trait SerializeMessage {}
  case class Join(user: String, out: ActorRef) extends SerializeMessage
  case class UnJoin(user: String, out: ActorRef) extends SerializeMessage
  case class BroadCast(trgt: JsValue)
  case class Distribute(users: Set[String], trgt: JsValue)
  case class Target(user: String, trgt: JsValue)
  case class UpdateRefs(store: mutable.AnyRefMap[String, ActorRef])
  val dtz: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-mm-dd HH:MM:SS Z")
}

class CommunicateActor extends Actor {
  import CommunicateActor._
  import ChildCommunicateActor._
  import system.dispatcher
  var prompt = 0

    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = maxnrofretries, withinTimeRange = withintimerange milliseconds) {
        case aIE: ActorInitializationException => SendEmail("ActorInitializationException - CommChildActor Shut:", aIE.getMessage).send; play.api.Play.stop(current); Stop 
        case aKE: ActorKilledException => SendEmail("ActorKilledException - CommChildActor Shut:", aKE.getMessage).send; play.api.Play.stop(current); Stop
        case uE: Exception => prompter(uE); Restart
      }

    val reader: ActorRef = context.actorOf(BalancingPool(initialsize).props(ChildCommunicateActor.props),"commrouter")  
    
    def receive = {

      case Join(user, out) => add(user, out) 

      case UnJoin(user, out) => remove(user) 

      case x => reader forward x
    }

  def prompter(ex: Exception) = {
    prompt + 1
    val id = UUID.randomUUID().toString
    val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      if (prompt > maxnrofretries/maxnrofretries){
      reader ! Broadcast(Json.toJson(Notification(id, "Server Down - You may experiece some service interuption", "Spark Error", "NOTIFICATION", "Try Again Later", date)))  
        SendEmail("CommunicateChildActor Restarted", ex.getMessage).send                     
      } else if (prompt >= maxnrofretries){
      reader ! Broadcast(Json.toJson(Notification(id, "Server Down - You may experiece some service interuption", "Spark Error", "NOTIFICATION", "Try Again Later", date)))
        SendEmail("CommunicateChildActor Died", ex.getMessage).send
      //System.exit(1)
      //context.system.shutdown
      play.api.Play.stop(current)
      }
  } 

  def add(user: String, out: ActorRef): Future[String] = Future{
    store += (user -> out)
    user
  }

  def remove(user: String): Future[String] = Future{
    store -= (user)
    user
  } 
  //Continiously broadcast the Apps latest userlist to all other apps
  // Send KafkaDistribution = (AppName, Set(store ids)) to all apps via topic GRID 
    system.scheduler.schedule(initialdelay milliseconds, interval milliseconds) {
      breaker.withCircuitBreaker( 
      Future{
        val msg = Json.toJson(KafkaDistribution(AppName.appname, store.keys.toSet)).toString
        val key = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
        val producerRecord = new ProducerRecord[Array[Byte],Array[Byte]](gridtopic, gridpartition, key.getBytes("UTF8"), msg.getBytes("UTF8"))
        Producer.producer.send(producerRecord.asInstanceOf[ProducerRecord[Nothing, Nothing]]) 
      })
    }  
}

class ChildCommunicateActor extends Actor {
  import CommunicateActor._
  import ChildCommunicateActor._ 

  def receive = {

    case HealthCheck => 
      val date = dtz.parseDateTime(DateTime.now.toString("yyyy-mm-dd HH:MM:SS Z")).toString
      if (CircuitBreakerState == "Open"){
      } else if (CircuitBreakerState == "Closed"){
        sender ! ("ChildCommunicateActor" + "=" + date + ":")
      }

    case Target(user, payload) => target(user, payload)

    case Distribute(users, payload) => distribute(users, payload) 

    case BroadCast(payload) => broadcast(payload)
  }

    def findOne(user: String): Option[ActorRef] = {
      store.get(user)
    }

    def findMany(users: Set[String]): Set[ActorRef] = {
     var retVal = 
        for{ a <- users 
             (k, v) <- store if store.contains(a)
            }yield v
      retVal
    }

    def target(user: String, pload: JsValue) = Future{
      store.get(user)map(_ ! pload)
    } 

    def distribute(users: Set[String], pload: JsValue) = Future{
      for (actor <- findMany(users)) actor ! pload
    } 

    def broadcast(pload: JsValue) = Future{
      for ((user, actor) <- store) actor ! pload
    } 

}

object ChildCommunicateActor {

  var CircuitBreakerState = "Closed"
  var store: mutable.AnyRefMap[String, ActorRef] = mutable.AnyRefMap()

    val system = akka.actor.ActorSystem("system")
    val c = ConfigFactory.load()
    c.checkValid(ConfigFactory.defaultReference(), "communicateactor")
    
    val initialsize = c.getInt("communicateactor.startingRouteeNumber")
    val withintimerange = c.getDuration("communicateactor.supervisorStrategy.withinTimeRange", TimeUnit.MILLISECONDS) //should be(1 * 1000)
    val maxnrofretries = c.getInt("communicateactor.supervisorStrategy.maxNrOfRetries")  
    
    val maxfailures = c.getInt("communicateactor.breaker.maxFailures")
    val calltimeout = c.getDuration("communicateactor.breaker.callTimeout", TimeUnit.MILLISECONDS)
    val resettimeout = c.getDuration("communicateactor.breaker.resetTimeout", TimeUnit.MILLISECONDS)
    val initialdelay = c.getDuration("communicateactor.scheduler.initialDelay", TimeUnit.MILLISECONDS) 
    val interval =  c.getDuration("communicateactor.scheduler.interval", TimeUnit.MILLISECONDS)

  val gridpartition: Integer = c.getInt("kafka.gridpartition")
  val gridtopic = c.getString("kafka.gridtopic")

  def props: Props = Props(new ChildCommunicateActor())

  def notifyMeOnOpen(): Unit = {
      CircuitBreakerState = "Open"
      SendEmail(
        "CommunicateActor CircuitBreaker Alert", 
        "The CircuitBreaker opened- Connection or Back Pressure issues, Check Kafka poll timing"  + "resetTimeout: " + resettimeout + "callTimeout: " + calltimeout + "maxFailures: " + maxfailures
        ).send
    Logger.info("My CircuitBreaker is now open for a while" + "resetTimeout: " + resettimeout + "callTimeout: " + calltimeout + "maxFailures: " + maxfailures) 
  }
  def notifyMeOnClose(): Unit = {
      CircuitBreakerState = "Closed"
      SendEmail(
        "CommunicateActor CircuitBreaker Alert", 
        "The CircuitBreaker closed- Back to good, Check Kafka poll timing"  + "resetTimeout: " + resettimeout + "callTimeout: " + calltimeout + "maxFailures: " + maxfailures
        ).send
    Logger.info("My CircuitBreaker is now closed for a while" + "resetTimeout: " + resettimeout + "callTimeout: " + calltimeout + "maxFailures: " + maxfailures) 
  }
  val breaker =
    new CircuitBreaker(system.scheduler,
      maxFailures = maxfailures,
      callTimeout = calltimeout milliseconds,
      resetTimeout = resettimeout milliseconds)

  breaker.onClose(notifyMeOnClose())
  breaker.onOpen(notifyMeOnOpen())
}

