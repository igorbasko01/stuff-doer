package database

import java.sql.{Connection, DriverManager, ResultSet}

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.stream._
import database.DatabaseActor.QueryResult
import org.h2.jdbc.JdbcSQLException
import scheduler.Basched
import utils.Configuration

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  case object Shutdown

  case class QueryDB(reqId: Int, query: String, update: Boolean = false)
  case class QueryResult(reqId: Int, result: Option[ArrayBuffer[List[String]]], message: String, errorCode: Int)

  case class IsTableExists(tableName: String)
  case class TableExistsResult(tableName: String, isExist: Boolean)

  def props(config: Configuration): Props = Props(new DatabaseActor(config))
}

class DatabaseActor(config: Configuration) extends Actor with ActorLogging {

  val materializer = ActorMaterializer()(context)

  val clients: ArrayBuffer[ActorRef] = ArrayBuffer.empty

  override def preStart(): Unit = {
    log.info("Starting...")

    clients += context.actorOf(Basched.props(config), "Basched")

    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination()
    case DatabaseActor.QueryDB(reqId, query, update) => sender ! queryDataBase(reqId, query, update = update)
    case DatabaseActor.IsTableExists(name) => sender ! DatabaseActor.TableExistsResult(name, checkIfTableExists(name))
    case PoisonPill => controlledTermination()
    case somemessage => log.error(s"Got some unknown message: $somemessage")
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }

  /**
    * This function executes a query against the database and returns the results as a one long string.
    * @param query The query to execute.
    * @return The result of the query as an array of fields and a relevant message.
    */
  def queryDataBase(reqId: Int, query: String, returnHeader: Boolean = false, update: Boolean = false) : QueryResult = {
    Class.forName("org.h2.Driver")
    val conn: Connection = DriverManager.getConnection("jdbc:h2:~/test", "sa", "")

    //"select * from INFORMATION_SCHEMA.TABLES"
    log.info(s"Got the following query: $query, from: $sender")

    val resultTry = update match {
      case false => Try(conn.createStatement().executeQuery(query))
      case true => Try(conn.createStatement().executeUpdate(query))
    }

    val resultArray = ArrayBuffer.empty[List[String]]

    val resultToReturn = resultTry match {
      case Success(result: ResultSet) =>
        val rsmd = result.getMetaData
        val colNumber = rsmd.getColumnCount
        val header = for (i <- 1 to colNumber) yield rsmd.getColumnName(i)
        if (returnHeader) resultArray += header.toList

        while (result.next()) {
          val row = for (i <- 1 to colNumber) yield result.getString(i)
          resultArray += row.toList
        }

        (Some(resultArray), "",0)
      case Success(result: Int) => (Some(resultArray), s"Updated $result rows !",0)
      case Success(result) => (Some(resultArray), s"Unexpected result: ${result.toString}",0)
      case Failure(e) => (None, e.getMessage, e.asInstanceOf[JdbcSQLException].getErrorCode)
    }

    conn.close()

    QueryResult(reqId, resultToReturn._1,resultToReturn._2,resultToReturn._3)
  }

  /**
    * Check if a table exists and return accordingly.
    * @param name The full name of the table.
    * @return If a table exists or no.
    */
  def checkIfTableExists(name: String) : Boolean = {
    val query = s"SELECT * FROM $name limit 1"

    val result = queryDataBase(0, query)

    result match {
      case QueryResult(_, Some(rows), msg, _) => true
      case _ => false
    }
  }
}
