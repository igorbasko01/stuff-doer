package main

import akka.actor.{Actor, ActorLogging, Props}

/**
  * Created by igor on 10/10/17.
  */
object FileActor {
  def props = Props(new FileActor)
}

class FileActor extends Actor with ActorLogging {
  override def preStart(): Unit = {
    log.info("Starting...")
    context.parent ! MasterActor.RegisterAction("copy_file", self)
    log.info("Started.")
  }

  override def postStop(): Unit = {
    log.info("Stopped.")
  }

  override def receive = {
    case action: DatabaseActor.Action => handleArrivedAction(action)
    case someMessage => log.warning(s"Got the following message for some reason: $someMessage, from: $sender")
  }

  def handleArrivedAction(action: DatabaseActor.Action) : Unit = {

  }
}

