package quest.montana.app

import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.webrtc.VideoFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * THE CALL AS THE PERSON SAW IT (iOS MTCallRecordingLayout, MTCallTape.video/canvas/drawn/seen/placed, MTCallRecordingVideo,
 * MTCallTapeExport.copyPicture; the author's word 16.10.2026 15:27 UTC: «the video call must be recorded as I saw it»): while a
 * tape rolls, the call's two pictures are drawn as its screen stood -- the big one filling the frame (a shared screen whole, on
 * black), the corner one where and as large as it stood, rounded with its rim, my own mirrored as the screen mirrors it -- ten
 * times a second by the film's own clock into one H.264 film of the screen's shape, 400 kbit/s. At the end the film is copied as it was written beside
 * the voices' AAC into one .mp4: the picture is never encoded twice. The pictures are drawn on this phone's processor, not its
 * graphics: ten small frames a second.
 */
object CallFilm {
    /** What stands where on the call's screen (iOS MTCallRecordingLayout.Shape), in the screen's pixels; its one writer is
     *  the call screen (CallScreen.sides, placeMini). Replaced whole, never changed in place. */
    class Shape(val screenW: Int = 9, val screenH: Int = 16, val bigLocal: Boolean = false, val mini: RectF? = null,
                val mirrorLocal: Boolean = true, val remoteFits: Boolean = false, val round: Float = 0f)
    @Volatile var shape = Shape()

    /** One picture as it came, made small (its long side at most 640) and read out of the engine's buffer at once. */
    internal class Pic(val w: Int, val h: Int, val turn: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray, val at: Long) {
        val cw = (w + 1) / 2
        // the picture as the screen stands it: a turned frame's sides trade places
        val rw = if (turn % 180 == 0) w else h
        val rh = if (turn % 180 == 0) h else w
    }

    @Volatile private var reel: Reel? = null

    /** The film begins with the tape (CallTape.begin): its clock is the tape's. */
    fun roll(base: File, origin: Long) { reel = Reel(File(base.path + ".video"), origin) }
    /** The film stops taking pictures (CallTape.finish); what it wrote is closed by [join]. */
    fun stop(): Reel? = reel.also { reel = null }

    /**
     * A FRAME OF EITHER PICTURE (iOS MTCallRecordingVideo.renderFrame, MTCallTape.video): admitted before it costs anything --
     * each picture is read at most as often as the film writes, a held call lays nothing, under the phone's heat the picture
     * slows to one a second and at its critical state stops, the voice staying whole (iOS MontanaPower.floorNow). Video may
     * shed frames; it never delays the call: the frame's own thread only reads it out and leaves.
     */
    fun frame(f: VideoFrame, local: Boolean) {
        val r = reel ?: return
        if (CallLine.held) return
        val floor = CallLine.powerFloor
        if (floor >= 4) return
        val step = if (floor == 0) 100L else 1000L
        val now = SystemClock.elapsedRealtime()
        val due = if (local) r.dueLocal else r.dueRemote
        if (!ripe(now, due)) return
        if (!r.slots.tryAcquire()) return
        if (local) r.dueLocal = next(now, due, step) else r.dueRemote = next(now, due, step)
        val pic = runCatching { read(f, now) }.getOrNull()
        if (pic == null) { r.slots.release(); return }
        r.work.execute { try { r.put(local, pic, step) } finally { r.slots.release() } }
    }

    /**
     * TEN A SECOND, NOT EIGHT: the film keeps a clock of its own -- each tenth of a second is due, and the frame nearest it, up
     * to 20 ms early, takes it. Waiting a full step from the last frame taken lost a frame interval at each step (a camera's
     * 24 frames a second came 123 ms apart: 8 a second, 510 frames in 63 s on A1, 01.11.2026). A clock that fell behind by more
     * than a step starts again from now.
     */
    private const val EARLY = 20L
    private fun ripe(now: Long, due: Long) = now >= due - EARLY
    private fun next(now: Long, due: Long, step: Long) = if (now - due > step) now + step else due + step

