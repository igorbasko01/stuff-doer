import akka.actor.{Actor, ActorRef, Props}
import core.database.DatabaseActor
import core.scheduler.{Basched, BaschedRequest}
import core.utils.Configuration
import core.webserver.WebServerActor

package object core {

  case class PropsWithName(props: Props, actorName: String)

  val TABLE_NAME_TASKS = "tasks"
  val TABLE_NAME_RECORDS = "records"
  val TABLE_NAME_PROJECTS = "projects"
  val TABLE_NAME_ACTIVE_TASK = "active_task"

  val PRIORITY = Map(
    "im" -> 0,
    "hi" -> 1,
    "re" -> 2
  )

  val STATUS = Map(
    "READY" -> 0,
    "WINDOW_FINISHED" -> 1,
    "ON_HOLD_WINDOW_FINISHED" -> 2,
    "ON_HOLD_READY" -> 3,
    "FINISHED" -> 4
  )

  val NUM_OF_PMDRS_PER_PRIORITY = Map(
    PRIORITY("im") -> 0,
    PRIORITY("hi") -> 8,
    PRIORITY("re") -> 4
  )

  // 25m in ms
  val POMODORO_MAX_DURATION_MS = 1500000

  val ADDED = 0
  val DUPLICATE = 1
  val ERROR = 2
  val UPDATED = 3
  val SUCCESS = 4

  /**
    * Generic function to create Actor props
    * @param newActor - function to create the Actor
    * @return new Props object of the received Actor
    */
  def props(newActor: => Actor): Props = Props(newActor)

  /**
    * props function to use when instantiating a [[Basched]] actor.
    * @param config The configuration object of the application.
    * @param clientProps client configuration
    * @return A [[Props]] object with an instantiated [[Basched]] class.
    */
  def props(config: Configuration, clientProps: List[PropsWithName]): Props =
    Props(new DatabaseActor(config, clientProps))

  /**
    * Returns a [[Props]] object with an instantiated [[Basched]] class.
    * @param config The configuration object of the application.
    * @return A [[Props]] object with an instantiated [[Basched]] class.
    */
  def props(config: Configuration): Props = Props(new Basched(config))

  /**
    * Returns a [[Props]] object with instantiated [[BaschedRequest]] class.
    * @param db The [[DatabaseActor]] that the queries will be sent to.
    * @return Returns a [[Props]] object with instantiated [[BaschedRequest]] class.
    */
  def props(db: ActorRef): Props = Props(new BaschedRequest(db))

  // A recommended way of creating props for actors with parameters.
  def props(hostname: String, port: Int, password: String,  databaseActor: ActorRef): Props =
    Props(new WebServerActor(hostname, port, password, databaseActor))

}
