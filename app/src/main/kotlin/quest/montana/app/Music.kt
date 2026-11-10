package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

// ─────────────────────────── the folders lent to the music (iOS MTMusicFolders) ───────────────────────────

/** A track as a walk found it: where it lies, its file's name, and — once read — its tag's title and its length. */
class Track(val uri: Uri, val file: String) {
    @Volatile var tagTitle: String? = null
    @Volatile var artist: String? = null
    @Volatile var length: Double = 0.0
    /** The picture its folder holds, when it holds one: the cover of a file that carries none (iOS MTMusicFolders.art). */
    @Volatile var art: Uri? = null
    val title: String get() = tagTitle ?: file.substringBeforeLast('.')
}

/**
 * THE PLUS LENDS A FOLDER: the platform's own permission for it is kept (the document tree the system's picker hands over,
 * taken as a persistable grant) — nothing is copied, nothing enters a chat, and a file put into the folder later comes in with
 * the next walk (every opening of the page). The folder lent last stands first, as on iOS.
 */
object MusicFolders {
    private const val KEY = "musicFolders"
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "alac", "aif", "aiff", "wma")
    @Volatile var tracks: List<Track> = emptyList(); private set
    @Volatile var walking = false; private set
    private val known = HashMap<String, Track>()   // what a walk found before, with what was read of it
    val listeners = mutableListOf<() -> Unit>()
    private fun said(act: MainActivity) = act.onMain { listeners.toList().forEach { it() } }

    private fun lent(): List<Uri> = Prefs.str(KEY, "").split('\n').filter { it.isNotEmpty() }.map(Uri::parse)

    /** The folders this phone still holds leave for, as authority and tree id: a track's own uri is compared by them, never as a string. */
    fun held(c: Context): Set<Pair<String?, String>> = c.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri.authority to DocumentsContract.getTreeDocumentId(it.uri) }.toSet()
    /** A lent track whose folder lost its leave (iOS MTMusicFolders.closed, 6e2b29ca): the page holds none, a queue made before may. */
    fun closed(t: Track, held: Set<Pair<String?, String>>) =
        DocumentsContract.isTreeUri(t.uri) && (t.uri.authority to DocumentsContract.getTreeDocumentId(t.uri)) !in held

    fun lend(act: MainActivity, tree: Uri) {
        try { act.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        val all = lent().filter { it != tree } + tree
        Prefs.setStr(KEY, all.joinToString("\n"))
        walk(act, picked = true)
    }

    /** Walks every lent folder, at any depth; then reads each new track's tag and length behind the page. */
    fun walk(act: MainActivity, picked: Boolean = false) {
        if (walking) return
        walking = picked; said(act)
        act.background {
            val found = ArrayList<Track>()
            for (tree in lent().reversed()) {
                val here = ArrayList<Track>()
                try { collect(act, tree, DocumentsContract.getTreeDocumentId(tree), here) } catch (_: Exception) {}
                found += here.sortedBy { it.file.lowercase() }
            }
            tracks = found
            walking = false
            said(act)
            for (t in found) if (t.length == 0.0 && t.tagTitle == null) {
                try {
                    MediaMetadataRetriever().apply {
                        setDataSource(act, t.uri)
                        t.tagTitle = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                        t.artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                        t.length = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                        release()
                    }
                } catch (_: Exception) {}
            }
            said(act)
        }
    }

    private fun collect(c: Context, tree: Uri, doc: String, out: MutableList<Track>) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val here = ArrayList<Track>()
        val pictures = ArrayList<Pair<String, String>>()   // a picture's name and its document
        c.contentResolver.query(kids, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0); val name = cur.getString(1) ?: continue; val mime = cur.getString(2) ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) collect(c, tree, id, out)
                else if (mime.startsWith("audio/") || ext in audio) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    here += known.getOrPut(uri.toString()) { Track(uri, name) }
                } else if (ext in picture) pictures += name to id
            }
        }
        // EACH TRACK TAKES ITS OWN FOLDER'S COVER, when the folder holds a picture (iOS MTMusicFolders walk, MontanaMusicPlayer.swift:
        // 2425-2431 at 2310; the author's word 24.09: «the covers are there, and another music app shows them locally»).
        val art = cover(pictures)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
        for (t in here) t.art = art
        out += here
    }
    private val picture = setOf("jpg", "jpeg", "png", "heic", "webp")
    /** A folder's cover among its pictures, as music players have long chosen it: the one named for the cover, else the first by
     *  name (iOS MTMusicFolders.cover(among:), MontanaMusicPlayer.swift:2437-2446 at 2310). */
    private fun cover(pictures: List<Pair<String, String>>): String? {
        val byName = pictures.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        fun stem(n: String) = n.substringBeforeLast('.').lowercase()
        for (want in listOf("cover", "folder", "front", "album", "albumart", "albumartsmall", "artwork"))
            byName.firstOrNull { stem(it.first) == want }?.let { return it.second }
        return (byName.firstOrNull { stem(it.first).startsWith("albumart") } ?: byName.firstOrNull())?.second
    }
}

// ─────────────────────────── a track's picture (iOS MTTrackMeta.wholeCover, MTMusicArt) ───────────────────────────

/**
 * A TRACK'S PICTURE, ONE OWNER (iOS MTTrackMeta and MTMusicArt, MontanaMusicPlayer.swift:918-984 and 1689-1827 at 2310; the author's
 * words 23.09 «the music file's cover, when it has one», 29.09 ~23:10 «the app's icon natively, if the music has no real cover»):
 * the file's own picture, else the one its folder holds, else the app's own icon in its own shape. Only the track that plays wears
 * one here -- the unfolded plate, its opened stage and the lock screen -- so the one track's picture is read, whole (1024 a side, as
 * iOS reads the lock screen's), off the main thread, once; its landing redraws the player and the lock screen (iOS readingLanded).
 * A cover is a peer's file like any other: past 32 MB it is not drawn, and it is never decoded whole.
 */
object TrackArt {
    private const val SIDE = 1024
    private const val CAP = 32 * 1_048_576
    /** The icon's own shape: a rounded square whose corner is 22.37 % of its side (iOS MTMusicArt.corner). */
    const val ICON_CORNER = 0.2237f
    @Volatile private var ofKey = ""
    @Volatile private var asking = ""
    @Volatile private var picture: android.graphics.Bitmap? = null
    private var icon: android.graphics.Bitmap? = null
    private var lock: android.graphics.Bitmap? = null

    /** The playing track's own picture once read (null: none, or not yet -- asked here, and its landing says so). */
    fun of(c: Context, t: Track): android.graphics.Bitmap? {
        val key = t.uri.toString()
        if (ofKey == key) return picture
        if (asking != key) {
            asking = key
            val a = c.applicationContext
            Thread {
                var from = "none"
                val own = runCatching {
                    MediaMetadataRetriever().run { try { setDataSource(a, t.uri); embeddedPicture } finally { release() } }
                }.getOrNull()?.takeIf { it.size <= CAP }?.let { decode(it) }
                if (own != null) from = "file"
                val img = own ?: t.art?.let { u -> runCatching { a.contentResolver.openInputStream(u)?.use { s -> read(s) } }.getOrNull()?.let { decode(it) } }
                if (own == null && img != null) from = "folder"
                android.util.Log.d("Montana", "music_meta kind=${t.file.substringAfterLast('.', "").lowercase()} cover=${if (img != null) 1 else 0} from=$from")
                MainThread.post { if (asking == key) { ofKey = key; picture = img; asking = ""; MusicPlayer.landed() } }
            }.start()
        }
        return null
    }

    /** The app's icon, drawn down once (iOS MTMusicArt.thumb): what a plate shows where the track has no cover. */
    fun icon(c: Context): android.graphics.Bitmap = icon ?: res(c).also { icon = it }
    /** The icon for the lock screen, cut by its own shape with its corners clear (iOS MTMusicArt.lockIcon). */
    fun lockIcon(c: Context): android.graphics.Bitmap = lock ?: icon(c).let { src ->
        val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cv = android.graphics.Canvas(out)
        val r = src.width * ICON_CORNER
        cv.drawRoundRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(src, 0f, 0f, p)
        out
    }.also { lock = it }

    private fun res(c: Context): android.graphics.Bitmap =
        android.graphics.BitmapFactory.decodeResource(c.resources, R.drawable.music_icon_art)   // 512 px, iOS AppIconPicture (the coin, 678ff911) drawn down once
    /** Bytes up to the cap, else nothing. */
    private fun read(s: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536)
        while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > CAP) return null }
        return out.toByteArray()
    }
    /** Drawn down to the side by the platform's own sampling, never decoded whole. */
    private fun decode(d: ByteArray): android.graphics.Bitmap? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var k = 1
        while (maxOf(o.outWidth, o.outHeight) / (k * 2) >= SIDE) k *= 2
        val b = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = k }) ?: return null
        val m = maxOf(b.width, b.height)
        if (m <= SIDE) b else android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, b.width * SIDE / m), maxOf(1, b.height * SIDE / m), true)
    }.getOrNull()
}

