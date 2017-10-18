package main

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.stream._

import scala.util.{Failure, Success, Try}
import java.sql.{Connection, DriverManager, ResultSet}
import main.DatabaseActor.QueryResult

import scala.collection.mutable.ArrayBuffer

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  val ACTION_STATUS_INITIAL = 0
  val ACTION_STATUS_FINISHED = 99
  val SCHEMA_NAME = "stuff_doer"
  val ACTIONS_TABLE_NAME = "actions"
  val ACTION_COPY_FILE = "copy_file"

  case object Shutdown
  case object QueryUnfinishedActions

  //TODO: Add an action id, so it could be uniquely identified through out the whole application.
  //TODO: Replace the date and time fields with one timestamp field.
  //TODO: Add last updated field of type timestamp.
  case class Action(date: String, time: String, act_type: String, params: List[String], status: Int)
  case class QueryDB(query: String, update: Boolean = false)
  case class QueryResult(result: Option[ArrayBuffer[List[String]]], message: String)

  def props(): Props = Props(new DatabaseActor)
}

class DatabaseActor extends Actor with ActorLogging {

  //TODO: Load the unfinished actions from the database. - Update the webserver actor to handle the new return type of
  // getUnfinishedActions.

  val paramsDelimiter = ","
  var readyToAcceptWork = false

  val materializer = ActorMaterializer()(context)

  override def preStart(): Unit = {
    log.info("Starting...")
    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination()
    case DatabaseActor.QueryUnfinishedActions => sender ! getUnfinishedActions
    case DatabaseActor.QueryDB(query, update) => sender ! queryDataBase(query, update)
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
        val params = parts(3).split(paramsDelimiter).toList
        log.info(s"Params are: $params")
        log.info(s"Status is: ${parts(4)}")

        Try(parts(4).toInt) match {
          case Success(x) =>
            log.info("toInt success")
            Some(DatabaseActor.Action(parts(0),parts(1),parts(2),params,x))
          case Failure(e) =>
            log.info("toInt failure")
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
  def validateRawAction(parts: List[String]) : Boolean = if (parts.length == 5) true else false

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

    val resultTry =
    if (!update)
      Try(conn.createStatement().executeQuery(query))
    else
      Try(conn.createStatement().executeUpdate(query))

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
  def getUnfinishedActions : ArrayBuffer[DatabaseActor.Action] = {
    val result = queryDataBase(s"select * from ${DatabaseActor.SCHEMA_NAME}.${DatabaseActor.ACTIONS_TABLE_NAME} " +
      s"where STATUS=${DatabaseActor.ACTION_STATUS_INITIAL}")
    val actions = result match {
      case QueryResult(Some(listOfRawActions), "") =>
        listOfRawActions.flatMap(convertToAction)
      case (QueryResult(None, msg)) =>
        log.error(s"Got the following message: $msg")
        ArrayBuffer.empty[DatabaseActor.Action]
    }

    actions
  }

  /**
    * Creates the actions table.
    */
  def createTheActionsTable() : Unit = {
    val createTableStmt = s"CREATE TABLE ${DatabaseActor.SCHEMA_NAME}.${DatabaseActor.ACTIONS_TABLE_NAME} (" +
      "ID INT UNIQUE, " +
      "CREATED TIMESTAMP, " +
      "TYPE VARCHAR(255), " +
      "PARAMS LONGVARCHAR, " +
      "STATUS INT" +
      ")"

    val result = queryDataBase(createTableStmt,update = true)

    val message = result match {
      case QueryResult(_, msg) => msg
      case _ => "Some error occurred while creating the actions table."
    }

    log.info(message)
  }

  /**
    * Delete the actions table.
    */
  def deleteTheActionsTable() : Unit = {

  }
}
