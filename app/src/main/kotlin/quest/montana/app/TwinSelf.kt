package quest.montana.app

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ONE PERSON'S OWN NAME, FACE AND WORDS ON EVERY DEVICE OF THE SEED (iOS MTTwinSelf, MontanaPQ.swift, 56177404; the author's word
 * 20677, decision 884 = a: on T1 «Kit» stood as another name -- each device kept its own name and answered a peer with it). One
 * watcher of the person's own keys: what changed here rides the seed's twin mirror as a word with its moment, and every device
 * takes the later one; on a tie the larger print wins, so two devices that met with two names settle on one.
 * AND THE PERSON'S CHOICES (decision 886 = a): the pinned, muted, blocked and archived chats, the pinned and archived contacts, the
 * names given by hand and the contacts kept rode only the one-time copy. Each entry of these books rides by itself with its moment:
 * put or taken away here is said, a twin's entry is taken when later than the one held here. An entry met for the first time is
 * said at moment 0 and only joins -- a twin's older word never lifts a block, a name or a pin that stands here, as a copy never does.
 * The words and the moments are the iPhone's own (mark «TS:», the pipe «twin:self», the records mt.twin.self and mt.twin.book), so
 * an iPhone and this phone of one seed settle on one person.
 */
object TwinSelf {
    val KEYS = listOf("userName", "avatarData", "profileBio", "profileLink", "presenceSharing")
    val BOOKS = listOf("pinnedChatsList", "mutedChats", "blockedChats", "archivedNames", "pinnedContacts", "archivedContacts",
        "manualNames", "mtContacts")
    const val HELD = "mt.twin.self"   // iOS heldKey: the moment and the print of each own key last said (a copy carries it)
    const val BOOK = "mt.twin.book"   // iOS bookKey: each book's entries with the moment each was last said (a copy carries it)
    const val MARK = "​​TS:"   // iOS MTTwinSelf.mark: NOT-UI, one own key or a book's entries with their moment
    const val PIPE = "twin:self"   // iOS MTTwinSelf.pipe: the mirror's name for these words, never a conversation
    private const val BATCH = 64   // the entries one word carries, so a word stays small
    private const val FACE_MAX = 200_000

