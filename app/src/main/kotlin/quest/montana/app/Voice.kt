package quest.montana.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File

/**
 * THE VOICE TAPE (iOS VoiceRecorder + MontanaVoiceSound): 48 kHz mono, AAC at 64 kbit/s in an .m4a — the same speech through
 * the same door; the platform's own MediaRecorder writes it. The tape rides as a media letter of kind «aud» with its length «du».
 */
object VoiceTape {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var began = 0L
    /** THE WAVE IS THE SOUND ITSELF (iOS VoiceRecorder.levels): one slot per 25 ms keeps the loudest reading, linear 0…1. */
    val levels = ArrayList<Float>()
    var onLevel: ((Float) -> Unit)? = null
    private val meter = object : Runnable {
        override fun run() {
            val r = rec ?: return
            if (!paused) {   // a paused tape hears nothing: the wave stands (iOS)
                val a = runCatching { r.maxAmplitude }.getOrDefault(0) / 32767f
                levels.add(a); onLevel?.invoke(a)
            }
            MainThread.later(25, this)
        }
    }
    val seconds: Double get() = if (rec == null) 0.0 else (System.currentTimeMillis() - began - pausedMs - (if (paused) System.currentTimeMillis() - pauseAt else 0)) / 1000.0

    /** THE PAUSE OF A LOCKED TAPE (iOS VoiceRecorder.pause/resume): the platform's recorder stands, the file skips the gap. */
    var paused = false; private set
    private var pauseAt = 0L
    private var pausedMs = 0L
    fun togglePause() {
        val r = rec ?: return
        if (paused) { runCatching { r.resume() }; pausedMs += System.currentTimeMillis() - pauseAt; paused = false }
        else if (runCatching { r.pause() }.isSuccess) { pauseAt = System.currentTimeMillis(); paused = true }
    }

    /** The tape's 60 lines as iOS shapes them (MTWaveform.compute): scaled to a loud slot (92nd percentile), a soft curve (0.7). */
    fun waveform(): FloatArray {
        if (levels.isEmpty()) return FloatArray(0)
        val bars = 60
        val raw = FloatArray(bars) { i ->
            val a = i * levels.size / bars; val b = maxOf(a + 1, (i + 1) * levels.size / bars)
            var acc = 0f; for (j in a until minOf(b, levels.size)) acc += levels[j]; acc / maxOf(1, b - a)
        }
        val sorted = raw.sorted()
        val ref = maxOf(sorted[((sorted.size - 1) * 0.92).toInt()], 1e-6f)
        return FloatArray(bars) { Math.pow(minOf(1f, raw[it] / ref).toDouble(), 0.7).toFloat() }
    }

    fun granted(c: Context) = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The tape starts; false when the microphone is not ours (the question is asked, the next hold records). */
    fun start(act: MainActivity): Boolean {
        if (!granted(act)) { act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7); return false }
        stop(keep = false)
        val f = File(act.cacheDir, "tape-${System.currentTimeMillis()}.m4a")
        return try {
            rec = MediaRecorder(act).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64_000)
                setOutputFile(f.path)
                prepare(); start()
            }
            file = f; began = System.currentTimeMillis(); paused = false; pausedMs = 0
            levels.clear(); MainThread.later(25, meter)
            true
        } catch (_: Exception) { rec?.release(); rec = null; f.delete(); false }
    }

    /** The tape ends: the file and its length in seconds, or null when it was dropped or too short to be a word. */
    fun stop(keep: Boolean): Pair<File, Double>? {
        val r = rec ?: return null
        rec = null
        val secs = seconds
        paused = false
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file; file = null
        if (!keep || !ok || secs < 0.6 || f == null) { f?.delete(); return null }
        return f to secs
    }
}

/**
 * ONE VOICE PLAYS AT A TIME (iOS VoicePlayer, the chat's one player): a new voice stops the one before; a tap on the voice that
 * plays rests it and plays it on (iOS: pause, not stop); a queue's next follows the end (iOS auto-next). The voice plays at the
 * one speed of voices and notes (Playing.rate), says whose it is to the one bar (sender), and while it plays a track steps
 * aside with its place and plays on after it (iOS stepAside, 26.09); a voice that starts closes an open note.
 */