    private fun read(f: VideoFrame, at: Long): Pic? {
        val b = f.buffer
        val k = minOf(1.0, 640.0 / maxOf(1, maxOf(b.width, b.height)))
        val sw = maxOf(2, (b.width * k).toInt() and 1.inv())
        val sh = maxOf(2, (b.height * k).toInt() and 1.inv())
        val small = b.cropAndScale(0, 0, b.width, b.height, sw, sh)
        try {
            val i = small.toI420() ?: return null
            try {
                val w = i.width; val h = i.height; val cw = (w + 1) / 2; val ch = (h + 1) / 2
                fun plane(src: ByteBuffer, stride: Int, pw: Int, ph: Int): ByteArray {
                    val out = ByteArray(pw * ph); val s = src.duplicate()
                    for (row in 0 until ph) { s.position(row * stride); s.get(out, row * pw, pw) }
                    return out
                }
                return Pic(w, h, f.rotation, plane(i.dataY, i.strideY, w, h), plane(i.dataU, i.strideU, cw, ch), plane(i.dataV, i.strideV, cw, ch), at)
            } finally { i.release() }
        } finally { small.release() }
    }

    /** The file's picture has the shape of the screen the person looked at (iOS canvas): its long side 640, the short one by the
     *  screen's proportion, both even for the encoder. */
    private fun canvas(s: Shape): Pair<Int, Int> {
        val w = maxOf(1, s.screenW).toDouble(); val h = maxOf(1, s.screenH).toDouble()
        fun even(v: Double) = maxOf(2, (Math.round(v / 2) * 2).toInt())
        return if (w <= h) even(640 * w / h) to 640 else 640 to even(640 * h / w)
    }

    /** The live film: the pictures' newest frames, the canvas drawn from them, the encoder, its samples laid on disk. */
    class Reel(val file: File, val origin: Long) {
        internal val work = Executors.newSingleThreadExecutor()
        internal val slots = Semaphore(3)
        @Volatile internal var dueLocal = 0L
        @Volatile internal var dueRemote = 0L
        private val latest = HashMap<Boolean, Pic>()
        private var w = 0; private var h = 0
        private var yy = ByteArray(0); private var uu = ByteArray(0); private var vv = ByteArray(0)
        private var codec: MediaCodec? = null
        private var out: DataOutputStream? = null
        internal var format: MediaFormat? = null
        internal var frames = 0
        private var due = 0L
        private var lastPts = 0L
        private var failed = false
        private var ended = false

        internal fun put(local: Boolean, pic: Pic, step: Long) {
            if (failed || ended) return
            latest[local] = pic
            val now = pic.at
            if (!ripe(now, due)) return
            // A stopped camera leaves the picture: the film cannot show its last frame for long (iOS: older than a second)
            latest.entries.removeAll { now - it.value.at >= 1000 }
            try {
                val s = shape
                if (codec == null) open(s)   // the file takes the screen's shape at its birth (iOS)
                draw(s)
                val pts = maxOf(lastPts + 1, (now - origin) * 1000)
                if (encode(pts)) { due = next(now, due, step); lastPts = pts }
            } catch (e: Exception) { fail(e) }
        }

