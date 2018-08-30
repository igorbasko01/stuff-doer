package core.scheduler

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill}
import core.database.DatabaseActor
import org.joda.time.format.DateTimeFormat
import core.webserver.WebServerActor
import core.utils.Message
import core._

import scala.util.{Success, Try}

/**
  * A single request actor, handles only one request, so each request should instantiate this actor.
  * Will return the result of the request straight to the sender.
  * It mainly contains the logic of the Basched app, and it is the main link between the [[DatabaseActor]] and the
  * [[WebServerActor]].
  * @param db A [[DatabaseActor]] that the basched request will query.
  */

class BaschedRequest(db: ActorRef) extends Actor with ActorLogging {

  var replyTo: ActorRef = _
  var handleReply: Message.QueryResult => Unit = _

  override def receive: Receive = {
    case Message.RequestAllProjects => queryGetAllProjects()
    case addTask: Message.AddTask => addNewTask(addTask)
    case addRecord: Message.RequestAddRecord => addNewRecord(addRecord)
    case Message.RequestAddProject(prjName) => requestAddProject(prjName)
    case Message.RequestAllUnfinishedTasks => queryAllUnfinishedTasks()
    case Message.RequestUpdatePmdrCountInTask(taskid, pom) => requestUpdatePmdrCount(taskid, pom)
    case req: Message.RequestStartTask => requestStartTask(req)
    case req: Message.RequestRemainingTimeInPomodoro => queryRemainingTimeInPomodoro(req.taskId,req.priority)
    case req: Message.RequestUpdateLastPing => requestUpdateLastPing(req)
    case req: Message.RequestActiveTaskDetails => requestActiveTaskDetails(req.taskid)
    case req: Message.RequestDeleteActiveTask => requestDeleteActiveTask(req.taskid)
    case req: Message.RequestHistoricalTaskDuration => requestHistoricalTaskDuration(req.taskId)
    case req: Message.RequestUpdateTaskPriority => requestUpdateTaskPriority(req.taskid, req.priority)
    case req: Message.RequestAggRecordsByDateRange => requestAggregatedRecords(req)
    case Message.RequestActiveTasks => requestActiveTasks()
    case Message.RequestTaskDetails(taskid) => requestTaskDetails(taskid)
    case req: Message.RequestTaskStatusUpdate => requestTaskStatusUpdate(req.taskid, req.newStatus)
    case Message.RequestUpdateAllWindowFinishedToReady => requestUpdateAllWindowFinishedToReady()
    case r: Message.QueryResult =>
      handleReply(r)
      self ! PoisonPill
  }

  def queryGetAllProjects() : Unit = {
    replyTo = sender()
    handleReply = replyGetAllProjects
    db ! Message.QueryDB(0, "SELECT * FROM projects")
  }

  def replyGetAllProjects(r: Message.QueryResult) : Unit = {
    val projects = r.result.get.map(listToProj).toList
    replyTo ! Message.ReplyAllProjects(projects)
  }

  def listToProj(projAsList: List[String]) : Message.Project = {
    Message.Project(projAsList.head.toInt, projAsList(1))
  }

  def addNewTask(newTask: Message.AddTask) : Unit = {
    replyTo = sender()
    handleReply = replyAddTask

    val escapedName = newTask.name.replace("'", "''")
    val status = if (PRIORITY(newTask.priority) == PRIORITY("im")) STATUS("READY")
      else STATUS("WINDOW_FINISHED")

    db ! Message.QueryDB(0,s"INSERT INTO $TABLE_NAME_TASKS (PRJID, NAME, PRIORITY, STATUS, POMODOROS) " +
      s"VALUES (${newTask.prjId},'$escapedName',${PRIORITY(newTask.priority)},$status,0" +
      s")",update = true)
  }

