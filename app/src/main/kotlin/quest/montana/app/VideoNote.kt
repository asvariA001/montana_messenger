package quest.montana.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * THE ROUND VIDEO NOTE (iOS MontanaVideoNoteCamera): «a square with the circle baked in» — every frame turned upright, cut to
 * its centre square, the 400-point circle drawn over a darkened copy of itself in a 480 file (iOS side 400 / square 480),
 * H.264 at the chosen quality (iOS MontanaNoteQuality: 0.9 / 1.5 / 2.5 Mbit/s), the voice in AAC; the preview IS the file —
 * the one drawing goes to the screen and to the encoder. A tap on the window turns the camera; 6:39 at most (iOS maxSeconds).
 * The platform's own pieces only: Camera2, OpenGL ES, MediaCodec, MediaMuxer, AudioRecord.
 */
class NoteRecorder(private val c: Context, private val preview: TextureView) {
    companion object {
        const val SQUARE = 480
        const val CIRCLE = 400
        const val MAX_SECONDS = 399.0
        fun bitRate() = when (Prefs.str("noteQuality", "medium")) { "low" -> 900_000; "high" -> 2_500_000; else -> 1_500_000 }
    }

    private val gl = HandlerThread("montana-note").apply { start() }
    private val h = Handler(gl.looper)
    private var egl: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var viewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var dummy: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var program = 0
    private var camTexture: SurfaceTexture? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var front = true
    private val mtx = FloatArray(16)

    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var record: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var vTrack = -1; private var aTrack = -1
    private var muxing = false
    private val muxLock = Any()
    private var startNs = 0L
    @Volatile private var running = false
    private var audioThread: Thread? = null
    lateinit var file: File; private set
    val elapsed: Double get() = if (startNs == 0L) 0.0 else (System.nanoTime() - startNs) / 1e9

    /** The tape starts: the camera, the encoders and the drawing; false when a piece refused. */
    fun start(): Boolean = try {
        file = File(c.cacheDir, "vnote_${System.currentTimeMillis()}.mp4")
        setupCodecs()
        val ready = java.util.concurrent.CountDownLatch(1)
        var ok = false
        h.post { ok = runCatching { setupGl(); openCamera(); true }.getOrDefault(false); ready.countDown() }
        ready.await()
        if (ok) { running = true; startNs = System.nanoTime(); startAudio() }
        ok
    } catch (_: Exception) { release(); false }

