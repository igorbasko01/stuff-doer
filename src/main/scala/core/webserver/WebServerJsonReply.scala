package core.webserver

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import core.utils.Message
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

trait WebServerJsonReply extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val taskFormat: RootJsonFormat[Message.Task] = jsonFormat9(Message.Task)
  implicit val tasksFormat: RootJsonFormat[Message.Tasks] = jsonFormat1(Message.Tasks)

  implicit val projFormat: RootJsonFormat[Message.Project] = jsonFormat2(Message.Project)
  implicit val projsFormat: RootJsonFormat[Message.Projects] = jsonFormat1(Message.Projects)

  implicit val pomodoroDurationFormat: RootJsonFormat[Message.PomodoroDuration] = jsonFormat1(Message.PomodoroDuration)

  implicit val aggRecordFormat: RootJsonFormat[Message.AggregatedRecord] = jsonFormat2(Message.AggregatedRecord)
  implicit val aggRecordsFormat: RootJsonFormat[Message.AggRecords] = jsonFormat1(Message.AggRecords)
}

