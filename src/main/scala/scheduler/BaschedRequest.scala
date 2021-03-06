package scheduler

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.http.javadsl.model.ws.Message
import database.DatabaseActor
import database.DatabaseActor.QueryResult
import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTime
import webserver.WebServerActor
import scheduler.BaschedRequest._

import scala.util.{Success, Try}

/**
  * A companion object for [[BaschedRequest]]. Contains all the messages that are handle by it and a props function
  * to use when creating an actor of type [[BaschedRequest]].
  */
object BaschedRequest {

  sealed trait Message
  case object RequestAllProjects extends Message
  case class Project(id: Int, name: String)
  case class ReplyAllProjects(projects: List[Project]) extends Message

  case class AddTask(prjId: Int, name: String, priority: String) extends Message
  val ADDED = 0
  val DUPLICATE = 1
  val ERROR = 2
  val UPDATED = 3
  val SUCCESS = 4
  case class ReplyAddTask(response: Int) extends Message

  case object RequestAllUnfinishedTasks extends Message
  case class Task(id: Int, prjId: Int, prjName: String, name: String, startTimestamp: String, priority: Int, status: Int,
                  pomodoros: Int, current: Boolean)
  case class ReplyAllUnfinishedTasks(tasks: List[Task])

  case class RequestAddRecord(taskId: Int, endTimestamp: Long, duration: Long) extends Message
  case class ReplyAddRecord(response: Int)

  case class RequestAddProject(projectName: String) extends Message
  case class ReplyAddProject(response: Int)

  case class RequestRemainingTimeInPomodoro(taskId: Int, priority: Int) extends Message
  case class ReplyRemainingTimeInPomodoro(duration: Long)

  case class RequestHistoricalTaskDuration(taskId: Int) extends Message
  case object RequestTodaysHistoricalTaskDuration extends Message
  case class ReplyHistoricalTaskDuration(duration: Long)

  case class RequestUpdatePmdrCountInTask(taskId: Int, pmdrsToAdd: Int) extends Message
  case class ReplyUpdatePmdrCountInTask(response: Int)

  case class RequestTaskDetails(taskId: Int) extends Message
  case class ReplyTaskDetails(task: Task)

  case class RequestTaskStatusUpdate(taskid: Int, newStatus: Int) extends Message
  case class ReplyTaskStatusUpdate(response: Int)

  case object RequestUpdateAllWindowFinishedToReady extends Message
  case class ReplyUpdateAllWindowFinishedToReady(response: Int)

  case class RequestStartTask(taskid: Int, initialDuration: Long) extends Message
  case class ReplyStartTask(response: Int)

  case class RequestUpdateLastPing(taskid: Int) extends Message
  case class ReplyUpdateLastPing(response: Int)

  case class RequestActiveTaskDetails(taskid: Option[Int]) extends Message
  case class ActiveTask(taskid: Int, startTimestamp: String, endTimestamp: String, initDuration: Long)
  case class ReplyActiveTaskDetails(status: Int, activeTask: ActiveTask)

  case class RequestDeleteActiveTask(taskid: Int) extends Message
  case class ReplyDeleteActiveTask(response: Int)

  case object RequestActiveTasks extends Message
  case class ReplyActiveTasks(status: Int, activeTasks: List[ActiveTask])

  case class RequestUpdateTaskPriority(taskid: Int, priority: Int) extends Message
  case class ReplyUpdateTaskPriority(response: Int)

  case class Record(id: Int, taskId: Int, end: Long, duration_ms: Long)
  case class AggregatedRecord(taskName: String, duration: Long)
  case class RequestAggRecordsByDateRange(from: String, to: String) extends Message
  case class ReplyAggRecordsByDateRange(response: Int, aggRecords: List[AggregatedRecord])

