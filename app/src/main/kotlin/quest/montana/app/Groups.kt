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
    /** [P2P-COMPAT] iOS MTGroup.stateKey: the groups held, under this name on both phones and on the card of a copy. */
    const val STORE = "groups.held"
    private const val COPIES = "groups.copies"
    /** A copy outlives the queue's own term by a day and is let go with it (iOS copyLife). */
    private const val COPY_LIFE_MS = 8L * 24 * 3600 * 1000
    private const val TITLE_LIMIT = 64
    private const val ABOUT_LIMIT = 255
    private const val NAME_LIMIT = 64
    private const val FACE_LIMIT = 24_000
    private const val FACE_SIDE = 160   // iOS faceSide
    private const val FULL_FACE_SIDE = 1024   // iOS fullFaceSide (06c8eaf1)
    private const val FULL_FACE_LIMIT = 1_048_576   // iOS fullFaceLimit
    /** A speaker's face on its letter (iOS speakerFaceSide, MTGroupWord.sf): the feed's face at three pixels a point. */
    private const val SPEAKER_FACE_SIDE = 96
    /** A speaker gives its face again a day after it last did (iOS faceAgain): one who came in since is not left with an initial. */
    private const val FACE_AGAIN = 24 * 3600 * 1000L
    /** [I-14]: a group is bounded by the people its owner carries to (iOS peopleLimit). */
    private const val PEOPLE_LIMIT = 200
    /** [I-14]: the posts one word of a channel's views names (iOS viewsBatch). */
    const val VIEWS_BATCH = 200
    private const val PIECE_CHARS = 4500   // iOS ChatStore.sendPieceChars
    private const val HEARD_LIMIT = 512
    private val CROWNS = setOf(0x1F451, 0x2654, 0x2655, 0x265A, 0x265B)   // iOS MTCrown: only the Montana room wears a crown

    /** `INSTANT` -- a word that lived an instant (a typing word): it rode no queue, so nothing is receipted and nothing knocks again. */
    enum class Verdict { HELD, REFUSED, WAIT, INSTANT }

    class Member(val seat: String, val pipe: String)
    /** ONE WHO ASKED TO JOIN BY THE LINK (iOS MTGroupRequest, the reference's «Member Requests»): the pipe the owner met them by, the name they gave and when (ms). */
    class Request(val pipe: String, val name: String, val at: Long)
    /** One deed of a channel, as its owner's phone wrote it (iOS MTChannelAct): what, about whom by the names known then, when (ms). */
    class Act(val e: String, val p: List<String>?, val t: String?, val at: Long)
    /** The face this phone last gave a group on a letter of its own (iOS MTFaceGiven): its tag, the people counted then, when (ms). */
    class FaceGiven(val tag: String, val count: Int, val at: Long)

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
        /** WHAT EACH ADMINISTRATOR MAY NOT DO (iOS adminTaken, G.6): by seat, the rights the owner left out; absent -- all of them. */
        val taken: MutableMap<String, List<String>> = mutableMapOf(),
        /** The custom titles the owner gave (iOS ranks, the reference's «Custom Title»), by seat: shown instead of «admin». */
        val ranks: MutableMap<String, String> = mutableMapOf(),
        /** WHAT THE MEMBERS MAY NOT DO (iOS barred, G.5, the reference's «Permissions»): the rights the owner took, by their short words; empty -- everything. */
        val barred: MutableList<String> = mutableListOf(),
        /** SLOW MODE (iOS slow, the reference's «Slowmode»): the seconds a member waits between letters; 0 -- off. */
        var slow: Int = 0,
        /** CHAT HISTORY FOR NEW MEMBERS (iOS history, G.4, the reference's «Visible» / «Hidden»): true -- one added reads the group's newest words; false -- hidden. A group is born with it visible (iOS 99825581, create); the owner may hide it. The owner's choice, never on the wire. */
        var history: Boolean = false,
        /** THE PEOPLE KEPT OUT (iOS banned, G.7, the reference's «Removed Users»): everyone the owner or an administrator took out, by seat and pipe; the owner's alone, never on the wire. */
        val banned: MutableList<Member> = mutableListOf(),
        /** THE STANDING MARK OF THE GROUP'S INVITE LINK (iOS invite, the reference's «Invite Link»): whoever opens the link meets the owner by its card and asks with it; a new mark ends every link given before. The owner's alone. */
        var invite: String? = null,
        /** THE LINK ASKS THE OWNER FIRST (iOS approval, G.8, the reference's «Request Admin Approval»): who opens it waits in `requests` until the owner adds or dismisses them. The owner's alone, never on the wire. */
        var approval: Boolean = false,
        val requests: MutableList<Request> = mutableListOf(),
        /** THE GROUP'S INVITE LINK AS THE OWNER HANDS IT TO AN ADMINISTRATOR WHO MAY INVITE BY IT (iOS link, G.6): kept only while this phone is one. */
        var link: String? = null,
        /** THE CHANNEL SIGNS ITS POSTS (iOS signs, the reference's «Sign Messages»): its writer's name in every post's stamp. Carried in its invitation (sg). */
        var signs: Boolean = false,
        /** THE CHANNEL TAKES REACTIONS (iOS reacts, the reference's «Allow Reactions»): false -- its owner turned them off. Carried in its invitation (rx). */
        var reacts: Boolean = true,
        /** THE REACTIONS A CHANNEL TAKES (iOS reactList, the reference's «Some Reactions»): the owner's list; null -- every one. Carried in its invitation (rl). */
        var reactList: List<String>? = null,
        /** WHAT WAS DONE IN A CHANNEL (iOS acts, the reference's «Recent Actions»): the owner's phone writes each deed as it does it, by the names it knew then. Never on the wire. */
        val acts: MutableList<Act> = mutableListOf(),
        /** A CHANNEL'S SUBSCRIBERS COMMENT UNDER ITS POSTS (iOS talks, the reference's «Allow Comments»). Carried in its invitation (dc). */
        var talks: Boolean = false,
        /** THE OWNER'S NEXT POSTS GO WITHOUT SOUND (iOS silent, the bell in a channel's field, the reference's «Silent Broadcast»). The owner's alone, never in an invitation. */
        var silent: Boolean = false,
        /** THE FACES OF THE GROUP'S PEOPLE, EACH AS THE PERSON GAVE IT ON THEIR OWN LETTER (iOS faces/faceTags, MTGroupWord.sf, 4c20068e):
         *  by seat, a name of the faces folder (Book.face), and the tag it came under. Never on the wire. */
        val faces: MutableMap<String, String> = mutableMapOf(),
        val faceTags: MutableMap<String, String> = mutableMapOf(),
        /** The face this phone last gave the group on a letter of its own (iOS faceGiven, MTGroup.dress). */
        var faceGiven: FaceGiven? = null,
        /** THE GROUP'S SEATS AS ITS OWNER NAMES THEM, ON A MEMBER'S PHONE (iOS seats, 07d5fc70, OZ 1 p.1, decision 877 a): every member's
         *  seat, carried on each «inv» (ps); the page's people and the head's number both read it (pagePeople). Null -- an owner whose
         *  build names no seats: the page lists who spoke, and the head counts that list. */
        var seats: List<String>? = null,
        /** THE OWNER'S ROUND (iOS round, d28805b6, OZ 1 p.8, the author's word 21075): the owner's build at which it last told the group
         *  its seats -- on its own phone (keepRoster tells them again at a new build), on a member's phone as the owner's «inv» named it
         *  (rr). A new round, or a new build of the member, lays the member's card again (cardMark), and the owner answers it with every
         *  card it holds: a card an older build refused or the road lost comes back by itself. */
        var round: Int? = null,
        /** THE CARDS THE GROUP'S PEOPLE LAID (iOS cards, 07d5fc70, OZ 1 p.8): by seat, each member's permanent link, laid with their
         *  name («pc»); one met by it is written to without being a contact. The owner keeps them to hand on. */
        val cards: MutableMap<String, String> = mutableMapOf(),
        /** The seat and the name this phone last laid its own card under (iOS cardGiven, giveCards): a new seat or a new name lays it again. */
        var cardGiven: String? = null,
        /** THE FULL FACE THIS PHONE WEARS (iOS faceFull, 06c8eaf1, OZ 1 p.1): the invitation's «blob.key» it came by -- the same word is
         *  not fetched again. */
        var faceFull: String? = null,
        /** The owner told every member its full face once (iOS fullFaced, 06c8eaf1): a face given before rode the small one alone. */
        var fullFaced: Boolean = false,
    ) {
        val mine: Boolean get() = owner.isEmpty()
        fun json(): JSONObject = JSONObject().put("id", id).put("k", kind).put("ti", title).put("ds", about).put("ow", owner).put("me", me)
            .put("mb", JSONArray().apply { members.forEach { put(JSONObject().put("s", it.seat).put("p", it.pipe)) } })
            .put("c", count).put("nm", JSONObject(names.toMap())).put("at", at).put("lf", left).put("ft", faceTag ?: "")
            .put("rb", JSONObject(readBy.toMap())).put("rm", removed).put("ad", JSONArray(admins))
            .put("ax", JSONObject().apply { taken.forEach { (k, v) -> put(k, JSONArray(v)) } }).put("rk", JSONObject(ranks.toMap()))
            .put("bn", JSONArray(barred)).put("sw", slow).put("hy", history)
            .put("bd", JSONArray().apply { banned.forEach { put(JSONObject().put("s", it.seat).put("p", it.pipe)) } })
            .put("iv", invite ?: "").put("ap", approval)
            .put("rq", JSONArray().apply { requests.forEach { put(JSONObject().put("p", it.pipe).put("n", it.name).put("at", it.at)) } })
            .put("lk", link ?: "").put("sg", signs).put("rx", reacts).put("dc", talks).apply { reactList?.let { put("rl", JSONArray(it)) } }
            .put("as", actsJson(acts, 1L)).put("sl", silent)
            .put("fs", JSONObject(faces.toMap())).put("fq", JSONObject(faceTags.toMap()))
            .apply { faceGiven?.let { put("fg", JSONObject().put("t", it.tag).put("c", it.count).put("at", it.at)) } }
            .apply { seats?.let { put("ps", JSONArray(it)) } }.apply { round?.let { put("rr", it) } }
            .put("pc", JSONObject(cards.toMap())).put("cg", cardGiven ?: "")
            .put("fu", faceFull ?: "").put("fd", fullFaced)
        companion object {
            fun of(o: JSONObject) = State(o.getString("id"), o.getString("k"), o.optString("ti"), o.optString("ds"), o.optString("ow"),
                o.getString("me"),
                o.optJSONArray("mb")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Member(it.getString("s"), it.getString("p")) } } } ?: mutableListOf(),
                o.optInt("c", 2),
                o.optJSONObject("nm")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optLong("at"), o.optBoolean("lf"), o.optString("ft").ifEmpty { null },
                o.optJSONObject("rb")?.let { r -> r.keys().asSequence().associateWith { r.getLong(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optBoolean("rm"), o.optJSONArray("ad")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(),
                o.optJSONObject("ax")?.let { x -> x.keys().asSequence().associateWith { k -> x.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } }.toMutableMap() } ?: mutableMapOf(),
                o.optJSONObject("rk")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optJSONArray("bn")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(), o.optInt("sw"),
                o.optBoolean("hy"),
                o.optJSONArray("bd")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Member(it.getString("s"), it.getString("p")) } } } ?: mutableListOf(),
                o.optString("iv").ifEmpty { null }, o.optBoolean("ap"),
                o.optJSONArray("rq")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Request(it.getString("p"), it.optString("n"), it.optLong("at")) } } } ?: mutableListOf(),
                o.optString("lk").ifEmpty { null }, o.optBoolean("sg"), o.optBoolean("rx", true),
                reactionList(o.optJSONArray("rl")), actsOf(o.optJSONArray("as"), 1L), o.optBoolean("dc"), o.optBoolean("sl"),
                o.optJSONObject("fs")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optJSONObject("fq")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optJSONObject("fg")?.let { FaceGiven(it.optString("t"), it.optInt("c"), it.optLong("at")) },
                o.optJSONArray("ps")?.let { a -> List(a.length()) { a.getString(it) } }, if (o.has("rr")) o.optInt("rr") else null,
                o.optJSONObject("pc")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) }.toMutableMap() } ?: mutableMapOf(),
                o.optString("cg").ifEmpty { null }, o.optString("fu").ifEmpty { null }, o.optBoolean("fd"))
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
        ChannelComments.wipe()
        ChannelViews.wipe()
    }

    // ── the copy: the groups ride the card in the iPhone's form, and every group held stands a row ──

    /** The name a group's face is laid under in the copy's faces (iOS MTGroupState.face: a file of the avatars folder). */
    fun faceFile(id: String) = "grp_$id.jpg"

    /**
     * THE GROUPS A COPY CARRIES (iOS SeedScope.dataKeys → MTGroup.stateKey; 8cd39e19 «groups and channels did not come back»): every
     * group held here as the iPhone keeps it -- one map of id to MTGroupState, moments in seconds -- laid over the card's own record of
     * it, so what only an iPhone knows of a group rides on; its face by the file `face` laid it under in the copy, none when not laid.
     * Without it a copy brought the group's letters back to a phone that held no group to open them by.
     */
    fun carried(base: JSONObject?, face: (String, java.io.File) -> String?): ByteArray? = synchronized(lock) {
        if (!ready()) return null
        fun seats(l: List<Member>) = JSONArray().apply { l.forEach { put(JSONObject().put("seat", it.seat).put("pipe", it.pipe)) } }
        val out = JSONObject()
        for ((id, g) in groups) {
            val o = base?.optJSONObject(id) ?: JSONObject()
            o.put("id", id).put("kind", g.kind).put("title", g.title).put("about", g.about).put("owner", g.owner).put("me", g.me)
                .put("members", seats(g.members)).put("count", g.count).put("names", JSONObject(g.names.toMap())).put("at", g.at / 1000.0)
                .put("left", g.left).put("removed", g.removed).put("admins", JSONArray(g.admins))
                .put("readBy", JSONObject(g.readBy.toMap())).put("barred", JSONArray(g.barred)).put("slow", g.slow)
                .put("adminTaken", JSONObject().apply { g.taken.forEach { (k, v) -> put(k, JSONArray(v)) } }).put("ranks", JSONObject(g.ranks.toMap()))
                .put("approval", g.approval).put("history", g.history).put("banned", seats(g.banned))
                .put("requests", JSONArray().apply { g.requests.forEach { put(JSONObject().put("pipe", it.pipe).put("name", it.name).put("at", it.at / 1000.0)) } })
            for ((k, v) in listOf("faceTag" to g.faceTag, "invite" to g.invite, "cardGiven" to g.cardGiven, "faceFull" to g.faceFull)) if (v == null) o.remove(k) else o.put(k, v)
            if (g.fullFaced) o.put("fullFaced", true) else o.remove("fullFaced")
            // the seats, the roster told and the cards ride as iOS MTGroupState keeps them (07d5fc70, OZ 1 p.1, p.8)
            g.seats?.let { o.put("seats", JSONArray(it)) } ?: o.remove("seats")
            o.remove("rostered")   // iOS d28805b6 keeps the owner's round instead
            g.round?.let { o.put("round", it) } ?: o.remove("round")
            if (g.cards.isNotEmpty()) o.put("cards", JSONObject(g.cards.toMap())) else o.remove("cards")
            if (g.kind == CHANNEL && g.signs) o.put("signs", true) else o.remove("signs")
            if (g.kind == CHANNEL && !g.reacts) o.put("reacts", false) else o.remove("reacts")
            if (g.kind == CHANNEL && g.talks) o.put("talks", true) else o.remove("talks")
            g.reactList?.takeIf { g.kind == CHANNEL }?.let { o.put("reactList", JSONArray(it)) } ?: o.remove("reactList")
            if (g.kind == CHANNEL && g.acts.isNotEmpty()) o.put("acts", actsJson(g.acts, 1000L)) else o.remove("acts")
            val file = Book.face(key(id)).takeIf { it.isFile }?.let { face(faceFile(id), it) }
            if (file == null) o.remove("face") else o.put("face", file)
            out.put(id, o)
        }
        out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * THE GROUPS A COPY BROUGHT (iOS layCard → MTGroup.reread, then standAll): a group this phone does not hold is taken as the copy
     * holds it, its face out of the copy's faces when this phone has none; a group held here keeps this phone's word. Then every
     * group held stands its row, before the archive reads the letters back into it.
     */
    fun lay(raw: ByteArray, faces: java.io.File?): Int {
        val o = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return 0
        var took = 0
        synchronized(lock) {
            if (!ready()) return 0
            for (id in o.keys()) {
                if (groups.containsKey(id) || !isLabel(id)) continue
                val s = o.optJSONObject(id) ?: continue
                val g = runCatching { fromCard(id, s) }.getOrNull() ?: continue
                groups[id] = g
                took++
                val src = s.optString("face").takeIf { MontanaBackup.plainName(it) }?.let { f -> faces?.let { java.io.File(it, f) } }
                val dst = Book.face(key(id))
                if (src != null && src.isFile && !dst.exists()) runCatching { src.copyTo(dst) }
            }
            if (took > 0) save()
        }
        Log.i("Montana", "group_laid took=$took of ${o.length()}")
        standAll()
        return took
    }
    /** One group of the card, in this phone's form (iOS MTGroupState: moments in seconds, the people by seat and pipe). */
    private fun fromCard(id: String, s: JSONObject): State? {
        val kind = s.optString("kind")
        if (kind != GROUP && kind != CHANNEL) return null
        fun seats(a: JSONArray?) = a?.let { MutableList(it.length()) { i -> it.getJSONObject(i).let { m -> Member(m.getString("seat"), m.getString("pipe")) } } } ?: mutableListOf()
        fun words(a: JSONArray?) = a?.let { MutableList(it.length()) { i -> it.getString(i) } } ?: mutableListOf()
        fun map(o: JSONObject?) = o?.let { m -> m.keys().asSequence().associateWith { m.getString(it) }.toMutableMap() } ?: mutableMapOf()
        return State(id, kind, clean(s.optString("title"), TITLE_LIMIT), s.optString("about"), s.optString("owner"), s.getString("me"),
            seats(s.optJSONArray("members")), s.optInt("count", 2), map(s.optJSONObject("names")), (s.optDouble("at", 0.0) * 1000).toLong(),
            s.optBoolean("left"), s.optString("faceTag").ifEmpty { null },
            s.optJSONObject("readBy")?.let { r -> r.keys().asSequence().associateWith { r.getLong(it) }.toMutableMap() } ?: mutableMapOf(),
            s.optBoolean("removed"), words(s.optJSONArray("admins")),
            s.optJSONObject("adminTaken")?.let { x -> x.keys().asSequence().associateWith { k -> words(x.getJSONArray(k)).toList() }.toMutableMap() } ?: mutableMapOf(),
            map(s.optJSONObject("ranks")), words(s.optJSONArray("barred")), s.optInt("slow"), s.optBoolean("history"),
            seats(s.optJSONArray("banned")), s.optString("invite").ifEmpty { null }, s.optBoolean("approval"),
            s.optJSONArray("requests")?.let { a -> MutableList(a.length()) { i -> a.getJSONObject(i).let { Request(it.getString("pipe"), it.optString("name"), (it.optDouble("at", 0.0) * 1000).toLong()) } } } ?: mutableListOf(),
            signs = kind == CHANNEL && s.optBoolean("signs"), reacts = kind != CHANNEL || s.optBoolean("reacts", true),
            reactList = if (kind == CHANNEL) reactionList(s.optJSONArray("reactList")) else null,
            acts = if (kind == CHANNEL) actsOf(s.optJSONArray("acts"), 1000L) else mutableListOf(),
            talks = kind == CHANNEL && s.optBoolean("talks"),
            seats = if (kind == GROUP) s.optJSONArray("seats")?.let { a -> words(a).filter(::isSeat) } else null,
            round = if (s.has("round")) s.optInt("round") else null,
            cards = if (kind == GROUP) map(s.optJSONObject("cards")).filterValues(::isCard).toMutableMap() else mutableMapOf(),
            cardGiven = s.optString("cardGiven").ifEmpty { null },
            faceFull = s.optString("faceFull").ifEmpty { null }, fullFaced = s.optBoolean("fullFaced"))
    }

    /**
     * EVERY GROUP THIS PHONE HOLDS STANDS ON THE LIST (iOS MTGroup.standAll, 8cd39e19): a group has no pipe, so nothing derives its row
     * from a feed -- a group a copy brought, or one whose row was lost, stands one under its title, at every launch and after every
     * copy laid. A group the person deleted stays deleted: its row was erased here, or -- erased on the phone the copy came from,
     * whose deletions this phone's copy does not carry -- it was left, and a left group with no row is not stood back.
     */
    fun standAll() {
        var stood = 0
        synchronized(lock) {
            if (!ready() || groups.isEmpty()) return
            for (id in groups.keys.sorted()) {
                val g = groups[id] ?: continue
                val key = key(id)
                if (g.left || Book.chat(key) != null || Book.erased(key)) continue
                Book.open(key, g.title, false)
                stood++
            }
        }
        if (stood > 0) Log.i("Montana", "group_stood rows=$stood held=${groups.size}")
    }

    // ── names ──

    fun isKey(chat: String) = chat.startsWith(KEY_HEAD)
    fun key(id: String) = KEY_HEAD + id
    /** Every group and channel this phone holds, by its key (iOS MTGroup.shared.groups.keys.map(key(of:))): the archive names their folders. */
    fun keys(): List<String> = synchronized(lock) { if (ready()) groups.keys.map { key(it) } else emptyList() }
    /** [I-14]: a letter's link card on the wire, at most -- the pair's leg (iOS cardLimit). [I-15]: how long a card that outran its letter waits for it (iOS cardWait). */
    private const val CARD_LIMIT = 16_384
    private const val PASSPORT_LIMIT = 4_096          // iOS passportLimit: a sticker's passport, never a carrier of anything else
    private const val CARD_WAIT_MS = 600_000L
    private fun state(key: String): State? = synchronized(lock) { if (isKey(key) && ready()) groups[key.removePrefix(KEY_HEAD)] else null }
    /** The people in the group, the owner included — the head of the group's chat says this number (iOS people). */
    fun count(key: String): Int? = state(key)?.let(::people)
    fun peopleLine(c: Context, key: String): String? = count(key)?.let { c.resources.getQuantityString(R.plurals.gr_members, it, it) }
    /** A GROUP'S NUMBER IS ITS PAGE'S LIST AND THIS PHONE, ONE OWNER (iOS people, 07d5fc70, OZ 1 p.1: the head said 11 and the list 6);
     *  a channel's is its owner's count. */
    private fun people(g: State): Int = if (g.kind != GROUP) g.count else synchronized(lock) {
        if (g.mine) g.members.size + 1 else 2 + pageRest(g).size + unnamedOf(g)
    }
    /**
     * THE PEOPLE A MEMBER'S PHONE CANNOT LIST (iOS unnamed, 5ed85a6b, OZ 1 p.4, the author's word 21000): an owner that names no seats
     * -- an older build -- counts its people (count) and names only whoever spoke; the rest are counted and never invented. None on the
     * owner's phone, none where the owner named every seat.
     */
    fun unnamed(key: String): Int = state(key)?.let { g -> synchronized(lock) { unnamedOf(g) } } ?: 0
    private fun unnamedOf(g: State): Int =
        if (g.kind != GROUP || g.mine || g.seats != null) 0 else maxOf(0, g.count - 2 - pageRest(g).size)
    /** The seats of a member's page after the owner (iOS pagePeople): every seat the owner named, or everyone who spoke where it names none. */
    private fun pageRest(g: State): List<String> =
        (g.seats ?: g.names.keys.toList()).filter { it != OWNER_SEAT && it != g.me }.distinct()
    /**
     * A GROUP'S HEAD AS THE REFERENCE SAYS IT (iOS groupHead, 9bff8550): «N members, M online» once someone besides this phone is
     * online, «N members» otherwise. M is this phone and whoever of the group it sees live by the one ladder (Presence.word): the
     * owner's phone everyone it carries to, a member's phone the owner -- it holds no pipe to the others. A channel says its count alone.
     */
    fun headLine(c: Context, key: String): String? {
        val g = state(key) ?: return null
        // A CHANNEL'S HEAD IS «N SUBSCRIBERS», THE OWNER COUNTED AMONG THEM (iOS MTChannelPage.head, MontanaChannel.swift 494)
        if (g.kind == CHANNEL) return c.resources.getQuantityString(R.plurals.chn_subscribers, g.count, g.count)
        val n = people(g)
        val members = c.resources.getQuantityString(R.plurals.gr_members, n, n)
        val lit = 1 + heldConversations(g).count { Presence.word(c, it)?.second == true }
        return if (lit == 1) members else members + ", " + c.resources.getQuantityString(R.plurals.gr_online, lit, lit)
    }
    /** The conversations this phone holds with people of the group, by their roots (iOS heldConversations): whom it may see online at all. */
    private fun heldConversations(g: State): List<String> =
        if (g.mine) g.members.map { it.pipe }.filter { it.isNotEmpty() }.map { SamePair.root(it) }.distinct() else listOf(SamePair.root(g.owner))
    /** Out of the group — it left, or the owner took it out: the chat says so instead of a field (iOS isOut). */
    fun isOut(key: String) = state(key)?.left == true
    /** Taken out by the owner (iOS state.removed): a channel's foot then says «Join» in grey. */
    fun removed(key: String) = state(key)?.removed == true
    /** A channel, not a group: its page is the channels' (iOS kind == .channel). */
    fun isChannel(key: String) = state(key)?.kind == CHANNEL
    fun canWrite(key: String): Boolean { val g = state(key) ?: return false; return !g.left && posts(g, g.me) }
    /** WHO WRITES HERE (iOS canWrite): a group's every person; a channel's owner and the administrators it let post (the reference's «Post Messages»). */
    private fun posts(g: State, seat: String) = g.kind == GROUP || seat == OWNER_SEAT || (seat in g.admins && POST !in (g.taken[seat] ?: emptyList()))
    /**
     * WHETHER THE FIELD OF THIS GROUP HOLDS THE COIN (iOS takesCoins; the author's word 17.10.2026 21:14 UTC): a group's every person
     * who writes; never a channel -- its owner alone posts and its subscribers have no field.
     */
    fun takesCoins(key: String): Boolean = state(key)?.kind == GROUP && canWrite(key)
    /** Whether this phone pins a letter of the group for everyone (iOS mayPin). */
    fun mayPin(key: String): Boolean { val g = state(key) ?: return false; return !g.left && pins(g, g.me) }

    /** The moment the group stood here: its row stands by it until its first letter (iOS stand puts the row at the top). */
    fun bornAt(key: String): Long = state(key)?.at ?: 0L

    private fun speaker(id: String, seat: String) = SPEAKER_HEAD + id + "/" + seat
    /** The speaker of a seat in a group by its key: the colour and the name every phone gives them (a channel's commenter). */
    fun speakerOf(key: String, seat: String) = state(key)?.let { speaker(it.id, seat) } ?: ""
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
    /**
     * THE PIPE THIS PHONE HOLDS TO A SPEAKER (iOS speakerPipe): the owner's phone to every member it carries to, a member's phone to
     * the owner -- the others are known by their own words alone.
     */
    /** HOW A PERSON OF A GROUP IS REACHED FROM THIS PHONE (iOS MTGroupReach, d28805b6): by the conversation it holds, by the card they
     *  laid, or not yet. */
    sealed class Reach {
        class Held(val conv: String) : Reach()
        class Card(val link: String) : Reach()
        object Away : Reach()
        /** Whether a letter can go to them from here: held or met by the card; one waiting for the owner cannot yet. */
        val writes: Boolean get() = this !is Away
    }
    /**
     * HOW THIS PHONE REACHES ONE PERSON OF A GROUP, ONE ANSWER FOR EVERY PLACE THAT SHOWS THEM (iOS MTGroupPage.way, d28805b6, OZ 1 p.8, the
     * author's word 21091: «the avatar does not press at all»): the page's row read the pipe and the card, the feed's face read the pipe
     * alone and fell silent where a member's phone holds none -- every speaker but the owner. Held by the pipe whose conversation stands,
     * met by the card the person laid, or waiting for the owner to hand that card on; null for this phone itself and a seat of no group.
     */
    fun way(key: String, seat: String): Reach? {
        val g = state(key) ?: return null
        if (seat == g.me) return null
        speakerPipe(speaker(g.id, seat))?.let { SamePair.root(it) }?.takeIf { Book.chat(it) != null }?.let { return Reach.Held(it) }
        synchronized(lock) { g.cards[seat] }?.let { return Reach.Card(it) }
        return Reach.Away
    }
    /** The seat a speaker's mark names in this group (iOS MTGroupPage.seat), or null for a mark of another group. */
    fun seatOf(ref: String, key: String): String? {
        val g = state(key) ?: return null
        val head = speaker(g.id, "")
        return if (ref.startsWith(head) && head.length < ref.length) ref.removePrefix(head) else null
    }
    /** What the diary says of a group's people where a hand found nobody (iOS group_tap way=away). */
    fun awayTrace(key: String): String = state(key)?.let { g -> "seats=" + (g.seats?.size ?: -1) + " cards=" + g.cards.size + " round=" + (g.round ?: 0) } ?: "seats=-1"
    /** A seat's speaker reference in a group (iOS MTGroup.speaker): the key its colour and its pipe are read by. */
    fun seatRef(key: String, seat: String): String? = state(key)?.let { speaker(it.id, seat) }
    fun speakerPipe(ref: String?): String? {
        val parts = ref?.takeIf { it.startsWith(SPEAKER_HEAD) }?.removePrefix(SPEAKER_HEAD)?.split("/", limit = 2) ?: return null
        if (parts.size != 2) return null
        val g = state(key(parts[0])) ?: return null
        val pipe = if (!g.mine && parts[1] == OWNER_SEAT) g.owner else g.members.firstOrNull { it.seat == parts[1] }?.pipe
        return pipe?.ifEmpty { null }
    }
    /**
     * THE FACE A SPEAKER GAVE THE GROUP ON THEIR OWN LETTER (iOS MTGroup.speakerFace, 4c20068e; the author's words 19316 p.1, 19387): the
     * feed reads it first and the pipe's face only without it -- a member's phone holds a pipe to the owner alone, so every other
     * speaker wore an initial there.
     */
    fun speakerFace(ref: String?): java.io.File? {
        val parts = ref?.takeIf { it.startsWith(SPEAKER_HEAD) }?.removePrefix(SPEAKER_HEAD)?.split("/", limit = 2) ?: return null
        if (parts.size != 2) return null
        val name = state(key(parts[0]))?.faces?.get(parts[1]) ?: return null
        return Book.face(name).takeIf { it.exists() }
    }
    /** THE SPEAKER'S ROLE (iOS MTSpeakerHead.Role): 1 -- the group's owner, 2 -- an administrator the owner named, 0 -- neither. */
    fun roleOf(ref: String?): Int {
        val parts = ref?.takeIf { it.startsWith(SPEAKER_HEAD) }?.removePrefix(SPEAKER_HEAD)?.split("/", limit = 2) ?: return 0
        if (parts.size != 2) return 0
        val g = state(key(parts[0])) ?: return 0
        return if (parts[1] == OWNER_SEAT) 1 else if (parts[1] in g.admins) 2 else 0
    }
    /** The custom title the owner gave a speaker with a role (iOS MTSpeakerHead.rank, G.6), worn instead of the role's own word. */
    fun rankOf(ref: String?): String? {
        if (roleOf(ref) == 0) return null
        val parts = ref!!.removePrefix(SPEAKER_HEAD).split("/", limit = 2)
        return rank(key(parts[0]), parts[1])
    }
    /** The faces' one palette (iOS MontanaAvatar.paletteHex). */
    private val TINTS = intArrayOf(0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFAF52DE.toInt(),
        0xFFFF2D55.toInt(), 0xFFFF3B30.toInt(), 0xFF30B0C7.toInt(), 0xFFD99E38.toInt())
    /** THE COLOUR EVERY PHONE GIVES A SPEAKER (iOS MTSpeakerFace.tint, MontanaAvatar.colorIndex): FNV-1a of their ref over the palette. */
    fun tint(ref: String): Int {
        var h = -2128831035   // 2166136261
        for (b in ref.toByteArray(Charsets.UTF_8)) h = (h xor (b.toInt() and 0xFF)) * 16777619
        return TINTS[(h.toUInt() % TINTS.size.toUInt()).toInt()]
    }
    /** The line above a speaker's bubble (iOS speakerLine): in a group every letter of another says who wrote it; a channel speaks as itself. */
    fun speakerLine(c: Context, m: Msg, chat: String): String? =
        if (m.mine || m.from?.startsWith(SPEAKER_HEAD) != true || state(chat)?.kind != GROUP) null else speakerName(c, m.from)

    // ── the wire ──

    /** A person this phone will not carry the group to (iOS ChatStore.refusesCold): one it blocked, or one the network barred. */
    private fun refuses(pipe: String): Boolean = PeerSafety.isBlocked(pipe) || pipe in PeerSafety.barred()

    /**
     * ONE COPY TO ONE RECEIVER, UNDER A NAME OF ITS OWN (iOS carry): it has no row of its own — the row it carries stands in the
     * group's feed under the letter's name, and the copy's receipt moves that row (follow).
     */
    private fun carry(w: JSONObject, pipe: String, letter: String, own: Boolean, ring: Boolean? = null): String? {
        val secret = Book.secret(pipe) ?: return null
        if (refuses(pipe)) return null   // a person this phone blocked, or one the network barred, is carried nothing (iOS refusesCold)
        w.put("au", ownTag(w, secret))   // signed by the person: the seed's other devices know the word as their own (566c6dcc)
        val name = Marks.mintMid()
        copies[name] = Copy(w.getString("g"), letter, pipe, own, 1)
        saveCopies()
        // WHETHER A WORD RINGS, ONE ANSWER (iOS MTRing.groupWord, 4c20068e): a letter (say) that is neither the past (G.4) nor a
        // channel's silent post, or a room's invitation to the very seat it is carried to -- nothing else: an invitation into the
        // group, a renewal of its face, title or rights, an administrator named, an answer, a leaving, a taking out are all silent
        val t = w.optString("t")
        val rings = w.optInt("hs") != 1 && w.optInt("sl") != 1 && ring != false && (t == "say" || (t == "room" && ring == true))
        Post.send(pipe, name, MARK + w.toString(), quiet = !rings)
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
        if (t == "say" && g.kind == CHANNEL && g.silent) w.put("sl", 1)   // the bell in the owner's field (iOS w.sl, Silent Broadcast)
        clean(Prefs.userName, NAME_LIMIT).takeIf { it.isNotEmpty() }?.let { w.put("n", it) }
        return w
    }

    /**
     * A LETTER OF MINE INTO A GROUP (iOS ChatStore.send → MTGroup.send): long words leave as pieces (breakOutgoingText), each a row
     * born in the group's feed under its one name and a copy to everyone this phone carries to — on a member's phone, the owner;
     * the receipts raise the row (follow). Nobody to carry to: honest red. False when the group takes no letter from this phone.
     */
    fun send(key: String, words: String, qt: String?, qm: String?, first: ((String, String) -> Unit)? = null): Boolean {
        var lead: Pair<String, String>? = null
        synchronized(lock) {
            val g = state(key)
            if (g == null || g.left || !posts(g, g.me) || words.isBlank()) {
                Log.i("Montana", "group_refused send -- group=" + (if (g == null) 0 else 1))
                return false
            }
            // A RIGHT THE GROUP TOOK, OR SLOW MODE'S WAIT (iOS refusal, G.5): said, and the letter is not made
            refusal(Book.ctx, key, words, letter = true)?.let { tellRefusal(it); return false }
            pieces(words).forEachIndexed { i, piece ->
                if (!carries(piece)) return@forEachIndexed
                val first = i == 0
                val mid = Marks.mintMid()
                Book.edit(key) { it.msgs.add(Msg(mid, piece, true, Marks.birthMs(mid) ?: System.currentTimeMillis(),
                    qt = qt.takeIf { first }, qm = qm.takeIf { first })) }
                val w = word("say", g).put("id", mid).put("s", g.me).put("tx", piece)
                if (first && !qt.isNullOrEmpty()) w.put("qt", qt)
                if (first && !qm.isNullOrEmpty()) w.put("qm", qm)
                dress(w, g)
                // a group of its owner alone holds the letter here (iOS MTGroup.alone, b4cf7d10); else nobody to carry to: honest red
                if (spread(w, g, null, own = true) == 0) Book.edit(key) { c -> c.msgs.find { it.mid == mid }?.advance(if (alone(g)) 2 else -1) }
                if (first && lead == null) lead = mid to piece
            }
        }
        // the first piece's row, out of the lock: the card the person saw above the field attends it (iOS MTLinkCards.attend)
        lead?.let { (mid, piece) -> first?.invoke(mid, piece) }
        return true
    }

    /**
     * THE CARD OF A LETTER OF MINE, TO EVERYONE THIS PHONE CARRIES TO (iOS carryCard, 89dfd51d; the author's word 17.10: «everything as
     * in chats, one to one»): the card the person saw above the field, and its picture when it catches up, ride as the group's own word
     * «lp» under the letter's name -- the pair's same-name copy that carries the card, said the group's way, cut to the pair's leg.
     */
    fun carryCard(key: String, mid: String, card: LinkCard) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (g.left || !posts(g, g.me) || !isLetterName(mid)) return
            if (Book.chat(key)?.msgs?.any { it.mine && it.mid == mid } != true) return
            val tx = LinkPreview.fitting(card.json())?.takeIf { it.toByteArray().size <= CARD_LIMIT } ?: return
            val n = spread(word("lp", g).put("id", mid).put("s", g.me).put("tx", tx), g, null, own = false)   // no copy of a card witnesses the row
            Log.i("Montana", "group_tx mid=" + mid.take(14) + " card copies=$n bytes=" + tx.toByteArray().size)
        }
    }

    /**
     * A STICKER'S PASSPORT INTO A GROUP (iOS MTGroup.carryPassport, MontanaGroup.swift:774): the set my sticker is of rides as the
     * group's own word «sp» under the sticker letter's name, to everyone this phone carries to; it names no address. An older build
     * buries it.
     */
    fun carryPassport(key: String, letter: String, body: String) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (g.left || !posts(g, g.me) || !isLetterName(letter) || body.toByteArray().size > PASSPORT_LIMIT) return
            val n = spread(word("sp", g).put("id", letter).put("s", g.me).put("tx", body), g, null, own = false)   // no copy witnesses the row
            Log.i("Montana", "group_tx mid=" + letter.take(14) + " passport copies=$n")
        }
    }

    /**
     * A STICKER'S PASSPORT (iOS passported, MontanaGroup.swift:2403): read by the pair's one reader of a passport (StickerWire.handle)
     * and carried on by the owner. Only the sticker's speaker names it; it names no address, so the set is asked of the pipe it came by
     * (a member holds the owner's).
     */
    private fun passported(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED
        val id = w.optString("id"); val body = w.optString("tx")
        if (!isLetterName(id) || body.toByteArray().size > PASSPORT_LIMIT) return Verdict.REFUSED
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Verdict.REFUSED
        if (o.optString("t") != "id" || StickerBook.bare(o.optString("m")) != id) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val seat = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == from }?.seat
            else w.optString("s").takeIf { SamePair.root(g.owner) == from && isSeat(it) && it != g.me }) ?: return Verdict.REFUSED
        val row = Book.chat(key(g.id))?.msgs?.firstOrNull { it.mid == id }
        if (row != null && (row.mine || author(row, g) != seat)) {
            Log.i("Montana", "group_refused a passport past its sticker's speaker")
            return Verdict.REFUSED
        }
        o.remove("o")   // no address rides a group: the giver is the pipe this word came by
        val clean = o.toString()
        StickerWire.handle(StickerWire.MARK + clean, pipe, key(g.id))
        if (g.mine) spread(word("sp", g).put("id", id).put("s", seat).put("tx", clean), g, seat, own = false)
        Log.i("Montana", "group_rx mid=$id passport owner=" + (if (g.mine) 1 else 0))
        return Verdict.HELD
    }

    /**
     * A LETTER'S CARD (iOS carded, 89dfd51d): it lands on its letter's row by the pair's one rule of a late card (LinkPreview.enrich),
     * and the owner carries it on. Only the letter's speaker names its card, and only for a link the letter's own words carry; a card
     * that outran its letter waits for it ten minutes by the letter's birth.
     */
    private fun carded(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED
        val id = w.optString("id"); val lp = w.optString("tx")
        if (!isLetterName(id) || lp.toByteArray().size > CARD_LIMIT) return Verdict.REFUSED
        val card = LinkCard.parse(lp) ?: return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val seat = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == from }?.seat
            else w.optString("s").takeIf { SamePair.root(g.owner) == from && isSeat(it) && it != g.me }) ?: return Verdict.REFUSED
        val key = key(g.id)
        val row = Book.chat(key)?.msgs?.firstOrNull { it.mid == id }
        if (row == null) {
            if (id in gone || Book.isBuried(key, id)) return Verdict.HELD   // the letter is gone here already
            val born = Marks.birthMs(id) ?: return Verdict.REFUSED
            return if (System.currentTimeMillis() - born < CARD_WAIT_MS) Verdict.WAIT else Verdict.REFUSED
        }
        if (row.mine || author(row, g) != seat || card.u !in LinkPreview.webURLs(row.text)) {
            Log.i("Montana", "group_refused a card past its letter's speaker or its words")
            return Verdict.REFUSED
        }
        Book.edit(key) { c -> c.msgs.find { it.mid == id }?.let { LinkPreview.enrich(it, lp, id) } }
        if (g.mine) spread(word("lp", g).put("id", id).put("s", seat).put("tx", lp), g, seat, own = false)
        Log.i("Montana", "group_rx mid=$id card owner=" + (if (g.mine) 1 else 0))
        return Verdict.HELD
    }

    /**
     * A MEDIA LETTER OF MINE INTO A GROUP (iOS MTGroup.carryMedia): its row stands in the feed already under its one name, its
     * file's sealed pieces lie on the nodes for every receiver — the node's term keeps them, no single receipt takes them away —
     * and its manifest leaves to everyone this phone carries to, as a word does. False: nobody took it.
     */
    fun carryMedia(key: String, mid: String, letter: String): Boolean {
        synchronized(lock) {
            val g = state(key)
            // ANOTHER'S LETTER LAID BACK TO ITS RESTORED AUTHOR (iOS 2c0498fd, OZ 2 p.1.2.4): the copy goes as «fo» under its author's
            // seat to the pipe that asked, never to the group. Its row is another's: no answer of this send may paint it red, so the
            // send says yes.
            val theirs = runCatching { Book.chat(key)?.msgs?.firstOrNull { !it.mine && it.mid == mid } }.getOrNull()
            if (g != null && theirs != null) {
                val ref = theirs.from
                val to = ref?.let { synchronized(ownLays) { ownLays[it] } }
                val at = ref?.let { seatOf(it, key) }
                if (to == null || at == null || !letter.startsWith(Marks.MEDIA) || !isLetterName(mid)) {
                    Log.i("Montana", "kept_file m=" + mid.take(8) + " a copy of another's letter nobody asked for -- not carried")
                    return true
                }
                val back = JSONObject().put("t", "fo").put("g", g.id).put("k", g.kind).put("id", mid).put("s", at).put("tx", letter)
                val sent = carry(back, to, "", own = false) != null
                Log.i("Montana", "kept_file m=" + mid.take(8) + " a copy laid back to its restored author sent=" + (if (sent) 1 else 0))
                return true
            }
            if (g == null || g.left || !posts(g, g.me) || !letter.startsWith(Marks.MEDIA) || !carries(letter) || !isLetterName(mid)) {
                Log.i("Montana", "group_refused media -- group=" + (if (g == null) 0 else 1))
                return false
            }
            val w = word("say", g).put("id", mid).put("s", g.me).put("tx", letter).also { dress(it, g) }
            // A KEPT FILE LAID AGAIN UNDER ITS CARRIED LETTER (iOS carryMedia, 11c34ed7 at 2389, OZ 1 p.10.1): the row stands everywhere,
            // nobody is rung
            if (runCatching { Book.chat(key)?.msgs?.any { it.mine && it.mid == mid && 2 <= it.state } }.getOrNull() == true) w.put("hs", 1)
            val n = spread(w, g, null, own = true)
            if (n == 0 && alone(g)) { Book.edit(key) { c -> c.msgs.find { it.mid == mid }?.advance(2) }; return true }   // held here (A.2)
            return n > 0
        }
    }

    /** A group of its owner alone (iOS MTGroup.alone, the checklist's A.2): nobody to carry to, so a letter is held where it was written, never red. */
    private fun alone(g: State): Boolean = g.mine && g.kind == GROUP && g.members.isEmpty()

    /**
     * MY FACE RIDES MY OWN LETTER WHERE THE GROUP'S PEOPLE MAY LACK IT (iOS MTGroup.dress, 4c20068e): a new face, a person counted since,
     * a day since -- else the letter goes bare and the face costs nothing.
     */
    private fun dress(w: JSONObject, g: State) {
        if (g.kind != GROUP) return
        val img = SelfFace.bytes(Book.ctx) ?: return
        val tag = img.size.toString() + "-" + java.util.Arrays.hashCode(img).toString(16)
        val now = System.currentTimeMillis()
        g.faceGiven?.let { if (it.tag == tag && it.count == g.count && now - it.at < FACE_AGAIN) return }
        val small = thumb(img, SPEAKER_FACE_SIDE) ?: return
        w.put("sf", small)
        g.faceGiven = FaceGiven(tag, g.count, now)
        save()
    }
    /** A speaker's face taken from their own letter, by seat (iOS takeFace): a repeat lays no second file, a new face takes the old one's place. */
    private fun takeFace(b64: String?, seat: String, g: State): Boolean {
        if (b64.isNullOrEmpty() || g.kind != GROUP) return false
        val mark = tagOfFace(b64)
        if (g.faceTags[seat] == mark) return false
        val d = face(b64) ?: return false
        val name = "gsf_" + g.id + "_" + seat + "_" + Integer.toHexString(mark.hashCode())
        if (runCatching { Book.face(name).writeBytes(d) }.isFailure) return false
        g.faces[seat]?.takeIf { it != name }?.let { runCatching { Book.face(it).delete() } }
        g.faces[seat] = name
        g.faceTags[seat] = mark
        return true
    }
    /** The tag a face on the wire is known by -- its length and its tail (iOS tag(ofFace:)): a repeat of the same word lays no second file. */
    private fun tagOfFace(b64: String) = b64.length.toString() + "-" + b64.takeLast(16)

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
     * default for a new group; never in a channel. The one gate of a pin, asked where it is sent and wherever it lands: the owner,
     * an administrator holding «Pin Messages» (G.6), or anyone while the members keep their own «Pin Messages» (G.5).
     */
    private fun pins(g: State, seat: String): Boolean =
        g.kind == GROUP && seat.isNotEmpty() && (adminCan(g, seat, PIN) || M_PIN !in g.barred)

    // ── what an administrator may do (the checklist's G.6, the reference's «What can this admin do?») ──

    /** The rights, each a short word on the wire (iOS MTAdminRight): the owner names the ones an administrator lacks. */
    const val INFO = "ci"; const val DELETE = "dm"; const val BAN = "bu"; const val INVITE = "il"; const val PIN = "pm"
    val RIGHTS = listOf(INFO, DELETE, BAN, INVITE, PIN)
    /** A CHANNEL'S ADMINISTRATOR'S RIGHTS (iOS MTChannelRight): its info, posting through the owner, taking another's post away, its link. */
    const val POST = "po"
    val CH_RIGHTS = listOf(INFO, POST, DELETE, INVITE)
    /** The rights the owner hands an administrator of this group or channel, in the reference's order (iOS setAdmin's words). */
    fun rights(key: String): List<String> = if (state(key)?.kind == CHANNEL) CH_RIGHTS else RIGHTS
    private const val RANK_LIMIT = 16   // iOS clean(rank, 16)
    /** WHETHER A SEAT HOLDS AN ADMINISTRATOR'S RIGHT (iOS adminCan): the owner every one; an administrator the ones the owner did not take. */
    private fun adminCan(g: State, seat: String, r: String): Boolean {
        if (seat == OWNER_SEAT) return true
        if (seat !in g.admins) return false
        return r !in (g.taken[seat] ?: emptyList())
    }

    /**
     * A LETTER PINNED OR UNPINNED FOR EVERYONE (iOS MTGroup.pin): here at once, and its word carried to every phone of the group by
     * the answers' road; `notify` -- «Pin and notify all members» against «Only Pin» (n 0: pinned without a banner); a pin lays
     * the service row «X pinned "..."» (iOS lay, MTGroupEvent).
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
        pipes.filter { !refuses(it) }.forEach { Post.now(it, text) }
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
     * stands at once; the invitations ride the queue until each is receipted. The key of the group's feed, or null when it has no
     * name or the store is not ready -- a group may begin with nobody (A.2).
     */
    fun create(channel: Boolean, title: String, about: String, face: ByteArray?, people: List<String>): String? {
        synchronized(lock) {
            val name = clean(title, TITLE_LIMIT)
            val pipes = people.map { SamePair.root(it) }.filter { !isKey(it) && Book.secret(it) != null && !refuses(it) }.distinct().take(PEOPLE_LIMIT)
            // A GROUP MAY BEGIN WITH NOBODY YET (iOS b4cf7d10, the checklist's A.2): its owner adds people from its page
            if (!ready() || name.isEmpty()) {
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
            if (g.kind == GROUP) g.round = ownBuild()   // its invitations name the seats and the round (iOS d28805b6, OZ 1 p.1, p.8)
            if (g.kind == GROUP) g.history = true   // A NEW GROUP SHOWS ITS PAST TO NEW MEMBERS (iOS 99825581, the author's word 20455 p.2)
            g.fullFaced = true   // its invitations carry the full face beside the small one (iOS 06c8eaf1, OZ 1 p.1)
            groups[id] = g
            save()
            stand(g, face, revive = true)   // the group is born here
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
    private fun tellAll(g: State, face: String?, only: Set<String>? = null, by: String = OWNER_SEAT) {
        // the group's link, minted once, for the administrators who may invite by it (iOS 1957-1958, G.6)
        val link = if (g.admins.any { adminCan(g, it, INVITE) }) standingLink(g) else null
        // the picture itself beside the small face, laid once for every member it rides to (iOS 06c8eaf1, OZ 1 p.1)
        val full = if (face == null) null else Book.face(key(g.id)).takeIf { it.isFile }?.let { f -> runCatching { f.readBytes() }.getOrNull() }
            ?.let(::layFullFace)
        for (m in g.members) {
            val w = word("inv", g).put("k", g.kind).put("ti", g.title).put("c", g.count).put("me", m.seat)
            if (g.about.isNotEmpty()) w.put("ds", g.about)
            if (face != null && (only == null || m.seat in only)) {
                w.put("fc", face)
                full?.let { w.put("ff", it) }   // the full face rides with the small one alone (iOS 06c8eaf1)
            }
            if (g.admins.isNotEmpty()) w.put("ad", JSONArray(g.admins))   // the administrators the owner named (iOS ad, stage R.6)
            // the administrators' rights left out and the custom titles (iOS ax, rk, G.6); an older build skips the keys
            if (g.taken.isNotEmpty()) w.put("ax", JSONObject().apply { g.taken.forEach { (k, v) -> put(k, JSONArray(v)) } })
            if (g.ranks.isNotEmpty()) w.put("rk", JSONObject(g.ranks.toMap()))
            if (g.kind == CHANNEL && g.signs) w.put("sg", 1)   // a channel signs its posts (iOS sg, Sign Messages)
            if (g.kind == CHANNEL && !g.reacts) w.put("rx", 0)   // a channel takes no reactions (iOS rx, Allow Reactions off)
            if (g.kind == CHANNEL && g.talks) w.put("dc", 1)   // a channel's subscribers comment (iOS dc, Allow Comments)
            if (g.kind == CHANNEL) g.reactList?.let { w.put("rl", JSONArray(it)) }   // the reactions a channel takes (iOS rl, Some Reactions)
            if (link != null && m.seat in g.admins && adminCan(g, m.seat, INVITE)) w.put("lk", link)   // the link, to an admin who invites
            // what the members may not do, and the slow mode (iOS bn, sw, G.5): an older build skips the keys, the owner's door holds
            if (g.barred.isNotEmpty()) w.put("bn", JSONArray(g.barred))
            if (0 < g.slow) w.put("sw", g.slow)
            if (by != OWNER_SEAT) w.put("s", by)   // a member's renewal names its own hand (iOS w.s, G.5)
            nameSeats(w, g)   // every member's seat and the owner's round: the page and the head say the same (iOS d28805b6, OZ 1 p.1, p.8)
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
    /** THE SEATS A GROUP'S «inv» NAMES, ONE ANSWER (iOS roster, 07d5fc70): every member's, never a pipe; a channel names none -- its
     *  subscribers are not shown to each other. */
    private fun roster(g: State): List<String>? = if (g.kind == GROUP) g.members.map { it.seat } else null
    /** WHAT AN «inv» NAMES OF THE GROUP'S PEOPLE, ONE ANSWER (iOS nameSeats, d28805b6): the seats (roster) and the owner's round. */
    private fun nameSeats(w: JSONObject, g: State) {
        roster(g)?.let { w.put("ps", JSONArray(it)) }
        if (g.kind == GROUP) g.round?.let { w.put("rr", it) }
    }
    /** This phone's build: an owner's new build begins a new round (iOS MontanaRelease.ownBuild). */
    private fun ownBuild(): Int = Update.own(Book.ctx).toInt()
    /**
     * THE SEATS AND THE CARDS KEPT UP AT EVERY LOAD (iOS keepRoster, d28805b6): the owner's phone at a new build begins a new round -- it
     * tells every group its seats again with the round, and each member lays its card again and is answered with all the cards (OZ 1 p.8,
     * the author's word 21075: the cards went once, and one refused or lost never came back); a member's phone lays its card where it is
     * due. Each group's state goes into the diary, so a page that does not open names its reason.
     */
    fun keepRoster() = synchronized(lock) {
        if (!ready()) return@synchronized
        val build = ownBuild()
        for (id in groups.keys.sorted()) {
            val g = groups[id] ?: continue
            if (g.kind != GROUP || g.left) continue
            Log.i("Montana", "group_roster mine=" + (if (g.mine) 1 else 0) + " people=" + (if (g.mine) g.members.size else g.seats?.size ?: -1) +
                " cards=" + g.cards.size + " round=" + (g.round ?: 0))
            if (!g.mine || g.round == build) continue
            g.round = build
            save()
            if (g.members.isNotEmpty()) tellAll(g, null)
        }
        // THE FULL FACE ONCE TO THOSE WHO HOLD THE SMALL ONE (iOS 06c8eaf1, OZ 1 p.1): a face given before rode the small one alone.
        for (id in groups.keys.sorted()) {
            val g = groups[id] ?: continue
            if (!g.mine || g.left || g.fullFaced || g.members.isEmpty()) continue
            val d = Book.face(key(id)).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } ?: continue
            tellAll(g, thumb(d))
            g.fullFaced = true
            save()
        }
        giveCards()
    }

    // ── the cards (iOS 07d5fc70, OZ 1 p.8, the author's decision 877 a) ──

    /** Whether a word is a person's card: a link the one meeting road opens, and short (iOS isCard). */
    fun isCard(s: String): Boolean = s.toByteArray().size <= 256 && Meeting.invite(s) != null

    /**
     * A CARD LAID IN A GROUP (iOS cardLaid): a member's permanent link with the name they give. The owner takes it from the member its
     * pipe holds and hands it to everyone else; a member takes it from the owner for the seat the owner names. A group's alone.
     */
    private fun cardLaid(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        val link = w.optString("tx")
        if (g.kind != GROUP || g.left || !isCard(link)) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val seat = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == from }?.seat
            else w.optString("s").takeIf { SamePair.root(g.owner) == from && isSeat(it) && it != g.me }) ?: return Verdict.REFUSED
        // THE OWNER ANSWERS ONE WHO LAID A CARD WITH EVERY OTHER CARD IT HOLDS (iOS d28805b6, OZ 1 p.8, the author's word 21075): a card
        // handed on while this member's build refused «pc», or lost on the road, reaches it again -- a repeat included; the member lays
        // again only at a new build or a new round (cardMark)
        if (g.mine) g.members.firstOrNull { it.seat == seat }?.let { m -> Log.i("Montana", "group_card answered=" + handCards(g, listOf(m))) }
        val name = clean(w.optString("n"), NAME_LIMIT)
        val named = name.isNotEmpty() && g.names[seat] != name
        if (!named && g.cards[seat] == link) return Verdict.HELD   // a repeat: nothing new to keep or to hand on
        g.cards[seat] = link
        if (named) g.names[seat] = name   // a person's name reaches the group from that person
        save()
        if (g.mine) spread(cardWord(g, seat, name, link), g, seat, own = false)   // COMPAT-GATED
        Log.i("Montana", "group_card laid mine=" + (if (g.mine) 1 else 0))
        return Verdict.HELD
    }
    /**
     * THE CARDS THE GROUP'S PEOPLE LAID, HANDED TO SOME OF THEM, ONE ANSWER (iOS handCards, d28805b6, OZ 1 p.8): each card of one still in
     * the group, with the name it came under, to every person given -- never a person's own card back to them. One just added takes them
     * after its invitation (add); one who laid its card takes everyone else's (cardLaid). The count of the words that left.
     */
    private fun handCards(g: State, people: List<Member>): Int {
        var n = 0
        for ((seat, link) in g.cards.toSortedMap()) {
            if (g.members.none { it.seat == seat }) continue
            val w = cardWord(g, seat, g.names[seat].orEmpty(), link)   // COMPAT-GATED: an older build refuses «pc»
            for (m in people) if (m.seat != seat && carry(w, m.pipe, "", own = false) != null) n++
        }
        return n
    }
    /** THE MARK A MEMBER'S CARD IS LAID UNDER, ONE ANSWER (iOS cardMark, d28805b6): the seat, the name, this phone's build and the owner's
     *  round -- a new one of any lays the card again and brings every card the owner holds back in answer. */
    private fun cardMark(g: State, name: String) = g.me + "/" + name + "/" + ownBuild() + "/" + (g.round ?: 0)
    /** The word of one card: an older build refuses «pc», so it goes only where the owner names seats (COMPAT-GATED). */
    private fun cardWord(g: State, seat: String, name: String, link: String) =
        word("pc", g).put("s", seat).put("tx", link).apply { if (name.isNotEmpty()) put("n", name) }

    /**
     * THIS PHONE LAYS ITS OWN CARD IN EVERY GROUP WHOSE OWNER NAMES SEATS (iOS giveCards): the permanent link and the person's name, to
     * the owner, who hands them to everyone; again at a new seat or a new name. An owner that names no seats is an older build and would
     * refuse the word: nothing goes to it. The link is drawn off the lock -- its birth gathers randomness and may ask the node.
     */
    private fun giveCards() {
        val name = clean(Prefs.userName, NAME_LIMIT)
        if (groups.values.none { owesCard(it, name) }) return
        Thread {
            val link = runCatching { MontanaCard.offerPermanent(Book.ctx) }.getOrNull()
            if (link == null || !isCard(link)) { Log.i("Montana", "group_card given=0 -- no permanent link"); return@Thread }
            layCards(link, name)
        }.start()
    }
    private fun owesCard(g: State, name: String) =
        !g.mine && g.kind == GROUP && !g.left && g.seats != null && g.cardGiven != cardMark(g, name)
    private fun layCards(link: String, name: String) = synchronized(lock) {
        var laid = 0
        for (id in groups.keys.sorted()) {
            val g = groups[id] ?: continue
            if (!owesCard(g, name)) continue
            if (carry(cardWord(g, g.me, name, link), g.owner, "", own = false) == null) continue
            g.cardGiven = cardMark(g, name)
            laid++
        }
        if (0 < laid) save()
        Log.i("Montana", "group_card given=$laid")
    }
    /** A new name lays the card again in each group (iOS E2E.broadcastName → giveCards, 07d5fc70). */
    fun nameChanged() = synchronized(lock) { if (ready()) giveCards() }
    /** The owner's seat, as the page compares it. */
    const val OWNER = OWNER_SEAT
    /**
     * THE PEOPLE OF A GROUP'S PAGE (iOS pagePeople, stage R -- the reference folder lists every member): the owner's phone everyone it
     * carries to; a member's phone the owner first, then everyone who spoke -- the owner names nobody, the others come by their own words.
     */
    fun pagePeople(c: Context, key: String): List<Seat> {
        val g = state(key) ?: return emptyList()
        val out = shownSeats(c, key).toMutableList()
        if (!g.mine) synchronized(lock) { pageRest(g) }.filter { s -> out.none { it.seat == s } }
            .map { s -> Seat(s, speakerName(c, speaker(g.id, s)), "") }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { out.add(it) }
        return out
    }
    /**
     * ONE PERSON OF A GROUP AS A COIN OF THIS PHONE REACHES THEM (iOS MTGroupPayee): the seat, the name shown, the pair's chat a
     * transfer to them rides when it is open to coins (null otherwise), and whether this phone holds a pipe with them at all.
     */
    class Payee(val seat: String, val name: String, val ref: String?, val held: Boolean)
    /**
     * WHOM A COIN OF THE GROUP REACHES FROM THIS PHONE (iOS coinPeople; the author's word 17.10.2026 21:14 UTC: «with a choice by check
     * marks among the members, as on the Send page of the time coin»): a transfer is one record for one receiver under the pair's own
     * secret, so a person is reached only through a pipe this phone holds with them -- the owner's phone every member it carries to,
     * a member's phone the owner. The others stand by their seat and name alone; the pair's own door decides the rest (CoinSend.canPay).
     */
    fun coinPeople(c: Context, key: String): List<Payee> {
        if (!takesCoins(key)) return emptyList()
        val me = state(key)?.me ?: return emptyList()
        return pagePeople(c, key).filter { it.seat != me }.map { p ->
            if (p.pipe.isEmpty()) return@map Payee(p.seat, p.name, null, false)
            val root = SamePair.root(p.pipe)
            Payee(p.seat, p.name, root.takeIf { CoinSend.canPay(it) }, true)
        }
    }
    /**
     * THE GROUP IS TOLD, IT IS NEVER PAID (iOS tellCoins): the coins went to each person picked by the pair's own transfer; the group's
     * feed gets the sender's plain words of how many were paid -- no sum, no name, no coin letter, so no phone of the group credits
     * anything by it. A right the group took from its members, or slow mode's wait, leaves the words unsaid: the transfers stand.
     */
    fun tellCoins(c: Context, people: Int, key: String) {
        if (people <= 0 || !takesCoins(key)) return
        val words = "🪙 " + c.resources.getQuantityString(R.plurals.gr_coins_told, people, people)
        if (!carries(words) || refusal(c, key, words, true) != null) { Log.i("Montana", "group_coin told=0 people=$people"); return }
        send(key, words, null, null)
        Log.i("Montana", "group_coin told=1 people=$people")
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
     * A LETTER THAT NAMES ME (iOS mentionsMe, 5666090f: no nick any more): «@» and my own name. The name must end where a word ends:
     * «@Al» is not a mention of a person named «Al» inside «@Alex». It rings even in a chat whose sound is off (Notify.letter).
     */
    fun mentionsMe(words: String): Boolean {
        val low = words.lowercase()
        return listOf(Prefs.userName).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { name ->
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

    /** This phone's own name among a group's people, as it goes out with its words; «Member» while it has none (iOS lines, clean(myDisplayName)). */
    fun myName(c: Context) = clean(Prefs.userName, NAME_LIMIT).ifEmpty { c.getString(R.string.gr_member) }
    /** This phone owns the group: its page edits it (iOS state.mine). */
    fun owns(key: String) = state(key)?.mine == true
    /** This phone owns the group and has not ended it: it adds, names, takes out and hands the link (iOS manages, canManage). */
    fun manages(key: String) = state(key)?.let { it.mine && !it.left } == true
    /** Whether the owner's phone already carries the group to this person (iOS carries(to:in:)): the page's «add» lists the others only. */
    fun carriesTo(pipe: String, key: String): Boolean {
        val p = SamePair.root(pipe)
        return state(key)?.members?.any { SamePair.root(it.pipe) == p } == true
    }

    /**
     * THE OWNER RENAMES THE GROUP OR GIVES IT A NEW FACE (iOS renew): every member is told by the invitation's own word again —
     * the same group, the same seat, the new title and face — so every phone shows what the owner's shows.
     */
    fun renew(key: String, title: String, face: ByteArray?, about: String? = null, by: String = OWNER_SEAT) {
        synchronized(lock) {
            val g = state(key) ?: return
            val name = clean(title, TITLE_LIMIT)
            // the description, the owner's words about the group (iOS renew about, stage R): carried in the invitation's ds
            val words = about?.let { clean(it, ABOUT_LIMIT) }
            val described = words != null && words != g.about
            if (name.isEmpty() || (name == g.title && face == null && !described)) return
            // A MEMBER WITH «CHANGE GROUP INFO» (iOS askRenew, G.5) asks the owner, whose phone carries the group; the owner's own hand renews here
            if (!g.mine) { if (!g.left) askRenew(g, name, face, words); return }
            val renamed = name != g.title
            g.title = name
            if (words != null) g.about = words
            if (renamed) act(g, "title", t = name)
            if (described) act(g, "about")
            if (face != null) act(g, "photo")
            save()
            stand(g, face, revive = false)   // the owner's own row follows the group, never back from away
            tellAll(g, face?.let { thumb(it) }, by = by)
            if (renamed) lay(g, event("named", by, t = name))
            if (face != null) lay(g, event("face", by))
            Log.i("Montana", "group_renewed face=" + (if (face == null) 0 else 1) + " about=" + (if (described) 1 else 0))
        }
    }
    /** The owner's words about the group, read by everyone on its page (iOS about, stage R). */
    fun about(key: String): String = state(key)?.about ?: ""

    /** THE OWNER ADDS PEOPLE (iOS add): each new one is invited with a seat of their own and the group's face; everyone learns the count. */
    fun add(key: String, people: List<String>, viaLink: Boolean = false) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine) return
            val held = g.members.map { SamePair.root(it.pipe) }.toSet()
            val taken = (g.members.map { it.seat } + OWNER_SEAT).toMutableSet()
            val fresh = mutableListOf<Member>()
            for (p in people.map { SamePair.root(it) }.distinct()) {
                if (isKey(p) || Book.secret(p) == null || refuses(p) || p in held || g.members.size + fresh.size >= PEOPLE_LIMIT) continue
                var seat = mintSeat()
                while (seat in taken) seat = mintSeat()
                taken.add(seat)
                fresh.add(Member(seat, p))
            }
            if (fresh.isEmpty()) return
            g.members.addAll(fresh)
            g.count = g.members.size + 1
            act(g, if (viaLink) "joined" else "invited", fresh.map { nameNow(g, it.seat) })
            // one taken out and added again by the owner's own hand is let back (iOS add, the reference's «Add To Group», G.7)
            g.banned.removeAll { b -> fresh.any { it.pipe == SamePair.root(b.pipe) } }
            save()
            val face = Book.face(key).takeIf { it.exists() }?.readBytes()?.let { thumb(it) }
            tellAll(g, face, fresh.map { it.seat }.toSet())
            handCards(g, fresh)   // the cards the group's people laid go to one just added, after the invitation (iOS d28805b6, OZ 1 p.8)
            // one who came by the group's link joined by their own hand (iOS add viaLink, the reference: «%@ joined the group via invite link»)
            val came = if (viaLink) event("joined", fresh[0].seat) else event("added", OWNER_SEAT, fresh.map { it.seat })
            lay(g, came)
            tellEvent(g, came)
            handPast(g, fresh)
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
            act(g, "removed", listOf(nameNow(g, seat)))
            // ONE TAKEN OUT IS KEPT OUT (iOS remove, G.7 and E.4, the reference's «Removed Users»): until the owner lets them back or adds them again
            g.banned.removeAll { it.seat == seat }
            g.banned.add(m)
            while (PEOPLE_LIMIT < g.banned.size) g.banned.removeAt(0)
            g.members.removeAll { it.seat == seat }
            g.admins.remove(seat)   // a person taken out is nobody's administrator
            g.cards.remove(seat)   // their card leaves with them (iOS 07d5fc70, OZ 1 p.8)
            g.taken.remove(seat); g.ranks.remove(seat)
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
    // ── the group's invite link (iOS stage R.6 and G.3, the reference's «Invite Link», «joined the group via invite link») ──

    /** The link's query word: the group's id and its standing mark, «jg=<id>.<mark>» (iOS joinParam). */
    private const val JOIN_PARAM = "jg"
    /**
     * THE LINK THE OWNER HANDS OUT (iOS inviteLink): its own permanent card -- whoever opens it meets the owner, the one road of a
     * link -- and the group's mark after it. Null: no card stands, or this phone owns no such group. The card may be born on the
     * network: off the main thread.
     */
    fun inviteLink(c: Context, key: String): String? {
        val mark = synchronized(lock) {
            val g = state(key) ?: return null
            if (!g.mine || g.left) return null   // a group's or a channel's (iOS inviteLink: canManage alone)
            g.invite ?: mintId().also { g.invite = it; save() }
        }
        val card = MontanaCard.offerPermanent(c) ?: return null
        val id = state(key)?.id ?: return null
        return joinLink(card, id, mark)
    }
    private fun joinLink(card: String, id: String, mark: String) = card + (if ('?' in card) "&" else "?") + JOIN_PARAM + "=" + id + "." + mark
    /**
     * The owner's link as the invitation's word carries it (iOS tellAll's inviteLink): the card this phone already holds, never one
     * born here -- the word is laid under the groups' lock, and a card is born on the network. Null: no card stands yet (handLink).
     */
    private fun standingLink(g: State): String? {
        if (!g.mine || g.left) return null
        val card = MontanaCard.heldPermanent() ?: return null
        val mark = g.invite ?: mintId().also { g.invite = it; save() }
        return joinLink(card, g.id, mark)
    }
    /**
     * AN ADMINISTRATOR WHO MAY INVITE HOLDS THE LINK AT ONCE (iOS tellAll mints it before it tells, G.6): where no card stands yet,
     * the page asks for one off the main thread, and the invitation's word goes again once it is born.
     */
    fun handLink(c: Context, key: String) {
        if (MontanaCard.heldPermanent() != null) return
        val g = state(key) ?: return
        if (!g.mine || g.admins.none { adminCan(g, it, INVITE) }) return
        if (inviteLink(c, key) == null) return
        synchronized(lock) { state(key)?.let { if (!it.left) tellAll(it, null) } }
    }
    /** The link the owner handed this phone, an administrator who may invite by it (iOS state.link, G.6); null elsewhere. */
    fun heldLink(key: String): String? = state(key)?.link
    /** A NEW MARK (iOS resetInvite, the reference's «Revoke Link»): every link given before opens nothing more. */
    fun resetInvite(key: String) = synchronized(lock) {
        val g = state(key) ?: return@synchronized
        if (!g.mine) return@synchronized
        g.invite = mintId()
        save()
        // an administrator who invites holds the new link at once (iOS 1574-1575, G.6); the old one opens nothing more
        if (g.admins.any { adminCan(g, it, INVITE) }) tellAll(g, null)
        Log.i("Montana", "group_link reset")
    }
    /** A group's id or a link's mark: 32 lowercase hex, the form mintId makes (iOS isMark). */
    private fun isMark(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }
    /** A JOIN THAT WAITS FOR ITS MEETING (iOS waitingJoin): the link's group and mark, asked of the owner once the meeting opens; ten minutes at most. */
    private var waitingJoin: Triple<String, String, Long>? = null
    /**
     * THE LINK'S GROUP MARK IS TAKEN OFF BEFORE THE MEETING (iOS joinMark + waitJoin, MontanaMeeting.handleLink): the join waits,
     * and the bare card goes on by the one road of a link. A link without the mark comes back as it was.
     */
    fun takeJoin(link: String): String {
        val u = runCatching { android.net.Uri.parse(link.trim()) }.getOrNull() ?: return link
        val v = runCatching { u.getQueryParameter(JOIN_PARAM) }.getOrNull() ?: return link
        val bits = v.split(".", limit = 2)
        if (bits.size != 2 || !isMark(bits[0]) || !isMark(bits[1])) return link
        val bare = u.buildUpon().clearQuery().apply {
            u.queryParameterNames.filter { it != JOIN_PARAM }.forEach { n -> u.getQueryParameters(n).forEach { appendQueryParameter(n, it) } }
        }.build().toString()
        synchronized(lock) { waitingJoin = Triple(bits[0], bits[1], System.currentTimeMillis()) }
        Log.i("Montana", "group_link a join waits for its meeting")
        return bare
    }
    /** THE MEETING OPENED ITS CONVERSATION (iOS askJoin): the waiting join is asked of the owner over it, the mark and this phone's name. */
    fun askJoin(pipe: String) {
        val w = synchronized(lock) {
            val j = waitingJoin ?: return
            waitingJoin = null
            if (System.currentTimeMillis() - j.third > 600_000 || !ready()) return
            val me = clean(Prefs.userName, NAME_LIMIT)
            JSONObject().put("t", "jn").put("g", j.first).put("id", Marks.mintMid()).put("tx", j.second).apply { if (me.isNotEmpty()) put("n", me) }
        }
        // the meeting's pipe may still be standing up: at once when it holds, else every two seconds, a minute at most (iOS askJoin)
        Thread {
            for (i in 0 until 30) { if (Book.secret(pipe) != null) break; Thread.sleep(2000) }
            val sent = synchronized(lock) { carry(w, pipe, "", own = false) } != null
            Log.i("Montana", "group_link join asked sent=" + (if (sent) 1 else 0))
        }.start()
    }
    /**
     * ONE WHO OPENED THE GROUP'S LINK ASKS AT THE OWNER'S PHONE (iOS joined): the mark must be the group's standing one; one the
     * owner took out is refused (G.7, the reference's «Removed Users»); then the person is added by the owner's own road, and
     * everyone reads that they joined by the link.
     */
    private fun joined(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.optString("g")] ?: return Verdict.REFUSED
        val held = g.invite
        if (!g.mine || g.left || held == null || w.optString("tx") != held) return Verdict.REFUSED   // a channel's link too (iOS joined)
        val from = SamePair.root(pipe)
        if (g.members.any { SamePair.root(it.pipe) == from }) return Verdict.HELD   // already in: nothing more
        if (g.banned.any { SamePair.root(it.pipe) == from }) {
            Log.i("Montana", "group_link a removed person asked by the link -- refused")
            return Verdict.REFUSED
        }
        // THE LINK ASKS THE OWNER FIRST (iOS joined, G.8): the asker waits among the requests, once, until the owner adds or dismisses them
        if (g.approval) {
            synchronized(lock) {
                g.requests.removeAll { SamePair.root(it.pipe) == from }
                g.requests.add(Request(from, clean(w.optString("n"), NAME_LIMIT), System.currentTimeMillis()))
                while (PEOPLE_LIMIT < g.requests.size) g.requests.removeAt(0)
                save()
            }
            Log.i("Montana", "group_link a join waits for approval")
            return Verdict.HELD
        }
        add(key(g.id), listOf(from), viaLink = true)
        Log.i("Montana", "group_link joined by the link")
        return Verdict.HELD
    }

    // ── member requests (iOS G.8, the reference's «Request Admin Approval» and «Member Requests») ──

    /** Whether the group's link asks the owner first. */
    fun approval(key: String): Boolean = state(key)?.approval == true
    /** THE OWNER TURNS THE LINK'S APPROVAL ON OR OFF (iOS setApproval): the requests already waiting stay until they are answered. */
    fun setApproval(key: String, on: Boolean) = synchronized(lock) {
        val g = state(key) ?: return@synchronized
        if (!g.mine || g.kind != GROUP || g.approval == on) return@synchronized
        g.approval = on
        save()
        Log.i("Montana", "group_link approval=" + if (on) 1 else 0)
    }
    /** The people waiting to join, by the names they are known by, the newest first (iOS requests). */
    fun requests(c: Context, key: String): List<Request> {
        val g = state(key)?.takeIf { it.mine } ?: return emptyList()
        val list = synchronized(lock) { g.requests.toList() }
        return list.sortedByDescending { it.at }.map { r ->
            val name = Book.chat(SamePair.root(r.pipe))?.shown?.ifBlank { null } ?: r.name.ifEmpty { c.getString(R.string.gr_member) }
            Request(r.pipe, name, r.at)
        }
    }
    /** THE OWNER ADDS ONE WHO ASKED (iOS approve): by the owner's own road, as by the link -- they joined by it. */
    fun approve(key: String, pipe: String) = synchronized(lock) {
        if (state(key)?.mine != true) return@synchronized
        drop(key, pipe)
        add(key, listOf(pipe), viaLink = true)
        Log.i("Montana", "group_link request approved")
    }
    /** THE OWNER DISMISSES ONE WHO ASKED (iOS dismiss): they are told nothing; the link may bring them again. */
    fun dismiss(key: String, pipe: String) = synchronized(lock) {
        if (state(key)?.mine != true) return@synchronized
        drop(key, pipe)
        Log.i("Montana", "group_link request dismissed")
    }
    private fun drop(key: String, pipe: String) {
        val g = state(key) ?: return
        val root = SamePair.root(pipe)
        if (g.requests.removeAll { SamePair.root(it.pipe) == root }) save()
    }

    /** THE OWNER LETS A REMOVED PERSON BACK (iOS unban, the reference's «Removed Users», Delete): they are no longer kept out. */
    fun unban(key: String, seat: String) = synchronized(lock) {
        val g = state(key) ?: return@synchronized
        if (!g.mine || !g.banned.removeAll { it.seat == seat }) return@synchronized
        save()
        Log.i("Montana", "group_banned let back left=" + g.banned.size)
    }
    /** The people the owner took out, by the names they were known by, for the editor's «Removed Users» (iOS bannedPeople), newest first. */
    fun bannedPeople(c: Context, key: String): List<Seat> {
        val g = state(key)?.takeIf { it.mine } ?: return emptyList()
        val list = synchronized(lock) { g.banned.toList() }
        return list.asReversed().map { b ->
            val name = Book.chat(SamePair.root(b.pipe))?.shown?.ifBlank { null } ?: g.names[b.seat]?.ifEmpty { null } ?: c.getString(R.string.gr_member)
            Seat(b.seat, name, b.pipe)
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
        return !g.left && adminCan(g, g.me, DELETE)   // a channel's administrators delete posts too (iOS moderates)
    }
    /** Whether this phone takes a person out of the group (iOS «Ban Users», G.6): its owner, or an administrator holding the right. */
    fun mayBan(key: String): Boolean {
        val g = state(key) ?: return false
        return !g.left && adminCan(g, g.me, BAN)
    }
    /** The custom title the owner gave a seat (iOS rank, G.6), else null: the role's own word stands. */
    fun rank(key: String, seat: String): String? = state(key)?.ranks?.get(seat)?.ifEmpty { null }
    /** The rights the owner left out of an administrator (iOS adminTaken, G.6). */
    fun taken(key: String, seat: String): Set<String> = state(key)?.taken?.get(seat)?.toSet() ?: emptySet()
    /** The administrators the owner named, in its order (iOS admins). */
    fun admins(key: String): List<String> = state(key)?.admins?.toList() ?: emptyList()

    /**
     * THE OWNER NAMES OR DISMISSES AN ADMINISTRATOR (iOS setAdmin): every member hears the list by the invitation's word again;
     * `taken` -- the rights left out of it, `rank` -- its custom title (G.6), kept while it is one and gone with its dismissal.
     */
    fun setAdmin(key: String, seat: String, on: Boolean, taken: Set<String> = emptySet(), rank: String? = null) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.members.none { it.seat == seat }) return   // a group's or a channel's (iOS canManage)
            g.admins.remove(seat)
            if (on) g.admins.add(seat)
            g.admins.sort()
            val left = (if (g.kind == CHANNEL) CH_RIGHTS else RIGHTS).filter { it in taken }   // a channel's rights are its own
            if (on && left.isNotEmpty()) g.taken[seat] = left else g.taken.remove(seat)
            val title = clean(rank ?: "", RANK_LIMIT)
            if (on && title.isNotEmpty()) g.ranks[seat] = title else g.ranks.remove(seat)
            act(g, if (on) "promoted" else "demoted", listOf(nameNow(g, seat)))   // a channel's recent actions (its owner's phone alone)
            save()
            tellAll(g, null)
            Log.i("Montana", "group_admin " + (if (on) "named" else "dismissed"))
        }
    }
    /** Whether a channel signs its posts (iOS signs, «Sign Messages»). */
    fun signsOn(key: String) = state(key)?.let { it.kind == CHANNEL && it.signs } == true
    /** THE OWNER SIGNS THE CHANNEL'S POSTS OR STOPS (iOS setSigns): every subscriber hears it by the invitation's word again. */
    fun setSigns(key: String, on: Boolean) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.kind != CHANNEL || g.signs == on) return
            g.signs = on
            act(g, if (on) "signOn" else "signOff")
            save()
            tellAll(g, null)
            Log.i("Montana", "channel_signs " + (if (on) "on" else "off"))
        }
    }
    /** [I-14]: the reactions a channel's list names, at most (iOS reactListLimit). */
    const val REACT_LIST_LIMIT = 80
    /** A list of reactions as it came (iOS reactionList): glyphs alone, each once, bounded; null for none or for every one. */
    fun reactionList(a: JSONArray?): List<String>? = a?.let { reactionList((0 until it.length()).map { i -> it.optString(i) }) }
    fun reactionList(l: List<String>?): List<String>? {
        val out = (l ?: return null).filter { e ->
            e.isNotEmpty() && e.codePointCount(0, e.length) <= 16 &&
                BreakIterator.getCharacterInstance().apply { setText(e) }.let { b -> var n = 0; while (b.next() != BreakIterator.DONE) n++; n <= 4 }
        }.distinct().take(REACT_LIST_LIMIT)
        return out.ifEmpty { null }
    }
    /** [I-14]: the deeds a channel's owner keeps for its Recent Actions (iOS actsKept). */
    private const val ACTS_KEPT = 100
    /** The deeds as JSON -- their moments in ms here, in seconds where the iOS store keeps them (per = 1000). */
    private fun actsJson(l: List<Act>, per: Long) = JSONArray().apply {
        l.forEach { a -> put(JSONObject().put("e", a.e).put("at", if (per == 1L) a.at else a.at / 1000.0)
            .apply { a.p?.let { put("p", JSONArray(it)) }; a.t?.let { put("t", it) } }) }
    }
    private fun actsOf(a: JSONArray?, per: Long): MutableList<Act> = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) }.map { o ->
        Act(o.optString("e"), o.optJSONArray("p")?.let { p -> List(p.length()) { p.optString(it) } }, o.optString("t").ifEmpty { null },
            if (per == 1L) o.optLong("at") else (o.optDouble("at", 0.0) * 1000).toLong())
    }.filter { it.e.isNotEmpty() }.takeLast(ACTS_KEPT).toMutableList() } ?: mutableListOf()
    /** A DEED OF A CHANNEL, WRITTEN ON ITS OWNER'S PHONE (iOS act): a group keeps its events in its feed instead. */
    private fun act(g: State, e: String, p: List<String>? = null, t: String? = null) {
        if (g.kind != CHANNEL || !g.mine) return
        g.acts.add(Act(e, p, t, System.currentTimeMillis()))
        while (ACTS_KEPT < g.acts.size) g.acts.removeAt(0)
    }
    /** The name a seat is known by on this phone right now (iOS seatName): the book's, else the one it gave; empty -- unknown. */
    private fun nameNow(g: State, seat: String): String =
        g.members.firstOrNull { it.seat == seat }?.let { Book.chat(SamePair.root(it.pipe))?.shown?.ifBlank { null } } ?: g.names[seat] ?: ""
    /** A channel's deeds of the last two days, newest first (iOS MTChannelActionsPage, span 48 hours). */
    fun recentActs(key: String): List<Act> = synchronized(lock) {
        val now = System.currentTimeMillis()
        state(key)?.takeIf { it.kind == CHANNEL }?.acts?.filter { now - it.at < 48 * 3600_000L }?.reversed() ?: emptyList()
    }
    /** The reactions a channel takes: its owner's list, or null for every one (iOS reactionList(key)). */
    fun reactionList(key: String): List<String>? = state(key)?.takeIf { it.kind == CHANNEL }?.reactList
    /** Whether a channel's posts take reactions (iOS reactionsOn, «Allow Reactions»): on unless its owner turned them off. */
    fun reactionsOn(key: String) = state(key)?.reacts != false
    /** THE OWNER ALLOWS REACTIONS OR TURNS THEM OFF (iOS setReactions): every subscriber hears it by the invitation's word again. */
    fun setReactions(key: String, on: Boolean) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.kind != CHANNEL || g.reacts == on) return
            g.reacts = on
            act(g, if (on) "reactOn" else "reactOff")
            save()
            tellAll(g, null)
            Log.i("Montana", "channel_reactions " + (if (on) "on" else "off"))
        }
    }
    /** Whether a channel's subscribers comment under its posts (iOS talks, «Allow Comments»). */
    fun talks(key: String) = state(key)?.let { it.kind == CHANNEL && it.talks } == true
    /** THE OWNER LETS ITS SUBSCRIBERS COMMENT OR STOPS IT (iOS setTalks): every subscriber hears it by the invitation's word again. */
    fun setTalks(key: String, on: Boolean) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.kind != CHANNEL || g.talks == on) return
            g.talks = on
            act(g, if (on) "talkOn" else "talkOff")
            save()
            tellAll(g, null)
            Log.i("Montana", "channel_comments " + (if (on) "on" else "off"))
        }
    }
    /** WHETHER THE OWNER'S NEXT POSTS GO WITHOUT SOUND (iOS silent, the bell in a channel's field): its owner's choice, kept on its state. */
    fun silent(key: String): Boolean = synchronized(lock) { state(key)?.silent == true }
    fun setSilent(key: String, on: Boolean) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.kind != CHANNEL) return
            g.silent = on
            save()
            Log.i("Montana", "channel_silent " + (if (on) "on" else "off"))
        }
    }
    /** [I-14]: a comment's words, at most (iOS commentLimit). */
    const val COMMENT_LIMIT = 4096
    /**
     * [I-15] ONE COMMENT FROM ONE PERSON IN THIS MANY MILLISECONDS (iOS commentGap, the reference's slowmode): the owner takes one
     * comment from a seat in this span and refuses the rest; a subscriber's own phone keeps the same span and its field says what is
     * left. The owner's own comments wait for nobody.
     */
    private const val COMMENT_GAP = 30_000L
    private val commentAt = mutableMapOf<String, Long>()
    private val myCommentAt = mutableMapOf<String, Long>()
    /** The seconds this phone waits before its next comment in a channel: 0 when it may comment now (iOS commentWait). */
    fun commentWait(key: String): Int {
        val g = state(key) ?: return 0
        if (g.mine) return 0
        val last = synchronized(lock) { myCommentAt[g.id] } ?: return 0
        return maxOf(0L, (COMMENT_GAP - (System.currentTimeMillis() - last) + 999) / 1000).toInt()
    }
    /**
     * A COMMENT OF MINE UNDER A CHANNEL'S POST (iOS comment): kept here at once and carried as the channel's word -- a subscriber's
     * to the owner, the owner's to everyone. False -- not carried (comments off, no pipe, an empty or overlong word).
     */
    fun comment(text: String, post: String, key: String): Boolean {
        val words = text.trim()
        synchronized(lock) {
            val g = state(key) ?: return false
            if (g.kind != CHANNEL || !g.talks || g.left || !isLetterName(post) || words.isEmpty() ||
                words.codePointCount(0, words.length) > COMMENT_LIMIT || commentWait(key) != 0) return false
            if (!g.mine) myCommentAt[g.id] = System.currentTimeMillis()
            val me = clean(Prefs.userName, NAME_LIMIT)
            val id = Marks.mintMid()
            val w = JSONObject().put("t", "cm").put("g", g.id).put("id", id).put("s", g.me).put("tx", words).put("qm", post)   // COMPAT-GATED: older builds refuse «cm»
            if (me.isNotEmpty()) w.put("n", me)
            ChannelComments.put(ChannelComments.Comment(id, g.me, me, words, System.currentTimeMillis(), mine = true), post, key)
            return 0 < spread(w, g, null, own = false) || (g.mine && g.members.isEmpty())
        }
    }
    /**
     * A COMMENT ARRIVED (iOS commented): the owner takes one only under a post of its feed, from a subscriber it carries to, while
     * comments are on, and carries it on to everyone else; a subscriber takes the owner's word alone. Each comment once, by its name.
     */
    private fun commented(w: JSONObject, pipe: String): Verdict {
        val postHere = Book.chat(key(w.optString("g")))?.msgs?.any { it.mid == w.optString("qm") } == true   // read before the lock: the book keeps its own
        synchronized(lock) {
            val g = groups[w.optString("g")] ?: return Verdict.WAIT
            val id = w.optString("id"); val post = w.optString("qm"); val tx = w.optString("tx")
            if (g.kind != CHANNEL || g.left || !isLetterName(id) || !isLetterName(post) || tx.isEmpty() ||
                tx.codePointCount(0, tx.length) > COMMENT_LIMIT) return Verdict.REFUSED
            val from = SamePair.root(pipe)
            val key = key(g.id)
            val seat: String
            if (g.mine) {
                val m = g.members.firstOrNull { SamePair.root(it.pipe) == from }
                if (!g.talks || m == null || !postHere) return Verdict.REFUSED
                seat = m.seat
            } else {
                val s = w.optString("s")
                if (SamePair.root(g.owner) != from || !isSeat(s) || s == g.me) return Verdict.REFUSED
                seat = s
            }
            if (ChannelComments.has(id, post, key)) return Verdict.HELD   // a repeat: taken already
            if (g.mine) {
                val now = System.currentTimeMillis(); val pace = g.id + "/" + seat
                if (now - (commentAt[pace] ?: 0L) < COMMENT_GAP) {
                    Log.i("Montana", "channel_comment_paced one comment from a seat in thirty seconds -- refused")
                    return Verdict.REFUSED
                }
                commentAt[pace] = now
            }
            val name = clean(w.optString("n"), NAME_LIMIT)
            val born = Marks.birthMs(id) ?: System.currentTimeMillis()
            val fresh = ChannelComments.put(ChannelComments.Comment(id, seat, name, tx, born, mine = false), post, key)
            if (fresh && g.mine) {
                val on = JSONObject().put("t", "cm").put("g", g.id).put("id", id).put("s", seat).put("tx", tx).put("qm", post)
                if (name.isNotEmpty()) on.put("n", name)
                spread(on, g, seat, own = false)
            }
            return Verdict.HELD
        }
    }

    // ── a channel's views (the reference's eye under a post; ChannelViews) ──

    /**
     * A SUBSCRIBER'S SCREEN SHOWED POSTS (iOS tellSeen): their names go to the owner alone, silent, for its count. False -- no pipe to
     * the owner now, and the posts wait. It is a read receipt: with the person's «Read receipts» off the word never leaves.
     */
    fun tellSeen(letters: List<String>, key: String): Boolean {
        synchronized(lock) {
            if (!Prefs.bool("readReceiptsEnabled", true) || letters.isEmpty()) return false
            val g = state(key) ?: return false
            if (g.kind != CHANNEL || g.mine || g.left) return false
            val w = JSONObject().put("t", "vw").put("g", g.id).put("id", Marks.mintMid()).put("s", g.me)
                .put("tx", letters.take(VIEWS_BATCH).joinToString(","))   // COMPAT-GATED: an older owner refuses «vw»
            return carry(w, g.owner, "", own = false) != null
        }
    }
    /** THE OWNER TELLS THE NUMBERS THAT MOVED (iOS tellCounts): one silent word to every subscriber. */
    fun tellCounts(numbers: Map<String, Int>, key: String) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (g.kind != CHANNEL || !g.mine || g.left || numbers.isEmpty()) return
            val w = JSONObject().put("t", "vc").put("g", g.id).put("id", Marks.mintMid()).put("s", OWNER_SEAT)
                .put("tx", JSONObject(numbers).toString())   // COMPAT-GATED: an older subscriber refuses «vc»
            for (m in g.members) carry(JSONObject(w.toString()), m.pipe, "", own = false)
        }
    }
    /** A subscriber's posts shown, at the owner's phone (iOS seen): the seat is the pipe's, and only posts of this channel's feed count. */
    private fun seen(w: JSONObject, pipe: String): Verdict {
        val posts = Book.chat(key(w.optString("g")))?.msgs?.toList()?.filter { !isEvent(it.text) }?.map { it.mid }?.toSet()
            ?: emptySet()   // read before the lock: the book keeps its own; the owner's posts and its administrators'
        val seat: String
        val k: String
        val letters: List<String>
        synchronized(lock) {
            val g = groups[w.optString("g")] ?: return Verdict.WAIT
            val from = SamePair.root(pipe)
            val tx = w.optString("tx")
            val m = g.members.firstOrNull { SamePair.root(it.pipe) == from }
            if (!g.mine || g.kind != CHANNEL || g.left || tx.isEmpty() || 16_384 < tx.toByteArray().size || m == null) return Verdict.REFUSED
            seat = m.seat
            k = key(g.id)
            letters = tx.split(",").take(VIEWS_BATCH).filter { isLetterName(it) && it in posts }
        }
        ChannelViews.saw(letters, seat, k)
        return Verdict.HELD
    }
    /** The owner's numbers, at a subscriber's phone (iOS counted): from the owner's pipe alone. */
    private fun counted(w: JSONObject, pipe: String): Verdict {
        val k: String
        val numbers = mutableMapOf<String, Int>()
        synchronized(lock) {
            val g = groups[w.optString("g")] ?: return Verdict.WAIT
            val tx = w.optString("tx")
            if (g.mine || g.kind != CHANNEL || g.left || SamePair.root(g.owner) != SamePair.root(pipe) ||
                tx.isEmpty() || 16_384 < tx.toByteArray().size) return Verdict.REFUSED
            val o = runCatching { JSONObject(tx) }.getOrNull() ?: return Verdict.REFUSED
            for (l in o.keys()) { val n = o.opt(l); if (isLetterName(l) && n is Number) numbers[l] = n.toInt() }
            k = key(g.id)
        }
        ChannelViews.told(numbers, k)
        return Verdict.HELD
    }
    /** THE OWNER CHOOSES THE REACTIONS ITS CHANNEL TAKES (iOS setReactionList, Some Reactions), or every one (null). */
    fun setReactionList(key: String, list: List<String>?) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.kind != CHANNEL) return
            val next = reactionList(list)
            if (next == g.reactList) return
            g.reactList = next
            act(g, if (next == null) "reactOn" else "reactSome")
            save()
            tellAll(g, null)
            Log.i("Montana", "channel_reactions list=${next?.size ?: 0}")
        }
    }
    /** The name a signed channel's post wears in its stamp (iOS signature): its writer's -- my own on my posts; null where the channel does not sign. */
    fun signature(c: Context, m: Msg, key: String): String? {
        if (!signsOn(key)) return null
        if (m.mine) return myName(c).takeIf { it != c.getString(R.string.gr_member) }
        if (m.from?.startsWith(SPEAKER_HEAD) != true) return null
        return speakerName(c, m.from).takeIf { it != c.getString(R.string.gr_member) }
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
            if (!adminCan(g, g.me, BAN) || seat == OWNER_SEAT || seat == g.me || seat in g.admins) return
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
        if (!adminCan(g, asker.seat, BAN) || target == asker.seat || target in g.admins || g.members.none { it.seat == target }) return Verdict.REFUSED
        remove(key(g.id), target, by = asker.seat)
        return Verdict.HELD
    }

    // ── chat history for new members (the checklist's G.4, the reference's «Chat History For New Members»; iOS 7ffa3211) ──

    /** The letters of the past one added may read (iOS historyLimit; I-14: bounded, as the reference's own first page is). */
    private const val HISTORY_LIMIT = 50
    /** The group's past is shown to new members (iOS state.history). */
    fun history(key: String): Boolean = state(key)?.history == true
    /** THE OWNER SHOWS OR HIDES THE GROUP'S PAST TO NEW MEMBERS (iOS setHistory): the owner's phone alone keeps it, nothing rides the wire. */
    fun setHistory(key: String, visible: Boolean) = synchronized(lock) {
        val g = state(key) ?: return@synchronized
        if (!g.mine || g.kind != GROUP || g.history == visible) return@synchronized
        g.history = visible
        save()
        Log.i("Montana", "group_history " + if (visible) "visible" else "hidden")
    }
    /**
     * THE PAST FOR ONE JUST ADDED (iOS add, G.4, the reference's «Visible»): the group's newest fifty letters of words, each under its
     * own one name and so at its own moment, by its own speaker, marked as the past (hs) -- silent on the wire, no banner where it
     * lands; a letter with a file stays, its sealed pieces were laid for the receivers of its day. Inside the lock, after the invitation.
     */
    private fun handPast(g: State, fresh: List<Member>) {
        if (g.kind != GROUP || !g.history || fresh.isEmpty()) return
        val head = SPEAKER_HEAD + g.id + "/"
        val me = clean(Prefs.userName, NAME_LIMIT)
        val past = Book.chat(key(g.id))?.msgs?.toList().orEmpty().filter { m ->
            m.file == null && !isEvent(m.text) && carries(m.text) && !m.text.startsWith(Marks.MEDIA)
        }
        var sent = 0
        for (p in past.takeLast(HISTORY_LIMIT)) {
            val seat = if (p.mine) g.me else p.from?.takeIf { it.startsWith(head) }?.removePrefix(head) ?: ""
            if (!isLetterName(p.mid) || !isSeat(seat) || fresh.any { it.seat == seat }) continue
            val name = if (seat == g.me) me else g.names[seat] ?: ""
            val w = JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", p.mid).put("s", seat)
                .put("tx", p.text).put("hs", 1)
            if (name.isNotEmpty()) w.put("n", name)
            p.qt?.takeIf { it.isNotEmpty() }?.let { w.put("qt", it) }
            for (m in fresh) carry(w, m.pipe, "", own = false)
            sent++
        }
        Log.i("Montana", "group_history_tx letters=" + sent + " to=" + fresh.size)
    }

    // ── permissions and slow mode (the checklist's G.5, the reference's «Permissions» and «Slowmode»; iOS d61b38fe) ──

    /** WHAT MEMBERS OF A GROUP MAY DO (iOS MTGroupRight), each a short word on the wire: the owner names the ones its members lack. */
    const val M_TEXT = "tx"; const val M_PHOTOS = "ph"; const val M_VIDEOS = "vd"; const val M_STICKERS = "gf"; const val M_MUSIC = "mu"
    const val M_FILES = "fl"; const val M_VOICE = "vo"; const val M_ROUNDS = "rv"; const val M_LINKS = "ln"; const val M_REACTIONS = "re"
    const val M_PIN = "pn"; const val M_INFO = "ci"
    /** «Send Media» opened, in the reference's order (iOS MTGroupRight.media). */
    val MEDIA_RIGHTS = listOf(M_PHOTOS, M_VIDEOS, M_STICKERS, M_MUSIC, M_FILES, M_VOICE, M_ROUNDS, M_LINKS, M_REACTIONS)
    /** The rows of the permissions page (iOS MTGroupRight.shown): words, the media, the pin, the group's info. */
    val MEMBER_RIGHTS = listOf(M_TEXT) + MEDIA_RIGHTS + listOf(M_PIN, M_INFO)
    /** The slow mode's steps, as the reference's slider has them (iOS MTGroupPermissionsPage.steps): off, 10 s, 30 s, 1, 5, 15 min, an hour. */
    val SLOW_STEPS = listOf(0, 10, 30, 60, 300, 900, 3600)

    /**
     * THE RIGHTS A LETTER ASKS OF ITS SPEAKER (iOS rightsAsked): a reaction, a pin, a sticker, each kind of media by its manifest,
     * and words -- with «Embed Links» where they carry a link. A deletion of one's own asks none.
     */
    private fun rightsAsked(words: String): List<String> = when {
        words.startsWith(Marks.REACTION) -> listOf(M_REACTIONS)
        words.startsWith(PIN_MARK) -> listOf(M_PIN)
        words.startsWith(Marks.DELETE) -> emptyList()
        words.startsWith(Marks.STICKER) -> listOf(M_STICKERS)
        words.startsWith(Marks.MEDIA) -> {
            val o = runCatching { JSONObject(words.removePrefix(Marks.MEDIA)) }.getOrNull() ?: JSONObject()
            val name = o.optString("n").ifEmpty { o.optString("name") }
            listOf(mediaRight(o.optString("k"), o.optBoolean("r"), name)) + (if (links(o.optString("cap"))) listOf(M_LINKS) else emptyList())
        }
        else -> {
            val text = if (words.startsWith(Marks.EDIT)) runCatching { JSONObject(words.removePrefix(Marks.EDIT)).optString("tx") }.getOrDefault("") else words
            if (links(text)) listOf(M_TEXT, M_LINKS) else listOf(M_TEXT)
        }
    }
    private fun links(text: String) = text.isNotEmpty() && LinkPreview.webURLs(text).isNotEmpty()
    /** The right one media letter asks (iOS mediaRight), by its kind, its roundness and its file's name. */
    private fun mediaRight(kind: String, round: Boolean, name: String): String = when (kind) {
        "img" -> if (name == Stickers.DOC_NAME) M_STICKERS else M_PHOTOS
        "vid", "video" -> if (round) M_ROUNDS else M_VIDEOS
        "aud" -> M_VOICE
        else -> if (Waveform.isAudio(name)) M_MUSIC else if (name.lowercase().endsWith(".gif")) M_STICKERS else M_FILES
    }
    /** WHETHER A SEAT MAY SAY THESE WORDS INTO THE GROUP (iOS allows): the owner and its administrators always; a member by the rights left them. */
    private fun allows(g: State, seat: String, words: String): Boolean {
        if (seat == OWNER_SEAT || seat in g.admins) return true
        return rightsAsked(words).none { it in g.barred }
    }
    /** The moment the owner last took a letter of each member while slow mode stands, by group and seat (iOS slowAt; I-15: time, not money). */
    private val slowAt = HashMap<String, Long>()
    /**
     * A MEMBER'S LETTER IN SLOW MODE (iOS paceSlow): taken once an interval; a letter before it is held unanswered and knocks again --
     * delayed, never lost. The owner and its administrators are not paced, as in the reference.
     */
    private fun paceSlow(g: State, seat: String): Boolean {
        if (g.slow <= 0 || seat == OWNER_SEAT || seat in g.admins) return true
        val k = g.id + "/" + seat
        val now = System.currentTimeMillis()
        if (now - (slowAt[k] ?: 0L) < g.slow * 1000L) return false
        slowAt[k] = now
        return true
    }
    /** The group whose permissions bind this phone (iOS refusal's guard): a group proper, not its owner's, not an administrator's, not left. */
    private fun bound(key: String): State? = state(key)?.takeIf { it.kind == GROUP && !it.mine && !it.left && it.me !in it.admins }
    /** The sentence a right says when this phone tries what the group took (iOS MTGroupRight.refusal). */
    private fun refusalOf(c: Context, r: String): String = c.getString(when (r) {
        M_TEXT -> R.string.gn_text; M_PHOTOS -> R.string.gn_photos; M_VIDEOS -> R.string.gn_videos; M_STICKERS -> R.string.gn_stickers
        M_MUSIC -> R.string.gn_music; M_FILES -> R.string.gn_files; M_VOICE -> R.string.gn_voice; M_ROUNDS -> R.string.gn_rounds
        M_LINKS -> R.string.gn_links; M_REACTIONS -> R.string.gn_reactions
        else -> R.string.gn_fixed
    })
    /**
     * WHY THIS PHONE MAY NOT SEND THESE WORDS NOW (iOS refusal), in the reference's sentence -- a right the group took, or slow mode's
     * wait (`letter`: a letter, not an answer); null when it may. Asked before the field empties, so nothing typed is lost to it.
     */
    fun refusal(c: Context, key: String, words: String, letter: Boolean): String? {
        val g = synchronized(lock) { bound(key) } ?: return null
        rightsAsked(words).firstOrNull { it in g.barred }?.let { return refusalOf(c, it) }
        return if (letter) slowRefusal(c, g, key) else null
    }
    /** The same for a media letter not yet made (iOS mediaRefusal), by its kind, roundness, file name and caption. */
    fun mediaRefusal(c: Context, key: String, kind: String, round: Boolean, name: String, caption: String): String? {
        val g = synchronized(lock) { bound(key) } ?: return null
        val asked = listOf(mediaRight(kind, round, name)) + (if (links(caption)) listOf(M_LINKS) else emptyList())
        asked.firstOrNull { it in g.barred }?.let { return refusalOf(c, it) }
        return slowRefusal(c, g, key)
    }
    /** The slow mode's wait from this phone's last letter in the group (iOS slowRefusal): «m:ss» left, as the reference says it. */
    private fun slowRefusal(c: Context, g: State, key: String): String? {
        if (g.slow <= 0) return null
        val last = Book.chat(key)?.msgs?.toList()?.filter { it.mine && !isEvent(it.text) }?.maxOfOrNull { it.at } ?: return null
        val left = g.slow * 1000L - (System.currentTimeMillis() - last)
        if (left <= 0) return null
        val s = ((left + 999) / 1000).toInt()
        val span = if (3600 <= s) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
            else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
        return c.getString(R.string.gn_slow, span)
    }
    /**
     * WHERE A REFUSAL IS SAID (iOS groupRefusal's alert): the open chat hangs its own here; with no chat open, a toast. A refusal
     * may come from any thread -- the saying is on the main one.
     */
    @Volatile var refused: ((String) -> Unit)? = null
    fun tellRefusal(why: String) {
        Log.i("Montana", "group_refusal said")
        val say = refused
        MainThread.post { if (say != null) say(why) else android.widget.Toast.makeText(Book.ctx, why, android.widget.Toast.LENGTH_LONG).show() }
    }
    /** Whether the group took every way of writing from this phone (iOS mutes): words and every kind of media -- the field gives way. */
    fun mutes(key: String): Boolean {
        val g = synchronized(lock) { bound(key) } ?: return false
        return M_TEXT in g.barred && MEDIA_RIGHTS.filter { it != M_REACTIONS }.all { it in g.barred }
    }
    /** The field stands (iOS composeSlot): the group takes letters from this phone, and left it some way of writing. */
    fun fieldOpen(key: String) = canWrite(key) && !mutes(key)
    /** Whether this phone may change the group's name, words and face (iOS mayChangeInfo): its owner, an administrator with the right, or a member the group lets. */
    fun mayChangeInfo(key: String): Boolean {
        val g = state(key) ?: return false
        if (g.left) return false
        if (g.kind != GROUP) return adminCan(g, g.me, INFO)   // a channel's owner, or its administrator with «Change Channel Info»
        return adminCan(g, g.me, INFO) || M_INFO !in g.barred
    }
    /** What the members may not do, and the slow mode's seconds (the permissions page reads them). */
    fun barred(key: String): Set<String> = state(key)?.barred?.toSet() ?: emptySet()
    fun slow(key: String): Int = state(key)?.slow ?: 0
    /** «n/m», the rights left of all of them, as the editor's row counts them (iOS MTGroupPermissionsPage.count). */
    fun rightsLeft(key: String): String { val b = barred(key); return MEMBER_RIGHTS.count { it !in b }.toString() + "/" + MEMBER_RIGHTS.size }

    /** THE OWNER SETS WHAT THE MEMBERS MAY DO AND THE SLOW MODE (iOS setRights): every member hears it by the invitation's word again. */
    fun setRights(key: String, barred: Set<String>, slow: Int) {
        synchronized(lock) {
            val g = state(key) ?: return
            if (!g.mine || g.left || g.kind != GROUP) return
            val list = MEMBER_RIGHTS.filter { it in barred }
            val s = slow.coerceIn(0, 3600)
            if (list == g.barred && s == g.slow) return
            g.barred.clear(); g.barred.addAll(list)
            g.slow = s
            save()
            tellAll(g, null)
            Log.i("Montana", "group_rights barred=" + list.size + " slow=" + s)
        }
    }
    /** A MEMBER'S RENEWAL ASKED OF THE OWNER (iOS askRenew, their «ci»): the new name, words and face, small, as an invitation carries them. */
    private fun askRenew(g: State, title: String, face: ByteArray?, about: String?) {
        if (!mayChangeInfo(key(g.id))) return
        val w = JSONObject().put("t", "ci").put("g", g.id).put("ti", title).put("id", Marks.mintMid()).put("s", g.me)
        if (about != null) w.put("ds", about)
        face?.let { thumb(it) }?.let { w.put("fc", it) }
        carry(w, g.owner, "", own = false)
        Log.i("Montana", "group_renewed asked of the owner face=" + (if (w.has("fc")) 1 else 0))
    }
    /** A MEMBER'S RENEWAL AT THE OWNER'S PHONE (iOS infoAsked): the seat is the pipe's; the right is the group's «Change Group Info» or the admin's. */
    private fun infoAsked(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.REFUSED
        if (!g.mine || g.left) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val m = g.members.firstOrNull { SamePair.root(it.pipe) == from } ?: return Verdict.REFUSED
        if (!adminCan(g, m.seat, INFO) && !(g.kind == GROUP && M_INFO !in g.barred)) return Verdict.REFUSED
        val title = clean(w.optString("ti"), TITLE_LIMIT).ifEmpty { g.title }
        renew(key(g.id), title, face(w.optString("fc").ifEmpty { null }), if (w.has("ds")) w.optString("ds") else null, by = m.seat)
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
            "joined" -> if (mine) c.getString(R.string.ge_joined_me) else c.getString(R.string.ge_joined, who)
            "named" -> if (mine) c.getString(R.string.ge_named_me, t) else c.getString(R.string.ge_named, who, t)
            "face" -> if (mine) c.getString(R.string.ge_face_me) else c.getString(R.string.ge_face, who)
            "pinned" -> if (mine) c.getString(R.string.ge_pinned_me, t) else c.getString(R.string.ge_pinned, who, t)
            // a group's room (iOS eventWords «call», «ended»): begun by whom, ended with how long it lived
            "call" -> when {
                e.optString("k") == "live" -> if (mine) c.getString(R.string.ge_live_me) else c.getString(R.string.ge_live, who)
                e.optInt("v") == 1 -> if (mine) c.getString(R.string.ge_video_me) else c.getString(R.string.ge_video, who)
                else -> if (mine) c.getString(R.string.ge_voice_me) else c.getString(R.string.ge_voice, who)
            }
            "ended" -> c.getString(when {
                e.optString("k") == "live" -> R.string.ge_live_ended
                e.optInt("v") == 1 -> R.string.ge_video_ended
                else -> R.string.ge_voice_ended
            }, span(e.optInt("d")))
            else -> null
        }
    }
    /** A room's length as the reference's row says it: minutes and seconds, hours when it ran past one (iOS span). */
    private fun span(seconds: Int): String {
        val s = maxOf(0, seconds)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
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
            Rooms.groupGone(g.id)   // its room goes with the group (iOS groupGone)
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
            Rooms.groupGone(g.id)
        }
        Book.edit(key) {}   // the open chat trades its field for the note at once
    }

    private fun mintId() = UUID.randomUUID().toString().replace("-", "").lowercase()
    private fun mintSeat() = mintId().take(8)
    /** The face an invitation carries (iOS thumb): small, so the word stays a letter and not a file. */
    private fun thumb(face: ByteArray, sideOut: Int = FACE_SIDE): String? {
        val b = BitmapFactory.decodeByteArray(face, 0, face.size) ?: return null
        val side = minOf(b.width, b.height)
        val square = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, sideOut, sideOut, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).takeIf { it.length <= FACE_LIMIT }
    }

    /**
     * A GROUP'S FULL FACE (iOS layFullFace, 06c8eaf1, OZ 1 p.1, the author's word 20586: the group's picture was blurred when opened --
     * a member held the invitation's small face alone and drew it large): the invitation keeps the small face so the word stays a
     * letter; the picture goes up to the node once, sealed under a fresh key, named by its content, and the invitation hands the name
     * and the key over the pipe as «blob.key». The node holds it a week and reads nothing.
     */
    private fun layFullFace(face: ByteArray): String? {
        val b = BitmapFactory.decodeByteArray(face, 0, face.size) ?: return null
        val scale = minOf(FULL_FACE_SIDE.toFloat() / b.width, FULL_FACE_SIDE.toFloat() / b.height, 1f)   // iOS avatarResized: fit, never grow
        val fit = if (scale < 1f) Bitmap.createScaledBitmap(b, maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()), true) else b
        val full = ByteArrayOutputStream().also { fit.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        if (full.size > FULL_FACE_LIMIT) return null
        val key = MtBindings.nativeRandom(32) ?: return null
        val nonce = MtBindings.nativeRandom(12) ?: return null
        val sealed = MtBindings.nativeSealBlob(key, nonce, full) ?: return null
        val bid = Wire.hex(Wire.sha(sealed))
        Thread {
            val ok = runCatching { Wire.putBlob(bid, sealed, assumeAbsent = true) }.getOrDefault(false)
            Log.i("Montana", "group_face_up " + (if (ok) "ok" else "FAIL") + " bytes=" + full.size)
        }.start()
        return bid + "." + Wire.hex(key)
    }

    /**
     * A MEMBER WEARS THE GROUP'S FULL FACE (iOS wearFullFace, 06c8eaf1): asked of the node at once and twice more across half a minute
     * (the owner's put may still be on its way up); the small face stays until it comes, and a face given since leaves it unworn.
     */
    private fun wearFullFace(w: JSONObject) {
        val ff = w.optString("ff").ifEmpty { return }
        val tag = w.optString("fc").ifEmpty { return }.let(::tagOfFace)
        val id = w.optString("g")
        if (groups[id]?.faceFull == ff) return
        val part = ff.split(".")
        if (part.size != 2 || part[0].length != 64 || !part[0].all { it in "0123456789abcdefABCDEF" }) return
        if (part[1].length != 64 || !part[1].all { it in "0123456789abcdefABCDEF" }) return
        val key = ByteArray(32) { part[1].substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        Thread {
            for (pause in longArrayOf(0, 5, 25)) {
                if (pause != 0L) Thread.sleep(pause * 1000)
                val sealed = runCatching { Wire.getBlob(part[0]) }.getOrNull() ?: continue
                val full = MtBindings.nativeOpenBlob(key, sealed)
                if (full == null || full.size > FULL_FACE_LIMIT || BitmapFactory.decodeByteArray(full, 0, full.size) == null) {
                    Log.i("Montana", "group_face unreadable"); return@Thread
                }
                synchronized(lock) {
                    val g = groups[id]
                    if (g == null || g.faceTag != tag) { Log.i("Montana", "group_face a newer face stands"); return@Thread }
                    g.faceFull = ff
                    save()
                    stand(g, full, revive = false)
                }
                Log.i("Montana", "group_face full bytes=" + full.size)
                return@Thread
            }
            Log.i("Montana", "group_face absent")
        }.start()
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
    /** The row of mine a copy carries, still riding (iOS bidsNamed of the queued letter): the cargo check reads its media there. */
    fun copyRow(name: String): Msg? {
        synchronized(lock) {
            val c = (if (ready()) copies[name] else null)?.takeIf { it.own && it.letter.isNotEmpty() } ?: return null
            return Book.chat(key(c.group))?.msgs?.find { it.mine && it.mid == c.letter }
        }
    }
    /**
     * The copy's pipe was gone before it left (Post.flush): my row says so (iOS copySettled, red with no pipe);
     * so does its cargo found gone (Pipes.cargoReported, iOS copyRefused).
     */
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

    /** Whether a row of mine still has a copy in the queue (iOS rides, for paintOrphanSending): a clock with nothing behind it is a lie. */
    fun rides(letter: String, queued: Set<String>): Boolean = synchronized(lock) {
        if (!ready()) return true   // an unread store says nothing is lost: the clock waits for the store
        copies.any { it.value.own && it.value.letter == letter && it.key in queued }
    }
    /**
     * A ROW OF MINE STANDS WHERE ITS COPIES SAY, ASKED AGAIN AT THE COLD SWEEP (iOS answersFor, 2315; the author's words 27.10.2026
     * 09:15 and 09:44 UTC: after a relaunch a channel's files wore red and «retry» though they stood in the channel). A group's
     * media rides on its copies under names of their own, never the letter's; true when the row is the group's to answer -- a copy
     * of mine names the letter, or the store is not read yet -- and the row is moved by follow, as its receipts would move it.
     */
    fun answersFor(letter: String): Boolean = synchronized(lock) {
        if (!ready()) return true
        val c = copies.values.firstOrNull { it.own && it.letter == letter } ?: return false
        follow(c)
        true
    }
    /**
     * A HAND ASKED AGAIN (iOS MTGroup.resend, the red mark's retry): the row goes back to the clock; every copy still in the queue
     * keeps riding, one the queue let go rides again to its receiver anew; a letter with no copy left leaves anew to everyone.
     */
    fun resend(key: String, m: Msg) {
        synchronized(lock) {
            val g = state(key)?.takeIf { !it.left && posts(it, it.me) } ?: return
            if (!m.mine) return
            var back = false
            Book.edit(key) { c -> c.msgs.find { it.mid == m.mid && it.mine }?.let { back = it.restart() } }
            if (!back) return
            val w = word("say", g).put("id", m.mid).put("s", g.me).put("tx", m.text)
            if (!m.qt.isNullOrEmpty()) w.put("qt", m.qt)
            if (!m.qm.isNullOrEmpty()) w.put("qm", m.qm)
            val queued = Post.queued()
            val mine = copies.filter { it.value.own && it.value.group == g.id && it.value.letter == m.mid }
            if (mine.isEmpty()) {
                if (spread(w, g, null, own = true) == 0) Book.edit(key) { c -> c.msgs.find { it.mid == m.mid }?.advance(if (alone(g)) 2 else -1) }
                Log.i("Montana", "group_resend mid=" + m.mid.take(14) + " anew")
                return
            }
            val gone = mine.filter { it.key !in queued }
            gone.keys.forEach { copies.remove(it) }
            val again = gone.values.mapNotNull { carry(w, it.to, m.mid, own = true) }
            val left = mine.size - gone.size + again.size
            copies.values.filter { it.own && it.group == g.id && it.letter == m.mid }.forEach { it.of = left }
            saveCopies()
            if (left == 0) Book.edit(key) { c -> c.msgs.find { it.mid == m.mid }?.advance(-1) }
            Log.i("Montana", "group_resend mid=" + m.mid.take(14) + " copies=" + left + " again=" + again.size)
        }
        Thread { Post.flush() }.start()
    }

    // ── arrival ──

    /**
     * A GROUP'S WORD ARRIVED ON A PIPE (iOS MTGroup.handle, Post.place before any row): never a row of the pipe. Held or refused,
     * the copy is receipted by its own name on the pipe it came by; a word that must wait is not, and its copy knocks again.
     */
    fun handle(pipe: String, sid: String, text: String) {
        val w = runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull()
        // THE PERSON'S OWN WORD, HEARD ON A PIPE ITS DEVICES SHARE (iOS MTGroup.handle, 566c6dcc; the author's words 18.10.2026
        // 10:3x UTC: «check what the errors of sending into channels on T1 are, and fix everything»): a group's word rides too big
        // for the letter's eighth field more often than not (an invitation with the group's face, a channel's post), so the seed's
        // other device heard it as the member's and refused it without end. A word this person signed is done here as the hand did
        // it there (handleOwn) -- never judged as a stranger's, never answered: the device that spoke carries it.
        if (w != null && isLabel(w.optString("g")) && ownWord(w, pipe)) { handleOwn(text, pipe); return }
        val verdict = if (w != null && isLabel(w.optString("g"))) synchronized(lock) { take(w, pipe, sid) }
            else Verdict.REFUSED.also { Log.i("Montana", "group_refused unreadable from=${pipe.take(10)}") }
        if (verdict != Verdict.WAIT && verdict != Verdict.INSTANT) Post.receiptFor(pipe, sid)
        val t = w?.optString("t").orEmpty()
        if (verdict == Verdict.WAIT && t != "inv") keepEarly(w!!.optString("g"), text, pipe, sid)
        if (verdict == Verdict.HELD && t == "inv") hearEarly(w!!.optString("g"))
    }

    /**
     * [I-14] A GROUP'S WORD THAT OUTRAN ITS INVITATION (iOS ed467ee5, OZ 1 p.15.4, the author's word 20730): a channel's first post read
     * 0.15 s before the invitation that stood the channel waited unanswered, and its sender knocked again only on the rare beat, five
     * minutes on. Such a word is kept here, in memory and bounded, and heard again the moment its invitation is held -- its receipt
     * leaves then. A relaunch forgets them: the copy still knocks again, as before.
     */
    private const val EARLY_LIMIT = 64
    private class Early(val text: String, val pipe: String, val sid: String)
    private val early = HashMap<String, MutableList<Early>>()
    /** Keeps a word whose group has not stood on this phone yet: a repeat of the same copy once, past the bound none, over an unread store none. */
    private fun keepEarly(g: String, text: String, pipe: String, sid: String) = synchronized(lock) {
        if (!ready() || groups[g] != null) return@synchronized
        if (EARLY_LIMIT <= early.values.sumOf { it.size } || early[g]?.any { it.text == text && it.pipe == pipe } == true) return@synchronized
        early.getOrPut(g) { mutableListOf() }.add(Early(text, pipe, sid))
        Log.i("Montana", "group_early kept from=" + pipe.take(10))
    }
    /** The invitation stood the group: the words that outran it are heard now, each answered by its own receipt (handle). */
    private fun hearEarly(g: String) {
        val kept = synchronized(lock) { if (groups[g] == null) null else early.remove(g) } ?: return
        Log.i("Montana", "group_early heard n=" + kept.size)
        for (e in kept) handle(e.pipe, e.sid, e.text)
    }

    /** The fields the person's tag is made over (iOS MTGroup.tagText): the kind, the letter's name, the seats, the title and the
     *  words -- what a member could alter to pass another's word off. */
    private fun tagText(w: JSONObject) =
        listOf("t", "id", "me", "s", "ti", "tx").joinToString("\u0000") { w.optString(it) }
    /** THE WORD'S AUTHOR IS THE PERSON (iOS MTGroup.ownTag, MTPipeKey.authorTag): sixteen bytes only this person's devices make,
     *  under the letter author's key over the pipe's item key of the day and the word's own fields, as a letter's eighth field.
     *  No author key yet: the word rides unsigned, as from an older build. */
    private fun ownTag(w: JSONObject, secret: ByteArray): String? =
        LetterAuthor.key?.let { Wire.authorTag(it, Wire.itemKey(secret), w.getString("g"), tagText(w)) }
    /** Whether a word heard on a pipe was signed by this person -- on another of its devices (iOS MTGroup.ownWord). */
    private fun ownWord(w: JSONObject, pipe: String): Boolean {
        val tag = w.optString("au").ifEmpty { return false }
        val secret = Book.secret(pipe) ?: return false
        return Wire.authorHolds(tag, LetterAuthor.key, secret, w.getString("g"), tagText(w))
    }

    /**
     * THE GROUPS THIS DEVICE HELD BEFORE THE MIRROR, ONCE A DEVICE (iOS MTGroup.mirrorHeld, 349cc053): each rides to the seed's other
     * devices as the words that made it here -- the owner's invitation of every member, a member's invitation from its owner -- and
     * with it the person's own letters of the box's term, so a group born on one device before the mirror stands on every device of
     * the seed by the same replay a new one takes (handleOwn). A media letter's cargo is not given.
     */
    fun mirrorHeld() {
        val flag = "mt.group.mirrored.1"
        if (Prefs.bool(flag, false) || MontanaSeed.twin == null) return
        val notes = ArrayList<Triple<String, String, Boolean>>()   // pipe, the word, own
        val heads = ArrayList<Pair<String, JSONObject>>()   // a group's carrier and the head of its letters
        var letters = 0
        synchronized(lock) {
            if (!ready()) return
            for (id in groups.keys.sorted()) {
                val g = groups[id] ?: continue
                if (g.left) continue
                fun invitation(seat: String): JSONObject {
                    val w = JSONObject().put("t", "inv").put("g", g.id).put("k", g.kind).put("ti", g.title).put("c", g.count).put("me", seat)
                    if (g.about.isNotEmpty()) w.put("ds", g.about)
                    if (!g.mine) g.names[OWNER_SEAT]?.takeIf { it.isNotEmpty() }?.let { w.put("n", it) }
                    if (g.admins.isNotEmpty()) w.put("ad", JSONArray(g.admins))
                    if (g.barred.isNotEmpty()) w.put("bn", JSONArray(g.barred))
                    if (0 < g.slow) w.put("sw", g.slow)
                    if (g.taken.isNotEmpty()) w.put("ax", JSONObject().apply { g.taken.forEach { (k, v) -> put(k, JSONArray(v)) } })
                    if (g.ranks.isNotEmpty()) w.put("rk", JSONObject(g.ranks.toMap()))
                    if (g.kind == CHANNEL && g.signs) w.put("sg", 1)
                    if (g.kind == CHANNEL && !g.reacts) w.put("rx", 0)
                    if (g.kind == CHANNEL && g.talks) w.put("dc", 1)
                    if (g.kind == CHANNEL) g.reactList?.let { w.put("rl", JSONArray(it)) }
                    (if (g.mine) roster(g) else g.seats)?.let { w.put("ps", JSONArray(it)) }   // the seats (iOS 07d5fc70)
                    if (g.kind == GROUP) g.round?.let { w.put("rr", it) }   // and the owner's round (iOS nameSeats, d28805b6)
                    return w
                }
                if (g.mine) g.members.forEach { m -> notes.add(Triple(m.pipe, MARK + invitation(m.seat), true)) }
                else notes.add(Triple(g.owner, MARK + invitation(g.me), false))
                // the person's own letters of the box's term, by their names: the other device lands each once (replayLetter)
                val carrier = if (g.mine) g.members.firstOrNull()?.pipe.orEmpty() else g.owner
                if (carrier.isNotEmpty()) heads.add(carrier to JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("s", g.me))
            }
        }
        val edge = System.currentTimeMillis() - 7 * 86_400_000L
        for ((carrier, head) in heads) for (r in Book.chat(key(head.getString("g")))?.msgs.orEmpty().takeLast(300)) {
            if (!r.mine || r.at <= edge || r.file != null || Marks.isService(r.text) || r.text.isBlank() || Marks.birthMs(r.mid) == null) continue
            val w = JSONObject(head.toString()).put("id", r.mid).put("tx", r.text)
            r.qt?.takeIf { it.isNotEmpty() }?.let { w.put("qt", it) }
            notes.add(Triple(carrier, MARK + w, true))
            letters++
        }
        Prefs.setBool(flag, true)
        for ((pipe, x, own) in notes) if (pipe.isNotEmpty()) Twin.note(pipe, Marks.mintMid(), x, own = own)
        Log.i("Montana", "group_mirrored words=" + notes.size + " letters=" + letters)
    }

    /**
     * THE SEED'S OWN GROUP WORD FROM ANOTHER OF ITS DEVICES (iOS MTGroup.handleOwn and replay, 349cc053; the author's words
     * 18.10.2026 10:3x-10:4x MSK: «in my own groups and channels, what I posted from the phone is not seen»): a word my other device
     * said in a group, signed by these words (Wire.authorHolds), stood applied to nobody here. What that device said is what this
     * person said, so it is done here as the hand did it there: an invitation stands the group and its member, a letter its row, a
     * taking out and a leaving their state -- and nothing is said to anyone again: the device that spoke already carried it.
     */
    fun handleOwn(text: String, pipe: String) {
        val w = runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull() ?: return
        if (!isLabel(w.optString("g"))) return
        val t = w.optString("t")
        val done = synchronized(lock) {
            if (!ready()) false else when (t) {
                "inv" -> replayInvitation(w, pipe)
                "say" -> replayLetter(w)
                "out" -> {
                    val gone = SamePair.root(pipe)
                    val g = groups[w.getString("g")]
                    val m = g?.takeIf { it.mine }?.members?.firstOrNull { SamePair.root(it.pipe) == gone }
                    if (g == null || m == null) false else {
                        g.banned.removeAll { it.seat == m.seat }; g.banned.add(m)
                        while (g.banned.size > PEOPLE_LIMIT) g.banned.removeAt(0)
                        g.members.removeAll { it.seat == m.seat }
                        g.admins.remove(m.seat)
                        g.cards.remove(m.seat)
                        g.count = g.members.size + 1
                        save()
                        lay(g, event("removed", OWNER_SEAT, listOf(m.seat)))
                        true
                    }
                }
                "bye" -> {
                    val g = groups[w.getString("g")]
                    if (g == null || g.mine || g.left) false else { g.left = true; save(); true }
                }
                else -> { Log.d("Montana", "group_own kind=${t.take(8)} -- the other device's word, applied to nobody"); return }
            }
        }
        if (done && t == "bye") Book.edit(key(w.getString("g"))) {}   // the open chat trades its field for the note at once
        Log.i("Montana", "group_own kind=$t group=${w.optString("g").take(6)} applied=${if (done) 1 else 0}")
    }

    /** My other device invited a person into a group of these words -- its birth, an addition, a renewal: the group stands here
     *  as its owner's, with that person at the seat the word gave them, by the pipe the word rode (iOS replayInvitation). */
    private fun replayInvitation(w: JSONObject, pipe: String): Boolean {
        val kind = w.optString("k")
        val title = clean(w.optString("ti"), TITLE_LIMIT)
        val seat = w.optString("me")
        if ((kind != GROUP && kind != CHANNEL) || title.isEmpty() || !isSeat(seat) || seat == OWNER_SEAT) return false
        val member = SamePair.root(pipe)
        val id = w.getString("g")
        val fresh = groups[id] == null
        val g = groups[id] ?: State(id, kind, title, "", "", OWNER_SEAT, mutableListOf(), 2, mutableMapOf(), System.currentTimeMillis())
        if (!g.mine) { Log.i("Montana", "group_refused an own invitation of a group this device holds as a member"); return false }
        val renamed = !fresh && g.title != title
        g.title = title
        g.about = clean(w.optString("ds"), ABOUT_LIMIT)
        g.members.removeAll { it.seat == seat || SamePair.root(it.pipe) == member }
        g.members.add(Member(seat, member))
        g.banned.removeAll { SamePair.root(it.pipe) == member }
        g.count = maxOf(g.members.size + 1, w.optInt("c", 2).coerceAtMost(PEOPLE_LIMIT + 1))
        g.admins.clear(); g.admins.addAll(admins(w))
        takeRights(w, g)
        takeAdmins(w, g)
        val fc = w.optString("fc").ifEmpty { null }
        val tag = fc?.let(::tagOfFace)
        val newFace = tag != null && tag != g.faceTag
        if (newFace) g.faceTag = tag
        groups[id] = g
        save()
        stand(g, if (newFace) face(fc) else null, revive = fresh)   // the sibling's birth of a group, never its renewal
        wearFullFace(w)
        if (fresh) lay(g, event("made", OWNER_SEAT, t = title))
        if (renamed) lay(g, event("named", OWNER_SEAT, t = title))
        return true
    }

    /** A letter my other device wrote into a group of these words, or carried there for a member: its row stands here under its one
     *  name, as mine when its seat is this person's, never a second time and never over its grave (iOS replayLetter). */
    private fun replayLetter(w: JSONObject): Boolean {
        val g = groups[w.optString("g")] ?: return false
        val id = w.optString("id")
        val words = w.optString("tx")
        val seat = w.optString("s")
        if (g.left || !isLetterName(id) || !carries(words) || !isSeat(seat)) return false
        val key = key(g.id)
        if (id in gone || Book.isBuried(key, id) || Book.chat(key)?.msgs?.any { it.mid == id } == true) return false
        val mine = seat == g.me
        stand(g, null, revive = true)   // a letter
        val at = Marks.birthMs(id) ?: System.currentTimeMillis()
        val quote = w.optString("qt").ifEmpty { null }
        val quoted = w.optString("qm").removePrefix("mid:").ifEmpty { null }
        var landed = false
        Book.edit(key) { c ->
            if (c.msgs.none { it.mid == id }) {
                c.msgs.add(Msg(id, words, mine, at, state = if (mine) 1 else 0, qt = quote, qm = quoted, from = if (mine) null else speaker(g.id, seat)))
                c.msgs.sortBy { it.at }
                landed = true
            }
        }
        Log.i("Montana", "group_own mid=$id letter landed=${if (landed) 1 else 0} mine=${if (mine) 1 else 0}")
        if (landed && words.startsWith(Marks.MEDIA)) Media.fetch(Book.ctx, key, id, words, own = mine)
        return landed
    }

    private fun take(w: JSONObject, pipe: String, copy: String): Verdict {
        if (!ready()) return Verdict.WAIT   // the copy knocks again once the store opens
        return when (val t = w.optString("t")) {
            "inv" -> invited(w, pipe, copy)
            "say" -> said(w, pipe)
            "sig" -> answered(w, pipe)
            "out" -> dropped(w, pipe)
            "bye" -> parted(w, pipe)
            "ty" -> typed(w, pipe)   // COMPAT-GATED: a member typing, an instant word an older build buries (B.12)
            "rd" -> readWord(w, pipe)   // COMPAT-GATED: a reader's mark, an older build buries it (B.13)
            "ev" -> evented(w, pipe)   // COMPAT-GATED: the owner's event, a row in the feed (iOS stage R)
            "kk" -> kicked(w, pipe)   // COMPAT-GATED: an administrator's «take out», to the owner (iOS stage R.6)
            "ci" -> infoAsked(w, pipe)   // COMPAT-GATED: a member's renewal, to the owner (iOS G.5)
            "jn" -> joined(w, pipe)   // COMPAT-GATED: a person who opened the group's invite link, to the owner (iOS stage R.6)
            "sp" -> passported(w, pipe)   // COMPAT-GATED: a sticker's passport (iOS 17.10, 2df34a13), an older build buries it
            "lp" -> carded(w, pipe)   // COMPAT-GATED: a letter's link card (iOS 17.10, 89dfd51d), an older build buries it
            "pc" -> cardLaid(w, pipe)   // COMPAT-GATED: a member's card (iOS 07d5fc70, OZ 1 p.8), laid only where the owner names seats
            "fo" -> keptLaid(w, pipe)   // COMPAT-GATED: my own letter's copy laid back after a restore (iOS 2c0498fd, OZ 2 p.1.2.4)
            "fk" -> keptAsked(w, pipe)   // COMPAT-GATED: a restored phone asks a group letter's file again (iOS 11c34ed7, OZ 1 p.10.1)
            "kp" -> Verdict.HELD   // an older build's «kept» word: taken, told to nobody (iOS 2358, 16165 p.5)
            "cm" -> commented(w, pipe)   // COMPAT-GATED: a comment under a channel's post (iOS MTChannelComments, 2315)
            "vw" -> seen(w, pipe)   // COMPAT-GATED: a subscriber's posts shown, to a channel's owner (iOS 17.10)
            "vc" -> counted(w, pipe)   // COMPAT-GATED: a channel owner's view counts, to its subscribers (iOS 17.10)
            "room" -> roomed(w, pipe)   // COMPAT-GATED: a group room's word (iOS MTGroupRoom, 07.10)
            else -> Verdict.REFUSED.also { Log.i("Montana", "group_refused kind=${t.take(8)}") }   // a newer build's word: its road ends here
        }
    }

    /**
     * AN INVITATION (iOS invited): the group stands on this phone, owned by the pipe the word came by. A second invitation of the
     * same owner renews the title, the words, the count and the face; another pipe naming a group held here is refused.
     */
    private fun invited(w: JSONObject, pipe: String, copy: String): Verdict {
        val owner = SamePair.root(pipe)
        val kind = w.optString("k")
        val title = clean(w.optString("ti"), TITLE_LIMIT)
        val seat = w.optString("me")
        if ((kind != GROUP && kind != CHANNEL) || title.isEmpty() || !isSeat(seat) || seat == OWNER_SEAT) return Verdict.REFUSED
        // THE OWNER'S SEATS (iOS 07d5fc70, OZ 1 p.1): the page's people and the head's number; this phone's own seat stands among them
        val seats: List<String>? = if (kind != GROUP) null else w.optJSONArray("ps")?.let { a ->
            val seen = hashSetOf(OWNER_SEAT)
            val got = (0 until a.length()).map { a.optString(it) }.filter { isSeat(it) && seen.add(it) }.take(PEOPLE_LIMIT).toMutableList()
            if (seat !in got) got.add(seat)
            got
        }
        val round: Int? = if (w.has("rr")) w.optInt("rr") else null   // the owner's round (iOS rr, d28805b6, OZ 1 p.8)
        val count = seats?.let { it.size + 1 } ?: w.optInt("c", 2).coerceIn(2, PEOPLE_LIMIT + 1)
        val ownerName = clean(w.optString("n"), NAME_LIMIT)
        val fc = w.optString("fc").ifEmpty { null }
        val tag = fc?.let(::tagOfFace)
        val id = w.getString("g")
        val had = groups[id]
        // WHO MAY ADD ME (iOS MTGroup.invited, b9b212d4, OZ 1 p.1.8): a group or a channel new to this phone, or one I left, stands
        // only by the rule of inviting; a renewal of a group I hold is no invitation.
        if ((had == null || had.left) && !Privacy.admits(Privacy.Act.INVITE, owner)) {
            Log.i("Montana", "group_refused invitation refused by the rule of who may add me")
            return Verdict.REFUSED
        }
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
            had.seats = seats
            had.round = if (seats == null) null else round   // the owner's round: a new one lays this phone's card again (iOS d28805b6)
            if (seats != null) had.cards.keys.retainAll(seats.toSet())   // a card of one gone leaves with their seat (iOS 07d5fc70)
            if (ownerName.isNotEmpty()) had.names[OWNER_SEAT] = ownerName
            had.admins.clear(); had.admins.addAll(admins(w))   // the owner's word alone names them
            takeAdmins(w, had)
            takeRights(w, had)
            val newFace = tag != null && tag != had.faceTag
            if (newFace) had.faceTag = tag
            save()
            stand(had, if (newFace) face(fc) else null, revive = back)   // a renewal leaves a row put away; being added again brings it
            wearFullFace(w)
            // the reference folder's rows: added again, a new name, a new face -- each the owner's deed
            if (back) lay(had, event("added", OWNER_SEAT, listOf(seat)))
            // a member's renewal is named by the owner (iOS w.s, G.5); else the owner's own deed
            val actor = w.optString("s").takeIf { isSeat(it) } ?: OWNER_SEAT
            if (renamed) lay(had, event("named", actor, t = title))
            if (newFace && hadFace && !back) lay(had, event("face", actor))
            giveCards()   // this phone's own card, where the owner names seats (iOS 07d5fc70, OZ 1 p.8)
            return Verdict.HELD
        }
        // A RESTORE'S CATCH-UP IS THE PAST (iOS 3ed661fa, decision 865 a): an invitation sent before this person came in on this phone
        // births no group -- the groups the person held come back with the archive; an invitation sent later is born as before.
        if (MontanaSeed.before(Marks.birthMs(copy))) {
            Log.i("Montana", "group_refused invitation older than this person's coming in")
            return Verdict.REFUSED
        }
        val g = State(id, kind, title, clean(w.optString("ds"), ABOUT_LIMIT), owner, seat, mutableListOf(), count,
            if (ownerName.isEmpty()) mutableMapOf() else mutableMapOf(OWNER_SEAT to ownerName), System.currentTimeMillis(), faceTag = tag,
            admins = admins(w).toMutableList(), seats = seats, round = if (seats == null) null else round)
        takeAdmins(w, g)
        takeRights(w, g)
        groups[id] = g
        save()
        stand(g, face(fc), revive = true)   // the group is born here
        wearFullFace(w)
        lay(g, event("added", OWNER_SEAT, listOf(seat)))   // «X added you»
        giveCards()   // this phone's own card, where the owner names seats (iOS 07d5fc70, OZ 1 p.8)
        Log.i("Montana", "group_rx invitation kind=$kind people=$count from=${pipe.take(10)}")
        return Verdict.HELD
    }

    /**
     * The owner's word about its administrators, taken as it stands (iOS takeAdmins, G.6): the rights left out (known ones alone)
     * the custom titles and the link -- an https link of the owner's card, kept only while this phone is an administrator who may invite.
     */
    private fun takeAdmins(w: JSONObject, g: State) {
        g.taken.clear()
        w.optJSONObject("ax")?.let { x ->
            for (seat in x.keys()) {
                if (!isSeat(seat)) continue
                val a = x.optJSONArray(seat) ?: continue
                val known = (0 until a.length()).map { a.optString(it) }.filter { it in RIGHTS || it in CH_RIGHTS }.distinct()   // a channel's too
                if (known.isNotEmpty()) g.taken[seat] = known
            }
        }
        g.ranks.clear()
        w.optJSONObject("rk")?.let { r ->
            for (seat in r.keys()) {
                if (!isSeat(seat)) continue
                val t = clean(r.optString(seat), RANK_LIMIT)
                if (t.isNotEmpty()) g.ranks[seat] = t
            }
        }
        val mine = adminCan(g, g.me, INVITE) && g.me != OWNER_SEAT
        g.link = if (mine) w.optString("lk").takeIf { it.length <= 2048 && it.startsWith("https://") } else null
    }

    /** What the members may not do and the slow mode, as the owner's word says them (iOS bn, sw, G.5): known rights alone, an hour at most. */
    private fun takeRights(w: JSONObject, g: State) {
        g.barred.clear()
        w.optJSONArray("bn")?.let { a -> g.barred.addAll((0 until a.length()).map { a.optString(it) }.filter { it in MEMBER_RIGHTS }.distinct()) }
        g.slow = w.optInt("sw").coerceIn(0, 3600)
        g.signs = g.kind == CHANNEL && w.optInt("sg") == 1   // iOS g.signs = w.sg == 1
        g.reacts = g.kind != CHANNEL || w.optInt("rx", 1) != 0   // iOS g.reacts = w.rx == 0 ? false : nil
        g.reactList = if (g.kind == CHANNEL) reactionList(w.optJSONArray("rl")) else null   // iOS reactionList(w.rl)
        g.talks = g.kind == CHANNEL && w.optInt("dc") == 1   // iOS g.talks = w.dc == 1 ? true : nil
    }

    /** The administrators an invitation names (iOS ad): seats alone. */
    private fun admins(w: JSONObject): List<String> =
        w.optJSONArray("ad")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { isSeat(it) }.distinct().take(PEOPLE_LIMIT) } ?: emptyList()

    /**
     * The group's row on the list (iOS stand): the one standing or archived, or a new one.
     * `revive` -- A ROW THE PERSON PUT AWAY COMES BACK ONLY BY A PERSON'S DEED (iOS 221da3d9, OZ 1 p.17: «the chat of a group call pops
     * up from nowhere»): a letter, the group's birth, being added again. A renewal of the group, the sibling's renewal keep the group's
     * state and leave its row away -- keepRoster renews every group at launch and would stand back the groups the person deleted.
     */
    private fun stand(g: State, face: ByteArray?, revive: Boolean) {
        val key = key(g.id)
        if (face != null) runCatching { Book.face(key).writeBytes(face) }
        if (!revive && Book.chat(key) == null && Book.erased(key)) { Log.i("Montana", "group_row put away -- the group's own word leaves it away"); return }
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
            // A CHANNEL CARRIES ITS ADMINISTRATORS' POSTS (iOS heard, «Post Messages»): the owner named them; anyone else only reads
            if (!posts(g, m.seat)) { Log.i("Montana", "group_refused a member's letter in a channel is not carried"); return Verdict.REFUSED }
            seat = m.seat
        } else {
            val s0 = w.optString("s")
            if (SamePair.root(g.owner) != from || !isSeat(s0) || s0 == g.me) return Verdict.REFUSED
            seat = s0
        }
        val name = clean(w.optString("n"), NAME_LIMIT)
        var told = takeFace(w.optString("sf").ifEmpty { null }, seat, g)   // the speaker's own face, as their name (iOS MTGroupWord.sf)
        if (name.isNotEmpty() && g.names[seat] != name) { g.names[seat] = name; told = true }
        if (told) save()
        if (id in gone) return Verdict.HELD
        val key = key(g.id)
        // A RESTORED ROW TAKES ITS LETTER'S NAME AND ITS SPEAKER (iOS 6549e6c6, OZ 2 p.1.2): the file laid again for a seal lands in
        // the row the archive laid back -- its file's seal is this letter's name -- and no second row is born
        if (words.startsWith(Marks.MEDIA) && Book.chat(key)?.msgs?.none { it.mid == id } == true)
            Book.edit(key) { c -> if (c.msgs.none { it.mid == id }) Media.nameRestored(c.msgs, id, speaker(g.id, seat)) }
        // a repeat: the row stands -- with new cargo, the row's file comes (iOS refill, 11c34ed7 at 2389, OZ 1 p.10.1)
        runCatching { Book.chat(key)?.msgs?.firstOrNull { it.mid == id } }.getOrNull()?.let { row -> refill(row, words, id, seat, g, key); return Verdict.HELD }
        if (g.mine) {
            // THE GROUP'S PERMISSIONS AT THE DOOR (iOS heard, G.5): the owner carries every letter, so the owner refuses what the members
            // may not send -- whatever a member's build shows -- and holds a letter that came inside its slow mode's interval: it knocks again
            if (!allows(g, seat, words)) { Log.i("Montana", "group_refused a letter past the group's permissions"); return Verdict.REFUSED }
            if (!paceSlow(g, seat)) { Log.i("Montana", "group_slow a letter held inside the slow mode's interval"); return Verdict.WAIT }
        }
        stand(g, null, revive = true)   // a letter
        val sp = speaker(g.id, seat)
        val at = Marks.birthMs(id) ?: System.currentTimeMillis()
        val quote = w.optString("qt").ifEmpty { null }
        val quoted = w.optString("qm").removePrefix("mid:").ifEmpty { null }
        var landed = false
        Book.edit(key) { c ->
            if (c.msgs.none { it.mid == id } && !Book.isBuried(key, id)) {   // a deleted letter's copy stays out (Book.bury)
                c.msgs.add(Msg(id, words, false, at, qt = quote, qm = quoted, from = sp)); c.msgs.sortBy { it.at }
                if (!OldRecordings.notice(words) && (Book.openChat != key || !Presence.shown)) c.unread++   // a locked phone reads nothing (iOS placeRow)
                landed = true
            }
        }
        Log.i("Montana", "group_rx mid=$id letter landed=${if (landed) 1 else 0} owner=${if (g.mine) 1 else 0}")
        // a media letter's file is gathered from the pieces every member reads; its copy was receipted already (handle)
        if (landed && words.startsWith(Marks.MEDIA)) Media.fetch(Book.ctx, key, id, words)
        // the banner a running app owes its person: the group's title over who wrote and what (a channel speaks as itself)
        val caughtUp = MontanaSeed.before(Marks.birthMs(id))   // a restore's catch-up (iOS 3ed661fa, 865 a): silent here and on its way on
        val past = w.optInt("hs") == 1 || w.optInt("sl") == 1 || caughtUp   // the group's past handed to one just added (G.4), and a channel's silent post: no banner
        if (landed && Book.openChat != key && !past && !OldRecordings.notice(words)) Notify.letter(key, words, if (g.kind == GROUP) speakerName(Book.ctx, sp) else null,
            mentioned = g.kind == GROUP && mentionsMe(words))   // a mention rings through the mute (iOS presentGroup mentioned)
        if (landed && Book.openChat == key && g.kind == GROUP) readSoon(key)   // read before the eyes: the speaker hears it (B.13)
        // THE OWNER CARRIES IT ON (iOS heard, g.mine): to every other member, under the speaker's seat and the name they gave
        typingStopped(key, seat)   // the letter came: its speaker types no more (the reference's head)
        if (landed && g.mine) {
            val on = JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", id).put("s", seat).put("tx", words)
            if (name.isNotEmpty()) on.put("n", name)
            w.optString("sf").ifEmpty { null }?.let { on.put("sf", it) }   // the speaker's own face goes on with their letter, unchanged
            if (quote != null) on.put("qt", quote)
            w.optString("qm").ifEmpty { null }?.let { on.put("qm", it) }
            if (caughtUp) on.put("hs", 1)   // the past carried on rings nobody (iOS 3ed661fa: T2 and T3 rang after the restore)
            spread(on, g, seat, own = false)
        }
        return Verdict.HELD
    }

    // A GROUP LETTER'S FILE ASKED FOR AGAIN (iOS MTGroup askKeptFile / keptAsked / refill, 11c34ed7 at 2389, OZ 1 p.10.1; decisions
    // 875 = a, 883 = a): a phone restored from the light copy holds the group's rows and none of their files, and a delivered letter's
    // pieces have left the nodes. It asks by «fk» on the group's own road -- a member the owner, the owner every member; the letter's
    // author lays the file again under the same name (Media.serveKept, carryMedia), the owner carries the new cargo on, and the row
    // without its file takes it. Each side keeps the one clock of a kept file (Media.mayKept).
    /** The asker's half (Media holds its clock): one word to everyone this phone carries to; its receipts move no row. */
    fun askKeptFile(key: String, id: String) {
        synchronized(lock) {
            val g = state(key) ?: return
            // a restored row asks by its file's seal (iOS 6549e6c6, OZ 2 p.1.2): «seal:» is no letter name and was dropped here
            if (g.left || !isLetterName(id) && Media.sealOf(id) == null && Media.ownOf(id) == null) {
                Log.i("Montana", "kept_file m=" + id.take(21) + " a group letter's file is not asked -- no group held or no name to ask by"); return
            }
            val w = JSONObject().put("t", "fk").put("g", g.id).put("k", g.kind).put("id", id)
            val targets = if (g.mine) g.members.map { it.pipe } else listOf(g.owner)
            val n = targets.mapNotNull { carry(w, it, "", own = false) }.size
            Log.i("Montana", "kept_file m=" + id.take(8) + " a group letter's file is asked n=" + n)
        }
    }
    /** The holder's half: the letter's author lays its file again; the owner, not its author, passes the ask to the author's seat
     *  once in the span; anyone else holds the word. Only the group's own road asks. */
    private fun keptAsked(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.optString("g")] ?: return Verdict.REFUSED
        val id = w.optString("id")
        val own = Media.ownOf(id)
        if (g.left || !isLetterName(id) && Media.sealOf(id) == null && own == null) return Verdict.REFUSED
        val root = SamePair.root(pipe)
        val road = if (g.mine) g.members.any { SamePair.root(it.pipe) == root } else SamePair.root(g.owner) == root
        if (!road) return Verdict.REFUSED
        val key = key(g.id)
        // A RESTORED AUTHOR ASKS FOR ITS OWN LETTER'S FILE (iOS 2c0498fd, OZ 2 p.1.2.4, the author's words 22141, 22151): its row of
        // mine there is named by a UUID, mine here by the letter's name. The asker's seat is the one its pipe holds -- a member on the
        // owner's phone, the owner on a member's; its letter of that kind born in [second, second+120], the place-th, goes back to it
        // alone (carryMedia «fo»).
        if (own != null) {
            val asker = (if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == root }?.seat else OWNER_SEAT) ?: return Verdict.REFUSED
            val who = speaker(g.id, asker)
            val row = runCatching { Book.chat(key)?.msgs?.let { Media.ownLetter(it, own, who) } }.getOrNull()
                ?: return Verdict.HELD.also { Log.i("Montana", "kept_file m=" + id.take(24) + " no letter of that seat by this second -- nothing to lay") }
            synchronized(ownLays) { ownLays[who] = pipe }
            Media.serveKept(Book.ctx, key, row)
            return Verdict.HELD
        }
        // a seal finds the letter whose wire name seals to it (iOS 6549e6c6, OZ 2 p.1.2); a letter name finds its own row
        val seal = Media.sealOf(id)
        val row = runCatching { Book.chat(key)?.msgs?.firstOrNull { if (seal != null) Media.seal(it.mid) == seal else it.mid == id } }.getOrNull()
            ?.takeIf { it.text.startsWith(Marks.MEDIA) } ?: return Verdict.HELD
        if (row.mine) { Media.serveKept(Book.ctx, key, row); return Verdict.HELD }
        if (!g.mine) return Verdict.HELD
        val to = speakerPipe(row.from) ?: return Verdict.HELD
        if (SamePair.root(to) == root || !Media.mayKept("pass:" + id)) return Verdict.HELD
        carry(JSONObject().put("t", "fk").put("g", g.id).put("k", g.kind).put("id", id), to, "", own = false)
        Log.i("Montana", "kept_file m=" + id.take(8) + " the ask passes to the letter's author")
        return Verdict.HELD
    }
    /** MY COPY OF ANOTHER'S LETTER GOES BACK TO ITS RESTORED AUTHOR ALONE (iOS MTGroup.ownLays): the asker's pipe by its speaker, kept
     *  for this run while the copy uploads (keptAsked to carryMedia); a copy nobody asked for is never carried. */
    private val ownLays = HashMap<String, String>()
    /** MY OWN GROUP LETTER'S FILE LAID BACK (iOS MTGroup.keptLaid, OZ 2 p.1.2.4): a copy of my letter from the group's own road -- the
     *  owner's pipe on a member's phone, a member's on the owner's -- under my own seat, for a row of mine this phone asked by own:. My
     *  restored row takes the letter's name (Media.nameOwnRestored) and the copy fills it; no second row is born, nothing rings. */
    private fun keptLaid(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.optString("g")] ?: return Verdict.REFUSED
        val id = w.optString("id"); val words = w.optString("tx")
        if (g.left || !isLetterName(id) || !carries(words) || !words.startsWith(Marks.MEDIA) || w.optString("s") != g.me) return Verdict.REFUSED
        val root = SamePair.root(pipe)
        val laid = if (g.mine) g.members.any { SamePair.root(it.pipe) == root } else SamePair.root(g.owner) == root
        if (!laid) return Verdict.REFUSED   // only the group's own road lays
        val key = key(g.id)
        val kind = Media.manifest(words)?.optString("k")?.ifEmpty { null }
        var mine = false
        Book.edit(key) { c ->
            if (c.msgs.none { it.mid == id }) Media.nameOwnRestored(c.msgs, id, kind)
            c.msgs.find { it.mid == id && it.mine }?.let { r ->
                mine = true
                if (r.file?.let { java.io.File(it).exists() } != true) { r.text = words; r.meta = null }
            }
        }
        if (!mine) { Log.i("Montana", "kept_file m=" + id.take(8) + " a copy of my letter for no row of mine this phone asked by -- not taken"); return Verdict.HELD }
        Media.liftGone(id)
        Media.fetch(Book.ctx, key, id, words)
        Log.i("Montana", "kept_file m=" + id.take(8) + " my restored group row takes my letter's copy")
        return Verdict.HELD
    }
    /** A REPEAT WITH NEW CARGO: the author laid a letter's file again under its name. The row without its file takes it; the owner
     *  carries the new cargo on once in the span, silent -- the row stands everywhere already. Only the row's own speaker lays it. */
    private fun refill(row: Msg, words: String, id: String, seat: String, g: State, key: String) {
        if (!words.startsWith(Marks.MEDIA) || words == row.text || row.mine || row.from != speaker(g.id, seat)) return
        if (row.file?.let { java.io.File(it).exists() } != true) {
            Book.edit(key) { c -> c.msgs.find { it.mid == id }?.let { it.text = words; it.meta = null } }
            Media.liftGone(id)
            Log.i("Montana", "rx_refill m=" + id.take(8) + " a group row stands without its file -- the new cargo is taken")
            Media.fetch(Book.ctx, key, id, words)
        }
        if (!g.mine || !Media.mayKept("carry:" + id)) return
        val on = JSONObject().put("t", "say").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", id).put("s", seat).put("tx", words)
            .put("hs", 1)   // the row stands everywhere: the new cargo rings nobody
        spread(on, g, seat, own = false)
    }

    /**
     * AN ANSWER (iOS answered): a reaction, an edit of one's own words, a deletion of one's own letter — applied in the group's
     * feed once by its name. Only a letter's own speaker changes it or takes it away; a deletion of a letter not landed yet
     * buries it before it comes.
     */
    /**
     * A ROOM'S WORD (iOS roomed): its seat is proven by the pipe it came by, as an answer's is; the room reads it once by its name,
     * and the owner of the group passes it on to everyone else -- a targeted word (an invitation, a key) to the seats it names alone,
     * ringing there only if it is an invitation. A room's word is never a row.
     */
    private fun roomed(w: JSONObject, pipe: String): Verdict {
        val g = groups[w.getString("g")] ?: return Verdict.WAIT
        if (g.left) return Verdict.REFUSED
        val id = w.optString("id")
        val words = w.optString("tx")
        if (!isLetterName(id) || words.isEmpty() || words.toByteArray().size > 4096) return Verdict.REFUSED
        val from = SamePair.root(pipe)
        val seat = if (g.mine) g.members.firstOrNull { SamePair.root(it.pipe) == from }?.seat ?: return Verdict.REFUSED
            else w.optString("s").takeIf { SamePair.root(g.owner) == from && isSeat(it) && it != g.me } ?: return Verdict.REFUSED
        if (id in heard) return Verdict.HELD
        if (!paced(g, seat)) return Verdict.WAIT
        remember(id)
        Rooms.heard(words, g.id, seat)
        if (g.mine) {
            val on = JSONObject().put("t", "room").put("g", g.id).put("k", g.kind).put("ti", g.title).put("id", id).put("s", seat).put("tx", words)
            w.optString("n").ifEmpty { null }?.let { on.put("n", it) }
            if (MontanaSeed.before(Marks.birthMs(id))) on.put("hs", 1)   // a restore's catch-up rings nobody (iOS 3ed661fa, 865 a)
            val ts = w.optJSONArray("ts")
            if (ts != null && ts.length() <= 32) {
                // a targeted word: to the seats it names alone -- ringing there if it is an invitation (a word naming no ring is one)
                val ring = !w.has("rg") || w.optInt("rg") == 1
                val to = List(ts.length()) { ts.optString(it) }.toSet()
                on.put("ts", ts).put("rg", if (ring) 1 else 0)
                for (m in g.members) if (m.seat in to && m.seat != seat) carry(JSONObject(on.toString()), m.pipe, "", own = false, ring = ring)
            } else {
                for (m in g.members) if (m.seat != seat) carry(JSONObject(on.toString()), m.pipe, "", own = false, ring = false)
            }
        }
        return Verdict.HELD
    }
    /** The pace of one member's room words the owner takes and carries on (iOS paced, carriedPerMinute 30). */
    private val carriedAt = HashMap<String, MutableList<Long>>()
    private fun paced(g: State, seat: String): Boolean {
        if (!g.mine) return true
        val now = System.currentTimeMillis()
        val moments = carriedAt.getOrPut(g.id + "/" + seat) { mutableListOf() }
        moments.removeAll { now - it >= 60_000 }
        if (moments.size >= 30) { Log.i("Montana", "group_paced a member past thirty room words a minute"); return false }
        moments.add(now)
        return true
    }
    /** A row of the room laid in the group's feed (iOS lay MTGroupEvent «call», «ended»): this phone's own, never sent. */
    fun layRoom(gid: String, e: JSONObject) { synchronized(lock) { groups[gid]?.let { lay(it, e) } } }
    /** The name a seat is shown by in its group (iOS speakerName(speaker(group, seat))). */
    fun seatName(c: Context, gid: String, seat: String): String = speakerName(c, speaker(gid, seat))

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
        // A CHANNEL WHOSE OWNER TURNED REACTIONS OFF TAKES NONE, ONE THAT TAKES SOME TAKES THOSE ALONE (iOS answered, Allow / Some
        // Reactions): a subscriber's reaction ends here; the owner and its administrators are never refused
        if (words.startsWith(Marks.REACTION) && g.kind == CHANNEL && seat != OWNER_SEAT && seat !in g.admins) {
            val o = runCatching { JSONObject(words.removePrefix(Marks.REACTION)) }.getOrNull()
            val list = g.reactList
            if (!g.reacts || (list != null && o?.optString("op") == "add" && o.optString("e") !in list)) {
                Log.i("Montana", "group_refused a reaction the channel does not take")
                return Verdict.REFUSED
            }
        }
        if (g.mine && !allows(g, seat, words)) {   // a reaction or an edit past the group's permissions (iOS answered, G.5)
            Log.i("Montana", "group_refused an answer past the group's permissions")
            return Verdict.REFUSED
        }
        val key = key(g.id)
        target(words)?.let { t ->
            val row = Book.chat(key)?.msgs?.firstOrNull { it.mid == t }
            if (row != null) {
                // the owner and the administrators it named take another's letter away (iOS stage R.6); an edit stays the speaker's own
                val boss = words.startsWith(Marks.DELETE) && adminCan(g, seat, DELETE)   // G.6: «Delete Messages»
                if (author(row, g) != seat && !boss) {
                    Log.i("Montana", "group_refused an answer to another's letter seat=$seat")
                    return Verdict.REFUSED
                }
            } else if (t in gone || Book.isBuried(key, t)) return Verdict.HELD   // the letter is gone here already (iOS store.buried)
            else if (g.mine) return Verdict.WAIT   // the letter has not landed at the owner yet: the copy knocks again
            else if (words.startsWith(Marks.DELETE)) { bury(t); remember(id); return Verdict.HELD }
        }
        remember(id)
        apply(g, key, words, seat)
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
        act(g, "left", listOf(nameNow(g, m.seat)))
        g.members.removeAll { it.seat == m.seat }
        g.admins.remove(m.seat)
        g.taken.remove(m.seat); g.ranks.remove(m.seat)
        g.cards.remove(m.seat)   // their card leaves with them (iOS 07d5fc70, OZ 1 p.8)
        g.count = g.members.size + 1
        save()
        tellAll(g, null)
        val gone = event("left", m.seat)
        lay(g, gone)
        tellEvent(g, gone)
        Log.i("Montana", "group_rx a member left count=" + g.count)
        return Verdict.HELD
    }

    private fun apply(g: State, key: String, words: String, seat: String) {
        when {
            // A DELETION FOR EVERYONE ALWAYS LANDS (iOS 2358, 16165 p.5): a letter or a channel's post taken away leaves this phone
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
                Book.edit(key) { c -> c.msgs.find { it.mid == t }?.let { m ->
                    if (o.optString("op") == "del") { m.reactions.remove(e); if (!c.ansMine) c.noteAnswer(null, false, m) } else { m.reactions.add(e); c.noteAnswer(e, false, m) }
                } }
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
            Rooms.groupGone(g.id)
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
    fun isLetterName(s: String) = s.isNotEmpty() && s.length <= 80 && !s.startsWith("mid:") &&
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
