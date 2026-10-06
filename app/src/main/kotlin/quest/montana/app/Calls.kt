package quest.montana.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * THE CALL MACHINE, ITS FIRST HALF (iOS MontanaCall; the map is docs/calls.md). A call of the iPhone is heard here — by its
 * ring letter in the box (RG) or by its words on the node's lane — and rings as the system's call (iOS CallKit; here the
 * call's own notification). The caller is told «ringing», a decline is told «call-end», a call that ends unanswered leaves its
 * row, and the caller's «missed» letter (MC) lays the row of a call this phone never saw. The voice, and the answer that
 * carries it, are the next step of Anna's plan: «Answer» opens the conversation meanwhile.
 */
object Calls {
    const val LIFE_MS = 90_000L   // iOS callLifeS: the ring, the age of an offer, the life of a call letter — one number
    const val EXTRA_REF = "montana.call.ref"
    const val EXTRA_ANSWER = "montana.call.answer"
    private const val CHANNEL = "calls"
    private const val NOTE = 0x4d43   // one incoming call stands on the screen at a time
    private const val SEEDS = "callSeeds"   // seed -> {st, at}: «alive» rang here, «dead» is over (iOS MontanaMissedCall's book, burySeed)

    private class Ring(val ref: String, val seed: String, val video: Boolean) {
        var offer: String? = null   // the caller's description, kept for the answer of step 4
        var answered = false
    }
    private val gate = Any()
    private var ring: Ring? = null

    /** iOS callEpoch: the call's name on every word is its seed's first sixteen letters. */
    fun epochOf(seed: String) = seed.take(16)

    // ── what the iPhone says ──

    /** iOS ChatStore ringMark: a call letter raises the incoming call; its age and the graveyard cut the stale; no row. */
    fun ringLetter(ref: String, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val t = (o.optDouble("t", 0.0) * 1000).toLong()
        if (t > 0 && System.currentTimeMillis() - t > LIFE_MS) return log("ring letter stale")
        incoming(ref, o.optString("s"), o.optBoolean("v"), o.optString("n"), null)
    }

    /** The words of a call on the node's lane (iOS handleMeshCallSignal, handleSignal): its birth, and the end of a ring here. */
    fun hear(ref: String, epoch: String, payload: ByteArray) {
        val words = runCatching { JSONArray(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        for (k in 0 until words.length()) {
            val w = words.optJSONObject(k) ?: continue
            when (val ctrl = w.optString("ctrl")) {
                "call" -> {
                    val ts = w.optLong("ts")
                    if (ts > 0 && System.currentTimeMillis() - ts > LIFE_MS) { log("call word stale"); continue }
                    incoming(ref, w.optString("s"), w.optBoolean("video"), w.optString("n"), w.optJSONObject("sdp")?.optString("sdp"))
                }
                "call-end", "call-gone" -> {
                    // «call-gone» names its call in «s» (iOS 24.09); every other word in «e», else by the lane's own epoch
                    val named = if (ctrl == "call-gone") epochOf(w.optString("s")) else w.optString("e").ifEmpty { epoch }
                    synchronized(gate) { ring?.takeIf { it.ref == ref && epochOf(it.seed) == named } }?.let { end(it, ctrl) }
                }
                else -> {}   // the rest of a call's words wait for the voice (step 4)
            }
        }
    }

    /**
     * THE BIRTH OF AN INCOMING CALL (iOS handleSignalBody «call»): a dead seed is nobody, the same seed is the same call, and
     * a seedless word is an offer for the call in hand, never a birth.
     */
    private fun incoming(ref: String, seed: String, video: Boolean, name: String, offer: String?) {
        if (PeerSafety.isBlocked(ref)) return log("blocked caller")   // a blocked person's call is not assembled (iOS 15.09)
        val fresh = synchronized(gate) {
            val r = ring
            if (seed.isEmpty()) { if (r != null && r.ref == ref && r.offer == null) r.offer = offer; return }
            if (seedState(seed) == "dead") return log("dead seed")
            if (r != null && r.seed != seed) return log("busy: another call rings")
            if (r != null) { if (r.offer == null) r.offer = offer; false }
            else { ring = Ring(ref, seed, video).also { it.offer = offer }; true }
        }
        say(ref, seed, "call-ringing")   // the caller hears that it rings here (iOS 2218), for the birth and for each copy
        if (!fresh) return
        noteSeed(seed, "alive")
        if (name.isNotBlank() && name.length <= 64) Book.edit(ref) { it.name = name.trim() }   // iOS onCallerNamed
        Signal.hold(ref, "call")
        show(ref, video)
        MainThread.later(LIFE_MS) { synchronized(gate) { ring?.takeIf { it.seed == seed } }?.let { end(it, "timeout") } }
        log("ring video=" + video)
    }

    // ── what this phone says ──

    /** «Decline» (iOS CXEndCallAction on a ringing call): the caller is told; the row stays — a declined call is a missed one. */
    fun decline() {
        val r = synchronized(gate) { ring } ?: return
        say(r.ref, r.seed, "call-end")
        end(r, "declined")
    }

    /** «Answer»: the ring stops here. The voice that answers the caller is step 4 — the conversation opens meanwhile. */
    fun answer() {
        synchronized(gate) { ring }?.answered = true
        hide()
    }

    private fun say(ref: String, seed: String, ctrl: String) {
        val w = JSONObject().put("t", "cal").put("ctrl", ctrl).put("ts", System.currentTimeMillis()).put("e", epochOf(seed))
        Signal.postWord(ref, epochOf(seed), JSONArray().put(w).toString().toByteArray())
    }

    private fun end(r: Ring, why: String) {
        synchronized(gate) { if (ring !== r) return; ring = null }
        noteSeed(r.seed, "dead")
        Signal.release(r.ref, "call")
        hide()
        // iOS: «missed» means the person never reacted — an answer whose call did not connect is «Connection failed»
        row(r.ref, r.video, incoming = true, dur = 0, missed = !r.answered)
        if (!r.answered && why != "declined") Notify.letter(r.ref, rowText(r.video, true, 0, true))   // iOS postMissedCallBanner
        log("end " + why)
    }

    /**
     * The caller's «missed» letter (iOS DeliveryEngine missedCallMark): a call this phone saw, alive or dead, has its row
     * already; any other becomes a missed row here. The receipt is the letter's place's own.
     */
    fun missedLetter(ref: String, json: String) {
        if (PeerSafety.isBlocked(ref)) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: JSONObject()
        val seed = o.optString("s")
        if (seed.isNotEmpty() && seedState(seed) != null) return log("missed letter of a call seen here")
        noteSeed(seed, "dead")
        val video = o.optBoolean("v")
        row(ref, video, incoming = true, dur = 0, missed = true)
        Notify.letter(ref, rowText(video, true, 0, true))
    }

    // ── the call's row (iOS appendCallLog, callMark) ──

    class Info(val video: Boolean, val incoming: Boolean, val dur: Int, val missed: Boolean)

    fun info(text: String): Info? {
        if (!text.startsWith(Marks.CALL)) return null
        val o = runCatching { JSONObject(text.removePrefix(Marks.CALL)) }.getOrNull() ?: return null
        return Info(o.optBoolean("v"), o.optBoolean("inc"), o.optInt("dur"), o.optBoolean("miss"))
    }

    private fun rowText(video: Boolean, incoming: Boolean, dur: Int, missed: Boolean) =
        Marks.CALL + JSONObject().put("v", video).put("inc", incoming).put("dur", dur).put("miss", missed).toString()

    /** Each phone lays its own row of a call; the same call told twice within its life lands once (iOS «one row per call»). */
    fun row(ref: String, video: Boolean, incoming: Boolean, dur: Int, missed: Boolean) {
        val now = System.currentTimeMillis()
        Book.edit(ref) { c ->
            val last = c.msgs.lastOrNull()
            val li = last?.let { info(it.text) }
            if (last != null && li != null && li.video == video && li.incoming == incoming && li.missed == missed && now - last.at < LIFE_MS) return@edit
            c.msgs.add(Msg(Marks.mintMid(), rowText(video, incoming, dur, missed), !incoming, now, state = if (incoming) 2 else 3))
            c.msgs.sortBy { it.at }
        }
    }

    /** iOS ChatStore.listPreview for a call: the glyph and the call's word. */
    fun words(c: Context, i: Info): String = (if (i.video) "📹 " else "📞 ") + c.getString(when {
        i.missed -> if (i.incoming) R.string.call_missed else R.string.call_unanswered
        i.video -> R.string.call_video
        else -> R.string.call_voice
    })

    /** iOS MessageBubble.callBubble: the glyph, red when missed; the kind; under it the arrow, how it went and the moment. */
    fun body(c: Context, m: Msg, into: LinearLayout) {
        val i = info(m.text) ?: return
        val ink = BubbleStyle.text(m.mine)
        val accent = if (i.missed) SysColor.red else ink
        val how = when {
            i.missed -> c.getString(if (i.incoming) R.string.call_missed_short else R.string.call_no_answer)
            i.dur > 0 -> String.format(Locale.ROOT, "%d:%02d", i.dur / 60, i.dur % 60)
            else -> c.getString(R.string.call_failed)
        }
        val time = android.text.format.DateFormat.getTimeFormat(c).format(Date(m.at))
        into.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(c.icon(if (i.video) R.drawable.ic_peer_video else R.drawable.ic_peer_phone, accent, 18), lp(dp(18), dp(18)).apply { marginEnd = dp(10) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(c.getString(if (i.video) R.string.call_video else R.string.call_voice), 15f, ink, bold = true), lp(WRAP, WRAP))
                addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(c.icon(if (i.incoming) R.drawable.ic_call_received else R.drawable.ic_call_made, accent, 10), lp(dp(10), dp(10)).apply { marginEnd = dp(4) })
                    addView(c.text(how + " · " + time, 12f, BubbleStyle.time(m.mine)), lp(WRAP, WRAP))
                }, lp(WRAP, WRAP).apply { topMargin = dp(2) })
            }, lp(WRAP, WRAP))
        }, lp(WRAP, WRAP))
    }

    // ── the ring on the screen (iOS: CallKit's incoming call; here the call's own notification) ──

    private fun show(ref: String, video: Boolean) {
        val c = Book.ctx
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.app_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000)
        })
        val name = Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun page(answer: Boolean) = PendingIntent.getActivity(c, if (answer) 2 else 1,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_REF, ref).putExtra(EXTRA_ANSWER, answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val decline = PendingIntent.getBroadcast(c, 3, Intent(c, CallDecline::class.java), flags)
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(name)   // USER-DATA: the caller's name
            .setContentText(c.getString(if (video) R.string.call_video else R.string.call_voice))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(page(false))
            .setFullScreenIntent(page(false), true)
        if (Build.VERSION.SDK_INT >= 31) {
            val face = Book.shownFace(ref).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.let { Icon.createWithBitmap(it) }
            val who = Person.Builder().setName(name).setIcon(face).setImportant(true).build()
            b.setStyle(Notification.CallStyle.forIncomingCall(who, decline, page(true)).setIsVideo(video))
        } else {
            b.addAction(Notification.Action.Builder(null as Icon?, c.getString(R.string.call_decline), decline).build())
            b.addAction(Notification.Action.Builder(null as Icon?, c.getString(R.string.call_accept), page(true)).build())
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT   // the ringtone rings on until the call is answered or gone
        runCatching { nm.notify(NOTE, n) }.onFailure { log("notify: " + it.message) }
    }

    private fun hide() { Book.ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE) }

    // ── the seeds this phone has seen (iOS MontanaMissedCall's book, MontanaCall's graveyard), kept the letter queue's week ──

    private fun seeds(): JSONObject = runCatching { JSONObject(Prefs.str(SEEDS, "{}")) }.getOrNull() ?: JSONObject()
    private fun seedState(seed: String): String? = seeds().optJSONObject(seed)?.optString("st")?.ifEmpty { null }
    private fun noteSeed(seed: String, st: String) {
        if (seed.isEmpty()) return
        synchronized(gate) {
            val o = seeds(); val now = System.currentTimeMillis()
            o.put(seed, JSONObject().put("st", st).put("at", now))
            o.keys().asSequence().toList().filter { now - (o.optJSONObject(it)?.optLong("at") ?: 0L) > 7 * 86_400_000L }.forEach { o.remove(it) }
            Prefs.setStr(SEEDS, o.toString())
        }
    }

    private fun log(s: String) { Log.d("Montana", "call: " + s) }
}

/** The ring's «Decline» (Calls): the system hands the press here. */
class CallDecline : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Thread { Calls.decline() }.start() }
}
