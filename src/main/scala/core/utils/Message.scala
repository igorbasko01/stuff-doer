package core.utils

import scala.collection.mutable.ArrayBuffer

sealed trait Message extends Serializable

object Message {

  final case object Shutdown extends Message

  // Database Actor messages

  final case class QueryDB(reqId: Int, query: String, update: Boolean = false) extends Message
  final case class QueryResult(reqId: Int, result: Option[ArrayBuffer[List[String]]], message: String, errorCode: Int) extends Message

  final case class IsTableExists(tableName: String) extends Message
  final case class TableExistsResult(tableName: String, isExist: Boolean) extends Message

  // BaschedRequest messages

  final case class Project(id: Int, name: String)
  final case object RequestAllProjects extends Message
  final case class ReplyAllProjects(projects: List[Project]) extends Message

  final case class AddTask(prjId: Int, name: String, priority: String) extends Message
  final case class ReplyAddTask(response: Int) extends Message

  final case object RequestAllUnfinishedTasks extends Message
  final case class Task(id: Int, prjId: Int, prjName: String, name: String, startTimestamp: String, priority: Int, status: Int,
                  pomodoros: Int, current: Boolean)
  final case class ReplyAllUnfinishedTasks(tasks: List[Task])

  final case class RequestAddRecord(taskId: Int, endTimestamp: Long, duration: Long) extends Message
  final case class ReplyAddRecord(response: Int)

  final case class RequestAddProject(projectName: String) extends Message
  final case class ReplyAddProject(response: Int)

  final case class RequestRemainingTimeInPomodoro(taskId: Int, priority: Int) extends Message
  final case class ReplyRemainingTimeInPomodoro(duration: Long)

  final case class RequestHistoricalTaskDuration(taskId: Int) extends Message
  final case class ReplyHistoricalTaskDuration(duration: Long)

  final case class RequestUpdatePmdrCountInTask(taskId: Int, pmdrsToAdd: Int) extends Message
  final case class ReplyUpdatePmdrCountInTask(response: Int)

  final case class RequestTaskDetails(taskId: Int) extends Message
  final case class ReplyTaskDetails(task: Task)

  final case class RequestTaskStatusUpdate(taskid: Int, newStatus: Int) extends Message
  final case class ReplyTaskStatusUpdate(response: Int)

  final case object RequestUpdateAllWindowFinishedToReady extends Message
  final case class ReplyUpdateAllWindowFinishedToReady(response: Int)

  final case class RequestStartTask(taskid: Int, initialDuration: Long) extends Message
  final case class ReplyStartTask(response: Int)

  final case class RequestUpdateLastPing(taskid: Int) extends Message
  final case class ReplyUpdateLastPing(response: Int)

  final case class RequestActiveTaskDetails(taskid: Int) extends Message
  final case class ActiveTask(taskid: Int, startTimestamp: String, endTimestamp: String, initDuration: Long)
  final case class ReplyActiveTaskDetails(status: Int, activeTask: ActiveTask)

  final case class RequestDeleteActiveTask(taskid: Int) extends Message
  final case class ReplyDeleteActiveTask(response: Int)

  final case object RequestActiveTasks extends Message
  final case class ReplyActiveTasks(status: Int, activeTasks: List[ActiveTask])

  final case class RequestUpdateTaskPriority(taskid: Int, priority: Int) extends Message
  final case class ReplyUpdateTaskPriority(response: Int)

  final case class Record(id: Int, taskId: Int, end: Long, duration_ms: Long)
  final case class AggregatedRecord(taskName: String, duration: Long)
  final case class RequestAggRecordsByDateRange(from: String, to: String) extends Message
  final case class ReplyAggRecordsByDateRange(response: Int, aggRecords: List[AggregatedRecord]) extends Message

  // WebserverActor messages

  final case class Tasks(tasks: List[Message.Task]) extends Message
  final case class Projects(projects: List[Message.Project]) extends Message
  final case class PomodoroDuration(duration: Long) extends Message
  final case class AggRecords(records: List[Message.AggregatedRecord]) extends Message

}