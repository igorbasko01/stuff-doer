package webserver

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.util.Timeout
import database.DatabaseActor
import database.DatabaseActor.QueryResult
import org.joda.time.format.DateTimeFormat
import scheduler.{Basched, BaschedRequest}
import scheduler.BaschedRequest._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object RouteContainer {
  case object Shutdown
}

class RouteContainer(self: ActorRef, databaseActor: ActorRef, password: String, sendRequest: BaschedRequest.Message => Future[Any],
                     dispatcher: ExecutionContext) extends Directives with WebServerJsonReply {

  implicit val timeout: Timeout = Timeout(10.seconds)

  val getHomeFile = pathSingleSlash {
    getFromFile("src/main/resources/html/index.html")
  }

  val getHtmlFiles = pathPrefix("html") {
    getFromDirectory("src/main/resources/html")
  }

  val getShutdown = path("shutdown") {
    //TODO: Change it to the shutdown case object of the RouteContainer.
    self ! WebServerActor.Shutdown
    complete(s"Shutting down...")
  }

  val getQuery = path("query") {
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
  }

  val getUpdate = path("update") {
    parameters('text) { (text) =>
      val response = (databaseActor ? DatabaseActor.QueryDB(0,text,update = true)).mapTo[QueryResult]

      onSuccess(response) {
        case res: QueryResult => complete(s"Result: \n${res.message}")
        case _ => complete("Got some Error...")
      }
    }
  }

  val getAllProjects = path("basched" / "allprojects") {
    val response = sendRequest(BaschedRequest.RequestAllProjects).mapTo[ReplyAllProjects]
    onSuccess(response) {
      case ReplyAllProjects(projs) => complete(WebServerActor.Projects(projs))
      case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
        HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any projects: $other")))
    }
  }

  val getUnfinishedTasks = path("basched" / "unfinishedtasks") {
    val response = sendRequest(BaschedRequest.RequestAllUnfinishedTasks).mapTo[ReplyAllUnfinishedTasks]
    onSuccess(response) {
      case ReplyAllUnfinishedTasks(tasks) => handleUnfinishedTasks(tasks)
      case other => complete(HttpResponse(StatusCodes.NotFound,Nil,
        HttpEntity(ContentTypes.`text/plain(UTF-8)`,s"Could not get any tasks: $other")))
    }
  }

  val getReqRemainingPomodoroTime = path("basched" / "getRemainingPomodoroTime") {
    parameters('taskid, 'priority) { (taskid, priority) =>
      getRemainingPomodoroTime(taskid.toInt)
    }
  }

  val getReqRecordsByDateRange = path("basched" / "getRecordsByDateRange") {
    parameters('from, 'to) { (from, to) =>
      getRecordsByDateRange(from, to)
    }
  }

  val getMP3 = pathPrefix("resources" / "mp3") {
    getFromDirectory("src/main/resources/mp3")
  }

  val getRoutes = get { getHomeFile ~ getShutdown ~ getQuery ~ getUpdate ~ getAllProjects ~
    getUnfinishedTasks ~ getReqRemainingPomodoroTime ~ getHtmlFiles ~ getReqRecordsByDateRange ~ getMP3 }

  val postAddTask = path("basched" / "addTask") {
    parameters('prj, 'name, 'pri) { (prj, name, priority) =>
      val response = sendRequest(BaschedRequest.AddTask(prj.toInt,name,priority)).mapTo[ReplyAddTask]

      onSuccess(response) {
        case ReplyAddTask(BaschedRequest.ADDED) => complete(StatusCodes.Created)
        case ReplyAddTask(BaschedRequest.DUPLICATE) => complete(StatusCodes.Conflict)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postAddRecord = path ("basched" / "addRecord") {
    parameters('taskid, 'timestamp, 'duration) { (taskid, timestamp, duration) =>
      val response = sendRequest(BaschedRequest.RequestAddRecord(taskid.toInt, timestamp.toLong, duration.toLong))
        .mapTo[ReplyAddRecord]

      onSuccess(response) {
        case ReplyAddRecord(BaschedRequest.ADDED) => complete(StatusCodes.Created)
        case ReplyAddRecord(BaschedRequest.DUPLICATE) => complete(StatusCodes.Conflict)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postAddProject = path ("basched" / "addProject") {
    parameters('projectName) { (projectName) =>
      val response = sendRequest(BaschedRequest.RequestAddProject(projectName)).mapTo[BaschedRequest.ReplyAddProject]

      onSuccess(response) {
        case ReplyAddProject(BaschedRequest.ADDED) => complete(StatusCodes.Created)
        case ReplyAddProject(BaschedRequest.DUPLICATE) => complete(StatusCodes.Conflict)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postUpdatePomodorosCount = path ("basched" / "updatePomodorosCount") {
    parameters('taskid, 'pomodorosToAdd) { (taskid, pomodorosToAdd) =>
      val response = sendRequest(BaschedRequest.RequestUpdatePmdrCountInTask(taskid.toInt, pomodorosToAdd.toInt)).
        mapTo[BaschedRequest.ReplyUpdatePmdrCountInTask]

      onSuccess(response) {
        case BaschedRequest.ReplyUpdatePmdrCountInTask(BaschedRequest.ADDED) => complete(StatusCodes.Created)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postUpdateTaskWindowIfNeeded = path ("basched" / "updateTaskWindowIfNeeded") {
    parameters('taskid) { (taskid) =>
      val response = sendRequest(BaschedRequest.RequestTaskDetails(taskid.toInt))
        .mapTo[BaschedRequest.ReplyTaskDetails]

      onSuccess(response) {
        case BaschedRequest.ReplyTaskDetails(task) =>
          val windowFinished = if (task.priority == Basched.PRIORITY("im")) false
          else (task.pomodoros % Basched.NUM_OF_PMDRS_PER_PRIORITY(task.priority)) == 0

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

  val postFinishTask = path("basched" / "finishTask") {
    parameters('taskid) { (taskid) =>
      val response = sendRequest(BaschedRequest.RequestTaskStatusUpdate(taskid.toInt,Basched.STATUS("FINISHED")))
        .mapTo[BaschedRequest.ReplyTaskStatusUpdate]

      onSuccess(response) {
        case BaschedRequest.ReplyTaskStatusUpdate(BaschedRequest.UPDATED) => complete(StatusCodes.Created)
        case _ => complete(StatusCodes.NotFound)
      }
    }
  }

  val postToggleHold = path("basched" / "toggleHold") {
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

  val postStartTask = path("basched" / "startTask") {
    parameters('taskid, 'priority) { (taskid, priority) => startTask(taskid.toInt, priority.toInt) }
  }

  val postPingTask = path("basched" / "pingTask") {
    parameters('taskid) { (taskid) => pingTask(taskid.toInt) }
  }

  val postStopTask = path("basched" / "stopTask") {
    parameters('taskid) { (taskid) => stopTask(taskid.toInt)}
  }

  val postUpdatePriority = path("basched" / "updatePriority") {
    parameters('taskid, 'priority) { (taskid, priority) => updateTaskPriority(taskid.toInt, priority.toInt) }
  }

  val postRoutes = post { postAddTask ~ postAddRecord ~ postAddProject ~ postUpdatePomodorosCount ~
    postUpdateTaskWindowIfNeeded ~ postFinishTask ~ postToggleHold ~ postStartTask ~ postPingTask ~ postStopTask ~
  postUpdatePriority }

  val fullRoute = authenticateBasic("secure site", myUserPassAuthenticator) {
    userName => getRoutes ~ postRoutes
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
    * Get the remaining time in a pomodoro of a [[Task]]
    * @param taskId [[Task.id]]
    * @return Return the duration that left in the pomodoro as a [[Route]]
    */
  def getRemainingPomodoroTime(taskId: Int) : Route = {
    implicit val ec: ExecutionContext = dispatcher

    val resp = for {
      activeTask <- sendRequest(RequestActiveTaskDetails(taskId)).mapTo[ReplyActiveTaskDetails]
      histDuration <- sendRequest(RequestHistoricalTaskDuration(taskId)).mapTo[ReplyHistoricalTaskDuration]
    } yield (activeTask, histDuration)

    onSuccess(resp) {
      case (active, hist) if active.status == SUCCESS =>
        complete(WebServerActor.PomodoroDuration(
          calculateRemainingDuration(extractDurationFromActiveTask(active.activeTask) + hist.duration)))
      case (active, hist) if active.status != SUCCESS =>
        complete(WebServerActor.PomodoroDuration(
          calculateRemainingDuration(hist.duration)))
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Returns how much time left in the current pomodoro.
    * @param totalDuration The total duration of a [[Task]]
    * @return How much time left in the current pomodoro.
    */
  def calculateRemainingDuration(totalDuration: Long) : Long = {
    val numOfPomodorosDone = totalDuration / Basched.POMODORO_MAX_DURATION_MS
    val remainingTime = Basched.POMODORO_MAX_DURATION_MS -
      (totalDuration - (numOfPomodorosDone * Basched.POMODORO_MAX_DURATION_MS))

    remainingTime
  }

  /**
    * Calculate the duration of an active task by its start and end time.
    * @param activeTask [[ActiveTask]]
    * @return The duration in milliseconds.
    */
  def extractDurationFromActiveTask(activeTask: ActiveTask) : Long = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")
    val endTimestamp_ms = formatter.parseDateTime(activeTask.endTimestamp).getMillis
    val startTimestamp_ms = formatter.parseDateTime(activeTask.startTimestamp).getMillis

    endTimestamp_ms - startTimestamp_ms
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

  /** Start a task, should stop all active tasks if exist.
    * @param taskid The [[Task.id]]
    * @param priority The [[Task.priority]]
    * @return Route.
    */
  def startTask(taskid: Int, priority: Int) : Route = {
    implicit val ec: ExecutionContext = dispatcher

    val response = for {
      activeTasks <- sendRequest(BaschedRequest.RequestActiveTasks).mapTo[BaschedRequest.ReplyActiveTasks]
      stopTasks <- if (activeTasks.activeTasks.nonEmpty) stopActiveTasks(activeTasks.activeTasks, updateLastPing = false)
      else Future.successful(List(ReplyDeleteActiveTask(SUCCESS)))
      remainingTime <- if (stopTasks.forall(_.response == SUCCESS)) requestRemainingTime(taskid, priority)
      else Future.successful(ReplyRemainingTimeInPomodoro(0))
      startTask <- requestStartTask(taskid, remainingTime.duration)
    } yield startTask

    onSuccess(response) {
      case BaschedRequest.ReplyStartTask(ADDED) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Will stop all provided active tasks
    * @param activeTasks A list of [[ActiveTask]]
    * @param updateLastPing Should we update now the last ping or not. Used when the user pressed the stop button
    *                       (then yes), if it happened because of a "stuck" task then the last ping shouldn't be updated.
    * @return A future with the reply of success or failure.
    */
  def stopActiveTasks(activeTasks: List[ActiveTask], updateLastPing: Boolean = true): Future[List[ReplyDeleteActiveTask]] = {
    implicit val ec: ExecutionContext = dispatcher
    val listOfFutures = activeTasks.map(task => stopTaskLogic(task.taskid, updateLastPing))
    Future.sequence(listOfFutures).mapTo[List[ReplyDeleteActiveTask]]
  }

  /**
    * Runs the logic to stop a specific task.
    * @param taskid The [[Task.id]] to stop.
    * @param updateLastPing Do it needs to update the ping one last time or not. Explained in [[stopActiveTasks()]]
    * @return A response if it was able to delete the active task or not.
    */
  def stopTaskLogic(taskid: Int, updateLastPing: Boolean = true) : Future[ReplyDeleteActiveTask] = {
    implicit val ec: ExecutionContext = dispatcher

    val response = for {
      updateLastPing <- if (updateLastPing) sendRequest(RequestUpdateLastPing(taskid)).mapTo[ReplyUpdateLastPing]
      else Future.successful(ReplyUpdateLastPing(UPDATED))
      activeTaskDetails <- if (updateLastPing.response == BaschedRequest.UPDATED) getActiveTaskDetails(taskid)
      else Future.successful(ReplyActiveTaskDetails(ERROR,ActiveTask(0,"","",0)))
      storeTaskDetails <- if (activeTaskDetails.status == BaschedRequest.SUCCESS)
        storeTaskDetailsInRecordsTable(convertActiveTaskToStore(activeTaskDetails.activeTask))
      else Future.successful(ReplyAddRecord(ERROR))
      deleteTask <- if (storeTaskDetails.response == BaschedRequest.ADDED) deleteActiveTask(taskid)
      else Future.successful(ReplyDeleteActiveTask(ERROR))
    } yield deleteTask

    response
  }

  /**
    * Get details of an active task.
    * @param taskid [[Task.id]]
    * @return Future.
    */
  def getActiveTaskDetails(taskid: Int) : Future[ReplyActiveTaskDetails] = {
    sendRequest(RequestActiveTaskDetails(taskid)).mapTo[ReplyActiveTaskDetails]
  }

  /**
    * Store a task in the records table.
    * @param req [[RequestAddRecord]]
    * @return Future.
    */
  def storeTaskDetailsInRecordsTable(req: RequestAddRecord) : Future[ReplyAddRecord] = {
    sendRequest(req).mapTo[ReplyAddRecord]
  }

  /**
    * Convert an active task to a storable record.
    * @param activeTask [[ActiveTask]] to convert.
    * @return A [[RequestAddRecord]] to use when requesting to store.
    */
  def convertActiveTaskToStore(activeTask: ActiveTask) : RequestAddRecord = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")
    val endTimestamp_ms = formatter.parseDateTime(activeTask.endTimestamp).getMillis
    val startTimestamp_ms = formatter.parseDateTime(activeTask.startTimestamp).getMillis

    val taskid = activeTask.taskid
    val duration = endTimestamp_ms - startTimestamp_ms

    RequestAddRecord(taskid, endTimestamp_ms, duration)
  }

  /**
    * Remove a task from the active taks table.
    * @param taskid The [[Task.id]] of the [[Task]] to remove.
    * @return Future.
    */
  def deleteActiveTask(taskid: Int) : Future[ReplyDeleteActiveTask] = {
    sendRequest(RequestDeleteActiveTask(taskid)).mapTo[ReplyDeleteActiveTask]
  }

  def updateTaskPriority(taskid: Int, priority: Int) : Route = {
    val reply = sendRequest(RequestUpdateTaskPriority(taskid, priority)).mapTo[ReplyUpdateTaskPriority]

    onSuccess(reply) {
      case ReplyUpdateTaskPriority(UPDATED) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * A wrapper function to send a request to get the remaining time of a task.
    * @param taskid [[Task.id]]
    * @param priority [[Task.priority]]
    * @return Future with the remaining time.
    */
  def requestRemainingTime(taskid: Int, priority: Int) : Future[ReplyRemainingTimeInPomodoro] = {
    sendRequest(RequestRemainingTimeInPomodoro(taskid,priority)).mapTo[ReplyRemainingTimeInPomodoro]
  }

  /**
    * Sends a request to start a task.
    * @param taskid [[Task.id]]
    * @param initialDuration The duration that the task already did.
    * @return Reply if the start succeeded or failed.
    */
  def requestStartTask(taskid: Int, initialDuration: Long) : Future[ReplyStartTask] = {
    sendRequest(RequestStartTask(taskid, Basched.POMODORO_MAX_DURATION_MS - initialDuration)).mapTo[ReplyStartTask]
  }

  /**
    * Invoke a last_ping update in the active tasks table.
    * @param taskid The task id to update its last ping.
    * @return If the request was successful.
    */
  def pingTask(taskid: Int) : Route = {

    val pingTaskRep = sendRequest(BaschedRequest.RequestUpdateLastPing(taskid))
      .mapTo[BaschedRequest.ReplyUpdateLastPing]

    onSuccess(pingTaskRep) {
      case BaschedRequest.ReplyUpdateLastPing(UPDATED) => complete(StatusCodes.OK)
      case _ => complete(StatusCodes.NotFound)
    }
  }

  /**
    * Stop a running task.
    * @param taskid The [[Task.id]] of a running [[Task]]
    * @return Route.
    */
  def stopTask(taskid: Int) : Route = {
    onSuccess(stopTaskLogic(taskid)) {
      case ReplyDeleteActiveTask(SUCCESS) => complete(StatusCodes.OK)
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
      aggRecords <- sendRequest(RequestAggRecordsByDateRange(from, to)).mapTo[ReplyAggRecordsByDateRange]
    } yield aggRecords

    onSuccess(response) {
      case ReplyAggRecordsByDateRange(SUCCESS, records) => complete(WebServerActor.AggRecords(records))
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
      case p @ Credentials.Provided(id) if (p.verify(password)) => Some(id)
      case _ => None
    }
  }
}
