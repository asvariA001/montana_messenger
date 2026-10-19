package quest.montana.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.BreakIterator
import java.util.UUID

/**
 * GROUPS AND CHANNELS, CARRIED BY THE PHONES THEMSELVES (iOS MTGroup, MontanaGroup.swift). No server holds a group: its owner's
 * phone holds a pipe with every member and carries every word to the others over those pipes — a star through the owner. This
 * phone takes the owner's words: the invitation that stands the group here, the letters of its people, their answers (a
 * reaction, an edit, a deletion) and the owner taking this phone out. The word on the wire is iOS's own: the mark, then JSON
 * with iOS's short keys; a key a reader does not know is skipped by it.
 */
object Groups {
    /** [P2P-COMPAT] iOS MTGroup.mark: a build that does not know it buries the word unread. */
    const val MARK = "​​GR:"
    // COMPAT-LOCAL: the feed's key and a speaker's reference name rows of this phone; neither rides the wire (iOS keyHead, speakerHead).
    private const val KEY_HEAD = "grp:"
    /** COMPAT-LOCAL: a row of the group's events, this phone's own, never on the wire (iOS MTGroupEvent.mark). */
    private const val EVENT_MARK = "\u200B\u200BGE:"
    private const val SPEAKER_HEAD = "gm:"
    private const val OWNER_SEAT = "0"
    private const val GROUP = "g"
    private const val CHANNEL = "c"
    private const val STORE = "groups.held"
    private const val COPIES = "groups.copies"
    /** A copy outlives the queue's own term by a day and is let go with it (iOS copyLife). */
    private const val COPY_LIFE_MS = 8L * 24 * 3600 * 1000
    private const val TITLE_LIMIT = 64
    private const val ABOUT_LIMIT = 255
    private const val NAME_LIMIT = 64
    private const val FACE_LIMIT = 24_000
    private const val FACE_SIDE = 160   // iOS faceSide
    /** [I-14]: a group is bounded by the people its owner carries to (iOS peopleLimit). */
    private const val PEOPLE_LIMIT = 200
    private const val PIECE_CHARS = 4500   // iOS ChatStore.sendPieceChars
    private const val HEARD_LIMIT = 512
    private val CROWNS = setOf(0x1F451, 0x2654, 0x2655, 0x265A, 0x265B)   // iOS MTCrown: only the Montana room wears a crown

    /** `INSTANT` -- a word that lived an instant (a typing word): it rode no queue, so nothing is receipted and nothing knocks again. */
    enum class Verdict { HELD, REFUSED, WAIT, INSTANT }

    class Member(val seat: String, val pipe: String)

    /** A group as this phone holds it (iOS MTGroupState): `owner` — the pipe to the owner, empty on the owner's phone; `me` — this phone's seat. */
    class State(
        val id: String, val kind: String, var title: String, var about: String, val owner: String, var me: String,
        val members: MutableList<Member>, var count: Int, val names: MutableMap<String, String>, val at: Long,
        var left: Boolean = false, var faceTag: String? = null,
        /** WHO READ THIS PHONE'S LETTERS, AND HOW FAR (iOS readBy, B.13): a reader's seat with the birth ms of the newest letter they read; never on the wire. */
        val readBy: MutableMap<String, Long> = mutableMapOf(),
        /** The owner took this phone out (their «out»), as against a leaving by its own hand: the head says which (iOS removed, stage R). */
        var removed: Boolean = false,
        /** The seats the owner named its administrators (iOS admins, stage R.6): named by the owner alone, in its invitation. */
        val admins: MutableList<String> = mutableListOf(),
    ) {
        val mine: Boolean get() = owner.isEmpty()
        fun json(): JSONObject = JSONObject().put("id", id).put("k", kind).put("ti", title).put("ds", about).put("ow", owner).put("me", me)
            .put("mb", JSONArray().apply { members.forEach { put(JSONObject().put("s", it.seat).put("p", it.pipe)) } })
            .put("c", count).put("nm", JSONObject(names.toMap())).put("at", at).put("lf", left).put("ft", faceTag ?: "")
            .put("rb", JSONObject(readBy.toMap())).put("rm", removed).put("ad", JSONArray(admins))
        companion object {
            fun of(o: JSONObject) = State(o.getString("id"), o.getString("k"), o.optString("ti"), o.optString("ds"), o.optString("ow"),
                o.getString("me"),
                o.optJSONArray("mb")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Member(it.getString("s"), it.getString("p")) } } } ?: mutableListOf(),
                o.optInt("c", 2),
                o.optJSONObject("nm")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optLong("at"), o.optBoolean("lf"), o.optString("ft").ifEmpty { null },
                o.optJSONObject("rb")?.let { r -> r.keys().asSequence().associateWith { r.getLong(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optBoolean("rm"), o.optJSONArray("ad")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf())
        }
    }

    private val lock = Any()
    private var loaded = false
    /**
     * A STORE THAT WOULD NOT OPEN IS NOT AN EMPTY ONE (iOS MTGroup.unread): it is read again before it is used, nothing is written
     * over it, and a word that needs it waits unanswered — one group written over an unread store would write every other away.
     */
    private var unread = false
    private val groups = LinkedHashMap<String, State>()
    /** Letters taken away before they came (iOS deletedMids of a group's feed): their late copy lands nowhere. */
    private val gone = mutableListOf<String>()
    /** The names of the newest answers already applied: a repeat of a copy is applied once (iOS answersHeard). */
    private val heard = mutableListOf<String>()

    /**
     * A COPY OF A GROUP'S WORD ON ITS WAY TO ONE RECEIVER (iOS MTGroupCopy): the queue knows it by its own name, the group knows by
     * this record whose row its receipt moves. `letter` — the row it carries, empty for a word of no row; `own` — a copy of a
     * letter of mine, its receipt a witness of my row; `of` — how many copies the letter left in.
     */
    private class Copy(val group: String, val letter: String, val to: String, val own: Boolean, var of: Int,
                       var held: Boolean = false, var failed: Boolean = false, val at: Long = System.currentTimeMillis()) {
        fun json(): JSONObject = JSONObject().put("g", group).put("l", letter).put("to", to).put("o", own).put("of", of)
            .put("h", held).put("f", failed).put("at", at)
        companion object {
            fun of(o: JSONObject) = Copy(o.getString("g"), o.optString("l"), o.getString("to"), o.optBoolean("o"), o.optInt("of", 1),
                o.optBoolean("h"), o.optBoolean("f"), o.optLong("at"))
        }
    }
    private val copies = LinkedHashMap<String, Copy>()

    private fun ready(): Boolean {
        if (loaded && !unread) return true
        loaded = true
        groups.clear(); gone.clear(); copies.clear()
        val raw = DeviceVault.get(STORE)
        val kept = DeviceVault.get(COPIES)
        unread = (raw == null && DeviceVault.has(STORE)) || (kept == null && DeviceVault.has(COPIES))
        if (raw != null) runCatching {
            val o = JSONObject(String(raw, Charsets.UTF_8))
            o.optJSONObject("g")?.let { g -> g.keys().forEach { k -> groups[k] = State.of(g.getJSONObject(k)) } }
            o.optJSONArray("gone")?.let { a -> for (i in 0 until a.length()) gone.add(a.getString(i)) }
        }.onFailure { groups.clear(); gone.clear(); unread = true }
        if (kept != null) runCatching {
            val o = JSONObject(String(kept, Charsets.UTF_8))
            val edge = System.currentTimeMillis() - COPY_LIFE_MS
            o.keys().forEach { k -> Copy.of(o.getJSONObject(k)).takeIf { edge < it.at }?.let { copies[k] = it } }
        }.onFailure { copies.clear(); unread = true }
        if (unread) Log.i("Montana", "group_unread the groups' store did not open -- read again before use")
        return !unread
    }
    private fun save() {
        if (unread) return
        val g = JSONObject(); groups.forEach { (k, v) -> g.put(k, v.json()) }
        DeviceVault.set(STORE, JSONObject().put("g", g).put("gone", JSONArray(gone)).toString().toByteArray(Charsets.UTF_8))
    }
    private fun saveCopies() {
        if (unread) return
        val o = JSONObject(); copies.forEach { (k, v) -> o.put(k, v.json()) }
        DeviceVault.set(COPIES, o.toString().toByteArray(Charsets.UTF_8))
    }
    /** The person leaves: their groups leave with them (iOS montanaSeedForgotten). */
    fun wipe() = synchronized(lock) {
        groups.clear(); gone.clear(); heard.clear(); copies.clear(); loaded = false; unread = false
        DeviceVault.delete(STORE); DeviceVault.delete(COPIES)
    }

    // ── names ──

