package main

import java.nio.file.{Paths,Files}

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.util.{Failure, Success, Try}

/**
  * Created by igor on 25/05/17.
  */
//object ActionStatus extends Enumeration {
//  type Status = Value
//  val INITIAL, FINISHED = Value
//}

object DatabaseActor {
  case object Shutdown
  case object ReadyForWork

  case class Action(date: String, time: String, action: String, params: List[String], status: Int)

  def props(actionsFilePath: String): Props = Props(new DatabaseActor(actionsFilePath))
}

class DatabaseActor(actionsFilePath: String) extends Actor with ActorLogging {

  //TODO: Use akka streams to read the actions file.
  //TODO: Read the actions file, or create a new one if it doesn't exist.
  //TODO: Test what happens when you try to open a file that doesn't exist.
  //TODO: Add a message to handle adding actions to the database.
  //TODO: Add a message to update the status of an action.

  // Line structure:
  // date;time;action;param1,param2;status
  val fieldsDelimiter = ";"
  val paramsDelimiter = ","
  var readyToAcceptWork = false

  val materializer = ActorMaterializer()(context)

  override def preStart(): Unit = {
    log.info("Starting...")

    loadActionsFile(actionsFilePath)

    //    val source: Source[Int, NotUsed] = Source(1 to 100)

//    val factorials = source.scan(BigInt(1))((acc, next) => acc * next)
//      .zipWith(Source(0 to 100))((num, idx) => s"$idx! = $num")
//      .throttle(1,1.second, 1, ThrottleMode.shaping)
//      .runWith(Sink.foreach(println))(materializer)
//      .onComplete(_ => self ! main.DatabaseActor.ReadyForWork)(context.dispatcher)
//
//    def lineSink(fileName: String): Sink[String, Future[IOResult]] =
//      Flow[String]
//      .map(s => ByteString(s + "\n"))
//      .toMat(FileIO.toPath(Paths.get(fileName)))(Keep.right)

//    factorials.map(_.toString).runWith(lineSink("factorial2.txt"))(materializer)
//      .onComplete(_ => log.info("Written file"))(context.dispatcher)

//    val result: Future[IOResult] =
//      factorials
//      .map(num => ByteString(s"$num\n"))
//      .runWith(FileIO.toPath(Paths.get("factorials.txt")))(materializer)
//
//    result.onComplete(res => log.info(s"File is written ${res.get.count} bytes"))(context.system.dispatcher)

    log.info("Started !")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination()
    case action: DatabaseActor.Action => log.info(s"Got Action: $action")
    case PoisonPill => controlledTermination()
    case DatabaseActor.ReadyForWork =>
      readyToAcceptWork = true
      log.info("I'm ready for work ! Bring it on !!")
    case somemessage =>
      if (readyToAcceptWork) log.error(s"Got some unknown message: $somemessage")
      else log.error("Still initializing ! Sorry...")
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }

  /**
    * This function opens and loads the actions in the actions file.
    * Filters out comments.
    * @param fileName The name of the actions files to open.
    * @return A list of strings. Each string represents an action.
    */
  def loadActionsFile(fileName: String) : Unit = {
    val file = Paths.get(fileName)

    if (!Files.exists(file)) {
      log.info(s"Actions file:[$file] doesn't exists creating a new one.")
      Try(Files.createFile(file)) match {
        case Success(path) => log.info(s"Actions file created: [$file]")
        case Failure(ex) =>
          log.error(s"Error creating file: [$file], reason: ")
          log.error(ex.getStackTrace.mkString("\n"))
      }
    }

    val fileContents =
      FileIO
        .fromPath(file)
        .runWith(Sink.seq[ByteString])(materializer)

    fileContents.onComplete({
      case Success(lines) =>
        val actions = lines.map(_.utf8String).mkString.split("\n").filter(!_.startsWith("#")).map(convertToAction)
        actions.filter(_.isDefined).foreach(self ! _.get)
      case Failure(excp) =>
        log.error(s"Error with reading actions file: $fileName")
        log.error(excp.getStackTrace.mkString("\n"))
        log.error("Terminating the application....")
        context.system.terminate
    })(context.dispatcher)
  }

  /**
    * This function converts action string into an Action object.
    * @param rawAction An action string that was read from an action file.
    * @return The Action object or None.
    */
  def convertToAction(rawAction: String) : Option[DatabaseActor.Action] = {
    val parts = rawAction.split(fieldsDelimiter)
    log.info(s"Got parts: ${parts.toList}")

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
  def validateRawAction(parts: Array[String]) : Boolean = if (parts.length == 5) true else false
}
