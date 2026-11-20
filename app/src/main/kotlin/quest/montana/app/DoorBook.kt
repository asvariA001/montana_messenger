package quest.montana.app

import android.util.Log
import org.json.JSONObject

/**
 * THE BOOK OF DOORS -- ONE OWNER OF «THIS DOOR ANSWERS ME» (iOS MTDoorBook, MontanaNodes 382-639; OZ 1 p.2, the critic of
 * 19.11.2026). Before it the doors were judged by several books, each by its own rule and none hearing the others: the signal
 * lane's death (Signal.doorFails/deathStreak/deadUntil), the proof of its answers (Signal.proof), the health round's silence
 * (Doors.silent) and the knock's pace (Channels.tried). iOS measured on T1 19.11.2026 15:16 UTC «verdict=down» and a knock at
 * the same door in the same millisecond. The law: a door is alive only by its own answer, and one book holds it -- every answer
 * and every silence is written here, and every place that dials a door asks here. One book per network (networkChanged), kept
 * past the process (open, N-2).
 */
object DoorBook {
    private class Record {
        var inRow = 0                // silences since the last answer, for the diary
        var silences = 0             // silences toward the next rest: two are a death
        var streak = 0               // rests in a row: a minute, two, four, eight at most
        var restUntil = 0L           // dead for me until then (0: not resting)
        var answeredAt = 0L          // the last answer on this network (0: never)
        var answers = 0
        var dialAt = 0L              // the knock's pace: the last dial of a node's channel (not kept)
        var dialStep = STEP_MIN
        var probeAt = 0L             // the last probe of this door while it rests (mayDial)
        fun json() = JSONObject().put("i", inRow).put("s", silences).put("k", streak).put("r", restUntil).put("a", answeredAt).put("n", answers).put("p", probeAt)
        companion object {
            fun of(o: JSONObject) = Record().apply {
                inRow = o.optInt("i"); silences = o.optInt("s"); streak = o.optInt("k"); restUntil = o.optLong("r")
                answeredAt = o.optLong("a"); answers = o.optInt("n"); probeAt = o.optLong("p")
            }
        }
    }

    private const val STEP_MIN = 2.0
    private const val STEP_MAX = 15.0
    private const val PROBE_EVERY = 60_000L
    private const val SHELF_MAX = 8
    private const val KEPT = "mt.doorbook.life"
    private val lock = Any()
    private var records = HashMap<String, Record>()
    private val shelf = LinkedHashMap<String, HashMap<String, Record>>()
    private var current = ""
    private var bookProbeAt = 0L
    @Volatile private var keepDue = false

    /** The book's clock (iOS clock): the wall clock. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }
    /** A call is live (iOS callLive = MontanaCall.isBusy, N-1). */
    @Volatile var callLive: () -> Boolean = { Calls.busy() }

    /**
     * THE ONE KEY OF A DOOR (iOS door 386-393): the endpoint a connection meets, host and port. A base («https://h/pushwake»), a
     * node's label («wss/h:443», «h:8447», «[v6]:8447») and a bare name reduce to it; the dress is not part of it -- a question
     * over HTTPS and a web socket to one name and port meet one front, and its silence is one fact. A relay's road keeps its own key.
     */
    fun door(s: String): String {
        if (s.startsWith("turn:") || s.startsWith("turns:")) return relayRoad(s)
        if (s.startsWith("turn/") || s.startsWith("turns/")) return s
        if (s.contains("://")) {
            val u = android.net.Uri.parse(s)
            val h = u.host
            if (h != null) return endpoint(h, if (u.port > 0) u.port else 443)
        }
        val bare = if (s.startsWith("wss/")) s.drop(4) else s
        if (bare.startsWith("[") || bare.split(':').size == 2) return bare
        return endpoint(bare, 443)
    }
    fun door(host: String, port: Int): String = endpoint(host, port)
    private fun endpoint(h: String, p: Int) = if (h.contains(':')) "[$h]:$p" else "$h:$p"