        private fun open(s: Shape) {
            val (cw, chh) = canvas(s)
            w = cw; h = chh
            yy = ByteArray(w * h); uu = ByteArray(w * h / 4); vv = ByteArray(w * h / 4)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 400_000)   // iOS AVVideoAverageBitRateKey 400_000
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)   // iOS AVVideoMaxKeyFrameIntervalKey 20 at ten a second
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); c.start()
            codec = c
            out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
            log("film " + w + "x" + h)
        }

        /** THE CALL AS IT WAS SEEN (iOS drawn, seen): of the two pictures, the big one and the corner one while it stood. */
        private fun draw(s: Shape) {
            yy.fill(16); uu.fill(-128); vv.fill(-128)   // black
            val big = latest[s.bigLocal] ?: latest[!s.bigLocal]
            val bigIsLocal = if (latest[s.bigLocal] != null) s.bigLocal else !s.bigLocal
            if (big != null) place(big, 0f, 0f, w.toFloat(), h.toFloat(), fit = !bigIsLocal && s.remoteFits, mirror = bigIsLocal && s.mirrorLocal, round = 0f)
            val small = latest[!s.bigLocal]
            val r = s.mini
            if (latest[s.bigLocal] != null && small != null && r != null && s.screenW > 0 && s.screenH > 0) {
                val sx = w.toFloat() / s.screenW; val sy = h.toFloat() / s.screenH
                val local = !s.bigLocal
                place(small, r.left * sx, r.top * sy, r.width() * sx, r.height() * sy, fit = !local && s.remoteFits,
                    mirror = local && s.mirrorLocal, round = s.round * minOf(sx, sy))
            }
        }

        /**
         * A picture in its place (iOS placed): filling it and losing its edges, as a camera's picture stands on the screen, or whole
         * inside it; a rounded one wears a thin white rim a quarter strong (iOS rim 0.25), the corners outside it untouched.
         */
        private fun place(p: Pic, x0: Float, y0: Float, bw: Float, bh: Float, fit: Boolean, mirror: Boolean, round: Float) {
            if (bw < 2 || bh < 2) return
            val k = if (fit) minOf(bw / p.rw, bh / p.rh) else maxOf(bw / p.rw, bh / p.rh)
            val ox = x0 + (bw - p.rw * k) / 2; val oy = y0 + (bh - p.rh * k) / 2
            val rim = if (round > 0f) 1 else 0
            val xa = maxOf(0, (x0 - rim).toInt()); val xb = minOf(w, Math.ceil((x0 + bw + rim).toDouble()).toInt())
            val ya = maxOf(0, (y0 - rim).toInt()); val yb = minOf(h, Math.ceil((y0 + bh + rim).toDouble()).toInt())
            // 0 outside, 1 the rim, 2 the picture
            fun inside(px: Float, py: Float): Int {
                if (round <= 0f) return if (px >= x0 && px < x0 + bw && py >= y0 && py < y0 + bh) 2 else 0
                fun within(grow: Float): Boolean {
                    val l = x0 - grow; val t = y0 - grow; val rr = x0 + bw + grow; val bb = y0 + bh + grow; val rad = round + grow
                    if (px < l || px >= rr || py < t || py >= bb) return false
                    val cx = if (px < l + rad) l + rad else if (px > rr - rad) rr - rad else px
                    val cy = if (py < t + rad) t + rad else if (py > bb - rad) bb - rad else py
                    val dx = px - cx; val dy = py - cy
                    return dx * dx + dy * dy <= rad * rad
                }
                return if (within(0f)) 2 else if (within(1f)) 1 else 0
            }
            // the canvas point → the picture as stood (u, v) → the frame's own pixel by its turn
            fun src(px: Float, py: Float, sw: Int, sh: Int, scale: Int): Int {
                var u = ((px - ox) / k / scale).toInt(); val v = ((py - oy) / k / scale).toInt()
                val rw = (p.rw + scale - 1) / scale; val rh = (p.rh + scale - 1) / scale
                if (mirror) u = rw - 1 - u
                val uc = u.coerceIn(0, rw - 1); val vc = v.coerceIn(0, rh - 1)
                val bx: Int; val by: Int
                when (p.turn) {
                    90 -> { bx = vc; by = sh - 1 - uc }
                    180 -> { bx = sw - 1 - uc; by = sh - 1 - vc }
                    270 -> { bx = sw - 1 - vc; by = uc }
                    else -> { bx = uc; by = vc }
                }
                return by.coerceIn(0, sh - 1) * sw + bx.coerceIn(0, sw - 1)
            }
            for (y in ya until yb) for (x in xa until xb) {
                val at = inside(x + 0.5f, y + 0.5f)
                if (at == 2) yy[y * w + x] = p.y[src(x + 0.5f, y + 0.5f, p.w, p.h, 1)]
                else if (at == 1) yy[y * w + x] = ((yy[y * w + x].toInt() and 0xff) * 3 / 4 + 64).toByte()
            }
            val cw = w / 2; val ch = (p.h + 1) / 2
            for (y in ya / 2 until (yb + 1) / 2) for (x in xa / 2 until minOf(cw, (xb + 1) / 2)) {
                if (y >= h / 2) break
                val at = inside(2 * x + 1f, 2 * y + 1f)
                if (at == 2) { val i = src(2 * x + 1f, 2 * y + 1f, p.cw, ch, 2); uu[y * cw + x] = p.u[i]; vv[y * cw + x] = p.v[i] }
                else if (at == 1) {
                    uu[y * cw + x] = ((uu[y * cw + x].toInt() and 0xff) * 3 / 4 + 32).toByte()
                    vv[y * cw + x] = ((vv[y * cw + x].toInt() and 0xff) * 3 / 4 + 32).toByte()
                }
            }
        }

        private fun encode(pts: Long): Boolean {
            val c = codec ?: return false
            val i = c.dequeueInputBuffer(20_000)
            if (i < 0) { drain(false); return false }   // the encoder is behind: this frame is shed
            val img = c.getInputImage(i) ?: throw IllegalStateException("no input image")
            val planes = img.planes
            fun lay(src: ByteArray, pw: Int, ph: Int, plane: android.media.Image.Plane) {
                val b = plane.buffer; val rs = plane.rowStride; val ps = plane.pixelStride
                for (row in 0 until ph) {
                    if (ps == 1) { b.position(row * rs); b.put(src, row * pw, pw) }
                    else for (col in 0 until pw) b.put(row * rs + col * ps, src[row * pw + col])
                }
            }
            lay(yy, w, h, planes[0]); lay(uu, w / 2, h / 2, planes[1]); lay(vv, w / 2, h / 2, planes[2])
            c.queueInputBuffer(i, 0, w * h * 3 / 2, pts, 0)
            frames++
            drain(false)
            return true
        }

        private fun drain(end: Boolean) {
            val c = codec ?: return
            val info = MediaCodec.BufferInfo()
            val until = SystemClock.elapsedRealtime() + 3000
            while (true) {
                val o = c.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { format = c.outputFormat; keepFormat(c.outputFormat) }
                else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val b = c.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); b.get(bytes)
                        out?.run { writeLong(info.presentationTimeUs); writeInt(info.flags); writeInt(info.size); write(bytes) }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (!end || SystemClock.elapsedRealtime() > until) break
            }
        }

        /** The encoder's shape and its two parameter sets lie beside the film: a film the app's death cut is joined by them. */
        private fun keepFormat(f: MediaFormat) = runCatching {
            fun csd(k: String) = f.getByteBuffer(k)?.let { b -> val d = b.duplicate(); ByteArray(d.remaining()).also { d.get(it) } }
            val j = JSONObject().put("w", f.getInteger(MediaFormat.KEY_WIDTH)).put("h", f.getInteger(MediaFormat.KEY_HEIGHT))
            csd("csd-0")?.let { j.put("csd0", Base64.encodeToString(it, Base64.NO_WRAP)) }
            csd("csd-1")?.let { j.put("csd1", Base64.encodeToString(it, Base64.NO_WRAP)) }
            File(file.path + ".fmt").writeText(j.toString())
        }.onFailure { log("film format " + it.javaClass.simpleName) }

        private fun fail(e: Exception) {
            failed = true
            log("film fail " + e.javaClass.simpleName + " " + e.message)
        }

        /** The film's end: the encoder's last frames are laid down and the file closed; true when it holds a picture. */
        internal fun end(): Boolean = runCatching {
            work.submit<Boolean> {
                ended = true
                codec?.let { c ->
                    runCatching {
                        val i = c.dequeueInputBuffer(100_000)
                        if (i >= 0) c.queueInputBuffer(i, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        drain(true)
                    }.onFailure { log("film end " + it.javaClass.simpleName) }
                    runCatching { c.stop() }; c.release()
                }
                codec = null
                runCatching { out?.close() }
                !failed && frames > 0 && format != null
            }.get(10, TimeUnit.SECONDS)
        }.getOrDefault(false).also { work.shutdown() }
    }

    /** A film without voices is not kept (iOS: no voice, no recording): closed and gone. */
    fun drop(r: Reel): Boolean { r.end(); gone(r); return false }
    /** A film whose voices could not be exported yet is closed and stays for the next return (CallTape.recover). */
    fun keep(r: Reel) { r.end() }
    private fun gone(r: Reel) { r.file.delete(); File(r.file.path + ".fmt").delete() }

    /** A film the app's death cut, read back by its kept format; its last sample may be torn, and is left out at the join. */
    fun reopen(base: File): Reel? {
        val file = File(base.path + ".video")
        if (!file.exists()) return null
        val j = runCatching { JSONObject(File(file.path + ".fmt").readText()) }.getOrNull()
        val csd0 = j?.optString("csd0").orEmpty()
        if (j == null || csd0.isEmpty()) { file.delete(); File(file.path + ".fmt").delete(); return null }
        return Reel(file, 0L).also { r ->
            r.format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, j.getInt("w"), j.getInt("h")).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(Base64.decode(csd0, Base64.NO_WRAP)))
                j.optString("csd1").takeIf { it.isNotEmpty() }?.let { setByteBuffer("csd-1", ByteBuffer.wrap(Base64.decode(it, Base64.NO_WRAP))) }
            }
            r.frames = 1
        }
    }

    /**
     * THE PICTURE IS NOT ENCODED TWICE (iOS copyPicture; 16.10.2026, No.70, the fleet's heat): the film's samples are copied as
     * they were written beside the voices' AAC, each in its time, into one .mp4. False -- no picture, or the copy failed -- and the
     * voices go alone, as an .m4a. The film's file is gone either way.
     */
    fun join(r: Reel, voices: File, out: File): Boolean {
        try {
            if (!r.end()) return false
            val vf = r.format ?: return false
            val ex = MediaExtractor()
            out.delete()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            val inp = DataInputStream(BufferedInputStream(FileInputStream(r.file), 1 shl 16))
            try {
                ex.setDataSource(voices.path)
                ex.selectTrack(0)
                val vt = mux.addTrack(vf)
                val at = mux.addTrack(ex.getTrackFormat(0))
                mux.start(); started = true
                val buf = ByteBuffer.allocate(1 shl 20)
                val info = MediaCodec.BufferInfo()
                var vPts = -1L; var vFlags = 0; var vBytes: ByteArray? = null
                fun nextVideo() {
                    // a film the app's death cut ends in a torn sample: it, and anything past a size no frame has, is left out
                    vBytes = try { vPts = inp.readLong(); vFlags = inp.readInt(); val n = inp.readInt(); if (n !in 1..(4 shl 20)) null else ByteArray(n).also { inp.readFully(it) } } catch (e: EOFException) { null }
                }
                nextVideo()
                var audioLeft = true
                while (vBytes != null || audioLeft) {
                    val aPts = if (audioLeft) ex.sampleTime else Long.MAX_VALUE
                    if (audioLeft && aPts < 0) { audioLeft = false; continue }
                    val vb = vBytes
                    if (vb != null && vPts <= aPts) {
                        buf.clear(); buf.put(vb); buf.flip()
                        info.set(0, vb.size, vPts, if (vFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        mux.writeSampleData(vt, buf, info)
                        nextVideo()
                    } else {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { audioLeft = false; continue }
                        info.set(0, n, aPts, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        mux.writeSampleData(at, buf, info)
                        ex.advance()
                    }
                }
            } finally {
                runCatching { inp.close() }
                ex.release()
                runCatching { if (started) mux.stop() }; mux.release()
            }
            log("film joined frames=" + r.frames)
            return true
        } catch (e: Exception) {
            log("film join fail " + e.javaClass.simpleName + " " + e.message)
            out.delete()
            return false
        } finally { gone(r) }
    }

    private fun log(s: String) { Log.d("Montana", "call record: " + s) }
}
