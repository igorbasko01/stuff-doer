package main

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

object Basched {
  def props(): Props = Props(new Basched)
}

class Basched extends Actor with ActorLogging {

  var db: ActorRef = _

  override def preStart(): Unit = {
    log.info("Starting...")
    context.parent ! MasterActor.GetDBActor
  }

  override def receive: Receive = {
    case MasterActor.DBActor(x) => db = x
    case _ => log.warning(s"Got unhandled message: ${_}")
  }
}
