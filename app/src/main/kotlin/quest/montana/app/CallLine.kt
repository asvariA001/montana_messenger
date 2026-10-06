package quest.montana.app

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.Executors

/**
 * THE CALL'S VOICE (iOS MontanaCall, the callee's half: rtcConfig 1028-1111, buildPC 1113-1140, proceedAccept 2653-2698,
 * produceAndSendAnswer 2700-2790, the candidates 4910-4962 and 2368-2379; the map is docs/calls.md). «Answer» builds one
 * connection with the relay pass the doors give, takes the caller's offer, says «call-answer» with its own description tuned
 * as iOS tunes it, and the candidates go both ways on the call's lane. The microphone is one track; the loudspeaker and the
 * mute are the person's. The line closes with its call (Calls.end) and gives back the seconds it spoke, for the call's row.
 */
object CallLine {
    private const val CONNECT_MS = 60_000L   // iOS armHardTimeout: an answered call that never connects ends in a minute
    private const val PASS = "turnPass"   // iOS rememberTurnPass: {u, c, uris, stun}; the name is the pass's own moment

    private class Line(val ref: String, val seed: String, val pc: PeerConnection, val source: AudioSource, val track: AudioTrack) {
        val out = ArrayList<IceCandidate>()
        var batching = false
        var flushes = 0
        var remoteSet = false
        @Volatile var connectedAt = 0L
    }

    // the connection's own queue (iOS: the call machine's serial queue) — the order of the words holds, no screen waits on it
    private val work = Executors.newSingleThreadExecutor()
    private val gate = Any()
    private var line: Line? = null
    private val waiting = ArrayList<Pair<String, IceCandidate>>()   // the caller's candidates that came before the line stood
    private var focus: AudioFocusRequest? = null
    @Volatile var muted = false
        private set
    @Volatile var speaker = false
        private set

    /** The moment the voice joined, for the call's clock; 0 — not yet. */
    val connectedAt: Long get() = synchronized(gate) { line?.connectedAt ?: 0L }

