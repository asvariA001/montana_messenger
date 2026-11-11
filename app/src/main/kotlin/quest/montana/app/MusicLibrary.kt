package quest.montana.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/** A feed's name as the person sees it: Saved Messages in their language, else the chat's own (or pinned) name. */
fun roomTitle(c: Context, chat: Chat): String =
    if (chat.ref == Book.SAVED) c.getString(R.string.saved_messages) else chat.shown.ifBlank { chat.ref.take(8) }

/**
 * THE MUSIC LIBRARY, ONE BUILDER (iOS ChatStore.musicLibrary / MontanaMusicLibrary.build, MontanaMusicPlayer.swift:58-100 and
 * MontanaChatStore.swift:3124-3155 at 956efd5b): every music file of every chat that lies on this phone, and the lent folders'
 * tracks, in order of appearance -- a folder's tracks at the moment it was lent, a letter first on a tie -- ONE TRACK ONCE (the
 * same name without its kind and the same size: one song sent to two chats or lying in a folder as well is one row). The page reads
 * it newest first. The build walks off the main thread and is built again only after the letters or the folders changed, and only
 * while someone looks (`wanted`); the page reads the last list built until the new one lands.
 */
object MusicLibrary {
    /** Newest first: the page's order, and the queue a tap starts. */
    @Volatile var list: List<Track> = emptyList(); private set
    val listeners = mutableListOf<() -> Unit>()
    /** Whether the list is looked at now (the page on the screen): a change of the letters builds it again only then. */
    var wanted: () -> Boolean = { false }
    private val lock = Any()
    private var gen = 0
    private var builtGen = -1
    private var building = false
    private var heard = false
    private var again = Runnable {}
    private val known = HashMap<String, Track>()    // a letter's track by its file, with what was read of its tags
    private val tagged = HashSet<String>()          // the files whose tags were read once

    private fun hear(app: Context) {
        if (heard) return
        heard = true
        Book.listen { changed(app) }
        MusicFolders.listeners += { changed(app) }
    }
    private fun changed(app: Context) {
        synchronized(lock) { gen++ }
        if (wanted()) { MainThread.cancel(again); again = Runnable { ask(app) }; MainThread.later(300, again) }   // a burst of letters is one build
    }

    /** The page asks: a build starts when the last one is stale, and its landing is told to the listeners on the main thread. */
    fun ask(c: Context) {
        val app = c.applicationContext
        val g = synchronized(lock) {
            hear(app)
            if (building || builtGen == gen) return
            building = true
            gen
        }
        Thread { land(app, g) }.start()
    }

    /** The list as it stands now, built here when stale (off the main thread: the source filter asks it before it plays). */
    fun fresh(c: Context): List<Track> {
        val app = c.applicationContext
        val g = synchronized(lock) { hear(app); if (builtGen == gen) return list; gen }
        land(app, g)
        return list
    }

    private fun land(app: Context, g: Int) {
        val out = try { build(app) } catch (e: Exception) { Log.w("Montana", "music_build failed " + e.javaClass.simpleName); null }
        val stale = synchronized(lock) {
            building = false
            if (out != null) { list = out; if (builtGen < g) builtGen = g }
            builtGen != gen
        }
        MainThread.post {
            listeners.toList().forEach { it() }
            if (stale && wanted()) ask(app)
        }
        if (out != null) readTags(app, out)
    }

    private fun build(app: Context): List<Track> {
        val t0 = System.currentTimeMillis()
        val letters = ArrayList<Track>()
        for (chat in Book.rooms()) {
            val title = roomTitle(app, chat)
            for (r in chat.msgs) {
                val path = r.file ?: continue   // only a file on this phone (iOS fileOnDisk)
                val man = Media.manifestOf(r) ?: continue
                if (!isTrackLetter(r, man) || OldRecordings.caption(man)) continue
                val f = File(path)
                if (!f.exists()) continue
                val name = man.optString("n").ifEmpty { f.name }
                val t = synchronized(known) { known.getOrPut(path) { Track(Uri.fromFile(f), name) } }
                t.chat = chat.ref; t.chatTitle = title; t.mid = r.mid; t.at = r.at / 1000.0
                if (t.length == 0.0) t.length = man.optDouble("du", 0.0)
                t.size = f.length()
                letters += t
            }
        }
        letters.sortBy { it.at }
        // the folders stand newest first and each in its own order on the page, so they come in here the other way round
        val lent = MusicFolders.tracks.reversed()
        val merged = ArrayList<Track>(letters.size + lent.size)
        var i = 0; var j = 0
        while (i < letters.size || j < lent.size) {
            if (j == lent.size || (i < letters.size && letters[i].at <= lent[j].at)) merged += letters[i++] else merged += lent[j++]
        }
        val files = HashSet<String>(); val same = HashSet<String>()
        val once = merged.filter { t -> files.add(t.uri.toString()) && same.add(key(t)) }
        Log.d("Montana", "music_build n=" + once.size + " of " + merged.size + " lent=" + lent.size + " ms=" + (System.currentTimeMillis() - t0))
        return once.reversed()
    }
    /** ONE TRACK ONCE (iOS MontanaMusicLibrary.key): the name without its kind, lower case, and the size. */
    private fun key(t: Track) = t.file.substringBeforeLast('.').lowercase() + "#" + t.size

