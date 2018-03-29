package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import main.BaschedRequest.{ReplyAddRecord, ReplyAddTask, ReplyAllProjects, ReplyAllUnfinishedTasks}
import main.DatabaseActor.QueryResult
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by igor on 14/05/17.
  */
final case class Tasks(tasks: List[BaschedRequest.Task])
final case class Projects(projects: List[BaschedRequest.Project])
final case class PomodoroDuration(duration: Long)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val taskFormat = jsonFormat8(BaschedRequest.Task)
  implicit val tasksFormat = jsonFormat1(Tasks)

  implicit val projFormat = jsonFormat2(BaschedRequest.Project)
  implicit val projsFormat = jsonFormat1(Projects)

  implicit val pomodoroDurationFormat = jsonFormat1(PomodoroDuration)
}

object WebServerActor {
  case object Shutdown

  // A recommended way of creating props for actors with parameters.
  def props(hostname: String, port: Int, databaseActor: ActorRef): Props =
    Props(new WebServerActor(hostname,port,databaseActor))
}

class WebServerActor(hostname: String,
                     port: Int,
                     databaseActor: ActorRef) extends Actor with ActorLogging with Directives with JsonSupport {

  implicit val materializer = ActorMaterializer()

  var bindingFuture: Future[ServerBinding] = _

  implicit val timeout: Timeout = Timeout(10.seconds)

  val route =
    get {
      pathSingleSlash {
        getFromFile("resources/html/index.html")
      } ~
      path("shutdown") {
        self ! WebServerActor.Shutdown
        complete(s"Shutting down...")
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
          case ReplyAllProjects(projs) => complete(Projects(projs))
          case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
            HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any projects: $other")))
        }
      } ~
      path("basched" / "unfinishedtasks") {
        val response = sendRequest(BaschedRequest.RequestAllUnfinishedTasks).mapTo[ReplyAllUnfinishedTasks]
        onSuccess(response) {
          case ReplyAllUnfinishedTasks(tasks) => handleUnfinishedTasks(tasks)
          case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
            HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any tasks: $other")))
        }
      } ~
      path("basched" / "getRemainingPomodoroTime") {
        parameters('taskid, 'priority) { (taskid, priority) =>
          val response = sendRequest(BaschedRequest.RequestRemainingTimeInPomodoro(taskid.toInt,priority.toInt))
            .mapTo[BaschedRequest.ReplyRemainingTimeInPomodoro]

          onSuccess(response) {
            case BaschedRequest.ReplyRemainingTimeInPomodoro(duration) => complete(PomodoroDuration(duration))
            case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
              HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get a duration: $other")))
          }
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
          case ReplyAddTask(BaschedRequest.ADDED) => complete(StatusCodes.Created)
          case ReplyAddTask(BaschedRequest.DUPLICATE) => complete(StatusCodes.Conflict)
          case _ => complete(StatusCodes.NotFound)
        }
      }
    } ~
    path ("basched" / "addRecord") {
      parameters('taskid, 'timestamp, 'duration) { (taskid, timestamp, duration) =>
        val response = sendRequest(BaschedRequest.RequestAddRecord(taskid.toInt, timestamp.toLong, duration.toLong))
          .mapTo[ReplyAddRecord]

        onSuccess(response) {
          case ReplyAddRecord(BaschedRequest.ADDED) => complete(StatusCodes.Created)
          case ReplyAddRecord(BaschedRequest.DUPLICATE) => complete(StatusCodes.Conflict)
          case _ => complete(StatusCodes.NotFound)
        }
      }
    } ~
    path ("basched" / "updatePomodorosCount") {
      parameters('taskid, 'pomodorosToAdd) { (taskid, pomodorosToAdd) =>
        val response = sendRequest(BaschedRequest.RequestUpdatePmdrCountInTask(taskid.toInt, pomodorosToAdd.toInt)).
          mapTo[BaschedRequest.ReplyUpdatePmdrCountInTask]

        onSuccess(response) {
          case BaschedRequest.ReplyUpdatePmdrCountInTask(BaschedRequest.ADDED) => complete(StatusCodes.Created)
          case _ => complete(StatusCodes.NotFound)
        }
      }
    } ~
    path ("basched" / "updateTaskWindowIfNeeded") {
      parameters('taskid) { (taskid) =>
        val response = sendRequest(BaschedRequest.RequestTaskDetails(taskid.toInt))
          .mapTo[BaschedRequest.ReplyTaskDetails]

        onSuccess(response) {
          case BaschedRequest.ReplyTaskDetails(task) =>
            val windowFinished = (task.pomodoros % Basched.NUM_OF_PMDRS_PER_PRIORITY(task.priority)) == 0
            if (windowFinished) {
              val updateResponse = sendRequest(BaschedRequest.RequestTaskStatusUpdate(task.id,
                Basched.STATUS("WINDOW_FINISHED"))).mapTo[BaschedRequest.ReplyTaskStatusUpdate]
              onSuccess(updateResponse) {
                case BaschedRequest.ReplyTaskStatusUpdate(BaschedRequest.UPDATED) => complete(StatusCodes.Created)
                case _ => complete(StatusCodes.NotFound)
              }
            } else {
              complete(StatusCodes.OK)
            }
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

  def handleUnfinishedTasks(tasks: List[BaschedRequest.Task]) : Route = {
    if (tasks.contains(BaschedRequest.Task(_, _, _, _, _, Basched.STATUS("READY"), _, _)))
      complete(Tasks(tasks))
    else {
      // TODO: Update all the WINDOW_FINISHED tasks into READY tasks.
      // TODO: And then return all the tasks with one selected.
      complete(Tasks(tasks))
    }
  }
}
