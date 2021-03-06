/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.scalatest.{ WordSpec, BeforeAndAfterAll, Tag }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ ActorSystem, ActorSystemImpl }
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.MessageDispatcher
import akka.event.{ Logging, LoggingAdapter }
import akka.util.duration._
import akka.dispatch.FutureTimeoutException
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.PoisonPill
import java.util.concurrent.LinkedBlockingQueue
import akka.actor.CreateChild
import akka.actor.DeadLetter

object TimingTest extends Tag("timing")

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            core-pool-size-factor = 2
          }
        }
      }
      """)

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName: String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*AkkaSpec.?$")
    s.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName, config.withFallback(AkkaSpec.testConf)))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName, AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    system.stop()
    try system.asInstanceOf[ActorSystemImpl].terminationFuture.await(5 seconds) catch {
      case _: FutureTimeoutException ⇒ system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
    }
    atTermination()
  }

  protected def atStartup() {}

  protected def atTermination() {}

  def spawn(body: ⇒ Unit)(implicit dispatcher: MessageDispatcher) {
    system.actorOf(Props(ctx ⇒ { case "go" ⇒ try body finally ctx.self.stop() }).withDispatcher(dispatcher)) ! "go"
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaSpecSpec extends WordSpec with MustMatchers {

  "An AkkaSpec" must {

    "terminate all actors" in {
      // verbose config just for demonstration purposes, please leave in in case of debugging
      import scala.collection.JavaConverters._
      val conf = Map(
        "akka.actor.debug.lifecycle" -> true, "akka.actor.debug.event-stream" -> true,
        "akka.loglevel" -> "DEBUG", "akka.stdout-loglevel" -> "DEBUG")
      val system = ActorSystem("AkkaSpec1", ConfigFactory.parseMap(conf.asJava).withFallback(AkkaSpec.testConf))
      val spec = new AkkaSpec(system) {
        val ref = Seq(testActor, system.actorOf(Props.empty, "name"))
      }
      spec.ref foreach (_.isTerminated must not be true)
      system.stop()
      spec.awaitCond(spec.ref forall (_.isTerminated), 2 seconds)
    }

    "must stop correctly when sending PoisonPill to rootGuardian" in {
      val system = ActorSystem("AkkaSpec2", AkkaSpec.testConf)
      val spec = new AkkaSpec(system) {}
      val latch = new TestLatch(1)(system)
      system.registerOnTermination(latch.countDown())

      system.actorFor("/") ! PoisonPill

      latch.await(2 seconds)
    }

    "must enqueue unread messages from testActor to deadLetters" in {
      val system, otherSystem = ActorSystem("AkkaSpec3", AkkaSpec.testConf)

      try {
        var locker = Seq.empty[DeadLetter]
        implicit val timeout = system.settings.ActorTimeout
        implicit val davyJones = otherSystem.actorOf(Props(new Actor {
          def receive = {
            case m: DeadLetter ⇒ locker :+= m
            case "Die!"        ⇒ sender ! "finally gone"; self.stop()
          }
        }), "davyJones")

        system.eventStream.subscribe(davyJones, classOf[DeadLetter])

        val probe = new TestProbe(system)
        probe.ref ! 42
        /*
       * this will ensure that the message is actually received, otherwise it
       * may happen that the system.stop() suspends the testActor before it had 
       * a chance to put the message into its private queue
       */
        probe.receiveWhile(1 second) {
          case null ⇒
        }

        val latch = new TestLatch(1)(system)
        system.registerOnTermination(latch.countDown())
        system.stop()
        latch.await(2 seconds)
        (davyJones ? "Die!").get must be === "finally gone"

        // this will typically also contain log messages which were sent after the logger shutdown
        locker must contain(DeadLetter(42, davyJones, probe.ref))
      } finally {
        system.stop()
        otherSystem.stop()
      }
    }

  }
}

