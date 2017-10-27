import akka.testkit.TestKit
import akka.actor.ActorSystem
import org.specs2.mutable.SpecificationLike

class WebSocketActorSpec extends TestKit(ActorSystem()) with SpecificationLike {
  import ProcessActor._
   
  "WebSocketActor Actor Accept" should {
     
    val actorProps = Props(new WebSocketActor(Some(testActor)))
    val actor = system.actorOf(actorProps, "actor-to-test")
     
    val msg = Msg("id", "from", "to", "typ", "activity", "draftid", "category", "title", "summary", "detail", "options", "price", "expiry", "picurl", "fdback", false, "likes", "template", "created")
    val shp = Shp("id", "name", "category", "typ", "activity", "summary", "about", "email", "phone", "address", "latitude", "longitude", "picurl", "likes", "template", "date")
    val lnk = Links("id", "userid", "shopid", "activity", "feedid", "filter")
    val str = "String"
 
    "print out the name of the received buckets" in {
      actor ! msg
      expectMsg(msg.from == "from")
      actor ! shp
      expectMsg(shp.category == "category")
      actor ! lnk
      expectMsg(lnk.shopid == "shopid")
      actor ! str
      expectMsg()
      actor ! secondBucket
      expectMsg(secondBucket.label)
      success
    }
     
    "accumulate the quantity of buckets received" in {
      actor ! GetCounter(testActor)
      expectMsg(10)
      success
    }
  }

