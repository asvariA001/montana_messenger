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
    private const val KEPT_FORM = 2
    private val lock = Any()
    private var records = HashMap<String, Record>()
    private val shelf = LinkedHashMap<String, HashMap<String, Record>>()
    private var current = ""
    private var bookProbeAt = 0L
    @Volatile private var keepDue = false

    /** The book's clock (iOS clock): the wall clock. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * THE ONE KEY OF A DOOR (iOS door 383-396, OZ 1 p.2.4): the endpoint a connection meets, host and port, and the way it meets it.
     * A base («https://h/pushwake») and a bare name reduce to «h:443»; a node's label keeps its dress («wss/h:443», «h:8447»,
     * «[v6]:8447»). A question over HTTPS and a web socket to one name and port are two doors, each fact written by its own road:
     * iOS T3 on Wi-Fi, 19.11.2026 22:03:54 UTC, an HTTPS answer ended the web socket's rest and the socket knocked again at a door
     * dead to it. A relay's road keeps its own key.
     */
    fun door(s: String): String {
        if (s.startsWith("turn:") || s.startsWith("turns:")) return relayRoad(s)
        if (s.startsWith("turn/") || s.startsWith("turns/")) return s
        if (s.contains("://")) {
            val u = android.net.Uri.parse(s)
            val h = u.host
            if (h != null) return (if (u.scheme == "wss") "wss/" else "") + endpoint(h, if (u.port > 0) u.port else 443)
        }
        if (s.startsWith("wss/")) return "wss/" + door(s.drop(4))   // a web socket is a door of its own (OZ 1 p.2.4)
        if (s.startsWith("[") || s.split(':').size == 2) return s
        return endpoint(s, 443)
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
        val k = if (isRoad(d)) d.substringAfter(' ') else d.removePrefix("wss/")
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

    /**
     * A RELAY'S ROAD IS JUDGED BY THE WHOLE GATHERING, NOT BY ONE ADAPTER (iOS roadHeard/roundEnded, MontanaNodes 483-498, N-5, the
     * critic of call 2, 19.11.2026). The engine asks every road from every adapter of the phone, and one adapter's 701 is that
     * adapter's silence, not the road's: T3 on Wi-Fi gave a relay candidate over turns/tcp from one adapter and a 701 from another.
     * A road is answered at its first candidate; it is silent only when its gathering ended -- the connection closed (roundEnded) --
     * without one candidate over it, and then it weighs two: it waited the connection's whole life. A gathering's word belongs to its
     * network: a new network forgets the open rounds.
     */
    fun roadHeard(road: String, round: Any, ok: Boolean) {
        val k = door(road)
        synchronized(lock) { val m = rounds.getOrPut(round) { HashMap() }; m[k] = ok || (m[k] ?: false) }
        if (ok) answered(road)
    }
    fun roundEnded(round: Any) {
        val heard = synchronized(lock) { rounds.remove(round) } ?: return
        for ((k, ok) in heard) if (!ok) silent(k, weight = 2)
    }
    private val rounds = java.util.IdentityHashMap<Any, HashMap<String, Boolean>>()   // under lock

    /**
     * Alive for me: not resting after its own silences, and -- while another door answers -- not back from its rest unheard (iOS alive
     * 500-507, OZ 1 p.2.4): such a door is not dialled, so it is not offered.
     */
    fun alive(s: String): Boolean {
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure()
            val r = records[k] ?: return true
            if (r.restUntil > now) return false
            return r.streak == 0 || isRoad(k) || !heard(now)
        }
    }

    /** Whether this door has answered on this network (the proof the call's walk reads). */
    fun heardOnce(s: String): Boolean = synchronized(lock) { ensure(); (records[door(s)]?.answeredAt ?: 0L) > 0L }

    /**
     * THE ORDER OF A WALK (iOS ordered 494-508, OZ 1 p.3): a door is placed by its LAST word. The doors whose last word was an
     * answer, the newest answer first; then the living ones not yet asked, in the order given; then the living ones whose last word
     * was a silence, the fewest silences first; the dead last (OZ 1 p.2.4: resting, or back from the rest unheard) -- an order, never
     * an exclusion.
     */
    fun ordered(doors: List<String>): List<String> {
        val now = clock()
        val rs = synchronized(lock) { ensure(); doors.map { records[door(it)]?.let { r -> Triple(if (dead(r, now)) Long.MAX_VALUE else 0L, r.answeredAt, r.inRow) } } }
        val all = doors.zip(rs)
        fun living(r: Triple<Long, Long, Int>?) = (r?.first ?: 0L) == 0L
        val live = all.filter { living(it.second) }
        val heard = live.filter { (it.second?.second ?: 0L) > 0L && (it.second?.third ?: 0) == 0 }.sortedByDescending { it.second?.second ?: 0L }
        val unasked = live.filter { (it.second?.second ?: 0L) == 0L && (it.second?.third ?: 0) == 0 }
        val faltering = live.withIndex().filter { (it.value.second?.third ?: 0) > 0 }
            .sortedWith(compareBy({ it.value.second?.third ?: 0 }, { it.index })).map { it.value }
        val resting = all.filter { !living(it.second) }
        return (heard + unasked + faltering + resting).map { it.first }
    }

    /**
     * MAY A WORD DIAL THIS DOOR NOW (iOS mayDial 531-536, OZ 1 p.4, p.2.4): a living door -- always; a dead one -- by the book's one rule
     * (mayProbe), the probe written. A skipped dial writes nothing: it is not the door's word.
     */
    fun mayDial(s: String): Boolean {
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure()
            val r = records[k] ?: return true
            if (!dead(r, now)) return true
            return mayProbe(k, now)
        }
    }

    /**
     * A DEAD DOOR IS DIALLED BY ONE RULE, IN A CALL AND OUT OF IT (iOS mayProbe 538-555, OZ 1 p.2.4; the author's words 25250, 25256:
     * the device holds one connection, and a door that died on this network is not its business while another answers). A door is
     * dead while it rests, and after its rest until it answers. While any door of the network answers, a dead door is not dialled at
     * all; when none answers, the whole book is probed one door a minute, each in its turn (bookProbe). A relay's road is not a door
     * of the network: it keeps its own minute while it rests and is judged by its own silence after (P-7). Under the lock.
     */
    private fun mayProbe(k: String, now: Long): Boolean {
        if (isRoad(k)) {
            val r = records[k] ?: return true
            if (r.restUntil <= now) return true
            if (now - r.probeAt < PROBE_EVERY) return false
            r.probeAt = now
            return true
        }
        if (heard(now)) return false
        return bookProbe(k, now)
    }

    /** THE BOOK'S MINUTE (iOS bookProbe 559-566, N-1): one dead door a minute for the whole book, each in its turn. Under the lock. */
    private fun bookProbe(k: String, now: Long): Boolean {
        val waiting = records.count { !isRoad(it.key) && dead(it.value, now) }
        if (now - bookProbeAt < PROBE_EVERY || now - (records[k]?.probeAt ?: 0L) < PROBE_EVERY * maxOf(1, waiting)) return false
        bookProbeAt = now
        records[k]?.probeAt = now
        return true
    }

    /** A door of the network answers me now: its last word an answer, and it is not dead; a relay's road is not one (OZ 1 p.2.4). Under the lock. */
    private fun heard(now: Long): Boolean = records.any { !isRoad(it.key) && it.value.answeredAt > 0L && it.value.inRow == 0 && !dead(it.value, now) }
    /** DEAD FOR ME (iOS dead, OZ 1 p.2.4): resting, or back from the rest and not yet heard. */
    private fun dead(r: Record, now: Long) = r.restUntil > now || r.streak > 0
    /** A relay's road (relayRoad): its key names its scheme and transport. */
    private fun isRoad(k: String) = k.startsWith("turn/") || k.startsWith("turns/")

    /**
     * THE KNOCK'S PACE (iOS dialDue 548-562): a living door is dialled again after a wait that doubles and spreads up to a quarter of
     * a minute; a dead door by the book's one rule (mayProbe). True: dial now -- the dial is written.
     */
    fun dialDue(s: String): Boolean {
        val k = door(s)
        val now = clock()
        synchronized(lock) { ensure();
            val r = records.getOrPut(k) { Record() }
            if (now - r.dialAt < r.dialStep * 1000) return false
            if (dead(r, now)) { if (!mayProbe(k, now)) return false; r.probeAt = now }   // OZ 1 p.2.4: the one rule of a dead door
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
            rounds.clear()   // N-5: a gathering's word belongs to its network
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
        // THE BOOK'S FORM (iOS keptForm, OZ 1 p.2.4): 2 since a web socket and a question over HTTPS are two doors; the first form
        // held both under one key, and a book of it is not read -- its verdicts name no way
        if (o.optInt("form") != KEPT_FORM) return
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
                    JSONObject().put("form", KEPT_FORM).put("net", current).put("shelf", all).toString()
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
