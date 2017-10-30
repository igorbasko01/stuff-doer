package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import akka.util.Timeout
import utils.Configuration

import scala.concurrent.duration._
import akka.pattern.ask

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success}

/**
  * Created by igor on 10/05/17.
  */
object MasterActor {

  case class RegisterAction(action: String, actor: ActorRef)

  def props(config: Configuration): Props = Props(new MasterActor(config))
}

class MasterActor(config: Configuration) extends Actor with ActorLogging {

  //TODO: Add an implementation to fill the map
  // Maybe each actor will decide on the actions it handles and will reply to the master when it is
  // up with the action it handles.
  var actionsToActors = Map.empty[String, ActorRef]

  val watched = ArrayBuffer.empty[ActorRef]

  val dataBase = context.actorOf(DatabaseActor.props(), "main.DatabaseActor")
  watchActor(dataBase)

  val webServer = context.actorOf(WebServerActor.props(config.hostname, config.portNum, dataBase),"main.WebServerActor")
  watchActor(webServer)

  override def preStart(): Unit = {
    log.info("Starting Master Actor...")

    val fileActor = context.actorOf(FileActor.props, "main.FileActor")
    watchActor(fileActor)

    // Watch all the child actors.
    watched.foreach(context.watch)

    // TODO: Add code here to get and handle unfinished actions. Maybe add a scheduler that will try and send
    // fetch unfinished actions periodically.
    handleUnfinishedActions()
  }

  override def postStop(): Unit = {
    log.info("Stopping Master Actor...")
  }

  override def receive: Receive = {
    case MasterActor.RegisterAction(action, actor) => registerAction(action, actor)
    case Terminated(ref) =>
      watched -= ref
      log.info(s"Actor: ${ref.path} died.")
      controlledTermination()
    case someMessage => log.warning(s"Got the following message for some reason: $someMessage")
  }

  /***
    * Register an action to an actor.
    * @param action The action the actor handles.
    * @param actor The actor
    */
  def registerAction(action: String, actor: ActorRef): Unit = {
    log.info(s"Adding the following link between action and actor: $action -> $actor")
    actionsToActors += action -> actor
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
    // If webserver or database actor is not alive stop all the other actors and stop the application.
    if (!watched.contains(webServer) || !watched.contains(dataBase)) watched.foreach(_ ! PoisonPill)
    if (watched.isEmpty) context.stop(self)
  }

  def handleUnfinishedActions() : Unit = {
    implicit val timeout = Timeout(10.seconds)

    val response = (dataBase ? DatabaseActor.QueryUnfinishedActions).mapTo[ArrayBuffer[DatabaseActor.Action]]

    response.onComplete {
      case Success(actions) =>
        // TODO: Update the status of the action in the database. To SENT or something.
        actions
          .filter(action => actionsToActors.getOrElse(action.act_type, null) != null)
          .foreach(action => actionsToActors(action.act_type) ! action)
      case Failure(exp) =>
        log.error(s"Problem while retrieving unfinished actions: ${exp.getMessage}")
        exp.printStackTrace()
    }(context.dispatcher)
  }
}
