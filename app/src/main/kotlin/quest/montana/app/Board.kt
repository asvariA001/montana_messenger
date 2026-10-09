package quest.montana.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ─────────────────────────── the walls of my people (iOS MontanaBoard.swift MTBoard, MontanaBoardViews.swift MTFeedTabView) ───────────────────────────

/**
 * THE WALL, AS ITS VISITOR READS IT (iOS MTBoard, the author's word 24.09): a person's posts travel as the service word «WL:» in the
 * one pipe between two people, their files as sealed pieces on the node's blind store under keys that ride only inside the pipes.
 * The owner carries the page; a visitor asks for it when the version in the owner's presence word moved, or when the feed is looked
 * at and the page held is older than ten minutes (iOS heard, lookFeed). Any word of the wall from here tells the owner's build that
 * this phone reads the wall, and from then on it carries its page here (iOS learnCap). This phone's own wall is still empty: an ask
 * is answered with the page of an empty wall nobody may write on, which reads as a refusal reads (iOS refusedVersion).
 */
object Board {
    const val MARK = "​​WL:"
    private const val PAGE_SIZE = 30
    private const val COMMENTS_SHOWN = 20
    private const val TEXT_LIMIT = 4000
    private const val COMMENT_LIMIT = 1000
    private const val MEDIA_LIMIT = 10          // iOS MTPostMeasure.files
    private const val THUMB_LIMIT = 60_000
    private const val FACE_LIMIT = 16_000
    private const val FEED_SIZE = 300
    private const val FIRST_KEPT = 2000
    private const val LOOK_STALE = 600_000L
    private const val LOOK_BUDGET = 1_000_000_000L   // iOS MTBoardLook.budget: a gigabyte (MontanaBoard.swift:2414 at 2155)
    private const val LOOK_KEEP = 300           // iOS MTBoardLook.keep

    private val lock = Any()
    private var loaded = false
    private val pages = HashMap<String, JSONObject>()     // a wall's owner -> {w, ps, at, v}, as the owner last sent it
    private val firstSeen = HashMap<String, Long>()       // a post -> the moment this phone first held it (iOS held)
    private val askedAt = HashMap<String, Long>()
    private val answeredAt = HashMap<String, Long>()
    private val kept = HashMap<String, JSONObject>()      // a post -> {post, wall, files, dl}: its files kept here (iOS MTBoardKept 224-229)
    private val keeping = HashSet<String>()               // a Save on its way, once per post
    private val countedDownload = HashSet<String>()       // a download counted in this run (iOS countedDownload 375)
    private val goneSaid = HashSet<String>()              // a post whose lost files were said once in this run (iOS goneSaid 374)

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private fun store() = File(File(Book.ctx.filesDir, "board").apply { mkdirs() }, "pages.json")
    /** The person forgotten: the walls this phone held and the files brought for a look leave with them (Book.wipe). */
    fun wipe() = synchronized(lock) {
        MyWall.wipe()
        pages.clear(); firstSeen.clear(); askedAt.clear(); answeredAt.clear(); kept.clear(); keeping.clear(); countedDownload.clear(); goneSaid.clear(); loaded = false
        File(Book.ctx.filesDir, "board").deleteRecursively(); lookDir().deleteRecursively()
    }
    private fun ensure() {
        if (loaded) return
        loaded = true
        val o = runCatching { JSONObject(store().readText()) }.getOrNull() ?: return
        o.optJSONObject("pages")?.let { p -> p.keys().forEach { k -> p.optJSONObject(k)?.let { pages[k] = it } } }
        o.optJSONObject("first")?.let { f -> f.keys().forEach { k -> firstSeen[k] = f.optLong(k) } }
        o.optJSONObject("kept")?.let { k -> k.keys().forEach { id -> k.optJSONObject(id)?.let { kept[id] = it } } }
    }
    private fun save() {
        val p = JSONObject(); for ((k, v) in pages) p.put(k, v)
        val f = JSONObject(); firstSeen.entries.sortedByDescending { it.value }.take(FIRST_KEPT).forEach { f.put(it.key, it.value) }
        val k = JSONObject(); for ((id, v) in kept) k.put(id, v)
        val part = File(store().path + ".part")
        runCatching { part.writeText(JSONObject().put("pages", p).put("first", f).put("kept", k).toString()); part.renameTo(store()) }
    }

    /** iOS Announced.wireTag: the first four bytes of SHA-256 as a decimal number; nothing is «0». */
    fun wireTag(d: ByteArray?): String {
        if (d == null || d.isEmpty()) return "0"
        val h = Wire.sha(d)
        fun b(i: Int) = h[i].toLong() and 0xff
        return ((b(0) shl 24) or (b(1) shl 16) or (b(2) shl 8) or b(3)).toString()
    }
    private fun send(ref: String, w: JSONObject): Boolean = sendWord(ref, w) != null
    /** A word of the wall to a correspondent: the letter's name, by which its receipt is known (the page's «sent» is written by it). */
    fun sendWord(ref: String, w: JSONObject): String? {
        if (Groups.isKey(ref) || PeerSafety.isBlocked(ref) || Book.secret(ref) == null) return null
        val mid = Marks.mintMid()
        Post.send(ref, mid, MARK + w.toString())
        Log.d("Montana", "wall_tx t=" + w.optString("t") + " to=" + ref.take(10))
        return mid
    }