    private fun setupCodecs() {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SQUARE, SQUARE).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate())
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)   // the spoken word's own quality (iOS MontanaVoiceSound)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    // ── the drawing ──

    private fun setupGl() {
        egl = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(egl, v, 0, v, 1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
        val cs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(egl, attrs, 0, cs, 0, 1, n, 0)
        cfg = cs[0]
        ctx = EGL14.eglCreateContext(egl, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val vc = video!!
        encSurface = EGL14.eglCreateWindowSurface(egl, cfg, vc.createInputSurface(), intArrayOf(EGL14.EGL_NONE), 0)
        vc.start()
        preview.surfaceTexture?.let { viewSurface = EGL14.eglCreateWindowSurface(egl, cfg, Surface(it), intArrayOf(EGL14.EGL_NONE), 0) }
        EGL14.eglMakeCurrent(egl, encSurface, encSurface, ctx)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = link(VS, FS)
        camTexture = SurfaceTexture(tex).apply {
            setDefaultBufferSize(1280, 720)
            setOnFrameAvailableListener({ draw() }, h)
        }
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    @Volatile private var closing = false
    private fun draw() {
        // A frame the camera sent after the tape ended finds the drawing gone: it is let go, never drawn.
        if (closing || egl == EGL14.EGL_NO_DISPLAY) return
        val st = camTexture ?: return
        if (runCatching { st.updateTexImage() }.isFailure) return
        st.getTransformMatrix(mtx)
        fun paint(s: EGLSurface, w: Int, hh: Int) {
            EGL14.eglMakeCurrent(egl, s, s, ctx)
            GLES20.glViewport(0, 0, w, hh)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, mtx, 0)
            // The upright frame is 9:16; its centre square spans 9/16 of its height.
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCrop"), 720f / 1280f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMirror"), if (front) 1f else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRadius"), CIRCLE / 2f / SQUARE)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        if (viewSurface != EGL14.EGL_NO_SURFACE) { paint(viewSurface, preview.width, preview.height); EGL14.eglSwapBuffers(egl, viewSurface) }
        if (running) {
            paint(encSurface, SQUARE, SQUARE)
            EGLExt.eglPresentationTimeANDROID(egl, encSurface, System.nanoTime() - startNs)
            EGL14.eglSwapBuffers(egl, encSurface)
            drain(video!!, false, isVideo = true)
            if (elapsed >= MAX_SECONDS) onMax?.invoke()
        }
    }
    var onMax: (() -> Unit)? = null

    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(p)
        }
    }

    // ── the camera ──

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = c.getSystemService(CameraManager::class.java)
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } ?: cm.cameraIdList.first()
        val out = Surface(camTexture)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                cam = d
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(out), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        runCatching {
                            s.setRepeatingRequest(d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(out); set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }.build(), null, h)
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, h)
            }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, e: Int) { d.close() }
        }, h)
    }

    /** The other camera; the tape runs on through the turn (iOS flip). */
    fun flip() = h.post {
        runCatching { session?.close() }; runCatching { cam?.close() }
        front = !front
        openCamera()
    }

    // ── the voice ──

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val min = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, 48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        record = r
        val ac = audio!!
        ac.start(); r.startRecording()
        audioThread = Thread {
            val buf = ByteArray(4096)
            var samples = 0L
            while (true) {
                val n = r.read(buf, 0, buf.size)
                val stop = !running
                val i = ac.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val ib = ac.getInputBuffer(i)!!
                    ib.clear()
                    val len = if (n > 0) minOf(n, ib.remaining()) else 0
                    ib.put(buf, 0, len)
                    val pts = samples * 1_000_000L / 48_000
                    ac.queueInputBuffer(i, 0, len, pts, if (stop) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    samples += len / 2
                }
                drain(ac, stop, isVideo = false)
                if (stop) break
            }
        }.apply { start() }
    }

    // ── the file ──

    private fun drain(codec: MediaCodec, eos: Boolean, isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return else continue }
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxLock) {
                    val m = muxer ?: return
                    if (isVideo) vTrack = m.addTrack(codec.outputFormat) else aTrack = m.addTrack(codec.outputFormat)
                    if (vTrack >= 0 && aTrack >= 0 && !muxing) { m.start(); muxing = true }
                }
                continue
            }
            if (o < 0) continue
            val ob = codec.getOutputBuffer(o)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            synchronized(muxLock) {
                if (muxing && info.size > 0) { ob.position(info.offset); ob.limit(info.offset + info.size); muxer?.writeSampleData(if (isVideo) vTrack else aTrack, ob, info) }
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /** The tape ends: the file and its length, or null when dropped, too short, or nothing was written. */
    fun stop(keep: Boolean): Pair<File, Double>? {
        if (!running) { release(); return null }
        val secs = elapsed
        running = false
        runCatching { audioThread?.join(3000) }
        val done = java.util.concurrent.CountDownLatch(1)
        h.post {
            runCatching { video?.signalEndOfInputStream(); drain(video!!, true, isVideo = true) }
            // THE FILE IS CLOSED BEFORE IT IS HANDED ON: the muxer writes its index (moov) at stop, and a file read before
            // that is a heap of samples no player opens.
            synchronized(muxLock) { runCatching { if (muxing) muxer?.stop() }; runCatching { muxer?.release() }; muxer = null }
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val wrote = synchronized(muxLock) { muxing.also { muxing = false } }
        release()
        if (!keep || !wrote || secs < 1.0 || !file.exists()) { runCatching { file.delete() }; return null }
        return file to secs
    }

    private fun release() {
        closing = true
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }; audio = null
        h.post {
            runCatching { session?.close() }; runCatching { cam?.close() }
            camTexture?.setOnFrameAvailableListener(null)
            runCatching { video?.stop() }; runCatching { video?.release() }; video = null
            synchronized(muxLock) { runCatching { if (muxing) muxer?.stop() }; runCatching { muxer?.release() }; muxer = null; muxing = false }
            runCatching { camTexture?.release() }
            if (egl != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(egl, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                for (s in listOf(encSurface, viewSurface, dummy)) if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(egl, s)
                EGL14.eglDestroyContext(egl, ctx); EGL14.eglTerminate(egl)
                egl = EGL14.EGL_NO_DISPLAY
            }
            gl.quitSafely()
        }
    }

    private val VS = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
    """.trimIndent()

    // The centre square of the upright frame; the circle live, outside it the same picture darkened (iOS: over its own copy).
    private val FS = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES sTex;
        uniform mat4 uTex;
        uniform float uCrop;
        uniform float uMirror;
        uniform float uRadius;
        void main() {
            vec2 q = vUv;
            if (uMirror > 0.5) q.x = 1.0 - q.x;
            vec2 src = vec2(q.x, 0.5 + (q.y - 0.5) * uCrop);
            vec4 col = texture2D(sTex, (uTex * vec4(src, 0.0, 1.0)).xy);
            float d = distance(vUv, vec2(0.5));
            float edge = smoothstep(uRadius, uRadius - 0.004, d);
            gl_FragColor = mix(vec4(col.rgb * 0.28, 1.0), col, edge);
        }
    """.trimIndent()
}

/** THE NOTE'S RING (iOS MontanaNoteRing): the minute's progress around the window. */
class NoteRing(c: Context) : View(c) {
    var progress = 0f; set(v) { field = v; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        val w = dp(4).toFloat(); track.strokeWidth = w; bar.strokeWidth = w
        val r = RectF(w, w, width - w, height - w)
        canvas.drawOval(r, track)
        canvas.drawArc(r, -90f, 360f * progress.coerceIn(0f, 1f), false, bar)
    }
}

/** A view cut round (the note's window and the bubble's circle). */
fun View.round(): View = apply {
    outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height) }
    clipToOutline = true
}
