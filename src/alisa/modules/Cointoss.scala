package alisa.modules

import alisa.{ModuleProvider, SimpleCmdModule, IrcCommandEvent}
import scala.util.Random

final class Cointoss extends SimpleCmdModule("cointoss") with ModuleProvider {

	def handleCommand(event: IrcCommandEvent) {
		val result = if (Random.nextBoolean) "heads" else "tails"
		event.bot.sendMessage(event.channel, s"${event.user.nick}, $result")
	}
}
