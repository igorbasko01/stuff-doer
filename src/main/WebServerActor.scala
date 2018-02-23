package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import main.BaschedRequest.{ReplyAddTask, ReplyAllProjects}
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

class WebServerActor(hostname: String,
                     port: Int,
                     databaseActor: ActorRef) extends Actor with ActorLogging {

  implicit val materializer = ActorMaterializer()

  var bindingFuture: Future[ServerBinding] = _

  implicit val timeout: Timeout = Timeout(10.seconds)

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
          val response = (databaseActor ? DatabaseActor.QueryDB(0,text)).mapTo[QueryResult]

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
          val response = (databaseActor ? DatabaseActor.QueryDB(0,text,update = true)).mapTo[QueryResult]

          onSuccess(response) {
            case res: QueryResult => complete(s"Result: \n${res.message}")
            case _ => complete("Got some Error...")
          }
        }
      } ~
      path("basched" / "allprojects") {
        val response = sendRequest(BaschedRequest.RequestAllProjects).mapTo[ReplyAllProjects]
        onSuccess(response) {
          case res: ReplyAllProjects => complete(res.projects.map(p=>s"${p._1},${p._2}").mkString(";"))
          case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
            HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any projects: $other")))
        }
      } ~
      pathPrefix("html") {
        getFromDirectory("resources/html")
      }
    } ~
  post {
    path("basched" / "addTask") {
      parameters('prj, 'name, 'pri) { (prj, name, priority) =>
        val response = sendRequest(BaschedRequest.AddTask(prj.toInt,name,priority)).mapTo[ReplyAddTask]

        onSuccess(response) {
          case ReplyAddTask(BaschedRequest.TASK_ADDED) => complete(StatusCodes.Created)
          case ReplyAddTask(BaschedRequest.TASK_DUPLICATE) => complete(StatusCodes.Conflict)
          case _ => complete(StatusCodes.NotFound)
        }
      }
    }
  }

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