    /** The page of a correspondent's wall, asked of its owner — once a minute per wall at most (iOS ask). */
    fun ask(ref: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) { if (now - (askedAt[ref] ?: 0L) < 60_000L) return; askedAt[ref] = now }
        send(ref, JSONObject().put("t", "ask"))
    }
    private fun people() = Book.refs().filter { !Groups.isKey(it) && !PeerSafety.isBlocked(it) && SamePair.merged(it) == null }
    /** THE LOOK ASKS (iOS lookFeed, the author's word 25.09): every wall not held, or held from more than ten minutes ago. */
    fun lookFeed() {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { ensure(); people().filter { r -> now - (pages[r]?.optLong("at") ?: 0L) >= LOOK_STALE } }
        due.forEachIndexed { i, r -> MainThread.later(300L * i, Runnable { Thread { ask(r) }.start() }) }
    }
    /**
     * EVERY WALL NEVER SEEN IS ASKED AT ONCE (iOS sweep, MontanaBoard.swift:591-602 at 2155; ContentView.swift:474): at every return of
     * the app, every correspondent whose build speaks the wall and whose page never came from its owner -- a page of one post of mine
     * stood for the wall for ever -- is asked, a third of a second apart.
     */
    fun sweep() {
        val speakers = MyWall.speakers()
        val unknown = synchronized(lock) { ensure(); speakers.filter { r -> pages[r]?.has("v") != true } }.filter { Book.secret(it) != null }
        unknown.forEachIndexed { n, r -> MainThread.later(300L * n, Runnable { Thread { ask(r) }.start() }) }
    }

    /** iOS heardHeld's wallHeard: «W» and its digits after the name's tag and the bio's tag of a fresh presence word. */
    fun heardPresence(ref: String, payload: String) {
        val n = payload.indexOf('N'); if (n < 0) return
        var j = n + 1
        while (j < payload.length && payload[j].isDigit()) j++
        if (j < payload.length && payload[j] == 'A') { j++; while (j < payload.length && payload[j].isDigit()) j++ }
        if (j >= payload.length || payload[j] != 'W') return
        val v = payload.substring(j + 1).takeWhile { it.isDigit() }
        if (v.isEmpty()) return
        val pg = synchronized(lock) { ensure(); pages[ref] }
        if (v == "0") {   // a build of 1923–1925: an empty wall and a refusal alike; a wall never held is asked (iOS heard)
            if (pg == null) ask(ref)
            else if ((pg.optJSONArray("ps")?.length() ?: 0) > 0) { synchronized(lock) { pg.put("ps", JSONArray()); save() }; changed() }
            return
        }
        if (pg?.optString("v") != v) ask(ref)
    }

    /** A word of a wall (iOS MTBoard.handle): receipted as a letter, and never a row. */
    fun handle(ref: String, sid: String, text: String) {
        Post.receiptFor(ref, sid)
        if (PeerSafety.isBlocked(ref)) return
        val w = runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull() ?: return
        val t = w.optString("t")
        Log.d("Montana", "wall_rx t=" + t + " from=" + ref.take(10))
        MyWall.learnCap(ref)   // a word of the wall is the proof its build speaks it (iOS apply 812)
        val now = System.currentTimeMillis()
        when (t) {
            "page" -> {
                val src = w.optJSONArray("ps") ?: JSONArray()
                val fresh = (0 until minOf(src.length(), PAGE_SIZE)).mapNotNull { k -> src.optJSONObject(k)?.let { held(clean(it), now) } }
                // MY WORDS STAY ON MY SCREEN UNTIL THE OWNER'S PAGE CARRIES THEM (iOS keepMine, MontanaBoard.swift:984-999,
                // the author's word 25.09): a page fetched before the owner's own build echoes my latest act does not
                // take my own words off my own screen -- the owner's next page carries them for good.
                val oldPs = synchronized(lock) { ensure(); pages[ref]?.optJSONArray("ps") }
                val ps = JSONArray(); keepMine(oldPs, fresh, now).forEach { ps.put(it) }
                val pg = JSONObject().put("w", w.optBoolean("w", false)).put("ps", ps).put("at", now)
                if (w.has("v")) pg.put("v", w.optString("v"))
                synchronized(lock) { pages[ref] = pg; save() }
                Log.d("Montana", "wall_page posts=" + ps.length() + " from=" + ref.take(10))
                changed()
            }
            "no", "drop" -> w.optString("id").takeIf { it.isNotEmpty() }?.let { drop(ref, it) }
            "ask" -> MyWall.answer(ref)
            // THE OWNER ASKS THIS KEEPER TO LAY A POST'S FILES AGAIN (iOS apply «seed» 964-966): a post whose files this phone keeps
            "seed" -> w.optString("id").takeIf { it.isNotEmpty() }?.let { seedKept(it) }
            else -> MyWall.heard(ref, w)   // the owner's side: a post, a mark, a comment, a view on my wall
        }
    }

    private fun drop(ref: String, id: String) {
        forgetKept(id)   // iOS apply «no» 840, «drop» 891: a post off its wall is kept here no more
        synchronized(lock) {
            ensure()
            val pg = pages[ref] ?: return
            val ps = pg.optJSONArray("ps") ?: return
            val out = JSONArray()
            for (i in 0 until ps.length()) ps.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { out.put(it) }
            pg.put("ps", out); save()
        }
        changed()
    }

    /** A RECEIVED POST STANDS NO LATER THAN THE MOMENT THIS PHONE FIRST HELD IT (iOS held, 07.10): five minutes for the clocks. */
    private fun held(p: JSONObject, now: Long): JSONObject {
        val first = synchronized(lock) { firstSeen.getOrPut(p.optString("id")) { now } }
        p.put("at", minOf(p.optDouble("at", 0.0), first / 1000.0 + 300))
        return p
    }

    /** WHAT ARRIVES IS HELD TO ITS BOUNDS (iOS clean, the critic 24.09): a hostile page cannot fill the store. */
    private fun clean(p: JSONObject): JSONObject {
        val x = JSONObject().put("id", p.optString("id").take(64)).put("byName", p.optString("byName").take(64))
            .put("byGlyph", p.optString("byGlyph").take(8)).put("at", p.optDouble("at", 0.0))
            .put("text", p.optString("text").take(TEXT_LIMIT)).put("pinned", p.optBoolean("pinned"))
        for (k in listOf("keepers", "likes", "downloads", "reposts", "commentCount")) x.put(k, maxOf(0, p.optInt(k)))
        for (k in listOf("liked", "kept", "reposted", "mine", "own")) if (p.optBoolean(k)) x.put(k, true)
        if (p.has("views") && !p.isNull("views")) x.put("views", maxOf(0, p.optInt("views")))
        p.optString("from").takeIf { it.isNotEmpty() }?.let { x.put("from", it.take(64)) }
        p.optString("face").takeIf { it.isNotEmpty() && it.length <= FACE_LIMIT }?.let { x.put("face", it) }
        val md = JSONArray(); val ms = p.optJSONArray("media") ?: JSONArray()
        for (i in 0 until minOf(ms.length(), MEDIA_LIMIT)) ms.optJSONObject(i)?.let { m ->
            val y = JSONObject().put("kind", m.optString("kind")).put("name", m.optString("name").take(120))
                .put("ext", m.optString("ext").take(8).filter { it.isLetterOrDigit() }).put("size", m.optLong("size"))
                .put("key", m.optString("key")).put("chunks", m.optJSONArray("chunks") ?: JSONArray())
            m.optString("thumb").takeIf { i < 4 && it.isNotEmpty() && it.length <= THUMB_LIMIT }?.let { y.put("thumb", it) }
            if (m.has("dur")) y.put("dur", m.optDouble("dur"))
            m.optJSONObject("fr")?.let { y.put("fr", it) }
            md.put(y)
        }
        x.put("media", md)
        val cs = JSONArray(); val cm = p.optJSONArray("comments") ?: JSONArray()
        for (i in maxOf(0, cm.length() - COMMENTS_SHOWN) until cm.length()) cm.optJSONObject(i)?.let { c ->
            val y = JSONObject().put("id", c.optString("id")).put("by", c.optString("by").take(64)).put("glyph", c.optString("glyph").take(8))
                .put("text", c.optString("text").take(COMMENT_LIMIT)).put("at", c.optDouble("at", 0.0))
            c.optString("fc").takeIf { it.isNotEmpty() && it.length <= FACE_LIMIT }?.let { y.put("fc", it) }
            c.optString("h").takeIf { it.length == 64 }?.let { y.put("h", it) }
            c.optString("pv").takeIf { it.length == 64 }?.let { y.put("pv", it) }
            if (c.optBoolean("m")) y.put("m", true)
            if (c.optBoolean("o")) y.put("o", true)
            cs.put(y)
        }
        return x.put("comments", cs)
    }

    /** MY WORDS STAY ON MY SCREEN UNTIL THE OWNER'S PAGE CARRIES THEM (iOS keepMine, MontanaBoard.swift:984-999, the
     * author's word 25.09): a page fetched before the owner's own build echoes my latest act does not take my own words
     * off my own screen. My comments not yet on the owner's page stay as I sent them for half an hour, in the order
     * written; my own post not yet on the owner's page stays too, before the first post that is not pinned. */
    private fun keepMine(oldPs: JSONArray?, fresh: List<JSONObject>, now: Long): List<JSONObject> {
        if (oldPs == null || oldPs.length() == 0) return fresh
        val since = now / 1000.0 - 1800.0
        val out = fresh.toMutableList()
        for (i in 0 until oldPs.length()) {
            val o = oldPs.optJSONObject(i) ?: continue
            val idx = out.indexOfFirst { it.optString("id") == o.optString("id") }
            if (idx >= 0) {
                val dst = out[idx]
                val haveComments = dst.optJSONArray("comments") ?: JSONArray()
                val have = (0 until haveComments.length()).mapNotNull { haveComments.optJSONObject(it)?.optString("id") }.toSet()
                val oldComments = o.optJSONArray("comments") ?: JSONArray()
                val mine = (0 until oldComments.length()).mapNotNull { oldComments.optJSONObject(it) }
                    .filter { it.optBoolean("m") && it.optString("id") !in have && since < it.optDouble("at") }
                if (mine.isNotEmpty()) {
                    val all = ((0 until haveComments.length()).mapNotNull { haveComments.optJSONObject(it) } + mine).sortedBy { it.optDouble("at") }
                    dst.put("comments", JSONArray(all)).put("commentCount", dst.optInt("commentCount") + mine.size)
                }
            } else if (o.optBoolean("mine") && since < o.optDouble("at")) {
                val before = out.indexOfFirst { !it.optBoolean("pinned") }
                out.add(if (before == -1) out.size else before, o)
            }
        }
        return out
    }

    class Item(val wall: String, val post: JSONObject)
    /** THE FEED (iOS feed, the author's word 06.10 15:0x): every post of every wall held, each once, strictly by time, the newest on top. */
    fun feed(): List<Item> {
        val held = Book.refs().toSet()
        val own = MyWall.posts().map { Item("", it) }   // my own wall stands in the feed as every other (iOS feed 732)
        val all = own + synchronized(lock) {
            ensure()
            pages.entries.filter { it.key in held && !PeerSafety.isBlocked(it.key) }.flatMap { e ->
                val ps = e.value.optJSONArray("ps") ?: JSONArray()
                (0 until ps.length()).mapNotNull { ps.optJSONObject(it)?.let { p -> Item(e.key, p) } }
            }
        }
        val once = HashSet<String>()
        return all.filter { once.add(it.post.optString("id")) }
            .sortedWith(compareByDescending<Item> { it.post.optDouble("at") }.thenByDescending { it.post.optString("id") }).take(FEED_SIZE)
    }

    // ── the files of a post, brought for a look (iOS MTBoardLook) ──
    private val going = HashSet<String>()
    private fun lookDir() = File(Book.ctx.filesDir, "walllook").apply { mkdirs() }
    private fun fileName(pid: String, i: Int, ext: String) = "wall_" + pid + "_" + i + (if (ext.isEmpty()) "" else "." + ext)
    /** A picture of a post, brought from the node's pieces into the look's folder; the latest three hundred stay. */
    fun bring(m: JSONObject, pid: String, i: Int, then: (File) -> Unit) {
        val name = fileName(pid, i, m.optString("ext"))
        MyWall.ownFile(name)?.let { then(it); return }   // a post of mine, or one kept here: this phone is its peer, nothing to bring
        val dest = File(lookDir(), name)
        if (dest.exists()) { then(dest); return }
        val chunks = m.optJSONArray("chunks")
        if (m.optString("kind") != "img" || chunks == null || chunks.length() == 0) return
        synchronized(going) { if (!going.add(name)) return }
        Thread {
            try {
                if (Media.download(JSONObject().put("bk", m.optString("key")).put("chunks", chunks), dest)) {
                    Log.d("Montana", "wall_look id=" + pid.take(10) + " i=" + i + " bytes=" + dest.length())
                    trimLook()
                    MainThread.post { then(dest) }
                }
            } catch (e: Exception) { Log.w("Montana", "wall_look: " + e.javaClass.simpleName) }
            finally { synchronized(going) { going.remove(name) } }
        }.start()
    }
    /** THE FOLDER KEEPS THE LATEST SEEN, BY COUNT AND BY THE DISK THEY TAKE (iOS MTBoardLook.trim, MontanaBoard.swift:2480-2494 at
     * 2155): a gigabyte and three hundred files at most; the oldest go first. */
    private fun trimLook() {
        val all = lookDir().listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = all.sumOf { it.length() }
        var count = all.size
        for (f in all) {
            if (count <= LOOK_KEEP && total <= LOOK_BUDGET) break
            total -= f.length(); count--
            f.delete()
        }
    }

    // ── the keepers of a post (iOS keep, becomePeer, unkeep, file, noteDownloaded, reportGone, seedFiles, MontanaBoard 1585-1762) ──
    /**
     * SAVE (iOS keep 1585-1597, the author's word 24.09): the files come to this phone and stay -- from now on this phone is one of
     * the post's peers. True when every file of the post is here; one Save of a post at a time.
     */
    fun keep(wall: String, p: JSONObject): Boolean {
        val id = p.optString("id")
        if (!plain(id)) return false
        synchronized(lock) { if (!keeping.add(id)) return false }
        try {
            val ms = p.optJSONArray("media") ?: JSONArray()
            for (i in 0 until ms.length()) if (file(wall, p, i) == null) return false
            becomePeer(wall, p)   // a post with no files, or its files already here
            return true
        } finally { synchronized(lock) { keeping.remove(id) } }
    }
    /**
     * THE FILE OF A POST ON THIS PHONE (iOS file 1685-1721): kept under its wall name in the one store of the wall's files. The copy
     * a look brought is taken in -- what was seen is not brought again -- else the node's pieces are fetched. A file come here makes
     * this phone the post's peer; pieces gone at every door (iOS fetchChunks .lost, MontanaWakePush 2532-2538) are said to the owner.
     */
    fun file(wall: String, p: JSONObject, i: Int): File? {
        val m = p.optJSONArray("media")?.optJSONObject(i) ?: return null
        val id = p.optString("id")
        if (!plain(id)) return null
        val dest = MyWall.storeFile(fileName(id, i, m.optString("ext")))
        if (dest.exists()) return dest
        val seen = File(lookDir(), dest.name)
        val part = File(dest.path + ".part")
        val taken = seen.exists() && runCatching { seen.copyTo(part, overwrite = true) }.isSuccess && part.renameTo(dest)
        if (!taken) {
            val chunks = m.optJSONArray("chunks") ?: JSONArray()
            if (chunks.length() == 0) return null
            if (!Media.download(JSONObject().put("bk", m.optString("key")).put("chunks", chunks), dest)) {
                if ((0 until chunks.length()).any { k -> Wire.isGone(chunks.optJSONObject(k)?.optString("bid") ?: "") }) reportGone(wall, id)
                return null
            }
        }
        becomePeer(wall, p)
        noteDownloaded(wall, p)
        return dest
    }
    /**
     * A FILE FETCHED MAKES THIS PHONE THE POST'S PEER (iOS becomePeer 1598-1623, the author's word 24.09): what it holds of the post
     * stays, and the wall's owner is told it keeps the post. The writer keeps theirs from the start.
     */
    private fun becomePeer(wall: String, p: JSONObject) {
        val id = p.optString("id")
        val ms = p.optJSONArray("media") ?: JSONArray()
        val here = (0 until ms.length()).map { fileName(id, it, ms.optJSONObject(it)?.optString("ext") ?: "") }.filter { MyWall.storeFile(it).exists() }
        if (wall.isEmpty()) { MyWall.ownMark(id, "keepers", true); return }
        val tell = synchronized(lock) {
            ensure()
            val k = kept[id]
            if (k != null) {
                val had = k.optJSONArray("files") ?: JSONArray()
                val was = (0 until had.length()).map { had.optString(it) }.sorted()
                val all = (was + here).toSortedSet().toList()
                if (all == was) return
                k.put("files", JSONArray(all)); save()
                false
            } else {
                kept[id] = JSONObject().put("post", JSONObject(p.toString()).put("kept", true)).put("wall", wall).put("files", JSONArray(here))
                save()
                !p.optBoolean("mine")
            }
        }
        if (!tell) return
        send(wall, JSONObject().put("t", "keep").put("id", id).put("on", true))
        patch(wall, id) { x -> if (!x.optBoolean("kept")) { x.put("kept", true); x.put("keepers", x.optInt("keepers") + 1) } }
    }
    /** REMOVE FROM SAVED (iOS unkeep 1624-1630): the writer keeps their post while it stands; only the wall's owner takes it down. */
    fun unkeep(wall: String, p: JSONObject) {
        val id = p.optString("id")
        if (wall.isEmpty()) { MyWall.ownMark(id, "keepers", false); return }
        if (p.optBoolean("mine")) return
        forgetKept(id)
        send(wall, JSONObject().put("t", "keep").put("id", id).put("on", false))
        patch(wall, id) { x -> if (x.optBoolean("kept")) { x.remove("kept"); x.put("keepers", maxOf(0, x.optInt("keepers") - 1)) } }
    }
    /**
     * A POST NO LONGER KEPT HERE (iOS forgetKept 1667-1671): its files stay while my own wall still shows the post; else they go at
     * once -- iOS leaves them to the sweep of the files no post names (allFiles 509-521), and this phone has no such sweep.
     */
    private fun forgetKept(id: String) {
        val k = synchronized(lock) { ensure(); kept.remove(id)?.also { save() } } ?: return
        if (MyWall.post(id) != null) return
        val fs = k.optJSONArray("files") ?: return
        for (i in 0 until fs.length()) fs.optString(i).takeIf { it.isNotEmpty() }?.let { MyWall.storeFile(it).delete() }
    }
    /**
     * A DOWNLOAD IS COUNTED ONCE PER PERSON (iOS noteDownloaded 1722-1736, the author's word 24.09): when every file of the post has
     * come to this phone -- on the owner's wall, and on this page at once; one counted in a launch before this one is not counted again.
     */
    private fun noteDownloaded(wall: String, p: JSONObject) {
        val id = p.optString("id")
        val ms = p.optJSONArray("media") ?: return
        if (ms.length() == 0) return
        if (!(0 until ms.length()).all { MyWall.storeFile(fileName(id, it, ms.optJSONObject(it)?.optString("ext") ?: "")).exists() }) return
        synchronized(lock) { if (!countedDownload.add(id)) return }
        if (wall.isEmpty()) { MyWall.ownMark(id, "downloads", true); return }
        synchronized(lock) {
            ensure()
            val k = kept[id]
            if (k?.optBoolean("dl") == true) return   // counted in a launch before this one
            k?.put("dl", true); save()
        }
        send(wall, JSONObject().put("t", "dl").put("id", id))
        patch(wall, id) { x -> x.put("downloads", x.optInt("downloads") + 1) }
    }
    /** Pieces of a post gone at every door (iOS reportGone 1737-1743): said once to the wall's owner; on my own wall I lay them again. */
    private fun reportGone(wall: String, id: String) {
        synchronized(lock) { if (!goneSaid.add(id)) return }
        Log.d("Montana", "wall_gone id=" + id.take(10) + " wall=" + (if (wall.isEmpty()) "mine" else "theirs"))
        if (wall.isEmpty()) { MyWall.reseed(id); return }
        send(wall, JSONObject().put("t", "gone").put("id", id))
    }
    /** The wall's owner asks this keeper to lay a post's files on the node again (iOS apply «seed» 964-966): the post as it was kept. */
    private fun seedKept(id: String) {
        val media = synchronized(lock) { ensure(); kept[id]?.optJSONObject("post")?.optJSONArray("media") } ?: return
        Thread { seedFiles(id, media) }.start()
    }
    /**
     * A KEEPER LAYS THE FILES ON THE NODE AGAIN UNDER THE SAME KEY (iOS seedFiles 1752-1762): the same bytes sealed by the same key in
     * the same pieces give the same chunk names, so the manifest every page already carries finds them again.
     */
    fun seedFiles(id: String, media: JSONArray) {
        if (!plain(id)) return
        var n = 0
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val f = MyWall.storeFile(fileName(id, i, m.optString("ext")))
            val key = runCatching { Base64.decode(m.optString("key"), Base64.DEFAULT) }.getOrNull()
            if (!f.exists() || key == null || key.isEmpty()) continue
            if (Media.layFile(f, "", key) {} != null) n++
        }
        Log.d("Montana", "wall_seed id=" + id.take(10) + " files=" + n + "/" + media.length())
    }
    /** A post's name as a file's name: letters, digits and the dash -- a hostile page names no path (iOS names a post «p» and 24 hex). */
    private fun plain(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }

    // ── the visitor's marks (iOS like, view, comment) ──
    fun post(wall: String, id: String): JSONObject? = if (wall.isEmpty()) MyWall.post(id) else synchronized(lock) {
        ensure()
        val ps = pages[wall]?.optJSONArray("ps") ?: return null
        (0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }
    fun canWrite(wall: String) = wall.isEmpty() || synchronized(lock) { ensure(); pages[wall]?.optBoolean("w") == true }

    /** WHICH WALL HOLDS A POST, FOR A WALL'S LINK (iOS MTBoard.place(of:), MontanaBoard.swift:1431-1435): "" for a post of mine,
     * a correspondent's ref for a post on their page, null for a post this phone does not hold. */
    fun place(id: String): String? {
        if (MyWall.post(id) != null) return ""
        return synchronized(lock) {
            ensure()
            pages.entries.firstOrNull { (_, pg) -> (pg.optJSONArray("ps") ?: JSONArray()).let { ps -> (0 until ps.length()).any { i -> ps.optJSONObject(i)?.optString("id") == id } } }?.key
        }
    }
    /** A long wall link, read before every other link (iOS MontanaFirstContact.handleLink:1855, MTBoardComments.target:2421-2433). */
    class LinkTarget(val wall: String, val post: String, val comment: String?)
    fun isWallLink(link: String): Boolean = Meeting.normalize(link).startsWith("montana://wall/")
    /** The post and the comment a long wall link names, among the posts this phone holds; null for a post not held here, or for
     * the named-nick short form this phone does not resolve (iOS MTBoard.find(name:), not ported). */
    fun linkTarget(link: String): LinkTarget? {
        val parts = Meeting.normalize(link).removePrefix("montana://wall/").split("/").filter { it.isNotEmpty() }
        if (parts.size == 2 && parts[0] == "post") place(parts[1])?.let { return LinkTarget(it, parts[1], null) }
        if (parts.size == 3 && parts[0] == "comment") place(parts[1])?.let { return LinkTarget(it, parts[1], parts[2]) }
        return null
    }
    /** A WALL READ AS ITS OWNER SENT IT (iOS posts(on:), MontanaBoard.swift:723): every post of one person's own page, the
     * pinned first, then by the newest record of its chain -- its birth or its latest comment (iOS lastAt 1306), so a comment
     * of mine lifts its post here at once. */
    fun posts(wall: String): List<Item> = synchronized(lock) {
        ensure()
        val ps = pages[wall]?.optJSONArray("ps") ?: return emptyList()
        (0 until ps.length()).mapNotNull { ps.optJSONObject(it)?.let { p -> Item(wall, p) } }
            .sortedWith(compareByDescending<Item> { it.post.optBoolean("pinned") }.thenByDescending { lastAt(it.post) })
    }
    private fun lastAt(p: JSONObject): Double {
        val cs = p.optJSONArray("comments") ?: return p.optDouble("at", 0.0)
        return (0 until cs.length()).maxOfOrNull { cs.optJSONObject(it)?.optDouble("at", 0.0) ?: 0.0 }?.let { maxOf(it, p.optDouble("at", 0.0)) } ?: p.optDouble("at", 0.0)
    }
    /** THE LOOK ASKS (iOS look, MontanaBoard.swift:609-613, the author's word 25.09): a person's page not held, or held
     * more than ten minutes ago, is asked for the moment their page opens; a page already fresh is drawn at once and
     * nothing is sent. */
    fun look(ref: String) {
        val stale = synchronized(lock) { ensure(); val pg = pages[ref]; pg == null || System.currentTimeMillis() - pg.optLong("at") >= LOOK_STALE }
        if (stale) ask(ref)
    }
    /**
     * WHOSE POST IT IS, AS FAR AS THIS PHONE KNOWS (iOS writer(of:on:), MontanaBoard.swift:2137-2154 at 2155): "" -- mine, a reference
     * -- a correspondent's, null -- a writer this phone is not told of. On my wall its writer's reference is mine to read; on another's
     * the post is mine, the owner's own, or -- a repost -- its first writer's, where this phone holds the post as itself.
     */
    fun writer(wall: String, p: JSONObject): String? {
        if (wall.isEmpty()) return MyWall.writerOf(p.optString("id")) ?: if (p.optBoolean("mine")) "" else null
        if (p.has("from")) return if (p.optBoolean("own")) wall else originalWriter(p.optString("id"), wall)
        if (p.optBoolean("mine")) return ""
        return if (p.optBoolean("own")) wall else null
    }
    /** The post where it stands as itself -- my wall, else the first wall by its reference this phone holds -- and its writer there
     * (iOS originalWriter 2190-2194, original(of:besides:) 2182-2188). */
    private fun originalWriter(id: String, besides: String): String? {
        MyWall.writerOf(id)?.let { return it }
        val o = synchronized(lock) {
            ensure()
            pages.keys.sorted().filter { it != besides }.firstNotNullOfOrNull { w ->
                val ps = pages[w]?.optJSONArray("ps") ?: JSONArray()
                (0 until ps.length()).mapNotNull { ps.optJSONObject(it) }.firstOrNull { q -> q.optString("id") == id && !q.has("from") }?.let { q -> w to q }
            }
        } ?: return null
        return writer(o.first, o.second)
    }
    /** WHO WROTE A COMMENT, AS FAR AS THIS PHONE KNOWS (iOS commenter, MontanaBoard.swift:2195-2207 at 2155): on my wall its writer's
     * reference is mine to read; on another's the owner says which are this visitor's own («m») and which the owner's («o»). */
    fun commenter(wall: String, postId: String, k: JSONObject): String? {
        if (wall.isEmpty()) return MyWall.commenterOf(postId, k.optString("id"))
        if (k.optBoolean("m")) return ""
        return if (k.optBoolean("o")) wall else null
    }
    private fun patch(wall: String, id: String, change: (JSONObject) -> Unit) {
        synchronized(lock) {
            ensure()
            val ps = pages[wall]?.optJSONArray("ps") ?: return
            for (i in 0 until ps.length()) ps.optJSONObject(i)?.takeIf { it.optString("id") == id }?.let(change)
            save()
        }
        changed()
    }

    /** A like given or taken back (iOS like): the word to the wall's owner and the page here at once; the owner's next page says it exact. */
    fun like(wall: String, p: JSONObject) {
        if (wall.isEmpty()) { MyWall.like(p.optString("id")); return }   // my own wall: my own mark, the version moves
        val on = !p.optBoolean("liked")
        val id = p.optString("id")
        if (!send(wall, JSONObject().put("t", "like").put("id", id).put("on", on))) return
        patch(wall, id) { s -> if (on) s.put("liked", true) else s.remove("liked"); s.put("likes", maxOf(0, s.optInt("likes") + (if (on) 1 else -1))) }
    }

    /**
     * THE POSTS I SAW ARE NAMED TO THEIR WALL, EACH ONCE (iOS view and tellSeen, the author's words 06.10 00:4x): a post drawn on the
     * screen joins its wall's list of the moment, and two seconds later the list leaves as one word. A wall whose page counts no
     * views is told nothing; the number shown is the owner's alone.
     */
    private val seenDue = HashMap<String, MutableList<String>>()
    private val viewed: MutableList<String> by lazy { Prefs.str("board.viewed", "").split('\n').filter { it.isNotEmpty() }.toMutableList() }
    fun view(wall: String, p: JSONObject) {
        if (wall.isEmpty() || p.optBoolean("mine") || !p.has("views")) return
        val id = p.optString("id")
        val first = synchronized(lock) {
            if ((wall + "#" + id) in viewed || seenDue[wall]?.contains(id) == true) return
            val due = seenDue.getOrPut(wall) { mutableListOf() }
            due.add(id); due.size == 1
        }
        if (first) MainThread.later(2000, Runnable { Thread { tellSeen(wall) }.start() })
    }
    private fun tellSeen(wall: String) {
        val ids = synchronized(lock) { seenDue.remove(wall) } ?: return
        if (ids.isEmpty() || !send(wall, JSONObject().put("t", "view").put("vs", JSONArray(ids)))) return
        synchronized(lock) {
            viewed.addAll(ids.map { wall + "#" + it })
            while (viewed.size > 4000) viewed.removeAt(0)
            Prefs.setStr("board.viewed", viewed.joinToString("\n"))
        }
    }

    /** iOS seal: SHA-256 of the comment's name, its moment in UTC to the millisecond, the seal before it and its words. */
    private const val ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    fun seal(id: String, at: Double, prev: String, text: String): String =
        Wire.hex(Wire.sha((id + "\n" + stamp(at) + "\n" + prev + "\n" + text).toByteArray(Charsets.UTF_8)))
    private fun moment(at: Double, zone: java.util.TimeZone, pattern: String): String {
        var sec = at.toLong()
        var ms = ((at - sec) * 1000 + 0.5).toInt()
        if (ms == 1000) { ms = 0; sec += 1 }
        if (ms < 0) ms = 0
        val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = zone }
        return f.format(java.util.Date(sec * 1000)) + "." + ms.toString().padStart(3, '0')
    }
    fun stamp(at: Double) = moment(at, java.util.TimeZone.getTimeZone("UTC"), "yyyy-MM-dd'T'HH:mm:ss") + "Z"
    /** A comment's number in its chain, its moment on this phone's clock, then the prime sum of its own seal (iOS numberLine 1296-1302). */
    fun numberLine(n: Int, at: Double, h: String): String {
        val line = n.toString() + " · " + moment(at, java.util.TimeZone.getDefault(), "dd.MM.yyyy HH:mm:ss")
        return if (h.isEmpty()) line else line + " · gematria " + gematria(h)
    }
    private val PRIMES = intArrayOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109)
    /** iOS gematria (MontanaBoard 1266-1290): the seal read as one number, divided by 29 again and again, the prime of every remainder summed. */
    fun gematria(hex: String): Int {
        var chars = hex.lowercase().toList()
        if (chars.isEmpty()) return PRIMES[0]
        var total = 0
        while (true) {
            var rem = 0
            val next = ArrayList<Char>()
            var started = false
            for (ch in chars) {
                val v = Character.digit(ch, 16)
                if (v == -1) return total
                val cur = rem * 16 + v
                val q = cur / 29
                rem = cur % 29
                if (q != 0 || started) { next.add(Character.forDigit(q, 16)); started = true }
            }
            total += PRIMES[rem]
            if (next.isEmpty()) break
            chars = next
        }
        return total
    }

    /** My small face for a comment, as the commenter sends it (iOS commentFace): a JPEG under the bound every build keeps. */
    private fun commentFace(): String? {
        val raw = SelfFace.bytes(Book.ctx) ?: return null
        val b = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val side = 96
        val small = Bitmap.createScaledBitmap(b, side, maxOf(1, b.height * side / maxOf(1, b.width)), true)
        val out = java.io.ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).takeIf { it.length <= FACE_LIMIT }
    }

    /**
     * A COMMENT (iOS comment): sealed into the post's chain after the last seal this phone holds, sent to the wall's owner with
     * one coin when the book holds one (the author's words 04.10 02:50: four of five burn, the fifth to the post's writer), and
     * shown here at once as my own; the wall is asked again so the owner's page brings every comment.
     */
    fun comment(wall: String, p: JSONObject, text: String) {
        if (wall.isEmpty()) { MyWall.comment(p.optString("id"), text); return }
        val t = text.trim().take(COMMENT_LIMIT)
        if (t.isEmpty()) return
        val id = p.optString("id")
        val name = Prefs.userName.trim()
        val glyph = name.take(1).uppercase()
        val at = System.currentTimeMillis() / 1000.0
        val cid = java.util.UUID.randomUUID().toString().uppercase()
        val prev = post(wall, id)?.optJSONArray("comments")?.let { cs -> cs.optJSONObject(cs.length() - 1)?.optString("h")?.takeIf { it.isNotEmpty() } } ?: ZERO_HASH
        val h = seal(cid, at, prev, t)
        val face = commentFace()
        val r = java.util.UUID.randomUUID().toString().uppercase()
        val paid = CoinBook.spend(1L, "post:" + id, wall, "cmt:" + r)
        val w = JSONObject().put("t", "cmt").put("id", id).put("at", at).put("tx", t).put("bn", name).put("bg", glyph)
            .put("cid", cid).put("hh", h).put("pv", prev)
        if (face != null) w.put("fc", face)
        if (paid) w.put("c", 1).put("r", r)
        if (!send(wall, w)) return
        synchronized(lock) { askedAt[wall] = System.currentTimeMillis() }
        send(wall, JSONObject().put("t", "ask"))   // iOS askAfterAct: the owner answers with the page as it stands
        val said = JSONObject().put("id", cid).put("by", name).put("glyph", glyph).put("text", t).put("at", at).put("m", true).put("h", h).put("pv", prev)
        if (face != null) said.put("fc", face)
        patch(wall, id) { s -> (s.optJSONArray("comments") ?: JSONArray().also { s.put("comments", it) }).put(said); s.put("commentCount", s.optInt("commentCount") + 1) }
    }

    fun nameOf(c: Context, ref: String) = Book.chat(ref)?.name?.takeIf { it.isNotBlank() } ?: c.getString(R.string.wall_someone)
}