// ─────────────────────────── the one player (iOS VoicePlayer for the music) ───────────────────────────

/**
 * THE ONE PLAYER: a queue — the list as it stood when a row was tapped, so the next track is the row below — the platform's
 * MediaPlayer with the system's audio focus, and one clock that tells the bar where the track stands. Every change of the
 * track, its rest or its place is told to the system too (MusicService), so the shade, the lock screen and the headphones
 * follow it; the clock's ticks are not — the system runs the position on by itself.
 */
object MusicPlayer {
    var queue: List<Track> = emptyList(); private set
    var index = -1; private set
    var playing = false; private set
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    val listeners = mutableListOf<() -> Unit>()
    private fun said() { listeners.toList().forEach { it() }; Playing.changed() }
    private fun changed() { said(); app?.let { MusicService.sync(it) } }
    val current: Track? get() = queue.getOrNull(index)
    var refused: ((Int) -> Unit)? = null
    /** The nearest track from `from` that can sound, past the tracks of a folder that did not open (iOS nextIndex, prev at 6e2b29ca). */
    private fun sounding(c: Context, from: Int, step: Int): Int? {
        val held = MusicFolders.held(c)
        var i = from
        while (i in queue.indices) { if (!MusicFolders.closed(queue[i], held)) return i; i += step }
        return null
    }
    /** Where the player stands, for the diary; a player in error may not answer. */
    private fun clock(p: MediaPlayer) = if (prepared) runCatching { "at=" + p.currentPosition / 1000 + "s of=" + p.duration / 1000 + "s" }.getOrDefault("at=-1s of=-1s") else "at=-1s of=-1s"