object VoicePlayer {
    private var player: MediaPlayer? = null
    var playing: String? = null; private set
    var paused = false; private set
    var sender = ""; private set   // whose voice, as the person sees the name (iOS nowSender)
    private var onStop: (() -> Unit)? = null
    private var progress: ((Int, Int) -> Unit)? = null
    private var musicAside = false

    /** This voice sounds now — not only chosen: a rested voice is chosen and silent. */
    fun sounding(path: String?) = path != null && playing == path && !paused

    fun toggle(path: String, onProgress: (Int, Int) -> Unit, stopped: () -> Unit, then: (() -> Unit)? = null, sender: String = "") {
        if (playing == path) { if (paused) resume() else pause(); return }
        stop()
        NoteOpen.close?.invoke()   // one thing plays: a voice closes the open note (iOS MontanaVideoDock)
        val p = runCatching { MediaPlayer().apply { setDataSource(path); prepare() } }.getOrNull() ?: return
        if (MusicPlayer.playing) { MusicPlayer.pause(); musicAside = true }
        player = p; playing = path; paused = false; onStop = stopped; progress = onProgress; this.sender = sender
        p.setOnCompletionListener { stop(); then?.invoke() }
        p.start(); speed(p)
        tick(path)
        Playing.changed()
    }
    // the speed is set on a sounding player only: on a rested one the platform starts it
    private fun speed(p: MediaPlayer) { runCatching { p.playbackParams = p.playbackParams.setSpeed(Playing.rate) } }
    fun applyRate() { val p = player ?: return; if (!paused) speed(p) }
    private fun tick(path: String) {
        val t = object : Runnable {
            override fun run() {
                val cur = player ?: return
                if (playing != path || paused) return
                runCatching { progress?.invoke(cur.currentPosition, cur.duration) }
                Playing.changed()
                MainThread.later(100, this)
            }
        }
        MainThread.later(0, t)
    }
    fun pause() { val p = player ?: return; runCatching { p.pause() }; paused = true; Playing.changed() }
    fun resume() {
        val p = player ?: return
        val path = playing ?: return
        runCatching { p.start(); speed(p) }; paused = false; tick(path); Playing.changed()
    }
    fun seek(path: String, ms: Int) { if (playing == path) player?.seekTo(ms) }
    fun seekTo(f: Double) { val p = player ?: return; runCatching { p.seekTo((f * p.duration).toInt()) }; Playing.changed() }
    val fraction: Double get() = player?.let { p -> runCatching { p.currentPosition.toDouble() / maxOf(1, p.duration) }.getOrNull() } ?: 0.0
    fun stop() {
        val had = player != null
        player?.runCatching { stop(); release() }
        player = null; playing = null; paused = false; progress = null
        onStop?.invoke(); onStop = null
        // the track that stepped aside for the voice plays on from its place (iOS noteClosed, 26.09)
        if (musicAside) { musicAside = false; if (!MusicPlayer.playing) MusicPlayer.resume() }
        if (had) Playing.changed()
    }
}

