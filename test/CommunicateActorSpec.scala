import scala.util.Random
 
import org.scalatest.{BeforeAndAfterAll,WordSpecLike,Matchers}
import akka.actor.{Actor,ActorRef,ActorSystem,Props}
import akka.testkit.{ TestActors, DefaultTimeout, ImplicitSender, TestKit }
import scala.concurrent.duration._
import scala.collection.immutable
import com.typesafe.config.ConfigFactory 

class TestKitUsageSpec extends TestKit(ActorSystem("TestKitUsageSpec",
                  ConfigFactory.parseString(TestKitUsageSpec.config)))
              with DefaultTimeout with ImplicitSender
              with WordSpecLike with Matchers with BeforeAndAfterAll {
import TestKitUsageSpec._

  override def afterAll {
    shutdown()
  } 

  val commRef = system.actorOf(Props(classOf[CommunicateActor], testcommActor))
      
    val msg = Join(user, out)
    val shp = UnJoin(user, out)
    val str = "String"

      case Join(user, out) => add(user, out) 
      case UnJoin(user, out) => remove(user) 
      case x => reader forward x

  "CommunicateActor" should {
    "Filter all messages, except expected messagetypes it receives" in {
      var messages = Seq[String]()
      within(500 millis) {
        commRef ! "test"
        expectMsg("test")
        commRef ! 1
        expectNoMsg
        commRef ! "some"
        commRef ! "more"
        commRef ! 1
        commRef ! "text"
        commRef ! 1
 
        receiveWhile(500 millis) {
          case msg: String => messages = msg +: messages
        }
      }
      messages.length should be(3)
      messages.reverse should be(Seq("some", "more", "text"))
    }
  }

  val chdRef = system.actorOf(Props(classOf[ChildCommunicateActor], testchdActor))
    case Target(user, payload) => target(user, payload)
    case Distribute(users, payload) => distribute(users, payload) 
    case BroadCast(payload) => broadcast(payload)  

  "A SequencingActor" should {
    "receive an interesting message at some point " in {
      within(500 millis) {
        ignoreMsg {
          case msg: String => msg != "something"
        }
        seqRef ! "something"
        expectMsg("something")
        ignoreMsg {
          case msg: String => msg == "1"
        }
        expectNoMsg
        ignoreNoMsg
      }
    }
  }
}
 
object TestKitUsageSpec {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "WARNING"
    }
    """
 
  /**
   * An Actor that forwards every message to a next Actor
   */
  class ForwardingActor(next: ActorRef) extends Actor {
    def receive = {
      case msg => next ! msg
    }
  }
 
  /**
   * An Actor that only forwards certain messages to a next Actor
   */
  class FilteringActor(next: ActorRef) extends Actor {
    def receive = {
      case msg: String => next ! msg
      case _           => None
    }
  }
 
  /**
   * An actor that sends a sequence of messages with a random head list, an
   * interesting value and a random tail list. The idea is that you would
   * like to test that the interesting value is received and that you cant
   * be bothered with the rest
   */
  class SequencingActor(next: ActorRef, head: immutable.Seq[String],
                        tail: immutable.Seq[String]) extends Actor {
    def receive = {
      case msg => {
        head foreach { next ! _ }
        next ! msg
        tail foreach { next ! _ }
      }
    }
  }
}