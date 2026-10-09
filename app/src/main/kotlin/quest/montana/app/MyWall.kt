package quest.montana.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * WHO MAY WRITE ON MY WALL, AND WHO MAY SEE IT (iOS MTBoardRule, MontanaBoard.swift 25-95 at 2155; the author's words 24.09 and 25.09):
 * only me, everyone, my contacts, some — chosen — or everyone but some. One rule over two acts, each under its own keys; both start
 * at «Everyone». A blocked person never may.
 */
object WallRule {
    enum class Act { WRITE, SEE }
    enum class Rule(val raw: String) { ONLY_ME("onlyMe"), EVERYONE("everyone"), CONTACTS("contacts"), SOME("some"), EXCEPT("except") }
    private fun keys(a: Act) = if (a == Act.WRITE) Triple("boardRule", "boardAllow", "boardDeny") else Triple("boardSight", "boardSightAllow", "boardSightDeny")
    fun current(a: Act): Rule = Prefs.str(keys(a).first, "").let { s -> Rule.values().firstOrNull { it.raw == s } } ?: Rule.EVERYONE
    private fun set(key: String) = Prefs.str(key, "").split('\n').filter { it.isNotEmpty() }.toSet()
    fun allowed(a: Act) = set(keys(a).second)
    fun denied(a: Act) = set(keys(a).third)
    fun choose(r: Rule, a: Act) { Prefs.setStr(keys(a).first, r.raw); MyWall.renewVersion(own = true) }
    fun setAllowed(s: Set<String>, a: Act) { Prefs.setStr(keys(a).second, s.sorted().joinToString("\n")); MyWall.renewVersion(own = true) }
    fun setDenied(s: Set<String>, a: Act) { Prefs.setStr(keys(a).third, s.sorted().joinToString("\n")); MyWall.renewVersion(own = true) }
    /** May this correspondent write on my wall, or see it? A blocked person never may. */
    fun admits(ref: String, a: Act): Boolean = ref.isNotEmpty() && !PeerSafety.isBlocked(ref) && byRule(ref, a)
    /** The rule alone, without the block list — for the presence word, which never reaches a blocked person anyway. */
    fun byRule(ref: String, a: Act): Boolean {
        if (ref.isEmpty()) return false
        return when (current(a)) {
            Rule.ONLY_ME -> false
            Rule.EVERYONE -> true
            Rule.CONTACTS -> Book.chat(ref)?.shown?.isNotBlank() == true && !Groups.isKey(ref)   // a person of the contacts' page
            Rule.SOME -> ref in allowed(a)
            Rule.EXCEPT -> ref !in denied(a)
        }
    }
}

/**
 * MY OWN WALL, AS ITS OWNER KEEPS IT (iOS MTBoard's owner side, MontanaBoard.swift 331-1343 at 2155; the author's word 24.09): on my page
 * the people I allow write posts, and a post lives with those who keep it. The references are mine and never leave this phone: a visitor
 * gets the counts and their own marks. The owner carries the page to every correspondent whose build speaks the wall and whose version
 * of it moved; the version rides every presence word («W»), one per visitor. Sealed at rest under the device key.
 */
object MyWall {
    private const val PAGE_SIZE = 30
    private const val COMMENTS_SHOWN = 20
    private const val TEXT_LIMIT = 4000
    private const val COMMENT_LIMIT = 1000
    private const val POSTS_PER_WRITER = 100   // [I-14]: a writer let in cannot fill a wall
    private const val WALL_LIMIT = 500
    private const val FACE_LIMIT = 16_000
    private const val THUMB_LIMIT = 60_000
    private const val MEDIA_LIMIT = 10
    private const val PUSH_TO = 128            // the owner carries the wall to the latest correspondents this many at most
    private const val PAGE_SHAPE = "c4"        // the page's shape is part of its version: c4 carries the posts' views
    private const val ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    private val lock = Any()
    private var loaded = false
    private val mine = mutableListOf<JSONObject>()
    private val cap = HashSet<String>()                    // correspondents whose build speaks the wall — told by their own words
    private val sent = HashMap<String, String>()           // the version each was last carried, written by the receipt alone
    private val inFlight = HashMap<String, Triple<String, Long, String>>()   // the page handed to the queue: version, moment, letter
    private val answeredAt = HashMap<String, Long>()
    private val pushing = HashSet<String>()
    @Volatile private var versionKept = "0"

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun unlisten(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private fun file() = File(File(Book.ctx.filesDir, "board").apply { mkdirs() }, "mine.sealed")
    private fun ensure() {
        if (loaded) return
        loaded = true
        val o = runCatching { DeviceVault.unseal(file().readBytes())?.let { JSONObject(String(it, Charsets.UTF_8)) } }.getOrNull()
        o?.optJSONArray("mine")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { mine.add(it) } }
        o?.optJSONArray("cap")?.let { a -> for (i in 0 until a.length()) cap.add(a.optString(i)) }
        o?.optJSONObject("sent")?.let { s -> s.keys().forEach { k -> sent[k] = s.optString(k) } }
        val sealed = sealMissing()
        if (numberMissing() || sealed) write()
        versionKept = postsTag()
    }
    private fun write() {
        val o = JSONObject().put("mine", JSONArray(mine)).put("cap", JSONArray(cap.toList())).put("sent", JSONObject(sent as Map<*, *>))
        val sealedBytes = DeviceVault.seal(o.toString().toByteArray(Charsets.UTF_8)) ?: return
        val part = File(file().path + ".part")
        runCatching { part.writeBytes(sealedBytes); part.renameTo(file()) }
    }
    /** The person forgotten: their wall leaves with them. */
    fun wipe() = synchronized(lock) { mine.clear(); cap.clear(); sent.clear(); inFlight.clear(); loaded = false; file().delete() }

    // ── the shape of a post, owner and visitor ──
    private fun arr(p: JSONObject, k: String): JSONArray = p.optJSONArray(k) ?: JSONArray().also { p.put(k, it) }
    private fun has(a: JSONArray, v: String) = (0 until a.length()).any { a.optString(it) == v }
    private fun toggle(p: JSONObject, k: String, who: String, on: Boolean) {
        val a = arr(p, k)
        if (on) { if (!has(a, who)) a.put(who) }
        else { val keep = JSONArray(); for (i in 0 until a.length()) a.optString(i).takeIf { it != who }?.let { keep.put(it) }; p.put(k, keep) }
    }
    private fun lastAt(p: JSONObject): Double {
        val cs = p.optJSONArray("comments") ?: return p.optDouble("at", 0.0)
        return (0 until cs.length()).maxOfOrNull { cs.optJSONObject(it)?.optDouble("at", 0.0) ?: 0.0 }?.let { maxOf(it, p.optDouble("at", 0.0)) } ?: p.optDouble("at", 0.0)
    }
    /** THE WALL, NEWEST FIRST: the pinned on top, then each post at the moment of the newest record of its chain. */
    private fun ordered(): List<JSONObject> = mine.sortedWith(compareByDescending<JSONObject> { it.optBoolean("pinned") }.thenByDescending { lastAt(it) })
    /** The views move the version at every doubling (1, 2, 4, 8 …), so a hundred viewers carry the wall a handful of times. */
    private fun viewStep(n: Int): Int = if (n == 0) 0 else 64 - java.lang.Long.numberOfLeadingZeros(n.toLong())

    /** One version per visitor's right, the posts' part beside it (iOS postsTag 552-565): an edit moves only its own wall's version. */
    private fun postsTag(): String {
        val shown = ordered().take(PAGE_SIZE)
        if (shown.isEmpty()) return "0"
        val s = StringBuilder(PAGE_SHAPE + ";")
        for (p in shown) {
            val cs = arr(p, "comments")
            val faces = (0 until cs.length()).count { cs.optJSONObject(it)?.has("fc") == true } + (if (p.has("face")) 1 else 0)
            val hid = (0 until cs.length()).count { cs.optJSONObject(it)?.optBoolean("hid") == true }
            s.append(p.optString("id")).append(if (p.optBoolean("pinned")) "p" else "").append(arr(p, "keepers").length()).append('.')
                .append(arr(p, "likes").length()).append('.').append(arr(p, "downloads").length()).append('.').append(arr(p, "reposts").length())
                .append('.').append(cs.length()).append('.').append(faces).append('.').append(viewStep(arr(p, "views").length()))
            if (p.has("ed")) s.append("e").append((p.optDouble("ed") * 1000).toLong())
            s.append("z").append(hid).append(";")
        }
        return Presence.wireTag(s.toString().toByteArray(Charsets.UTF_8))
    }
    private val refused by lazy { Presence.wireTag("0r".toByteArray()) }
    /** MY WALL'S VERSION FOR ONE VISITOR (iOS spokenVersion 534-540): one the rule of sight leaves out hears what an empty wall says. */
    fun spokenVersion(peer: String): String {
        synchronized(lock) { ensure() }
        if (!WallRule.byRule(peer, WallRule.Act.SEE)) return refused
        val bit = if (WallRule.byRule(peer, WallRule.Act.WRITE)) "w" else "r"
        return Presence.wireTag((versionKept + bit).toByteArray(Charsets.UTF_8))
    }
    /** One who hides their presence carries the wall only right after an act of their own (the critic's N6). */
    fun renewVersion(own: Boolean = false) {
        synchronized(lock) { ensure(); versionKept = postsTag() }
        changed()
        if (own || Presence.sharing) schedulePush()
    }

    // ── the owner carries the wall ──
    /** A correspondent spoke the wall — a word of it: from now on the owner may carry the page to them. */
    fun learnCap(ref: String) {
        if (ref.isEmpty() || Groups.isKey(ref)) return
        val fresh = synchronized(lock) { ensure(); cap.add(ref).also { if (it) write() } }
        if (fresh && Presence.sharing) schedulePush()
    }
    @Volatile private var pushGen = 0
    /** Half a second folds a burst of edits into one page, then every correspondent a few frames apart (iOS schedulePush 640-648): only the last ask of a burst runs. */
    fun schedulePush() {
        val gen = synchronized(lock) { ++pushGen }
        MainThread.later(500, Runnable { if (gen == pushGen) Thread { pushDue() }.start() })
    }
    private fun pushDue() {
        val (latest, withdrawn) = synchronized(lock) {
            ensure()
            val l = Book.all().map { it.ref }.filter { it in cap }.take(PUSH_TO)
            // A WITHDRAWN SIGHT REACHES EVERYONE WHO SAW (the critic's N4): the empty page, so no old post stays with them
            val w = sent.filter { it.value != refused && !WallRule.byRule(it.key, WallRule.Act.SEE) }.keys
            l to w
        }
        val due = latest + withdrawn.filter { it !in latest }
        var n = 0
        for (conv in due) {
            if (Book.secret(conv) == null) continue
            val v = spokenVersion(conv)
            val go = synchronized(lock) { conv !in pushing && sent[conv] != v && !onItsWay(conv, v) && pushing.add(conv) }
            if (!go) continue
            MainThread.later(150L * n++, Runnable {
                Thread {
                    synchronized(lock) { pushing.remove(conv) }
                    val v2 = spokenVersion(conv)
                    if (synchronized(lock) { sent[conv] != v2 && !onItsWay(conv, v2) }) sendPage(conv, v2)
                }.start()
            })
        }
    }
    /** The same page already stands in the queue for them, handed less than half an hour ago. */
    private fun onItsWay(conv: String, v: String): Boolean = inFlight[conv]?.let { it.first == v && System.currentTimeMillis() - it.second < 1_800_000L } == true

    /**
     * THE ONE PAGE THIS OWNER SENDS (iOS sendPage 683-690): only one who may see may write; the page is «sent» at its receipt; a
     * visitor gets the counts and their own marks, the latest comments, and a hidden comment's words are not carried.
     */
    private fun sendPage(conv: String, v: String) {
        val sees = WallRule.admits(conv, WallRule.Act.SEE)
        val ps = JSONArray()
        if (sees) synchronized(lock) { ensure(); ordered().take(PAGE_SIZE).forEach { ps.put(seen(it, conv, conceal = true)) } }
        val word = JSONObject().put("t", "page").put("w", sees && WallRule.admits(conv, WallRule.Act.WRITE)).put("ps", ps).put("v", v)
        val mid = Board.sendWord(conv, word) ?: return
        synchronized(lock) { inFlight[conv] = Triple(v, System.currentTimeMillis(), mid); answeredAt[conv] = System.currentTimeMillis() }
        Log.d("Montana", "wall_page posts=" + ps.length())
    }
    /** The receipt of a page letter: the version it carried is the one that visitor now holds (iOS delivered 692-701). */
    fun delivered(ref: String, mid: String) {
        synchronized(lock) {
            val f = inFlight[ref] ?: return
            if (f.third != mid) return
            sent[ref] = f.first
            inFlight.remove(ref)
            write()
        }
    }
    /** An ask: one answer to one correspondent in thirty seconds (the critic's P6) — the page as it stands, or the empty one. */
    fun answer(ref: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) { if (now - (answeredAt[ref] ?: 0L) < 30_000L) return; answeredAt[ref] = now }
        sendPage(ref, spokenVersion(ref))
    }

    /** A post as a visitor sees it: the counts and the visitor's own marks, no reference of anybody (iOS seen 758-769, shown 773-780). */
    private fun seen(p: JSONObject, by: String, conceal: Boolean): JSONObject {
        val cs = arr(p, "comments")
        val from = if (conceal) maxOf(0, cs.length() - COMMENTS_SHOWN) else 0
        val shown = JSONArray()
        for (i in from until cs.length()) {
            val c = cs.optJSONObject(i) ?: continue
            val x = JSONObject(c.toString())
            x.remove("ref")
            if (by.isNotEmpty() && c.optString("ref", "-") == by) x.put("m", true) else x.remove("m")
            if (c.has("ref") && c.optString("ref") == "") x.put("o", true) else x.remove("o")
            if (conceal && c.optBoolean("hid")) x.put("text", "")
            shown.put(x)
        }
        val author = p.optString("author")
        val x = JSONObject().put("id", p.optString("id")).put("byName", p.optString("byName")).put("byGlyph", p.optString("byGlyph"))
            .put("at", p.optDouble("at")).put("text", p.optString("text")).put("media", arr(p, "media")).put("pinned", p.optBoolean("pinned"))
            .put("keepers", arr(p, "keepers").length()).put("likes", arr(p, "likes").length()).put("downloads", arr(p, "downloads").length())
            .put("reposts", arr(p, "reposts").length()).put("comments", shown).put("commentCount", cs.length())
            .put("liked", has(arr(p, "likes"), by)).put("kept", has(arr(p, "keepers"), by)).put("reposted", has(arr(p, "reposts"), by))
            .put("mine", author == by).put("views", arr(p, "views").length())
        p.optString("from").takeIf { it.isNotEmpty() }?.let { x.put("from", it) }
        if (!(author.isEmpty() && !p.has("from"))) p.optString("face").takeIf { it.isNotEmpty() }?.let { x.put("face", it) }
        if (author.isEmpty() && (!p.has("from") || p.optString("srcBy", "-") == "")) x.put("own", true)
        p.optString("lp").takeIf { it.isNotEmpty() }?.let { x.put("lp", it) }
        if (p.has("n")) x.put("n", p.optInt("n"))
        return x
    }

    // ── the words of visitors (iOS apply 810-972, the owner's side) ──
    /** A word of my wall from a correspondent: their post, their marks, their comment, their view. */
    fun heard(ref: String, w: JSONObject) {
        val now = System.currentTimeMillis() / 1000.0
        when (w.optString("t")) {
            "post" -> takePost(ref, w, now)
            "like" -> if (WallRule.admits(ref, WallRule.Act.SEE)) mutate(w.optString("id")) { toggle(it, "likes", ref, w.optBoolean("on", true)) }
            "keep" -> if (WallRule.admits(ref, WallRule.Act.SEE)) mutate(w.optString("id")) { toggle(it, "keepers", ref, w.optBoolean("on", true)) }
            "dl" -> if (WallRule.admits(ref, WallRule.Act.SEE)) mutate(w.optString("id")) { toggle(it, "downloads", ref, true) }
            "rp" -> if (WallRule.admits(ref, WallRule.Act.SEE)) mutate(w.optString("id")) { toggle(it, "reposts", ref, true) }
            // A POST SEEN: a person counts once on a post, its writer never, and nobody is shown who (iOS 899-909)
            "view" -> if (WallRule.admits(ref, WallRule.Act.SEE)) {
                val vs = w.optJSONArray("vs") ?: JSONArray()
                var moved = false
                synchronized(lock) {
                    ensure()
                    for (i in 0 until minOf(vs.length(), PAGE_SIZE)) {
                        val p = mine.firstOrNull { it.optString("id") == vs.optString(i) } ?: continue
                        if (p.optString("author") == ref || has(arr(p, "views"), ref)) continue
                        arr(p, "views").put(ref); moved = true
                    }
                    if (moved) write()
                }
                if (moved) renewVersion()
            }
            "cmt" -> takeComment(ref, w, now)
            // ONLY THE WALL'S OWNER TAKES A POST OFF THEIR WALL: a writer's «del» says only that their phone no longer keeps its files
            "del" -> mutate(w.optString("id")) { p -> if (has(arr(p, "keepers"), ref)) toggle(p, "keepers", ref, false) }
            "gone", "seed" -> Log.d("Montana", "wall_files t=" + w.optString("t") + " — the files of my wall come with the posts' files")
            else -> {}   // «hi» is the proof alone; a shape from a newer build is buried in silence
        }
    }
    private fun takePost(ref: String, w: JSONObject, now: Double) {
        val id = w.optString("id").take(64)
        if (id.isEmpty()) return
        if (!WallRule.admits(ref, WallRule.Act.WRITE) || !WallRule.admits(ref, WallRule.Act.SEE)) {
            Board.sendWord(ref, JSONObject().put("t", "no").put("id", id)); Log.d("Montana", "wall_refused rule=" + WallRule.current(WallRule.Act.WRITE).raw); return
        }
        val name = w.optString("bn").take(64).ifEmpty { Book.chat(ref)?.shown?.ifBlank { null } ?: Book.ctx.getString(R.string.wall_someone) }
        val glyph = w.optString("bg").take(8).ifEmpty { name.take(1).uppercase() }
        val post = JSONObject().put("id", id).put("author", ref).put("byName", name).put("byGlyph", glyph)
            .put("at", minOf(w.optDouble("at", now), now)).put("text", w.optString("tx").take(TEXT_LIMIT)).put("media", cleanMedia(w.optJSONArray("md")))
            .put("pinned", false).put("keepers", JSONArray().put(ref)).put("likes", JSONArray()).put("downloads", JSONArray())
            .put("reposts", JSONArray()).put("comments", JSONArray())
        w.optString("fc").takeIf { it.isNotEmpty() && it.length <= FACE_LIMIT }?.let { post.put("face", it) }
        w.optString("lp").takeIf { 2 < it.length && it.toByteArray().size < 80_001 }?.let { post.put("lp", it) }
        val refuse = synchronized(lock) {
            ensure()
            when {
                mine.any { it.optString("id") == id } -> return
                POSTS_PER_WRITER <= mine.count { it.optString("author") == ref } || WALL_LIMIT <= mine.size -> true
                else -> { mine.add(post); false }
            }
        }
        if (refuse) { Board.sendWord(ref, JSONObject().put("t", "no").put("id", id)); Log.d("Montana", "wall_refused full"); return }
        saveMine()
    }
    private fun takeComment(ref: String, w: JSONObject, now: Double) {
        if (!WallRule.admits(ref, WallRule.Act.SEE) || !WallRule.admits(ref, WallRule.Act.WRITE)) return
        val text = w.optString("tx").trim()
        if (text.isEmpty()) return
        val name = w.optString("bn").take(64).ifEmpty { Book.chat(ref)?.shown?.ifBlank { null } ?: Book.ctx.getString(R.string.wall_someone) }
        val c = JSONObject().put("id", w.optString("cid").ifEmpty { java.util.UUID.randomUUID().toString().uppercase() }).put("by", name)
            .put("glyph", w.optString("bg").take(8).ifEmpty { name.take(1).uppercase() }).put("text", text.take(COMMENT_LIMIT))
            .put("at", minOf(w.optDouble("at", now), now)).put("ref", ref)
        w.optString("fc").takeIf { it.isNotEmpty() && it.length <= FACE_LIMIT }?.let { c.put("fc", it) }
        w.optString("hh").takeIf { it.isNotEmpty() }?.let { c.put("h", it) }
        w.optString("pv").takeIf { it.isNotEmpty() }?.let { c.put("pv", it) }
        mutate(w.optString("id")) { p -> chain(p, c) }
        // THE COMMENTER SEES EVERY COMMENT: a comment is answered as an ask is — with the page as it stands
        answer(ref)
    }
    /** A comment joins its post's chain once by its name; one that came unsealed is sealed after the last seal the post holds. */
    private fun chain(p: JSONObject, c: JSONObject) {
        val cs = arr(p, "comments")
        if ((0 until cs.length()).any { cs.optJSONObject(it)?.optString("id") == c.optString("id") }) return
        if (!c.has("h")) {
            val prev = cs.optJSONObject(cs.length() - 1)?.optString("h")?.ifEmpty { null } ?: ZERO_HASH
            c.put("pv", prev).put("h", Board.seal(c.optString("id"), c.optDouble("at"), prev, c.optString("text")))
        }
        cs.put(c)
    }
    private fun cleanMedia(ms: JSONArray?): JSONArray {
        val out = JSONArray()
        if (ms == null) return out
        for (i in 0 until minOf(ms.length(), MEDIA_LIMIT)) {
            val m = ms.optJSONObject(i) ?: continue
            val y = JSONObject().put("kind", m.optString("kind")).put("name", m.optString("name").take(120))
                .put("ext", m.optString("ext").take(8).filter { it.isLetterOrDigit() }).put("size", m.optLong("size"))
                .put("key", m.optString("key")).put("chunks", m.optJSONArray("chunks") ?: JSONArray())
            m.optString("thumb").takeIf { i < 4 && it.isNotEmpty() && it.length <= THUMB_LIMIT }?.let { y.put("thumb", it) }
            if (m.has("dur")) y.put("dur", m.optDouble("dur"))
            if (m.optString("kind") == "img" || m.optString("kind") == "vid") m.optJSONObject("fr")?.let { y.put("fr", it) }
            out.put(y)
        }
        return out
    }
    private fun mutate(id: String, own: Boolean = false, change: (JSONObject) -> Unit) {
        val hit = synchronized(lock) { ensure(); mine.firstOrNull { it.optString("id") == id }?.also(change) } ?: return
        saveMine(own)
    }
    /** One write a moment: the post numbers given, the record sealed, the version renewed (iOS saveMine 445-448). */
    private fun saveMine(own: Boolean = false) {
        synchronized(lock) { numberMissing(); write() }
        renewVersion(own)
    }
    /** THE POST'S NUMBER ON ITS WALL (the author's word 02.10): given once, the next after the highest, in the order of birth. */
    private fun numberMissing(): Boolean {
        var top = mine.maxOfOrNull { it.optInt("n", 0) } ?: 0
        var moved = false
        for (p in mine.sortedBy { it.optDouble("at") }) if (!p.has("n")) { top++; p.put("n", top); moved = true }
        return moved
    }
    private fun sealMissing(): Boolean {
        var moved = false
        for (p in mine) {
            val cs = arr(p, "comments")
            var prev = ZERO_HASH
            for (i in 0 until cs.length()) {
                val c = cs.optJSONObject(i) ?: continue
                if (!c.has("h")) { c.put("pv", prev).put("h", Board.seal(c.optString("id"), c.optDouble("at"), prev, c.optString("text"))); moved = true }
                prev = c.optString("h").ifEmpty { prev }
            }
        }
        return moved
    }

    // ── the owner's own hand ──
    /** My wall as I see it: every post, the pinned first, newest by its chain. */
    fun posts(): List<JSONObject> = synchronized(lock) { ensure(); ordered().map { seen(it, "", conceal = false) } }
    fun post(id: String): JSONObject? = synchronized(lock) { ensure(); mine.firstOrNull { it.optString("id") == id }?.let { seen(it, "", conceal = false) } }
    /** A NEW POST OF MINE, WORDS (iOS begin/fresh/lay for a post with no files): it stands on my wall at once and the wall's version moves. */
    fun publish(text: String): Boolean {
        val t = text.trim().take(TEXT_LIMIT)
        if (t.isEmpty()) return false
        val name = Prefs.userName.trim()
        val id = "p" + java.util.UUID.randomUUID().toString().replace("-", "").lowercase().take(24)
        val post = JSONObject().put("id", id).put("author", "").put("byName", name).put("byGlyph", name.take(1).uppercase())
            .put("at", System.currentTimeMillis() / 1000.0).put("text", t).put("media", JSONArray()).put("pinned", false)
            .put("keepers", JSONArray().put("")).put("likes", JSONArray()).put("downloads", JSONArray()).put("reposts", JSONArray())
            .put("comments", JSONArray())
        synchronized(lock) { ensure(); mine.add(post) }
        saveMine(own = true)
        Log.d("Montana", "wall_write id=" + id.take(10) + " files=0 wall=mine")
        return true
    }
    fun pin(id: String) = mutate(id, own = true) { it.put("pinned", !it.optBoolean("pinned")) }
    fun like(id: String) = mutate(id, own = true) { toggle(it, "likes", "", !has(arr(it, "likes"), "")) }
    /** My own comment on my own post: sealed after the last seal the post holds. */
    fun comment(id: String, text: String) {
        val t = text.trim().take(COMMENT_LIMIT)
        if (t.isEmpty()) return
        val name = Prefs.userName.trim()
        val c = JSONObject().put("id", java.util.UUID.randomUUID().toString().uppercase()).put("by", name).put("glyph", name.take(1).uppercase())
            .put("text", t).put("at", System.currentTimeMillis() / 1000.0).put("ref", "")
        mutate(id, own = true) { chain(it, c) }
    }
    /** OFF MY WALL, BY ITS OWNER ALONE (iOS remove 1571-1584): its writer is told they need not keep it for me. */
    fun remove(id: String) {
        val author = synchronized(lock) {
            ensure()
            val p = mine.firstOrNull { it.optString("id") == id } ?: return
            mine.remove(p)
            p.optString("author")
        }
        if (author.isNotEmpty()) Board.sendWord(author, JSONObject().put("t", "drop").put("id", id))
        saveMine(own = true)
    }
}
