package lib.log

import chisel3.{PrintableHelper, fromBooleanToLiteral, when}
import lib.log.Log.Level

/**
 * Chisel logging helper
 *
 * Facilitates logging at configurable levels and modules during simulation.
 */
object Log {
  sealed abstract class Level(val short: String, val name: String, val order: Int) extends Ordered[Level] {
    def compare(that: Level): Int = this.order - that.order
  }

  object Level {
    case object Silent extends Level("", "", -1)
    case object Critical extends Level("CRT", "crit", 0)
    case object Error extends Level("ERR", "error", 1)
    case object Warning extends Level("WRN", "warn", 2)
    case object Info extends Level("INF", "info", 3)
    case object Debug extends Level("DBG", "debug", 4)

    private val all: Seq[Level] = Seq(Silent, Critical, Error, Warning, Info, Debug)

    def fromString(s: String): Option[Level] = all.find(l => {
      s.equalsIgnoreCase(l.short) || s.equalsIgnoreCase(l.name)
    })
  }

  private var defaultLevel: Level = Level.Critical
  private val levels = collection.mutable.Map[String, Level]()

  protected[log] def getLevel(module: String): Level = {
    var key = module
    while (key.nonEmpty) {
      levels.get(key) match {
        case Some(level) => return level
        case None =>
      }
      val dot = key.lastIndexOf(".")
      if (dot < 0) {
        key = ""
      } else {
        key = key.substring(0, dot)
      }
    }
    levels.getOrElse(module, defaultLevel)
  }

  def setDefaultLevel(level: Level): Unit = defaultLevel = level
  def setModuleLevel(module: String, level: Level): Unit = levels.put(module, level)

  /// Set the levels from a string like "module1:level1,module2:level2"
  def setLevelsFromString(string: String): Unit = {
    string
      .split(",")
      .map(_.split(":"))
      .map { case Array(k, v) => (k, v)}
      .foreach(p => {
        setModuleLevel(p._1, Level.fromString(p._2).get)
      })
  }
}

object Logger {
  def apply(module: String): Logger = new Logger(module, enable = None)
  def apply(module: String, enable: chisel3.Bool): Logger = new Logger(module, enable = Some(enable))

  def log(level: Level, module: String, log: chisel3.Printable): Unit = {
    if (level <= Log.getLevel(module)) {
      chisel3.printf(cf"[${level.short}][${module}] " + log + cf"\n")
    }
  }
}

class Logger(module: String, enable: Option[chisel3.Bool]) {
  private def maybeLog(level: Level, message: chisel3.Printable): Unit = {
    enable match {
      case Some(enable) => {
        when (enable) {
          Logger.log(level, module, message)
        }
      }
      case _ => Logger.log(level, module, message)
    }
  }

  def crit(log: chisel3.Printable): Unit = maybeLog(Log.Level.Critical, log)
  def error(log: chisel3.Printable): Unit = maybeLog(Log.Level.Error, log)
  def warn(log: chisel3.Printable): Unit = maybeLog(Log.Level.Warning, log)
  def info(log: chisel3.Printable): Unit = maybeLog(Log.Level.Info, log)
  def debug(log: chisel3.Printable): Unit = maybeLog(Log.Level.Debug, log)
}