    fun isKey(chat: String) = chat.startsWith(KEY_HEAD)
    private fun key(id: String) = KEY_HEAD + id
    private fun state(key: String): State? = synchronized(lock) { if (isKey(key) && ready()) groups[key.removePrefix(KEY_HEAD)] else null }
    /** The people in the group, the owner included — the head of the group's chat says this number (iOS people). */
    fun peopleLine(c: Context, key: String): String? = state(key)?.count?.let { c.resources.getQuantityString(R.plurals.gr_members, it, it) }
    /** Out of the group — it left, or the owner took it out: the chat says so instead of a field (iOS isOut). */
    fun isOut(key: String) = state(key)?.left == true
    /** A channel, not a group: its page is the channels' (iOS kind == .channel). */
    fun isChannel(key: String) = state(key)?.kind == CHANNEL
    /** Everyone writes in a group, the owner alone in a channel (iOS canWrite). */
    fun canWrite(key: String): Boolean { val g = state(key) ?: return false; return !g.left && (g.kind == GROUP || g.mine) }
    /** Whether this phone pins a letter of the group for everyone (iOS mayPin). */
    fun mayPin(key: String): Boolean { val g = state(key) ?: return false; return !g.left && pins(g, g.me) }

    /** The moment the group stood here: its row stands by it until its first letter (iOS stand puts the row at the top). */
    fun bornAt(key: String): Long = state(key)?.at ?: 0L

    private fun speaker(id: String, seat: String) = SPEAKER_HEAD + id + "/" + seat
    /**
     * The name a speaker's rows wear (iOS speakerName): the person as this phone knows them where it holds their pipe, else the
     * name they gave themselves in their last word.
     */
    fun speakerName(c: Context, ref: String?): String {
        val parts = ref?.takeIf { it.startsWith(SPEAKER_HEAD) }?.removePrefix(SPEAKER_HEAD)?.split("/", limit = 2)
        if (parts == null || parts.size != 2) return c.getString(R.string.gr_member)
        val g = state(key(parts[0])) ?: return c.getString(R.string.gr_member)
        val seat = parts[1]
        val pipe = if (!g.mine && seat == OWNER_SEAT) g.owner else g.members.firstOrNull { it.seat == seat }?.pipe
        val book = pipe?.takeIf { it.isNotEmpty() }?.let { Book.chat(SamePair.root(it))?.shown }
        return book?.ifBlank { null } ?: g.names[seat]?.ifEmpty { null } ?: c.getString(R.string.gr_member)
    }
    /** The line above a speaker's bubble (iOS speakerLine): in a group every letter of another says who wrote it; a channel speaks as itself. */
    fun speakerLine(c: Context, m: Msg, chat: String): String? =
        if (m.mine || m.from?.startsWith(SPEAKER_HEAD) != true || state(chat)?.kind != GROUP) null else speakerName(c, m.from)

    // ── the wire ──

    /**
     * ONE COPY TO ONE RECEIVER, UNDER A NAME OF ITS OWN (iOS carry): it has no row of its own — the row it carries stands in the
     * group's feed under the letter's name, and the copy's receipt moves that row (follow).
     */
    private fun carry(w: JSONObject, pipe: String, letter: String, own: Boolean): String? {
        if (Book.secret(pipe) == null) return null
        val name = Marks.mintMid()
        copies[name] = Copy(w.getString("g"), letter, pipe, own, 1)
        saveCopies()
        Post.send(pipe, name, MARK + w.toString())
        Log.i("Montana", "group_tx mid=" + name.take(14) + " t=" + w.optString("t") + " to=" + pipe.take(10))
        return name
    }

    /** A word to everyone this phone carries to in the group (iOS spread): the owner to every member but the speaker, a member to the owner. */
    private fun spread(w: JSONObject, g: State, except: String?, own: Boolean): Int {
        val targets = if (g.mine) g.members.filter { it.seat != except }.map { it.pipe } else listOf(g.owner)
        val names = targets.mapNotNull { carry(w, it, w.optString("id"), own) }
        names.forEach { copies[it]?.of = names.size }
        if (names.isNotEmpty()) saveCopies()
        return names.size
    }

    /**
     * The word's head (iOS MTGroupWord): its kind and its group; a letter carries the group's kind and title too — the receiver's
     * notification holds no group and names it by these — and every word the name this phone gives itself.
     */
    private fun word(t: String, g: State): JSONObject {
        val w = JSONObject().put("t", t).put("g", g.id)
        if (t == "say") w.put("k", g.kind).put("ti", g.title)
        clean(Prefs.userName, NAME_LIMIT).takeIf { it.isNotEmpty() }?.let { w.put("n", it) }
        return w
    }

    /**
     * A LETTER OF MINE INTO A GROUP (iOS ChatStore.send → MTGroup.send): long words leave as pieces (breakOutgoingText), each a row
     * born in the group's feed under its one name and a copy to everyone this phone carries to — on a member's phone, the owner;
     * the receipts raise the row (follow). Nobody to carry to: honest red. False when the group takes no letter from this phone.
     */
    fun send(key: String, words: String, qt: String?, qm: String?): Boolean {
        synchronized(lock) {
            val g = state(key)
            if (g == null || g.left || (g.kind != GROUP && !g.mine) || words.isBlank()) {
                Log.i("Montana", "group_refused send -- group=" + (if (g == null) 0 else 1))
                return false
            }
            pieces(words).forEachIndexed { i, piece ->
                if (!carries(piece)) return@forEachIndexed
                val first = i == 0
                val mid = Marks.mintMid()
                Book.edit(key) { it.msgs.add(Msg(mid, piece, true, Marks.birthMs(mid) ?: System.currentTimeMillis(),
                    qt = qt.takeIf { first }, qm = qm.takeIf { first })) }
                val w = word("say", g).put("id", mid).put("s", g.me).put("tx", piece)
                if (first && !qt.isNullOrEmpty()) w.put("qt", qt)
                if (first && !qm.isNullOrEmpty()) w.put("qm", qm)
                if (spread(w, g, null, own = true) == 0) Book.edit(key) { c -> c.msgs.find { it.mid == mid }?.advance(-1) }
            }
            return true
        }
    }

    /**
     * A MEDIA LETTER OF MINE INTO A GROUP (iOS MTGroup.carryMedia): its row stands in the feed already under its one name, its
     * file's sealed pieces lie on the nodes for every receiver — the node's term keeps them, no single receipt takes them away —
     * and its manifest leaves to everyone this phone carries to, as a word does. False: nobody took it.
     */
    fun carryMedia(key: String, mid: String, letter: String): Boolean {
        synchronized(lock) {
            val g = state(key)
            if (g == null || g.left || (g.kind != GROUP && !g.mine) || !letter.startsWith(Marks.MEDIA) || !carries(letter) || !isLetterName(mid)) {
                Log.i("Montana", "group_refused media -- group=" + (if (g == null) 0 else 1))
                return false
            }
            return spread(word("say", g).put("id", mid).put("s", g.me).put("tx", letter), g, null, own = true) > 0
        }
    }

    /**
     * A REACTION, AN EDIT OR A DELETION OF MINE (iOS MTGroup.signal): carried to every phone of the group like a letter and applied
     * there by the same road, never a row. Everyone in a group or a channel may react; only a letter's own speaker changes it or
     * takes it away (answered checks the seat on every phone).
     */
    fun signal(key: String, text: String) {
        synchronized(lock) {
            val g = state(key)
            if (g == null || g.left || !answers(text)) { Log.i("Montana", "group_refused answer -- group=" + (if (g == null) 0 else 1)); return }
            spread(word("sig", g).put("id", Marks.mintMid()).put("s", g.me).put("tx", text), g, null, own = false)
        }
    }

    // ── the pin for everyone (the checklist's B.11, the reference: «Pin this message and notify all members of the group?») ──

    /**
     * WHO PINS FOR EVERYONE (iOS MTGroup.pins): every member of a group while the group lets its members pin -- the reference's own
     * default for a new group; never in a channel. The one gate of a pin, asked where it is sent and wherever it lands; the
     * administrators and the group's permissions narrow it once they stand here (G.6, G.5).
     */
    private fun pins(g: State, seat: String): Boolean = g.kind == GROUP && seat.isNotEmpty()

    /**
     * A LETTER PINNED OR UNPINNED FOR EVERYONE (iOS MTGroup.pin): here at once, and its word carried to every phone of the group by
     * the answers' road; `notify` -- «Pin and notify all members» against «Only Pin» (n 0: pinned without a banner). The service row
     * «X pinned "..."» waits for the group's events (iOS lay, MTGroupEvent), which this build does not carry yet.
     */
    fun pin(key: String, m: Msg, pin: Boolean, notify: Boolean) {
        synchronized(lock) {
            val g = state(key)
            if (g == null || g.left || !pins(g, g.me) || !isLetterName(m.mid)) { Log.i("Montana", "group_refused pin -- group=" + (if (g == null) 0 else 1)); return }
        }
        if (pin) MsgPins.pin(key, m.mid) else MsgPins.unpin(key, m.mid)
        signal(key, PIN_MARK + JSONObject().put("sid", "mid:" + m.mid).put("op", if (pin) "pin" else "unpin").apply { if (!notify) put("n", 0) })
        if (pin) synchronized(lock) { state(key)?.let { lay(it, event("pinned", it.me, t = pinWords(m))) } }   // «You pinned "..."»
        Log.d("Montana", "pin_tx group " + (if (pin) "pin" else "unpin") + " notify=" + (if (notify) 1 else 0))
    }

