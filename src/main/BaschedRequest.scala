package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import main.BaschedRequest.{AddTask, ReplyAllProjects, RequestAllProjects}

object BaschedRequest {

  sealed trait Message
  case object RequestAllProjects extends Message
  case class ReplyAllProjects(projects: List[(String,String)]) extends Message

  case class AddTask(prjId: Int, name: String, priority: Int) extends Message

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
    db ! DatabaseActor.QueryDB(0,"",update = true)
  }

  def replyAddTask(r: DatabaseActor.QueryResult) : Unit = {

  }
}
