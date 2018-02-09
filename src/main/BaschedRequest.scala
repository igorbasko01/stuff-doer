package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import main.BaschedRequest.GetAllProjects

object BaschedRequest {

  sealed trait Message
  case object GetAllProjects extends Message
  case class SendAllProjects(projects: List[(String,String)]) extends Message

  def props(db: ActorRef): Props = Props(new BaschedRequest(db))

}

class BaschedRequest(db: ActorRef) extends Actor with ActorLogging {

  var replyTo: ActorRef = _
  var handleReply: (DatabaseActor.QueryResult) => () = _

  override def receive: Receive = {
    case GetAllProjects => queryGetAllProjects()
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
    val replyMsg = r.result.flatMap(allRows => Some(allRows.map(row => row.head))).mkString(",")
    replyTo ! replyMsg
  }
}