  /**
    * Returns a [[Props]] object with instantiated [[BaschedRequest]] class.
    * @param db The [[DatabaseActor]] that the queries will be sent to.
    * @return Returns a [[Props]] object with instantiated [[BaschedRequest]] class.
    */
  def props(db: ActorRef): Props = Props(new BaschedRequest(db))

}

/**
  * A single request actor, handles only one request, so each request should instantiate this actor.
  * Will return the result of the request straight to the sender.
  * It mainly contains the logic of the Basched app, and it is the main link between the [[DatabaseActor]] and the
  * [[WebServerActor]].
  * @param db A [[DatabaseActor]] that the basched request will query.
  */
class BaschedRequest(db: ActorRef) extends Actor with ActorLogging {

  var replyTo: ActorRef = _
  var handleReply: (DatabaseActor.QueryResult) => Unit = _

  override def receive: Receive = {
    case RequestAllProjects => queryGetAllProjects()
    case addTask: AddTask => addNewTask(addTask)
    case addRecord: RequestAddRecord => addNewRecord(addRecord)
    case RequestAddProject(prjName) => requestAddProject(prjName)
    case RequestAllUnfinishedTasks => queryAllUnfinishedTasks()
    case RequestUpdatePmdrCountInTask(taskid, pom) => requestUpdatePmdrCount(taskid, pom)
    case req: RequestStartTask => requestStartTask(req)
    case req: RequestRemainingTimeInPomodoro => queryRemainingTimeInPomodoro(req.taskId,req.priority)
    case req: RequestUpdateLastPing => requestUpdateLastPing(req)
    case req: RequestActiveTaskDetails => requestActiveTaskDetails(req.taskid)
    case req: RequestDeleteActiveTask => requestDeleteActiveTask(req.taskid)
    case req: RequestHistoricalTaskDuration => requestHistoricalTaskDuration(req.taskId)
    case RequestTodaysHistoricalTaskDuration => requestTodaysHistoricalTaskDuration
    case req: RequestUpdateTaskPriority => requestUpdateTaskPriority(req.taskid, req.priority)
    case req: RequestAggRecordsByDateRange => requestAggregatedRecords(req)
    case RequestActiveTasks => requestActiveTasks()
    case RequestTaskDetails(taskid) => requestTaskDetails(taskid)
    case req: RequestTaskStatusUpdate => requestTaskStatusUpdate(req.taskid, req.newStatus)
    case RequestUpdateAllWindowFinishedToReady => requestUpdateAllWindowFinishedToReady()
    case r: DatabaseActor.QueryResult =>
      handleReply(r)
      self ! PoisonPill
  }

  def queryGetAllProjects() : Unit = {
    replyTo = sender()
    handleReply = replyGetAllProjects
    db ! DatabaseActor.QueryDB(0, "SELECT * FROM projects")
  }

  def replyGetAllProjects(r: DatabaseActor.QueryResult) : Unit = {
    val projects = r.result.get.map(listToProj).toList
    replyTo ! ReplyAllProjects(projects)
  }

  def listToProj(projAsList: List[String]) : Project = {
    Project(projAsList.head.toInt, projAsList(1))
  }

  def addNewTask(newTask: AddTask) : Unit = {
    replyTo = sender()
    handleReply = replyAddTask

    val escapedName = newTask.name.replace("'", "''")
    val status = if (Basched.PRIORITY(newTask.priority) == Basched.PRIORITY("im")) Basched.STATUS("READY")
      else Basched.STATUS("WINDOW_FINISHED")

    db ! DatabaseActor.QueryDB(0,s"INSERT INTO ${Basched.TABLE_NAME_TASKS} (PRJID, NAME, PRIORITY, STATUS, POMODOROS) " +
      s"VALUES (${newTask.prjId},'$escapedName',${Basched.PRIORITY(newTask.priority)},$status,0" +
      s")",update = true)
  }

