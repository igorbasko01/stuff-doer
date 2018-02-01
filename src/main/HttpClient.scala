package main

import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString

object HttpClient {
  def props() : Props = Props(new HttpClient)
}

class HttpClient extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val materializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val http = Http(context.system)

  override def preStart(): Unit = {
    http.singleRequest(HttpRequest(uri = "http://akka.io"))(materializer).pipeTo(self)
  }

  override def receive: Receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      entity.dataBytes.runFold(ByteString(""))(_ ++ _)(materializer).foreach {body =>
        log.info("Got response, body: " + body.utf8String)
      }
    case resp @ HttpResponse(code, _, _, _) =>
      log.info("Request failed, response code: " + code)
      resp.discardEntityBytes()(materializer)
  }

}
