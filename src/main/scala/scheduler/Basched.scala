package scheduler

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import database.DatabaseActor
import utils.Configuration
import webserver.WebServerActor
import scheduler.Basched._

/**
  * A companion object for the [[Basched]] class.
  * Contains some Constants and a props function to use when instantiating a [[Basched]] actor.
  */
object Basched {

  val TABLE_NAME_TASKS = "tasks"
  val TABLE_NAME_RECORDS = "records"
  val TABLE_NAME_PROJECTS = "projects"

  val PRIORITY = Map("im" -> 0, "hi" -> 1, "re" -> 2)
  val STATUS = Map("READY" -> 0, "WINDOW_FINISHED" -> 1, "ON_HOLD_WINDOW_FINISHED" -> 2, "ON_HOLD_READY" -> 3,
    "FINISHED" -> 4)
  val NUM_OF_PMDRS_PER_PRIORITY = Map(PRIORITY("im") -> 0, PRIORITY("hi") -> 8, PRIORITY("re") -> 4)
  val POMODORO_MAX_DURATION_MS = 25 * 60 * 1000

  /**
    * Returns a [[Props]] object with an instantiated [[Basched]] class.
    * @param config The configuration object of the application.
    * @return A [[Props]] object with an instantiated [[Basched]] class.
    */
  def props(config: Configuration): Props = Props(new Basched(config))
}

/**
  * Responsible for initialising the Basched system.
  * Creates the needed tables in the Data Base, and initialises them with some rows.
  * @param config A configuration object to use in the underlying actors.
  */
class Basched(config: Configuration) extends Actor with ActorLogging {

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
    s"ID INT AUTO_INCREMENT, " +
    s"PRJID INT, " +
    s"NAME VARCHAR(255) UNIQUE, " +
    s"START TIMESTAMP DEFAULT CURRENT_TIMESTAMP()," +
    s"PRIORITY INT," +
    s"STATUS INT," +
    s"POMODOROS INT" +
    s")"

  private def createStmtRecordsTable = s"CREATE TABLE $TABLE_NAME_RECORDS (" +
    s"ID INT AUTO_INCREMENT, " +
    s"TSKID INT, " +
    s"END BIGINT," +
    s"DURATION_MS BIGINT" +
    s")"

  private def createStmtProjectsTable = s"CREATE TABLE $TABLE_NAME_PROJECTS (" +
    s"ID INT AUTO_INCREMENT, " +
    s"NAME VARCHAR(255) UNIQUE" +
    s")"+";"+insertDefaultProject()

  private def insertDefaultProject() = s"INSERT INTO $TABLE_NAME_PROJECTS (ID, NAME) VALUES (1, \'DEFAULT\')"
}
