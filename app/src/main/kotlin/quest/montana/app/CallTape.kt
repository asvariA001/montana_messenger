package quest.montana.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE CALL'S RECORDING (iOS MTCallRecording.swift: MTCallRecordingPolicy, MTCallRecordingProcessor, MTCallTape, MTCallTapeExport):
 * a reader of the call's existing voices -- it never opens a microphone. My voice is taken after the engine cleaned it (the
 * capture's post-processing), theirs before the loudspeaker (the render's pre-processing), as iOS takes both through its
 * RTCAudioCustomProcessingDelegate. Each voice is laid on its own tape by the clock; at the end the two are mixed half and half
 * (iOS AVMutableAudioMixInputParameters 0.5) and encoded once into AAC, mono 48 kHz 48 kbit/s, an .m4a the chat sends as a voice.
 * NOT YET WIRED: the call's start and end, the caps rec/lv, the notice letter, the consent, the switch and the mark (HANDOVER 113).
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
            if (local && muted) return
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
    @Volatile var muted = false   // my microphone is off: my side is not recorded (iOS MTCallRecordingGate.mute)
    val recording: Boolean get() = tape != null

    /** The call is connected and may be recorded: its tape begins. One call holds the tapes at a time (iOS MTCallRecordingGate). */
    @Synchronized fun begin(id: String) {
        if (tape != null) return
        tape = Tape(File(dir(), id))
        log("start")
    }

    /** The call ended: its tape is closed and mixed into an .m4a off the call's threads; done gets the file and its seconds,
     *  or null when no voice was heard (iOS «This call has no recording because no audio was received»). */
    @Synchronized fun finish(out: File, done: (File?, Double) -> Unit) {
        val t = tape ?: return
        tape = null
        t.close {
            val secs = runCatching { export(t, out) }.onFailure { log("export fail " + it.javaClass.simpleName + " " + it.message) }.getOrNull()
            t.delete()
            done(if (secs != null && secs > 0) out else null, secs ?: 0.0)
        }
    }

    private fun dir() = File(Book.ctx.filesDir, "CallRecordings").apply { mkdirs() }

    /** One call's two tapes: raw 16-bit voices laid by the clock, a gap filled with silence, never written on the real-time thread. */
    private class Tape(val base: File) {
        val work = Executors.newSingleThreadExecutor()
        val slots = AtomicInteger(512)   // iOS: up to 2.56 s of the two 10 ms streams; past it a buffer is shed, never waited for
        val origin = SystemClock.elapsedRealtime()
        class Side(val file: File) { val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16); var rate = 0; var written = 0L }
        val sides = HashMap<Boolean, Side>()
        fun audio(local: Boolean, pcm: ShortArray, rate: Int, at: Long) {
            if (slots.decrementAndGet() < 0) { slots.incrementAndGet(); return }
            work.execute {
                try {
                    val s = sides.getOrPut(local) { Side(File(base.path + if (local) ".local" else ".remote")).also { it.rate = rate } }
                    if (s.rate != rate) return@execute   // a voice that changes its rate mid-call is not re-timed: its rest is shed
                    val due = (at - origin) * rate / 1000 - pcm.size
                    if (due > s.written + rate / 10) { repeat((due - s.written).toInt() * 2) { s.out.write(0) }; s.written = due }
                    val b = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                    s.out.write(b); s.written += pcm.size
                } catch (e: Exception) { log("tape " + e.javaClass.simpleName) } finally { slots.incrementAndGet() }
            }
        }
        fun close(then: () -> Unit) = work.execute {
            sides.values.forEach { runCatching { it.out.close() } }
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
