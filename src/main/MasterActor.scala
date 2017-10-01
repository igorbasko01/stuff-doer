package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import akka.util.Timeout
import utils.Configuration

import scala.concurrent.duration._
import akka.pattern.ask

import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success}

/**
  * Created by igor on 10/05/17.
  */
object MasterActor {

  //TODO: Add an implementation to fill the map
  // Maybe each actor will decide on the actions it handles and will reply to the master when it is
  // up with the action it handles.
  val actionsToActors = Map.empty[String, ActorRef]

  def props(config: Configuration): Props = Props(new MasterActor(config))
}

class MasterActor(config: Configuration) extends Actor with ActorLogging {

  val watched = ArrayBuffer.empty[ActorRef]

  val dataBase = context.actorOf(DatabaseActor.props(), "main.DatabaseActor")
  watched += dataBase

  val webServer = context.actorOf(WebServerActor.props(config.hostname, config.portNum, dataBase),"main.WebServerActor")
  watched += webServer

  // Watch all the child actors.
  watched.foreach(context.watch)

  override def preStart(): Unit = {
    log.info("Starting Master Actor...")

    // TODO: Add code here to get and handle unfinished actions.
  }

  override def postStop(): Unit = {
    log.info("Stopping Master Actor...")
  }

  override def receive: Receive = {
    case Terminated(ref) =>
      watched -= ref
      log.info(s"Actor: ${ref.path} died.")
      controlledTermination()
    case someMessage => log.warning(s"Got the following message for some reason: $someMessage")
  }

  /**
    * Terminate the actor safely if, no more actors to watch, or the webServer actor is stopped.
    */
  def controlledTermination(): Unit = {
    // If webserver or database actor is not alive stop all the other actors and stop the application.
    if (!watched.contains(webServer) || !watched.contains(dataBase)) watched.foreach(_ ! PoisonPill)
    if (watched.isEmpty) context.stop(self)
  }

  def getUnfinishedActions : ArrayBuffer[DatabaseActor.Action] = {
    implicit val timeout = Timeout(10.seconds)
    import context.dispatcher
    dataBase ! DatabaseActor.QueryUnfinishedActions

    val response = (dataBase ? DatabaseActor.QueryUnfinishedActions).mapTo[ArrayBuffer[DatabaseActor.Action]]

    //TODO: find a way to return the actions...
    response.onComplete {
      case Success(actions) => actions
      case Failure(exp) =>
        log.error(s"Problem while retrieving unfinished actions: ${exp.getMessage}")
        exp.printStackTrace()
        ArrayBuffer.empty[DatabaseActor.Action]
    }
  }

  def handleUnfinishedActions(actions: ArrayBuffer[DatabaseActor.Action]) = {
    // TODO: Handle the case when the action key is not in the map.
    actions.foreach(action => MasterActor.actionsToActors(action.act_type) ! action)
  }
}