    /**
     * A RELAY'S ROAD IS A DOOR OF ITS OWN (iOS relayRoad 399-415, OZ 1 p.7): «turns:h:5349?transport=tcp», «turn:h:3478?transport=tcp»
     * and «turn:h:3478?transport=udp» are three roads to one machine; a TLS road dead on this network says nothing of the UDP one.
     */
    private fun relayRoad(u: String): String {
        val tls = u.startsWith("turns:")
        val parts = u.drop(if (tls) 6 else 5).split("?", limit = 2)
        val authority = parts.firstOrNull() ?: ""
        val tcp = tls || (parts.size == 2 && parts[1].contains("transport=tcp"))
        var host = authority
        var port = if (tls) 5349 else 3478   // the defaults of RFC 7065
        if (authority.startsWith("[") && authority.contains(']')) {
            val close = authority.indexOf(']')
            host = authority.substring(1, close)
            val tail = authority.substring(close + 1)
            if (tail.startsWith(":")) tail.drop(1).toIntOrNull()?.let { port = it }
        } else {
            val hp = authority.split(':')
            host = hp.firstOrNull() ?: ""
            if (hp.size == 2) hp[1].toIntOrNull()?.let { port = it }
        }
        return (if (tls) "turns" else "turn") + "/" + (if (tcp) "tcp" else "udp") + " " + endpoint(host, port)
    }
    /** The port a door meets. */
    fun port(s: String): Int? = door(s).substringAfterLast(':').toIntOrNull()
    /** The name a door is spoken of by -- in the diary and to the delivery. */
    fun host(s: String): String {
        val d = door(s)
        val k = if (d.startsWith("turn/") || d.startsWith("turns/")) d.substringAfter(' ') else d
        if (k.startsWith("[") && k.contains(']')) return k.substring(1, k.indexOf(']'))
        return k.substringBefore(':')
    }

