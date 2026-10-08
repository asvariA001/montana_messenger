package quest.montana.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

// ─────────────────────────── the live lane and the presence ladder (iOS MontanaWakePush signal lane, E2E beacons, ChatStore ladder) ───────────────────────────

/**
 * THE SIGNAL LANE (iOS MontanaWakePush.postSignal / fetchSignals, epoch «chat»): a word that lives an instant — typing, «in
 * my chat», «in the app» — never takes a place in the letters' queue. It is sealed under the pipe's key of the minute as a
 * letter is, as peer‖0‖epoch‖0‖deflate(text), and posted to the doors' /signal under the conversation's daily label; the
 * other side holds a long question on /signal-fetch and hears it the millisecond it lands. A bare «box» on the lane says the
 * box holds a letter of ours — it is fetched at once.
 */
object Signal {
    fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)   // raw DEFLATE, as Apple's .zlib writes it (no header)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }
    private fun inflate(b: ByteArray): ByteArray? = runCatching {
        val i = Inflater(true); i.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        var guard = 0
        while (!i.finished() && guard++ < 1000) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n) }
        i.end(); out.toByteArray()
    }.getOrNull()

    /** One word on the lane to every door: per-node stores do not talk, and the other side asks whichever it asks. */
    fun post(ref: String, text: String) = postWord(ref, "chat", text.toByteArray())

    /** A word of any epoch (iOS postSignal): «chat» for the live words, a call's own epoch for its words (Calls). */
    fun postWord(ref: String, epoch: String, payload: ByteArray) {
        val twin = MontanaSeed.twin ?: return
        val secret = Book.secret(ref) ?: return
        Thread {
            val body = twin.toByteArray() + byteArrayOf(0) + epoch.toByteArray() + byteArrayOf(0) + deflate(payload)
            val sealed = Wire.seal(Wire.bodyKey(secret, Wire.minute()), body) ?: return@Thread
            val cw = Wire.convW(secret, Wire.day())
            val req = JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("env", Base64.encodeToString(sealed, Base64.NO_WRAP))
            // a chat word passes a door resting dead for me; a call's word knocks every door (iOS postSignal 3010-3033)
            val doors = if (epoch == "chat") MontanaCard.doors.filter { doorAlive(it) }.ifEmpty { MontanaCard.doors } else MontanaCard.doors
            for (door in doors) {
                val (code, _) = Wire.post(door, "/signal", req, 8000)
                if (code == 200) doorAnswered(door) else doorFailed(door, code)
            }
        }.start()
    }

    // ── the doors' life (iOS WakePush baseFailed 318-346, signalDoorAnswered 451-459, the path change 516-528) ──
    private val doorFails = HashMap<String, Int>()
    private val deathStreak = HashMap<String, Int>()
    private val deadUntil = HashMap<String, Long>()
    /** No answer, or the door's own failure: two in a row and it rests a minute, then two, four, eight at most. A refusal is an answer. */
    fun doorFailed(door: String, code: Int) {
        if (code != -1 && code < 500) { doorAnswered(door, proved = false); return }
        val now = System.currentTimeMillis()
        val rest = synchronized(deadUntil) {
            if ((deadUntil[door] ?: 0L) > now) return
            val n = (doorFails[door] ?: 0) + 1
            doorFails[door] = n
            if (n < 2) return
            doorFails[door] = 0
            val streak = minOf((deathStreak[door] ?: 0) + 1, 4)
            deathStreak[door] = streak
            (60_000L shl (streak - 1)).also { deadUntil[door] = now + it }
        }
        Log.d("Montana", "sig_door dead " + host(door) + " code=" + code + " — not asked for " + rest / 1000 + "s")
    }
    fun doorAnswered(door: String, proved: Boolean = true) {
        val revived = synchronized(deadUntil) {
            val was = deathStreak.containsKey(door) || deadUntil.containsKey(door)
            doorFails.remove(door); deathStreak.remove(door); deadUntil.remove(door)
            if (proved) proof[host(door)] = System.currentTimeMillis()
            was
        }
        if (revived) {
            Log.d("Montana", "sig_door alive " + host(door))
            Wire.forgetGone()   // a door that was silent answers: what it holds is new knowledge (iOS door_alive, forgetGoneChunks)
            // A DOOR THAT ANSWERS AFTER SILENCE DRAINS THE QUEUE AT ONCE (iOS 1672): the letters that waited for it leave now
            if (draining.compareAndSet(false, true)) Thread { try { Post.flush() } finally { draining.set(false) } }.start()
        }
    }
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)
    fun doorAlive(door: String) = synchronized(deadUntil) { (deadUntil[door] ?: 0L) <= System.currentTimeMillis() }
    /** PROOF OF A DOOR (iOS doorProof): the moment it last answered me with 200 since the path changed — keyed by host. */
    private val proof = HashMap<String, Long>()
    /**
     * THE PROVEN DOORS FIRST (iOS MontanaWakePush 484, measured 13.09 15:20: the first knock stood five seconds on a door that had
     * never answered on this network while three others had answered a second before): proof is what the app already knows — the
     * freshest first, an unproven door after them, a resting one last; with no proof at all, the network's own order.
     */
    fun byProof(doors: List<String>): List<String> = synchronized(deadUntil) {
        val now = System.currentTimeMillis()
        doors.sortedWith(compareBy({ if (now < (deadUntil[it] ?: 0L)) 2 else if (proof.containsKey(host(it))) 0 else 1 }, { -(proof[host(it)] ?: 0L) }))
    }
    private fun host(door: String) = android.net.Uri.parse(door).host ?: door
    /** The doors alive for me, in the list's order — what I name to the peer with every word (iOS signalDoors 497-499). */
    fun aliveHosts(): List<String> = MontanaCard.doors.filter { doorAlive(it) }.map { host(it) }

    private var pathWatched = false
    /** THE NETWORK PATH CHANGED (iOS 516-528): dead on one path says nothing of another — every door is forgiven at once. */
    fun watchPath(c: Context) {
        if (pathWatched) return
        pathWatched = true
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: android.net.Network) = forgive("path available")
                override fun onLost(n: android.net.Network) = forgive("path lost")
            })
        }
    }
    private fun forgive(why: String) {
        synchronized(deadUntil) { deadUntil.clear(); doorFails.clear(); deathStreak.clear(); proof.clear() }   // proof on one path says nothing of another
        Log.d("Montana", "sig_lane " + why + " — every door forgiven")
        Wire.forgetGone()   // a new path may reach a store the old one could not
    }

    /**
     * The words that landed for `ref`: opened by the minute (this one and its neighbours), unpacked, handed on by their epoch —
     * «chat» to the presence, any other to the call it names; «box» hints fetch the box.
     */
    private fun open(ref: String, envs: org.json.JSONArray) {
        val secret = Book.secret(ref) ?: return
        for (k in 0 until envs.length()) {
            val e = envs.optString(k)
            if (e == "box") { Thread { Post.fetch() }.start(); continue }
            val sealed = runCatching { Base64.decode(e, Base64.DEFAULT) }.getOrNull() ?: continue
            val w0 = Wire.minute()
            val plain = listOf(w0, w0 - 1, w0 + 1).firstNotNullOfOrNull { w -> Wire.open(Wire.bodyKey(secret, w), sealed) } ?: continue
            val a = plain.indexOf(0); if (a < 0) continue
            val b = (a + 1 until plain.size).firstOrNull { plain[it] == 0.toByte() } ?: continue
            val epoch = String(plain, a + 1, b - a - 1, Charsets.UTF_8)
            val words = inflate(plain.copyOfRange(b + 1, plain.size))?.takeIf { it.isNotEmpty() } ?: continue
            if (epoch == "chat") Presence.hear(ref, String(words, Charsets.UTF_8)) else Calls.hear(ref, epoch, words)
        }
    }

    private val holders = HashMap<String, MutableSet<String>>()
    private val running = HashMap<String, Int>()

    /** THE LANE OF ONE CONVERSATION stands while anyone holds it — the open chat, a call ringing — one long question per door. */
    fun hold(ref: String, why: String) {
        val start = synchronized(holders) {
            holders.getOrPut(ref) { mutableSetOf() }.add(why)
            ((running[ref] ?: 0) == 0).also { if (it) running[ref] = MontanaCard.doors.size }
        }
        if (start) listen(ref)
    }

    fun release(ref: String, why: String) {
        synchronized(holders) { holders[ref]?.let { it.remove(why); if (it.isEmpty()) holders.remove(ref) } }
    }

    /**
     * THE APP'S EAR (what the platform's call push — PushKit — gives the iPhone, this phone keeps for itself on our own doors, with
     * no third party): while the app stands — in front, or closed under its EarService — the lane of every conversation is held, so a call's first word rings here
     * the millisecond it lands — as an iPhone's ring does — not at the box's next round. The doors read a held lane as «this
     * phone is here» and keep the bell of a letter silent: the person is here, its screen is receiving it.
     */
    fun ear(on: Boolean) {
        for (ref in Book.refs()) if (on && !PeerSafety.isBlocked(ref)) hold(ref, "ear") else release(ref, "ear")
    }

    /**
     * THE LONG QUESTION (iOS fetchSignals): each door asked with a wait of twelve seconds — the doors' gate cuts at fifteen —
     * and asked again the moment it answers, while the lane is held.
     */
    private fun listen(ref: String) {
        for (door in MontanaCard.doors) Thread {
            var fails = 0
            while (true) {
                val twin = MontanaSeed.twin
                val secret = Book.secret(ref)
                val go = synchronized(holders) {
                    val ok = twin != null && secret != null && !holders[ref].isNullOrEmpty()
                    if (!ok) running[ref] = (running[ref] ?: 1) - 1
                    ok
                }
                if (!go || twin == null || secret == null) return@Thread
                // the standing question leaves a door resting dead for me; a path change forgives it (iOS 442-445, 516-528)
                if (!doorAlive(door)) { Thread.sleep(2000); continue }
                val cw = Wire.convW(secret, Wire.day())
                val (code, text) = Wire.post(door, "/signal-fetch", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("wait", 12), 15_000)
                EarService.awake()   // the answer woke the phone: it stays up for the word and the next question (the app closed)
                if (code != 200 || text == null) { doorFailed(door, code); fails++; Thread.sleep(minOf(10_000L, 1000L * fails)); continue }
                doorAnswered(door)
                fails = 0
                runCatching { JSONObject(text).optJSONArray("envs") }.getOrNull()?.let { envs -> if (envs.length() > 0) open(ref, envs) }
            }
        }.start()
    }
}