    private var focus: AudioFocusRequest? = null
    /** True when the system gave the sound; under another app's call it does not (iOS MontanaAudioSession.wake's ready). */
    private fun takeFocus(c: Context): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { focusMoved(it) }
            .build()
        return am.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    // THE SYSTEM TAKES THE SOUND AND GIVES IT BACK (iOS da6d7683, the author's word 05.11: in the car the music dropped). A track the
    // system took for a while plays on from its place when the sound comes back; never one the person paused, never under a call.
    // A loss for good (another player took over) stays a pause. A navigator's word only lowers the track: the platform does that itself.
    private var takenBySystem = false
    private var endedInPause = false
    private fun focusMoved(kind: Int) {
        when (kind) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val was = playing
                android.util.Log.i("Montana", "music_interrupt began paused=" + (if (was) 0 else 1))
                if (was) pause()
                takenBySystem = was && kind == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && !Calls.busy()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val again = takenBySystem && !playing && !Calls.busy()
                android.util.Log.i("Montana", "music_interrupt ended resume=1 taken=" + (if (takenBySystem) 1 else 0) + " plays=" + (if (again) 1 else 0))
                takenBySystem = false
                if (again) resume()
            }
        }
    }
    private fun dropFocus() {
        val f = focus ?: return; focus = null
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f)
    }

    /** Where the queue plays from, as its page heads it (iOS MTQueuePage.from): the one chat's name, the walls, the folders. */
    var from = ""; private set

    fun play(c: Context, list: List<Track>, i: Int, from: String? = null) {
        if (CallSound.refused("music")) return   // the call holds the sound (iOS MontanaMedia 372)
        YouTube.rest()   // one sound: YouTube rests where it stands (iOS MontanaMedia 381)
        val a = c.applicationContext
        app = a
        // A TRACK OF A FOLDER THAT DID NOT OPEN IS SAID, NOT SWALLOWED (iOS MontanaMedia at 6e2b29ca, the author's word 17206)
        if (list.getOrNull(i)?.let { MusicFolders.closed(it, MusicFolders.held(a)) } == true) {
            android.util.Log.i("Montana", "play_refused voice=0 lent=1 closed=1")
            refused?.invoke(R.string.track_folder_closed)
            return
        }
        from?.let { this.from = it }
        // THE TRACK IN HAND KEEPS ITS MOMENT BEFORE THE NEXT ONE TAKES THE PLAYER (iOS VoicePlayer.toggle:386 at 2155,
        // MTPlayPlaces.keep, the author's word 26.09).
        mp?.takeIf { prepared }?.let { p -> current?.let { t -> PlayPlaces.keep(t.file, p.currentPosition / 1000.0, p.duration / 1000.0, false) } }
        queue = list; index = i
        takenBySystem = false; endedInPause = false
        release()
        val t = current ?: return
        takeFocus(a)
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            // the processor stays awake while it plays: the screen goes dark, the music goes on
            setWakeMode(a, PowerManager.PARTIAL_WAKE_LOCK)
            // A LONG LISTEN GOES ON WHERE IT STOPPED (iOS VoicePlayer.toggle:411-414 at 2155, MTPlayPlaces.at, 26.09).
            setOnPreparedListener { if (mp !== it) return@setOnPreparedListener; prepared = true; PlayPlaces.at(t.file, it.duration / 1000.0, false)?.let { at -> runCatching { it.seekTo((at * 1000).toInt()) } }; it.start(); playing = true; tick(); changed() }
            setOnCompletionListener {
                if (mp !== it) return@setOnCompletionListener
                android.util.Log.i("Montana", "music_end i=" + (index + 1) + " " + clock(it))   // iOS finished(), 545acb26: an end told from a stop
                // A PAUSE IN THE TRACK'S LAST MOMENT STAYS A PAUSE: the platform still reports the end, and the next track started over
                // the very sound that took ours (seen on A1 under a ringing timer). The next one waits for the resume.
                if (!playing) { endedInPause = true; return@setOnCompletionListener }
                finished(a)
            }
            setOnErrorListener { p, what, extra ->
                if (mp !== p) return@setOnErrorListener true
                // A SOUND THAT BROKE IS NOT AN END (iOS 59793b5f): the platform gives one word for both, so both lines are written
                android.util.Log.w("Montana", "sound_end broken " + clock(p))
                android.util.Log.w("Montana", "sound_decode error=" + what + " extra=" + extra + " " + clock(p))
                playing = false; changed(); refused?.invoke(R.string.track_refused); true
            }
            try { setDataSource(a, t.uri); prepareAsync() } catch (_: Exception) { refused?.invoke(R.string.track_refused) }
        }
        changed()
    }

    fun toggle() { if (playing) pause() else resume() }
    fun pause() {
        takenBySystem = false
        // iOS VoicePlayer.pause:444 at 2155, MTPlayPlaces.keep, 26.09
        mp?.takeIf { prepared }?.let { p -> current?.let { t -> PlayPlaces.keep(t.file, p.currentPosition / 1000.0, p.duration / 1000.0, false) } }
        mp?.takeIf { prepared }?.pause(); playing = false; changed()
    }
    fun resume() {
        val p = mp?.takeIf { prepared } ?: return
        // A RESUME THAT WOULD NOT PLAY IS SAID TOO (iOS fa901ee6)
        if (CallSound.refused("music")) { android.util.Log.i("Montana", "play_refused resume voice=0 ready=1 call=1"); return }   // iOS MontanaMedia 482
        YouTube.rest()   // one sound (iOS MontanaMedia 500)
        if (app?.let { takeFocus(it) } == false) { android.util.Log.i("Montana", "play_refused resume voice=0 ready=0 call=0"); return }
        takenBySystem = false
        if (endedInPause) {
            endedInPause = false
            val a = app; val nx = a?.let { sounding(it, index + 1, 1) }
            if (a != null && nx != null) { play(a, queue, nx); return }
        }
        p.start(); playing = true; tick(); changed()
    }
    fun seek(f: Double) { val p = mp?.takeIf { prepared } ?: return; p.seekTo((f * p.duration).toInt()); changed() }
    /** Forward turns the page even on the last track — the first follows; under the shuffle any other track (iOS next). */
    fun next() { val a = app ?: return; if (queue.isEmpty()) return; play(a, queue, nextIndex(a) ?: 0) }

    /** THE REPEAT'S ROUND (iOS VoicePlayer.turnRepeat, MontanaMedia.swift:315-324 at 2310, the author's word 01.10 00:42: «the
     *  repeat repeats the chosen track in a circle, a second tap repeats the playlist, a third takes it all off»). */
    var repeatOn = false; private set
    var repeatAll = false; private set
    fun turnRepeat() {
        if (repeatOn) { repeatOn = false; repeatAll = true } else if (repeatAll) repeatAll = false else repeatOn = true
        android.util.Log.d("Montana", "music repeat " + if (repeatOn) "track" else if (repeatAll) "playlist" else "off")
        said()
    }
    var shuffleOn = false
        set(v) { field = v; android.util.Log.d("Montana", "music shuffle=" + if (v) 1 else 0); said() }
    /** The next track, past the tracks of a folder that did not open (iOS nextIndex at 6e2b29ca, the author's word 17210). */
    private fun nextIndex(a: Context): Int? {
        if (queue.isEmpty()) return null
        if (shuffleOn) { val held = MusicFolders.held(a); return queue.indices.filter { it != index && !MusicFolders.closed(queue[it], held) }.randomOrNull() }
        return sounding(a, index + 1, 1)
    }
    /** The track ran to its end (iOS VoicePlayer.finished, MontanaMedia.swift:627-632 at 2310): the track again, else the next,
     *  else the playlist from its first track again, else it rests at its start. */
    private fun finished(a: Context) {
        if (repeatOn) {
            // THE TRACK AGAIN PLAYS AGAIN (iOS start(index:) 686-696): the ended track is started over, never left waiting
            mp?.takeIf { prepared }?.let { it.seekTo(0); it.start(); playing = true; tick() }
            android.util.Log.d("Montana", "music_again i=${index + 1} repeat=track")
            changed(); return
        }
        nextIndex(a)?.let { play(a, queue, it); return }
        if (repeatAll && queue.isNotEmpty()) { play(a, queue, 0); return }
        playing = false; seek(0.0); changed()
    }
    /** A track leaves the queue (iOS VoicePlayer.dropFromQueue, MontanaMedia.swift:706-717 at 2311): the ones after it step up; the
     *  one that plays leaving, the turn goes on from the place it stood, and an empty queue stops. */
    fun dropFromQueue(file: String) {
        val i = queue.indexOfFirst { it.file == file }.takeIf { it != -1 } ?: return
        val was = i == index
        queue = queue.toMutableList().apply { removeAt(i) }
        android.util.Log.d("Montana", "music_queue out i=${i + 1} n=${queue.size} playing=${if (was) 1 else 0}")
        if (i < index) { index -= 1; said(); return }
        if (!was) { said(); return }
        if (queue.isEmpty()) { stop(); return }
        index = i - 1   // the turn goes on from the place the track stood
        next()
    }
    /** A track moved by hand (iOS moveInQueue 722-728): the one that plays keeps playing, its place followed by its file. */
    fun moveInQueue(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val playing = current?.file
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        playing?.let { f -> index = queue.indexOfFirst { it.file == f } }
        android.util.Log.d("Montana", "music_queue moved n=${queue.size} playing_at=${index + 1}")
        said()
    }
    /** THE QUEUE TURNED OVER (iOS reverseQueue 733-740, the author's word 18.10.2026 10:3x UTC): a second turn gives the order back. */
    fun reverseQueue() {
        if (queue.size < 2) return
        val playing = current?.file
        queue = queue.reversed()
        playing?.let { f -> index = queue.indexOfFirst { it.file == f } }
        android.util.Log.d("Montana", "music_queue reversed n=${queue.size} playing_at=${index + 1}")
        said()
    }
    /** The playing track's picture landed (TrackArt): the plate and the lock screen take it at once. */
    fun landed() = changed()
    /** Back: past three seconds the track starts over, before them the one above plays (iOS prev). */
    fun prev() {
        val a = app ?: return
        val back = if (positionMs > 3000 || index <= 0) null else sounding(a, index - 1, -1)
        if (back == null) seek(0.0) else play(a, queue, back)
    }
    /** The bar's cross: the track stops and the bar leaves (iOS MontanaPlayerBar close). */
    fun stop() {
        // iOS VoicePlayer.release:526 at 2155, MTPlayPlaces.keep, 26.09
        mp?.takeIf { prepared }?.let { p -> current?.let { t -> PlayPlaces.keep(t.file, p.currentPosition / 1000.0, p.duration / 1000.0, false) } }
        takenBySystem = false; endedInPause = false
        release(); dropFocus(); queue = emptyList(); index = -1; changed()
    }

    val fraction: Double get() = mp?.takeIf { prepared && it.duration > 0 }?.let { it.currentPosition.toDouble() / it.duration } ?: 0.0
    val duration: Double get() = mp?.takeIf { prepared }?.duration?.div(1000.0) ?: (current?.length ?: 0.0)
    val positionMs: Long get() = mp?.takeIf { prepared }?.currentPosition?.toLong() ?: 0L

    /**
     * THE MUSIC'S LEVEL, once a frame for the cover's waves (iOS VoicePlayer.level, MTFileSound.level, MontanaMedia.swift:349, 926 at
     * 2311): the platform's own meter on the player's sound (Visualizer in its peak-and-RMS mode), the RMS in decibels laid on 0…1
     * over the last forty (iOS MTSoundEar.unit); silence while it rests. The meter stands only while the waves ask for it. Refused
     * (the microphone's leave not given: the platform guards every meter with it), a steady middle stands in, so the long quiet of
     * beats still lets a wave go every two seconds.
     */
    var metering = false
        set(v) { field = v; if (!v) dropMeter() }
    private var meter: android.media.audiofx.Visualizer? = null
    private var meterOf = 0
    private val heard = android.media.audiofx.Visualizer.MeasurementPeakRms()
    fun level(): Float {
        val p = mp?.takeIf { prepared && playing && metering } ?: return 0f
        if (meterOf != p.audioSessionId) {
            dropMeter()
            meterOf = p.audioSessionId
            meter = runCatching {
                android.media.audiofx.Visualizer(p.audioSessionId).apply {
                    measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS
                    captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                    enabled = true
                }
            }.getOrNull()
            meter?.let { wave = ByteArray(it.captureSize) }
            android.util.Log.d("Montana", "cover_waves meter=" + if (meter != null) 1 else 0)
        }
        val m = meter ?: return 0.3f
        // THE PEAK-AND-RMS METER IS DUMB ON A1 (Pixel 9 Pro XL, build 901: a 120 bpm kick heard at volume 4 and 12 read 0.00 the
        // whole time, the permission given and the meter standing): the RMS is taken from the meter's own waveform as well, the
        // louder of the two stands. Both laid on 0…1 over the last forty decibels (iOS MTSoundEar.unit).
        val status = runCatching { m.getMeasurementPeakRms(heard) }.getOrDefault(-1)
        val byMeter = if (status == android.media.audiofx.Visualizer.SUCCESS) (heard.mRms / 100f + 40f) / 40f else 0f
        val got = runCatching { m.getWaveForm(wave) }.getOrDefault(-1)
        var byWave = 0f
        if (got == android.media.audiofx.Visualizer.SUCCESS) {
            var sum = 0.0
            for (b in wave) { val x = ((b.toInt() and 0xFF) - 128) / 128.0; sum += x * x }
            val rms = kotlin.math.sqrt(sum / wave.size)
            if (rms > 1e-5) byWave = ((20 * kotlin.math.log10(rms)).toFloat() + 40f) / 40f
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - meterSaid > 2000) {
            meterSaid = now
            android.util.Log.d("Montana", "cover_waves meter status=$status rms_mb=${heard.mRms} wave=$got by_wave=" + "%.2f".format(byWave))
        }
        return kotlin.math.max(byMeter, byWave).coerceIn(0f, 1f)
    }
    private var wave = ByteArray(1024)
    private var meterSaid = 0L
    private fun dropMeter() { meter?.let { runCatching { it.enabled = false; it.release() } }; meter = null; meterOf = 0 }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        dropMeter()
        // a player still waiting on a track's piece (Streams) lets go off the main thread: its read may stand on the network a while
        mp?.let { old -> Thread { old.release() }.start() }
        mp = null; prepared = false; playing = false
    }
    private fun tick() {
        main.removeCallbacksAndMessages(null)
        if (!playing) return
        said()
        main.postDelayed({ tick() }, 500)
    }
}

/**
 * WHERE A LONG LISTEN STOPPED (iOS MTPlayPlaces, MontanaMedia.swift:647-666 at 2155, the author's word 26.09: «the mini
 * player must remember the place where it stopped playing and not be reset by recording or listening to a voice or a
 * video message»): a track of ten minutes and more, a voice of five, keeps its moment -- between five seconds in and
 * five before its end -- and the next play of the same file starts there. One key of the preferences, written when a
 * listen stops, never by the clock, bounded to the newest two hundred.
 */