    /**
     * AN ANSWER (iOS answered 448-462) -- any code a door speaks, a refusal too (only no code, or a 5xx, is silence): its silences,
     * its rest and the rest's streak are forgiven, its pace starts fast again. True when the door had rested: its return is news.
     */
    fun answered(s: String): Boolean {
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure();
            val r = records.getOrPut(k) { Record() }
            val revived = r.streak > 0 || r.restUntil > now
            r.inRow = 0; r.silences = 0; r.streak = 0; r.restUntil = 0
            r.answeredAt = now; r.answers += 1
            r.dialAt = 0; r.dialStep = STEP_MIN
            if (revived || r.answers == 1) keepSoon()
            return revived
        }
    }

    /**
     * A SILENCE (iOS silent 464-482) -- no code, or a 5xx. Two in a row are a death: the door rests a minute, then two, four, eight
     * at most. The health round's silence weighs two. A resting door's late silences add nothing. A door back from its rest is on
     * trial (N-1): one silence sends it back to a rest twice as long. The rest begun now, in seconds, or null.
     */
    fun silent(s: String, weight: Int = 1): Int? {
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure();
            val r = records.getOrPut(k) { Record() }
            r.inRow += 1
            if (r.restUntil > now) return null
            r.silences += weight
            if (r.silences < 2 && r.streak == 0) return null
            r.silences = 0
            r.streak = minOf(r.streak + 1, 4)
            val rest = 60 * (1 shl (r.streak - 1))
            r.restUntil = now + rest * 1000L
            keepSoon()
            return rest
        }
    }

    /** Alive for me: not resting after its own silences. */
    fun alive(s: String): Boolean {
        val k = door(s)
        synchronized(lock) { ensure(); return (records[k]?.restUntil ?: 0L) <= clock() }
    }

    /** Whether this door has answered on this network (the proof the call's walk reads). */
    fun heardOnce(s: String): Boolean = synchronized(lock) { ensure(); (records[door(s)]?.answeredAt ?: 0L) > 0L }

    /**
     * THE ORDER OF A WALK (iOS ordered 494-508, OZ 1 p.3): a door is placed by its LAST word. The doors whose last word was an
     * answer, the newest answer first; then the living ones not yet asked, in the order given; then the living ones whose last word
     * was a silence, the fewest silences first; the resting last -- an order, never an exclusion.
     */
    fun ordered(doors: List<String>): List<String> {
        val now = clock()
        val rs = synchronized(lock) { ensure(); doors.map { records[door(it)]?.let { r -> Triple(r.restUntil, r.answeredAt, r.inRow) } } }
        val all = doors.zip(rs)
        fun living(r: Triple<Long, Long, Int>?) = (r?.first ?: 0L) <= now
        val live = all.filter { living(it.second) }
        val heard = live.filter { (it.second?.second ?: 0L) > 0L && (it.second?.third ?: 0) == 0 }.sortedByDescending { it.second?.second ?: 0L }
        val unasked = live.filter { (it.second?.second ?: 0L) == 0L && (it.second?.third ?: 0) == 0 }
        val faltering = live.withIndex().filter { (it.value.second?.third ?: 0) > 0 }
            .sortedWith(compareBy({ it.value.second?.third ?: 0 }, { it.index })).map { it.value }
        val resting = all.filter { !living(it.second) }
        return (heard + unasked + faltering + resting).map { it.first }
    }

    /**
     * MAY A WORD DIAL THIS DOOR NOW (iOS mayDial 510-526, OZ 1 p.4): a living door -- always; a resting one -- one probe a minute,
     * the probe written. In a live call, while a door answers, the dead doors are probed one a minute for the whole book (N-1).
     */
    fun mayDial(s: String): Boolean {
        val inCall = callLive()
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure();
            val r = records[k] ?: return true
            val resting = r.restUntil > now
            if (!resting && r.streak == 0) return true
            if (inCall && heard(now)) return bookProbe(k, now)
            if (!resting) return true   // out of a call a door back from its rest is dialled once and judged (silent)
            if (now - r.probeAt < PROBE_EVERY) return false
            r.probeAt = now
            return true
        }
    }

    /** THE CALL'S QUIET (iOS bookProbe 528-535, N-1): one dead door a minute for the whole book, each in its turn. Under the lock. */
    private fun bookProbe(k: String, now: Long): Boolean {
        val waiting = records.count { !it.key.startsWith("turn") && (it.value.streak > 0 || it.value.restUntil > now) }
        if (now - bookProbeAt < PROBE_EVERY || now - (records[k]?.probeAt ?: 0L) < PROBE_EVERY * maxOf(1, waiting)) return false
        bookProbeAt = now
        records[k]?.probeAt = now
        return true
    }

    /** A door answers me now: its last word an answer, and it is not resting. Under the lock. */
    private fun heard(now: Long): Boolean = records.values.any { it.answeredAt > 0L && it.inRow == 0 && it.restUntil <= now }

    /**
     * THE KNOCK'S PACE (iOS dialDue 548-562): a living door is dialled again after a wait that doubles and spreads up to a quarter of
     * a minute; a resting door is not dialled until its rest ends. True: dial now -- the dial is written.
     */
    fun dialDue(s: String): Boolean {
        val inCall = callLive()
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure();
            val r = records.getOrPut(k) { Record() }
            if (r.restUntil > now) return false
            if (now - r.dialAt < r.dialStep * 1000) return false
            if (r.streak > 0 && inCall && heard(now)) { if (!bookProbe(k, now)) return false; r.probeAt = now }
            val spread = 0.8 + Math.random() * 0.6   // the spread of a retry, not a quantity of the protocol
            r.dialAt = now
            r.dialStep = minOf(STEP_MAX, r.dialStep * 2 * spread)
            return true
        }
    }

    /** The process itself could not open a socket (iOS notJudged 564-570): not the door's word -- dialled again after the fast step. */
    fun notJudged(s: String) {
        val k = door(s)
        synchronized(lock) { ensure(); records.getOrPut(k) { Record() }.apply { dialAt = clock(); dialStep = STEP_MIN } }
    }

    /** Every opening of the app knocks NOW, but keeps what the doors said (iOS knockNow 572-575): the pace resets, the verdicts stay. */
    fun knockNow() = synchronized(lock) { ensure(); for (r in records.values) r.dialAt = 0 }

    /**
     * THE NETWORK'S OWN BOOK (iOS networkChanged 582-591, OZ 1 p.5): dead on Wi-Fi says nothing about cellular, and what cellular
     * said is not forgotten on Wi-Fi. A network met for the first time opens an empty book; a network returned to opens the book it
     * left; the same network keeps its book open. The last eight books stay, keyed by the network's salted name.
     */
    fun networkChanged(net: String) {
        synchronized(lock) { ensure();
            if (net == current) return
            shelf[current] = records
            if (SHELF_MAX < shelf.size) shelf.keys.firstOrNull { it != net }?.let { shelf.remove(it) }
            current = net
            records = shelf.remove(net) ?: HashMap()
            keepSoon()
        }
        Log.d("Montana", "door_book net=" + net)
    }

    /** The open book's network name (a round that outlived a change writes no silence). */
    fun net(): String = synchronized(lock) { ensure(); current }

    /**
     * THE BOOK OUTLIVES ITS PROCESS (iOS open 601-608, N-2): a process woken by a push opens the book its last life left, and the
     * rests stand by the wall clock; the knock's pace is not kept. A launch inside a rest waits its minute out before the probe.
     */
    fun open() = synchronized(lock) { ensure() }
    @Volatile private var opened = false
    /** Under the lock: the book the last life left is opened once, before the first verdict is read or written. */
    private fun ensure() {
        if (opened) return
        opened = true
        val raw = runCatching { Prefs.str(KEPT, "") }.getOrDefault("").ifEmpty { return }
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val now = clock()
        val books = o.optJSONObject("shelf") ?: JSONObject()
        for (net in books.keys()) {
            val b = books.optJSONObject(net) ?: continue
            val m = HashMap<String, Record>()
            for (d in b.keys()) b.optJSONObject(d)?.let { m[d] = Record.of(it).apply { if (now < restUntil) probeAt = now } }
            shelf[net] = m
        }
        val net = o.optString("net")
        if (current.isEmpty()) { current = net; records = shelf.remove(current) ?: HashMap() }
    }

    /** Under the lock: one write two seconds after the first change, carrying every change since (iOS keepSoon 617-630). */
    private fun keepSoon() {
        if (keepDue) return
        keepDue = true
        MainThread.later(2000, Runnable {
            Thread {
                val text = synchronized(lock) { ensure();
                    keepDue = false
                    val all = JSONObject()
                    for ((net, b) in shelf) if (net.isNotEmpty()) all.put(net, JSONObject().apply { for ((d, r) in b) put(d, r.json()) })
                    if (current.isNotEmpty()) all.put(current, JSONObject().apply { for ((d, r) in records) put(d, r.json()) })
                    JSONObject().put("net", current).put("shelf", all).toString()
                }
                Prefs.setStr(KEPT, text)
            }.start()
        })
    }

    /** The door's record for the diary (iOS record 632-638). */
    fun record(s: String): String {
        val now = clock()
        val r = synchronized(lock) { ensure(); records[door(s)]?.let { Triple(it.inRow, it.answers, it.answeredAt) to (it.restUntil > now || it.streak > 0) } }
        val since = r?.first?.third?.takeIf { it > 0 }?.let { ((now - it) / 1000).toString() + "s" } ?: "never"
        return "streak=" + (r?.first?.first ?: 0) + " ok=" + (r?.first?.second ?: 0) + " last_ok=" + since + " verdict=" + (if (r?.second == true) "down" else "blip")
    }
}
