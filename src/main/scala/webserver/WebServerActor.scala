package webserver

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import database.DatabaseActor
import database.DatabaseActor.QueryResult
import scheduler.BaschedRequest
import scheduler.BaschedRequest._
import scheduler.Basched

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

  // A recommended way of creating props for actors with parameters.
  def props(hostname: String, port: Int, databaseActor: ActorRef): Props =
    Props(new WebServerActor(hostname,port,databaseActor))
}

class WebServerActor(hostname: String,
                     port: Int,
                     databaseActor: ActorRef) extends Actor with ActorLogging with Directives with WebServerJsonReply {

  implicit val materializer = ActorMaterializer()

  var bindingFuture: Future[ServerBinding] = _

  implicit val timeout: Timeout = Timeout(10.seconds)

  val route =
    get {
      pathSingleSlash {
        getFromFile("src/main/resources/html/index.html")
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
            case ReplyAllProjects(projs) => complete(WebServerActor.Projects(projs))
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
              case BaschedRequest.ReplyRemainingTimeInPomodoro(duration) => complete(WebServerActor.PomodoroDuration(duration))
              case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
                HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get a duration: $other")))
            }
          }
        } ~
        pathPrefix("html") {
          getFromDirectory("src/main/resources/html")
        } ~
        pathPrefix("resources" / "mp3") {
          getFromDirectory("src/main/resources/mp3")
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
          path ("basched" / "addProject") {
            parameters('projectName) { (projectName) =>
              val response = sendRequest(BaschedRequest.RequestAddProject(projectName)).mapTo[BaschedRequest.ReplyAddProject]

              onSuccess(response) {
                case ReplyAddProject(BaschedRequest.ADDED) => complete(StatusCodes.Created)
                case ReplyAddProject(BaschedRequest.DUPLICATE) => complete(StatusCodes.Conflict)
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
          } ~
          path("basched" / "finishTask") {
            parameters('taskid) { (taskid) =>
              val response = sendRequest(BaschedRequest.RequestTaskStatusUpdate(taskid.toInt,Basched.STATUS("FINISHED")))
                .mapTo[BaschedRequest.ReplyTaskStatusUpdate]

              onSuccess(response) {
                case BaschedRequest.ReplyTaskStatusUpdate(BaschedRequest.UPDATED) => complete(StatusCodes.Created)
                case _ => complete(StatusCodes.NotFound)
              }
            }
          } ~
          path("basched" / "toggleHold") {
            parameters('taskid) { (taskid) =>
              val response = sendRequest(BaschedRequest.RequestTaskDetails(taskid.toInt))
                .mapTo[BaschedRequest.ReplyTaskDetails]

              onSuccess(response) {
                case BaschedRequest.ReplyTaskDetails(task) =>
                  val updateToStatus = toggleHoldStatus(task.status)
                  val updateResponse = sendRequest(BaschedRequest.RequestTaskStatusUpdate(task.id,
                    updateToStatus)).mapTo[BaschedRequest.ReplyTaskStatusUpdate]

                  onSuccess(updateResponse) {
                    case BaschedRequest.ReplyTaskStatusUpdate(BaschedRequest.UPDATED) => complete(StatusCodes.Created)
                    case _ => complete(StatusCodes.NotFound)
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
    if (tasks.exists(_.status == Basched.STATUS("READY")))
    // If have any tasks in ready status, than there should be a current task selected.
    // so just return all the tasks to the client.
      complete(WebServerActor.Tasks(tasks))
    else {
      // Otherwise, there are no READY tasks, so try and convert all the WINDOW_FINISHED tasks into READY tasks.
      val response = sendRequest(BaschedRequest.RequestUpdateAllWindowFinishedToReady)
        .mapTo[BaschedRequest.ReplyUpdateAllWindowFinishedToReady]

      // Then if everything is ok, return all the tasks. There should be some current task if at least one task was
      // converted to READY.
      onSuccess(response) {
        case BaschedRequest.ReplyUpdateAllWindowFinishedToReady(BaschedRequest.UPDATED) =>
          val resp_unf = sendRequest(BaschedRequest.RequestAllUnfinishedTasks)
            .mapTo[BaschedRequest.ReplyAllUnfinishedTasks]
          onSuccess(resp_unf) {
            case ReplyAllUnfinishedTasks(unf_tasks) => complete(WebServerActor.Tasks(unf_tasks))
            case _ => complete(StatusCodes.NotFound)
          }
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  /**
    * Toggle task statuses.
    * @param status Current [[BaschedRequest.Task.status]] of the [[BaschedRequest.Task]].
    * @return New status.
    */
  def toggleHoldStatus(status: Int) : Int = {
    status match {
      case x: Int if x == Basched.STATUS("READY") => Basched.STATUS("ON_HOLD_READY")
      case x: Int if x == Basched.STATUS("WINDOW_FINISHED") => Basched.STATUS("ON_HOLD_WINDOW_FINISHED")
      case x: Int if x == Basched.STATUS("ON_HOLD_READY") => Basched.STATUS("READY")
      case x: Int if x == Basched.STATUS("ON_HOLD_WINDOW_FINISHED") => Basched.STATUS("WINDOW_FINISHED")
    }
  }
}


