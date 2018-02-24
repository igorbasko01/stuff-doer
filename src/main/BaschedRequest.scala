package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import main.BaschedRequest._
import main.DatabaseActor.QueryResult
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

object BaschedRequest {

  sealed trait Message
  case object RequestAllProjects extends Message
  case class ReplyAllProjects(projects: List[(String,String)]) extends Message

  case class AddTask(prjId: Int, name: String, priority: String) extends Message
  val TASK_ADDED = 0
  val TASK_DUPLICATE = 1
  val TASK_ERROR = 2
  case class ReplyAddTask(response: Int) extends Message

  case object RequestAllUnfinishedTasks extends Message
  case class Task(id: Int, prjId: Int, name: String, startTimestamp: DateTime, priority: Int, status: Int, pomodoros: Int)
  case class ReplyAllUnfinishedTasks(tasks: List[Task])

  def props(db: ActorRef): Props = Props(new BaschedRequest(db))

}

class BaschedRequest(db: ActorRef) extends Actor with ActorLogging {

  var replyTo: ActorRef = _
  var handleReply: (DatabaseActor.QueryResult) => Unit = _

  override def receive: Receive = {
    case RequestAllProjects => queryGetAllProjects()
    case addTask: AddTask => addNewTask(addTask)
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
    val replyMsg = r.result.flatMap(allRows => Some(allRows.map(row => (row(0),row(1))))).get.toList
    replyTo ! ReplyAllProjects(replyMsg)
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
    replyTo ! ReplyAllUnfinishedTasks(tasks)
  }

  def listToTask(taskAsList: List[String]) : Task = {
    val format = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
    Task(taskAsList.head.toInt,taskAsList(1).toInt,taskAsList(2),
      format.parseDateTime(taskAsList(3)),taskAsList(4).toInt,taskAsList(5).toInt,taskAsList(6).toInt)
  }
}
