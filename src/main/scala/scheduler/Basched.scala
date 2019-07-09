package scheduler

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import database.DatabaseActor, DatabaseActor.QueryResult
import utils.Configuration
import scheduler.Basched._

/**
  * A companion object for the [[Basched]] class.
  * Contains some Constants and a props function to use when instantiating a [[Basched]] actor.
  */
object Basched {

  val TABLE_NAME_TASKS = "tasks"
  val TABLE_NAME_RECORDS = "records"
  val TABLE_NAME_PROJECTS = "projects"
  val TABLE_NAME_ACTIVE_TASK = "active_task"
  val TABLE_NAME_SCHEMA_EVO = "schema_evo"
  val TABLE_NAME_USERS = "users"

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
    TABLE_NAME_PROJECTS -> createStmtProjectsTable,
    TABLE_NAME_ACTIVE_TASK -> createStmtActiveTaskTable,
    TABLE_NAME_SCHEMA_EVO -> createStmtSchemaEvoTab,
    TABLE_NAME_USERS -> createUserTable
  )

  val schemaEvoCmnds = List(
    s"ALTER TABLE ${TABLE_NAME_TASKS} ADD COLUMN USER_ID VARCHAR(255)",
    s"ALTER TABLE ${TABLE_NAME_RECORDS} ADD COLUMN USER_ID VARCHAR(255)",
    s"ALTER TABLE ${TABLE_NAME_PROJECTS} ADD COLUMN USER_ID VARCHAR(255)",
    s"ALTER TABLE ${TABLE_NAME_ACTIVE_TASK} ADD COLUMN USER_ID VARCHAR(255)"
  )

  val db: ActorRef = context.parent

  var requests: Map[Int, ((DatabaseActor.QueryResult) => Unit)] = Map(0 -> ((_: DatabaseActor.QueryResult) => ()))

  var amntInitTablesComplete = 0

  override def preStart(): Unit = {
    log.info("Starting...")

    tablesCreationStmts.foreach{case (name, _) => db ! DatabaseActor.IsTableExists(name)}
  }

  override def receive: Receive = {
    case DatabaseActor.TableExistsResult(name, isExist) if !isExist => createTable(name)
    case DatabaseActor.TableExistsResult(name, true) => tableInitComplete(DatabaseActor.QueryResult(-1, None, "Already exists !", 0))
    case x: DatabaseActor.QueryResult => handleQueryResult(x)
    case unknown => log.warning("Got unhandled message: {}", unknown)
  }

  def tableInitComplete(res: DatabaseActor.QueryResult): Unit = {
    log.info("Table creation result: {}", res.message)
    if (res.errorCode == 0) amntInitTablesComplete += 1
    if (amntInitTablesComplete == tablesCreationStmts.size)
      fetchSchemaEvoHist()
  }

  def fetchSchemaEvoHist(): Unit = {
    log.info("Fetching schema evolution history.")
    addQueryRequest(db, s"SELECT MAX(cmd_id) FROM ${TABLE_NAME_SCHEMA_EVO}",
      update = false, execSchemaEvo)
  }

  def execSchemaEvo(res: DatabaseActor.QueryResult): Unit = {
    log.info("Starting Schema Evolution commands...")

    res match {
      case QueryResult(_, rows, _, 0) =>
        val startFrom = if (rows.head.head.head == null) 0 else rows.head.head.head.toInt + 1
        log.info(s"Got the following result: ${startFrom}")
        executeEvoStmt(startFrom)(QueryResult(0, None, "", 201))
      case other => throw new Exception(s"Couldn't determine schema evolution history. Res: ${other}")
    }
  }

  def executeEvoStmt(cmndIdx: Int)(res: DatabaseActor.QueryResult): Unit = {
    res match {
      case QueryResult(_, _, _, 0) =>
        if (cmndIdx > 0)
          addQueryRequest(db,
            s"INSERT INTO ${TABLE_NAME_SCHEMA_EVO} (CMD_ID, CMD_STR, EXEC_TIME) " +
            s"VALUES(${cmndIdx-1}, '${schemaEvoCmnds(cmndIdx-1)}', CURRENT_TIMESTAMP)",
            update = true, schemaEvoUpdateResult)
      case QueryResult(_, _, _, 201) => log.info("Start of the schema evo process.")
      case other =>
        log.error(s"Couldn't perform the following command: ${schemaEvoCmnds(cmndIdx-1)}, Res: ${res}")
        context.system.terminate()
        return
    }

    if (cmndIdx < schemaEvoCmnds.size)
      addQueryRequest(db, schemaEvoCmnds(cmndIdx), update = true, executeEvoStmt(cmndIdx + 1))
    else
      log.info("Schema evolution process is finished !")
  }

  def schemaEvoUpdateResult(res: DatabaseActor.QueryResult): Unit = {
    log.info(s"Query result: ${res.message}")
  }

  def handleQueryResult(result: DatabaseActor.QueryResult): Unit = {
    requests(result.reqId)(result)
    requests -= result.reqId
  }

  def createTable(name: String): Unit = {
    log.info("Going to create table: {}", name)

    addQueryRequest(db,
      tablesCreationStmts(name), update = true,
      tableInitComplete
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

  private def createStmtActiveTaskTable = s"CREATE TABLE $TABLE_NAME_ACTIVE_TASK (" +
    s"TSKID INT," +
    s"START TIMESTAMP DEFAULT CURRENT_TIMESTAMP(), " +
    s"LAST_PING TIMESTAMP DEFAULT CURRENT_TIMESTAMP()," +
    s"INITIAL_DURATION BIGINT" +
    s")"

  private def createStmtSchemaEvoTab = s"CREATE TABLE $TABLE_NAME_SCHEMA_EVO (" +
    s"CMD_ID INT," +
    s"CMD_STR VARCHAR(1024)," +
    s"EXEC_TIME TIMESTAMP)"

  private def createUserTable = s"CREATE TABLE $TABLE_NAME_USERS (" +
    s"USER_ID VARCHAR(255)," +
    s"USER_EMAIL VARCHAR(255)," +
    s"USER_SALT VARCHAR(10)," +
    s"USER_PASSWORD VARCHAR(245)," +
    s"USER_ROLE INT)"
}
