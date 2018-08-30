package core.webserver

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.util.Timeout
import org.joda.time.format.DateTimeFormat
import core.utils.Message
import core._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object RouteContainer {
  case object Shutdown
}

class RouteContainer(self: ActorRef, databaseActor: ActorRef, password: String, sendRequest: Message => Future[Any],
                     dispatcher: ExecutionContext) extends Directives with WebServerJsonReply {

  implicit val timeout: Timeout = Timeout(10.seconds)

  val getHomeFile: Route = pathSingleSlash {
    getFromFile("src/main/resources/html/index.html")
  }

  val getHtmlFiles: Route = pathPrefix("html") {
    getFromDirectory("src/main/resources/html")
  }

  val getShutdown: Route = path("shutdown") {
    //TODO: Change it to the shutdown case object of the RouteContainer.
    self ! Message.Shutdown
    complete(s"Shutting down...")
  }

  val getQuery: Route = path("query") {
    parameters('text) { text =>
      val response = (databaseActor ? Message.QueryDB(0,text)).mapTo[Message.QueryResult]

      onSuccess(response) {
        case res: Message.QueryResult =>
          if (res.result.isDefined)
            complete(s"Result: \n${res.result.get.map(_.mkString(",")).mkString("\n")}")
          else
            complete(s"Error: ${res.message}")
        case _ => complete("Got some Error....")
      }
    }
  }

  val getUpdate: Route = path("update") {
    parameters('text) { text =>
      val response = (databaseActor ? Message.QueryDB(0,text,update = true)).mapTo[Message.QueryResult]

      onSuccess(response) {
        case res: Message.QueryResult => complete(s"Result: \n${res.message}")
        case _ => complete("Got some Error...")
      }
    }
  }

  val getAllProjects: Route = path("basched" / "allprojects") {
    val response = sendRequest(Message.RequestAllProjects).mapTo[Message.ReplyAllProjects]
    onSuccess(response) {
      case Message.ReplyAllProjects(projs) => complete(Message.Projects(projs))
      case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
        HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any projects: $other")))
    }
  }

  val getUnfinishedTasks: Route = path("basched" / "unfinishedtasks") {
    val response = sendRequest(Message.RequestAllUnfinishedTasks).mapTo[Message.ReplyAllUnfinishedTasks]
    onSuccess(response) {
      case Message.ReplyAllUnfinishedTasks(tasks) => handleUnfinishedTasks(tasks)
      case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
        HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any tasks: $other")))
    }
  }

  val getReqRemainingPomodoroTime: Route = path("basched" / "getRemainingPomodoroTime") {
    parameters('taskid, 'priority) { (taskid, priority) =>
      getRemainingPomodoroTime(taskid.toInt, priority.toInt)
    }
  }

  val getReqRecordsByDateRange: Route = path("basched" / "getRecordsByDateRange") {
    parameters('from, 'to) { (from, to) =>
      getRecordsByDateRange(from, to)
    }
  }

  val getMP3: Route = pathPrefix("resources" / "mp3") {
    getFromDirectory("src/main/resources/mp3")
  }

  val getRoutes: Route = get { getHomeFile ~ getShutdown ~ getQuery ~ getUpdate ~ getAllProjects ~
    getUnfinishedTasks ~ getReqRemainingPomodoroTime ~ getHtmlFiles ~ getReqRecordsByDateRange ~ getMP3 }

  val postAddTask: Route = path("basched" / "addTask") {
    parameters('prj, 'name, 'pri) { (prj, name, priority) =>
      val response = sendRequest(Message.AddTask(prj.toInt,name,priority)).mapTo[Message.ReplyAddTask]

      onSuccess(response) {
        case Message.ReplyAddTask(ADDED) => complete(StatusCodes.Created)
        case Message.ReplyAddTask(DUPLICATE) => complete(StatusCodes.Conflict)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postAddRecord: Route = path ("basched" / "addRecord") {
    parameters('taskid, 'timestamp, 'duration) { (taskid, timestamp, duration) =>
      val response = sendRequest(Message.RequestAddRecord(taskid.toInt, timestamp.toLong, duration.toLong))
        .mapTo[Message.ReplyAddRecord]

      onSuccess(response) {
        case Message.ReplyAddRecord(ADDED) => complete(StatusCodes.Created)
        case Message.ReplyAddRecord(DUPLICATE) => complete(StatusCodes.Conflict)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postAddProject: Route = path ("basched" / "addProject") {
    parameters('projectName) { projectName =>
      val response = sendRequest(Message.RequestAddProject(projectName)).mapTo[Message.ReplyAddProject]

      onSuccess(response) {
        case Message.ReplyAddProject(ADDED) => complete(StatusCodes.Created)
        case Message.ReplyAddProject(DUPLICATE) => complete(StatusCodes.Conflict)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postUpdatePomodorosCount: Route = path ("basched" / "updatePomodorosCount") {
    parameters('taskid, 'pomodorosToAdd) { (taskid, pomodorosToAdd) =>
      val response = sendRequest(Message.RequestUpdatePmdrCountInTask(taskid.toInt, pomodorosToAdd.toInt)).
        mapTo[Message.ReplyUpdatePmdrCountInTask]

      onSuccess(response) {
        case Message.ReplyUpdatePmdrCountInTask(ADDED) => complete(StatusCodes.Created)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postUpdateTaskWindowIfNeeded: Route = path ("basched" / "updateTaskWindowIfNeeded") {
    parameters('taskid) { taskid =>
      val response = sendRequest(Message.RequestTaskDetails(taskid.toInt))
        .mapTo[Message.ReplyTaskDetails]

      onSuccess(response) {
        case Message.ReplyTaskDetails(task) =>
          val windowFinished = if (task.priority == PRIORITY("im")) false
          else (task.pomodoros % NUM_OF_PMDRS_PER_PRIORITY(task.priority)) == 0

          if (windowFinished) {
            val updateResponse = sendRequest(Message.RequestTaskStatusUpdate(task.id,
              STATUS("WINDOW_FINISHED"))).mapTo[Message.ReplyTaskStatusUpdate]
            onSuccess(updateResponse) {
              case Message.ReplyTaskStatusUpdate(UPDATED) => complete(StatusCodes.Created)
              case _ => complete(StatusCodes.NotFound)
            }
          } else {
            complete(StatusCodes.OK)
          }
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postFinishTask: Route = path("basched" / "finishTask") {
    parameters('taskid) { taskid =>
      val response = sendRequest(Message.RequestTaskStatusUpdate(taskid.toInt,STATUS("FINISHED")))
        .mapTo[Message.ReplyTaskStatusUpdate]

      onSuccess(response) {
        case Message.ReplyTaskStatusUpdate(UPDATED) => complete(StatusCodes.Created)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postToggleHold: Route = path("basched" / "toggleHold") {
    parameters('taskid) { taskid =>
      val response = sendRequest(Message.RequestTaskDetails(taskid.toInt))
        .mapTo[Message.ReplyTaskDetails]

      onSuccess(response) {
        case Message.ReplyTaskDetails(task) =>
          val updateToStatus = toggleHoldStatus(task.status)
          val updateResponse = sendRequest(Message.RequestTaskStatusUpdate(task.id,
            updateToStatus)).mapTo[Message.ReplyTaskStatusUpdate]

          onSuccess(updateResponse) {
            case Message.ReplyTaskStatusUpdate(UPDATED) => complete(StatusCodes.Created)
            case _ => complete(StatusCodes.NotFound)
          }
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postStartTask: Route = path("basched" / "startTask") {
    parameters('taskid, 'priority) { (taskid, priority) => startTask(taskid.toInt, priority.toInt) }
  }

  val postPingTask: Route = path("basched" / "pingTask") {
    parameters('taskid) { taskid => pingTask(taskid.toInt) }
  }

  val postStopTask: Route = path("basched" / "stopTask") {
    parameters('taskid) { taskid => stopTask(taskid.toInt)}
  }

  val postUpdatePriority: Route = path("basched" / "updatePriority") {
    parameters('taskid, 'priority) { (taskid, priority) => updateTaskPriority(taskid.toInt, priority.toInt) }
  }

  val postRoutes: Route = post { postAddTask ~ postAddRecord ~ postAddProject ~ postUpdatePomodorosCount ~
    postUpdateTaskWindowIfNeeded ~ postFinishTask ~ postToggleHold ~ postStartTask ~ postPingTask ~ postStopTask ~
  postUpdatePriority }

  val fullRoute: Route = authenticateBasic("secure site", myUserPassAuthenticator) {
    _ => getRoutes ~ postRoutes
  }

  def handleUnfinishedTasks(tasks: List[Message.Task]) : Route = {
    if (tasks.exists(_.status == STATUS("READY")))
    // If have any tasks in ready status, than there should be a current task selected.
    // so just return all the tasks to the client.
      complete(Message.Tasks(tasks))
    else {
      // Otherwise, there are no READY tasks, so try and convert all the WINDOW_FINISHED tasks into READY tasks.
      val response = sendRequest(Message.RequestUpdateAllWindowFinishedToReady)
        .mapTo[Message.ReplyUpdateAllWindowFinishedToReady]

      // Then if everything is ok, return all the tasks. There should be some current task if at least one task was
      // converted to READY.
      onSuccess(response) {
        case Message.ReplyUpdateAllWindowFinishedToReady(UPDATED) =>
          val resp_unf = sendRequest(Message.RequestAllUnfinishedTasks)
            .mapTo[Message.ReplyAllUnfinishedTasks]
          onSuccess(resp_unf) {
            case Message.ReplyAllUnfinishedTasks(unf_tasks) => complete(Message.Tasks(unf_tasks))
            case _ => complete(StatusCodes.NotFound)
          }
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  /**
    * Get the remaining time in a pomodoro of a [[Message.Task]]
    * @param taskId [[Message.Task.id]]
    * @param priority [[Message.Task.priority]]
    * @return Return the duration that left in the pomodoro as a [[Route]]
    */
  def getRemainingPomodoroTime(taskId: Int, priority: Int) : Route = {
    implicit val ec: ExecutionContext = dispatcher

    val resp = for {
      activeTask <- sendRequest(Message.RequestActiveTaskDetails(taskId)).mapTo[Message.ReplyActiveTaskDetails]
      histDuration <- sendRequest(Message.RequestHistoricalTaskDuration(taskId)).mapTo[Message.ReplyHistoricalTaskDuration]
    } yield (activeTask, histDuration)

    onSuccess(resp) {
      case (active, hist) if active.status == SUCCESS =>
        complete(Message.PomodoroDuration(
          calculateRemainingDuration(extractDurationFromActiveTask(active.activeTask) + hist.duration)))
      case (active, hist) if active.status != SUCCESS =>
        complete(Message.PomodoroDuration(
          calculateRemainingDuration(hist.duration)))
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Returns how much time left in the current pomodoro.
    * @param totalDuration The total duration of a [[Message.Task]]
    * @return How much time left in the current pomodoro.
    */
  def calculateRemainingDuration(totalDuration: Long) : Long = {
    val numOfPomodorosDone = totalDuration / POMODORO_MAX_DURATION_MS
    val remainingTime = POMODORO_MAX_DURATION_MS -
      (totalDuration - (numOfPomodorosDone * POMODORO_MAX_DURATION_MS))

    remainingTime
  }

  /**
    * Calculate the duration of an active task by its start and end time.
    * @param activeTask [[Message.ActiveTask]]
    * @return The duration in milliseconds.
    */
  def extractDurationFromActiveTask(activeTask: Message.ActiveTask) : Long = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")
    val endTimestamp_ms = formatter.parseDateTime(activeTask.endTimestamp).getMillis
    val startTimestamp_ms = formatter.parseDateTime(activeTask.startTimestamp).getMillis

    endTimestamp_ms - startTimestamp_ms
  }

  /**
    * Toggle task statuses.
    * @param status Current [[Message.Task.status]] of the [[Message.Task]].
    * @return New status.
    */
  def toggleHoldStatus(status: Int) : Int = {
    status match {
      case x: Int if x == STATUS("READY") => STATUS("ON_HOLD_READY")
      case x: Int if x == STATUS("WINDOW_FINISHED") => STATUS("ON_HOLD_WINDOW_FINISHED")
      case x: Int if x == STATUS("ON_HOLD_READY") => STATUS("READY")
      case x: Int if x == STATUS("ON_HOLD_WINDOW_FINISHED") => STATUS("WINDOW_FINISHED")
    }
  }

  /** Start a task, should stop all active tasks if exist.
    * @param taskid The [[Message.Task.id]]
    * @param priority The [[Message.Task.priority]]
    * @return Route.
    */
  def startTask(taskid: Int, priority: Int) : Route = {
    implicit val ec: ExecutionContext = dispatcher

    val response = for {
      activeTasks <- sendRequest(Message.RequestActiveTasks).mapTo[Message.ReplyActiveTasks]
      stopTasks <- if (activeTasks.activeTasks.nonEmpty) stopActiveTasks(activeTasks.activeTasks, updateLastPing = false)
      else Future.successful(List(Message.ReplyDeleteActiveTask(SUCCESS)))
      remainingTime <- if (stopTasks.forall(_.response == SUCCESS)) requestRemainingTime(taskid, priority)
      else Future.successful(Message.ReplyRemainingTimeInPomodoro(0))
      startTask <- requestStartTask(taskid, remainingTime.duration)
    } yield startTask

    onSuccess(response) {
      case Message.ReplyStartTask(ADDED) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Will stop all provided active tasks
    * @param activeTasks A list of [[Message.ActiveTask]]
    * @param updateLastPing Should we update now the last ping or not. Used when the user pressed the stop button
    *                       (then yes), if it happened because of a "stuck" task then the last ping shouldn't be updated.
    * @return A future with the reply of success or failure.
    */
  def stopActiveTasks(activeTasks: List[Message.ActiveTask], updateLastPing: Boolean = true): Future[List[Message.ReplyDeleteActiveTask]] = {
    implicit val ec: ExecutionContext = dispatcher
    val listOfFutures = activeTasks.map(task => stopTaskLogic(task.taskid, updateLastPing))
    Future.sequence(listOfFutures).mapTo[List[Message.ReplyDeleteActiveTask]]
  }

  /**
    * Runs the logic to stop a specific task.
    * @param taskid The [[Message.Task.id]] to stop.
    * @param updateLastPing Do it needs to update the ping one last time or not. Explained in [[stopActiveTasks()]]
    * @return A response if it was able to delete the active task or not.
    */
  def stopTaskLogic(taskid: Int, updateLastPing: Boolean = true) : Future[Message.ReplyDeleteActiveTask] = {
    implicit val ec: ExecutionContext = dispatcher

    val response = for {
      updateLastPing <- if (updateLastPing) sendRequest(Message.RequestUpdateLastPing(taskid)).mapTo[Message.ReplyUpdateLastPing]
      else Future.successful(Message.ReplyUpdateLastPing(UPDATED))
      activeTaskDetails <- if (updateLastPing.response == UPDATED) getActiveTaskDetails(taskid)
      else Future.successful(Message.ReplyActiveTaskDetails(ERROR,Message.ActiveTask(0,"","",0)))
      storeTaskDetails <- if (activeTaskDetails.status == SUCCESS)
        storeTaskDetailsInRecordsTable(convertActiveTaskToStore(activeTaskDetails.activeTask))
      else Future.successful(Message.ReplyAddRecord(ERROR))
      deleteTask <- if (storeTaskDetails.response == ADDED) deleteActiveTask(taskid)
      else Future.successful(Message.ReplyDeleteActiveTask(ERROR))
    } yield deleteTask

    response
  }

  /**
    * Get details of an active task.
    * @param taskid [[Message.Task.id]]
    * @return Future.
    */
  def getActiveTaskDetails(taskid: Int) : Future[Message.ReplyActiveTaskDetails] = {
    sendRequest(Message.RequestActiveTaskDetails(taskid)).mapTo[Message.ReplyActiveTaskDetails]
  }

  /**
    * Store a task in the records table.
    * @param req [[Message.RequestAddRecord]]
    * @return Future.
    */
  def storeTaskDetailsInRecordsTable(req: Message.RequestAddRecord) : Future[Message.ReplyAddRecord] = {
    sendRequest(req).mapTo[Message.ReplyAddRecord]
  }

  /**
    * Convert an active task to a storable record.
    * @param activeTask [[Message.ActiveTask]] to convert.
    * @return A [[Message.RequestAddRecord]] to use when requesting to store.
    */
  def convertActiveTaskToStore(activeTask: Message.ActiveTask) : Message.RequestAddRecord = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")
    val endTimestamp_ms = formatter.parseDateTime(activeTask.endTimestamp).getMillis
    val startTimestamp_ms = formatter.parseDateTime(activeTask.startTimestamp).getMillis

    val taskid = activeTask.taskid
    val duration = endTimestamp_ms - startTimestamp_ms

    Message.RequestAddRecord(taskid, endTimestamp_ms, duration)
  }

  /**
    * Remove a task from the active taks table.
    * @param taskid The [[Message.Task.id]] of the [[Message.Task]] to remove.
    * @return Future.
    */
  def deleteActiveTask(taskid: Int) : Future[Message.ReplyDeleteActiveTask] = {
    sendRequest(Message.RequestDeleteActiveTask(taskid)).mapTo[Message.ReplyDeleteActiveTask]
  }

  def updateTaskPriority(taskid: Int, priority: Int) : Route = {
    val reply = sendRequest(Message.RequestUpdateTaskPriority(taskid, priority)).mapTo[Message.ReplyUpdateTaskPriority]

    onSuccess(reply) {
      case Message.ReplyUpdateTaskPriority(UPDATED) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * A wrapper function to send a request to get the remaining time of a task.
    * @param taskid [[Message.Task.id]]
    * @param priority [[Message.Task.priority]]
    * @return Future with the remaining time.
    */
  def requestRemainingTime(taskid: Int, priority: Int) : Future[Message.ReplyRemainingTimeInPomodoro] = {
    sendRequest(Message.RequestRemainingTimeInPomodoro(taskid,priority)).mapTo[Message.ReplyRemainingTimeInPomodoro]
  }

  /**
    * Sends a request to start a task.
    * @param taskid [[Message.Task.id]]
    * @param initialDuration The duration that the task already did.
    * @return Reply if the start succeeded or failed.
    */
  def requestStartTask(taskid: Int, initialDuration: Long) : Future[Message.ReplyStartTask] = {
    sendRequest(Message.RequestStartTask(taskid, POMODORO_MAX_DURATION_MS - initialDuration)).mapTo[Message.ReplyStartTask]
  }

  /**
    * Invoke a last_ping update in the active tasks table.
    * @param taskid The task id to update its last ping.
    * @return If the request was successful.
    */
  def pingTask(taskid: Int) : Route = {

    val pingTaskRep = sendRequest(Message.RequestUpdateLastPing(taskid))
      .mapTo[Message.ReplyUpdateLastPing]

    onSuccess(pingTaskRep) {
      case Message.ReplyUpdateLastPing(UPDATED) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Stop a running task.
    * @param taskid The [[Message.Task.id]] of a running [[Message.Task]]
    * @return Route.
    */
  def stopTask(taskid: Int) : Route = {
    onSuccess(stopTaskLogic(taskid)) {
      case Message.ReplyDeleteActiveTask(SUCCESS) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Return all Records that ended at the specified date range.
    * @param from The start date in millis.
    * @param to The end date in millis.
    * @return The Route object of the request.
    */
  def getRecordsByDateRange(from: String, to: String) : Route = {
    implicit val ec: ExecutionContext = dispatcher

    val response = for {
      aggRecords <- sendRequest(Message.RequestAggRecordsByDateRange(from, to)).mapTo[Message.ReplyAggRecordsByDateRange]
    } yield aggRecords

    onSuccess(response) {
      case Message.ReplyAggRecordsByDateRange(SUCCESS, records) => complete(Message.AggRecords(records))
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Authenticate the credentials with an async manner.
    * @param credentials The credentials of of the request.
    * @return A future that will contain the id of the user.
    */
  def myUserPassAuthenticator(credentials: Credentials) : Option[String] = {
    credentials match {
      case p @ Credentials.Provided(id) if p.verify(password) => Some(id)
      case _ => None
    }
  }
}
