package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

// ─────────────────────────── the level of the person (iOS MTWalletCore: MTPersonChain, MTPersonLevel, MTLevelAccess) ───────────────────────────

/**
 * THE CHAIN OF THE PERSON (iOS MTPersonChain, MTWalletCore.swift:1943-2176): the seconds this person's app stood on the screen and on
 * the network, by the nodes' own clock. Every answer of a node (NodeClock.learn: the lane and the diary's ship -- the roads of iOS
 * learnSkew) while the app faces the person is a second, and so is every second back to the answer before it when they are no
 * further apart than REACH. The level is the whole doublings of the count (Canon: bit_length(s) - 1, none below one second); one
 * link a level, at the second the count reached it, sealed on the one before (TimeChain.seal), in TimeChain/wallet-person.jsonl.
 * DEVIATION: the devices of the words do not join their seconds yet -- iOS lays a sealed piece beside the coin book on every node
 * (MTCoinVault vault-put/vault-get), a door Android has not got; and the minting while away (iOS left/came) is not carried, the
 * coins being Anna's word. This phone counts its own seconds.
 */
object PersonChain {
    class Span(val a: Long, var b: Long)
    const val REACH = 10L
    private const val FILE = "person-seconds.json"   // NOT-UI
    private const val LINKS = "wallet-person.jsonl"   // NOT-UI: iOS MTTimeChainPlace.file("person")
    @Volatile var onScreen = false   // MainActivity: onResume and onPause (iOS applicationState == .active)
    @Volatile var seconds = 0L; private set
    /** The person's level as this life has read it (iOS MTPersonLevel.now): null until the seconds are read, and below one second. */
    @Volatile var known: Int? = null; private set
    @Volatile var links: List<TimeChain.Link> = emptyList(); private set
    private var spans: List<Span> = emptyList()
    private var lane = ""
    private var last: Long? = null
    private var loaded = false
    private var checked = false
    private var keepDue = false
    private var fedAt = 0L

    /** THE LEVEL (Canon, «The level of the chain of the person»): the whole doublings of the count, none below one second. */
    fun level(s: Long): Int? = if (s < 1) null else 63 - java.lang.Long.numberOfLeadingZeros(s)
    /** THE FROZEN VECTORS (iOS levelKAT 1966-1973): three refuses a logarithm rounded upward, one a bit length taken without its
     *  minus one, the edges a level that opens a second early or late. */
    fun levelKAT(): Boolean {
        if (level(1) != 0 || level(3) != 1 || level(1_048_576) != 20 || level(0) != null || level(-5) != null) return false
        for (k in 1..62) if (level(1L shl k) != k || level((1L shl k) - 1) != k - 1) return false
        return level(Long.MAX_VALUE) == 62
    }
    /**
     * THE GLOBAL LEVEL OF THE PERSON, ITS ONE READING (iOS global, MTWalletCore.swift:1980-1982; Canon, «The level of a person»:
     * min(p, w, f)): the lowest of the person's level and the wallet's level of π. DEVIATION: the third term, the fabric of time
     * (iOS MTFabricLevel), is not read on Android -- by iOS's own rule a fabric this life has not read adds no third term.
     */
    fun global(person: Int?, balance: Long): Int = minOf(person ?: 0, PiLevels.place(balance))

