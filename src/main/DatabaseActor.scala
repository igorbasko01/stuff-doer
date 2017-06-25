package main

import java.nio.file.{Files, Paths}

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  val ACTION_STATUS_INITIAL = 0
  val ACTION_STATUS_FINISHED = 99
  val ACTION_COPY_FILE = "copy_file"

  case object Shutdown
  case object ReadyForWork
  case object QueryUnfinishedActions

  case class Action(date: String, time: String, action: String, params: List[String], status: Int)
  case class ActionKey(key: Int)

  def props(actionsFilesPath: String, actionsFilesPrfx: String): Props =
    Props(new DatabaseActor(actionsFilesPath, actionsFilesPrfx))
}

class DatabaseActor(actionsFilesPath: String, actionsFilesPrfx: String) extends Actor with ActorLogging {

  //TODO: Save actions to new actions file.
  //TODO: Save to a file only if there are new actions/updated actions to store, since last time.
  //TODO: Control the amount of backup files.

  // Line structure:
  // date;time;action;param1,param2;status
  val fieldsDelimiter = ";"
  val paramsDelimiter = ","
  var readyToAcceptWork = false

  var actions = Map.empty[DatabaseActor.ActionKey, DatabaseActor.Action]

  val materializer = ActorMaterializer()(context)

  override def preStart(): Unit = {
    log.info("Starting...")

    val fileToLoad = findActionsFileToLoad(actionsFilesPath, actionsFilesPrfx)

    if (fileToLoad.isEmpty) {
      log.error("Could not determine actions file. Terminating.")
      context.stop(self)
    }
    else loadActionsFile(fileToLoad)

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
    case action: DatabaseActor.Action => actions ++= Map(getActionKey(action) -> action)
    case DatabaseActor.QueryUnfinishedActions => queryUnfinishedActions(sender())
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
    * This function finds out the latest action file in a specified path and a specified prefix.
    * The date should be incorporated in the name of the files.
    * @param actionsPath The path where the action files reside.
    * @param filePrfx The prefix of the action file. The portion of the name without the date.
    * @return The full name of the action file (without the path).
    */
  def findActionsFileToLoad(actionsPath: String, filePrfx: String) : String = {
    val listOfFiles = Files.list(Paths.get(actionsPath)).iterator().asScala

    val actionFiles = listOfFiles.filter(_.getFileName.toString.startsWith(filePrfx))

    val latestFile =  if (actionFiles.isEmpty) ""
    else {
      val fileNames = actionFiles.map(file => file.getFileName.toString)
      fileNames.toList.sortBy(x=>x).last
    }

    log.info(s"Latest file is : $latestFile")
    latestFile
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

  /**
    * Get the action's key using its first four values: date, time, action, params
    * @param action The action for which to determine the key.
    * @return Returns an object of actions key.
    */
  def getActionKey(action: DatabaseActor.Action): DatabaseActor.ActionKey = {
    val stringKey = action.date + action.time + action.action + action.params.mkString
    DatabaseActor.ActionKey(stringKey.hashCode)
  }

  /**
    * Reply to the sender of the query with the actions that are not finished.
    * @param replyTo The sender of the query.
    */
  def queryUnfinishedActions(replyTo: ActorRef) : Unit =
    replyTo ! actions.values.filter(_.status != DatabaseActor.ACTION_STATUS_FINISHED).toList
}
