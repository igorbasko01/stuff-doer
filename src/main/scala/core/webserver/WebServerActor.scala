package core.webserver

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import core.utils.Message
import core._

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by igor on 14/05/17.
  */

class WebServerActor(hostname: String, port: Int, password: String,
                     databaseActor: ActorRef) extends Actor with ActorLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  implicit val timeout: Timeout = Timeout(10.seconds)

  var bindingFuture: Future[ServerBinding] = _

  val route: Route = new RouteContainer(self, databaseActor, password, sendRequest, context.dispatcher).fullRoute

  /**
    * Creates a Request Actor and sends the request.
    * @param request The message to handle.
    * @return A future of the reply.
    */
  def sendRequest(request: Message) : Future[Any] = {
    val requestActor = context.actorOf(props(databaseActor))
    requestActor ? request
  }

  override def preStart(): Unit = {
    log.info("Starting...")
    bindingFuture = Http(context.system).bindAndHandle(route, hostname, port)
    log.info("Started !")
    log.info("Listening on {}:{}", hostname, port)
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
    bindingFuture.flatMap(_.unbind())(context.dispatcher)
  }

  override def receive: Receive = {
    case Message.Shutdown => context.stop(self)
  }
}