object PlayPlaces {
    private const val KEY = "playPlaces"
    private const val MOST = 200
    private fun long(of: Double, voice: Boolean) = of >= (if (voice) 300.0 else 600.0)
    private fun all(): org.json.JSONObject = runCatching { org.json.JSONObject(Prefs.str(KEY, "{}")) }.getOrDefault(org.json.JSONObject())
    fun keep(file: String, at: Double, of: Double, voice: Boolean) {
        if (file.isEmpty() || !long(of, voice)) return
        val o = all()
        if (at > 5 && at < of - 5) o.put(file, org.json.JSONArray().put(at).put(System.currentTimeMillis() / 1000.0)) else o.remove(file)
        if (o.length() > MOST) {
            val oldest = o.keys().asSequence().sortedBy { o.getJSONArray(it).optDouble(1, 0.0) }.take(o.length() - MOST).toList()
            for (k in oldest) o.remove(k)
        }
        Prefs.setStr(KEY, o.toString())
    }
    fun at(file: String, of: Double, voice: Boolean): Double? {
        if (!long(of, voice)) return null
        val t = all().optJSONArray(file)?.optDouble(0, -1.0) ?: return null
        return t.takeIf { it > 5 && it < of - 5 }
    }
}

/** «m:ss», or «h:mm:ss» past an hour (iOS fmtDuration). */
fun fmtDuration(s: Double): String {
    val t = s.toInt().coerceAtLeast(0)
    return if (t >= 3600) String.format("%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60) else String.format("%d:%02d", t / 60, t % 60)
}

// ─────────────────────────── the one bar (iOS MontanaPlayerBar, MTMiniFace) ───────────────────────────

/**
 * WHAT PLAYS, ONE ANSWER (iOS MontanaPlayerBar.standing, the one gate): an open note, else a voice, else a track — the bar shows
 * the first that stands; every player says its changes here, and every bar redraws from here. The speed is one for voices and
 * notes (iOS voiceRate: 1 → 1.5 → 2, kept across launches).
 */
object Playing {
    enum class Kind { TRACK, VOICE, NOTE }
    val kind: Kind? get() = when {
        NoteOpen.close != null -> Kind.NOTE
        VoicePlayer.playing != null -> Kind.VOICE
        MusicPlayer.current != null -> Kind.TRACK
        else -> null
    }
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    fun changed() { MainThread.post { synchronized(listeners) { listeners.toList() }.forEach { it() } } }
    var rate: Float
        get() = Prefs.str("voiceRate", "1").toFloatOrNull() ?: 1f
        set(v) = Prefs.setStr("voiceRate", v.toString())
    /** One tap around 1 → 1.5 → 2 (iOS onSide), heard at once by the voice and the note. */
    fun nextRate() {
        rate = if (2f <= rate) 1f else if (1.5f <= rate) 2f else 1.5f
        VoicePlayer.applyRate(); NoteOpen.applyRate?.invoke(); changed()
    }
    fun rateWord() = if (rate == 1f) "1×" else if (rate == 2f) "2×" else "1.5×"   // USER-DATA: a number, not a word
    /** A TAPE HOLDS WHAT PLAYS, THE OPEN NOTE CLOSES (iOS MontanaPlayerBar.standDown, MontanaMusicPlayer.swift:1026-1035 at
     *  2155): a voice tape always rests whatever sounds; a note's camera keeps a track that was already playing
     *  (`keepingMusic`, read by the caller before anything stands down) and rests a voice message regardless -- the
     *  chat's own stepAside (Conversation.kt:2424-2426, 2498) already lets a rested track play on once the tape ends. */
    fun standDown(keepingMusic: Boolean = false) {
        NoteOpen.close?.invoke()
        VoicePlayer.pause()
        if (!keepingMusic) MusicPlayer.pause()
    }
}

/**
 * THE ONE MINI PLAYER (iOS MTMiniFace, the author's word 22.09): one face for the track, the voice and the video note — the play on
 * its round glass plate, the long glass plate that is the scrubber with the name running inside it, at its end the track's timer
 * (its two faces) or, for a voice and a note, the speed; for a track the fifteen-second skips beside it; and the cross. On a row of
 * the music list it stands still. The playing track wears the platform's thin blue ring.
 */
class MiniFace(ctx: Context, private val bar: Boolean) : LinearLayout(ctx) {
    private val h = dp(44)
    val play = FrameLayout(ctx)
    private val playGlyph = ctx.icon(R.drawable.ic_play_fill, Color.rgb(204, 204, 204))
    private val plate = FrameLayout(ctx)
    private val fill = View(ctx).apply { setBackgroundColor(Color.argb(41, 255, 255, 255)) }
    // THE NAME RUNS INSIDE THE PLATE (iOS MTPlayerPlate: MTMarquee .subheadline centred; a still row of the list keeps it still,
    // semibold and cut); the copy word stands in its room for a moment (MTCopiedWord)
    private val title = Marquee(ctx, 15f, centred = true).apply { runs = bar }
    private val copiedWord = ctx.text("", 15f, center = true).apply { typeface = semibold(); visibility = View.GONE }
    // the position, the remaining time or the speed: subheadline semibold, digits even, the glyphs' colour, centred in 64
    private val side = ctx.text("", 15f, Color.rgb(204, 204, 204), center = true).apply { typeface = semibold(); fontFeatureSettings = "tnum"; gravity = Gravity.CENTER }
    private var remaining = false
    private var scrub: Double? = null
    private var shownF = 0.0   // the fill's edge as drawn: a swipe moves it by the finger's travel (iOS grabDX)
    private var grabDx = 0f
    private var prevBtn: View? = null
    private var nextBtn: View? = null
    private var closeBtn: View? = null
    var onSeek: (Double) -> Unit = {}
    // A HOLD ON THE MINI COPIES ITS NAME AT ONCE (iOS MTNameCopy, MontanaMusicPlayer.swift:1141-1159 and 1267-1309 at 2155, the
    // author's word 29.09 ~23:10: «at a long press, at the speed our menu opens in the chat, the track's name is copied»): the
    // bar's plate alone -- a still row of the list answers no touch of its own (still, below).
    private var displayTitle = ""
    private var copiedUntil = 0L
    private var longPressed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { longPressed = true; copyTitle() }
    private companion object { const val HOLD_MS = 220L }   // iOS montanaLongPress, MontanaNetFrames.swift:9 at 2155
    /** THE MINI UNFOLDED WHERE IT STANDS (iOS MontanaPlayerBar.unfolded, MTPlayerHead): the bar's alone, a track's alone. */
    val big: PlayerHead? = if (bar) PlayerHead(ctx) else null

    /**
     * A TAP ON THE NAME'S ROOM: A VOICE OR A NOTE GOES TO ITS BUBBLE (iOS MTMiniFace.open → onGoTo, MontanaMusicPlayer.swift:
     * 1131-1137 at 2155, the author's word 15.09; atom 83753ae0754a): the open chat scrolls to the letter that carries the
     * file and lights it, another chat opens at it (ShowLetter, the one road of «Show in chat»).
     */
    private fun goToLetter() {
        val note = when (Playing.kind) { Playing.Kind.NOTE -> true; Playing.Kind.VOICE -> false; else -> return }
        val mid = if (note) NoteOpen.mid else ""
        val file = if (note) NoteOpen.file.ifEmpty { null } else VoicePlayer.playing
        val act = context as? MainActivity ?: return
        val what = if (note) "note" else "voice"
        val (ref, letter) = ShowLetter.find(mid, file) ?: run { android.util.Log.d("Montana", "bar_goto kind=$what found=0"); return }
        android.util.Log.d("Montana", "bar_goto kind=$what here=" + (if (Book.openChat == ref) 1 else 0))
        if (note && Book.openChat != ref) NoteOpen.close?.invoke()   // the note's stage stands over its own chat alone
        ShowLetter.go(act, ref, letter)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
        play.background = ctx.glassPlate(oval = true)
        play.addView(playGlyph, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        addView(play, LayoutParams(h, h))
        // THE TRACK BEFORE AND THE TRACK AFTER (iOS MTMiniFace.onPrev/onNext, MontanaMusicPlayer.swift:1206-1238 at 2155, the
        // author's word 29.09 on build 2000: «the buttons should have switched to the next track, not seek»): the queue's own
        // turn, as the lock screen's buttons (MusicPlayer.prev/next) -- a track's bar alone, never a voice's or a note's.
        if (bar) prevBtn = square(R.drawable.ic_skip_prev) { MusicPlayer.prev() }.also { addView(it, LayoutParams(h, h).apply { marginStart = dp(10) }) }
        plate.background = ctx.glassPlate()
        plate.clipToOutline = true
        plate.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
        }
        plate.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        plate.addView(title, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL).apply { marginStart = dp(12); marginEnd = dp(64 + 2) })
        plate.addView(copiedWord, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL).apply { marginStart = dp(12); marginEnd = dp(64 + 2) })
        plate.addView(side, FrameLayout.LayoutParams(dp(64), MATCH, Gravity.END))
        addView(plate, LayoutParams(0, h, 1f).apply { marginStart = dp(10) })
        if (bar) {
            nextBtn = square(R.drawable.ic_skip_next) { MusicPlayer.next() }.also { addView(it, LayoutParams(h, h).apply { marginStart = dp(10) }) }
            closeBtn = square(R.drawable.ic_close) {
                when (Playing.kind) { Playing.Kind.NOTE -> NoteOpen.close?.invoke(); Playing.Kind.VOICE -> VoicePlayer.stop(); else -> MusicPlayer.stop() }
            }.also { addView(it, LayoutParams(h, h).apply { marginStart = dp(10) }) }
            play.pressable {
                when (Playing.kind) {
                    Playing.Kind.NOTE -> NoteOpen.toggle?.invoke()
                    Playing.Kind.VOICE -> if (VoicePlayer.paused) VoicePlayer.resume() else VoicePlayer.pause()
                    else -> MusicPlayer.toggle()
                }
            }
            onSeek = { f -> when (Playing.kind) { Playing.Kind.NOTE -> NoteOpen.seek?.invoke(f); Playing.Kind.VOICE -> VoicePlayer.seekTo(f); else -> MusicPlayer.seek(f) } }
            // THE PLATE IS THE SCRUBBER: the finger holds the value while it moves, the player is asked once, at the release; a tap
            // on the side room is the side's — the timer's two faces for a track, the speed for a voice and a note (iOS onSide)
            var downX = 0f; var downY = 0f
            // A FINGER THAT NEVER MOVED MAY HOLD, AND ASKS FOR NO SEEK (iOS MontanaSliderView.slop/moved, MontanaMusicPlayer.swift:
            // 1267-1309 at 2155): the first eight points are the tap's room -- standing in it, the plate's hold copies the name
            // (copyTitle) instead of the platform's old context menu, which 2155 no longer carries; leaving it is a swipe.
            plate.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true); scrub = null; longPressed = false
                        downX = e.x; downY = e.y
                        mainHandler.postDelayed(longPressRunnable, HOLD_MS)   // the chat bubble's own threshold (iOS montanaLongPress)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (scrub == null && kotlin.math.abs(e.x - downX) < dp(8) && kotlin.math.abs(e.y - downY) < dp(8)) true
                        else {
                            // THE PLATE IS TAKEN ANYWHERE (iOS MontanaSliderView grab): leaving the tap's room, the fill's edge keeps
                            // its distance from the finger and moves by the finger's own travel -- nothing jumps under it
                            mainHandler.removeCallbacks(longPressRunnable)
                            if (scrub == null) grabDx = e.x - v.width * shownF.toFloat()
                            val g = ((e.x - grabDx) / v.width).toDouble().coerceIn(0.0, 1.0)
                            scrub = g; show(g); true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        val held = scrub; scrub = null
                        if (longPressed) longPressed = false
                        else if (held != null) onSeek(held)
                        else if (e.x > v.width - dp(64)) { if (Playing.kind == Playing.Kind.TRACK) { remaining = !remaining; live() } else Playing.nextRate() }
                        // a tap on the name's room: the track unfolds the mini where it stands (iOS MontanaPlayerBar.open, 1307-1313)
                        else if (Playing.kind == Playing.Kind.TRACK) PlayerUnfold.set(true)
                        else goToLetter()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { mainHandler.removeCallbacks(longPressRunnable); scrub = null; true }
                    else -> false
                }
            }
        }
        big?.let { it.visibility = View.GONE; addView(it, LayoutParams(MATCH, WRAP)) }
    }

    /** The title shown now, unless the copied word still stands in its room (iOS MTCopiedWord, 2 s, MTNameCopy.shown). */
    private fun showTitle() {
        if (System.currentTimeMillis() < copiedUntil) return
        title.text = displayTitle
    }
    /** THE HOLD COPIES THE NAME (iOS MTNameCopy.copy, MontanaMusicPlayer.swift:1146-1158 at 2155): the name to the clipboard, the
     * platform's success haptic, and for two seconds the name's room says the catalogue's «Copied» after the platform's check, in
     * the name's own colour (MTCopiedWord, 1163-1167). */
    private fun copyTitle() {
        val name = displayTitle
        if (name.isEmpty()) return
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("", name))
        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.LONG_PRESS)
        val check = context.getDrawable(R.drawable.ic_check)?.mutate()?.apply { setTint(Color.WHITE); val k = copiedWord.textSize.toInt(); setBounds(0, 0, k, k) }
        copiedWord.setCompoundDrawablesRelative(check, null, null, null); copiedWord.compoundDrawablePadding = dp(4)
        copiedWord.text = context.getString(R.string.copied)
        copiedWord.visibility = View.VISIBLE; title.visibility = View.INVISIBLE
        copiedUntil = System.currentTimeMillis() + 2000
        mainHandler.postDelayed({ if (copiedUntil <= System.currentTimeMillis()) { copiedWord.visibility = View.GONE; title.visibility = View.VISIBLE; showTitle() } }, 2000)
    }

    private fun square(res: Int, onTap: () -> Unit) = FrameLayout(context).apply {
        background = context.glassPlate(oval = true)
        addView(context.icon(res, Color.rgb(204, 204, 204)), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        pressable(onTap)
    }

    /** A still row of the list: the name, the length, the ring when it is the one that plays. */
    fun still(t: Track, isCurrent: Boolean, playingNow: Boolean) {
        displayTitle = t.title; showTitle()   // USER-DATA: the track's own name
        side.text = if (t.length > 0) fmtDuration(t.length) else ""   // USER-DATA: a length, digits
        playGlyph.setImageResource(if (playingNow) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        ring(isCurrent)
    }

    /** The bar: whatever plays (Playing.kind), its name and its place on the plate. */
    fun live() {
        val kind = Playing.kind ?: return
        val track = kind == Playing.Kind.TRACK
        big?.let { head ->
            // the unfolded plate stands in the bar's own place: the mini's own pieces step aside while it stands
            val open = track && PlayerUnfold.open
            if (open != (head.visibility == View.VISIBLE)) {
                for (i in 0 until childCount) getChildAt(i).visibility = if ((getChildAt(i) === head) == open) View.VISIBLE else View.GONE
                if (open) setPadding(0, 0, 0, 0) else setPadding(dp(12), dp(4), dp(12), dp(4))
            }
            if (open) { head.update(); return }
        }
        prevBtn?.visibility = if (track) View.VISIBLE else View.GONE
        nextBtn?.visibility = if (track) View.VISIBLE else View.GONE
        val sounding = when (kind) {
            Playing.Kind.NOTE -> NoteOpen.sounding?.invoke() == true
            Playing.Kind.VOICE -> !VoicePlayer.paused
            Playing.Kind.TRACK -> MusicPlayer.playing
        }
        displayTitle = when (kind) {   // USER-DATA: the track's own name, or the speaker's name as the person sees it
            Playing.Kind.NOTE -> NoteOpen.title
            Playing.Kind.VOICE -> VoicePlayer.sender
            Playing.Kind.TRACK -> MusicPlayer.current?.title.orEmpty()
        }
        showTitle()
        playGlyph.setImageResource(if (sounding) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        // THE THIN BLUE RING ON EVERY PAGE (iOS MTMiniFace.playingRing, the author's word 29.09): the bar wears it fixed,
        // wherever it floats -- never conditioned, as the still row's own ring is, on «is this the one that plays».
        ring(true)
        if (scrub == null) show(when (kind) {
            Playing.Kind.NOTE -> NoteOpen.fraction?.invoke() ?: 0.0
            Playing.Kind.VOICE -> VoicePlayer.fraction
            Playing.Kind.TRACK -> MusicPlayer.fraction
        })
    }

    private fun show(f: Double) {
        shownF = f
        if (bar && Playing.kind != Playing.Kind.TRACK) side.text = Playing.rateWord()
        else {
            val d = MusicPlayer.duration
            side.text = if (d <= 0) "" else if (remaining) "-" + fmtDuration(d - f * d) else fmtDuration(f * d)   // USER-DATA: a time
        }
        val lp = fill.layoutParams as FrameLayout.LayoutParams
        val w = (plate.width * f).toInt()
        if (lp.width != w) { lp.width = w; fill.layoutParams = lp }
    }

    private fun ring(on: Boolean) {
        val blue = if (on) MT.blue else null
        fun oval() = blue?.let { GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), it) } }
        play.foreground = oval()
        plate.foreground = blue?.let { GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setStroke(dp(1), it) } }
        // EVERY BUTTON WEARS THE RING (iOS MTMiniFace.trackButton/onClose overlay, MontanaMusicPlayer.swift:1213-1244 at 2155,
        // the author's word 29.09 on build 2000: «all the buttons in the system's outline, as the play and the central one»).
        prevBtn?.foreground = oval()
        nextBtn?.foreground = oval()
        closeBtn?.foreground = oval()
    }
}