/**
 * THE PRESENCE LADDER (iOS ChatStore presenceWord): «typing…» lives five seconds, «in chat» and «online» one life of 45 —
 * two beats of the 20-second heartbeat and a margin; a departure word ends it at once; a word older than its life is history
 * and lights nothing (the lane hands over a minute of words when a chat opens). When no live word stands, the stamp: «last
 * seen…» — exact when both share it, the coarse class when either hides.
 */
object Presence {
    const val TYPING = "​​TY:"
    const val WATCH = "​​WA:"   // «1» my chat with you is on my screen, «0» left it
    const val APP = "​​AP:"     // «1» the app is on my screen, «0» left it; «0B» — gone for good
    private const val TYPING_LIFE = 5_000L
    private const val WORD_LIFE = 45_000L
    const val BEAT = 20_000L

    private val typingUntil = HashMap<String, Long>()
    private val inChatUntil = HashMap<String, Long>()
    private val onlineUntil = HashMap<String, Long>()
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        MainThread.post { ls.forEach { it() } }
    }

    val sharing get() = Prefs.bool("presenceSharing", true)
    /**
     * THE PERSON HIDES, AND SAYS SO ONCE (iOS MontanaPresencePrivacy.setSharing, E2E.presenceFarewell 849-855): one honest word
     * «0h» to everyone I speak presence to — an old reader takes the digit and the word dies, a new one reads the tail and coarsens
     * my stamp at once — then silence; shown again, «here» at once. Android went silent without the word: their phones kept my exact
     * moment and, for the word's life, «online».
     */
    fun setSharing(v: Boolean) {
        if (v == sharing) return
        Prefs.setBool("presenceSharing", v)
        if (v) Thread { sayApp(true) }.start()
        else Thread { for (ref in Book.refs()) if (SamePair.merged(ref) == null && !PeerSafety.isBlocked(ref)) Signal.post(ref, APP + "0h") }.start()
    }
    /** The moment a word was said, as iOS says it (E2E.saidTail): «T» + seconds + «.» + three digits of milliseconds. */
    fun saidTail(now: Long = System.currentTimeMillis()) = "T" + (now / 1000) + "." + (1000 + now % 1000).toString().drop(1)
    private val saidRe = Regex("T(\\d{9,11})\\.(\\d{3})")
    private fun said(payload: String): Long? = saidRe.find(payload)?.let { m -> m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong() }

    // ── what I say ──
    fun sayTyping(ref: String) { if (sharing) Signal.post(ref, TYPING + saidTail()) }
    fun sayChat(ref: String, open: Boolean) { if (sharing) Signal.post(ref, WATCH + (if (open) "1" else "0") + saidTail() + heldTail(ref) + doorTail()) }

    /** The tag a word names a thing by (iOS Announced.wireTag, MontanaDeliveryEngine 417-422): SHA-256's first four bytes, big-endian, in digits; none — «0». */
    fun wireTag(d: ByteArray?): String {
        if (d == null || d.isEmpty()) return "0"
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(d)
        return (((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)).toString()
    }
    private val heldFace = HashMap<String, Pair<Long, String>>()   // ref -> (the face file's moment, its tag): a file is new every update
    /**
     * WHAT I HOLD OF YOU, in every presence word (iOS heldTail, MontanaE2E 543-568; fork atom 1778): «F» + the tag of your face on my
     * screen, «N» + the tag of your name — digits only, after the moment, so every older reader reads nothing of it. The peer compares
     * with what it is and sends again what my screen holds stale.
     */
    private fun heldTail(ref: String): String {
        val f = Book.face(ref)
        val face = if (!f.exists()) "0" else synchronized(heldFace) {
            heldFace[ref]?.takeIf { it.first == f.lastModified() }?.second ?: wireTag(f.readBytes()).also { heldFace[ref] = f.lastModified() to it }
        }
        val n = Book.chat(ref)?.name.orEmpty()
        return "F" + face + "N" + wireTag(if (n.isEmpty()) null else n.toByteArray(Charsets.UTF_8))
    }
    /**
     * WHAT THEY HOLD OF ME, heard in their word (iOS heardHeld 629-667): their screen's tags of my face and my name against what I am —
     * a difference forgets the receipted mark and announces anew, at most once a minute a person. Android read nothing of it: an
     * iPhone that held an old face of this phone kept it for ever.
     */
    private val heldAskedAt = HashMap<String, Long>()
    private fun heardHeld(ref: String, payload: String) {
        fun digits(after: Char): String? {
            val i = payload.indexOf(after); if (i < 0) return null
            return payload.substring(i + 1).takeWhile { it.isDigit() }.ifEmpty { null }
        }
        val f = digits('F') ?: return
        val n = digits('N') ?: return
        val faceOff = f != wireTag(SelfFace.bytes(Book.ctx))
        val nameOff = n != wireTag(Prefs.userName.trim().takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
        if (!faceOff && !nameOff) return
        val now = System.currentTimeMillis()
        synchronized(heldAskedAt) { if (now - (heldAskedAt[ref] ?: 0L) < 60_000L) return; heldAskedAt[ref] = now }
        Log.d("Montana", "held_differs face=" + (if (faceOff) 1 else 0) + " name=" + (if (nameOff) 1 else 0))
        Thread { Post.announceAgain(ref, faceOff, nameOff) }.start()
    }
    /** «@DOORS» — the doors alive for me, in the chat word's tail (iOS doorTail 685-691): UPPERCASE, so no old reader takes a host's «h» for hiding. */
    private fun doorTail() = Signal.aliveHosts().joinToString(",").let { if (it.isEmpty()) "" else "@" + it.uppercase() }
    fun sayApp(open: Boolean) {
        if (!sharing) return
        for (ref in Book.refs()) if (SamePair.merged(ref) == null && !PeerSafety.isBlocked(ref)) Signal.post(ref, APP + (if (open) "1" else "0") + saidTail() + heldTail(ref))
    }

    /** A blocked person's live words leave with the block (iOS toggleBlocked 979: typing and watching removed). */
    fun drop(ref: String) {
        typingUntil.remove(ref); inChatUntil.remove(ref); onlineUntil.remove(ref)
        changed()
    }

    // ── what I hear ──
    /** A word of the lane from `ref` (iOS append's presence branches + liveWordMoves). */
    fun hear(ref: String, text: String) {
        if (PeerSafety.isBlocked(ref)) return   // a blocked person reaches nothing
        // THEIR LIVE DRAFT (iOS handleMeshDraft): its bubble, and «typing…» when it is a keystroke said now
        if (text.startsWith(LiveDraft.MARK)) { if (LiveDraft.hear(ref, text)) hear(ref, TYPING + saidTail()); return }
        val now = System.currentTimeMillis()
        val at = said(text)
        val age = at?.let { (now - it).coerceAtLeast(0) } ?: 0L
        when {
            text.startsWith(TYPING) -> {
                if (age > TYPING_LIFE + 2000) { noteSeen(ref, at); return }   // a keystroke a minute old is not typing now
                typingUntil[ref] = now + TYPING_LIFE - age
                inChatUntil[ref] = now + WORD_LIFE - age; onlineUntil[ref] = now + WORD_LIFE - age   // typing proves the chat is on their screen
                MainThread.later(TYPING_LIFE - age + 50) { changed() }
            }
            text.startsWith(WATCH) || text.startsWith(APP) -> {
                val chat = text.startsWith(WATCH)
                val payload = text.removePrefix(if (chat) WATCH else APP)
                Prefs.setBool("phide_$ref", payload.drop(1).contains('h'))
                val open = payload.startsWith("1")
                if (age <= WORD_LIFE) Board.heardPresence(ref, payload)   // their wall's version (iOS heardHeld, wallHeard)
                if (age <= WORD_LIFE) heardHeld(ref, payload)   // only a word said now says what their screen holds
                if (age <= WORD_LIFE) {
                    if (open) {
                        if (chat) inChatUntil[ref] = now + WORD_LIFE - age
                        onlineUntil[ref] = now + WORD_LIFE - age
                        MainThread.later(WORD_LIFE - age + 50) { changed() }
                    } else {
                        inChatUntil.remove(ref); typingUntil.remove(ref)
                        if (!chat) onlineUntil.remove(ref)
                    }
                }
                if (!chat && payload.drop(1).startsWith("B")) Prefs.setBool("peerGone.$ref", true)
                else if (open) Prefs.remove("peerGone.$ref")
            }
            else -> return
        }
        noteSeen(ref, at ?: now)
        changed()
    }

    /** THE ONE WRITER OF THE STAMP (iOS noteSeen): a moment never later than now, never earlier than the one held. */
    fun noteSeen(ref: String, atMs: Long?) {
        val ts = minOf(atMs ?: return, System.currentTimeMillis())
        if (ts <= seenAt(ref)) return
        Prefs.setStr("seenAt.$ref", ts.toString())
        changed()
    }

    private fun seenAt(ref: String) = Prefs.str("seenAt.$ref", "0").toLongOrNull() ?: 0L

    /** THE MOMENT BEHIND THE LINE, as a number to sort by (iOS presenceStamp): a live word above every stamp, «gone» at the bottom. */
    fun stamp(ref: String): Long {
        val now = System.currentTimeMillis()
        if ((typingUntil[ref] ?: 0) > now) return now + 3
        if ((inChatUntil[ref] ?: 0) > now) return now + 2
        if ((onlineUntil[ref] ?: 0) > now) return now + 1
        if (Prefs.bool("peerGone.$ref", false)) return 1
        return seenAt(ref)
    }

    /** The one presence line (iOS presenceWord): the words and whether they are live (green). null — nothing is known. */
    fun word(c: Context, ref: String): Pair<String, Boolean>? {
        val now = System.currentTimeMillis()
        if ((typingUntil[ref] ?: 0) > now) return c.getString(R.string.pr_typing) to true
        if ((inChatUntil[ref] ?: 0) > now) return c.getString(R.string.pr_in_chat) to true
        // a person on the line with me is present for as long as the call stands (iOS presenceWord 15.7)
        if (Calls.talking() == ref) return c.getString(R.string.pr_on_call) to true
        if ((onlineUntil[ref] ?: 0) > now) return c.getString(R.string.pr_online) to true
        if (Prefs.bool("peerGone.$ref", false)) return c.getString(R.string.pr_seen_long_ago) to false
        val seen = seenAt(ref).takeIf { it > 0 } ?: return null
        val exact = sharing && !Prefs.bool("phide_$ref", false)
        return (if (exact) phrase(c, seen) else coarse(c, seen)) to false
    }
    /** iOS MontanaSeen.coarse: the class, never the minute. */
    private fun coarse(c: Context, ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return c.getString(when {
            d < 3 * 86_400_000L -> R.string.pr_seen_recently
            d < 7 * 86_400_000L -> R.string.pr_seen_week
            d < 30 * 86_400_000L -> R.string.pr_seen_month
            else -> R.string.pr_seen_long_ago
        })
    }
    /** iOS MontanaSeen.phrase: minutes within the hour (never under one), «today at», «yesterday at», else the day. */
    private fun phrase(c: Context, ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        if (diff < 3_600_000L) { val m = maxOf(1, (diff / 60_000L).toInt()); return c.resources.getQuantityString(R.plurals.pr_minutes, m, m) }
        val time = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(ts))
        if (android.text.format.DateUtils.isToday(ts)) return c.getString(R.string.pr_today_at, time)
        if (android.text.format.DateUtils.isToday(ts + 86_400_000L)) return c.getString(R.string.pr_yesterday_at, time)
        return android.text.format.DateUtils.formatDateTime(c, ts, android.text.format.DateUtils.FORMAT_SHOW_DATE)
    }

    // ── the app's own beat (iOS appBeacon + the 20-second heartbeat) ──
    private var appOpen = false
    private val beat = object : Runnable {
        override fun run() { if (appOpen) { Thread { sayApp(true) }.start(); MainThread.later(BEAT, this) } }
    }
    fun appShown() { if (appOpen) return; appOpen = true; MainThread.post { beat.run() } }
    fun appHidden() { if (!appOpen) return; appOpen = false; Thread { sayApp(false) }.start() }
    fun log(s: String) = Log.d("Montana", "presence: $s")

    /**
     * «The app is on my screen» follows the activity's own life (iOS: the scene's phase) — watched from here, so no other
     * file carries it: registered once, on the first pickup of the box (which runs only while the app stands).
     */
    private var watching = false
    fun watchApp(c: Context) {
        if (watching) return
        watching = true
        Signal.watchPath(c)
        (c.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) = appShown()
            override fun onActivityPaused(a: android.app.Activity) = appHidden()
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // on the screen only when an activity really stands in front — a pickup from a background job must not say «online»;
        // the callbacks above say it when the activity resumes later
        val me = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        if (me.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) appShown()
    }
}
