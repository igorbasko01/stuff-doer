package main

import akka.actor.{Actor, ActorLogging, Props}

object Basched {
  def props(): Props = Props(new Basched)
}

class Basched extends Actor with ActorLogging {

  override def receive: Receive = {
    case _ => log.warning(s"Got unhandled message: ${_}")
  }
}
