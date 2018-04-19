package webserver

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import scheduler.BaschedRequest
import spray.json.DefaultJsonProtocol

trait WebServerJsonReply extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val taskFormat = jsonFormat9(BaschedRequest.Task)
  implicit val tasksFormat = jsonFormat1(WebServerActor.Tasks)

  implicit val projFormat = jsonFormat2(BaschedRequest.Project)
  implicit val projsFormat = jsonFormat1(WebServerActor.Projects)

  implicit val pomodoroDurationFormat = jsonFormat1(WebServerActor.PomodoroDuration)
}

