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
    private const val LOOK_KEEP = 300           // iOS MTBoardLook.keep

    private val lock = Any()
    private var loaded = false
    private val pages = HashMap<String, JSONObject>()     // a wall's owner -> {w, ps, at, v}, as the owner last sent it
    private val firstSeen = HashMap<String, Long>()       // a post -> the moment this phone first held it (iOS held)
    private val askedAt = HashMap<String, Long>()
    private val answeredAt = HashMap<String, Long>()

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private fun store() = File(File(Book.ctx.filesDir, "board").apply { mkdirs() }, "pages.json")
    private fun ensure() {
        if (loaded) return
        loaded = true
        val o = runCatching { JSONObject(store().readText()) }.getOrNull() ?: return
        o.optJSONObject("pages")?.let { p -> p.keys().forEach { k -> p.optJSONObject(k)?.let { pages[k] = it } } }
        o.optJSONObject("first")?.let { f -> f.keys().forEach { k -> firstSeen[k] = f.optLong(k) } }
    }
    private fun save() {
        val p = JSONObject(); for ((k, v) in pages) p.put(k, v)
        val f = JSONObject(); firstSeen.entries.sortedByDescending { it.value }.take(FIRST_KEPT).forEach { f.put(it.key, it.value) }
        val part = File(store().path + ".part")
        runCatching { part.writeText(JSONObject().put("pages", p).put("first", f).toString()); part.renameTo(store()) }
    }

    /** iOS Announced.wireTag: the first four bytes of SHA-256 as a decimal number; nothing is «0». */
    fun wireTag(d: ByteArray?): String {
        if (d == null || d.isEmpty()) return "0"
        val h = Wire.sha(d)
        fun b(i: Int) = h[i].toLong() and 0xff
        return ((b(0) shl 24) or (b(1) shl 16) or (b(2) shl 8) or b(3)).toString()
    }
    private val emptyVersion by lazy { wireTag("0r".toByteArray()) }

    private fun send(ref: String, w: JSONObject) {
        if (Groups.isKey(ref) || PeerSafety.isBlocked(ref) || Book.secret(ref) == null) return
        Post.send(ref, Marks.mintMid(), MARK + w.toString())
        Log.d("Montana", "wall_tx t=" + w.optString("t") + " to=" + ref.take(10))
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
        val now = System.currentTimeMillis()
        when (t) {
            "page" -> {
                val src = w.optJSONArray("ps") ?: JSONArray()
                val ps = JSONArray()
                for (k in 0 until minOf(src.length(), PAGE_SIZE)) src.optJSONObject(k)?.let { ps.put(held(clean(it), now)) }
                val pg = JSONObject().put("w", w.optBoolean("w", false)).put("ps", ps).put("at", now)
                if (w.has("v")) pg.put("v", w.optString("v"))
                synchronized(lock) { ensure(); pages[ref] = pg; save() }
                Log.d("Montana", "wall_page posts=" + ps.length() + " from=" + ref.take(10))
                changed()
            }
            "no", "drop" -> w.optString("id").takeIf { it.isNotEmpty() }?.let { drop(ref, it) }
            "ask" -> {
                synchronized(lock) { if (now - (answeredAt[ref] ?: 0L) < 30_000L) return; answeredAt[ref] = now }
                send(ref, JSONObject().put("t", "page").put("w", false).put("ps", JSONArray()).put("v", emptyVersion))
            }
            else -> {}   // the owner's side (post, like, cmt, view…) comes with this phone's own wall
        }
    }

    private fun drop(ref: String, id: String) {
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
            if (c.optBoolean("m")) y.put("m", true)
            if (c.optBoolean("o")) y.put("o", true)
            cs.put(y)
        }
        return x.put("comments", cs)
    }

    class Item(val wall: String, val post: JSONObject)
    /** THE FEED (iOS feed, the author's word 06.10 15:0x): every post of every wall held, each once, strictly by time, the newest on top. */
    fun feed(): List<Item> {
        val held = Book.refs().toSet()
        val all = synchronized(lock) {
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
        val dest = File(lookDir(), name)
        if (dest.exists()) { then(dest); return }
        val chunks = m.optJSONArray("chunks")
        if (m.optString("kind") != "img" || chunks == null || chunks.length() == 0) return
        synchronized(going) { if (!going.add(name)) return }
        Thread {
            try {
                if (Media.download(JSONObject().put("bk", m.optString("key")).put("chunks", chunks), dest)) {
                    Log.d("Montana", "wall_look id=" + pid.take(10) + " i=" + i + " bytes=" + dest.length())
                    lookDir().listFiles()?.sortedByDescending { it.lastModified() }?.drop(LOOK_KEEP)?.forEach { it.delete() }
                    MainThread.post { then(dest) }
                }
            } catch (e: Exception) { Log.w("Montana", "wall_look: " + e.javaClass.simpleName) }
            finally { synchronized(going) { going.remove(name) } }
        }.start()
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

/** ONE POST OF THE FEED (iOS MTBoardCell in MTFeedTabView): whose wall, the writer, the words, the pictures, the counts. */
private fun postCell(c: Context, it: Board.Item): View = c.vstack(Gravity.NO_GRAVITY) {
    val p = it.post
    val own = p.optBoolean("own")
    // WHOSE WALL A POST STANDS ON, when its writer is not the wall's owner (iOS wallLink)
    if (!own) addView(c.text(c.getString(R.string.wall_on, Board.nameOf(c, it.wall)), 12f, MT.gray).apply { setPadding(dp(4), 0, 0, dp(6)) }, lp())
    addView(c.vstack(Gravity.NO_GRAVITY) {
        background = c.rounded(MT.plate, 16)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        val name = p.optString("byName").ifEmpty { c.getString(R.string.wall_someone) }
        val face = if (own) Book.face(it.wall).takeIf { f -> f.exists() }?.let { f -> BitmapFactory.decodeFile(f.path) } else poster(p.optString("face"))
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(c.avatar(face, name, 40), lp(dp(40), dp(40)))
            gap(10)
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(name, 15f, android.graphics.Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp())
                val at = (p.optDouble("at") * 1000).toLong()
                addView(c.text(android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), 60_000L), 12f, MT.gray), lp())
            }, lp(0, WRAP, 1f))
        }, lp())
        p.optString("from").takeIf { f -> f.isNotEmpty() }?.let { f ->
            gap(8); addView(c.text(c.getString(R.string.wall_reposted, f), 12f, MT.gray), lp())
        }
        val words = p.optString("text")
        if (words.isNotEmpty()) { gap(10); addView(c.text(words, 16f).apply { setTextIsSelectable(false) }, lp()) }
        val media = p.optJSONArray("media") ?: JSONArray()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val kind = m.optString("kind")
            if (kind != "img" && kind != "vid") continue
            val small = poster(m.optString("thumb"))
            val fr = m.optJSONObject("fr")
            val shape = fr?.optDouble("a", 0.0)?.toFloat()?.takeIf { s -> s > 0f } ?: small?.let { b -> b.width.toFloat() / maxOf(1, b.height) } ?: 1f
            gap(10)
            val tile = WallTile(c, shape).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = c.rounded(MT.hairline, 12)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                small?.let { b -> setImageBitmap(b) }
            }
            addView(tile, lp())
            Board.bring(m, p.optString("id"), i) { f -> Thread { Media.preview(c, f, "img", 1600)?.let { b -> MainThread.post { tile.setImageBitmap(b) } } }.start() }
        }
        // THE COUNTS (iOS counters): the comments, and the eye with the views where the wall's owner counts them
        gap(10)
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(c.icon(R.drawable.ic_set_textbubble, MT.gray, 18)); gap(4)
            addView(c.text(p.optInt("commentCount").toString(), 13f, MT.gray, bold = true))
            spacer()
            if (p.has("views")) {
                addView(c.icon(R.drawable.ic_set_eye, MT.gray, 18)); gap(4)
                addView(c.text(p.optInt("views").toString(), 13f, MT.gray, bold = true))
            }
        }, lp())
    }, lp())
}

/**
 * THE FEED under the logo (iOS MTFeedTabView): every post on my people's walls this phone holds, the newest on top; «Posts on your
 * people's walls will appear here» while there is none. The moment the page is looked at, every wall it draws is asked for (iOS
 * mtPaneLive, lookFeed).
 */
class FeedPane(c: Context) : FrameLayout(c) {
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
        for (it in items) column.addView(postCell(context, it), lp().apply { setMargins(dp(12), dp(6), dp(12), dp(6)) })
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Board.listen(again); draw() }
    override fun onDetachedFromWindow() { Board.unlisten(again); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(v: View, visibility: Int) {
        super.onVisibilityChanged(v, visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow) { Board.lookFeed(); draw() }
    }
}