/** THE FLOATING PLAYER at the foot of the chats, the feed and the music (iOS MontanaPlayerBar): shown while anything plays. */
fun miniBar(act: MainActivity): MiniFace = MiniFace(act, bar = true).apply { visibility = View.GONE }

/** The one bar where a page stands on its own (the chat above its field, the open note): it follows Playing while attached. */
fun liveBar(act: MainActivity): MiniFace = miniBar(act).apply {
    val update: () -> Unit = { val on = Playing.kind != null; visibility = if (on) View.VISIBLE else View.GONE; if (on) live() }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { Playing.listen(update); update() }
        override fun onViewDetachedFromWindow(v: View) { Playing.unlisten(update) }
    })
}

/** WHETHER THE MINI STANDS UNFOLDED (iOS MTPlayerUnfold, the author's word 01.10 00:13): one fact for the bar of every page, so
 *  the bar on the next page stands as the person left it. */
object PlayerUnfold {
    var open = false; private set
    fun set(on: Boolean) {
        if (on == open) return
        open = on
        android.util.Log.d("Montana", "music mini " + if (on) "unfolds" else "folds")
        Playing.changed()
    }
}

/**
 * THE MINI UNFOLDED (iOS MTPlayerHead, MontanaMusicPlayer.swift:439-678 at 2310; the author's word 01.10 00:13: «a tap on the mini
 * player unfolds it as on the screenshot, the same buttons one to one, on any page where the mini player stands; a second tap on the
 * name folds it back»): the lock screen's plate -- the cover, the name running over the artist its tags name, the sound's wave while
 * it plays; the time's line with the elapsed and the remaining at its ends; the track before, the play and the track after, the
 * playlist's place at the left and the sound's way at the right -- then the system's volume as a thin line, and under it the round
 * glass buttons: the repeat, the shuffle, the search, the share. A tap on the cover raises it over the plate in a third of the screen
 * (MTCoverStage); a tap there folds it back. The whole frame is the player's: a tap in the air between the plates falls through to
 * no row under it.
 */
