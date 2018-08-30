package core.scheduler

import akka.actor.{Actor, ActorLogging, ActorRef}
import core.utils.{Configuration, Message}
import core._

/**
  * Responsible for initialising the Basched system.
  * Creates the needed tables in the Data Base, and initialises them with some rows.
  * @param config A configuration object to use in the underlying actors.
  */

class Basched(config: Configuration) extends Actor with ActorLogging {

  val tablesCreationStmts = Map(
    TABLE_NAME_TASKS -> createStmtTaskTable,
    TABLE_NAME_RECORDS -> createStmtRecordsTable,
    TABLE_NAME_PROJECTS -> createStmtProjectsTable,
    TABLE_NAME_ACTIVE_TASK -> createStmtActiveTaskTable
  )

  val db: ActorRef = context.parent

  var requests: Map[Int, Message.QueryResult => Unit] = Map(0 -> ((_: Message.QueryResult) => ()))

  override def preStart(): Unit = {
    log.info("Starting...")

    tablesCreationStmts.foreach{case (name, _) => db ! Message.IsTableExists(name)}
  }

  override def receive: Receive = {
    case Message.TableExistsResult(name, isExist) if !isExist => createTable(name)
    case x: Message.QueryResult => handleQueryResult(x)
    case unknown => log.warning("Got unhandled message: {}", unknown)
  }

  def handleQueryResult(result: Message.QueryResult): Unit = {
    requests(result.reqId)(result)
    requests -= result.reqId
  }

  def createTable(name: String): Unit = {
    log.info("Going to create table: {}", name)

    addQueryRequest(db,
      tablesCreationStmts(name), update = true,
      (x: Message.QueryResult) => {
        log.info("Table creation result: {}", x.message)
      }
    )
  }

  def addQueryRequest(actorHandler: ActorRef,
                      statement: String,
                      update: Boolean,
                      resultHandle: Message.QueryResult => Unit): Unit = {
    val reqId = requests.keySet.max+1
    actorHandler ! Message.QueryDB(reqId, statement, update)
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

  private def createStmtActiveTaskTable = s"CREATE TABLE $TABLE_NAME_ACTIVE_TASK (" +
    s"TSKID INT," +
    s"START TIMESTAMP DEFAULT CURRENT_TIMESTAMP(), " +
    s"LAST_PING TIMESTAMP DEFAULT CURRENT_TIMESTAMP()," +
    s"INITIAL_DURATION BIGINT" +
    s")"
}
