package main

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props, Terminated}
import akka.util.Timeout
import utils.Configuration

import scala.concurrent.duration._
import akka.pattern.ask

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success}
import org.joda.time.DateTime

/**
  * Created by igor on 10/05/17.
  */
object MasterActor {

  case class RegisterAction(action: String, actor: ActorRef)
  case object HandleUnfinishedActions

  def props(config: Configuration): Props = Props(new MasterActor(config))
}

class MasterActor(config: Configuration) extends Actor with ActorLogging {

  //TODO: Add an implementation to fill the map
  // Maybe each actor will decide on the actions it handles and will reply to the master when it is
  // up with the action it handles.
  var actionsToActors = Map.empty[String, ActorRef]

  private val watched = ArrayBuffer.empty[ActorRef]

  private val dataBase = context.actorOf(DatabaseActor.props(), "main.DatabaseActor")
  watchActor(dataBase)

  private val webServer = context.actorOf(WebServerActor.props(config.hostname, config.portNum, dataBase),"main.WebServerActor")
  watchActor(webServer)

  private val httpClient = context.actorOf(HttpClient.props(), "main.HttpClient")
  watchActor(httpClient)

  private var unfinishedMsgsScheduler: Option[Cancellable] = None

  override def preStart(): Unit = {
    log.info("Starting Master Actor...")

    val fileActor = context.actorOf(FileActor.props(dataBase), "main.FileActor")
    watchActor(fileActor)

    // Watch all the child actors.
    watched.foreach(context.watch)

//    unfinishedMsgsScheduler = Some(context.system.scheduler.schedule(0.seconds, 10.seconds, self,
//      MasterActor.HandleUnfinishedActions)(context.dispatcher))
  }

  override def postStop(): Unit = {
    log.info("Stopping Master Actor...")
    unfinishedMsgsScheduler.foreach(_.cancel())
  }

  override def receive: Receive = {
    case MasterActor.RegisterAction(action, actor) => registerAction(action, actor)
    case MasterActor.HandleUnfinishedActions => handleUnfinishedActions()
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

  /**
    * Get all the actions that are not started, send it to the relevant actors, and update them as sent actions.
    */
  def handleUnfinishedActions() : Unit = {
    implicit val timeout: Timeout = Timeout(10.seconds)

    val response = (dataBase ? DatabaseActor.QueryUnfinishedActions).mapTo[List[DatabaseActor.Action]]

    response.onComplete {
      case Success(actions) =>
        actions
          .filter(action => actionsToActors.contains(action.act_type))
          .foreach(action => {
            actionsToActors(action.act_type) ! action

            dataBase ! DatabaseActor.UpdateActionStatusRequest(action.id.get,DatabaseActor.ACTION_STATUS_SENT,
              DateTime.now())
          })
      case Failure(exp) =>
        log.error(s"Problem while retrieving unfinished actions: ${exp.getMessage}")
        exp.printStackTrace()
    }(context.dispatcher)
  }
}
