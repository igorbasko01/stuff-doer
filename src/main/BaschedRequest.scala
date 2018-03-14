package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import main.BaschedRequest._
import main.DatabaseActor.QueryResult
import org.joda.time.format.DateTimeFormat

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
  case class ReplyAddTask(response: Int) extends Message

  case object RequestAllUnfinishedTasks extends Message
  case class Task(id: Int, prjId: Int, name: String, startTimestamp: String, priority: Int, status: Int,
                  pomodoros: Int, current: Boolean)
  case class ReplyAllUnfinishedTasks(tasks: List[Task])

  case class RequestAddRecord(taskId: Int, endTimestamp: Long, duration: Long) extends Message
  case class ReplyAddRecord(response: Int)

  case class RequestRemainingTimeInPomodoro(taskId: Int, priority: Int) extends Message
  case class ReplyRemainingTimeInPomodoro(duration: Long)

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
    case RequestAllUnfinishedTasks => queryAllUnfinishedTasks()
    case req: RequestRemainingTimeInPomodoro => queryRemainingTimeInPomodoro(req.taskId,req.priority)
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
    db ! DatabaseActor.QueryDB(0,s"INSERT INTO ${Basched.TABLE_NAME_TASKS} (PRJID, NAME, PRIORITY, STATUS, POMODOROS) " +
      s"VALUES (${newTask.prjId},'${newTask.name}',${Basched.PRIORITY(newTask.priority)},${Basched.STATUS("READY")},0" +
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
      s"VALUES (${newRecord.taskId},${newRecord.endTimestamp},${newRecord.duration})", update = true)
  }

  def replyAddRecord(r: DatabaseActor.QueryResult) : Unit = {
    r match {
      case QueryResult(_, _, _, 0) => replyTo ! ReplyAddRecord(BaschedRequest.ADDED)
      case QueryResult(_, _, _, 23505) => replyTo ! ReplyAddRecord(BaschedRequest.DUPLICATE)
      case _ => replyTo ! ReplyAddRecord(BaschedRequest.ERROR)
    }
  }

  def queryAllUnfinishedTasks(): Unit = {
    replyTo = sender()
    handleReply = replyAllUnfinishedTasks
    db ! DatabaseActor.QueryDB(0, s"SELECT * FROM ${Basched.TABLE_NAME_TASKS} WHERE STATUS != ${Basched.STATUS("FINISHED")}")
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
    Task(taskAsList.head.toInt,taskAsList(1).toInt,taskAsList(2),
      taskAsList(3),taskAsList(4).toInt,taskAsList(5).toInt,taskAsList(6).toInt, current = false)
  }

  /**
    * Selects one [[Task]] that should be currently worked on. Using different logic for different priorities.
    * @param tasks A list of [[Task]]
    * @return The same list of [[Task]]s, but with one [[Task]] that has the property [[Task.current]] as true.
    */
  def selectCurrentTask(tasks: List[Task]): List[Task] = {
    val (immTasks, otherTasks) = tasks.partition(_.priority == Basched.PRIORITY("im"))
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

      Task(task.id,task.prjId,task.name,task.startTimestamp,task.priority,task.status,task.pomodoros, isCurrentTask)
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
    * Get the [[Task.id]] where the logic of the [[Task.priority]] is the lowest [[Task.pomodoros]] count.
    * @param tasks List of [[Task]]s
    * @return An [[Task.id]] of the selected [[Task]].
    */
  def getOtherPriorityTaskId(tasks: List[Task]) : Option[Int] = {
    if (tasks.nonEmpty) Some(tasks.minBy(_.pomodoros).id)
    else None
  }

  /**
    * Calculates and returns how much time left in the current pomodoro of the [[Task]].
    * @param taskId The [[Task.id]] for which to calculate the remaining time in the pomodoro.
    * @param taskPriority The [[Task.priority]]
    */
  def queryRemainingTimeInPomodoro(taskId: Int, taskPriority: Int) : Unit = {
    replyTo = sender()
    handleReply = replyRemainingTimeInPomodoro(taskPriority)
    db ! DatabaseActor.QueryDB(0, s"SELECT SUM(DURATION_MS) FROM ${Basched.TABLE_NAME_RECORDS} " +
      s"WHERE TSKID = $taskId")
  }

  def replyRemainingTimeInPomodoro(priority: Int)(r: DatabaseActor.QueryResult) : Unit = {
    val totalDuration = Try(r.result.get.head.head.toLong) match {
      case Success(x) => x
      case _ => 0
    }
    val numOfPomodorosDone = totalDuration / Basched.POMODORO_MAX_DURATION_MS
    val remainingTime = Basched.POMODORO_MAX_DURATION_MS - (totalDuration - (numOfPomodorosDone * Basched.POMODORO_MAX_DURATION_MS))
    replyTo ! ReplyRemainingTimeInPomodoro(remainingTime)
  }
}
