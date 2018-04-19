package utils

import com.typesafe.config.Config

/**
  * Created by igor on 30/05/17.
  */
class Configuration extends Serializable {

  var hostname: String = _
  var portNum: Int = _

  /**
    * Loads the configuration into the Configuration object.
    * Will inform the caller if the function failed.
    * @param config The Config object to load from.
    * @return If successful with loading
    */
  def loadConfig(config: Config): Boolean = {
    try {
      hostname = config.getString("webserver.hostname")
      portNum = config.getInt("webserver.port")
      true
    } catch {
      case e: RuntimeException => println(e.getMessage); false
    }
  }
}