    // ── typing (the checklist's B.12, the reference's «X is typing» in a group's head) ──

    /**
     * A GROUP'S TYPING WORD LIVES AN INSTANT (iOS MTGroup.typing): thrown at the pipes this phone holds for the group (Post.now) --
     * the owner's phone at every member, a member's at the owner -- never queued, never receipted, never written; the owner throws
     * a member's word on. The chat asks it at most every three seconds, while there are words in the field.
     */
    fun typing(key: String) {
        // TYPING IS PRESENCE (iOS fa7a5152, the critic's K-G1, MontanaPresencePrivacy.saysTyping): a phone that hides its presence says none
        if (!Presence.sharing) return
        val (w, pipes) = synchronized(lock) {
            val g = state(key) ?: return
            if (g.left || g.kind != GROUP) return
            word("ty", g).put("s", g.me) to (if (g.mine) g.members.map { it.pipe } else listOf(g.owner))
        }
        throwTyping(w, pipes)
    }
    /**
     * A person blocks only whom they hold a pipe with -- in a group the owner for a member and the members for the owner -- so the
     * check at the sender covers every block there is; the owner carrying another's word on passes it to nobody it blocked.
     */
    private fun throwTyping(w: JSONObject, pipes: List<String>) {
        val text = MARK + w.toString()
        pipes.filter { !PeerSafety.isBlocked(it) }.forEach { Post.now(it, text) }
    }
    /** The moment the owner last threw a member's typing on: once in two seconds a member, whatever it sends (iOS typingThrown, I-15). */
    private val typingThrown = HashMap<String, Long>()
    /** A TYPING WORD LANDED (iOS typed): its seat proven by the pipe it came by, as every word's is; the head shows it for the typing's life. */
    private fun typed(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.INSTANT
        if (g.left || g.kind != GROUP) return Verdict.INSTANT
        val from = SamePair.root(pipe)
        val seat: String
        if (g.mine) {
            val m = g.members.firstOrNull { SamePair.root(it.pipe) == from } ?: return Verdict.INSTANT
            if (PeerSafety.isBlocked(m.pipe)) return Verdict.INSTANT   // the owner takes no typing from a person it blocked (K-G1)
            seat = m.seat
            val k = g.id + "/" + seat
            val now = System.currentTimeMillis()
            if (now - (typingThrown[k] ?: 0L) >= 2000) {
                typingThrown[k] = now
                throwTyping(JSONObject(w.toString()).put("s", seat), g.members.filter { it.seat != seat }.map { it.pipe })
            }
        } else {
            val s = w.optString("s")
            if (SamePair.root(g.owner) != from || !isSeat(s) || s == g.me) return Verdict.INSTANT
            seat = s
        }
        // a person's name reaches the group from that person, as their letters bring it
        val name = clean(w.optString("n"), NAME_LIMIT)
        if (name.isNotEmpty() && g.names[seat] != name) { g.names[seat] = name; save() }
        typingHeard(key(g.id), seat)
        return Verdict.INSTANT
    }

    /** WHO TYPES IN A GROUP NOW (iOS MTGroupTyping): seats by the moment their word landed, each alive the typing's life. */
    private val typers = HashMap<String, HashMap<String, Long>>()
    private const val TYPING_LIFE = 5_000L   // iOS ChatStore.typingLife
    private fun typingHeard(key: String, seat: String) {
        synchronized(typers) { typers.getOrPut(key) { HashMap() }[seat] = System.currentTimeMillis() }
        Log.d("Montana", "group_typing_rx seat=" + seat.take(4))
        Presence.redraw()
        MainThread.later(TYPING_LIFE + 100, Runnable { Presence.redraw() })
    }
    private fun typingStopped(key: String, seat: String) {
        if (synchronized(typers) { typers[key]?.remove(seat) != null }) Presence.redraw()
    }
    /** The head's line while someone types, in the reference's words -- one name, two names, a name and how many others; null when nobody does. */
    fun typingLine(c: Context, key: String): String? {
        val now = System.currentTimeMillis()
        val seats = synchronized(typers) { typers[key]?.entries?.filter { now - it.value < TYPING_LIFE }?.sortedBy { it.value }?.map { it.key } }
        if (seats.isNullOrEmpty()) return null
        val id = key.removePrefix(KEY_HEAD)
        val names = seats.map { speakerName(c, speaker(id, it)) }
        return when (names.size) {
            1 -> c.getString(R.string.gr_typing_one, names[0])
            2 -> c.getString(R.string.gr_typing_two, names[0], names[1])
            else -> c.getString(R.string.gr_typing_many, names[0], (names.size - 1).toString())
        }
    }

    // ── read (the checklist's B.13, the reference's small group: a letter read, and who saw it; iOS fa7a5152) ──

    /** How far this phone told each group it read (iOS readTold): one word a moment, and only when the mark moved. Held in memory. */
    private val readTold = HashMap<String, Long>()
    /** How far the owner told the speakers each reader read, by group and reader (iOS readSpread): once for each new reach. */
    private val readSpread = HashMap<String, Long>()
    /** The groups whose read mark is due (iOS readMarkWork, 0.8 s): a run of letters landing is told by one word. */
    private val readDue = HashSet<String>()

    /** THE GROUP ON SCREEN READ ITS LETTERS (iOS ChatStore.sendReadMark for a group): told a moment later, while it is still on screen. */
    fun readSoon(key: String) {
        if (!synchronized(readDue) { readDue.add(key) }) return
        MainThread.later(800, Runnable {
            synchronized(readDue) { readDue.remove(key) }
            if (Book.openChat == key && Presence.shown) Thread { tellRead(key) }.start()
        })
    }

    /**
     * THIS PHONE READ THE GROUP (iOS MTGroup.tellRead): the birth millisecond of the newest letter of another before its eyes, told
     * as its own word «rd» to the owner, who tells each speaker it covers; the owner's own reading goes to them straight. Never
     * with «Read receipts» off -- the switch is the person's word about themselves.
     */
    private fun tellRead(key: String) {
        synchronized(lock) {
            if (!Prefs.bool("readReceiptsEnabled", true)) return
            val g = state(key) ?: return
            if (g.kind != GROUP || g.left) return
            val ms = Book.chat(key)?.msgs?.toList()?.filter { !it.mine }?.mapNotNull { Marks.birthMs(it.mid) }?.maxOrNull() ?: return
            if (ms <= (readTold[g.id] ?: 0L)) return
            readTold[g.id] = ms
            if (g.mine) spreadRead(g, g.me, ms)
            else carry(JSONObject().put("t", "rd").put("g", g.id).put("id", Marks.mintMid()).put("s", g.me).put("tx", ms.toString()), g.owner, "", own = false)
            Log.d("Montana", "read_tx group upto=" + Diary.shortMs(ms))
        }
    }

    /**
     * THE OWNER TELLS EACH SPEAKER A READER REACHED (iOS spreadRead): the seats of the letters born after the reader's last mark and
     * up to the new one, each told by a word of its own (ts names it); the owner's own letters are read here.
     */
    private fun spreadRead(g: State, reader: String, ms: Long) {
        val k = g.id + "/" + reader
        val since = readSpread[k] ?: 0L
        if (ms <= since) return
        readSpread[k] = ms
        val head = speaker(g.id, "")
        val speakers = HashSet<String>()
        for (r in Book.chat(key(g.id))?.msgs?.toList().orEmpty()) {
            val born = Marks.birthMs(r.mid) ?: continue
            if (born <= since || ms < born) continue
            val seat = if (r.mine) g.me else r.from?.takeIf { it.startsWith(head) }?.removePrefix(head) ?: ""
            if (seat.isNotEmpty() && seat != reader) speakers.add(seat)
        }
        if (g.me in speakers) readMine(g, reader, ms)
        for (m in g.members) if (m.seat in speakers) {
            val w = JSONObject().put("t", "rd").put("g", g.id).put("id", Marks.mintMid()).put("s", reader).put("tx", ms.toString())
                .put("ts", JSONArray(listOf(m.seat)))
            carry(w, m.pipe, "", own = false)
        }
    }