    /** «Answer» with the offer in hand (iOS proceedAccept): the line is built on its own queue, the answer leaves at once. */
    fun answer(ref: String, seed: String, offer: String) = work.execute {
        if (synchronized(gate) { line != null }) return@execute
        val f = CallEngine.factory ?: return@execute run { log("no engine"); Calls.hangUp("engine-missing") }
        val cfg = PeerConnection.RTCConfiguration(servers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL   // iOS 15.13: both roads at once, the relay offered first
        }
        var made: Line? = null
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { made?.let { gathered(it, c) } }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { made?.let { state(it, s) } }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(cs: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return@execute run { log("no connection"); Calls.hangUp("connection-unbuilt") }
        // iOS buildPC: one voice track «mt_audio» in the stream «mt_stream», the person's mute applied to it at birth
        val source = f.createAudioSource(MediaConstraints())
        val track = f.createAudioTrack("mt_audio", source)
        track.setEnabled(!muted)
        pc.addTrack(track, listOf("mt_stream"))
        val l = Line(ref, seed, pc, source, track)
        made = l
        synchronized(gate) { line = l }
        audioOn()
        MainThread.later(CONNECT_MS) { if (synchronized(gate) { line === l } && l.connectedAt == 0L) Calls.hangUp("connect-timeout") }
        pc.setRemoteDescription(sdp(onSet = {
            l.remoteSet = true
            drain(l)
            pc.createAnswer(sdp(onMade = { d ->
                val tuned = SessionDescription(d.type, tune(d.description))
                // an answer without its own accepted description is not an answer (iOS 2729): the call ends, nothing is sent
                pc.setLocalDescription(sdp(onSet = { sendAnswer(l, tuned.description, 0) },
                    onFail = { e -> log("own description refused: " + e); Calls.hangUp("answer-refused-here") }), tuned)
            }, onFail = { e -> log("answer unbuilt: " + e); Calls.hangUp("answer-unbuilt") }), MediaConstraints())
        }, onFail = { e -> log("offer refused: " + e); Calls.hangUp("offer-refused") }), SessionDescription(SessionDescription.Type.OFFER, offer))
    }

    /** The observer of one step of the descriptions; every callback goes back onto the line's queue. */
    private fun sdp(onSet: () -> Unit = {}, onMade: (SessionDescription) -> Unit = {}, onFail: (String) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { work.execute { onMade(d) } }
        override fun onSetSuccess() { work.execute { onSet() } }
        override fun onCreateFailure(e: String?) { work.execute { onFail(e ?: "") } }
        override fun onSetFailure(e: String?) { work.execute { onFail(e ?: "") } }
    }

    // iOS tuneSDP: the voice's settings ride its FEC line; 40 kbit/s, the rate iOS gives a far side that is not «premium»
    private fun tune(sdp: String) = sdp.replace("useinbandfec=1", "useinbandfec=1;stereo=0;maxaveragebitrate=40000;maxplaybackrate=48000;cbr=0")

    // iOS myCaps, as this build is: SFrame off, no rejoin, no video yet
    private fun caps() = JSONObject().put("tier", "android-native").put("ver", 1).put("opus_max", 40000).put("hw_aec", true)
        .put("sframe", false).put("av", false).put("rejoin", false)

    private fun word(l: Line, ctrl: String) =
        JSONObject().put("t", "cal").put("ctrl", ctrl).put("ts", System.currentTimeMillis()).put("e", Calls.epochOf(l.seed))

    private fun say(l: Line, words: JSONArray) = Signal.postWord(l.ref, Calls.epochOf(l.seed), words.toString().toByteArray())

    /** The answer travels an unreliable road: said again every two seconds until the voice joins (iOS answerResendTimer). */
    private fun sendAnswer(l: Line, sdp: String, n: Int) {
        if (synchronized(gate) { line !== l } || l.connectedAt != 0L) return
        say(l, JSONArray().put(word(l, "call-answer").put("sdp", JSONObject().put("type", "answer").put("sdp", sdp)).put("caps", caps())))
        if (n == 0) log("answer sent")
        if (n < 15) MainThread.later(2000) { work.execute { sendAnswer(l, sdp, n + 1) } }
    }

    // ── the candidates ──

    /** Our candidates: the first bundle leaves at once, the rest in batches of 200 ms (iOS 4916-4936), one word each (E2E 1090). */
    private fun gathered(l: Line, c: IceCandidate) = work.execute {
        if (synchronized(gate) { line !== l }) return@execute
        l.out.add(c)
        if (l.batching) return@execute
        l.batching = true
        MainThread.later(if (l.flushes == 0) 0L else 200L) { work.execute { flush(l) } }
    }

    private fun flush(l: Line) {
        l.batching = false
        if (l.out.isEmpty() || synchronized(gate) { line !== l }) { l.out.clear(); return }
        val words = JSONArray()
        for (c in l.out) words.put(word(l, "call-ice").put("candidate",
            JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
        l.out.clear()
        l.flushes++
        say(l, words)
    }

    /** The caller's candidates (iOS «call-ice» 2368): into the line once it holds the offer, kept until then. */
    fun remoteIce(ref: String, epoch: String, cs: List<IceCandidate>) = work.execute {
        val l = synchronized(gate) { line?.takeIf { it.ref == ref && Calls.epochOf(it.seed) == epoch } }
        if (l != null && l.remoteSet) { cs.forEach { l.pc.addIceCandidate(it) }; return@execute }
        synchronized(gate) {
            cs.forEach { waiting.add(epoch to it) }
            while (waiting.size > 64) waiting.removeAt(0)
        }
    }

    private fun drain(l: Line) {
        val e = Calls.epochOf(l.seed)
        val kept = synchronized(gate) { waiting.filter { it.first == e }.also { waiting.clear() } }
        kept.forEach { l.pc.addIceCandidate(it.second) }
        if (kept.isNotEmpty()) log("candidates kept: " + kept.size)
    }

    private fun state(l: Line, s: PeerConnection.IceConnectionState) = work.execute {
        if (synchronized(gate) { line !== l }) return@execute
        log("ice " + s)
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> if (l.connectedAt == 0L) {
                l.connectedAt = System.currentTimeMillis()
                CallService.sync()
            }
            PeerConnection.IceConnectionState.FAILED -> Calls.hangUp("ice-failed")
            // a break that does not heal in fifteen seconds ends the call; the restart of the checks is a later step
            PeerConnection.IceConnectionState.DISCONNECTED -> MainThread.later(15_000) {
                if (synchronized(gate) { line === l }) work.execute {
                    if (l.pc.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) Calls.hangUp("ice-lost")
                }
            }
            else -> {}
        }
    }

    /** The call ended (Calls.end): the line closes, the phone's sound is given back, and the seconds it spoke are returned. */
    fun stop(seed: String): Int {
        val l = synchronized(gate) {
            waiting.clear()
            line?.takeIf { it.seed == seed }?.also { line = null }
        } ?: return 0
        work.execute {
            runCatching { l.pc.dispose() }
            runCatching { l.source.dispose() }
        }
        audioOff()
        val at = l.connectedAt
        return if (at == 0L) 0 else ((System.currentTimeMillis() - at) / 1000).toInt()
    }

    // ── the relay pass (iOS rtcConfig 1045-1109, WakePush fetchTurnCred 1467) ──

    private fun servers(): List<PeerConnection.IceServer> {
        val p = pass() ?: return emptyList<PeerConnection.IceServer>().also { log("NO ICE SERVERS — host-only call") }
        val list = ArrayList<PeerConnection.IceServer>()
        val stun = strings(p.optJSONArray("stun"))
        if (stun.isNotEmpty()) list.add(PeerConnection.IceServer.builder(stun).createIceServer())
        // iOS turnRank: the TLS door on 443 first — it survives dead UDP and DPI — then the other TLS, tcp, udp
        val uris = strings(p.optJSONArray("uris")).sortedBy { u ->
            if (u.startsWith("turns:")) (if (u.contains(":443")) 0 else 1) else if (u.contains("transport=tcp")) 2 else 3
        }
        if (uris.isNotEmpty()) list.add(PeerConnection.IceServer.builder(uris).setUsername(p.optString("u")).setPassword(p.optString("c")).createIceServer())
        log("servers stun=" + stun.size + " relay=" + uris.size)
        return list
    }

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optString(it)?.ifEmpty { null } }

    /** iOS turnPassLive: a pass whose name is its moment lives while ten minutes of it remain. */
    private fun live(p: JSONObject) = p.optString("u").toDoubleOrNull()?.let { it - System.currentTimeMillis() / 1000.0 > 600 } ?: true

    /** The remembered pass, else a fresh one from every door at once — the first answer wins (iOS fetchTurnCred). */
    private fun pass(): JSONObject? {
        runCatching { JSONObject(Prefs.str(PASS, "")) }.getOrNull()?.takeIf { it.optJSONArray("uris") != null && live(it) }?.let { return it }
        val got = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        for (door in MontanaCard.doors) Thread {
            val (code, text) = Wire.post(door, "/turn-cred", JSONObject(), 6000)
            val o = if (code == 200 && text != null) runCatching { JSONObject(text) }.getOrNull() else null
            val uris = o?.optJSONArray("uris")
            if (o != null && uris != null && uris.length() > 0 && o.optString("username").isNotEmpty())
                got.offer(JSONObject().put("u", o.optString("username")).put("c", o.optString("credential")).put("uris", uris)
                    .put("stun", o.optJSONArray("stun") ?: JSONArray()))
        }.start()
        val p = got.poll(4, java.util.concurrent.TimeUnit.SECONDS) ?: return null   // iOS: four seconds at most on a cold start
        Prefs.setStr(PASS, p.toString())
        return p
    }

    // ── the phone's sound (iOS configureAudioSession, setSpeaker 974, toggleMute 3131) ──

    private fun am() = Book.ctx.getSystemService(AudioManager::class.java)

    private fun audioOn() {
        val am = am() ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        route(speaker)
    }

    private fun audioOff() {
        val am = am() ?: return
        route(false)
        am.mode = AudioManager.MODE_NORMAL
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        speaker = false
        muted = false
    }

    /** The loudspeaker, or whatever the system holds for a call (the earpiece, the headset): its own communication device. */
    fun setSpeaker(on: Boolean) { speaker = on; route(on) }

    @Suppress("DEPRECATION")
    private fun route(on: Boolean) {
        val am = am() ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val loud = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (on && loud != null) am.setCommunicationDevice(loud) else am.clearCommunicationDevice()
        } else am.isSpeakerphoneOn = on
    }

    /** The microphone goes quiet on its one track; the connection stays. */
    fun setMuted(on: Boolean) {
        muted = on
        val l = synchronized(gate) { line } ?: return
        work.execute { runCatching { l.track.setEnabled(!on) } }
    }

    private fun log(s: String) { Log.d("Montana", "call line: " + s) }
}
