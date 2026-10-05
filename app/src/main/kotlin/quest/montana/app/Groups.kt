package quest.montana.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator

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
    private const val SPEAKER_HEAD = "gm:"
    private const val OWNER_SEAT = "0"
    private const val GROUP = "g"
    private const val CHANNEL = "c"
    private const val STORE = "groups.held"
    private const val TITLE_LIMIT = 64
    private const val ABOUT_LIMIT = 255
    private const val NAME_LIMIT = 64
    private const val FACE_LIMIT = 24_000
    /** [I-14]: a group is bounded by the people its owner carries to (iOS peopleLimit). */
    private const val PEOPLE_LIMIT = 200
    private const val PIECE_CHARS = 4500   // iOS ChatStore.sendPieceChars
    private const val HEARD_LIMIT = 512
    private val CROWNS = setOf(0x1F451, 0x2654, 0x2655, 0x265A, 0x265B)   // iOS MTCrown: only the Montana room wears a crown

    enum class Verdict { HELD, REFUSED, WAIT }

    class Member(val seat: String, val pipe: String)

    /** A group as this phone holds it (iOS MTGroupState): `owner` — the pipe to the owner, empty on the owner's phone; `me` — this phone's seat. */
    class State(
        val id: String, val kind: String, var title: String, var about: String, val owner: String, var me: String,
        val members: MutableList<Member>, var count: Int, val names: MutableMap<String, String>, val at: Long,
        var left: Boolean = false, var faceTag: String? = null,
    ) {
        val mine: Boolean get() = owner.isEmpty()
        fun json(): JSONObject = JSONObject().put("id", id).put("k", kind).put("ti", title).put("ds", about).put("ow", owner).put("me", me)
            .put("mb", JSONArray().apply { members.forEach { put(JSONObject().put("s", it.seat).put("p", it.pipe)) } })
            .put("c", count).put("nm", JSONObject(names.toMap())).put("at", at).put("lf", left).put("ft", faceTag ?: "")
        companion object {
            fun of(o: JSONObject) = State(o.getString("id"), o.getString("k"), o.optString("ti"), o.optString("ds"), o.optString("ow"),
                o.getString("me"),
                o.optJSONArray("mb")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Member(it.getString("s"), it.getString("p")) } } } ?: mutableListOf(),
                o.optInt("c", 2),
                o.optJSONObject("nm")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optLong("at"), o.optBoolean("lf"), o.optString("ft").ifEmpty { null })
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

    private fun ready(): Boolean {
        if (loaded && !unread) return true
        loaded = true
        groups.clear(); gone.clear()
        val raw = DeviceVault.get(STORE)
        unread = raw == null && DeviceVault.has(STORE)
        if (raw != null) runCatching {
            val o = JSONObject(String(raw, Charsets.UTF_8))
            o.optJSONObject("g")?.let { g -> g.keys().forEach { k -> groups[k] = State.of(g.getJSONObject(k)) } }
            o.optJSONArray("gone")?.let { a -> for (i in 0 until a.length()) gone.add(a.getString(i)) }
        }.onFailure { groups.clear(); gone.clear(); unread = true }
        if (unread) Log.i("Montana", "group_unread the groups' store did not open -- read again before use")
        return !unread
    }
    private fun save() {
        if (unread) return
        val g = JSONObject(); groups.forEach { (k, v) -> g.put(k, v.json()) }
        DeviceVault.set(STORE, JSONObject().put("g", g).put("gone", JSONArray(gone)).toString().toByteArray(Charsets.UTF_8))
    }
    /** The person leaves: their groups leave with them (iOS montanaSeedForgotten). */
    fun wipe() = synchronized(lock) { groups.clear(); gone.clear(); heard.clear(); loaded = false; unread = false; DeviceVault.delete(STORE) }

    // ── names ──

    fun isKey(chat: String) = chat.startsWith(KEY_HEAD)
    private fun key(id: String) = KEY_HEAD + id
    private fun state(key: String): State? = synchronized(lock) { if (isKey(key) && ready()) groups[key.removePrefix(KEY_HEAD)] else null }
    /** The people in the group, the owner included — the head of the group's chat says this number (iOS people). */
    fun peopleLine(c: Context, key: String): String? = state(key)?.count?.let { c.resources.getQuantityString(R.plurals.gr_members, it, it) }
    /** Out of the group — it left, or the owner took it out: the chat says so instead of a field (iOS isOut). */
    fun isOut(key: String) = state(key)?.left == true
    /** Everyone writes in a group, the owner alone in a channel (iOS canWrite). */
    fun canWrite(key: String): Boolean { val g = state(key) ?: return false; return !g.left && (g.kind == GROUP || g.mine) }
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

    // ── arrival ──

    /**
     * A GROUP'S WORD ARRIVED ON A PIPE (iOS MTGroup.handle, Post.place before any row): never a row of the pipe. Held or refused,
     * the copy is receipted by its own name on the pipe it came by; a word that must wait is not, and its copy knocks again.
     */
    fun handle(pipe: String, sid: String, text: String) {
        val w = runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull()
        val verdict = if (w != null && isLabel(w.optString("g"))) synchronized(lock) { take(w, pipe) }
            else Verdict.REFUSED.also { Log.i("Montana", "group_refused unreadable from=${pipe.take(10)}") }
        if (verdict != Verdict.WAIT) Post.receiptFor(pipe, sid)
    }

    private fun take(w: JSONObject, pipe: String): Verdict {
        if (!ready()) return Verdict.WAIT   // the copy knocks again once the store opens
        return when (val t = w.optString("t")) {
            "inv" -> invited(w, pipe)
            "say" -> said(w, pipe)
            "sig" -> answered(w, pipe)
            "out" -> dropped(w, pipe)
            "bye" -> Verdict.REFUSED   // a member's leaving is told to its owner's phone, and this phone carries no group of its own yet
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
            if (had.left) { if (seat == had.me) return Verdict.REFUSED; had.left = false }
            had.title = title; had.about = clean(w.optString("ds"), ABOUT_LIMIT); had.count = count; had.me = seat
            if (ownerName.isNotEmpty()) had.names[OWNER_SEAT] = ownerName
            val newFace = tag != null && tag != had.faceTag
            if (newFace) had.faceTag = tag
            save()
            stand(had, if (newFace) face(fc) else null)
            return Verdict.HELD
        }
        val g = State(id, kind, title, clean(w.optString("ds"), ABOUT_LIMIT), owner, seat, mutableListOf(), count,
            if (ownerName.isEmpty()) mutableMapOf() else mutableMapOf(OWNER_SEAT to ownerName), System.currentTimeMillis(), faceTag = tag)
        groups[id] = g
        save()
        stand(g, face(fc))
        Log.i("Montana", "group_rx invitation kind=$kind people=$count from=${pipe.take(10)}")
        return Verdict.HELD
    }

    /** The group's row on the list (iOS stand): the one standing or archived, or a new one; a deleted group's row comes back with its next word. */
    private fun stand(g: State, face: ByteArray?) {
        val key = key(g.id)
        if (face != null) runCatching { Book.face(key).writeBytes(face) }
        Book.open(key, g.title, false)
        Book.edit(key) { it.name = g.title }
    }

    /**
     * A LETTER (iOS heard): it lands in the group's feed under its one name, worn by its speaker's seat. A member takes letters
     * from the owner's pipe alone; carrying a member's letter on to the others is the owner's phone's work.
     */
    private fun said(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED   // out of the group: the word's road ends here
        val id = w.optString("id")
        val words = w.optString("tx")
        if (!isLetterName(id) || !carries(words) || g.mine) return Verdict.REFUSED
        val seat = w.optString("s")
        if (SamePair.root(g.owner) != SamePair.root(pipe) || !isSeat(seat) || seat == g.me) return Verdict.REFUSED
        val name = clean(w.optString("n"), NAME_LIMIT)
        if (name.isNotEmpty() && g.names[seat] != name) { g.names[seat] = name; save() }
        if (id in gone) return Verdict.HELD
        val key = key(g.id)
        if (Book.chat(key)?.msgs?.any { it.mid == id } == true) return Verdict.HELD   // a repeat: the row stands
        stand(g, null)
        val from = speaker(g.id, seat)
        val at = Marks.birthMs(id) ?: System.currentTimeMillis()
        val quote = w.optString("qt").ifEmpty { null }
        val quoted = w.optString("qm").removePrefix("mid:").ifEmpty { null }
        var landed = false
        Book.edit(key) { c ->
            if (c.msgs.none { it.mid == id }) {
                c.msgs.add(Msg(id, words, false, at, qt = quote, qm = quoted, from = from)); c.msgs.sortBy { it.at }
                if (Book.openChat != key) c.unread++
                landed = true
            }
        }
        Log.i("Montana", "group_rx mid=$id letter landed=${if (landed) 1 else 0}")
        // the banner a running app owes its person: the group's title over who wrote and what (a channel speaks as itself)
        if (landed && Book.openChat != key) Notify.letter(key, words, if (g.kind == GROUP) speakerName(Book.ctx, from) else null)
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
        if (!isLetterName(id) || !answers(words) || g.mine) return Verdict.REFUSED
        val seat = w.optString("s")
        if (SamePair.root(g.owner) != SamePair.root(pipe) || !isSeat(seat) || seat == g.me) return Verdict.REFUSED
        if (id in heard) return Verdict.HELD
        val key = key(g.id)
        target(words)?.let { t ->
            val row = Book.chat(key)?.msgs?.firstOrNull { it.mid == t }
            if (row != null) {
                if (author(row, g) != seat) {
                    Log.i("Montana", "group_refused an answer to another's letter seat=$seat")
                    return Verdict.REFUSED
                }
            } else if (t in gone) return Verdict.HELD
            else if (words.startsWith(Marks.DELETE)) { bury(t); remember(id); return Verdict.HELD }
        }
        remember(id)
        apply(key, words)
        return Verdict.HELD
    }

    private fun apply(key: String, words: String) {
        when {
            words.startsWith(Marks.DELETE) -> target(words)?.let { t -> Book.edit(key) { c -> c.msgs.removeAll { it.mid == t } } }
            words.startsWith(Marks.EDIT) -> {
                val tx = runCatching { JSONObject(words.removePrefix(Marks.EDIT)).getString("tx") }.getOrNull()
                val t = target(words)
                if (tx != null && t != null) Book.edit(key) { c -> c.msgs.find { it.mid == t && !it.mine }?.let { it.text = tx; it.edited = true } }
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
        if (!g.left) {
            g.left = true
            save()
            Book.edit(key(g.id)) {}   // the open chat trades its field for the note at once
            Log.i("Montana", "group_rx taken out by the owner")
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

    /** WHAT AN ANSWER MAY BE IN A GROUP (iOS answers): an emoji put on or taken off, a letter's new words, a letter taken away. */
    private fun answers(text: String): Boolean = when {
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
     * WHAT A GROUP CARRIES (iOS carries): a person's words and a sticker, never a service word. iOS also refuses a coin's, a game's
     * and a phone's own letter at the sender's door; this build reads none of them.
     */
    private fun carries(text: String): Boolean =
        text.isNotEmpty() && graphemes(text) <= PIECE_CHARS && (!Marks.isService(text) || text.startsWith(Marks.STICKER))

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