    /** A letter's track wears its own tags' title and artist, as a lent folder's does (iOS MTTrackMeta): read once, behind the page. */
    private fun readTags(app: Context, tracks: List<Track>) {
        var read = 0
        for (t in tracks) {
            if (t.lent || t.uri.scheme != "file") continue
            val path = t.uri.path ?: continue
            if (!synchronized(tagged) { tagged.add(path) }) continue
            try {
                MediaMetadataRetriever().apply {
                    setDataSource(path)
                    t.tagTitle = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    t.artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                    if (t.length == 0.0) t.length = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                    release()
                }
                read++
            } catch (_: Exception) {}
        }
        if (read > 0) MainThread.post { listeners.toList().forEach { it() } }
    }
}

/**
 * WHERE A TRACK CAME FROM, THE PLAYLIST'S ONE CHOICE (iOS MTTrackSource, MontanaMusicPlayer.swift:364-420 at 956efd5b; the author's
 * word 30.09 ~00:55: «to the right of the search in the big music player put the native filter, so that one can choose where one
 * listens from -- a chat, ..., a folder and so on»): every track, the chats', the walls' (the feed's posts and my wall's), the lent
 * folders', or one chat's.
 */
sealed class TrackSource {
    object All : TrackSource()
    object Chats : TrackSource()
    object Walls : TrackSource()
    object Folders : TrackSource()
    data class One(val chat: String) : TrackSource()

    fun holds(t: Track): Boolean = when (this) {
        All -> true
        Chats -> of(t) is One
        else -> of(t) == this
    }

    companion object {
        fun of(t: Track): TrackSource = when {
            t.lent -> Folders
            t.inPost -> Walls
            else -> One(t.chat ?: "")
        }
    }
}

object MusicSource {
    /** The filter's choice; the round glows in the platform's blue while it is not «All». */
    @Volatile var chosen: TrackSource = TrackSource.All

    /**
     * A SOURCE CHOSEN, WHAT PLAYS IS THAT SOURCE (iOS MTTrackSource.listen): its tracks become the queue, and the track that plays
     * keeps playing when the source holds it, else the source's first plays. A source that holds nothing changes nothing.
     */
    fun listen(act: MainActivity, s: TrackSource) {
        chosen = s
        Thread {
            val list = if (s == TrackSource.Walls) boardTracks(Board.feed()) else MusicLibrary.fresh(act).filter { s.holds(it) }
            MainThread.post {
                Log.d("Montana", "music_source listen n=" + list.size)
                if (list.isEmpty()) return@post
                val playing = MusicPlayer.current?.uri
                val i = list.indexOfFirst { it.uri == playing }
                if (i != -1) MusicPlayer.requeue(list, i) else MusicPlayer.play(act, list, 0)
            }
        }.start()
    }

    /** The chats the library's letters came from, each once, in the library's order: the filter's one-chat choice. */
    fun chats(): List<Pair<String, String>> {
        val once = HashSet<String>()
        return MusicLibrary.list.mapNotNull { t ->
            val ref = t.chat
            if (t.lent || t.inPost || ref == null || !once.add(ref)) null else ref to t.chatTitle
        }
    }

    /** Where the queue plays from, read off what it holds (iOS MTQueuePage.from): the one chat's name, the walls, the folders, the chats, or all. */
    fun from(c: Context, queue: List<Track>): String {
        val first = queue.firstOrNull() ?: return ""
        val kinds = queue.map { TrackSource.of(it) }.toSet()
        if (kinds.size == 1) when (kinds.first()) {
            is TrackSource.One -> if (first.chatTitle.isNotEmpty()) return first.chatTitle
            TrackSource.Walls -> return c.getString(R.string.queue_walls)
            TrackSource.Folders -> return c.getString(R.string.cf_folders)
            else -> {}
        }
        if (kinds.all { it is TrackSource.One }) return c.getString(R.string.chats)
        return c.getString(R.string.cf_all)
    }
}