/** A voice letter in its bubble: the play mark, the running line and the time (iOS MTVoiceBubble). */
fun voiceBody(c: Context, m: Msg, du: Double, into: LinearLayout, wave: FloatArray? = null, chat: Chat? = null) {
    val tint = BubbleStyle.text(m.mine)
    val path = m.file?.takeIf { File(it).exists() }
    fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    // The length is the sender's word; a letter without it is measured from the file itself.
    val total = if (du > 0) (du * 1000).toInt() else path?.let { p ->
        runCatching { android.media.MediaMetadataRetriever().use { r -> r.setDataSource(p); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() } }.getOrNull()
    } ?: 0
    val time = c.text(fmt(total), 13f, BubbleStyle.time(m.mine))
    val bar = WaveView(c).apply {
        bars = wave?.takeIf { it.isNotEmpty() } ?: FloatArray(60) { 0.25f }
        played = BubbleStyle.text(m.mine); rest = (BubbleStyle.time(m.mine) and 0x00FFFFFF) or 0x80000000.toInt()
    }
    val mark = ImageView(c).apply {
        setImageResource(if (VoicePlayer.sounding(path)) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
        alpha = if (path == null) 0.4f else 1f
    }
    mark.setOnClickListener {
        // automatic download off, a voice of theirs waits: the play key asks for it (iOS: a tap starts the queued download)
        val p = path ?: run { if (Media.waiting(c, m)) Media.tapped(c, m); return@setOnClickListener }
        // USER-DATA: whose voice it is, as the one bar names it (iOS nowSender)
        val who = if (m.mine) Prefs.userName.trim() else chat?.shown.orEmpty()
        VoicePlayer.toggle(p, onProgress = { cur, dur -> bar.progress = cur.toFloat() / maxOf(1, dur); time.text = fmt(cur) },
            stopped = { mark.setImageResource(R.drawable.ic_play_fill); bar.progress = 0f; time.text = fmt(total) }, sender = who)
        mark.setImageResource(if (VoicePlayer.sounding(p)) R.drawable.ic_pause_fill else R.drawable.ic_play_fill)
        // their voice played here: its sender is told once, silently (iOS notePlayed) — the road to their «Listened»
        if (!m.mine && VoicePlayer.playing == p) Thread { Post.notePlayed(m.mid) }.start()
    }
    bar.onSeek = { f -> if (path != null) VoicePlayer.seek(path, (f * total).toInt()) }
    // the bar rests and plays this voice too: the play mark follows what the one player does (iOS: the bubble reads the one player)
    val follow: () -> Unit = { mark.setImageResource(if (VoicePlayer.sounding(path)) R.drawable.ic_pause_fill else R.drawable.ic_play_fill) }
    mark.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) { Playing.listen(follow) }
        override fun onViewDetachedFromWindow(v: android.view.View) { Playing.unlisten(follow) }
    })
    into.addView(c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        addView(mark, lp(c.dp(40), c.dp(40)))
        addView(if (path == null) android.widget.ProgressBar(c).apply { isIndeterminate = true } else bar, lp(c.dp(150), WRAP))
        addView(time, lp(WRAP, WRAP).apply { marginStart = c.dp(4) })
    }, LinearLayout.LayoutParams(WRAP, WRAP))
    if (path == null) into.getChildAt(into.childCount - 1).let { (it as LinearLayout).getChildAt(1).layoutParams.width = c.dp(28) }
}


/**
 * THE WAVE (iOS MTVoiceBubble's lines / the recorder's swell): one rounded line per slot. Played — the lines behind the
 * progress in the letter's own colour; `live` — the newest lines of a tape still being spoken, each as tall as its loudness.
 */
class WaveView(c: Context) : View(c) {
    var bars = FloatArray(0); set(v) { field = v; invalidate() }
    var progress = 0f; set(v) { field = v; invalidate() }
    var played = Color.WHITE
    var rest = Color.GRAY
    var onSeek: ((Float) -> Unit)? = null
    private var live: ArrayList<Float>? = null
    private var peak = 4000f / 32767   // iOS referenceFull: −18 dBFS is the whole height
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }

    /** The swell of a tape being spoken: each new loudness pushes a line in from the right. */
    fun push(a: Float) {
        val l = live ?: ArrayList<Float>().also { live = it }
        l.add(a); peak = maxOf(peak, a)
        val room = maxOf(1, width / (dp(3) + dp(2)))
        while (l.size > room) l.removeAt(0)
        invalidate()
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val s = onSeek ?: return false
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            s((e.x / width).coerceIn(0f, 1f))
        }
        return true
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(28))

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = dp(3).toFloat(); val gap = dp(2).toFloat()
        paint.strokeWidth = w
        val mid = height / 2f; val maxH = height - w
        val l = live
        if (l != null) {
            paint.color = played
            var x = width - w / 2
            for (i in l.indices.reversed()) {
                val h = maxOf(w, minOf(1f, l[i] / peak) * maxH)
                canvas.drawLine(x, mid - h / 2 + w / 2, x, mid + h / 2 - w / 2, paint)
                x -= w + gap; if (x < 0) break
            }
            return
        }
        if (bars.isEmpty()) return
        val step = width.toFloat() / bars.size
        bars.forEachIndexed { i, v ->
            val x = i * step + step / 2
            paint.color = if ((i + 0.5f) / bars.size <= progress) played else rest
            val h = maxOf(w, v * maxH)
            canvas.drawLine(x, mid - h / 2 + w / 2, x, mid + h / 2 - w / 2, paint)
        }
        paint.strokeWidth = minOf(w, step * 0.6f)
    }
}
