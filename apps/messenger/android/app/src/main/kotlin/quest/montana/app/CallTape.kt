package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.webrtc.ExternalAudioProcessingFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE CALL'S RECORDING (iOS MTCallRecording.swift: MTCallRecordingPolicy, MTCallRecordingProcessor, MTCallTape, MTCallTapeExport):
 * a reader of the call's existing voices -- it never opens a microphone. My voice is taken after the engine cleaned it (the
 * capture's post-processing), theirs before the loudspeaker (the render's pre-processing), as iOS takes both through its
 * RTCAudioCustomProcessingDelegate. Each voice is laid on its own tape by the clock; at the end the two are mixed half and half
 * (iOS AVMutableAudioMixInputParameters 0.5) and encoded once into AAC, mono 48 kHz 48 kbit/s, an .m4a the chat sends as a voice.
 * The call's side (iOS MTCallRecording begin/finish/consent/changed/toggleByHand, MontanaCall.refreshRecording): a call that
 * connects is recorded by itself from level 7 (the person's switch and one consent) or by the call screen's mark on any level, unless
 * the correspondent's switch holds; the chat is told once; the caller sends the recording to the chat, or the one who answered when
 * the caller does not record -- one copy, never none. A video call's pictures are filmed beside the voices (CallFilm) and leave
 * with them as one .mp4. A tape cut by the app's death is brought back at the next return and sent signed «incomplete» (iOS
 * recover); DEVIATION: its manifest is plain JSON in the app's own files (iOS seals it in MontanaLocalVault).
 */
object CallTape {
    const val KEY = "callRecordingEnabled"   // NOT-UI: iOS MTCallRecordingPolicy.key, on by default
    const val CONSENT_KEY = "callRecordingConsent.v1"   // NOT-UI: the person's one consent on this phone
    private const val RATE = 48_000
    val enabled: Boolean get() = Prefs.bool(KEY, true)
    val consented: Boolean get() = Prefs.bool(CONSENT_KEY, false)
    /** Recording by itself opens at its level (iOS MTCallRecording.automatic, MTLevelAccess.autoRecord). */
    val automatic: Boolean get() = enabled && LevelAccess.opens(LevelAccess.Feature.AUTO_RECORD)

    /** THE CORRESPONDENT'S SWITCH HOLDS BY LEVEL (iOS mayRecord; the author's word 20.10.2026 17:2x UTC): a forbidding correspondent
     *  holds unless my level is above theirs; a build that says nothing forbids nothing; an unknown level holds. */
    fun mayRecord(theirSwitch: Boolean?, theirLevel: Int?, mine: Int?): Boolean {
        if (theirSwitch != false) return true
        if (theirLevel == null || mine == null) return false
        return mine > theirLevel
    }

