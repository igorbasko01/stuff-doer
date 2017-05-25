import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

import scala.concurrent.Future

/**
  * Created by igor on 14/05/17.
  */
object WebServerActor {
  case object Shutdown

  // A recommended way of creating props for actors with parameters.
  def props(hostname: String, port: Int): Props = Props(new WebServerActor(hostname,port))
}

class WebServerActor(hostname: String, port: Int) extends Actor with ActorLogging {

  implicit val materializer = ActorMaterializer()

  var bindingFuture: Future[ServerBinding] = _

  val welcomeHtmlPage = "<html><body>Welcome to stuff doer !</body></html>"
  val shutdownHtmlPage = "<html><body>Shutting down</body></html>"

  val route =
    get {
      pathSingleSlash {
        complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,welcomeHtmlPage)))
      } ~
      path("shutdown") {
        self ! WebServerActor.Shutdown
        complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,shutdownHtmlPage)))
      }
    }

  override def preStart(): Unit = {
    log.info("Starting...")
    bindingFuture = Http(context.system).bindAndHandle(route, hostname, port)
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
    bindingFuture.flatMap(_.unbind())(context.dispatcher)
  }

  override def receive: Receive = {
    case WebServerActor.Shutdown => context.stop(self)
  }
}
