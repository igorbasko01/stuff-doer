package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import main.BaschedRequest._
import main.DatabaseActor.QueryResult
import org.joda.time.format.DateTimeFormat

object BaschedRequest {

  sealed trait Message
  case object RequestAllProjects extends Message
  case class Project(id: Int, name: String)
  case class ReplyAllProjects(projects: List[Project]) extends Message

  case class AddTask(prjId: Int, name: String, priority: String) extends Message
  val TASK_ADDED = 0
  val TASK_DUPLICATE = 1
  val TASK_ERROR = 2
  case class ReplyAddTask(response: Int) extends Message

  case object RequestAllUnfinishedTasks extends Message
  case class Task(id: Int, prjId: Int, name: String, startTimestamp: String, priority: Int, status: Int,
                  pomodoros: Int, current: Boolean)
  case class ReplyAllUnfinishedTasks(tasks: List[Task])

  def props(db: ActorRef): Props = Props(new BaschedRequest(db))

}

/**
  * A single request actor, handles only one request, so each request should instantiate this actor.
  * Will return the result of the request straight to the sender.
  * It mainly contains the logic of the Basched app, and it is the main link between the DB actor and the
  * Webserver actor.
  * @param db A DB actor that the basched request will query.
  */
class BaschedRequest(db: ActorRef) extends Actor with ActorLogging {

  var replyTo: ActorRef = _
  var handleReply: (DatabaseActor.QueryResult) => Unit = _

  override def receive: Receive = {
    case RequestAllProjects => queryGetAllProjects()
    case addTask: AddTask => addNewTask(addTask)
    case RequestAllUnfinishedTasks => queryAllUnfinishedTasks()
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
      case QueryResult(_, _, _, 0) => replyTo ! ReplyAddTask(BaschedRequest.TASK_ADDED)
      case QueryResult(_, _, _, 23505) => replyTo ! ReplyAddTask(BaschedRequest.TASK_DUPLICATE)
      case _ => replyTo ! ReplyAddTask(BaschedRequest.TASK_ERROR)
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
    * Converts the result from the DB to a Task object.
    * @param taskAsList Single row from the DB.
    * @return Task object.
    */
  def listToTask(taskAsList: List[String]) : Task = {
    Task(taskAsList.head.toInt,taskAsList(1).toInt,taskAsList(2),
      taskAsList(3),taskAsList(4).toInt,taskAsList(5).toInt,taskAsList(6).toInt, current = false)
  }

  /**
    * Selects one task that should be currently worked on. Using different logic for different priorities.
    * @param tasks A list of task
    * @return The same list of tasks, but with one task has the property current as true.
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
    * Get the Immediate priority task id with the latest creation time.
    * @param tasks A list of immediate priority tasks.
    * @return An id of the selected task.
    */
  def getImmPriorityTaskId(tasks: List[Task]) : Option[Int] = {
    val formater = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")

    val idsAndDate = tasks.map(task => (task.id, formater.parseDateTime(task.startTimestamp)))

    if (idsAndDate.nonEmpty) Some(idsAndDate.maxBy(_._2.getMillis)._1)
    else None
  }

  /**
    * Get the task id where the logic of the priority is the lowest pomodoro count.
    * @param tasks List of Tasks
    * @return An id of the selected task.
    */
  def getOtherPriorityTaskId(tasks: List[Task]) : Option[Int] = {
    if (tasks.nonEmpty) Some(tasks.minBy(_.pomodoros).id)
    else None
  }
}