/** A picture of a post: its width the post's, its height by the frame's shape (iOS MTBoardFrame.held: 9:16 up to 16:9). */
private class WallTile(c: Context, private val shape: Float) : ImageView(c) {
    override fun onMeasure(w: Int, h: Int) {
        val wd = MeasureSpec.getSize(w)
        setMeasuredDimension(wd, (wd / shape.coerceIn(9f / 16f, 16f / 9f)).toInt())
    }
}

private fun poster(b64: String?): Bitmap? = b64?.takeIf { it.isNotEmpty() }?.let {
    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()?.let { d -> BitmapFactory.decodeByteArray(d, 0, d.size) }
}

/**
 * A POST'S POSTER, DECODED OFF THE MAIN THREAD AND KEPT (iOS MTBoardPoster, MontanaBoard.swift:2253-2288 at 2155): a tile decoded the
 * poster its post carries -- a JPEG of up to sixty thousand characters -- on the main thread at every drawing of the feed, and read
 * it whole again for its shape. Decoded once, off it, and kept by its own words within 32 MB; its shape is read from its header alone.
 */
private object WallPoster {
    private val pictures = object : android.util.LruCache<String, Bitmap>(32_000_000) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val shapes = android.util.LruCache<String, Float>(1024)
    private val work = java.util.concurrent.Executors.newFixedThreadPool(2)
    private fun key(b64: String) = b64.length.toString() + ":" + b64.hashCode()
    /** The poster when it is already decoded; null -- not yet, and nothing is decoded here. */
    fun kept(b64: String): Bitmap? = if (b64.isEmpty()) null else pictures.get(key(b64))
    fun decode(b64: String, then: (Bitmap) -> Unit) {
        if (b64.isEmpty()) return
        work.execute {
            val b = pictures.get(key(b64)) ?: poster(b64)?.also { pictures.put(key(b64), it) }
            if (b != null) MainThread.post { then(b) }
        }
    }
    /** A poster's shape, width over height, from its header: no pixel of it is decoded. */
    fun shape(b64: String): Float? {
        if (b64.isEmpty()) return null
        shapes.get(key(b64))?.let { return it }
        val d = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(d, 0, d.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return (o.outWidth.toFloat() / o.outHeight).also { shapes.put(key(b64), it) }
    }
}

/** WHAT WAS DRAWN SHARP STAYS DRAWN (iOS MTBoardPicture.cache, MontanaBoardViews.swift:944-948 at 2155): the pictures drawn for the
 * tiles within 48 MB, by their file, so a post drawn again is sharp on its first frame. */
private val wallSharp = object : android.util.LruCache<String, Bitmap>(48_000_000) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

/** WHERE A WRITER'S NAME LEADS (iOS MTBoardRoute and MTBoardOpen.go, MontanaBoardViews.swift:75-111 at 2155): mine to My page -- the
 * one the drawer's face opens, face and all -- never to the room of my saved letters; a correspondent's to their page. */
fun openWriter(act: MainActivity, w: String) {
    if (w.isEmpty()) act.push { close -> myPage(act, close) } else act.push { close -> peerInfoPage(act, w, close) }
}

/** ONE POST OF THE FEED (iOS MTBoardCell in MTFeedTabView): whose wall, the writer, the words, the pictures, the counts. */
fun postCell(act: MainActivity, it: Board.Item, whole: Boolean = false, onItsWall: Boolean = false, sending: Boolean = false): View = act.vstack(Gravity.NO_GRAVITY) {
    val c: Context = act
    val openComments = { act.push { close -> wallCommentsPage(act, it.wall, it.post.optString("id"), close) } }
    val p = it.post
    val own = p.optBoolean("own")
    // WHOSE WALL A POST STANDS ON, when its writer is not the wall's owner and the row is not drawn on that very wall's
    // own page (iOS onItsWall, MontanaBoardViews.swift: "Drawn on its wall's own page... in the feed, off it")
    if (!own && !onItsWall) addView(c.text(c.getString(R.string.wall_on, Board.nameOf(c, it.wall)), 12f, MT.gray).apply { setPadding(dp(4), 0, 0, dp(6)) }, lp())
    addView(c.vstack(Gravity.NO_GRAVITY) {
        // THE POST'S PLATE (iOS MTBoardPostPlate, MontanaBoardViews.swift:2548-2560 at 2155; BubbleShape 18, MontanaBubble.swift:62-69):
        // one-tone glass in a correspondent's bubble's own shape, under the glass's thin light rim -- the clear glass is the buttons'
        background = c.rounded(GLASS_TONE, 18, android.graphics.Color.argb(46, 255, 255, 255))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val name = p.optString("byName").ifEmpty { c.getString(R.string.wall_someone) }
        val face = if (own && it.wall.isEmpty()) SelfFace.load(c)   // my own wall: my own face
            else if (own) Book.face(it.wall).takeIf { f -> f.exists() }?.let { f -> BitmapFactory.decodeFile(f.path) } else poster(p.optString("face"))
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            // THE BYLINE OPENS ITS WRITER'S ONE PAGE (iOS MTBoardCell.header, MontanaBoardViews.swift:375-386 at 2155; MTBoardOpen 94-111):
            // the face, the name and when -- one target over its whole room; my name leads to My page, a correspondent's to theirs; a
            // writer this phone is not told of opens nothing, nor the wall's owner on their own wall's page
            val w = Board.writer(it.wall, p)
            val opens = when {
                w == null -> false
                w.isEmpty() -> it.wall.isNotEmpty() || !onItsWall   // in the feed my own post opens my page
                else -> w != it.wall || !onItsWall
            }
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                addView(c.avatar(face, name, 40), lp(dp(40), dp(40)))
                gap(10)
                addView(c.vstack(Gravity.NO_GRAVITY) {
                    addView(c.text(name, 15f, android.graphics.Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp())
                    val at = (p.optDouble("at") * 1000).toLong()
                    addView(c.text(android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), 60_000L), 12f, MT.gray), lp())
                }, lp(0, WRAP, 1f))
                if (opens && w != null) pressable { openWriter(act, w) }
            }, lp(0, WRAP, 1f))
            // THE POST'S OWN MENU (iOS MTBoardCell menuItems 404-441): on my own wall — pin or unpin, copy the words, delete; a wall's
            // owner alone takes a post off it
            if (it.wall.isEmpty() && !sending) addView(c.icon(R.drawable.ic_more_horiz, MT.gray).apply {
                setPadding(dp(11), dp(11), dp(11), dp(11))
                contentDescription = c.getString(R.string.pi_more)
                pressable {
                    val id = p.optString("id")
                    android.widget.PopupMenu(c, this).apply {
                        menu.add(0, 0, 0, if (p.optBoolean("pinned")) R.string.cl_unpin else R.string.cl_pin)
                        if (p.optString("text").isNotEmpty()) menu.add(0, 1, 1, R.string.copy)
                        menu.add(0, 2, 2, R.string.delete)
                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                0 -> Thread { MyWall.pin(id) }.start()
                                1 -> c.getSystemService(android.content.ClipboardManager::class.java)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("", p.optString("text")))   // USER-DATA: the post's words
                                2 -> Thread { MyWall.remove(id) }.start()
                            }
                            true
                        }
                    }.show()
                }
            }, lp(dp(44), dp(44)))
        }, lp())
        p.optString("from").takeIf { f -> f.isNotEmpty() }?.let { f ->
            gap(8); addView(c.text(c.getString(R.string.wall_reposted, f), 12f, MT.gray), lp())
        }
        val words = p.optString("text")
        // EVERY MONTANA LINK OPENS AT A TOUCH (iOS MTPostTextView.lay, MontanaBoardViews.swift:2662-2666, atom 20c5cef6f490):
        // the web's own finder and a Montana link alike, in the platform's own blue (iOS words.tintColor, MontanaBoardViews.swift:2632).
        if (words.isNotEmpty()) {
            gap(10)
            addView(c.text(linkedWords(words) { u -> act.openLink(u.toString()) }, 16f).apply {
                setTextIsSelectable(false)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setLinkTextColor(MT.blue)
            }, lp())
        }
        val media = p.optJSONArray("media") ?: JSONArray()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val kind = m.optString("kind")
            if (kind != "img" && kind != "vid") continue
            val thumb = m.optString("thumb")
            val fr = m.optJSONObject("fr")
            // THE SHAPE FROM THE FRAME, ELSE FROM THE POSTER'S HEADER ALONE (iOS MTBoardPoster.shape, MontanaBoard.swift:2277-2287 at 2155)
            val shape = fr?.optDouble("a", 0.0)?.toFloat()?.takeIf { s -> s > 0f } ?: WallPoster.shape(thumb) ?: 1f
            gap(10)
            val posterHeld = WallPoster.kept(thumb)
            var sharp = false
            val tile = WallTile(c, shape).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = c.rounded(MT.hairline, 12)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                posterHeld?.let { b -> setImageBitmap(b) }
            }
            addView(tile, lp())
            // THE SHARP PICTURE, ELSE THE POSTER DECODED OFF THE MAIN THREAD, ELSE THE TILE'S GREY (iOS MTBoardPicture, MontanaBoardViews.swift:
            // 926-941 at 2155); what was drawn sharp stands sharp on the first frame of the next drawing (iOS MTBoardPicture.cache 944-948)
            if (posterHeld == null) WallPoster.decode(thumb) { b -> if (!sharp) tile.setImageBitmap(b) }
            Board.bring(m, p.optString("id"), i) { f ->
                val held = wallSharp.get(f.path)
                if (held != null) { sharp = true; tile.setImageBitmap(held) }
                else Thread { Media.preview(c, f, kind, 1600)?.let { b -> wallSharp.put(f.path, b); MainThread.post { sharp = true; tile.setImageBitmap(b) } } }.start()
            }
        }
        // THE COUNTS (iOS counters 445-487): like, comments, keepers, reposts; in the corner the downloads and the eye with the views
        // where the wall's owner counts them. Reposting comes with this phone's own wall (W3): its count stands.
        if (sending) return@vstack
        gap(4)
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            fun mark(glyph: Int, n: Int, tint: Int, words: Int, onTap: (() -> Unit)?) = addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = c.getString(words)
                addView(c.icon(glyph, tint, 18)); gap(4)
                addView(c.text(n.toString(), 13f, MT.gray, bold = true))
                if (onTap != null) pressable(onTap)
            }, lp(WRAP, dp(44)).apply { marginEnd = dp(14) })
            val liked = p.optBoolean("liked")
            mark(if (liked) R.drawable.ic_heart_fill else R.drawable.ic_heart, p.optInt("likes"), if (liked) MT.red else MT.gray, R.string.wall_like) { Board.like(it.wall, p) }
            mark(R.drawable.ic_set_textbubble, p.optInt("commentCount"), MT.gray, R.string.wall_comments, if (whole) null else openComments)
            // THE POST'S PEERS ANSWER THE FINGER (iOS counters 452-454): a tap saves the post -- its files come here and stay -- or lets it go
            mark(R.drawable.ic_tray_down, p.optInt("keepers"), if (p.optBoolean("kept")) android.graphics.Color.WHITE else MT.gray, R.string.wall_keepers) {
                Thread { if (p.optBoolean("kept")) Board.unkeep(it.wall, p) else Board.keep(it.wall, p) }.start()
            }
            mark(R.drawable.ic_repost, p.optInt("reposts"), if (p.optBoolean("reposted")) android.graphics.Color.WHITE else MT.gray, R.string.wall_repost, null)
            spacer()
            addView(c.icon(R.drawable.ic_arrow_circle_down, MT.gray, 16).apply { contentDescription = c.getString(R.string.wall_downloads) }); gap(4)
            addView(c.text(p.optInt("downloads").toString(), 13f, MT.gray, bold = true))
            if (p.has("views")) {
                gap(12)
                addView(c.icon(R.drawable.ic_set_eye, MT.gray, 16).apply { contentDescription = c.getString(R.string.wall_views) }); gap(4)
                addView(c.text(p.optInt("views").toString(), 13f, MT.gray, bold = true))
            }
        }, lp())
        if (!whole) pressable(openComments)
    }, lp())
    // A POST ON THE SCREEN IS A POST SEEN (iOS MTBoardCell.onAppear, 06.10): its wall is told once
    if (!sending) addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { Board.view(it.wall, it.post) }
        override fun onViewDetachedFromWindow(v: View) {}
    })
}

