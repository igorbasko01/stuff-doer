package core.utils

import com.typesafe.config.Config

/**
  * Created by igor on 30/05/17.
  */
class Configuration extends Serializable {

  var hostname: String = _
  var portNum: Int = _
  var password: String = _

  /**
    * Loads the configuration into the Configuration object.
    * Will inform the caller if the function failed.
    * @param config The Config object to load from.
    * @return If successful with loading
    */
  def loadConfig(config: Config): Boolean = {
    try {
      hostname = config.getString("core.webserver.hostname")
      portNum = config.getInt("core.webserver.port")
      password = config.getString("core.webserver.pass")

      validateConfig()

    } catch {
      case e: RuntimeException => println(e.getMessage); false
    }
  }

  /**
    * This function validates the config params.
    * @return true if all validation were ok.
    */
  def validateConfig() : Boolean = {
    if (password.trim().isEmpty) {
      println("core.webserver.password cannot be empty")
      false
    }

    true
  }
}
