package quest.montana.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.telecom.CallAttributes
import android.telecom.CallAudioState
import android.telecom.CallControl
import android.telecom.CallControlCallback
import android.telecom.CallEndpoint
import android.telecom.CallEventCallback
import android.telecom.CallException
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import org.json.JSONArray
import java.security.SecureRandom
import java.util.function.Consumer

/**
 * THE SYSTEM'S OWN CALL LIST (iOS MTSystemCalls: CallKit's provider, includesCallsInRecents for a pair call): every pair call of
 * ours is also the system's own self-managed call (Telecom), so it lands in the phone's call log -- by the caller's name and a
 * one-time token, never the conversation's link (iOS MontanaCallHandle) -- and «call back» there comes back here by the token.
 * The ring, the screen and the voice stay ours: the system's call mirrors the call's life (ringing, dialling, talking, ended),
 * and a system that will not hold it (a refusal, another app's call) leaves our call exactly as it was. From Android 14 the call
 * rides the platform's own road for a calling app (TelecomManager.addCall) -- the only one Android 17 writes into its integrated
 * call log; below, a self-managed ConnectionService, which Android 10 to 13 write by EXTRA_LOG_SELF_MANAGED_CALLS.
 */
object SystemCall {
    private const val ACCOUNT = "montana"
    private const val ACCOUNT_TRANSACT = "montana-calls"
    private const val TOKENS = "callHandleTokens"
    private const val CAP = 256   // iOS MontanaCallHandle.cap: as many entries as the call log itself keeps
    private const val SCHEME = "montana"
    const val EXTRA_SEED = "montana.call.seed"
    const val EXTRA_CALL_BACK = "montana.call.back"
    // Android 17 (API 37) TelecomManager names, written out: this build compiles against API 36
    const val ACTION_CALL_BACK = "android.telecom.action.CALL_BACK"
    const val EXTRA_CALL_BACK_NUMBER = "android.telecom.extra.CALL_BACK_NUMBER"
    const val EXTRA_UUID = "android.telecom.extra.UUID"

    private class Want(val ref: String, val token: String, val name: String, val video: Boolean, val incoming: Boolean)
    private val wanted = HashMap<String, Want>()   // seed -> the call the system has not asked for yet
    private val held = HashMap<String, Line>()     // seed -> the system's call
    private val over = HashMap<String, Int>()      // seed -> how a call ended before the system asked for it

    /** From Android 10 a self-managed call may be written to the call log (EXTRA_LOG_SELF_MANAGED_CALLS); below, nothing. */
    private val on get() = Build.VERSION.SDK_INT >= 29
    private val modern get() = Build.VERSION.SDK_INT >= 34

    // the platform's road (Android 14+) refuses an account that names a ConnectionService: its account names the app's page
    private fun handle(c: Context) = if (modern) PhoneAccountHandle(ComponentName(c, MainActivity::class.java), ACCOUNT_TRANSACT)
        else PhoneAccountHandle(ComponentName(c, CallsService::class.java), ACCOUNT)
    private fun telecom(c: Context) = c.getSystemService(TelecomManager::class.java)
    private fun address(token: String): Uri = Uri.fromParts(SCHEME, token, null)