    private fun dir(): File = File(Book.ctx.filesDir, "TimeChain").apply { mkdirs() }
    /** The seconds are read before a node's answer asks for them: a face that shows the level needs them now (iOS prime). */
    @Synchronized fun prime() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(File(dir(), FILE).readText())
            val l = o.getString("lane")
            if (Base64.decode(l, Base64.NO_WRAP).size == 16) {
                lane = l
                val a = o.getJSONArray("spans")
                spans = joined((0 until a.length()).map { val s = a.getJSONObject(it); Span(s.getLong("a"), s.getLong("b")) })
            }
        }
        if (lane.isEmpty()) lane = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        recount(write = false)
        if (!checked) { checked = true; Log.i("Montana", "person_self ok=" + (if (levelKAT()) 1 else 0) + " seconds=" + seconds) }
    }
    private fun keep() {
        val a = JSONArray()
        for (s in spans) a.put(JSONObject().put("a", s.a).put("b", s.b))
        runCatching {
            val t = File(dir(), FILE + ".part")
            t.writeText(JSONObject().put("lane", lane).put("spans", a).toString())
            t.renameTo(File(dir(), FILE))
        }.onFailure { Log.w("Montana", "person keep: " + it.javaClass.simpleName) }
    }
    private fun keepSoon() {
        if (keepDue) return
        keepDue = true
        MainThread.later(10_000L, Runnable { Thread { synchronized(this) { keepDue = false; keep() } }.start() })
    }

    /** Spans joined: sorted, and any two that touch or overlap become one -- a second is counted once (iOS joined 2018-2024). */
    fun joined(all: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in all.filter { it.a <= it.b }.sortedBy { it.a }) {
            val l = out.lastOrNull()
            if (l != null && s.a <= l.b + 1) l.b = maxOf(l.b, s.b) else out.add(Span(s.a, s.b))
        }
        return out
    }
    /** One link a level, at the node second the count reached 2^k, each sealed on the one before (iOS chain 2026-2042). */
    fun chain(spans: List<Span>): List<TimeChain.Link> {
        val out = ArrayList<TimeChain.Link>()
        var count = 0L; var next = 1L; var prev = TimeChain.GENESIS
        for (s in spans) {
            val len = s.b - s.a + 1
            while (next <= count + len) {
                val at = (s.a + (next - count) - 1) * 1000
                val k = out.size
                val ref = "level:" + k
                val hash = TimeChain.seal(k + 1, at, "level", k.toLong(), ref, prev)
                out.add(TimeChain.Link(k + 1, at, "level", k.toLong(), ref, prev, hash))
                prev = hash
                if (Long.MAX_VALUE / 2 < next) return out
                next *= 2
            }
            count += len
        }
        return out
    }
    /** The count and the links read again; the chain's file follows a new level (iOS recount 2044-2056). */
    private fun recount(write: Boolean = true) {
        seconds = spans.fold(0L) { n, s -> n + (s.b - s.a + 1) }
        known = level(seconds)
        val fresh = chain(spans)
        if (fresh.lastOrNull()?.hash == links.lastOrNull()?.hash) return   // the head's seal seals the whole chain
        links = fresh
        if (!write) return
        runCatching { File(dir(), LINKS).writeText(fresh.joinToString("") { it.line() + "\n" }) }
    }

    /**
     * A node answered (NodeClock.learn; iOS personFeed and answered, MontanaWakePush.swift:1339-1347, MTWalletCore.swift:2060-2075):
     * fed once a second at most; while the app faces the person its second counts, and every second back to the answer before.
     */
    fun answered(serverMs: Long) {
        if (!onScreen || serverMs <= 0L) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - fedAt < 1000L) return
            fedAt = now
            prime()
            val s = serverMs / 1000
            val l = last
            val from = if (l != null && l <= s && s - l <= REACH) l else s
            last = maxOf(l ?: s, s)
            val before = seconds
            spans = joined(spans + Span(from, s))
            recount()
            if (seconds != before) keepSoon()
        }
    }
    /** The app left the screen: the next answer starts a stretch of its own; what is counted is on disk now, not ten seconds on. */
    fun left() { synchronized(this) { last = null; if (loaded) Thread { synchronized(this) { keep() } }.start() } }
    /** The person leaves with the words: their seconds and their chain go with them (iOS reread, the folder leaving). */
    @Synchronized fun forget() {
        loaded = false; spans = emptyList(); links = emptyList(); seconds = 0; known = null; last = null; lane = ""
        runCatching { File(dir(), FILE).delete(); File(dir(), LINKS).delete() }
        Prefs.remove(LevelAccess.LAST_KEY)
    }
}

/**
 * ACCESS BY LEVEL (iOS MTLevelAccess, MTWalletCore.swift:772-793; the author's words 16.10.2026 18:2x and 17.10.2026 18:1x MSK):
 * every function a level opens, named once with its level. The level is the Global Level (PersonChain.global); before this life has
 * read it, the last one this phone knew stands, so a launch's first second never drops a choice the level opened.
 */
object LevelAccess {
    enum class Feature(val level: Int) { KEEP_DELETED(7), AUTO_RECORD(7), OWN_NODE(8) }
    const val LAST_KEY = "levelAccess.last"   // NOT-UI: the last Global Level this phone knew of this person
    const val KEEP_KEY = "levelAccess.keepDeleted"   // NOT-UI: the person's choice, on once the level opens it
    val level: Int? get() {
        val p = PersonChain.known
        val c = CoinBook.said
        if (p == null || c == null) return Prefs.str(LAST_KEY, "").toIntOrNull()
        val g = PersonChain.global(p, c)
        if (Prefs.str(LAST_KEY, "") != g.toString()) Prefs.setStr(LAST_KEY, g.toString())
        return g
    }
    fun opens(f: Feature): Boolean = f.level <= (level ?: 0)
    /** The person keeps the letters their correspondents delete: the level opens it, and it stands until they untick it. */
    val keepsDeleted: Boolean get() = opens(Feature.KEEP_DELETED) && Prefs.bool(KEEP_KEY, true)
}