    /** A READER REACHED MY LETTERS (iOS readMine): their mark kept, and every letter of mine born up to it raised to read by the ladder's one door. */
    private fun readMine(g: State, reader: String, ms: Long) {
        if (reader == g.me || ms <= (g.readBy[reader] ?: 0L)) return
        g.readBy[reader] = ms
        save()
        var raised = 0
        Book.edit(key(g.id)) { c -> c.msgs.forEach { if (it.mine && (Marks.birthMs(it.mid) ?: Long.MAX_VALUE) <= ms && it.advance(3)) raised++ } }
        Log.d("Montana", "read_rx group upto=" + Diary.shortMs(ms) + " raised=" + raised)
    }

    /**
     * A READER'S WORD (iOS readWord): at the owner, the reader's seat is the pipe's own, never the word's; at a member, the owner's
     * word for a reader of this phone's letters.
     */
    private fun readWord(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        val ms = w.optString("tx").toLongOrNull()
        if (g.left || g.kind != GROUP || ms == null || ms <= 0) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        if (g.mine) {
            val m = g.members.firstOrNull { SamePair.root(it.pipe) == from } ?: return Verdict.REFUSED
            spreadRead(g, m.seat, ms)
        } else {
            val s = w.optString("s")
            val ts = w.optJSONArray("ts")
            val forMe = ts == null || (0 until ts.length()).any { ts.optString(it) == g.me }
            if (SamePair.root(g.owner) != from || !isSeat(s) || s == g.me || !forMe) return Verdict.REFUSED
            readMine(g, s, ms)
        }
        return Verdict.HELD
    }