/**
 * THE COMMENTS OF A POST (iOS MTBoardComments 2246-2440): the post whole on top, its chain newest first, each comment under its
 * writer's head with its number in the chain and its moment; the field below for one who may write on the wall, else the words
 * that say who may.
 */
fun wallCommentsPage(act: MainActivity, wall: String, id: String, onClose: () -> Unit, focus: String? = null): View {
    val c: Context = act
    val body = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(12), 0, dp(12), dp(16)) }
    val scroll = ScrollView(c)
    // A COMMENT'S OWN LINK SCROLLS TO IT, ONCE (iOS MTBoardComments's ScrollViewReader.onAppear, MontanaBoardViews.swift:1591, 2155
    // build): montana://wall/comment opens this same page already drawn to its one comment.
    var scrolled = focus == null
    fun draw() {
        body.removeAllViews()
        val p = Board.post(wall, id) ?: return
        body.addView(postCell(act, Board.Item(wall, p), whole = true), lp().apply { setMargins(0, c.dp(6), 0, c.dp(10)) })
        val cs = p.optJSONArray("comments") ?: JSONArray()
        if (cs.length() == 0) { body.addView(c.text(c.getString(R.string.wall_no_comments), 15f, MT.gray), lp()); return }
        val base = p.optInt("commentCount") - cs.length()
        for (i in cs.length() - 1 downTo 0) {
            val k = cs.optJSONObject(i) ?: continue
            val name = k.optString("by").ifEmpty { c.getString(R.string.wall_someone) }
            val face = when {
                k.optBoolean("m") -> SelfFace.bytes(c)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) }
                k.optBoolean("o") -> Book.face(wall).takeIf { f -> f.exists() }?.let { f -> BitmapFactory.decodeFile(f.path) }
                else -> null
            } ?: poster(k.optString("fc"))
            body.addView(c.vstack(Gravity.NO_GRAVITY) {
                tag = k.optString("id")
                setPadding(0, dp(10), 0, dp(6))
                addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(c.avatar(face, name, 36), lp(dp(36), dp(36))); gap(10)
                    addView(c.text(name, 15f, android.graphics.Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))
                    // A COMMENTER'S HEAD OPENS THEIR PAGE (iOS MTBoardComments.row, MontanaBoardViews.swift:2338-2349 at 2155): me, the wall's
                    // owner, and on my own wall every commenter -- the face and the name one target
                    Board.commenter(wall, id, k)?.let { w -> minimumHeight = dp(44); pressable { openWriter(act, w) } }
                }, lp())
                val words = k.optString("text")
                // EVERY MONTANA LINK OPENS AT A TOUCH (iOS MTBoard.linked, MontanaBoard.swift:1491-1493, atom 20c5cef6f490):
                // the web's own finder and a Montana link alike, in the system's blue.
                if (words.isNotEmpty()) addView(c.text(linkedWords(words) { u -> act.openLink(u.toString()) }, 16f).apply {
                    setPadding(dp(46), dp(2), 0, 0)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    setLinkTextColor(MT.blue)
                }, lp())
                addView(c.text(Board.numberLine(base + i + 1, k.optDouble("at"), k.optString("h")), 11f, MT.gray).apply { setPadding(dp(46), dp(2), 0, 0) }, lp())
            }, lp())
        }
        if (!scrolled) {
            scrolled = true
            scroll.post {
                val target = (0 until body.childCount).map { body.getChildAt(it) }.firstOrNull { it.tag == focus }
                if (target != null) scroll.scrollTo(0, maxOf(0, target.top - (scroll.height - target.height) / 2))
            }
        }
    }
    val field = android.widget.EditText(c).apply {
        hint = c.getString(R.string.wall_write_comment); setTextColor(android.graphics.Color.WHITE); setHintTextColor(MT.gray)
        background = c.rounded(android.graphics.Color.rgb(38, 38, 38), 18); setPadding(dp(12), dp(10), dp(12), dp(10)); maxLines = 5
    }
    val foot: View = if (Board.canWrite(wall)) c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(field, lp(0, WRAP, 1f)); gap(8)
        addView(c.icon(R.drawable.ic_arrow_circle_up, android.graphics.Color.WHITE, 30).apply {
            contentDescription = c.getString(R.string.wall_send)
            pressable {
                val p = Board.post(wall, id) ?: return@pressable
                Thread { Board.comment(wall, p, field.text.toString()) }.start()
                field.setText("")
            }
        }, lp(dp(44), dp(44)))
    } else c.text(c.getString(R.string.wall_only_writers), 13f, MT.gray, center = true).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
    val bar = FrameLayout(c).apply {
        addView(c.icon(R.drawable.ic_close, android.graphics.Color.WHITE).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); pressable(onClose) },
            FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = c.dp(8) })
        addView(c.text(c.getString(R.string.wall_comments), 17f, android.graphics.Color.WHITE, bold = true, center = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }
    val again: () -> Unit = { draw() }
    return FrameLayout(c).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(bar, lp(MATCH, dp(52)))
            addView(scroll.apply { addView(body) }, lp(MATCH, 0, 1f))
            addView(foot, lp())
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { Board.listen(again); draw() }
            override fun onViewDetachedFromWindow(v: View) { Board.unlisten(again) }
        })
    }
}

/**
 * THE FEED under the logo (iOS MTFeedTabView): every post on my people's walls this phone holds, the newest on top; «Posts on your
 * people's walls will appear here» while there is none. The moment the page is looked at, every wall it draws is asked for (iOS
 * mtPaneLive, lookFeed).
 */
class FeedPane(private val act: MainActivity) : FrameLayout(act) {
    private val c: Context = act
    private val column = c.vstack(Gravity.NO_GRAVITY)
    private val empty = c.text(c.getString(R.string.no_posts), 17f, MT.gray, center = true)
    private val again: () -> Unit = { if (isShown) draw() }
    init {
        addView(ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(c.vstack(Gravity.NO_GRAVITY) { addView(column, lp()); gap(120) })
        }, LayoutParams(MATCH, MATCH))
        addView(empty, LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }
    private fun draw() {
        val items = Board.feed()
        column.removeAllViews()
        for (it in items) column.addView(postCell(act, it), lp().apply { setMargins(dp(12), dp(6), dp(12), dp(6)) })
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Board.listen(again); draw() }
    override fun onDetachedFromWindow() { Board.unlisten(again); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(v: View, visibility: Int) {
        super.onVisibilityChanged(v, visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow) { Board.lookFeed(); draw() }
    }
}
