package main

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config.ConfigFactory
import core.MasterActor
import core.utils.Configuration
import core._
/**
  * Created by igor on 22/03/17.
  */

object Main extends App {
  println("Loading configuration")
  val config = new Configuration
  if (!config.loadConfig(ConfigFactory.load())) {
    println("Problem with parsing configuration file !")
    sys.exit(1)
  }
  println("Loading configuration done. Starting actor system")

  val system = ActorSystem("Stuff-Doer")
  val masterActor = system.actorOf(Props(classOf[MasterActor], config),"core.MasterActor")

  val terminator = system.actorOf(Props(classOf[Terminator], masterActor), "Stuff-Doer-Terminator")

  class Terminator(app: ActorRef) extends Actor with ActorLogging {
    context watch app
    def receive: PartialFunction[Any, Unit] = {
      case Terminated(_) ⇒
        log.info("application supervisor has terminated, shutting down")
        context.system.terminate()
    }
  }
}