  def replyAddTask(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyAddTask(ADDED)
      case Message.QueryResult(_, _, _, 23505) => replyTo ! Message.ReplyAddTask(DUPLICATE)
      case _ => replyTo ! Message.ReplyAddTask(ERROR)
    }
  }

  def addNewRecord(newRecord: Message.RequestAddRecord) : Unit = {
    replyTo = sender()
    handleReply = replyAddRecord
    db ! Message.QueryDB(0, s"INSERT INTO $TABLE_NAME_RECORDS (TSKID, END, DURATION_MS) " +
      s"VALUES (${newRecord.taskId},${newRecord.endTimestamp}," +
      s"${math.min(newRecord.duration, POMODORO_MAX_DURATION_MS)})", update = true)
  }

  def replyAddRecord(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyAddRecord(ADDED)
      case Message.QueryResult(_, _, _, 23505) => replyTo ! Message.ReplyAddRecord(DUPLICATE)
      case _ => replyTo ! Message.ReplyAddRecord(ERROR)
    }
  }

  def requestAddProject(projectName: String) : Unit = {
    replyTo = sender()
    handleReply = replyAddProject
    db ! Message.QueryDB(0, s"INSERT INTO $TABLE_NAME_PROJECTS (NAME) VALUES ('$projectName')",
      update = true)
  }

  def replyAddProject(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyAddProject(ADDED)
      case Message.QueryResult(_, _, _, 23505) => replyTo ! Message.ReplyAddProject(DUPLICATE)
      case _ => replyTo ! Message.ReplyAddProject(ERROR)
    }
  }

  def queryAllUnfinishedTasks(): Unit = {
    replyTo = sender()
    handleReply = replyAllUnfinishedTasks
    db ! Message.QueryDB(0, s"SELECT t.ID, t.PRJID, p.NAME, t.NAME, t.START, t.PRIORITY, t.STATUS, t.POMODOROS " +
      s"FROM $TABLE_NAME_TASKS t JOIN $TABLE_NAME_PROJECTS p " +
      s"ON t.PRJID = p.ID " +
      s" WHERE t.STATUS != ${STATUS("FINISHED")}")
  }

  def replyAllUnfinishedTasks(r: Message.QueryResult): Unit = {
    val tasks = r.result.get.map(listToTask).toList
    val tasksWithSelected = selectCurrentTask(tasks)
    replyTo ! Message.ReplyAllUnfinishedTasks(tasksWithSelected)
  }

  /**
    * Converts the result from the [[DatabaseActor]] to a [[Message.Task]] object.
    * @param taskAsList Single row from the DB.
    * @return [[Message.Task]] object.
    */
  def listToTask(taskAsList: List[String]) : Message.Task = {
    Message.Task(taskAsList.head.toInt, taskAsList(1).toInt, taskAsList(2), taskAsList(3), taskAsList(4), taskAsList(5).toInt,
      taskAsList(6).toInt, taskAsList(7).toInt, current = false)
  }

  /**
    * Selects one [[Message.Task]] that should be currently worked on. Using different logic for different priorities.
    * @param tasks A list of [[Message.Task]]
    * @return The same list of [[Message.Task]]s, but with one [[Message.Task]] that has the property [[Message.Task.current]] as true.
    */
  def selectCurrentTask(tasks: List[Message.Task]): List[Message.Task] = {
    val readyTasks = tasks.filter(_.status == STATUS("READY"))
    val (immTasks, otherTasks) = readyTasks.partition(_.priority == PRIORITY("im"))
    val (highTasks, regTasks) = otherTasks.partition(_.priority == PRIORITY("hi"))

    val selectedId = getImmPriorityTaskId(immTasks) match {
      case Some(id) => Some(id)
      case None => getOtherPriorityTaskId(highTasks) match {
        case Some(id) => Some(id)
        case None => getOtherPriorityTaskId(regTasks) match {
          case Some(id) => Some(id)
          case None => None
        }
      }
    }

    val tasksWithSelected = tasks.map(task => {
      val isCurrentTask = if (selectedId.isDefined && selectedId.get == task.id) true else false

      Message.Task(task.id,task.prjId,task.prjName,task.name,task.startTimestamp,task.priority,task.status,task.pomodoros, isCurrentTask)
    })

    tasksWithSelected
  }

  /**
    * Get the [[Message.Task.id]] of a [[Message.Task]] with an Immediate [[Message.Task.priority]] with the latest creation time.
    * @param tasks A [[List]] of immediate [[Message.Task.priority]] tasks.
    * @return An [[Message.Task.id]] of the selected [[Message.Task]].
    */
  def getImmPriorityTaskId(tasks: List[Message.Task]) : Option[Int] = {
    val formater = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")

    val idsAndDate = tasks.map(task => (task.id, formater.parseDateTime(task.startTimestamp)))

    if (idsAndDate.nonEmpty) Some(idsAndDate.maxBy(_._2.getMillis)._1)
    else None
  }

  /**
    * Get the [[Message.Task.id]] where the logic of the [[Message.Task.priority]] is the highest [[Message.Task.pomodoros]] count.
    * @param tasks List of [[Message.Task]]s
    * @return An [[Message.Task.id]] of the selected [[Message.Task]].
    */
  def getOtherPriorityTaskId(tasks: List[Message.Task]) : Option[Int] = {
    if (tasks.nonEmpty) Some(tasks.maxBy(_.pomodoros).id)
    else None
  }

  /**
    * Query the core.database about the duration of the [[Message.Task]] to later determine how much time left in the last
    * pomodoro.
    * @param taskId The [[Message.Task.id]] for which to calculate the remaining time in the pomodoro.
    * @param taskPriority The [[Message.Task.priority]]
    */
  def queryRemainingTimeInPomodoro(taskId: Int, taskPriority: Int) : Unit = {
    replyTo = sender()
    handleReply = replyRemainingTimeInPomodoro(taskPriority)
    db ! Message.QueryDB(0, s"SELECT SUM(DURATION_MS) FROM $TABLE_NAME_RECORDS " +
      s"WHERE TSKID = $taskId")
  }

  /**
    * Calculates how much time left in the last pomodoro.
    * @param priority The [[Message.Task.priority]].
    * @param r The reply from the [[DatabaseActor]], should contain the sum of all the records of the specific task.
    */
  def replyRemainingTimeInPomodoro(priority: Int)(r: Message.QueryResult) : Unit = {
    // If the task is brand new, the total duration that was done, is 0.
    val totalDuration = Try(r.result.get.head.head.toLong) match {
      case Success(x) => x
      case _ => 0
    }
    val numOfPomodorosDone = totalDuration / POMODORO_MAX_DURATION_MS
    val remainingTime = POMODORO_MAX_DURATION_MS - (totalDuration - (numOfPomodorosDone * POMODORO_MAX_DURATION_MS))
    replyTo ! Message.ReplyRemainingTimeInPomodoro(remainingTime)
  }

  /**
    * Request the total duration of a [[Message.Task]] from the Records table.
    * @param taskId The [[Message.Task.id]] to query.
    */
  def requestHistoricalTaskDuration(taskId: Int) : Unit = {
    replyTo = sender()
    handleReply = replyHistoricalTaskDuration
    db ! Message.QueryDB(0, s"SELECT SUM(DURATION_MS) FROM $TABLE_NAME_RECORDS " +
      s"WHERE TSKID = $taskId")
  }

  /**
    * Reply the total duration of a [[Message.Task]] from the records table.
    * @param r The result from the [[DatabaseActor]]
    */
  def replyHistoricalTaskDuration(r: Message.QueryResult) : Unit = {
    val totalDuration = Try(r.result.get.head.head.toLong) match {
      case Success(x) => x
      case _ => 0
    }

    replyTo ! Message.ReplyHistoricalTaskDuration(totalDuration)
  }

  /**
    * Request to update the [[Message.Task]]s number of [[Message.Task.pomodoros]].
    * @param taskId The [[Message.Task.id]] to update.
    * @param pmdrsToAdd The amount of [[Message.Task.pomodoros]] to add.
    */
  def requestUpdatePmdrCount(taskId: Int, pmdrsToAdd: Int) : Unit = {
    replyTo = sender()
    handleReply = replyUpdatePmdrCount
    db ! Message.QueryDB(0, s"UPDATE $TABLE_NAME_TASKS SET POMODOROS=POMODOROS+$pmdrsToAdd " +
      s"WHERE ID=$taskId", update = true)
  }

  /**
    * Handle the reply from updating the number of [[Message.Task.pomodoros]]
    * @param r The reply.
    */
  def replyUpdatePmdrCount(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyUpdatePmdrCountInTask(ADDED)
      case _ => replyTo ! Message.ReplyUpdatePmdrCountInTask(ERROR)
    }
  }

  /**
    * Handles the request for the details of a specific task by [[Message.Task.id]]
    * @param taskid The id of the [[Message.Task]] to query.
    */
  def requestTaskDetails(taskid: Int) : Unit = {
    replyTo = sender()
    handleReply = replyTaskDetails
    db ! Message.QueryDB(0, s"SELECT t.ID, t.PRJID, p.NAME, t.NAME, t.START, t.PRIORITY, t.STATUS, t.POMODOROS " +
      s"FROM $TABLE_NAME_TASKS t JOIN $TABLE_NAME_PROJECTS p " +
      s"ON t.PRJID = p.ID " +
      s"WHERE t.ID = $taskid")
  }

  /**
    * Replys to the requestor the queried [[Message.Task]].
    * @param r The reply from the [[DatabaseActor]].
    */
  def replyTaskDetails(r: Message.QueryResult) : Unit = {
    val task = r.result.get.map(listToTask).toList.head
    replyTo ! Message.ReplyTaskDetails(task)
  }

  /**
    * Updates the [[Message.Task.status]] of the [[Message.Task]]
    * @param taskId The [[Message.Task.id]] to update.
    * @param newStatus The [[Message.Task.status]] to update to.
    */
  def requestTaskStatusUpdate(taskId: Int, newStatus: Int) : Unit = {
    replyTo = sender()
    handleReply = replyTaskStatusUpdate
    db ! Message.QueryDB(0, s"UPDATE $TABLE_NAME_TASKS SET STATUS=$newStatus " +
      s"WHERE ID=$taskId", update = true)
  }

  /**
    * Handles the reply of the update [[Message.Task.status]].
 *
    * @param r The [[Message.QueryResult]] from the [[DatabaseActor]].
    */
  def replyTaskStatusUpdate(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyTaskStatusUpdate(UPDATED)
      case _ => replyTo ! Message.ReplyTaskStatusUpdate(ERROR)
    }
  }

  /**
    * Update the [[Message.Task.status]] of all [[Message.Task]]s that are WINDOW_FINISHED to READY.
    */
  def requestUpdateAllWindowFinishedToReady() : Unit = {
    replyTo = sender()
    handleReply = replyUpdateAllWindowFinishedToReady

    db ! Message.QueryDB(0, s"UPDATE $TABLE_NAME_TASKS SET STATUS=${STATUS("READY")} " +
      s"WHERE STATUS=${STATUS("WINDOW_FINISHED")}", update = true)
  }

  /**
    * Handles the reply of update all WINDOW_FINISHED [[Message.Task.status]]s to READY [[Message.Task.status]]s.
 *
    * @param r The [[Message.QueryResult]].
    */
  def replyUpdateAllWindowFinishedToReady(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyUpdateAllWindowFinishedToReady(UPDATED)
      case _ => replyTo ! Message.ReplyUpdateAllWindowFinishedToReady(ERROR)
    }
  }

  /**
    * Starts a [[Message.Task]], by adding it to the active tasks table.
    * @param req The [[Message.RequestStartTask]] request parameters.
    */
  def requestStartTask(req: Message.RequestStartTask) : Unit = {
    replyTo = sender()
    handleReply = replyStartTask

    db ! Message.QueryDB(0, s"INSERT INTO $TABLE_NAME_ACTIVE_TASK " +
      s"(TSKID, INITIAL_DURATION) " +
      s"VALUES (${req.taskid}, ${req.initialDuration})", update = true)
  }

  /**
    * Handles the reply of the [[requestStartTask()]] function.
 *
    * @param r The [[Message.QueryResult]]
    */
  def replyStartTask(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyStartTask(ADDED)
      case _ => replyTo ! Message.ReplyStartTask(ERROR)
    }
  }

  /**
    * Update the last time a [[Message.Task]] was pinged.
    * @param req The [[Message.RequestUpdateLastPing]]
    */
  def requestUpdateLastPing(req: Message.RequestUpdateLastPing) : Unit = {
    replyTo = sender()
    handleReply = replyUpdateLastPing

    db ! Message.QueryDB(0, s"UPDATE $TABLE_NAME_ACTIVE_TASK SET LAST_PING=CURRENT_TIMESTAMP() " +
      s"WHERE TSKID=${req.taskid}", update = true)
  }

  /**
    * Handles the reply of the [[requestUpdateLastPing()]] function.
 *
    * @param r The [[Message.QueryResult]]
    */
  def replyUpdateLastPing(r: Message.QueryResult) : Unit = {
    r match {
      case Message.QueryResult(_, _, _, 0) => replyTo ! Message.ReplyUpdateLastPing(UPDATED)
      case _ => replyTo ! Message.ReplyUpdateLastPing(ERROR)
    }
  }

  /**
    * Queries a specific task from the [[TABLE_NAME_ACTIVE_TASK]] table.
    * @param taskId [[Message.Task.id]] to query.
    */
  def requestActiveTaskDetails(taskId: Int) : Unit = {
    replyTo = sender()
    handleReply = replyActiveTaskDetails

    db ! Message.QueryDB(0, s"SELECT TSKID, START, LAST_PING, INITIAL_DURATION " +
      s"FROM $TABLE_NAME_ACTIVE_TASK WHERE TSKID = $taskId")
  }

  /**
    * Handle the reply of [[Message.RequestActiveTaskDetails]]
 *
    * @param r The [[Message.QueryResult]]
    */
  def replyActiveTaskDetails(r: Message.QueryResult) : Unit = {
    val reply = r match {
      case Message.QueryResult(_, Some(result), "", 0) if result.nonEmpty =>
        Message.ReplyActiveTaskDetails(SUCCESS, listToActiveTask(result.head))
      case _ => Message.ReplyActiveTaskDetails(ERROR, Message.ActiveTask(0, "", "", 0))
    }

    replyTo ! reply
  }

  /**
    * Parse a list of strings to an ActiveTask object.
    * @param stringList A list of strings that contains all the necessary fields to create an ActiveTask.
    * @return An ActiveTask object.
    */
  def listToActiveTask(stringList: List[String]) : Message.ActiveTask = {
    Message.ActiveTask(stringList.head.toInt, stringList(1), stringList(2), stringList(3).toLong)
  }

  def requestDeleteActiveTask(taskid: Int) : Unit = {
    replyTo = sender()
    handleReply = replyDeleteActiveTask

    db ! Message.QueryDB(0, s"DELETE FROM $TABLE_NAME_ACTIVE_TASK WHERE TSKID=$taskid", update = true)
  }

  def replyDeleteActiveTask(r: Message.QueryResult) : Unit = {
    val reply = r match {
      case Message.QueryResult(_, Some(_), _, 0) => Message.ReplyDeleteActiveTask(SUCCESS)
      case _ => Message.ReplyDeleteActiveTask(ERROR)
    }

    replyTo ! reply
  }

  def requestActiveTasks() : Unit = {
    replyTo = sender()
    handleReply = replyActiveTasks

    db ! Message.QueryDB(0, s"SELECT TSKID, START, LAST_PING, INITIAL_DURATION FROM $TABLE_NAME_ACTIVE_TASK")
  }

  def replyActiveTasks(r: Message.QueryResult) : Unit = {
    val reply = r match {
      case Message.QueryResult(_, Some(result), _, 0) =>
        Message.ReplyActiveTasks(SUCCESS, result.map(listToActiveTask).toList)
      case _ => Message.ReplyActiveTasks(ERROR, List.empty[Message.ActiveTask])
    }

    replyTo ! reply
  }

  def requestUpdateTaskPriority(taskid: Int, priority: Int) : Unit = {
    replyTo = sender()
    handleReply = replyUpdateTaskPriority

    db ! Message.QueryDB(0, s"UPDATE $TABLE_NAME_TASKS SET PRIORITY=$priority WHERE ID=$taskid",
      update = true)
  }

  def replyUpdateTaskPriority(r: Message.QueryResult) : Unit = {
    val reply = r match {
      case Message.QueryResult(_, _, _, 0) => Message.ReplyUpdateTaskPriority(UPDATED)
      case _ => Message.ReplyUpdateTaskPriority(ERROR)
    }

    replyTo ! reply
  }

  /**
    * Takes a list of strings and converts it to a single [[Message.Record]] object.
    * @param rawRecord A list of Strings that describe one Record.
    * @return A Record object.
    */
  def listToRecord(rawRecord: List[String]) : Message.Record = {
    Message.Record(rawRecord.head.toInt, rawRecord(1).toInt, rawRecord(2).toLong, rawRecord(3).toLong)
  }

  /**
    * Takes a list of strings and converts it to a single [[Message.AggregatedRecord]] object.
    * @param aggRawRecord A list of Strings that describe one [[Message.AggregatedRecord]].
    * @return An [[Message.AggregatedRecord]] that was constructed from the list.
    */
  def listToAggRecord(aggRawRecord: List[String]) : Message.AggregatedRecord = {
    Message.AggregatedRecord(aggRawRecord.head, aggRawRecord(1).toLong)
  }

  /**
    * Queries the core.database actor to return the duration of all the records in a date range, and aggregates them by
    * task name.
    * @param request The request object with the from and to dates.
    */
  def requestAggregatedRecords(request: Message.RequestAggRecordsByDateRange) : Unit = {
    replyTo = sender()
    handleReply = replyAggregatedRecords

    db ! Message.QueryDB(0,
      s" SELECT t.name, SUM(duration_ms) FROM $TABLE_NAME_RECORDS r " +
      s" JOIN $TABLE_NAME_TASKS t ON r.tskid=t.id " +
      s" WHERE end > ${request.from} AND end < ${request.to} " +
      s" GROUP BY t.name " +
      s" ORDER BY 2 DESC")
  }

  def replyAggregatedRecords(r: Message.QueryResult) : Unit = {
    val reply = r match {
      case Message.QueryResult(_, Some(result), _, 0) =>
        Message.ReplyAggRecordsByDateRange(SUCCESS, result.map(listToAggRecord).toList)
      case _ => Message.ReplyAggRecordsByDateRange(ERROR, List.empty[Message.AggregatedRecord])
    }
    replyTo ! reply
  }
}
