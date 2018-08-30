package core.webserver

import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestKit
import core.database.DatabaseActor
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import core.scheduler.Basched
import core.utils.{Configuration, Message}
import core._

import scala.concurrent.duration._
import scala.concurrent.Future
import akka.pattern.ask
import akka.util.Timeout

import scala.collection.mutable.ArrayBuffer

class WebServerActorTest extends WordSpec with Matchers with BeforeAndAfterAll with ScalatestRouteTest {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  class DatabaseActorTest1(config: Configuration, clientProps: List[PropsWithName]) extends DatabaseActor(config, clientProps) {
    override def receive: Receive = {
      case _ => sender ! Message.QueryResult(0,Some(ArrayBuffer(List("1","hello"))),"",0)
    }
  }

  class WebserverActorTest1(host: String, port: Int, password: String, db: ActorRef) extends WebServerActor(host, port, password, db) {

    override def preStart(): Unit = {
      log.info("Starting...")
      log.info("Started !")
    }

    override def receive: Receive = {
      case _ => sender ! "webserver"
    }

    override def postStop(): Unit = {
      log.info("Stopped!")
    }
  }

  class BaschedActorTest1(config: Configuration) extends Basched(config) {
    override def receive: Receive = {
      case _ => sender ! "basched"
    }
  }

  "A Webserver Actor" must {
    "Get all the available projects" in {
      def propsBasched(config: Configuration) = Props(new BaschedActorTest1(config))
      def propsDatabase(config: Configuration) = Props(new DatabaseActorTest1(config,
        List(PropsWithName(propsBasched(config),"BaschedTest1"))))
      def propsWebserver(host: String, port: Int, password: String, db: ActorRef) = Props(new WebserverActorTest1(host, port, password, db))

      implicit val timeout: Timeout = Timeout(10.seconds)
      val config = new Configuration
      val databaseActor = system.actorOf(propsDatabase(config), "DatabaseTest1")
      val password = "p4ssword"
      val webServerActor = system.actorOf(propsWebserver("llll", 14, password, databaseActor), "WebserverTest1")
      def sendRequest(request: Message) : Future[Any] = {
        val requestActor = system.actorOf(props(databaseActor))
        requestActor ? request
      }
      val route = new RouteContainer(webServerActor, databaseActor, password, sendRequest, system.dispatcher).getAllProjects

      Get("/basched/allprojects") ~> route ~> check {
        responseAs[String] shouldEqual "{\"projects\":[{\"id\":1,\"name\":\"hello\"}]}"
      }
    }
  }

}
