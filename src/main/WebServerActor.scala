package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout

import main.DatabaseActor.QueryResult
import org.joda.time.DateTime

import scala.concurrent.Future
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

  val route =
    get {
      pathSingleSlash {
        complete(s"Welcome to stuff doer !")
      } ~
      path("shutdown") {
        self ! WebServerActor.Shutdown
        complete(s"Shutting down...")
      } ~
      path("unfinishedactions") {
        implicit val timeout = Timeout(10.seconds)
        val response = (databaseActor ? DatabaseActor.QueryUnfinishedActions).mapTo[List[DatabaseActor.Action]]

        onSuccess(response) {
          case res: List[DatabaseActor.Action] =>
            complete(s"Unfinished actions:\n${res.map(action => action.toString).mkString("\n")}\n<EOF>")
          case _ =>
            complete(s"Error !")
        }
      } ~
      path("add_action") {
        parameters('action, 'params) { (action, params) =>
          val created = DateTime.now()
          val lastUpdated = created
          val newAction = DatabaseActor.Action(None, created, action,
            params.split(DatabaseActor.PARAMS_DELIMITER).toList, DatabaseActor.ACTION_STATUS_INITIAL, lastUpdated)
          databaseActor ! newAction

          complete(s"Adding the following action: $action->$params")
        }
      } ~
      path("query") {
        parameters('text) { (text) =>
          implicit val timeout = Timeout(10.seconds)
          val response = (databaseActor ? DatabaseActor.QueryDB(text)).mapTo[QueryResult]

          onSuccess(response) {
            case res: QueryResult =>
              if (res.result.isDefined)
                complete(s"Result: \n${res.result.get.map(_.mkString(",")).mkString("\n")}")
              else
                complete(s"Error: ${res.message}")
            case _ => complete("Got some Error....")
          }
        }
      } ~
      path("update") {
        parameters('text) { (text) =>
          implicit val timeout = Timeout(10.seconds)
          val response = (databaseActor ? DatabaseActor.QueryDB(text,update = true)).mapTo[QueryResult]

          onSuccess(response) {
            case res: QueryResult => complete(s"Result: \n${res.message}")
            case _ => complete("Got some Error....")
          }
        }
      }
    }

  override def preStart(): Unit = {
    log.info("Starting...")
    bindingFuture = Http(context.system).bindAndHandle(route, hostname, port)
    log.info("Started !")
    log.info(s"Listening on $hostname:$port")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
    bindingFuture.flatMap(_.unbind())(context.dispatcher)
  }

  override def receive: Receive = {
    case WebServerActor.Shutdown => context.stop(self)
  }
}
