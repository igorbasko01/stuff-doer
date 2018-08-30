package core

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.util.StatusPrinter
import org.slf4j.LoggerFactory
import utils.Configuration

import scala.collection.mutable.ArrayBuffer

/**
  * Created by igor on 10/05/17.
  */

class MasterActor(config: Configuration) extends Actor with ActorLogging {

  // Print the status of the logger.
  val lc: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  StatusPrinter.print(lc)

  private val watched = ArrayBuffer.empty[ActorRef]

  private val dataBase = context.actorOf(props(config, List(PropsWithName(props(config),"Basched"))),
    "core.database.DatabaseActor")

  private val webserver = context.actorOf(props(config.hostname, config.portNum, config.password, dataBase),
    "core.webserver.WebServerActor")
  watchActor(dataBase)
  watchActor(webserver)

  override def preStart(): Unit = {
    log.info("Starting Master Actor...")

    // Watch all the child actors.
    watched.foreach(context.watch)
  }

  override def postStop(): Unit = {
    log.info("Stopping Master Actor...")
  }

  override def receive: Receive = {
    case Terminated(ref) =>
      watched -= ref
      log.info("Actor: {} died.", ref.path)
      controlledTermination()
    case someMessage => log.warning("Got the following message for some reason: {}", someMessage)
  }

  /***
    * Add an actor to the watch list.
    * @param actor Actor to add.
    */
  def watchActor(actor: ActorRef) : Unit = if (!watched.contains(actor)) watched += actor

  /**
    * Terminate the actor safely if, no more actors to watch, or the webServer actor is stopped.
    */
  def controlledTermination(): Unit = {
    // If core.webserver or core.database actor is not alive stop all the other actors and stop the application.
    if (!watched.contains(dataBase) || !watched.contains(webserver)) watched.foreach(_ ! PoisonPill)
    if (watched.isEmpty) context.stop(self)
  }
}