class PlayerHead(private val ctx: Context) : LinearLayout(ctx) {
    var onSearch: () -> Unit = {}
    var onPlace: () -> Unit = {}
    /** The place mark opens the queue's own page (iOS placeMark → MTQueuePage.present). */
    var onQueue: () -> Unit = {}
    private val stage = FrameLayout(ctx).apply { visibility = View.GONE }
    private val stageArt = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; clipToOutline = true }
    /** The icon on the stage sends out its waves (iOS MTCoverWaves): only while it stands there for want of a cover. */
    private val waves = CoverWaves(stageArt)
    private val cover = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private var coverCorner = 0f
    private val title = Marquee(ctx, 17f, centred = false)   // iOS MTMarquee .body: one pass, then a minute's rest
    private val byline = ctx.text("", 17f, MT.gray).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val wave = ctx.icon(R.drawable.ic_waveform, MT.gray, 17)
    private var waving: android.animation.ObjectAnimator? = null
    // iOS .footnote.weight(.medium).monospacedDigit()
    private val elapsed = ctx.text("", 13f, MT.gray).apply { fontFeatureSettings = "tnum"; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val remain = ctx.text("", 13f, MT.gray).apply { fontFeatureSettings = "tnum"; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val line = FrameLayout(ctx)
    private val lineFill = View(ctx).apply { background = ctx.rounded(Color.argb(184, 255, 255, 255), 4) }
    private var scrub: Double? = null
    private val playGlyph = ctx.icon(R.drawable.ic_play_fill, Color.WHITE, 36)
    private val placeNumber = ctx.text("", 13f, MT.gray, bold = true).apply { fontFeatureSettings = "tnum" }
    private val way = ctx.icon(R.drawable.ic_route_phone, MT.blue, 19)
    private val volume = VolumeLine(ctx)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val repeatGlyph = ctx.icon(R.drawable.ic_repeat, Color.WHITE, 16)
    private val shuffleGlyph = ctx.icon(R.drawable.ic_shuffle, Color.WHITE, 16)
    private var shownKey = ""
    private var shownArt: android.graphics.Bitmap? = null
    private var copiedUntil = 0L
    private var saidUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private companion object { const val EDGE = 15; const val GAP = 8 }   // iOS MTPlayerHead.edge/gap, off the author's screenshots

    init {
        orientation = VERTICAL
        setPadding(dp(EDGE), dp(GAP), dp(EDGE), dp(GAP))
        isClickable = true   // the air between the plates is the player's (iOS: player air tap -- kept off the page)
        stage.addView(stageArt, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        stage.pressable { toggleCover() }
        stage.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (stage.visibility == View.VISIBLE && shownArt == null) waves.start() }
            override fun onViewDetachedFromWindow(v: View) { waves.stop() }
        })
        addView(stage, LayoutParams(MATCH, ctx.resources.displayMetrics.heightPixels / 3).apply { bottomMargin = dp(GAP * 2) })
        addView(plate(), LayoutParams(MATCH, WRAP))
        addView(volumeLine(), LayoutParams(MATCH, dp(44)).apply { topMargin = dp(GAP) })
        addView(buttons(), LayoutParams(MATCH, dp(44)).apply { topMargin = dp(GAP) })
    }

    /** The lock screen's one plate (iOS nowPlaying 509-548), glass of corner 26. */
    private fun plate() = LinearLayout(ctx).apply {
        orientation = VERTICAL
        background = ctx.glassPlate().apply { cornerRadius = dp(26).toFloat() }
        setPadding(dp(14), dp(14), dp(14), dp(8))
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cover.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, coverCorner)
        }
        cover.pressable { toggleCover() }
        top.addView(cover, LayoutParams(dp(57), dp(57)))
        val names = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(title, LayoutParams(MATCH, dp(22)))
            addView(byline, LayoutParams(MATCH, WRAP).apply { topMargin = dp(1) })
            // the name folds the plate back into the mini; a hold copies it (iOS onTitle, MTNameCopy at the bubble's threshold)
            setOnClickListener { PlayerUnfold.set(false) }
            setOnLongClickListener { copyTitle(); true }
        }
        top.addView(names, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
        top.addView(wave, LayoutParams(dp(17), dp(17)))
        addView(top, LayoutParams(MATCH, WRAP))
        addView(clockLine(), LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        addView(controls(), LayoutParams(MATCH, dp(60)).apply { topMargin = dp(4) })
    }

    /** The time's line (iOS MTPlayerClockLine): the elapsed, the line of 7 the finger holds while it moves, the remaining. */
    private fun clockLine() = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(elapsed, LayoutParams(WRAP, WRAP))
        line.addView(View(ctx).apply { background = ctx.rounded(Color.argb(51, 255, 255, 255), 4) }, FrameLayout.LayoutParams(MATCH, dp(7), Gravity.CENTER_VERTICAL))
        line.addView(lineFill, FrameLayout.LayoutParams(0, dp(7), Gravity.CENTER_VERTICAL))
        // THE LINE IS TAKEN ANYWHERE (iOS MontanaScrubber grab, MontanaSliderView 234-366): the first eight points are a tap's room
        // and a lift inside it seeks nothing; leaving it, the fill's edge keeps its distance from the finger and moves by the
        // finger's own travel, and the player is asked once, at the lift
        var x0 = 0f; var grabDx = 0f
        line.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); x0 = e.x; scrub = null; true }
                MotionEvent.ACTION_MOVE -> {
                    if (scrub == null && kotlin.math.abs(e.x - x0) <= dp(8)) return@setOnTouchListener true
                    if (scrub == null) grabDx = e.x - v.width * MusicPlayer.fraction.toFloat()
                    val g = ((e.x - grabDx) / v.width).toDouble().coerceIn(0.0, 1.0)
                    scrub = g; clock(g); true
                }
                MotionEvent.ACTION_UP -> { val held = scrub; scrub = null; if (held != null) MusicPlayer.seek(held); true }
                MotionEvent.ACTION_CANCEL -> { scrub = null; clock(MusicPlayer.fraction); true }
                else -> false
            }
        }
        addView(line, LayoutParams(0, dp(44), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
        addView(remain, LayoutParams(WRAP, WRAP))
    }

    /** The lock screen's three at the plate's middle, the playlist's place at its left, the sound's way at its right (iOS controls). */
    private fun controls() = FrameLayout(ctx).apply {
        val three = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            fun control(v: View, side: Int, act: () -> Unit) = FrameLayout(ctx).apply {
                addView(v, FrameLayout.LayoutParams(dp(side), dp(side), Gravity.CENTER)); pressable(act)
            }
            addView(control(ctx.icon(R.drawable.ic_skip_prev, Color.WHITE), 28) { MusicPlayer.prev() }, LayoutParams(dp(52), dp(52)))
            addView(control(playGlyph, 36) { MusicPlayer.toggle() }, LayoutParams(dp(52), dp(52)).apply { marginStart = dp(30); marginEnd = dp(30) })
            addView(control(ctx.icon(R.drawable.ic_skip_next, Color.WHITE), 28) { MusicPlayer.next() }, LayoutParams(dp(52), dp(52)))
        }
        addView(three, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.CENTER))
        // THE PLAYLIST THAT PLAYS, LEFT OF «BACK» (iOS placeMark 571-590): the glyph and the track's place in the queue
        val place = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            addView(ctx.icon(R.drawable.ic_list_number, MT.gray, 15), LayoutParams(dp(15), dp(15)))
            addView(placeNumber, LayoutParams(WRAP, WRAP).apply { marginStart = dp(3) })
            contentDescription = ctx.getString(R.string.current_playlist)
            minimumWidth = dp(44)
            pressable { onQueue() }
        }
        addView(place, FrameLayout.LayoutParams(WRAP, dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        // THE SOUND'S WAY (iOS MTSoundWayMark): where the sound goes, in the system's blue; a tap opens the system's own output
        // switcher (Android 14: MediaRouter2.showSystemOutputSwitcher -- the platform's picker, as iOS hands AirPlay's)
        val wayRoom = FrameLayout(ctx).apply {
            addView(way, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
            pressable {
                if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { android.media.MediaRouter2.getInstance(ctx).showSystemOutputSwitcher() }
                android.util.Log.d("Montana", "music player way")
            }
        }
        addView(wayRoom, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.END))
    }

    /** THE SOUND'S LINE (iOS MTVolumeLine, the author's word 16.10.2026): the system's own music volume as a thin line between the
     *  quiet and the loud speaker, no plate; set without the system's volume panel, so nothing covers the player. */
    private fun volumeLine() = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        addView(ctx.icon(R.drawable.ic_speaker_fill, MT.gray, 12), LayoutParams(dp(12), dp(12)))
        volume.contentDescription = ctx.getString(R.string.volume)
        addView(volume, LayoutParams(0, dp(44), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
        addView(ctx.icon(R.drawable.ic_speaker_wave3, MT.gray, 12), LayoutParams(dp(12), dp(12)))
    }

    /** THE ROUND BUTTONS UNDER THE PLATE (iOS head 599-627): the repeat and the shuffle lit in the platform's blue while on, the
     *  search, the share -- the same space between every two and at both ends. (iOS's source filter has no other source here:
     *  the Android music is the lent folders alone.) */
    private fun buttons() = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        fun round(glyph: View, act: () -> Unit) = FrameLayout(ctx).apply {
            background = ctx.glassPlate(oval = true)
            addView(glyph, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            pressable(act)
        }
        fun space() = addView(View(ctx), LayoutParams(0, 1, 1f))
        space()
        addView(round(repeatGlyph) {
            MusicPlayer.turnRepeat()
            say(if (MusicPlayer.repeatOn) R.string.repeat_track else if (MusicPlayer.repeatAll) R.string.repeat_playlist else R.string.repeat_off,
                if (MusicPlayer.repeatOn) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        }, LayoutParams(dp(44), dp(44)))
        space()
        addView(round(shuffleGlyph) {
            MusicPlayer.shuffleOn = !MusicPlayer.shuffleOn
            say(if (MusicPlayer.shuffleOn) R.string.shuffle_on else R.string.shuffle_off, R.drawable.ic_shuffle)
        }, LayoutParams(dp(44), dp(44)))
        space()
        addView(round(ctx.icon(R.drawable.ic_search, Color.WHITE, 16)) { onSearch() }, LayoutParams(dp(44), dp(44)))
        space()
        addView(round(ctx.icon(R.drawable.ic_share, Color.WHITE, 16)) { share() }.apply { contentDescription = ctx.getString(R.string.share) }, LayoutParams(dp(44), dp(44)))
        space()
    }

    /** «SHARE» HANDS THE FILE (iOS MontanaPlayerBar.share): the lent track's own document, by the platform's own sheet. */
    private fun share() {
        val t = MusicPlayer.current ?: return
        android.util.Log.d("Montana", "music player share")
        val send = Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(t.uri) ?: "audio/*")
            .putExtra(Intent.EXTRA_STREAM, t.uri).putExtra(Intent.EXTRA_TITLE, t.title)   // USER-DATA: the track's own name
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(t.title, t.uri)
        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
    }

    /** THE STATE SAID UNDER THE NAME (iOS say 628-636): the glyph and the catalogue's word in the platform's blue, gone after two seconds. */
    private fun say(word: Int, glyph: Int) {
        val d = ctx.getDrawable(glyph)?.mutate()?.apply { setTint(MT.blue); val k = dp(15); setBounds(0, 0, k, k) }
        byline.setCompoundDrawablesRelative(d, null, null, null); byline.compoundDrawablePadding = dp(5)
        byline.setTextColor(MT.blue); byline.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        byline.typeface = android.graphics.Typeface.DEFAULT_BOLD
        byline.text = ctx.getString(word); byline.visibility = View.VISIBLE
        saidUntil = System.currentTimeMillis() + 2000
        main.postDelayed({ if (saidUntil <= System.currentTimeMillis()) update() }, 2000)
    }

    /** The hold copies the name (iOS MTNameCopy): the clipboard, the platform's confirm, «Copied» for two seconds. */
    private fun copyTitle() {
        val name = MusicPlayer.current?.title ?: return
        ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("", name))
        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.LONG_PRESS)
        android.util.Log.d("Montana", "music player title copied")
        title.text = ctx.getString(R.string.copied)
        copiedUntil = System.currentTimeMillis() + 2000
        main.postDelayed({ if (copiedUntil <= System.currentTimeMillis()) update() }, 2000)
    }

    /** The cover rises over the plate in a third of the screen, and folds back at a tap (iOS toggleCover, MTCoverStage). */
    private fun toggleCover() {
        val opens = stage.visibility != View.VISIBLE
        android.util.Log.d("Montana", "cover_look " + if (opens) "rises own=" + (if (shownArt == null) 0 else 1) else "folds")
        if (opens) {
            stage.visibility = View.VISIBLE
            stage.pivotX = 0f; stage.pivotY = stage.layoutParams.height.toFloat()
            stage.alpha = 0f; stage.scaleX = 0.15f; stage.scaleY = 0.15f
            stage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            stageShows()
        } else { waves.stop(); stage.animate().alpha(0f).scaleX(0.15f).scaleY(0.15f).setDuration(260).withEndAction { stage.visibility = View.GONE }.start() }
    }
    /** The stage: the track's own cover whole with a corner of 14, else the app's icon in its own shape, a little under half the stage. */
    private fun stageShows() {
        // the stage's shorter side (iOS MTCoverStage: side = min(width, height)); before its first layout, the head's own width
        val h = minOf(stage.layoutParams.height, if (stage.width > 0) stage.width else width - paddingLeft - paddingRight)
        val own = shownArt
        val side = if (own != null) h else (h * 0.45f).toInt()
        stageArt.setImageBitmap(own ?: TrackArt.icon(ctx))
        val r = if (own != null) dp(14).toFloat() else side * TrackArt.ICON_CORNER
        stageArt.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                // the picture's own drawn rect inside the square (FIT_CENTER), rounded
                val d = (v as android.widget.ImageView).drawable ?: return o.setRoundRect(0, 0, v.width, v.height, r)
                val k = minOf(v.width.toFloat() / d.intrinsicWidth, v.height.toFloat() / d.intrinsicHeight)
                val w = (d.intrinsicWidth * k).toInt(); val hh = (d.intrinsicHeight * k).toInt()
                o.setRoundRect((v.width - w) / 2, (v.height - hh) / 2, (v.width + w) / 2, (v.height + hh) / 2, r)
            }
        }
        stageArt.layoutParams = (stageArt.layoutParams as FrameLayout.LayoutParams).apply { width = side; height = side }
        stageArt.invalidateOutline()
        if (own == null) waves.start() else waves.stop()
    }

    private fun clock(f: Double) {
        val d = MusicPlayer.duration
        elapsed.text = fmtDuration(d * f)   // USER-DATA: the elapsed time, digits
        remain.text = "-" + fmtDuration(maxOf(0.0, d - d * f))   // USER-DATA: the remaining time, digits
        val lp = lineFill.layoutParams as FrameLayout.LayoutParams
        val w = (line.width * f).toInt()
        if (lp.width != w) { lp.width = w; lineFill.layoutParams = lp }
    }

    /** The player's word (every change and every tick): the face redrawn from MusicPlayer. */
    fun update() {
        val t = MusicPlayer.current ?: return
        val now = System.currentTimeMillis()
        val key = t.uri.toString()
        val art = TrackArt.of(ctx, t)
        if (key != shownKey || art !== shownArt) {
            shownKey = key; shownArt = art
            cover.setImageBitmap(art ?: TrackArt.icon(ctx))
            coverCorner = if (art != null) dp(10).toFloat() else dp(57) * TrackArt.ICON_CORNER
            cover.invalidateOutline()
            if (stage.visibility == View.VISIBLE) stageShows()
        }
        if (now >= copiedUntil && title.text.toString() != t.title) title.text = t.title   // USER-DATA: the track's own name
        if (now >= saidUntil) {
            byline.setCompoundDrawablesRelative(null, null, null, null)
            byline.setTextColor(MT.gray); byline.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f); byline.typeface = android.graphics.Typeface.DEFAULT
            val by = t.artist.orEmpty()   // USER-DATA: the artist the file's own tags name -- never where the track lies
            if (byline.text.toString() != by) byline.text = by
            byline.visibility = if (by.isEmpty()) View.GONE else View.VISIBLE
        }
        playGlyph.setImageResource(if (MusicPlayer.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        if (MusicPlayer.playing) waves.wake()
        // the sound's wave breathes while it plays (iOS symbolEffect .variableColor.iterative)
        if (MusicPlayer.playing && waving == null) waving = android.animation.ObjectAnimator.ofFloat(wave, View.ALPHA, 1f, 0.35f).apply {
            duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; start()
        } else if (!MusicPlayer.playing && waving != null) { waving?.cancel(); waving = null; wave.alpha = 1f }
        if (scrub == null) clock(MusicPlayer.fraction)
        placeNumber.text = if (MusicPlayer.index >= 0) (MusicPlayer.index + 1).toString() else ""   // USER-DATA: the track's place, digits
        repeatGlyph.setImageResource(if (MusicPlayer.repeatOn) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatGlyph.imageTintList = android.content.res.ColorStateList.valueOf(if (MusicPlayer.repeatOn || MusicPlayer.repeatAll) MT.blue else Color.WHITE)
        shuffleGlyph.imageTintList = android.content.res.ColorStateList.valueOf(if (MusicPlayer.shuffleOn) MT.blue else Color.WHITE)
        volume.follow()
        val out = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET) }
        way.setImageResource(if (out) R.drawable.ic_route_headphones else R.drawable.ic_route_phone)
    }
}