    private data class Held(val at: Double, val p: String)
    private data class Mark(val at: Double, val v: String?)   // v null: the entry was taken away

    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "twin-self").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var watching = false
    @Volatile private var due = false
    private val heard = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == null || k in KEYS || k.startsWith("block.") || k.startsWith("contact.") || k == "selfFaceCleared") soon()
    }
    private val moved: () -> Unit = { soon() }

    /** Once at launch (iOS MTTwinSelf.watch): a change of any own key or choice is looked at two seconds later, once for any number. */
    fun watch() {
        if (watching) return
        watching = true
        Prefs.listen(heard)
        ChatMarks.listen(moved)
        Book.listen(moved)
        soon()
    }
    /** A change the defaults do not tell (the face's file) is told here by its owner. */
    fun soon() {
        if (!watching || due) return
        due = true
        ex.schedule({ due = false; runCatching { look() }.onFailure { Log.w("Montana", "twin_self " + it.javaClass.simpleName) } }, 2, TimeUnit.SECONDS)
    }

    private fun obj(key: String): JSONObject =
        DeviceVault.get(key)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
    private fun held(): MutableMap<String, Held> {
        val o = obj(HELD); val m = HashMap<String, Held>()
        for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = Held(it.optDouble("at", 0.0), it.optString("p")) }
        return m
    }
    private fun keep(h: Map<String, Held>) {
        val o = JSONObject()
        for ((k, v) in h) o.put(k, JSONObject().put("at", v.at).put("p", v.p))
        DeviceVault.set(HELD, o.toString().toByteArray())
    }
    private fun book(): MutableMap<String, MutableMap<String, Mark>> {
        val o = obj(BOOK); val b = HashMap<String, MutableMap<String, Mark>>()
        for (k in o.keys()) o.optJSONObject(k)?.let { es ->
            val m = HashMap<String, Mark>()
            for (e in es.keys()) es.optJSONObject(e)?.let { x -> m[e] = Mark(x.optDouble("at", 0.0), if (x.has("v")) x.optString("v") else null) }
            b[k] = m
        }
        return b
    }
    private fun keepBook(b: Map<String, Map<String, Mark>>) {
        val o = JSONObject()
        for ((k, es) in b) o.put(k, JSONObject().also { j -> for ((e, m) in es) j.put(e, JSONObject().put("at", m.at).also { x -> m.v?.let { x.put("v", it) } }) })
        DeviceVault.set(BOOK, o.toString().toByteArray())
    }

    /** A key's value as it stands here (iOS bytes): the face as bytes, the words as text, the switch as 1 or 0; none and empty are one. */
    private fun bytes(k: String): ByteArray = when (k) {
        "avatarData" -> SelfFace.bytes(Book.ctx) ?: ByteArray(0)
        "presenceSharing" -> if (Prefs.has(k)) (if (Prefs.bool(k, true)) "1" else "0").toByteArray() else ByteArray(0)
        "userName" -> Prefs.userName.toByteArray()
        else -> Prefs.str(k, "").toByteArray()
    }
    private fun stamp(d: ByteArray): String =
        if (d.isEmpty()) "" else MessageDigest.getInstance("SHA-256").digest(d).take(8).joinToString("") { "%02x".format(it) }

    /** A book as it stands here, entry by entry (iOS entries): a set's entry holds "", a name given by hand its text. */
    private fun entries(k: String): Map<String, String> = when (k) {
        "blockedChats" -> PeerSafety.blocked().associateWith { "" }
        "manualNames" -> Book.pins().mapNotNull { (r, n) -> n?.let { r to it } }.toMap()
        "mtContacts" -> PeerContact.all().associateWith { "" }
        else -> (ChatMarks.carried()[k] ?: emptyList()).associateWith { "" }
    }

    private fun say(o: JSONObject, mid: String) = Twin.note(PIPE, mid, MARK + o.toString(), own = true)

    /**
     * WHAT CHANGED HERE SINCE IT WAS LAST SAID GOES TO THE TWINS (iOS MTTwinSelf.look). A key met for the first time is said at moment
     * 0 -- older than any change a person made -- and only when it holds something, so a fresh device never empties its twins.
     */
    fun look() = synchronized(lock) {
        if (MontanaSeed.twin == null || Twin.secret() == null) return@synchronized
        val h = held()
        val before = HashMap(h)
        val now = System.currentTimeMillis() / 1000.0
        for (k in KEYS) {
            val d = bytes(k); val p = stamp(d)
            if (h[k]?.p == p) continue
            val at = if (h[k] == null) 0.0 else now
            h[k] = Held(at, p)
            if (at == 0.0 && p.isEmpty()) continue
            val v = if (k == "avatarData") Base64.encodeToString(d, Base64.NO_WRAP) else String(d, Charsets.UTF_8)
            say(JSONObject().put("k", k).put("v", v).put("t", at), k + "." + (at * 1000).toLong())
            Log.d("Montana", "twin_self said $k at=" + at.toLong())
        }
        if (h != before) keep(h)
        val b = book()
        var changed = false
        for (k in BOOKS) {
            val cur = entries(k)
            val met = b.containsKey(k)
            val at = if (met) now else 0.0
            val marks = b[k] ?: HashMap()
            val said = ArrayList<JSONObject>()
            for ((e, v) in cur) if (marks[e]?.v != v) { marks[e] = Mark(at, v); said.add(JSONObject().put("e", e).put("v", v)) }
            for ((e, m) in marks.entries.toList()) if (m.v != null && !cur.containsKey(e)) { marks[e] = Mark(now, null); said.add(JSONObject().put("e", e)) }
            if (!met || said.isNotEmpty()) { b[k] = marks; changed = true }
            var i = 0
            while (i < said.size) {
                val s = JSONArray(); said.subList(i, minOf(i + BATCH, said.size)).forEach { s.put(it) }
                say(JSONObject().put("k", k).put("s", s).put("t", at), k + "." + (at * 1000).toLong() + "." + i)
                i += BATCH
            }
            if (said.isNotEmpty()) Log.d("Montana", "twin_self said $k n=" + said.size + " at=" + at.toLong())
        }
        if (changed) keepBook(b)
    }

    /**
     * A TWIN'S OWN KEY (iOS MTTwinSelf.take): taken only when later than the one held here (on a tie, when this one is empty or its
     * print smaller); the moment is kept BEFORE the value is written, so the watcher finds nothing to say back.
     */
    fun take(body: String) = synchronized(lock) {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@synchronized
        val k = o.optString("k")
        val t = (o.opt("t") as? Number)?.toDouble() ?: return@synchronized
        if (k in BOOKS) { o.optJSONArray("s")?.let { takeBook(k, it, t) }; return@synchronized }
        if (k !in KEYS) return@synchronized
        val v = o.opt("v") as? String ?: return@synchronized
        val d: ByteArray
        if (k == "avatarData") {
            val f = runCatching { Base64.decode(v, Base64.DEFAULT) }.getOrNull() ?: return@synchronized
            if (FACE_MAX < f.size) return@synchronized
            if (f.isNotEmpty()) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(f, 0, f.size, b)
                if (b.outWidth <= 0) return@synchronized
            }
            d = f
        } else {
            if (4096 < v.length) return@synchronized
            d = v.toByteArray()
        }
        val p = stamp(d)
        val h = held()
        val mine = h[k] ?: Held(0.0, stamp(bytes(k)))
        if (mine.p == p) return@synchronized
        val later = if (mine.at < t) true else if (mine.at == t) (mine.p.isEmpty() || mine.p < p) else false
        if (!later) { Log.d("Montana", "twin_self kept $k: the one here is later"); return@synchronized }
        h[k] = Held(t, p)
        keep(h)
        when (k) {
            "avatarData" -> if (d.isEmpty()) SelfFace.clear(Book.ctx) else SelfFace.set(Book.ctx, d)
            "presenceSharing" -> Presence.setSharing(v != "0")   // the one door of the switch: the beacons follow it
            "userName" -> Prefs.userName = v
            else -> Prefs.setStr(k, v)
        }
        Log.d("Montana", "twin_self took $k at=" + t.toLong())
    }

    /**
     * A TWIN'S ENTRIES OF ONE BOOK (iOS takeBook): each taken only when later than the one held here; on a tie an entry that stands
     * wins over its taking away, and of two names the larger. A book never met here is said first, at moment 0, so nothing of this
     * device is lost to the twin's word; the moments are kept before the book moves.
     */
    private fun takeBook(k: String, s: JSONArray, t: Double) {
        if (!book().containsKey(k)) look()
        val cur = entries(k)
        val b = book()
        val marks = b[k] ?: HashMap()
        val moved = ArrayList<Pair<String, String?>>()
        for (i in 0 until minOf(BATCH, s.length())) {
            val x = s.optJSONObject(i) ?: continue
            val e = x.opt("e") as? String ?: continue
            if (e.isEmpty() || 256 < e.length) continue
            val v = (x.opt("v") as? String)?.take(512)
            val mine = marks[e] ?: Mark(0.0, cur[e])
            if (mine.v == v) continue
            val later = if (mine.at < t) true else if (mine.at == t) (v != null && (mine.v == null || mine.v < v)) else false
            if (!later) continue
            marks[e] = Mark(t, v)
            moved.add(e to v)
        }
        if (moved.isEmpty()) return
        b[k] = marks
        keepBook(b)
        for ((e, v) in moved) runCatching { lay(k, e, v) }.onFailure { Log.w("Montana", "twin_self lay $k " + it.javaClass.simpleName) }
        Log.d("Montana", "twin_self took $k n=" + moved.size + " at=" + t.toLong())
    }

    /** An entry laid by the owner of its book, by the road a hand here would take: a block tells the peer, a name goes to its row. */
    private fun lay(k: String, e: String, v: String?) {
        val on = v != null
        when (k) {
            "pinnedChatsList" -> ChatMarks.setPinned(e, on)
            "mutedChats" -> ChatMarks.setMuted(e, on)
            "archivedNames" -> if (on) ChatMarks.archive(e) else ChatMarks.unarchive(e)
            "pinnedContacts" -> ChatMarks.setContactPinned(e, on)
            "archivedContacts" -> ChatMarks.setContactArchived(e, on)
            "blockedChats" -> PeerSafety.setBlocked(e, on)
            "manualNames" -> Book.pinName(e, v)
            "mtContacts" -> if (on) PeerContact.keep(e) else PeerContact.drop(e)
        }
    }
}
