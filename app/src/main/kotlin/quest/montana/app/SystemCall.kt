package quest.montana.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

/**
 * THE SYSTEM'S OWN CALL LIST (iOS MTSystemCalls: CallKit's provider, includesCallsInRecents for a pair call): every pair call of
 * ours is also the system's own self-managed call (Telecom), so it lands in the phone's call log -- by the caller's name and a
 * one-time token, never the conversation's link (iOS MontanaCallHandle) -- and «call back» there comes back here by the token.
 * The ring, the screen and the voice stay ours: the system's call mirrors the call's life (ringing, dialling, talking, ended),
 * and a system that will not hold it (a refusal, another app's call) leaves our call exactly as it was.
 */
object SystemCall {
    private const val ACCOUNT = "montana"
    private const val TOKENS = "callHandleTokens"
    private const val CAP = 256   // iOS MontanaCallHandle.cap: as many entries as the call log itself keeps
    private const val SCHEME = "montana"
    const val EXTRA_SEED = "montana.call.seed"
    const val EXTRA_CALL_BACK = "montana.call.back"
    // Android 17 (API 37) TelecomManager names, written out: this build compiles against API 36
    const val ACTION_CALL_BACK = "android.telecom.action.CALL_BACK"
    const val EXTRA_CALL_BACK_NUMBER = "android.telecom.extra.CALL_BACK_NUMBER"
    const val EXTRA_UUID = "android.telecom.extra.UUID"

    private class Want(val ref: String, val token: String, val name: String, val video: Boolean)
    private val wanted = HashMap<String, Want>()   // seed -> the call the system has not asked for yet
    private val held = HashMap<String, Line>()     // seed -> the system's call
    private val over = HashMap<String, Int>()      // seed -> how a call ended before the system asked for it

    /** From Android 10 a self-managed call may be written to the call log (EXTRA_LOG_SELF_MANAGED_CALLS); below, nothing. */
    private val on get() = Build.VERSION.SDK_INT >= 29

    private fun handle(c: Context) = PhoneAccountHandle(ComponentName(c, CallsService::class.java), ACCOUNT)
    private fun telecom(c: Context) = c.getSystemService(TelecomManager::class.java)
    private fun address(token: String): Uri = Uri.fromParts(SCHEME, token, null)

    /** The account the calls ride under, the app's own name on it; registered again at every use (cheap, and survives a clear). */
    private fun account(c: Context): PhoneAccountHandle? = runCatching {
        val h = handle(c)
        telecom(c)?.registerPhoneAccount(PhoneAccount.builder(h, c.getString(R.string.app_name))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING or
                PhoneAccount.CAPABILITY_VIDEO_CALLING)
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
        synchronized(this) { wanted[seed] = Want(ref, token, name, video) }
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
        synchronized(this) { wanted[seed] = Want(ref, token, name, video) }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_SEED, seed) })
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
        }
        runCatching { telecom(c)?.placeCall(address(token), extras) }.onFailure { forget(seed); log("outgoing: " + it.message) }
    }

    /** Both ends hold the call: its talk begins here, and the call log counts it from now. */
    fun talking(seed: String) = MainThread.post { synchronized(this) { held[seed] }?.setActive() }

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
            val line = synchronized(this) {
                held.remove(seed) ?: run { if (wanted.containsKey(seed)) over[seed] = code; null }
            }
            line?.let { it.setDisconnected(DisconnectCause(code)); it.destroy() }
        }
    }

    private fun forget(seed: String) = synchronized(this) { wanted.remove(seed); over.remove(seed) }

    // ── the tokens (iOS MontanaCallHandle): a fresh one per call, the way back from the call log, kept sealed on this device ──

    private fun rows(): JSONArray = DeviceVault.get(TOKENS)?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONArray()

    @Synchronized private fun token(ref: String): String {
        val tok = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val old = rows()
        val m = JSONArray().put(JSONArray().put(tok).put(ref))
        for (i in 0 until minOf(old.length(), CAP - 1)) m.put(old.get(i))
        DeviceVault.set(TOKENS, m.toString().toByteArray(Charsets.UTF_8))
        return tok
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
