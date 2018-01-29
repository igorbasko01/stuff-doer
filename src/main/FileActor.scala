package main

import java.io.{FileInputStream, FileOutputStream}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.joda.time.DateTime

/**
  * Created by igor on 10/10/17.
  */
object FileActor {
  def props(databaseActor: ActorRef) = Props(new FileActor(databaseActor))
}

class FileActor(databaseActor: ActorRef) extends Actor with ActorLogging {
  override def preStart(): Unit = {
    log.info("Starting...")
    context.parent ! MasterActor.RegisterAction("copy_file", self)
    log.info("Started.")
  }

  override def postStop(): Unit = {
    log.info("Stopped.")
  }

  override def receive: Receive = {
    case action: DatabaseActor.Action => handleArrivedAction(action)
    case someMessage => log.warning(s"Got the following message for some reason: $someMessage, from: $sender")
  }

  def handleArrivedAction(action: DatabaseActor.Action) : Unit = {
    databaseActor ! DatabaseActor.UpdateActionStatusRequest(action.id.get,DatabaseActor.ACTION_STATUS_RECEIEVED,
      DateTime.now())

    if (action.params.length == 2) {
      val (src,dst) = (action.params(0),action.params(1))

      new FileOutputStream(dst).getChannel.transferFrom(new FileInputStream(src).getChannel,0,Long.MaxValue)
      // TODO: Update state of the copy progress.
    } else {

    }
  }
}