    /** The account the calls ride under, the app's own name on it; registered again at every use (cheap, and survives a clear). */
    private fun account(c: Context): PhoneAccountHandle? = runCatching {
        val h = handle(c)
        telecom(c)?.registerPhoneAccount(PhoneAccount.builder(h, c.getString(R.string.app_name))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING or
                PhoneAccount.CAPABILITY_VIDEO_CALLING or (if (modern) PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS else 0))
            .addSupportedUriScheme(SCHEME)
            .setExtras(Bundle().apply { putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true) })
            .build())
        h
    }.onFailure { log("account: " + it.message) }.getOrNull()

    /** A ring here (Calls.ring): the system is told of it; its own screen is ours (onShowIncomingCallUi). */
    fun incoming(seed: String, ref: String, name: String, video: Boolean) {
        if (!on) return
        val c = Book.ctx
        val h = account(c) ?: return
        val token = token(ref)
        if (modern) return Transact.add(c, h, seed, Want(ref, token, name, video, incoming = true))
        synchronized(this) { wanted[seed] = Want(ref, token, name, video, incoming = true) }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address(token))
            putString(EXTRA_SEED, seed)
            if (video) putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
        }
        runCatching { telecom(c)?.addNewIncomingCall(h, extras) }.onFailure { forget(seed); log("incoming: " + it.message) }
    }

    /** A call of ours (Calls.dial): the system dials it with us. */
    fun outgoing(seed: String, ref: String, name: String, video: Boolean) {
        if (!on) return
        val c = Book.ctx
        val h = account(c) ?: return
        val token = token(ref)
        if (modern) return Transact.add(c, h, seed, Want(ref, token, name, video, incoming = false))
        synchronized(this) { wanted[seed] = Want(ref, token, name, video, incoming = false) }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_SEED, seed) })
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
        }
        runCatching { telecom(c)?.placeCall(address(token), extras) }.onFailure { forget(seed); log("outgoing: " + it.message) }
    }

    /** Both ends hold the call: its talk begins here, and the call log counts it from now. */
    fun talking(seed: String) = MainThread.post {
        if (modern) return@post Transact.talking(seed)
        val line = synchronized(this) { held[seed] } ?: return@post
        line.setActive()
        CallLine.reassertRoute("system active")   // the system's call takes its own route at its activation
    }

    /**
     * WHERE THE SOUND GOES WHILE THE SYSTEM HOLDS THE CALL: Telecom owns the route of its own call and takes back a route set
     * beside it on the AudioManager (the loudspeaker button did nothing from 645 on), so the way is asked of the system itself --
     * the call's endpoint from Android 14 (CallControl.requestCallEndpointChange), the Connection's audio route below. The way is
     * named by its AudioDeviceInfo type (the earpiece for «off»); a device's own name picks among several of a kind.
     */
    fun routeTo(type: Int, name: String? = null) = MainThread.post {
        if (!on) return@post
        if (modern) return@post Transact.routeTo(type, name)
        val line = synchronized(this) { held.values.firstOrNull() } ?: return@post
        val route = when (endpointOf(type)) {
            CallEndpoint.TYPE_SPEAKER -> CallAudioState.ROUTE_SPEAKER
            CallEndpoint.TYPE_BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            CallEndpoint.TYPE_WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
            else -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
        }
        @Suppress("DEPRECATION") line.setAudioRoute(route)
        log("system_call route=" + route)
    }

    // the system's kind of way for a device of the phone's (CallEndpoint's types; their values are constants, read on any Android)
    private fun endpointOf(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallEndpoint.TYPE_SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> CallEndpoint.TYPE_BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> CallEndpoint.TYPE_WIRED_HEADSET
        else -> CallEndpoint.TYPE_EARPIECE
    }

    /** The call ended (Calls.end): the system's call ends with it, by its own cause -- missed, declined, ours or theirs. */
    fun end(seed: String, incoming: Boolean, answered: Boolean, declined: Boolean, byUs: Boolean) {
        if (!on) return
        val code = when {
            incoming && !answered && declined -> DisconnectCause.REJECTED
            incoming && !answered -> DisconnectCause.MISSED
            byUs -> DisconnectCause.LOCAL
            else -> DisconnectCause.REMOTE
        }
        MainThread.post {
            if (modern) return@post Transact.end(seed, code)
            val line = synchronized(this) {
                held.remove(seed) ?: run { if (wanted.containsKey(seed)) over[seed] = code; null }
            }
            line?.let { it.setDisconnected(DisconnectCause(code)); it.destroy() }
        }
    }

    private fun forget(seed: String) = synchronized(this) { wanted.remove(seed); over.remove(seed) }

    // ── the tokens (iOS MontanaCallHandle): a fresh one per call, the way back from the call log, kept sealed on this device ──

    private fun rows(): JSONArray = DeviceVault.get(TOKENS)?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONArray()

    private fun token(ref: String): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { remember(it, ref) }

    /** A key the call log may hand back -- the row's token, or the system's own id of the call (Android 17's EXTRA_UUID). */
    @Synchronized private fun remember(key: String, ref: String) {
        val old = rows()
        val m = JSONArray().put(JSONArray().put(key).put(ref))
        for (i in 0 until minOf(old.length(), CAP - 1)) m.put(old.get(i))
        DeviceVault.set(TOKENS, m.toString().toByteArray(Charsets.UTF_8))
    }

    /** The conversation a call-log row's token stands for; null for a token this device never gave. */
    @Synchronized fun conv(token: String): String? {
        val m = rows()
        for (i in 0 until m.length()) m.optJSONArray(i)?.takeIf { it.optString(0) == token }?.let { return it.optString(1).ifEmpty { null } }
        return null
    }

    // ── what the system asks ──

    internal fun created(request: ConnectionRequest?, incoming: Boolean): Connection {
        val extras = request?.extras
        val seed = extras?.getString(EXTRA_SEED) ?: extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString(EXTRA_SEED)
        val w = seed?.let { synchronized(this) { wanted.remove(it) } }
        if (seed == null || w == null) {
            // «CALL BACK» FROM THE CALL LOG (iOS INStartCallIntent from Recents): the row's token names the conversation; the app
            // calls it as its own call -- this request of the system's is withdrawn, the new call stands for it
            if (!incoming) request?.address?.schemeSpecificPart?.let(::conv)?.let { callBack(it) }
            return Connection.createCanceledConnection()
        }
        val line = Line(seed)
        line.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        line.connectionCapabilities = Connection.CAPABILITY_MUTE
        line.audioModeIsVoip = true
        line.setAddress(address(w.token), TelecomManager.PRESENTATION_ALLOWED)
        line.setCallerDisplayName(w.name, TelecomManager.PRESENTATION_ALLOWED)
        line.videoState = if (w.video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        if (incoming) line.setRinging() else line.setDialing()
        val ended = synchronized(this) { over.remove(seed) ?: run { held[seed] = line; null } }
        if (ended != null) { line.setDisconnected(DisconnectCause(ended)); line.destroy() }   // the call ended before the system asked
        log("system_call " + (if (incoming) "in" else "out") + " epoch=" + Calls.epochOf(seed).take(8) + (if (ended != null) " ended" else ""))
        return line
    }

    internal fun failed(request: ConnectionRequest?) {
        val seed = request?.extras?.let { it.getString(EXTRA_SEED) ?: it.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString(EXTRA_SEED) }
        seed?.let(::forget)
        log("system_call refused")   // our own call goes on as it was, outside the system's list
    }

    private fun callBack(ref: String) {
        val c = Book.ctx
        runCatching { c.startActivity(Intent(c, MainActivity::class.java).putExtra(EXTRA_CALL_BACK, NoticeAlias.of(ref)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        log("call_back from the call log")
    }

    /** THE PLATFORM'S ROAD FOR A CALLING APP (Android 14+, TelecomManager.addCall): the call is the system's from its birth, its
     *  answer, talk and end said to it through its CallControl; a call that ended before the system answered is ended at once. */
    @android.annotation.TargetApi(34)
    private object Transact {
        private val controls = HashMap<String, CallControl>()   // seed -> the system's hold on the call
        private val calls = HashMap<String, Want>()   // seed -> its direction and kind
        private val pending = HashSet<String>()   // asked for, not answered yet
        private val over = HashMap<String, Int>()   // ended while pending
        private val live = HashSet<String>()        // talking while pending
        private val quiet = object : OutcomeReceiver<Void, CallException> {
            override fun onResult(r: Void?) {}
            override fun onError(e: CallException) { log("system_call step refused code=" + e.code) }
        }

        fun add(c: Context, h: PhoneAccountHandle, seed: String, w: Want) {
            val attrs = CallAttributes.Builder(h, if (w.incoming) CallAttributes.DIRECTION_INCOMING else CallAttributes.DIRECTION_OUTGOING,
                w.name, address(w.token)).setCallType(if (w.video) CallAttributes.VIDEO_CALL else CallAttributes.AUDIO_CALL).build()
            synchronized(this) { pending.add(seed); calls[seed] = w }
            val main = c.mainExecutor
            runCatching {
                telecom(c)?.addCall(attrs, main, object : OutcomeReceiver<CallControl, CallException> {
                    override fun onResult(control: CallControl) {
                        remember(control.callId.toString(), w.ref)
                        val ended: Int?; val talk: Boolean
                        synchronized(this@Transact) {
                            pending.remove(seed); ended = over.remove(seed); talk = live.remove(seed)
                            if (ended == null) controls[seed] = control else calls.remove(seed)
                        }
                        if (ended != null) control.disconnect(DisconnectCause(ended), main, quiet) else if (talk) activate(seed, control)
                        else CallLine.reassertRoute("system call")   // a loudspeaker chosen while the system was asked stands
                        log("system_call " + (if (w.incoming) "in" else "out") + " epoch=" + Calls.epochOf(seed).take(8) + (if (ended != null) " ended" else ""))
                    }
                    override fun onError(e: CallException) {
                        synchronized(this@Transact) { pending.remove(seed); over.remove(seed); live.remove(seed); calls.remove(seed) }
                        log("system_call refused code=" + e.code)   // our own call goes on as it was, outside the system's list
                    }
                }, Hands(seed), Events)
            }.onFailure { synchronized(this) { pending.remove(seed); calls.remove(seed) }; log("system_call: " + it.message) }
        }

        fun talking(seed: String) {
            val control = synchronized(this) { controls[seed] ?: run { if (seed in pending) live.add(seed); null } } ?: return
            activate(seed, control)
        }

        private fun activate(seed: String, control: CallControl) {
            val main = Book.ctx.mainExecutor
            val w = synchronized(this) { calls[seed] } ?: return
            // the system's call takes its own route at its activation: the way chosen before it is asked again once it stands
            val stood = object : OutcomeReceiver<Void, CallException> {
                override fun onResult(r: Void?) { CallLine.reassertRoute("system active") }
                override fun onError(e: CallException) { log("system_call step refused code=" + e.code) }
            }
            if (w.incoming) control.answer(if (w.video) CallAttributes.VIDEO_CALL else CallAttributes.AUDIO_CALL, main, stood) else control.setActive(main, stood)
        }

        fun routeTo(type: Int, name: String?) {
            val control = synchronized(this) { controls.values.firstOrNull() } ?: return
            val kind = endpointOf(type)
            val all = Events.endpoints
            val same = all.filter { it.endpointType == kind }
            val e = same.firstOrNull { name != null && it.endpointName.toString() == name } ?: same.firstOrNull()
                ?: (if (kind == CallEndpoint.TYPE_EARPIECE) all.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET } else null)
                ?: return log("system_call route kind=" + kind + " absent of " + all.size)
            if (Events.current?.identifier == e.identifier) return
            control.requestCallEndpointChange(e, Book.ctx.mainExecutor, object : OutcomeReceiver<Void, CallException> {
                override fun onResult(r: Void?) { log("system_call route kind=" + kind) }
                override fun onError(err: CallException) { log("system_call route kind=" + kind + " refused code=" + err.code) }
            })
        }

        fun end(seed: String, code: Int) {
            val control = synchronized(this) {
                calls.remove(seed)
                controls.remove(seed) ?: run { if (seed in pending) over[seed] = code; null }
            } ?: return
            control.disconnect(DisconnectCause(code), Book.ctx.mainExecutor, quiet)
        }

        /** The system's own hands on our call: a headset's or a car's «answer», «decline», «hang up». */
        private class Hands(val seed: String) : CallControlCallback {
            override fun onSetActive(done: Consumer<Boolean>) = done.accept(true)
            override fun onSetInactive(done: Consumer<Boolean>) = done.accept(false)   // no hold: the call stays as it is
            override fun onAnswer(videoType: Int, done: Consumer<Boolean>) { done.accept(true); Calls.systemAnswer(seed) }
            override fun onDisconnect(cause: DisconnectCause, done: Consumer<Boolean>) {
                synchronized(Transact) { Transact.controls.remove(seed) }
                done.accept(true)
                Calls.systemEnd(seed)
            }
            override fun onCallStreamingStarted(done: Consumer<Boolean>) = done.accept(false)
        }

        private object Events : CallEventCallback {
            @Volatile var current: CallEndpoint? = null
            @Volatile var endpoints: List<CallEndpoint> = emptyList()
            override fun onCallEndpointChanged(endpoint: CallEndpoint) { current = endpoint; log("system_call endpoint now=" + endpoint.endpointType) }
            override fun onAvailableCallEndpointsChanged(endpoints: List<CallEndpoint>) { this.endpoints = endpoints }
            override fun onMuteStateChanged(muted: Boolean) {}
            override fun onCallStreamingFailed(reason: Int) {}
            override fun onEvent(event: String, extras: Bundle) {}
        }
    }

    /** The system's own hands on our call: a headset's or a car's «answer», «decline», «hang up». */
    private class Line(val seed: String) : Connection() {
        override fun onShowIncomingCallUi() {}   // the ring's notification is ours already (Calls.show)
        override fun onAnswer() { Calls.systemAnswer(seed) }
        override fun onAnswer(videoState: Int) { Calls.systemAnswer(seed) }
        override fun onReject() { Calls.systemEnd(seed) }
        override fun onDisconnect() { Calls.systemEnd(seed) }
        override fun onAbort() { Calls.systemEnd(seed) }
    }

    private fun log(s: String) { Log.d("Montana", "call: " + s) }
}

/** The system's door to our calls (Telecom binds it; only the system may). */
class CallsService : ConnectionService() {
    override fun onCreate() {
        super.onCreate()
        DeviceVault.init(applicationContext)
        Prefs.init(applicationContext)
        Book.ctx = applicationContext
    }
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?) = SystemCall.created(request, incoming = true)
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?) = SystemCall.created(request, incoming = false)
    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) = SystemCall.failed(request)
    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) = SystemCall.failed(request)
}
