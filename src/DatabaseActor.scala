import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, PoisonPill, Props}

/**
  * Created by igor on 25/05/17.
  */
object DatabaseActor {
  case object Shutdown

  def props(): Props = Props(new DatabaseActor)
}

class DatabaseActor extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("Starting...")
  }

  override def postStop(): Unit = {
    log.info("Stopping...")
  }

  override def receive: Receive = {
    case DatabaseActor.Shutdown => controlledTermination
    case PoisonPill => controlledTermination
    case somemessage => log.error(s"Got some unknown message: $somemessage")
  }

  def controlledTermination(): Unit = {
    context.stop(self)
  }
}
