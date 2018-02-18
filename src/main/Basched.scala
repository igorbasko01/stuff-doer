package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import utils.Configuration

object Basched {
  def props(config: Configuration): Props = Props(new Basched(config))
}

class Basched(config: Configuration) extends Actor with ActorLogging {

  val TABLE_NAME_TASKS = "tasks"
  val TABLE_NAME_RECORDS = "records"
  val TABLE_NAME_PROJECTS = "projects"

  val tablesCreationStmts = Map(
    TABLE_NAME_TASKS -> createStmtTaskTable,
    TABLE_NAME_RECORDS -> createStmtRecordsTable,
    TABLE_NAME_PROJECTS -> createStmtProjectsTable
  )

  val db: ActorRef = context.parent
  var webServer: ActorRef = _

  var requests: Map[Int, ((DatabaseActor.QueryResult) => Unit)] = Map(0 -> ((_: DatabaseActor.QueryResult) => ()))

  override def preStart(): Unit = {
    log.info("Starting...")

    tablesCreationStmts.foreach{case (name, _) => db ! DatabaseActor.IsTableExists(name)}

    webServer = context.actorOf(
      WebServerActor.props(config.hostname, config.portNum, db),
      "WebServerActor"
    )
  }

  override def receive: Receive = {
    case DatabaseActor.TableExistsResult(name, isExist) if !isExist => createTable(name)
    case x: DatabaseActor.QueryResult => handleQueryResult(x)
    case unknown => log.warning(s"Got unhandled message: $unknown")
  }

  def handleQueryResult(result: DatabaseActor.QueryResult): Unit = {
    requests(result.reqId)(result)
    requests -= result.reqId
  }

  def createTable(name: String): Unit = {
    log.info(s"Going to create table: $name")

    addQueryRequest(db,
      tablesCreationStmts(name), update = true,
      (x: DatabaseActor.QueryResult) => {
        log.info(s"Table creation result: ${x.message}")
      }
    )
  }

  def addQueryRequest(actorHandler: ActorRef,
                      statement: String,
                      update: Boolean,
                      resultHandle: DatabaseActor.QueryResult => Unit): Unit = {
    val reqId = requests.keySet.max+1
    actorHandler ! DatabaseActor.QueryDB(reqId, statement, update)
    requests ++= Map(reqId -> resultHandle)
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
    s"DURATION INT" +
    s")"

  private def createStmtProjectsTable = s"CREATE TABLE $TABLE_NAME_PROJECTS (" +
    s"ID INT UNIQUE, " +
    s"NAME VARCHAR(255)" +
    s")"+";"+insertDefaultProject()

  private def insertDefaultProject() = s"INSERT INTO $TABLE_NAME_PROJECTS (ID, NAME) VALUES (1, \'DEFAULT\')"
}
