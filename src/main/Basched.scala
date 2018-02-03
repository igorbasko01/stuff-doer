package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

object Basched {
  def props(): Props = Props(new Basched)
}

class Basched extends Actor with ActorLogging {

  //TODO: Don't use ask pattern, try to create a request/reply pattern with tell only.

  val TABLE_NAME_TASKS = "tasks"
  val TABLE_NAME_RECORDS = "records"
  val TABLE_NAME_PROJECTS = "projects"

  val tablesCreationStmts = Map(
    TABLE_NAME_TASKS -> createStmtTaskTable,
    TABLE_NAME_RECORDS -> createStmtRecordsTable,
    TABLE_NAME_PROJECTS -> createStmtProjectsTable)

  var db: ActorRef = _

  override def preStart(): Unit = {
    log.info("Starting...")
    context.parent ! MasterActor.GetDBActor
  }

  override def receive: Receive = {
    case MasterActor.DBActor(x) =>
      db = x
      tablesCreationStmts.foreach{case (name, _) => db ! DatabaseActor.IsTableExists(name)}
    case DatabaseActor.TableExistsResult(name, isExist) if !isExist => createTable(name)
    case unknown => log.warning(s"Got unhandled message: $unknown")
  }

  def createTable(name: String): Unit = {

  }

  private def createStmtTaskTable = s"CREATE TABLE $TABLE_NAME_TASKS (" +
    s"ID INT UNIQUE, " +
    s"PRJID INT, " +
    s"NAME VARCHAR(255), " +
    s"START TIMESTAMP," +
    s"PRIORITY INT," +
    s"STATUS INT," +
    s"POMODOROS INT" +
    s")"

  private def createStmtRecordsTable = s"CREATE TABLE $TABLE_NAME_RECORDS (" +
    s"ID INT UNIQUE, " +
    s"TSKID INT, " +
    s"END TIMESTAMP," +
    s"DURATION INT," +
    s")"

  private def createStmtProjectsTable = s"CREATE TABLE $TABLE_NAME_PROJECTS (" +
    s"ID INT UNIQUE, " +
    s"NAME VARCHAR(255)," +
    s")"
}
