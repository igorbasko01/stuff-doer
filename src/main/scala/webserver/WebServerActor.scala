package webserver

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import scheduler.BaschedRequest

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by igor on 14/05/17.
  */
object WebServerActor {
  case object Shutdown

  final case class Tasks(tasks: List[BaschedRequest.Task])
  final case class Projects(projects: List[BaschedRequest.Project])
  final case class PomodoroDuration(duration: Long)
  final case class AggRecords(records: List[BaschedRequest.AggregatedRecord])

  // A recommended way of creating props for actors with parameters.
  def props(hostname: String, port: Int, databaseActor: ActorRef): Props =
    Props(new WebServerActor(hostname,port,databaseActor))
}

class WebServerActor(hostname: String,
                     port: Int,
                     databaseActor: ActorRef) extends Actor with ActorLogging {

  implicit val materializer = ActorMaterializer()

  implicit val timeout: Timeout = Timeout(10.seconds)

  var bindingFuture: Future[ServerBinding] = _

  val route = new RouteContainer(self, databaseActor, sendRequest, context.dispatcher).fullRoute

  /**
    * Creates a Request Actor and sends the request.
    * @param request The message to handle.
    * @return A future of the reply.
    */
  def sendRequest(request: BaschedRequest.Message) : Future[Any] = {
    val requestActor = context.actorOf(BaschedRequest.props(databaseActor))
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
    case WebServerActor.Shutdown => context.stop(self)
  }
}


