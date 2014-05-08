package alisa.modules.lastfm

import alisa.{CmdHandler, Module}
import java.util.concurrent.{Future, Callable, Executors, ConcurrentHashMap}
import alisa.util.{MircColors => MC, Logger}
import alisa.util.Misc._
import java.net.{HttpURLConnection, URL}
import javax.xml.parsers.DocumentBuilderFactory
import java.io._
import alisa.IrcCommandEvent
import alisa.util.Xml._
import resource._
import java.nio.file.{NoSuchFileException, Files, Paths}
import scala.collection.JavaConversions._
import scala.util.control.ControlThrowable

private object LastFmModule {

	final val USER_MAP_FILE = "lastfm-usermap"
	final val MAX_TAGS = 5

	val TAGS_XP = xpc("/lfm/toptags/tag/name")
	val STATUS_XP = xpc("/lfm/@status")
	val TRACK_XP = xpc("/lfm/recenttracks/track[1]")
	val NP_XP = xpc("@nowplaying")
	val ARTIST_XP = xpc("artist")
	val NAME_XP = xpc("name")
	val MBID_XP = xpc("mbid")
	val ALBUM_XP = xpc("album")
	val MBID_ATTR_XP = xpc("@mbid")
	val LOVED_XP = xpc("loved")

	type UserMap = ConcurrentHashMap[(String, String), String]

	def userKey(event: IrcCommandEvent) = (event.network.name, event.user.user.login)

	case class Failure(msg: String) extends ControlThrowable

	case class TrackInfo(name: String, artist: String, album: Option[String], np: Boolean,
	                     tags: Vector[String], loved: Boolean)
}

final class LastFmModule(apiKey: String) extends Module with CmdHandler with Logger {

	import LastFmModule._

	private val userMap = loadUserMap()

	private val apiBaseUrl = "https://ws.audioscrobbler.com/2.0/?api_key=" + apiKey
	private val recentBaseUrl = apiBaseUrl + "&method=user.getRecentTracks&limit=1&extended=1"
	private val trackTagsBaseUrl = apiBaseUrl + "&method=track.getTopTags&mbid="
	private val albumTagsBaseUrl = apiBaseUrl + "&method=album.getTopTags&mbid="
	private val artistTagsBaseUrl = apiBaseUrl + "&method=artist.getTopTags&mbid="

	private val tagsExecutor = Executors.newFixedThreadPool(3)

	override def stop {
		tagsExecutor.shutdownNow
	}

	def handler = Some(this)

	def handles(cmd: String) = cmd == "lf" || cmd == "lfn" || cmd == "np"

	def handleCommand(event: IrcCommandEvent): Unit =
		parseArgs(event.args.decoded, regex = WS_SPLIT_REGEX) match {
			case "user" :: args => args match {
				case user :: Nil => sendRecent(event, newUser = Some(user))
				case Nil =>
					userMap.remove(userKey(event))
					saveUserMap()
				case _ =>
			}
			case userOrOffset :: Nil => parseInt(userOrOffset) match {
				case Some(offset) => sendRecent(event, offset = Math.abs(offset))
				case _ => sendRecent(event, newUser = Some(userOrOffset /* user */))
			}
			case Nil => sendRecent(event)
			case _ =>
		}

	private def sendRecent(event: IrcCommandEvent, offset: Int = 0,
	                       newUser: Option[String] = None) {
		val lfmUser = newUser match {
			case Some(user) =>
				userMap.put(userKey(event), user)
				saveUserMap()
				user
			case _ => userMap.getOrElse(userKey(event), {
				val login = event.user.user.login
				if (login.startsWith("~"))
					login.substring(1)
				else
					login
			})
		}

		val nick = event.user.nick

		try {
			val t = getRecent(lfmUser, offset)

			val buf = new StringBuilder(128)
			buf ++= nick
			if (!nick.equals(lfmUser))
				buf ++= " (" ++= lfmUser += ')'

			if (t.loved)
				buf += ' ' ++= MC(MC.RED) += '❤' += MC.CLEAR

			buf += ' ' += MC.BOLD
			if (t.np)
				buf ++= MC(MC.RED) ++= "np"
			else
				buf ++= MC(MC.LIGHT_BLUE) ++= "lp"
			buf += MC.CLEAR += ' '

			buf ++= MC(MC.LIGHT_GREEN) ++= t.name += MC.CLEAR
			buf ++= " by " ++= MC(MC.PINK) ++= t.artist += MC.CLEAR
			t.album.foreach(buf ++= " on " ++= MC(MC.LIGHT_CYAN) ++= _ += MC.CLEAR)

			if (!t.tags.isEmpty) {
				val it = t.tags.iterator
				buf ++= " ("
				do {
					buf ++= it.next
					if (it.hasNext)
						buf.append(", ")
				} while (it.hasNext)
				buf += ')'
			}

			event.bot.sendAction(event.channel, buf.toString)
		} catch {
			case Failure(msg) => event.bot.sendMessage(event.channel, s"$nick, $msg")
		}
	}