    /** THE READERS OF A LETTER OF MINE, BY NAME (iOS seenBy, the reference's «N Seen» over the letter's menu); null outside a group. */
    fun seenBy(c: Context, m: Msg, key: String): List<String>? {
        val g = state(key) ?: return null
        if (g.kind != GROUP || !m.mine) return null
        val ms = Marks.birthMs(m.mid) ?: return null
        val seats = synchronized(lock) { g.readBy.filterValues { ms <= it }.keys.toList() }
        return seats.map { speakerName(c, speaker(g.id, it)) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    // ── birth: this phone owns the group ──

    /**
     * A GROUP OR A CHANNEL IS BORN ON THIS PHONE (iOS MTGroup.create): its owner's phone. The people are the correspondents chosen,
     * each by the pipe this phone holds with them; each is told by an invitation of their own, with a seat of their own. The row
     * stands at once; the invitations ride the queue until each is receipted. The key of the group's feed, or null when nobody
     * chosen can be carried to.
     */
    fun create(channel: Boolean, title: String, about: String, face: ByteArray?, people: List<String>): String? {
        synchronized(lock) {
            val name = clean(title, TITLE_LIMIT)
            val pipes = people.map { SamePair.root(it) }.filter { !isKey(it) && Book.secret(it) != null }.distinct().take(PEOPLE_LIMIT)
            if (!ready() || name.isEmpty() || pipes.isEmpty()) {
                Log.i("Montana", "group_refused birth -- title=" + (if (name.isEmpty()) 0 else 1) + " people=" + pipes.size)
                return null
            }
            val id = mintId()
            val taken = mutableSetOf(OWNER_SEAT)
            val members = pipes.map { p ->
                var seat = mintSeat()
                while (seat in taken) seat = mintSeat()
                taken.add(seat)
                Member(seat, p)
            }.toMutableList()
            val g = State(id, if (channel) CHANNEL else GROUP, name, clean(about, ABOUT_LIMIT), "", OWNER_SEAT, members, members.size + 1,
                mutableMapOf(), System.currentTimeMillis())
            groups[id] = g
            save()
            stand(g, face)
            lay(g, event("made", OWNER_SEAT, t = name))   // «You created the group»
            tellAll(g, face?.let { thumb(it) })
            Log.i("Montana", "group_born kind=" + g.kind + " people=" + g.count)
            return key(id)
        }
    }

    /**
     * The invitation's word to every member (iOS tellAll): the title, the words about the group, the count, the receiver's own
     * seat, and the face where it is given — `only`, the seats the face goes to (the people just added).
     */
    private fun tellAll(g: State, face: String?, only: Set<String>? = null) {
        for (m in g.members) {
            val w = word("inv", g).put("k", g.kind).put("ti", g.title).put("c", g.count).put("me", m.seat)
            if (g.about.isNotEmpty()) w.put("ds", g.about)
            if (face != null && (only == null || m.seat in only)) w.put("fc", face)
            if (g.admins.isNotEmpty()) w.put("ad", JSONArray(g.admins))   // the administrators the owner named (iOS ad, stage R.6)
            carry(w, m.pipe, "", own = false)
        }
    }

    // ── the group's life: its name and face, its people, leaving ──

    /** One person of the group's page: the seat, the name it wears here, and the pipe this phone holds with them. */
    class Seat(val seat: String, val name: String, val pipe: String)
    /** The people of the group's page (iOS shownSeats): the owner's phone lists everyone it carries to, a member's the owner. */
    fun shownSeats(c: Context, key: String): List<Seat> {
        val g = state(key) ?: return emptyList()
        return if (g.mine) g.members.map { Seat(it.seat, speakerName(c, speaker(g.id, it.seat)), it.pipe) }
            else listOf(Seat(OWNER_SEAT, speakerName(c, speaker(g.id, OWNER_SEAT)), g.owner))
    }
    /** The owner's seat, as the page compares it. */
    const val OWNER = OWNER_SEAT
    /**
     * THE PEOPLE OF A GROUP'S PAGE (iOS pagePeople, stage R -- the reference folder lists every member): the owner's phone everyone it
     * carries to; a member's phone the owner first, then everyone who spoke -- the owner names nobody, the others come by their own words.
     */
    fun pagePeople(c: Context, key: String): List<Seat> {
        val g = state(key) ?: return emptyList()
        val out = shownSeats(c, key).toMutableList()
        if (!g.mine) synchronized(lock) { g.names.toMap() }.filter { (s, _) -> s != OWNER_SEAT && s != g.me && out.none { it.seat == s } }
            .map { (s, _) -> Seat(s, speakerName(c, speaker(g.id, s)), "") }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { out.add(it) }
        return out
    }
    // ── mentions (iOS stage R, the reference folder: «@» names a person of the group) ──

    /** A group proper, not a channel (iOS kind == .group): only its field raises the «@» plate and only its letters name me. */
    fun isGroup(key: String) = state(key)?.kind == GROUP
    /**
     * The people a «@» may name in a group (iOS mentionable): everyone this phone knows by name in it -- the page's people -- this
     * phone aside, and nobody known only as «Member»; by their names.
     */
    fun mentionable(c: Context, key: String): List<Seat> {
        val g = state(key)?.takeIf { it.kind == GROUP } ?: return emptyList()
        val member = c.getString(R.string.gr_member)
        return pagePeople(c, key).filter { it.seat != g.me && it.name != member }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    /** The word after the last «@» at the text's end (iOS MTMentionCompose.tail): «@» at the start or after a space, then no space, 32 letters at most. */
    fun mentionTail(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0 || (0 < at && !text[at - 1].isWhitespace())) return null
        val rest = text.substring(at + 1)
        return if (rest.length <= 32 && rest.none { it.isWhitespace() }) rest else null
    }
    /**
     * A LETTER THAT NAMES ME (iOS mentionsMe): «@» and the name I go by -- my own name or my nick. The name must end where a word ends:
     * «@Al» is not a mention of a person named «Al» inside «@Alex». It rings even in a chat whose sound is off (Notify.letter).
     */
    fun mentionsMe(words: String): Boolean {
        val low = words.lowercase()
        return listOf(Prefs.userName, Names.heldName ?: "").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { name ->
            var from = 0
            while (true) {
                val at = low.indexOf("@$name", from)
                if (at < 0) break
                val end = at + 1 + name.length
                if (end == low.length || !low[end].isLetterOrDigit()) return@any true
                from = end
            }
            false
        }
    }

    /** This phone owns the group: its page edits it (iOS state.mine). */
    fun owns(key: String) = state(key)?.mine == true
    /** Whether the owner's phone already carries the group to this person (iOS carries(to:in:)): the page's «add» lists the others only. */
    fun carriesTo(pipe: String, key: String): Boolean {
        val p = SamePair.root(pipe)
        return state(key)?.members?.any { SamePair.root(it.pipe) == p } == true
    }

    /**
     * THE OWNER RENAMES THE GROUP OR GIVES IT A NEW FACE (iOS renew): every member is told by the invitation's own word again —
     * the same group, the same seat, the new title and face — so every phone shows what the owner's shows.
     */
    fun renew(key: String, title: String, face: ByteArray?, about: String? = null) {
        synchronized(lock) {
            val g = state(key) ?: return
            val name = clean(title, TITLE_LIMIT)
            // the description, the owner's words about the group (iOS renew about, stage R): carried in the invitation's ds
            val words = about?.let { clean(it, ABOUT_LIMIT) }
            val described = words != null && words != g.about
            if (!g.mine || name.isEmpty() || (name == g.title && face == null && !described)) return
            val renamed = name != g.title
            g.title = name
            if (words != null) g.about = words
            save()
            stand(g, face)
            tellAll(g, face?.let { thumb(it) })
            if (renamed) lay(g, event("named", OWNER_SEAT, t = name))
            if (face != null) lay(g, event("face", OWNER_SEAT))
            Log.i("Montana", "group_renewed face=" + (if (face == null) 0 else 1) + " about=" + (if (described) 1 else 0))
        }
    }
    /** The owner's words about the group, read by everyone on its page (iOS about, stage R). */
    fun about(key: String): String = state(key)?.about ?: ""

    /** THE OWNER ADDS PEOPLE (iOS add): each new one is invited with a seat of their own and the group's face; everyone learns the count. */
    fun add(key: String, people: List<String>) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine) return
            val held = g.members.map { SamePair.root(it.pipe) }.toSet()
            val taken = (g.members.map { it.seat } + OWNER_SEAT).toMutableSet()
            val fresh = mutableListOf<Member>()
            for (p in people.map { SamePair.root(it) }.distinct()) {
                if (isKey(p) || Book.secret(p) == null || p in held || g.members.size + fresh.size >= PEOPLE_LIMIT) continue
                var seat = mintSeat()
                while (seat in taken) seat = mintSeat()
                taken.add(seat)
                fresh.add(Member(seat, p))
            }
            if (fresh.isEmpty()) return
            g.members.addAll(fresh)
            g.count = g.members.size + 1
            save()
            val face = Book.face(key).takeIf { it.exists() }?.readBytes()?.let { thumb(it) }
            tellAll(g, face, fresh.map { it.seat }.toSet())
            val came = event("added", OWNER_SEAT, fresh.map { it.seat })
            lay(g, came)
            tellEvent(g, came)
            Log.i("Montana", "group_added people=" + fresh.size + " count=" + g.count)
        }
    }

    /**
     * THE OWNER TAKES A PERSON OUT (iOS remove): told by their own word, carried to no more; everyone left learns the count and reads
     * the row «X removed Y» -- `by`, the administrator whose ask it was, says its own name (stage R.6).
     */
    fun remove(key: String, seat: String, by: String = OWNER_SEAT) {
        synchronized(lock) {
            val g = state(key) ?: return
            val m = (if (g.mine) g.members.firstOrNull { it.seat == seat } else null) ?: return
            keepName(g, m)
            g.members.removeAll { it.seat == seat }
            g.admins.remove(seat)   // a person taken out is nobody's administrator
            g.count = g.members.size + 1
            save()
            carry(JSONObject().put("t", "out").put("g", g.id), m.pipe, "", own = false)
            tellAll(g, null)
            val out = event("removed", by, listOf(seat))
            lay(g, out)
            tellEvent(g, out)
            Log.i("Montana", "group_removed count=" + g.count)
        }
    }
    /** A person leaving the owner's book keeps the name the rows call them by: «You removed Anna», never «You removed Member». */
    private fun keepName(g: State, m: Member) {
        Book.chat(SamePair.root(m.pipe))?.shown?.ifBlank { null }?.let { g.names[m.seat] = clean(it, NAME_LIMIT) }
    }

    // ── administrators (iOS stage R.6, the reference folder: «Promote to Admin», «Dismiss Admin») ──

    /** Whether a seat is an administrator the owner named. */
    fun isAdmin(key: String, seat: String): Boolean = state(key)?.admins?.contains(seat) == true
    /** This phone's own seat in the group. */
    fun mySeat(key: String): String? = state(key)?.me
    /** Whether this phone moderates the group (iOS moderates): its owner or an administrator it named -- they take another's letter away for everyone. */
    fun moderates(key: String): Boolean {
        val g = state(key) ?: return false
        return !g.left && g.kind == GROUP && (g.mine || g.me in g.admins)
    }
    /** THE OWNER NAMES OR DISMISSES AN ADMINISTRATOR (iOS setAdmin): every member hears the list by the invitation's word again. */
    fun setAdmin(key: String, seat: String, on: Boolean) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.kind != GROUP || g.members.none { it.seat == seat }) return
            g.admins.remove(seat)
            if (on) g.admins.add(seat)
            g.admins.sort()
            save()
            tellAll(g, null)
            Log.i("Montana", "group_admin " + (if (on) "named" else "dismissed"))
        }
    }
    /**
     * TAKE A PERSON OUT (iOS kick): the owner's own hand; an administrator asks the owner, whose phone carries the group (its «kk»)
     * -- never the owner, never another administrator.
     */
    fun kick(key: String, seat: String) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (g.left) return
            if (g.mine) { remove(key, seat); return }
            if (g.me !in g.admins || seat == OWNER_SEAT || seat == g.me || seat in g.admins) return
            carry(JSONObject().put("t", "kk").put("g", g.id).put("id", Marks.mintMid()).put("s", seat), g.owner, "", own = false)
            Log.i("Montana", "group_admin asked the owner to take a person out")
        }
    }
    /** An administrator's «take out» at the owner's phone (iOS kicked): the asker an administrator, the person a member who is none. */
    private fun kicked(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.REFUSED
        if (!g.mine || g.left) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val asker = g.members.firstOrNull { SamePair.root(it.pipe) == from } ?: return Verdict.REFUSED
        val target = w.optString("s")
        if (asker.seat !in g.admins || target == asker.seat || target in g.admins || g.members.none { it.seat == target }) return Verdict.REFUSED
        remove(key(g.id), target, by = asker.seat)
        return Verdict.HELD
    }

    // ── the group's events: rows in the middle of its feed (iOS MTGroupEvent, lay, stage R) ──

    /**
     * AN EVENT OF THE GROUP STANDS IN ITS FEED (iOS lay): this phone's own row, read, drawn in the middle of the feed as the day is;
     * it rings nothing and is never sent (iOS appendGroupEvent). A channel lays none.
     */
    private fun lay(g: State, e: JSONObject) {
        if (g.kind != GROUP) return
        val mid = Marks.mintMid()
        val row = EVENT_MARK + e.put("g", g.id).toString()
        Book.edit(key(g.id)) { c -> c.msgs.add(Msg(mid, row, true, Marks.birthMs(mid) ?: System.currentTimeMillis(), state = 3)); c.msgs.sortBy { it.at } }
        Log.i("Montana", "group_event laid e=" + e.optString("e"))
    }
    /** An event as iOS MTGroupEvent writes it: e -- what happened, a -- the seat that did it, p -- the seats it was about, t -- a title or a pin's words. */
    private fun event(e: String, a: String, p: List<String>? = null, t: String? = null): JSONObject =
        JSONObject().put("e", e).put("a", a).apply { if (!p.isNullOrEmpty()) put("p", JSONArray(p)); if (t != null) put("t", t) }
    /** The owner tells everyone else who came, who was taken out, who left (iOS tellEvent) -- by seat, never by a name of the owner's. */
    private fun tellEvent(g: State, e: JSONObject) {
        if (!g.mine || g.kind != GROUP) return
        val w = JSONObject().put("t", "ev").put("g", g.id).put("id", Marks.mintMid()).put("s", OWNER_SEAT).put("tx", JSONObject(e.toString()).put("g", g.id).toString())
        val about = e.optJSONArray("p")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        for (m in g.members) if (m.seat !in about) carry(w, m.pipe, "", own = false)
    }
    /** AN EVENT FROM THE OWNER (iOS evented, its «ev»): taken from the owner's pipe alone, about seats alone, once by its name. */
    private fun evented(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left || g.mine || SamePair.root(g.owner) != SamePair.root(pipe)) return Verdict.REFUSED
        val id = w.optString("id")
        val tx = w.optString("tx")
        if (!isLetterName(id) || tx.isEmpty() || tx.toByteArray().size > 4096) return Verdict.REFUSED
        val e = runCatching { JSONObject(tx) }.getOrNull() ?: return Verdict.REFUSED
        val kind = e.optString("e")
        val a = e.optString("a")
        if (kind !in setOf("added", "removed", "left", "joined") || !isSeat(a)) return Verdict.REFUSED
        if (id in heard) return Verdict.HELD
        remember(id)
        val about = e.optJSONArray("p")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { isSeat(it) }.take(PEOPLE_LIMIT) }
        lay(g, event(kind, a, about))
        return Verdict.HELD
    }
    /** A row of the group's events, this phone's own (iOS MTRowLetter.ownRow): no rung, no menu, no banner. */
    fun isEvent(text: String) = text.startsWith(EVENT_MARK)
    /** The sentence an event's row says on this phone (iOS eventWords): the people by the names this group knows them by, «you» for this phone. */
    fun eventWords(c: Context, text: String): String? {
        if (!isEvent(text)) return null
        val e = runCatching { JSONObject(text.removePrefix(EVENT_MARK)) }.getOrNull() ?: return null
        val id = e.optString("g")
        val me = state(key(id))?.me
        val a = e.optString("a")
        val mine = a == me
        val who = if (mine) "" else speakerName(c, speaker(id, a))
        val about = e.optJSONArray("p")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
        val you = me != null && me in about
        val names = about.filter { it != me }.joinToString(", ") { speakerName(c, speaker(id, it)) }
        val t = e.optString("t")
        return when (e.optString("e")) {
            "made" -> if (mine) c.getString(R.string.ge_made_me, t) else c.getString(R.string.ge_made, who, t)
            "added" -> if (mine) c.getString(R.string.ge_added_me, names) else if (you) c.getString(R.string.ge_added_you, who) else c.getString(R.string.ge_added, who, names)
            "removed" -> if (mine) c.getString(R.string.ge_removed_me, names) else if (you) c.getString(R.string.ge_removed_you, who) else c.getString(R.string.ge_removed, who, names)
            "left" -> if (mine) c.getString(R.string.ge_left_me) else c.getString(R.string.ge_left, who)
            "named" -> if (mine) c.getString(R.string.ge_named_me, t) else c.getString(R.string.ge_named, who, t)
            "face" -> if (mine) c.getString(R.string.ge_face_me) else c.getString(R.string.ge_face, who)
            "pinned" -> if (mine) c.getString(R.string.ge_pinned_me, t) else c.getString(R.string.ge_pinned, who, t)
            else -> null
        }
    }
    /** The pinned letter's words as its service row quotes them (iOS pinWords): the list's own preview, cut at forty letters. */
    private fun pinWords(m: Msg): String {
        val w = letterWords(Book.ctx, m)
        return if (w.length <= 40) w else w.take(40) + "…"
    }
    /** What the head of a group's chat says once this phone is out of it (iOS outWords): removed by the owner, or left. */
    fun outWords(c: Context, key: String): String? {
        val g = state(key) ?: return null
        if (!g.left) return null
        val channel = g.kind == CHANNEL
        return c.getString(if (g.removed) { if (channel) R.string.gr_out_removed_ch else R.string.gr_out_removed }
            else { if (channel) R.string.gr_out_left_ch else R.string.gr_out_left })
    }

    // ── the way out (iOS stage R: «Delete and Exit», «Leave Group», «Delete Group») ──

    /**
     * DELETE AND EXIT (iOS erase): the chat and its rows leave this phone; the group stays known as one this phone is out of, so a
     * word still on its way finds its road over -- only the owner adding this phone again stands it anew.
     */
    fun erase(key: String) {
        if (state(key)?.left != true) return
        forgetChat(key)
        Log.i("Montana", "group_erased")
    }
    /** LEAVE GROUP (iOS leaveAndErase, «Are you sure you want to leave and delete %@?»): the owner is told, and the chat goes. */
    fun leaveAndErase(key: String) { leave(key); erase(key) }
    /**
     * DELETE FOR ALL (iOS dissolve, the owner's «Delete Group»): every member is told by the owner's own «out» marked as the group's
     * end (dl 1) -- a build that reads the mark erases the chat, an older one stands it read-only -- and the chat leaves this phone.
     */
    fun dissolve(key: String) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left) return
            for (m in g.members) carry(JSONObject().put("t", "out").put("g", g.id).put("dl", 1), m.pipe, "", own = false)
            g.left = true
            g.members.clear()
            save()
            Log.i("Montana", "group_dissolved by its owner")
        }
        erase(key)
    }

    /** A MEMBER LEAVES (iOS leave): the owner is told and carries nothing more to this phone; the group's rows stay readable here. */
    fun leave(key: String) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (g.mine || g.left) return
            g.left = true
            save()
            carry(JSONObject().put("t", "bye").put("g", g.id), g.owner, "", own = false)
            Log.i("Montana", "group_left by this phone")
        }
        Book.edit(key) {}   // the open chat trades its field for the note at once
    }

    private fun mintId() = UUID.randomUUID().toString().replace("-", "").lowercase()
    private fun mintSeat() = mintId().take(8)
    /** The face an invitation carries (iOS thumb): small, so the word stays a letter and not a file. */
    private fun thumb(face: ByteArray): String? {
        val b = BitmapFactory.decodeByteArray(face, 0, face.size) ?: return null
        val side = minOf(b.width, b.height)
        val square = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, FACE_SIDE, FACE_SIDE, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).takeIf { it.length <= FACE_LIMIT }
    }

    // ── the receipts of the copies ──

    /** The node holds a copy (Post.flush): my row has left this phone. True when the name was a group's copy. */
    fun copyHeld(name: String): Boolean {
        synchronized(lock) {
            val c = (if (ready()) copies[name] else null) ?: return false
            if (!c.held) { c.held = true; saveCopies() }
            if (c.own) follow(c)
            return true
        }
    }
    /** A copy was receipted (Post.place, «delivered» on its receiver's pipe): it witnesses the group's row, never a row of the pipe. */
    fun copyDelivered(name: String): Boolean {
        synchronized(lock) {
            val c = (if (ready()) copies.remove(name) else null) ?: return false
            saveCopies()
            Log.i("Montana", "group_rcpt mid=" + name.take(14) + " own=" + (if (c.own) 1 else 0))
            if (c.own) follow(c)
            return true
        }
    }
    /** The copy's pipe was gone before it left (Post.flush): my row says so (iOS copySettled, red with no pipe). */
    fun copyLost(name: String): Boolean {
        synchronized(lock) {
            val c = (if (ready()) copies[name] else null) ?: return false
            if (!c.failed) { c.failed = true; saveCopies() }
            if (c.own) follow(c)
            return true
        }
    }
    /**
     * MY ROW STANDS WHERE ITS RECEIVERS SAY (iOS follow): delivered when every copy is receipted, red while a copy has failed, sent
     * once the node holds one or one is receipted — through the rung's one door (Msg.advance: forward only, red only from below).
     */
    private fun follow(c: Copy) {
        if (c.letter.isEmpty()) return
        val left = copies.values.filter { it.own && it.group == c.group && it.letter == c.letter }
        val next = when {
            left.isEmpty() -> 2
            left.any { it.failed } -> -1
            left.size < c.of || left.any { it.held } -> 1
            else -> return
        }
        Book.edit(key(c.group)) { ch -> ch.msgs.find { it.mine && it.mid == c.letter }?.advance(next) }
    }

    // ── arrival ──

    /**
     * A GROUP'S WORD ARRIVED ON A PIPE (iOS MTGroup.handle, Post.place before any row): never a row of the pipe. Held or refused,
     * the copy is receipted by its own name on the pipe it came by; a word that must wait is not, and its copy knocks again.
     */
    fun handle(pipe: String, sid: String, text: String) {
        val w = runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull()
        val verdict = if (w != null && isLabel(w.optString("g"))) synchronized(lock) { take(w, pipe) }
            else Verdict.REFUSED.also { Log.i("Montana", "group_refused unreadable from=${pipe.take(10)}") }
        if (verdict != Verdict.WAIT && verdict != Verdict.INSTANT) Post.receiptFor(pipe, sid)
    }

    private fun take(w: JSONObject, pipe: String): Verdict {
        if (!ready()) return Verdict.WAIT   // the copy knocks again once the store opens
        return when (val t = w.optString("t")) {
            "inv" -> invited(w, pipe)
            "say" -> said(w, pipe)
            "sig" -> answered(w, pipe)
            "out" -> dropped(w, pipe)
            "bye" -> parted(w, pipe)
            "ty" -> typed(w, pipe)   // COMPAT-GATED: a member typing, an instant word an older build buries (B.12)
            "rd" -> readWord(w, pipe)   // COMPAT-GATED: a reader's mark, an older build buries it (B.13)
            "ev" -> evented(w, pipe)   // COMPAT-GATED: the owner's event, a row in the feed (iOS stage R)
            "kk" -> kicked(w, pipe)   // COMPAT-GATED: an administrator's «take out», to the owner (iOS stage R.6)
            else -> Verdict.REFUSED.also { Log.i("Montana", "group_refused kind=${t.take(8)}") }   // a newer build's word: its road ends here
        }
    }

    /**
     * AN INVITATION (iOS invited): the group stands on this phone, owned by the pipe the word came by. A second invitation of the
     * same owner renews the title, the words, the count and the face; another pipe naming a group held here is refused.
     */
    private fun invited(w: JSONObject, pipe: String): Verdict {
        val owner = SamePair.root(pipe)
        val kind = w.optString("k")
        val title = clean(w.optString("ti"), TITLE_LIMIT)
        val seat = w.optString("me")
        if ((kind != GROUP && kind != CHANNEL) || title.isEmpty() || !isSeat(seat) || seat == OWNER_SEAT) return Verdict.REFUSED
        val count = w.optInt("c", 2).coerceIn(2, PEOPLE_LIMIT + 1)
        val ownerName = clean(w.optString("n"), NAME_LIMIT)
        val fc = w.optString("fc").ifEmpty { null }
        val tag = fc?.let { it.length.toString() + "-" + it.takeLast(16) }
        val id = w.getString("g")
        val had = groups[id]
        if (had != null) {
            if (had.mine || SamePair.root(had.owner) != owner) {
                Log.i("Montana", "group_refused invitation of a group held by another owner")
                return Verdict.REFUSED
            }
            // out of the group, a word of the old seat is one that was on its way; the owner adding this phone again gives a new seat
            val back = had.left
            if (back) { if (seat == had.me) return Verdict.REFUSED; had.left = false; had.removed = false }
            val renamed = !back && had.title != title
            val hadFace = had.faceTag != null
            had.title = title; had.about = clean(w.optString("ds"), ABOUT_LIMIT); had.count = count; had.me = seat
            if (ownerName.isNotEmpty()) had.names[OWNER_SEAT] = ownerName
            had.admins.clear(); had.admins.addAll(admins(w))   // the owner's word alone names them
            val newFace = tag != null && tag != had.faceTag
            if (newFace) had.faceTag = tag
            save()
            stand(had, if (newFace) face(fc) else null)
            // the reference folder's rows: added again, a new name, a new face -- each the owner's deed
            if (back) lay(had, event("added", OWNER_SEAT, listOf(seat)))
            if (renamed) lay(had, event("named", OWNER_SEAT, t = title))
            if (newFace && hadFace && !back) lay(had, event("face", OWNER_SEAT))
            return Verdict.HELD
        }
        val g = State(id, kind, title, clean(w.optString("ds"), ABOUT_LIMIT), owner, seat, mutableListOf(), count,
            if (ownerName.isEmpty()) mutableMapOf() else mutableMapOf(OWNER_SEAT to ownerName), System.currentTimeMillis(), faceTag = tag,
            admins = admins(w).toMutableList())
        groups[id] = g
        save()
        stand(g, face(fc))
        lay(g, event("added", OWNER_SEAT, listOf(seat)))   // «X added you»
        Log.i("Montana", "group_rx invitation kind=$kind people=$count from=${pipe.take(10)}")
        return Verdict.HELD
    }

    /** The administrators an invitation names (iOS ad): seats alone. */
    private fun admins(w: JSONObject): List<String> =
        w.optJSONArray("ad")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { isSeat(it) }.distinct().take(PEOPLE_LIMIT) } ?: emptyList()

    /** The group's row on the list (iOS stand): the one standing or archived, or a new one; a deleted group's row comes back with its next word. */
    private fun stand(g: State, face: ByteArray?) {
        val key = key(g.id)
        if (face != null) runCatching { Book.face(key).writeBytes(face) }
        Book.open(key, g.title, false)
        Book.edit(key) { it.name = g.title }
    }

    /**
     * A LETTER (iOS heard): it lands in the group's feed under its one name, worn by its speaker's seat. The owner takes a letter
     * only from a member it carries to — in a group, never in a channel — and carries it on to every other member; a member takes
     * letters from the owner's pipe alone.
     */
    private fun said(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED   // out of the group: the word's road ends here
        val id = w.optString("id")
        val words = w.optString("tx")
        if (!isLetterName(id) || !carries(words)) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val seat: String
        if (g.mine) {
            val m = g.members.firstOrNull { SamePair.root(it.pipe) == from } ?: return Verdict.REFUSED
            if (g.kind != GROUP) { Log.i("Montana", "group_refused a member's letter in a channel is not carried"); return Verdict.REFUSED }
            seat = m.seat
        } else {
            val s0 = w.optString("s")
            if (SamePair.root(g.owner) != from || !isSeat(s0) || s0 == g.me) return Verdict.REFUSED
            seat = s0
        }
        val name = clean(w.optString("n"), NAME_LIMIT)
        if (name.isNotEmpty() && g.names[seat] != name) { g.names[seat] = name; save() }
        if (id in gone) return Verdict.HELD
        val key = key(g.id)
        if (Book.chat(key)?.msgs?.any { it.mid == id } == true) return Verdict.HELD   // a repeat: the row stands
        stand(g, null)
        val sp = speaker(g.id, seat)
        val at = Marks.birthMs(id) ?: System.currentTimeMillis()
        val quote = w.optString("qt").ifEmpty { null }
        val quoted = w.optString("qm").removePrefix("mid:").ifEmpty { null }
        var landed = false
        Book.edit(key) { c ->
            if (c.msgs.none { it.mid == id } && !Book.isBuried(key, id)) {   // a deleted letter's copy stays out (Book.bury)
                c.msgs.add(Msg(id, words, false, at, qt = quote, qm = quoted, from = sp)); c.msgs.sortBy { it.at }
                if (Book.openChat != key || !Presence.shown) c.unread++   // a locked phone reads nothing (iOS placeRow)
                landed = true
            }
        }
        Log.i("Montana", "group_rx mid=$id letter landed=${if (landed) 1 else 0} owner=${if (g.mine) 1 else 0}")
        // a media letter's file is gathered from the pieces every member reads; its copy was receipted already (handle)
        if (landed && words.startsWith(Marks.MEDIA)) Media.fetch(Book.ctx, key, id, words)
        // the banner a running app owes its person: the group's title over who wrote and what (a channel speaks as itself)
        if (landed && Book.openChat != key) Notify.letter(key, words, if (g.kind == GROUP) speakerName(Book.ctx, sp) else null,
            mentioned = g.kind == GROUP && mentionsMe(words))   // a mention rings through the mute (iOS presentGroup mentioned)
        if (landed && Book.openChat == key && g.kind == GROUP) readSoon(key)   // read before the eyes: the speaker hears it (B.13)
        // THE OWNER CARRIES IT ON (iOS heard, g.mine): to every other member, under the speaker's seat and the name they gave
        typingStopped(key, seat)   // the letter came: its speaker types no more (the reference's head)
        if (landed && g.mine) {
            val on = JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", id).put("s", seat).put("tx", words)
            if (name.isNotEmpty()) on.put("n", name)
            if (quote != null) on.put("qt", quote)
            w.optString("qm").ifEmpty { null }?.let { on.put("qm", it) }
            spread(on, g, seat, own = false)
        }
        return Verdict.HELD
    }

    /**
     * AN ANSWER (iOS answered): a reaction, an edit of one's own words, a deletion of one's own letter — applied in the group's
     * feed once by its name. Only a letter's own speaker changes it or takes it away; a deletion of a letter not landed yet
     * buries it before it comes.
     */
    private fun answered(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED
        val id = w.optString("id")
        val words = w.optString("tx")
        if (!isLetterName(id) || !answers(words)) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        // the owner answers for every member by the pipe the word came by; a member takes the owner's word alone
        val seat = if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == from }?.seat ?: return Verdict.REFUSED
            else w.optString("s").takeIf { SamePair.root(g.owner) == from && isSeat(it) && it != g.me } ?: return Verdict.REFUSED
        if (id in heard) return Verdict.HELD
        if (words.startsWith(PIN_MARK) && !pins(g, seat)) {
            Log.i("Montana", "group_refused a pin by a seat without the right seat=$seat")
            return Verdict.REFUSED
        }
        val key = key(g.id)
        target(words)?.let { t ->
            val row = Book.chat(key)?.msgs?.firstOrNull { it.mid == t }
            if (row != null) {
                // the owner and the administrators it named take another's letter away (iOS stage R.6); an edit stays the speaker's own
                val boss = words.startsWith(Marks.DELETE) && (seat == OWNER_SEAT || seat in g.admins)
                if (author(row, g) != seat && !boss) {
                    Log.i("Montana", "group_refused an answer to another's letter seat=$seat")
                    return Verdict.REFUSED
                }
            } else if (t in gone) return Verdict.HELD
            else if (g.mine) return Verdict.WAIT   // the letter has not landed at the owner yet: the copy knocks again
            else if (words.startsWith(Marks.DELETE)) { bury(t); remember(id); return Verdict.HELD }
        }
        remember(id)
        apply(key, words)
        // the reference's service row of a pin: «X pinned "..."», laid under the pinned letter's own words (iOS answered)
        if (words.startsWith(PIN_MARK)) runCatching {
            val o = JSONObject(words.removePrefix(PIN_MARK))
            val row = Book.chat(key)?.msgs?.find { it.mid == o.getString("sid").removePrefix("mid:") }
            if (o.optString("op") == "pin" && row != null) lay(g, event("pinned", seat, t = pinWords(row)))
        }
        // the owner carries the answer on to every other member (iOS answered, g.mine)
        if (g.mine) spread(JSONObject().put("t", "sig").put("g", g.id).put("id", id).put("s", seat).put("tx", words)
            .apply { w.optString("n").ifEmpty { null }?.let { put("n", it) } }, g, seat, own = false)
        return Verdict.HELD
    }

    /** A MEMBER LEFT (iOS parted, their «bye»): the owner carries nothing more to them, and everyone left learns the new count. */
    private fun parted(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val m = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == from } else null) ?: return Verdict.REFUSED
        keepName(g, m)
        g.members.removeAll { it.seat == m.seat }
        g.admins.remove(m.seat)
        g.count = g.members.size + 1
        save()
        tellAll(g, null)
        val gone = event("left", m.seat)
        lay(g, gone)
        tellEvent(g, gone)
        Log.i("Montana", "group_rx a member left count=" + g.count)
        return Verdict.HELD
    }

    private fun apply(key: String, words: String) {
        when {
            words.startsWith(Marks.DELETE) -> target(words)?.let { t -> Book.drop(key, listOf(t)) }
            words.startsWith(Marks.EDIT) -> {
                val tx = runCatching { JSONObject(words.removePrefix(Marks.EDIT)).getString("tx") }.getOrNull()
                val t = target(words)
                if (tx != null && t != null) Book.edit(key) { c -> c.msgs.find { it.mid == t && !it.mine }?.let { it.text = tx; it.edited = true } }
            }
            // a pin for everyone lands by the letter's one name; «Only Pin» (n 0) pins without a banner (iOS pin_rx)
            words.startsWith(PIN_MARK) -> runCatching {
                val o = JSONObject(words.removePrefix(PIN_MARK)); val t = o.getString("sid").removePrefix("mid:")
                val m = Book.chat(key)?.msgs?.find { it.mid == t }
                when (o.optString("op")) {
                    "pin" -> if (m != null) { MsgPins.pin(key, t); if (o.optInt("n", 1) != 0) Notify.pinned(key, t, m) }
                    "unpin" -> MsgPins.unpin(key, t)
                }
                Log.d("Montana", "pin_rx group " + o.optString("op") + " found=" + (if (m == null) 0 else 1))
            }
            words.startsWith(Marks.REACTION) -> runCatching {
                val o = JSONObject(words.removePrefix(Marks.REACTION)); val t = o.getString("sid").removePrefix("mid:"); val e = o.getString("e")
                Book.edit(key) { c -> c.msgs.find { it.mid == t }?.let { m -> if (o.optString("op") == "del") m.reactions.remove(e) else m.reactions.add(e) } }
            }
        }
    }

    /** THE OWNER TOOK THIS PHONE OUT (iOS dropped): the rows stay readable, and the chat says so instead of a field. */
    private fun dropped(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.REFUSED
        if (g.mine || SamePair.root(g.owner) != SamePair.root(pipe)) return Verdict.REFUSED
        val ended = w.optInt("dl") == 1   // the owner deleted the group for everyone (iOS dl, «Delete for All»)
        if (!g.left) {
            g.left = true
            g.removed = true
            save()
            if (!ended) lay(g, event("removed", OWNER_SEAT, listOf(g.me)))   // «X removed you»
            Book.edit(key(g.id)) {}   // the open chat trades its field for the way out at once
            Log.i("Montana", "group_rx taken out by the owner")
        }
        if (ended) {
            MainThread.post { erase(key(g.id)) }   // the chat leaves this phone, out of the word's own lock
            Log.i("Montana", "group_rx the owner deleted the group for everyone")
        }
        return Verdict.HELD
    }

    private fun bury(mid: String) {
        gone.add(mid)
        if (gone.size > HEARD_LIMIT) gone.subList(0, gone.size - HEARD_LIMIT).clear()
        save()
    }
    private fun remember(id: String) {
        heard.add(id)
        if (heard.size > HEARD_LIMIT) heard.subList(0, heard.size - HEARD_LIMIT).clear()
    }

    // ── measures ──

    /** The seat whose letter a row is: mine is this phone's seat, another's is named by its speaker's reference (iOS author). */
    private fun author(row: Msg, g: State): String? =
        if (row.mine) g.me else row.from?.takeIf { it.startsWith(speaker(g.id, "")) }?.removePrefix(speaker(g.id, ""))

    /** WHAT AN ANSWER MAY BE IN A GROUP (iOS answers): a pin, an emoji put on or taken off, a letter's new words, a letter taken away. */
    private fun answers(text: String): Boolean = when {
        // A PIN FOR EVERYONE (the checklist's B.11): the letter by its one name, pinned or unpinned; an older build refuses it here
        text.startsWith(PIN_MARK) -> text.toByteArray().size <= 1024 && runCatching {
            val o = JSONObject(text.removePrefix(PIN_MARK)); val sid = o.getString("sid")
            o.getString("op").let { it == "pin" || it == "unpin" } && sid.startsWith("mid:") && isLetterName(sid.removePrefix("mid:"))
        }.getOrDefault(false)
        text.startsWith(Marks.REACTION) -> text.length <= 2048 &&
            runCatching { JSONObject(text.removePrefix(Marks.REACTION)).optString("op") }.getOrNull().let { it == "add" || it == "del" }
        text.startsWith(Marks.EDIT) -> target(text) != null &&
            runCatching { JSONObject(text.removePrefix(Marks.EDIT)).getString("tx") }.getOrNull()?.let { carries(it) } == true
        else -> text.startsWith(Marks.DELETE) && target(text) != null
    }
    /** The bare name of the letter an edit or a deletion speaks of; null for a reaction (iOS target). */
    private fun target(text: String): String? {
        val sid = when {
            text.startsWith(Marks.EDIT) -> runCatching { JSONObject(text.removePrefix(Marks.EDIT)).optString("sid") }.getOrDefault("")
            text.startsWith(Marks.DELETE) -> text.removePrefix(Marks.DELETE)
            else -> ""
        }
        return sid.removePrefix("mid:").takeIf { isLetterName(it) }
    }
    /**
     * WHAT A GROUP CARRIES (iOS carries): a person's words, a sticker, and a media letter — the manifest of a picture, a film, a
     * round note, a voice or a file, whose sealed pieces lie on the nodes for every receiver; never another service word. iOS also
     * refuses a coin's, a game's and a phone's own letter at the sender's door; this build reads none of them.
     */
    private fun carries(text: String): Boolean = text.isNotEmpty() && graphemes(text) <= PIECE_CHARS &&
        (!Marks.isService(text) || text.startsWith(Marks.STICKER) || text.startsWith(Marks.MEDIA))

    private fun isLabel(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }
    private fun isSeat(s: String) = s.isNotEmpty() && s.length <= 16 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    private fun isLetterName(s: String) = s.isNotEmpty() && s.length <= 80 && !s.startsWith("mid:") &&
        s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

    /** A name or a title as the group shows it (iOS clean): trimmed, without a crown, at most `limit` characters as a person counts them. */
    private fun clean(s: String, limit: Int): String {
        val plain = StringBuilder()
        var afterMark = false
        val t = s.trim()
        var i = 0
        while (i < t.length) {
            val point = t.codePointAt(i); i += Character.charCount(point)
            if (point in CROWNS) { afterMark = true; continue }
            if (afterMark && (point == 0xFE0F || point == 0xFE0E)) continue
            afterMark = false
            plain.appendCodePoint(point)
        }
        val p = plain.toString()
        if (p.length <= limit) return p
        val cut = BreakIterator.getCharacterInstance().apply { setText(p) }
        var end = 0
        repeat(limit) { val next = cut.next(); if (next == BreakIterator.DONE) return p.substring(0, end); end = next }
        return p.substring(0, end)
    }
    /** Long words are cut into letters of up to the piece's characters, backing off to the last line break or period (iOS breakOutgoingText). */
    fun pieces(text: String): List<String> {
        if (graphemes(text) <= PIECE_CHARS) return listOf(text)
        val out = mutableListOf<String>()
        val cut = BreakIterator.getCharacterInstance().apply { setText(text) }
        var start = 0
        while (start < text.length) {
            var end = start; var n = 0; var mark = -1
            while (end < text.length && n < PIECE_CHARS) {
                val next = cut.following(end)
                val ch = text.substring(end, next)
                n++; end = next
                if (ch == "\n" || ch == ".") mark = end
            }
            val stop = if (end >= text.length || mark < 0) end else mark
            out.add(text.substring(start, stop).trim())
            start = stop
        }
        return out.filter { it.isNotEmpty() }
    }
    private fun graphemes(s: String): Int {
        if (s.length <= PIECE_CHARS) return s.length
        val cut = BreakIterator.getCharacterInstance().apply { setText(s) }
        var n = 0
        while (cut.next() != BreakIterator.DONE) n++
        return n
    }
    /** The face an invitation carries (iOS face): small, a picture, laid beside the group's row. */
    private fun face(b64: String?): ByteArray? {
        if (b64 == null || b64.length > FACE_LIMIT) return null
        val d = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        return d.takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }
    }
}
