package main

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.stream._

import scala.util.{Failure, Success, Try}
import java.sql.{Connection, DriverManager, ResultSet}

import main.DatabaseActor.QueryResult
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.collection.mutable.ArrayBuffer

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  val ACTION_STATUS_INITIAL = 0
  val ACTION_STATUS_SENT = 1
  val ACTION_STATUS_FINISHED = 99
  val SCHEMA_NAME = "stuff_doer"
  val ACTIONS_TABLE_NAME = "actions"
  val ACTIONS_FULL_TABLE_NAME = s"$SCHEMA_NAME.$ACTIONS_TABLE_NAME"
  val ACTION_COPY_FILE = "copy_file"

  val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"

  val PARAMS_DELIMITER = ","

  case object Shutdown
  case object QueryUnfinishedActions

  case class Action(id: Option[Int], created: DateTime, act_type: String, params: List[String], status: Int, lastUpdated: DateTime)
  case class QueryDB(query: String, update: Boolean = false)
  case class QueryResult(result: Option[ArrayBuffer[List[String]]], message: String)
  case class UpdateActionStatusRequest(actionId: Int, newStatus: Int, lastUpdated: DateTime)

  def props(): Props = Props(new DatabaseActor)
}

class DatabaseActor extends Actor with ActorLogging {

  var readyToAcceptWork = false

  val materializer = ActorMaterializer()(context)

  override def preStart(): Unit = {
    log.info("Starting...")

    val fullTableName = DatabaseActor.ACTIONS_FULL_TABLE_NAME
    checkIfTableExists(fullTableName) match {
      case true => log.info(s"Actions table: $fullTableName, exists.")
      case false => log.warning(s"Actions table: $fullTableName, doesn't exists.")
        createTheActionsTable()
    }

    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination()
    case DatabaseActor.QueryUnfinishedActions => sender ! getUnfinishedActions
    case DatabaseActor.QueryDB(query, update) => sender ! queryDataBase(query, update = update)
    case newAction: DatabaseActor.Action => addNewAction(newAction)
    case updateReq: DatabaseActor.UpdateActionStatusRequest => updateActionStatus(updateReq)
    case PoisonPill => controlledTermination()
    case somemessage => log.error(s"Got some unknown message: $somemessage")
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }

  /**
    * This function converts action string into an Action object.
    * @param parts An action string that was read from an action file.
    * @return The Action object or None.
    */
  def convertToAction(parts: List[String]) : Option[DatabaseActor.Action] = {
    log.info(s"Got parts: $parts")

    validateRawAction(parts) match {
      case true =>
        try {
          val id = parts(0).toInt
          val created = DateTimeFormat.forPattern(DatabaseActor.TIMESTAMP_FORMAT).parseDateTime(parts(1))
          val act_type = parts(2)
          val params = parts(3).split(DatabaseActor.PARAMS_DELIMITER).toList
          val status = parts(4).toInt
          val lastUpdated = DateTimeFormat.forPattern(DatabaseActor.TIMESTAMP_FORMAT).parseDateTime(parts(5))

          Some(DatabaseActor.Action(Some(id), created, act_type, params, status, lastUpdated))

        } catch {
          case e: Exception =>
            log.error(s"Couldn't convert the action: ${parts.mkString(",")} to case class.")
            None
        }

      case false =>
        log.info("Invalid raw action !")
        None
    }
  }

  /**
    * Checks if there is enough parts in the action.
    * @param parts An array of parts of the action.
    * @return true if enough parts.
    */
  def validateRawAction(parts: List[String]) : Boolean = if (parts.length == 6) true else false

  /**
    * This function executes a query against the database and returns the results as a one long string.
    * @param query The query to execute.
    * @return The result of the query as an array of fields and a relevant message.
    */
  def queryDataBase(query: String, returnHeader: Boolean = false, update: Boolean = false) : QueryResult = {
    Class.forName("org.h2.Driver")
    val conn: Connection = DriverManager.getConnection("jdbc:h2:~/test", "sa", "")

    //"select * from INFORMATION_SCHEMA.TABLES"
    log.info(s"Got the following query: $query, from: $sender")

    val resultTry = update match {
      case false => Try(conn.createStatement().executeQuery(query))
      case true => Try(conn.createStatement().executeUpdate(query))
    }

    val resultToReturn = resultTry match {
      case Success(result: ResultSet) =>
        val rsmd = result.getMetaData
        val colNumber = rsmd.getColumnCount
        val header = for (i <- 1 to colNumber) yield rsmd.getColumnName(i)
        val resultArray = ArrayBuffer.empty[List[String]]
        if (returnHeader) resultArray += header.toList

        while (result.next()) {
          val row = for (i <- 1 to colNumber) yield result.getString(i)
          resultArray += row.toList
        }

        (Some(resultArray), "")
      case Success(result: Int) => (None, s"Updated $result rows !")
      case Success(result) => (None, s"Unexpected result: ${result.toString}")
      case Failure(e) => (None, e.getMessage)
    }

    conn.close()

    QueryResult(resultToReturn._1,resultToReturn._2)
  }

