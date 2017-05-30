package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Created by igor on 14/05/17.
  */
object WebServerActor {
  case object Shutdown

  // A recommended way of creating props for actors with parameters.
  def props(hostname: String, port: Int, databaseActor: ActorRef): Props =
    Props(new WebServerActor(hostname,port,databaseActor))
}

class WebServerActor(hostname: String, port: Int, databaseActor: ActorRef) extends Actor with ActorLogging {

  implicit val materializer = ActorMaterializer()

  var bindingFuture: Future[ServerBinding] = _

  val welcomeHtmlPage = "<html><body>Welcome to stuff doer !</body></html>"
  val shutdownHtmlPage = "<html><body>Shutting down</body></html>"
  val underConstructionHtmlPage = "<html><body>This request is under construction...</body></html>"
  val htmlHead = "<html><body>"
  val htmlTail = "</body></html>"

  val route =
    get {
      pathSingleSlash {
        complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,welcomeHtmlPage)))
      } ~
      path("shutdown") {
        self ! WebServerActor.Shutdown
        complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,shutdownHtmlPage)))
      } ~
      path("unfinishedactions") {
        implicit val timeout = Timeout(10.seconds)
        val response = databaseActor ? DatabaseActor.QueryUnfinishedActions
        val result = Await.result(response, timeout.duration)
        val htmlResp = s"$htmlHead${result.toString}$htmlTail"
        complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,htmlResp)))
      }
    }

  override def preStart(): Unit = {
    log.info("Starting...")
    bindingFuture = Http(context.system).bindAndHandle(route, hostname, port)
    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
    bindingFuture.flatMap(_.unbind())(context.dispatcher)
  }

  override def receive: Receive = {
    case WebServerActor.Shutdown => context.stop(self)
  }
}