  def replyAddTask(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyAddTask(BaschedRequest.ADDED)
      case QueryResult(_, _, _, 23505) => replyTo ! ReplyAddTask(BaschedRequest.DUPLICATE)
      case _ => replyTo ! ReplyAddTask(BaschedRequest.ERROR)
    }
  }

  def addNewRecord(newRecord: RequestAddRecord) : Unit = {
    replyTo = sender()
    handleReply = replyAddRecord
    db ! DatabaseActor.QueryDB(0, s"INSERT INTO ${Basched.TABLE_NAME_RECORDS} (TSKID, END, DURATION_MS) " +
      s"VALUES (${newRecord.taskId},${newRecord.endTimestamp}," +
      s"${math.min(newRecord.duration, Basched.POMODORO_MAX_DURATION_MS)})", update = true)
  }

  def replyAddRecord(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyAddRecord(BaschedRequest.ADDED)
      case QueryResult(_, _, _, 23505) => replyTo ! ReplyAddRecord(BaschedRequest.DUPLICATE)
      case _ => replyTo ! ReplyAddRecord(BaschedRequest.ERROR)
    }
  }

  def requestAddProject(projectName: String) : Unit = {
    replyTo = sender()
    handleReply = replyAddProject
    db ! DatabaseActor.QueryDB(0, s"INSERT INTO ${Basched.TABLE_NAME_PROJECTS} (NAME) VALUES ('$projectName')",
      update = true)
  }

  def replyAddProject(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyAddProject(BaschedRequest.ADDED)
      case QueryResult(_, _, _, 23505) => replyTo ! ReplyAddProject(BaschedRequest.DUPLICATE)
      case _ => replyTo ! ReplyAddProject(BaschedRequest.ERROR)
    }
  }

  def queryAllUnfinishedTasks(): Unit = {
    replyTo = sender()
    handleReply = replyAllUnfinishedTasks
    db ! DatabaseActor.QueryDB(0, s"SELECT t.ID, t.PRJID, p.NAME, t.NAME, t.START, t.PRIORITY, t.STATUS, t.POMODOROS " +
      s"FROM ${Basched.TABLE_NAME_TASKS} t JOIN ${Basched.TABLE_NAME_PROJECTS} p " +
      s"ON t.PRJID = p.ID " +
      s" WHERE t.STATUS != ${Basched.STATUS("FINISHED")}")
  }

  def replyAllUnfinishedTasks(r: DatabaseActor.QueryResult): Unit = {
    val tasks = r.result.get.map(listToTask).toList
    val tasksWithSelected = selectCurrentTask(tasks)
    replyTo ! ReplyAllUnfinishedTasks(tasksWithSelected)
  }

  /**
    * Converts the result from the [[DatabaseActor]] to a [[Task]] object.
    * @param taskAsList Single row from the DB.
    * @return [[Task]] object.
    */
  def listToTask(taskAsList: List[String]) : Task = {
    Task(taskAsList(0).toInt, taskAsList(1).toInt, taskAsList(2), taskAsList(3), taskAsList(4), taskAsList(5).toInt,
      taskAsList(6).toInt, taskAsList(7).toInt, current = false)
  }

  /**
    * Selects one [[Task]] that should be currently worked on. Using different logic for different priorities.
    * @param tasks A list of [[Task]]
    * @return The same list of [[Task]]s, but with one [[Task]] that has the property [[Task.current]] as true.
    */
  def selectCurrentTask(tasks: List[Task]): List[Task] = {
    val readyTasks = tasks.filter(_.status == Basched.STATUS("READY"))
    val (immTasks, otherTasks) = readyTasks.partition(_.priority == Basched.PRIORITY("im"))
    val (highTasks, regTasks) = otherTasks.partition(_.priority == Basched.PRIORITY("hi"))

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

      Task(task.id,task.prjId,task.prjName,task.name,task.startTimestamp,task.priority,task.status,task.pomodoros, isCurrentTask)
    })

    tasksWithSelected
  }

  /**
    * Get the [[Task.id]] of a [[Task]] with an Immediate [[Task.priority]] with the latest creation time.
    * @param tasks A [[List]] of immediate [[Task.priority]] tasks.
    * @return An [[Task.id]] of the selected [[Task]].
    */
  def getImmPriorityTaskId(tasks: List[Task]) : Option[Int] = {
    val formater = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")

    val idsAndDate = tasks.map(task => (task.id, formater.parseDateTime(task.startTimestamp)))

    if (idsAndDate.nonEmpty) Some(idsAndDate.maxBy(_._2.getMillis)._1)
    else None
  }

  /**
    * Get the [[Task.id]] where the logic of the [[Task.priority]] is the highest [[Task.pomodoros]] count.
    * @param tasks List of [[Task]]s
    * @return An [[Task.id]] of the selected [[Task]].
    */
  def getOtherPriorityTaskId(tasks: List[Task]) : Option[Int] = {
    if (tasks.nonEmpty) Some(tasks.maxBy(_.pomodoros).id)
    else None
  }

  /**
    * Query the database about the duration of the [[Task]] to later determine how much time left in the last
    * pomodoro.
    * @param taskId The [[Task.id]] for which to calculate the remaining time in the pomodoro.
    * @param taskPriority The [[Task.priority]]
    */
  def queryRemainingTimeInPomodoro(taskId: Int, taskPriority: Int) : Unit = {
    replyTo = sender()
    handleReply = replyRemainingTimeInPomodoro(taskPriority)
    db ! DatabaseActor.QueryDB(0, s"SELECT SUM(DURATION_MS) FROM ${Basched.TABLE_NAME_RECORDS} " +
      s"WHERE TSKID = $taskId")
  }

  /**
    * Calculates how much time left in the last pomodoro.
    * @param priority The [[Task.priority]].
    * @param r The reply from the [[DatabaseActor]], should contain the sum of all the records of the specific task.
    */
  def replyRemainingTimeInPomodoro(priority: Int)(r: DatabaseActor.QueryResult) : Unit = {
    // If the task is brand new, the total duration that was done, is 0.
    val totalDuration = Try(r.result.get.head.head.toLong) match {
      case Success(x) => x
      case _ => 0
    }
    val numOfPomodorosDone = totalDuration / Basched.POMODORO_MAX_DURATION_MS
    val remainingTime = Basched.POMODORO_MAX_DURATION_MS - (totalDuration - (numOfPomodorosDone * Basched.POMODORO_MAX_DURATION_MS))
    replyTo ! ReplyRemainingTimeInPomodoro(remainingTime)
  }

  /**
    * Request the total duration of a [[Task]] from the Records table.
    * @param taskId The [[Task.id]] to query.
    */
  def requestHistoricalTaskDuration(taskId: Int) : Unit = {
    replyTo = sender()
    handleReply = replyHistoricalTaskDuration
    db ! DatabaseActor.QueryDB(0, s"SELECT SUM(DURATION_MS) FROM ${Basched.TABLE_NAME_RECORDS} " +
      s"WHERE TSKID = $taskId")
  }

  /**
    * Reply the total duration of a [[Task]] from the records table.
    * @param r The result from the [[DatabaseActor]]
    */
  def replyHistoricalTaskDuration(r: DatabaseActor.QueryResult) : Unit = {
    val totalDuration = Try(r.result.get.head.head.toLong) match {
      case Success(x) => x
      case _ => 0
    }

    replyTo ! ReplyHistoricalTaskDuration(totalDuration)
  }


  /**
    * Reply the total duration of all the records that ended today.
    * Using this method I should be able to calculate how much pomodoros
    * were finished today.
    */
  def requestTodaysHistoricalTaskDuration : Unit = {
    replyTo = sender()
    handleReply = replyHistoricalTaskDuration

    val startOfDay_ms = new DateTime().withTimeAtStartOfDay().getMillis

    db ! DatabaseActor.QueryDB(0, s"SELECT SUM(DURATION_MS) FROM ${Basched.TABLE_NAME_RECORDS} WHERE END > $startOfDay_ms")
  }

  /**
    * Request to update the [[Task]]s number of [[Task.pomodoros]].
    * @param taskId The [[Task.id]] to update.
    * @param pmdrsToAdd The amount of [[Task.pomodoros]] to add.
    */
  def requestUpdatePmdrCount(taskId: Int, pmdrsToAdd: Int) : Unit = {
    replyTo = sender()
    handleReply = replyUpdatePmdrCount
    db ! DatabaseActor.QueryDB(0, s"UPDATE ${Basched.TABLE_NAME_TASKS} SET POMODOROS=POMODOROS+$pmdrsToAdd " +
      s"WHERE ID=$taskId", update = true)
  }

  /**
    * Handle the reply from updating the number of [[Task.pomodoros]]
    * @param r The reply.
    */
  def replyUpdatePmdrCount(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyUpdatePmdrCountInTask(BaschedRequest.ADDED)
      case _ => replyTo ! ReplyUpdatePmdrCountInTask(BaschedRequest.ERROR)
    }
  }

  /**
    * Handles the request for the details of a specific task by [[Task.id]]
    * @param taskid The id of the [[Task]] to query.
    */
  def requestTaskDetails(taskid: Int) : Unit = {
    replyTo = sender()
    handleReply = replyTaskDetails
    db ! DatabaseActor.QueryDB(0, s"SELECT t.ID, t.PRJID, p.NAME, t.NAME, t.START, t.PRIORITY, t.STATUS, t.POMODOROS " +
      s"FROM ${Basched.TABLE_NAME_TASKS} t JOIN ${Basched.TABLE_NAME_PROJECTS} p " +
      s"ON t.PRJID = p.ID " +
      s"WHERE t.ID = $taskid")
  }

  /**
    * Replys to the requestor the queried [[Task]].
    * @param r The reply from the [[DatabaseActor]].
    */
  def replyTaskDetails(r: DatabaseActor.QueryResult) : Unit = {
    val task = r.result.get.map(listToTask).toList.head
    replyTo ! ReplyTaskDetails(task)
  }

  /**
    * Updates the [[Task.status]] of the [[Task]]
    * @param taskId The [[Task.id]] to update.
    * @param newStatus The [[Task.status]] to update to.
    */
  def requestTaskStatusUpdate(taskId: Int, newStatus: Int) : Unit = {
    replyTo = sender()
    handleReply = replyTaskStatusUpdate
    db ! DatabaseActor.QueryDB(0, s"UPDATE ${Basched.TABLE_NAME_TASKS} SET STATUS=$newStatus " +
      s"WHERE ID=$taskId", update = true)
  }

  /**
    * Handles the reply of the update [[Task.status]].
    * @param r The [[DatabaseActor.QueryResult]] from the [[DatabaseActor]].
    */
  def replyTaskStatusUpdate(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyTaskStatusUpdate(BaschedRequest.UPDATED)
      case _ => replyTo ! ReplyTaskStatusUpdate(BaschedRequest.ERROR)
    }
  }

  /**
    * Update the [[Task.status]] of all [[Task]]s that are WINDOW_FINISHED to READY.
    */
  def requestUpdateAllWindowFinishedToReady() : Unit = {
    replyTo = sender()
    handleReply = replyUpdateAllWindowFinishedToReady

    db ! DatabaseActor.QueryDB(0, s"UPDATE ${Basched.TABLE_NAME_TASKS} SET STATUS=${Basched.STATUS("READY")} " +
      s"WHERE STATUS=${Basched.STATUS("WINDOW_FINISHED")}", update = true)
  }

  /**
    * Handles the reply of update all WINDOW_FINISHED [[Task.status]]s to READY [[Task.status]]s.
    * @param r The [[DatabaseActor.QueryResult]].
    */
  def replyUpdateAllWindowFinishedToReady(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyUpdateAllWindowFinishedToReady(BaschedRequest.UPDATED)
      case _ => replyTo ! ReplyUpdateAllWindowFinishedToReady(BaschedRequest.ERROR)
    }
  }

  /**
    * Starts a [[Task]], by adding it to the active tasks table.
    * @param req The [[RequestStartTask]] request parameters.
    */
  def requestStartTask(req: RequestStartTask) : Unit = {
    replyTo = sender()
    handleReply = replyStartTask

    db ! DatabaseActor.QueryDB(0, s"INSERT INTO ${Basched.TABLE_NAME_ACTIVE_TASK} " +
      s"(TSKID, INITIAL_DURATION) " +
      s"VALUES (${req.taskid}, ${req.initialDuration})", update = true)
  }

  /**
    * Handles the reply of the [[requestStartTask()]] function.
    * @param r The [[DatabaseActor.QueryResult]]
    */
  def replyStartTask(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyStartTask(BaschedRequest.ADDED)
      case _ => replyTo ! ReplyStartTask(BaschedRequest.ERROR)
    }
  }

  /**
    * Update the last time a [[Task]] was pinged.
    * @param req The [[RequestUpdateLastPing]]
    */
  def requestUpdateLastPing(req: RequestUpdateLastPing) : Unit = {
    replyTo = sender()
    handleReply = replyUpdateLastPing

    db ! DatabaseActor.QueryDB(0, s"UPDATE ${Basched.TABLE_NAME_ACTIVE_TASK} SET LAST_PING=CURRENT_TIMESTAMP() " +
      s"WHERE TSKID=${req.taskid}", update = true)
  }

  /**
    * Handles the reply of the [[requestUpdateLastPing()]] function.
    * @param r The [[DatabaseActor.QueryResult]]
    */
  def replyUpdateLastPing(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyUpdateLastPing(BaschedRequest.UPDATED)
      case _ => replyTo ! ReplyUpdateLastPing(BaschedRequest.ERROR)
    }
  }

  /**
    * Queries a specific task from the [[Basched.TABLE_NAME_ACTIVE_TASK]] table.
    * If a [[Task.id]] is passed, than query it specifically.
    * Otherwise, just query whatever there is in the table.
    * @param taskId [[Task.id]] to query.
    */
  def requestActiveTaskDetails(taskId: Option[Int]) : Unit = {
    replyTo = sender()
    handleReply = replyActiveTaskDetails

    val sqlStmtHeader = s"SELECT TSKID, START, LAST_PING, INITIAL_DURATION " +
    s"FROM ${Basched.TABLE_NAME_ACTIVE_TASK} "

    val sqlStmt = taskId match {
      case Some(id) => s"$sqlStmtHeader WHERE TSKID = $id"
      case _ => sqlStmtHeader
    }

    db ! DatabaseActor.QueryDB(0, sqlStmt)
  }

  /**
    * Handle the reply of [[RequestActiveTaskDetails]]
    * @param r The [[DatabaseActor.QueryResult]]
    */
  def replyActiveTaskDetails(r: DatabaseActor.QueryResult) : Unit = {
    val reply = r match {
      case QueryResult(_, Some(result), "", 0) if result.nonEmpty => ReplyActiveTaskDetails(SUCCESS, listToActiveTask(result.head))
      case _ => ReplyActiveTaskDetails(ERROR, ActiveTask(0, "", "", 0))
    }

    replyTo ! reply
  }

  /**
    * Parse a list of strings to an ActiveTask object.
    * @param stringList A list of strings that contains all the necessary fields to create an ActiveTask.
    * @return An ActiveTask object.
    */
  def listToActiveTask(stringList: List[String]) : ActiveTask = {
    ActiveTask(stringList.head.toInt, stringList(1), stringList(2), stringList(3).toLong)
  }

  def requestDeleteActiveTask(taskid: Int) : Unit = {
    replyTo = sender()
    handleReply = replyDeleteActiveTask

    db ! DatabaseActor.QueryDB(0, s"DELETE FROM ${Basched.TABLE_NAME_ACTIVE_TASK} WHERE TSKID=$taskid", update = true)
  }

  def replyDeleteActiveTask(r: DatabaseActor.QueryResult) : Unit = {
    val reply = r match {
      case QueryResult(_, Some(_), _, 0) => ReplyDeleteActiveTask(SUCCESS)
      case _ => ReplyDeleteActiveTask(ERROR)
    }

    replyTo ! reply
  }

  def requestActiveTasks() : Unit = {
    replyTo = sender()
    handleReply = replyActiveTasks

    db ! DatabaseActor.QueryDB(0, s"SELECT TSKID, START, LAST_PING, INITIAL_DURATION FROM ${Basched.TABLE_NAME_ACTIVE_TASK}")
  }

  def replyActiveTasks(r: DatabaseActor.QueryResult) : Unit = {
    val reply = r match {
      case QueryResult(_, Some(result), _, 0) => ReplyActiveTasks(SUCCESS, result.map(listToActiveTask).toList)
      case _ => ReplyActiveTasks(ERROR, List.empty[BaschedRequest.ActiveTask])
    }

    replyTo ! reply
  }

  def requestUpdateTaskPriority(taskid: Int, priority: Int) : Unit = {
    replyTo = sender()
    handleReply = replyUpdateTaskPriority

    db ! DatabaseActor.QueryDB(0, s"UPDATE ${Basched.TABLE_NAME_TASKS} SET PRIORITY=$priority WHERE ID=$taskid",
      update = true)
  }

  def replyUpdateTaskPriority(r: DatabaseActor.QueryResult) : Unit = {
    val reply = r match {
      case QueryResult(_, _, _, 0) => ReplyUpdateTaskPriority(BaschedRequest.UPDATED)
      case _ => ReplyUpdateTaskPriority(BaschedRequest.ERROR)
    }

    replyTo ! reply
  }

  /**
    * Takes a list of strings and converts it to a single [[Record]] object.
    * @param rawRecord A list of Strings that describe one Record.
    * @return A Record object.
    */
  def listToRecord(rawRecord: List[String]) : Record = {
    Record(rawRecord.head.toInt, rawRecord(1).toInt, rawRecord(2).toLong, rawRecord(3).toLong)
  }

  /**
    * Takes a list of strings and converts it to a single [[AggregatedRecord]] object.
    * @param aggRawRecord A list of Strings that describe one [[AggregatedRecord]].
    * @return An [[AggregatedRecord]] that was constructed from the list.
    */
  def listToAggRecord(aggRawRecord: List[String]) : AggregatedRecord = {
    AggregatedRecord(aggRawRecord.head, aggRawRecord(1).toLong)
  }

  /**
    * Queries the database actor to return the duration of all the records in a date range, and aggregates them by
    * task name.
    * @param request The request object with the from and to dates.
    */
  def requestAggregatedRecords(request: RequestAggRecordsByDateRange) : Unit = {
    replyTo = sender()
    handleReply = replyAggregatedRecords

    db ! DatabaseActor.QueryDB(0,
      s" SELECT t.name, SUM(duration_ms) FROM ${Basched.TABLE_NAME_RECORDS} r " +
      s" JOIN ${Basched.TABLE_NAME_TASKS} t ON r.tskid=t.id " +
      s" WHERE end > ${request.from} AND end < ${request.to} " +
      s" GROUP BY t.name " +
      s" ORDER BY 2 DESC")
  }

  def replyAggregatedRecords(r: DatabaseActor.QueryResult) : Unit = {
    val reply = r match {
      case QueryResult(_, Some(result), _, 0) => ReplyAggRecordsByDateRange(SUCCESS, result.map(listToAggRecord).toList)
      case _ => ReplyAggRecordsByDateRange(ERROR, List.empty[AggregatedRecord])
    }
    replyTo ! reply
  }
}
