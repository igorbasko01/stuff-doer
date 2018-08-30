package core.database

import java.sql.{Connection, DriverManager, ResultSet}

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill}
import akka.stream._
import org.h2.jdbc.JdbcSQLException
import core.utils.Configuration
import core.utils.Message
import core._

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

/**
  * Created by igor on 25/05/17.
  */

class DatabaseActor(config: Configuration, clientsProps: List[PropsWithName]) extends Actor with ActorLogging {

  val materializer: ActorMaterializer = ActorMaterializer()(context)

  var clients: List[ActorRef] = List.empty

  override def preStart(): Unit = {
    log.info("Starting...")

    clients = clientsProps.map(prop => context.actorOf(prop.props,prop.actorName))

    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case Message.Shutdown => controlledTermination()
    case Message.QueryDB(reqId, query, update) => sender ! queryDataBase(reqId, query, update = update)
    case Message.IsTableExists(name) => sender ! Message.TableExistsResult(name, checkIfTableExists(name))
    case PoisonPill => controlledTermination()
    case somemessage => log.error("Got some unknown message: {}", somemessage)
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }

  /**
    * This function executes a query against the core.database and returns the results as a one long string.
    * @param query The query to execute.
    * @return The result of the query as an array of fields and a relevant message.
    */
  def queryDataBase(reqId: Int, query: String, returnHeader: Boolean = false, update: Boolean = false) : Message.QueryResult = {
    Class.forName("org.h2.Driver")
    val conn: Connection = DriverManager.getConnection("jdbc:h2:~/test", "sa", "")

    //"select * from INFORMATION_SCHEMA.TABLES"
    log.info(s"Got the following query: {}, from: {}", query, sender)

    val resultTry = if (update) {
      Try(conn.createStatement().executeUpdate(query))
    } else {
      Try(conn.createStatement().executeQuery(query))
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

    Message.QueryResult(reqId, resultToReturn._1,resultToReturn._2,resultToReturn._3)
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
      case Message.QueryResult(_, Some(_), _, _) => true
      case _ => false
    }
  }
}
