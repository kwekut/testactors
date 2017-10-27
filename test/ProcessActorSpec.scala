import akka.testkit.TestKit
import akka.actor.ActorSystem
import org.specs2.mutable.SpecificationLike

class ProcessActorSpec extends TestKit(ActorSystem()) with SpecificationLike {
  import BucketCounterActorProtocol._
   
  "A Bucket Counter Actor" should {
     
    val actorProps = Props(new BucketCounterActor(Some(testActor)))
    val actor = system.actorOf(actorProps, "actor-to-test")
     
    val firstBucket = Bucket("Yo, I am a bucket", 1)
    val secondBucket = Bucket("I am another bucket", 9)
 
    "print out the name of the received buckets" in {
      actor ! firstBucket
      expectMsg(firstBucket.label)
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