    // ── THE HOOKS, given to the call engine's factory once (CallEngine.warm, after PeerConnectionFactory.initialize) ──
    private class Hook(val local: Boolean) : ExternalAudioProcessingFactory.AudioProcessing {
        @Volatile private var rate = 0
        override fun initialize(sampleRateHz: Int, numChannels: Int) { rate = sampleRateHz }
        override fun reset(newRate: Int) { rate = newRate }
        // The engine's floats keep the Int16 amplitude range (iOS: «the SDK's floating point samples use the Int16 range»).
        override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
            val t = tape ?: return
            if (CallLine.held || (local && CallLine.muted)) return   // a held call and my muted microphone lay nothing (iOS MTCallRecordingGate)
            val r = rate
            if (r <= 0 || numFrames !in 1..1920) return
            val f = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            val n = minOf(numFrames, f.remaining())
            val pcm = ShortArray(n) { f.get(it).toInt().coerceIn(-32768, 32767).toShort() }
            t.audio(local, pcm, r, SystemClock.elapsedRealtime())
        }
    }
    private val hooks: ExternalAudioProcessingFactory by lazy {
        ExternalAudioProcessingFactory().apply { setCapturePostProcessing(Hook(true)); setRenderPreProcessing(Hook(false)) }
    }
    fun processing(): ExternalAudioProcessingFactory = hooks

    @Volatile private var tape: Tape? = null

    // ── THE CALL'S SIDE (iOS MTCallRecording): one call of two at a time, named by its seed ──
    /** The call whose tape rolls, or waits for the person's consent: its chat, its name, and whether this phone sends it. */
    private class Call(val seed: String, val ref: String, val id: String, val sendsToChat: Boolean)
    private var live: Call? = null
    private var waiting: Call? = null
    private val byHand = HashSet<String>()   // the calls the person started by the call screen's mark (iOS byHand)
    private val stoppedByHand = HashSet<String>()   // and those they ended by it: not recorded again by themselves (iOS stoppedByHand)
    private val forbidden = HashSet<String>()   // the correspondent's switch holds against this phone: no tape, no mark (iOS forbidden)
    private val noticed = HashSet<String>()   // the chat was told of this call's recording, once (iOS manifest.notice)

    /** The call's name on disk (iOS MTCallName): SHA-256 of «montana.call.identity.v1:» and the seed's bytes, in hex. */
    fun name(seed: String): String {
        val raw = runCatching { Base64.decode(seed, Base64.NO_WRAP) }.getOrNull() ?: seed.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest("montana.call.identity.v1:".toByteArray() + raw).joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun recording(seed: String): Boolean = live?.seed == seed && tape != null
    /** A tape rolls on this phone (iOS MTCallRecording.recording is not empty): the call screen's red dot stands. */
    val rolling: Boolean get() = tape != null
    /** The call screen's mark stands (iOS MTCallRecordMark): not on a call the person ended by it, nor one the correspondent holds. */
    @Synchronized fun markShown(seed: String): Boolean = seed !in stoppedByHand && seed !in forbidden
    @Synchronized fun markOn(seed: String): Boolean = recording(seed) || seed in byHand

    /**
     * THE CONNECTED CALL IS WEIGHED (iOS MontanaCall.refreshRecording 1323-1340; the author's word 20.10.2026 16:5x UTC: «the call's
     * recording must be in the chat in any case, even if the correspondent's does not record»): the correspondent's switch holds by
     * level; the caller sends, and the one who answered does when the caller does not record by itself.
     */
    @Synchronized fun refresh(seed: String, ref: String, caller: Boolean, theirSwitch: Boolean?, theirLevel: Int?) {
        val mine = LevelAccess.level
        if (!mayRecord(theirSwitch, theirLevel, mine)) forbidden.add(seed) else forbidden.remove(seed)
        val peerRecords = theirSwitch == true && mayRecord(automatic, mine, theirLevel)
        begin(Call(seed, ref, name(seed), caller || !peerRecords))
    }

    /** The tape begins (iOS MTCallRecording.begin): by itself or by the hand, never over the correspondent's switch, after one consent. */
    @Synchronized private fun begin(call: Call) {
        val s = call.seed
        if (!(automatic || s in byHand) || s in stoppedByHand || live != null) return
        if (s in forbidden) return log("held by the correspondent's switch")
        // A press of the call's own mark is the person's consent for this call; recording by itself asks once
        if (!consented && s !in byHand) { waiting = call; ask(); return }
        if (s !in noticed) {
            noticed.add(s)
            val words = Book.ctx.getString(if (call.sendsToChat) R.string.call_rec_notice_send else R.string.call_rec_notice_local)
            val mid = Marks.mintMid()
            Book.edit(call.ref) { it.msgs.add(Msg(mid, words, true, Marks.birthMs(mid) ?: System.currentTimeMillis())) }
            Post.send(call.ref, mid, words)
        }
        live = call
        // the tape's manifest lies on disk first: a tape the app's death cuts is found by it at the next return (iOS MTCallTapeStore)
        note(call.id) { it.put("chat", call.ref).put("started", System.currentTimeMillis()).put("sends", call.sendsToChat).put("stopped", false) }
        tape = Tape(File(dir(), call.id)).also { CallFilm.roll(it.base, it.origin) }   // a video call's pictures roll with its voices
        log("start sends=" + call.sendsToChat)
    }

    /**
     * THE CALL ENDED, OR ITS RECORDING (iOS MTCallRecording.finish, export 602-664): the tape is closed and mixed into an .m4a off the
     * call's threads; the phone that sends lays it in the chat as a voice signed «Call recording», and a tape that heard no voice
     * says so in the chat on this phone alone. A recording this phone does not send stays on it.
     */
    @Synchronized fun finish(seed: String, deliver: Boolean = true) {
        if (waiting?.seed == seed) waiting = null
        val call = live?.takeIf { it.seed == seed } ?: return
        val t = tape ?: return
        live = null; tape = null
        val reel = CallFilm.stop()
        exporting.add(call.id)
        t.close { deliverTape(call.id, call.ref, deliver && call.sendsToChat, t, reel, partial = false) }
    }

    /**
     * A CLOSED TAPE LEAVES (iOS export 602-664): mixed into an .m4a off the call's threads, the film joined beside it; the phone
     * that sends lays it in the chat as a voice or a video signed «Call recording» («— incomplete» for a tape the app's death cut),
     * and a tape that heard no voice says so in the chat on this phone alone. A recording this phone does not send stays on it.
     * An export that fails keeps the tape and its manifest: the next return tries again (iOS: «Montana will try again»).
     */
    private fun deliverTape(id: String, ref: String, sends: Boolean, t: Tape, reel: CallFilm.Reel?, partial: Boolean) {
        try {
            val out = File(dir(), "$id.m4a")
            val secs = runCatching { export(t, out) }.onFailure { log("export fail " + it.javaClass.simpleName + " " + it.message) }.getOrNull()
            if (secs == null) { reel?.let { CallFilm.keep(it) }; note(id) { it.put("stopped", true).put("partial", partial) }; return }
            t.delete()
            // a call whose pictures were filmed leaves as one .mp4, the voices beside the film (iOS «call_<id>.mp4», kind vid)
            val mp4 = File(dir(), "$id.mp4")
            val film = reel?.let { if (secs > 0.0) CallFilm.join(it, out, mp4) else CallFilm.drop(it) } == true
            val c = Book.ctx
            when {
                !sends -> { if (film) out.delete(); log("kept on this phone s=" + secs.toInt() + if (film) " film" else "") }
                secs <= 0.0 -> {
                    out.delete(); mp4.delete()
                    val mid = Marks.mintMid()
                    Book.edit(ref) { it.msgs.add(Msg(mid, c.getString(R.string.call_rec_none), true, System.currentTimeMillis(), state = 1)) }
                }
                else -> {
                    val p = if (film) Media.Picked(mp4.readBytes(), "vid", "mp4", null, secs) else Media.Picked(out.readBytes(), "aud", "m4a", null, secs)
                    Media.send(c, ref, p, c.getString(if (partial) R.string.call_rec_row_partial else R.string.call_rec_row))
                    out.delete(); mp4.delete()
                    log("queued s=" + secs.toInt() + if (partial) " partial" else "")
                }
            }
            manifest(id).delete()
        } finally { exporting.remove(id) }
    }

    // ── THE TAPE'S MANIFEST (iOS MTCallTapeManifest, MTCallTapeStore): its chat, whether this phone sends it, the voices' rates ──
    private val exporting = java.util.Collections.synchronizedSet(HashSet<String>())   // tapes leaving now: never recovered twice (iOS reserved)
    private val manifests = Any()
    private fun manifest(id: String) = File(dir(), "$id.recording")
    private fun note(id: String, edit: (JSONObject) -> Unit) = synchronized(manifests) {
        runCatching {
            val f = manifest(id)
            val j = if (f.exists()) JSONObject(f.readText()) else JSONObject().put("id", id)
            edit(j)
            val tmp = File(f.path + ".tmp"); tmp.writeText(j.toString()); tmp.renameTo(f)
        }.onFailure { log("manifest " + it.javaClass.simpleName) }
    }
    @Volatile private var recovering = false

    /**
     * THE CUT TAPE COMES BACK (iOS MTCallRecording.recover, run at the app's return): every manifest this phone sends that is neither
     * rolling nor leaving now is closed as it lies -- a tape never stopped is «incomplete» -- and leaves as at a call's end. A tape
     * this phone does not send, or whose chat is gone, stays.
     */
    fun recover() {
        if (recovering) return
        recovering = true
        Thread({
            try {
                val found = dir().listFiles { f -> f.name.endsWith(".recording") } ?: emptyArray()
                for (f in found) {
                    val id = f.name.removeSuffix(".recording")
                    if (synchronized(this) { live?.id == id } || id in exporting) continue
                    val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
                    val ref = j.optString("chat")
                    if (!j.optBoolean("sends") || ref.isEmpty() || Book.chat(ref) == null) continue
                    exporting.add(id)
                    val base = File(dir(), id)
                    log("recover partial=" + !j.optBoolean("stopped"))
                    deliverTape(id, ref, true, Tape.reopen(base, j), CallFilm.reopen(base), partial = !j.optBoolean("stopped") || j.optBoolean("partial"))
                }
            } catch (e: Exception) { log("recover " + e.javaClass.simpleName) } finally { recovering = false }
        }, "call-record-recover").start()
    }

    /** The person's one consent (iOS consent): a yes sets the switch too, and the call that waited begins. */
    @Synchronized fun consent(allow: Boolean) {
        Prefs.setBool(CONSENT_KEY, allow); Prefs.setBool(KEY, allow)
        val w = waiting; waiting = null
        if (allow && w != null) begin(w)
        if (allow) CallLine.refreshRecording()
    }

    /** The switch on Access by level moved (iOS changed): on asks the consent once; off ends what rolls by itself, undelivered. */
    fun changed(on: Boolean, act: MainActivity?) {
        if (on) { if (!consented) ask(act) else CallLine.refreshRecording(); return }
        val s = synchronized(this) { waiting = null; live?.seed?.takeIf { it !in byHand } } ?: return
        finish(s, deliver = false)
    }

    /** The call screen's mark (iOS toggleByHand): a call not recorded starts by the hand; one recorded ends and is delivered. */
    fun toggleByHand(seed: String) {
        val stop = synchronized(this) {
            if (live?.seed == seed || waiting?.seed == seed) { stoppedByHand.add(seed); byHand.remove(seed); true }
            else if (seed !in stoppedByHand && seed !in forbidden) { byHand.add(seed); false }
            else return
        }
        log("hand " + if (stop) "stop" else "start level=" + (LevelAccess.level ?: -1))
        if (stop) finish(seed) else CallLine.refreshRecording()
    }

    /** The consent's question (iOS MTCallRecordingConsent), on the page in front: the call's or the settings'. */
    private fun ask(act: MainActivity? = null) = MainThread.post {
        val a = act ?: CallScreen.front ?: return@post
        val c: Context = a
        AlertDialog.Builder(c).setTitle(R.string.call_rec_ask_title).setMessage(R.string.call_rec_ask_body)
            .setPositiveButton(R.string.call_rec_switch) { _, _ -> consent(true) }
            .setNegativeButton(R.string.call_rec_no) { _, _ -> consent(false) }
            .setCancelable(false).show()
    }

    private fun dir() = File(Book.ctx.filesDir, "CallRecordings").apply { mkdirs() }

    /** One call's two tapes: raw 16-bit voices laid by the clock, a gap filled with silence, never written on the real-time thread. */
    private class Tape(val base: File) {
        val work = Executors.newSingleThreadExecutor()
        val slots = AtomicInteger(512)   // iOS: up to 2.56 s of the two 10 ms streams; past it a buffer is shed, never waited for
        val origin = SystemClock.elapsedRealtime()
        class Side(val file: File) { var out: BufferedOutputStream? = null; var rate = 0; var written = 0L }
        val sides = HashMap<Boolean, Side>()
        companion object {
            /** A tape left on disk, its voices at the rates its manifest kept: read, never written again. */
            fun reopen(base: File, j: JSONObject) = Tape(base).apply {
                work.shutdown()
                for (local in listOf(true, false)) {
                    val f = File(base.path + if (local) ".local" else ".remote")
                    val r = j.optInt(if (local) "rateLocal" else "rateRemote")
                    if (f.exists() && r > 0) sides[local] = Side(f).also { it.rate = r; it.written = f.length() / 2 }
                }
            }
        }
        fun audio(local: Boolean, pcm: ShortArray, rate: Int, at: Long) {
            if (slots.decrementAndGet() < 0) { slots.incrementAndGet(); return }
            work.execute {
                try {
                    val s = sides.getOrPut(local) {
                        Side(File(base.path + if (local) ".local" else ".remote")).also {
                            it.rate = rate; it.out = BufferedOutputStream(FileOutputStream(it.file), 1 shl 16)
                            note(base.name) { j -> j.put(if (local) "rateLocal" else "rateRemote", rate) }
                        }
                    }
                    if (s.rate != rate) return@execute   // a voice that changes its rate mid-call is not re-timed: its rest is shed
                    val due = (at - origin) * rate / 1000 - pcm.size
                    val o = s.out ?: return@execute
                    if (due > s.written + rate / 10) { repeat((due - s.written).toInt() * 2) { o.write(0) }; s.written = due }
                    val b = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                    o.write(b); s.written += pcm.size
                } catch (e: Exception) { log("tape " + e.javaClass.simpleName) } finally { slots.incrementAndGet() }
            }
        }
        fun close(then: () -> Unit) = work.execute {
            sides.values.forEach { runCatching { it.out?.close() } }
            Thread(then, "call-record-export").start()
            work.shutdown()
        }
        fun delete() { sides.values.forEach { it.file.delete() } }
    }

    /** A tape read at 48 kHz: one of another rate is drawn between its neighbours (iOS: the composition plays it at its own rate). */
    private class Reader(file: File, val rate: Int) {
        val inp = DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16))
        var a = 0; var b = 0; var pos = 0.0; var ended = false
        init { a = next(); b = next() }
        fun next(): Int = try { val lo = inp.read(); val hi = inp.read(); if (lo < 0 || hi < 0) throw EOFException(); (hi shl 8 or lo).toShort().toInt() } catch (e: EOFException) { ended = true; 0 }
        fun sample(): Int {
            val v = (a + (b - a) * pos).toInt()
            pos += rate.toDouble() / RATE
            while (pos >= 1.0) { pos -= 1.0; a = b; b = next() }
            return v
        }
        fun close() = runCatching { inp.close() }
    }

    /** The two voices, half and half, encoded once into AAC in an .m4a; returns its seconds. */
    private fun export(t: Tape, out: File): Double {
        // a tape cut mid-write may end in half a sample: it is read to its last whole one
        val readers = t.sides.values.filter { it.written > 0 && it.file.length() > 0 }.map { Reader(it.file, it.rate) }
        if (readers.isEmpty()) return 0.0
        val total = t.sides.values.maxOf { it.written * RATE / maxOf(1, it.rate) }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var fed = 0L; var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val n = minOf(buf.capacity() / 2, 1024, (total - fed).toInt().coerceAtLeast(0))
                        for (k in 0 until n) buf.putShort((readers.sumOf { it.sample() } / 2).coerceIn(-32768, 32767).toShort())
                        val pts = fed * 1_000_000 / RATE
                        fed += n
                        inputDone = n == 0 || fed >= total
                        codec.queueInputBuffer(i, 0, n * 2, pts, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start() }
                else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0)
                        mux.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            readers.forEach { it.close() }
            runCatching { codec.stop() }; codec.release()
            runCatching { if (track >= 0) mux.stop() }; mux.release()
        }
        log("export ok s=" + total / RATE)
        return total.toDouble() / RATE
    }

    private fun log(s: String) { Log.d("Montana", "call record: " + s) }
}
