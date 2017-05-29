import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import java.nio.file.{Files, Paths}

import scala.io.Source

/**
  * Created by igor on 25/05/17.
  */
object ActionStatus extends Enumeration {
  type Status = Value
  val INITIAL, FINISHED = Value
}

object DatabaseActor {
  case object Shutdown

  case class Action(date: String, time: String, action: String, params: Array[String], status: ActionStatus.Status)

  def props(): Props = Props(new DatabaseActor)
}

class DatabaseActor extends Actor with ActorLogging {

  //TODO: Read the actions file, or create a new one if it doesn't exist.
  //TODO: Test what happens when you try to open a file that doesn't exist.
  //TODO: Add a message to handle adding actions to the database.
  //TODO: Add a message to update the status of an action.

  // Line structure:
  // date;time;action;"param1,param2";status
  val fieldsDelimiter = ";"
  val paramsDelimiter = ","
  val actionsFilePath = "/some/place"

  override def preStart(): Unit = {
    log.info("Starting...")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination()
    case PoisonPill => controlledTermination()
    case somemessage => log.error(s"Got some unknown message: $somemessage")
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }

  /**
    * This functions opens and loads the actions in the actions file.
    * Only loads actions that are not finished.
    * @param fileName The name of the actions files to open.
    * @return A list of strings. Each string represents an action.
    */
  def loadActionsFile(fileName: String) : List[String] = {
    val bufferedSource = Source.fromFile(fileName)

    val actions = bufferedSource.getLines().toList

    bufferedSource.close()

    actions
  }

  def loadUnFinishedActions(fileName: String) : List[DatabaseActor.Action] = {
    loadActionsFile(fileName).map(line => {
      val Array(date, time, action, params, status) = line.split(fieldsDelimiter)
      val actionParams = params.split(paramsDelimiter)
      DatabaseActor.Action(date,time,action,actionParams,status.asInstanceOf[ActionStatus.Status])
    }).filter(action => !action.status.equals(ActionStatus.FINISHED))
  }
}
