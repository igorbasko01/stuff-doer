import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, PoisonPill, Props}

import scala.collection.mutable.ArrayBuffer

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  case object Shutdown

  case class Action(date: String, time: String, action: String, params: ArrayBuffer[String], status: Int)

  def props(): Props = Props(new DatabaseActor)
}

class DatabaseActor extends Actor with ActorLogging {

  // Line structure:
  // date;time;action;"param1,param2";status
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
}
