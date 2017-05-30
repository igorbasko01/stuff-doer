package main

import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.util.{Failure, Success}

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

  case class Action(date: String, time: String, action: String, params: Array[String], status: Int)

  def props(actionsFilePath: String): Props = Props(new DatabaseActor(actionsFilePath))
}

class DatabaseActor(actionsFilePath: String) extends Actor with ActorLogging {

  //TODO: Use akka streams to read the actions file.
  //TODO: Read the actions file, or create a new one if it doesn't exist.
  //TODO: Test what happens when you try to open a file that doesn't exist.
  //TODO: Add a message to handle adding actions to the database.
  //TODO: Add a message to update the status of an action.

  // Line structure:
  // date;time;action;"param1,param2";status
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
    * @param fileName The name of the actions files to open.
    * @return A list of strings. Each string represents an action.
    */
  def loadActionsFile(fileName: String) : List[String] = {
    var actionsToReturn = List.empty[String]
    val file = Paths.get(fileName)

    val fileContents =
      FileIO
        .fromPath(file)
        .runWith(Sink.seq[ByteString])(materializer)

    fileContents.onComplete({
      case Success(lines) => actionsToReturn = lines.map(_.toString).toList
      case Failure(excp) =>
        log.error(s"Error with reading actions file: $fileName")
        log.error(excp.getStackTrace.mkString("\n"))
        log.error("Terminating the application....")
        context.system.terminate
    })(context.dispatcher)

    actionsToReturn
  }
//
//  /**
//    * Returns a list of un-finished actions.
//    * @param fileName The file name of the actions.
//    * @return List of un-finished actions.
//    */
//  def loadUnFinishedActions(fileName: String) : List[main.DatabaseActor.Action] = {
//    loadActionsFile(fileName).map(line => {
//      val Array(date, time, action, params, status) = line.split(fieldsDelimiter)
//      val actionParams = params.split(paramsDelimiter)
//      main.DatabaseActor.Action(date,time,action,actionParams,status.asInstanceOf[ActionStatus.Status])
//    }).filter(action => !action.status.equals(ActionStatus.FINISHED))
//  }
//
//  def createActionsFile(fileName: String) : Unit = {
//    val file = new Files()
//  }
}