	private def fail(msg: String = "something failed, s-sorry ;_;") = throw new Failure(msg)

	private def doLfmRequest(strUrl: String): XmlNode = {
		val url = new URL(strUrl)
		val conn = url.openConnection.asInstanceOf[HttpURLConnection]
		val doc = try {
			DocumentBuilderFactory.newInstance.newDocumentBuilder.parse(conn.getInputStream)
		} catch {
			case e: Exception =>
				logWarn("Failed to get or parse XML resource " + url, e)
				fail()
		}
		if (!evalXpathTextOpt(STATUS_XP, doc).exists(_ == "ok")) {
			logWarn("Last.fm request was not OK. URL: " + url + ", reply:\n" + dumpXml(doc))
			fail()
		}
		doc
	}

	private def getRecent(lfmUser: String, offset: Int): TrackInfo = {
		val xmlDoc = doLfmRequest(s"$recentBaseUrl&user=$lfmUser&page=${offset + 1}")

		// tfo ~ tags future option

		val trackNode = evalXpathNodeOpt(TRACK_XP, xmlDoc) getOrElse {
			fail("there are no recent tracks (append last.fm username once?)")
		}
		val trackName = evalXpathText(NAME_XP, trackNode)
		val trackTfo = evalXpathText(MBID_XP, trackNode) match {
			case mbid if !mbid.isEmpty => Some(getTagsFuture(trackTagsBaseUrl, mbid))
			case _ => None
		}

		val artistNode = evalXpathNode(ARTIST_XP, trackNode)
		val artistName = evalXpathText(NAME_XP, artistNode)
		val artistTfo = evalXpathText(MBID_XP, artistNode) match {
			case mbid if !mbid.isEmpty => Some(getTagsFuture(artistTagsBaseUrl, mbid))
			case _ => None
		}

		val albumNode = evalXpathNode(ALBUM_XP, trackNode)
		val albumName = xmlNodeText(albumNode) match {
			case "" => None
			case name => Some(name)
		}
		val albumTfo = albumName flatMap { _ =>
			evalXpathText(MBID_ATTR_XP, albumNode) match {
				case mbid if !mbid.isEmpty => Some(getTagsFuture(albumTagsBaseUrl, mbid))
				case _ => None
			}
		}

		val np = evalXpathNodeOpt(NP_XP, trackNode).exists(xmlNodeText(_) == "true")
		val loved = evalXpathText(LOVED_XP, trackNode) == "1"

		// iterate worker results and build list of at max MAX_TAGS tags, then continue
		// iteration to stop possibly still running remaining workers
		def selectTags(result: Vector[String], done: Boolean,
		               parts: List[Option[Future[Vector[String]]]]): Vector[String] =
			parts match {
				case Some(part) :: xs =>
					if (!done) {
						try {
							val next = result ++ part.get.take(MAX_TAGS - result.length)
							selectTags(next, next.length < MAX_TAGS, xs)
						} catch {
							case e: Exception =>
								logWarn("Tag fetch thread failed", e)
								selectTags(result, done, xs)
						}
					} else {
						part.cancel(true)
						selectTags(result, done, xs)
					}
				case _ :: xs => selectTags(result, done, xs)
				case _ => result
			}
		val tags = selectTags(Vector.empty, false, List(trackTfo, albumTfo, artistTfo))

		TrackInfo(trackName, artistName, albumName, np, tags, loved)
	}

	private def getTagsFuture(baseUrl: String, mbid: String) =
		tagsExecutor.submit(new Callable[Vector[String]] {
			def call = evalXpathNodeList(TAGS_XP, doLfmRequest(baseUrl + mbid))
					.take(MAX_TAGS)
					.map(xmlNodeText)
					.toVector
		})

	private def loadUserMap() =
		try {
			managed(new ObjectInputStream(new BufferedInputStream(
				Files.newInputStream(Paths.get(USER_MAP_FILE))))) acquireAndGet {
				in => in.readObject.asInstanceOf[UserMap]
			}
		} catch {
			case _: NoSuchFileException => new UserMap
		}

	private def saveUserMap(): Unit =
		synchronized {
			managed(new ObjectOutputStream(new BufferedOutputStream(
				Files.newOutputStream(Paths.get(USER_MAP_FILE))))) acquireAndGet {
				in => in.writeObject(userMap)
			}
		}
}
