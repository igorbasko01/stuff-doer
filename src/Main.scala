import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}

/**
  * Created by igor on 22/03/17.
  */
object Main extends App {
  //TODO: Read config file, and send it to the master actor some how.
  val system = ActorSystem("Stuff-Doer")
  val masterActor = system.actorOf(MasterActor.props(),"MasterActor")

  val terminator = system.actorOf(Props(classOf[Terminator], masterActor), "Stuff-Doer-Terminator")

  class Terminator(app: ActorRef) extends Actor with ActorLogging {
    context watch app
    def receive = {
      case Terminated(_) ⇒
        log.info("application supervisor has terminated, shutting down")
        context.system.terminate()
    }
  }
}
