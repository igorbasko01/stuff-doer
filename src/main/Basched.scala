package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

object Basched {
  def props(): Props = Props(new Basched)
}

class Basched extends Actor with ActorLogging {

  val TABLE_NAME_TASKS = "tasks"
  val TABLE_NAME_RECORDS = "records"

  var db: ActorRef = _

  override def preStart(): Unit = {
    log.info("Starting...")
    context.parent ! MasterActor.GetDBActor
  }

  override def receive: Receive = {
    case MasterActor.DBActor(x) =>
      db = x
      db ! DatabaseActor.IsTableExists(TABLE_NAME_TASKS)
      db ! DatabaseActor.IsTableExists(TABLE_NAME_RECORDS)
    case DatabaseActor.TableExistsResult(name, isExist) if !isExist => createTable(name)
    case _ => log.warning(s"Got unhandled message: ${_}")
  }

  def createTable(name: String): Unit = {
    //TODO: Check which table to create and create the relevant one.
  }
}
