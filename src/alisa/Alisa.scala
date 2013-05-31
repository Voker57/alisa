package alisa

import scala.collection.JavaConversions._
import sun.misc.{SignalHandler, Signal}
import joptsimple.OptionParser
import java.util.logging.{Logger => JDKLogger, Level}
import scala.util.Try
import scala.Some
import alisa.util.Logger

object Alisa extends Logger {

	val TEST_CONFIG = "alisa-test.conf"

	def main(args: Array[String]) {
		val termHandler = new SignalHandler {
			def handle(sig: Signal) {
				System.exit(0) // just override exit code to 0
			}
		}

		Signal.handle(new Signal("TERM"), termHandler)
		Signal.handle(new Signal("INT"), termHandler)

		val optParser = new OptionParser
		val debugOpt = optParser.accepts("d", "Debug log level")
				.withOptionalArg
				.ofType(classOf[Boolean])

		val optSet = optParser.parse(args: _*)
		val newArgs = optSet.nonOptionArguments

		val config =
			if (newArgs.length > 0)
				ConfigParser.parseFile(newArgs(0))
			else
				ConfigParser.parseFile(TEST_CONFIG)

		val verbose =
			if (optSet.has(debugOpt)) {
				if (optSet.hasArgument(debugOpt))
					optSet.valueOf(debugOpt)
				else
					true
			} else {
				config.verbose
			}
		// TODO works?
		initLogging(verbose)


		val mods = startModules(config.modules)

		val nets = mods.flatMap(mods => {
			val handlers = mods.foldLeft(IrcEventHandlerLists())({
				(list, mod) =>
					mod.handlers match {
						case Some(handler) => handler :: list
						case None => list
					}
			})
			startNetworks(config.networks, handlers)
		})

		sys.addShutdownHook({
			nets.foreach(stopNetworks)
			mods.foreach(stopModules)
		})

		nets.recover {
			case e: Exception => {
				logError("Init failed", e)
				System.exit(1)
			}
		}
	}

	def startModules(configs: List[ModuleConfig]): Try[List[Module]] = {
		val factory = new ModuleFactory

		def iter(remaining: List[ModuleConfig], modules: List[Module]): List[Module] = {
			remaining match {
				case conf :: xs =>
					try {
						iter(xs, factory.create(conf.name, conf.params) :: modules)
					} catch {
						case e: Exception => {
							stopModules(modules)
							throw e
						}
					}
				case Nil => modules
			}
		}

		Try(iter(configs, Nil))
	}

	def stopModules(modules: List[Module]) {
		for (m <- modules)
			try {
				m.stop
			} catch {
				case e: Exception => logError(s"Module `$m' failed to stop", e)
			}
	}

	def startNetworks(configs: List[NetworkConfig], handlers: IrcEventHandlerLists): Try[List[AlisaNetwork]] = {
		def iter(remaining: List[NetworkConfig], networks: List[AlisaNetwork]): List[AlisaNetwork] = {
			remaining match {
				case conf :: xs =>
					try {
						iter(xs, new AlisaNetwork(conf, handlers) :: networks)
					} catch {
						case e: Exception => {
							stopNetworks(networks)
							throw e
						}
					}
				case Nil => networks
			}
		}

		Try(iter(configs, Nil))
	}

	def stopNetworks(networks: List[AlisaNetwork]) {
		for (n <- networks)
			try {
				n.networkDisconnect
			} catch {
				case e: Exception => logError(s"Network `$n' failed to stop", e)
			}
	}

	def initLogging(debug: Boolean) {
		val level =
			if (debug)
				Level.FINEST
			else
				Level.INFO
		JDKLogger.getLogger(JDKLogger.GLOBAL_LOGGER_NAME).setLevel(level)
	}
}