// ─────────────────────────── the music page (iOS MusicTabView) ───────────────────────────

/**
 * THE MUSIC PAGE: every track of the lent folders as a row in the mini's face, newest folder first; the whole row is one
 * target — a still track plays with the list as its queue, the playing one rests or plays on. The plus at the bottom right
 * lends a folder through the system's own picker and turns while the folder just picked is walked.
 */
class MusicPage(private val act: MainActivity) {
    val view: FrameLayout = FrameLayout(act)
    private val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, act.dp(140)) }
    private val empty = act.text(act.getString(R.string.no_music), 17f, MT.gray, center = true)
    private val plus = FrameLayout(act)
    private val spinner = ProgressBar(act).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(204, 204, 204)); visibility = View.GONE }
    private val plusGlyph = act.icon(R.drawable.ic_plus, Color.rgb(204, 204, 204))
    private val faces = HashMap<String, MiniFace>()
    private val scroll = ScrollView(act)
    private var lastRing: String? = null
    private var lastPlaying = false
    /** The word at the page's head (iOS MusicTab.query): the rows are narrowed to it, see shown(). */
    var query = ""
        set(v) { if (v != field) { field = v; fill() } }

    init {
        scroll.addView(rows)
        view.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        view.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        plus.background = act.glassPlate(oval = true)
        plus.addView(plusGlyph, FrameLayout.LayoutParams(act.dp(28), act.dp(28), Gravity.CENTER))
        plus.addView(spinner, FrameLayout.LayoutParams(act.dp(26), act.dp(26), Gravity.CENTER))
        plus.pressable { act.pickFolder { it?.let { tree -> MusicFolders.lend(act, tree) } } }
        view.addView(plus, FrameLayout.LayoutParams(act.dp(56), act.dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, act.dp(16), act.dp(36)) })
        // the platform's bar at the right while the list scrolls, the track's number beside it; the arrow back to the top over
        // the plus (iOS MTChatListFrame numbered, MontanaMusicPlayer.swift:1879 at 2155)
        ListFrame.attach(view, "music", corner = plus, rows = rows)
        MusicFolders.listeners += { if (view.isAttachedToWindow || view.parent != null) fill() }
        MusicPlayer.listeners += { ringChanged() }
        // a track refused while the app is away (the next one, from the shade) is only not played: no window without a page
        MusicPlayer.refused = { word -> if (!act.isFinishing && !act.isDestroyed) AlertDialog.Builder(act).setMessage(word).setPositiveButton(R.string.ok, null).show() }
        fill()
    }

    /** The bar stands: the plus steps above it (iOS .padding(.bottom, 46 + reserve)). */
    fun reserve(on: Boolean) {
        (plus.layoutParams as FrameLayout.LayoutParams).bottomMargin = act.dp(if (on) 96 else 36)
        plus.requestLayout()
    }

    /**
     * THE PAGE'S OWN SEARCH (iOS MusicTab.query, MontanaMusicPlayer.swift:1815-1830 at 2155; atom 12063b4d0f35, the author's word
     * 24.09: «the search on the music page searches only the music of this page, beautifully and natively»): the word at the head
     * narrows the list itself -- by the track's name, its tag's name and artist, and where it lies (its folder) -- letter case and
     * marks aside, as the system's search compares; nothing stands over the rows and nothing leaves the phone.
     */
    private fun shown(): List<Track> {
        val q = fold(query.trim())
        if (q.isEmpty()) return MusicFolders.tracks
        return MusicFolders.tracks.filter { t -> listOf(t.title, t.file, t.artist ?: "", place(t)).any { fold(it).contains(q) } }
    }
    /** Where the track lies: its folder's path inside the lent tree (the document's own id, «primary:Music/Rock/a.mp3»). */
    private fun place(t: Track): String = try {
        android.provider.DocumentsContract.getDocumentId(t.uri).substringAfter(':').substringBeforeLast('/', "")
    } catch (_: Exception) { "" }
    /** Letter case and marks aside (iOS localizedStandardContains). */
    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun fill() {
        val list = shown()
        spinner.visibility = if (MusicFolders.walking) View.VISIBLE else View.GONE
        plusGlyph.visibility = if (MusicFolders.walking) View.GONE else View.VISIBLE
        // an empty narrowing says «Nothing found» (the catalog's own key), an empty library «No music»
        empty.text = act.getString(if (MusicFolders.tracks.isNotEmpty() && list.isEmpty()) R.string.nothing_found else R.string.no_music)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rows.removeAllViews()
        list.forEachIndexed { i, t ->
            val face = faces.getOrPut(t.uri.toString()) { MiniFace(act, bar = false) }
            (face.parent as? LinearLayout)?.removeView(face)
            val cur = MusicPlayer.current?.uri == t.uri
            face.still(t, cur, cur && MusicPlayer.playing)
            face.setOnClickListener {
                if (MusicPlayer.current?.uri == t.uri) MusicPlayer.toggle() else MusicPlayer.play(act, list, i, act.getString(R.string.cf_folders))   // the queue is the list as it stands
            }
            face.play.setOnClickListener { face.performClick() }
            rows.addView(face, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** THE TRACK'S PLACE, ASKED OF THE PAGE (iOS MTMusicFocus, the author's word 01.10 00:44: «the track's number opens the page
     *  where it plays and centres the visible area on that track, so that it stands at the middle of the screen between the player
     *  and the screen's top»): `above` is the player's top on the page. */
    fun focus(t: Track, above: Int) {
        android.util.Log.d("Montana", "music place asked")
        scroll.post {
            val face = faces[t.uri.toString()]?.takeIf { it.parent === rows } ?: return@post
            val middle = (scroll.paddingTop + above) / 2
            scroll.smoothScrollTo(0, (face.top + face.height / 2 - middle).coerceAtLeast(0))
        }
    }

    /** The clock ticks every half second; the rows are drawn again only when the playing track or its rest changes. */
    private fun ringChanged() {
        val now = MusicPlayer.current?.uri?.toString()
        if (now == lastRing && MusicPlayer.playing == lastPlaying) return
        lastRing = now; lastPlaying = MusicPlayer.playing
        MusicFolders.tracks.forEach { t -> faces[t.uri.toString()]?.still(t, t.uri.toString() == now, t.uri.toString() == now && MusicPlayer.playing) }
    }
}