  /**
    * Queries the database and returns a list of unfinished actions.
    * @return An array of unfinished actions.
    */
  // TODO: It returns only actions that are in the initial status and not in the "NOT finished" status.
  // Should think how to handle all the unfinished actions.
  def getUnfinishedActions : List[DatabaseActor.Action] = {
    val result = queryDataBase(s"select * from ${DatabaseActor.ACTIONS_FULL_TABLE_NAME} " +
      s"where STATUS=${DatabaseActor.ACTION_STATUS_INITIAL}")
    val actions = result match {
      case QueryResult(Some(listOfRawActions), "") =>
        listOfRawActions.flatMap(convertToAction).toList
      case (QueryResult(None, msg)) =>
        log.error(s"Got the following message: $msg")
        List.empty[DatabaseActor.Action]
    }

    actions
  }

  /**
    * Creates the actions table.
    */
  def createTheActionsTable() : Unit = {
    val actionsFullTableName = s"${DatabaseActor.ACTIONS_FULL_TABLE_NAME}"
    log.info(s"Creating $actionsFullTableName...")
    val createTableStmt = s"CREATE TABLE $actionsFullTableName (" +
      "ID INT UNIQUE, " +
      "CREATED TIMESTAMP, " +
      "TYPE VARCHAR(255), " +
      "PARAMS LONGVARCHAR, " +
      "STATUS INT," +
      "LASTUPDATED TIMESTAMP" +
      ")"

    val result = queryDataBase(createTableStmt,update = true)

    val message = result match {
      case QueryResult(_, msg) => s"$msg <The table was probably created...>"
      case _ => "Some error occurred while creating the actions table."
    }

    log.info(message)
  }

  /**
    * Check if a table exists and return accordingly.
    * @param name The full name of the table.
    * @return If a table exists or no.
    */
  def checkIfTableExists(name: String) : Boolean = {
    val query = s"SELECT * FROM $name limit 1"

    val result = queryDataBase(query)

    result match {
      case QueryResult(Some(rows), msg) => true
      case _ => false
    }
  }

  /**
    * Add a new action to the table.
    * @param newAction The action to add. Ignores the provided id.
    */
  def addNewAction(newAction: DatabaseActor.Action) : Unit = {
    val fullTableName = s"${DatabaseActor.ACTIONS_FULL_TABLE_NAME}"
    // Get a unique id for the action.
    val result = queryDataBase(s"SELECT MAX(ID) FROM $fullTableName")
    val id = result match {
      case QueryResult(Some(rows), msg) => Try(rows(0)(0).toInt).getOrElse(0) + 1
      case QueryResult(None, msg) =>
        log.error(s"Failed to fetch a new id: $msg. \n For the following action: $newAction")
        -1
    }

    // Insert the action into the database.
    id match {
      case x: Int if x > 0 => val result = queryDataBase(s"INSERT INTO $fullTableName VALUES(" +
        s"$id, " +
        s"'${newAction.created.toString(DatabaseActor.TIMESTAMP_FORMAT)}', " +
        s"'${newAction.act_type}', " +
        s"'${newAction.params.mkString(DatabaseActor.PARAMS_DELIMITER)}', " +
        s"${newAction.status}, " +
        s"'${newAction.lastUpdated.toString(DatabaseActor.TIMESTAMP_FORMAT)}'" +
        s");",update = true)
        log.info(result.message)
      case _ => log.error(s"Invalid action id, not going to add the following action: $newAction")
    }
  }

  // TODO: Update the specific action in database. Add the last updated date.
  /**
    * A function that updates the status of the action.
    * @param updateReq All the needed information for the update.
    */
  def updateActionStatus(updateReq: DatabaseActor.UpdateActionStatusRequest) : Unit = {
    val fullTableName = s"${DatabaseActor.ACTIONS_FULL_TABLE_NAME}"

    val result = queryDataBase(s"UPDATE $fullTableName " +
      s"SET (STATUS, LASTUPDATED)=('${updateReq.newStatus}','${updateReq.lastUpdated}') " +
      s"WHERE ID='${updateReq.actionId}'")

    log.info(s"Update action result: ${result.message}")
  }
}
