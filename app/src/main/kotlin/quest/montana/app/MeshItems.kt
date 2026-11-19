package quest.montana.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * THE MESH WALL'S PICTURES, VOICES, FILES AND STICKERS, part 3b (iOS MTMeshRoom media extension, MontanaBLEMesh 1008-1350; the
 * author's word 29.09: «everything goes into the common mesh room as in any chat»). An item rides as its word (cell 7, version 2)
 * and its pieces (cell 8), each passed on once by every neighbour; a neighbour that missed pieces asks for them (cell 9) and whoever
 * holds the item whole answers. The radio carries kilobytes a second, so an item is at most 256 KB: a picture is made that small,
 * the rest leaves as it is or not at all.
 *   word:  [ver 2 | run 8 | at 4 | nl | name | kind 1 | item 16 | size 4 | pieces 2 | sha 32 | el | ext | fl | file | dur ms 4 | caption] 0
 *   piece: [1 | item 16 | index 2 | count 2 | bytes <= 960] 0          ask: [1 | item 16 | n <= 40 | index 2 x n] 0
 */
object MeshItems {
    private const val PIECE_BYTES = 960
    const val ITEM_MAX = 256 * 1024
    private const val PIECES_MAX = (ITEM_MAX + PIECE_BYTES - 1) / PIECE_BYTES
    private const val PIECE_GAP_MS = 150L                   // about six kilobytes a second of one's own item
    private const val PASS_BYTES_PER_MINUTE = 384 * 1024    // the pieces this phone passes on per minute
    private const val HELD_FOR = 600_000L                   // an item whole answers asks for ten minutes
    private const val HELD_BYTES = 4 * 1024 * 1024          // the bytes kept whole to answer asks
    private const val GATHER_FOR = 180_000L                 // an item not whole in three minutes is let go
    private const val GATHER_MAX = 8
    private const val ASK_EVERY = 5_000L
    private const val ASKS_MAX = 6

    class Item(val kind: String, val item: ByteArray, val size: Int, val pieces: Int, val sha: ByteArray,
               val ext: String, val fileName: String, val durMs: Long)
    private class Gathering(var word: MeshRoom.Word?, var item: Item?, val got: HashMap<Int, ByteArray>, val first: Long, var last: Long, var asks: Int)
    private class Held(val word: MeshRoom.Word, val item: Item, val data: ByteArray, val at: Long)
    private class Piece(val item: ByteArray, val index: Int, val count: Int, val bytes: ByteArray)

    private val lock get() = MeshRoom.lock
    private val gathering = HashMap<String, Gathering>()
    private val held = HashMap<String, Held>()
    private val passedBytes = ArrayList<Pair<Long, Int>>()
    private val answeredAt = HashMap<String, Long>()
    private var repair: ScheduledFuture<*>? = null
    private val queue = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mesh-room-items") }

    /** A bounded reader of a cell's body: every read says whether its bytes are there (iOS Reader). */
    private class Reader(val d: ByteArray) {
        var at = 0
        fun bytes(n: Int): ByteArray? { if (n < 0 || at + n > d.size) return null; val r = d.copyOfRange(at, at + n); at += n; return r }
        fun u8(): Int? = bytes(1)?.let { it[0].toInt() and 0xff }
        fun u16(): Int? = bytes(2)?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
        fun u32(): Long? = bytes(4)?.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
        fun rest(): ByteArray = d.copyOfRange(at, d.size)
    }
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun kindByte(k: String): Byte = when (k) { "img" -> 0x69; "vid" -> 0x76; "aud" -> 0x61; else -> 0x64 }
    private fun kindOf(b: Int) = when (b) { 0x69 -> "img"; 0x76 -> "vid"; 0x61 -> "aud"; else -> "doc" }
    private fun pieceCount(size: Int) = (size + PIECE_BYTES - 1) / PIECE_BYTES
    private fun random(n: Int) = MtBindings.nativeRandom(n) ?: ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    fun mediaCell(w: MeshRoom.Word, m: Item): ByteArray {
        val name = MeshRoom.cut(w.name, MeshRoom.NAME_BYTES).toByteArray(); val cap = MeshRoom.cut(w.text, MeshRoom.WORD_BYTES).toByteArray()
        val ext = MeshRoom.cut(m.ext, 8).toByteArray(); val file = MeshRoom.cut(m.fileName, 120).toByteArray()
        return byteArrayOf(MeshRoom.MEDIA_VERSION) + w.run.copyOf(8) + MeshRoom.be32(w.at) + byteArrayOf(name.size.toByte()) + name +
            byteArrayOf(kindByte(m.kind)) + m.item.copyOf(16) + MeshRoom.be32(m.size.toLong()) + be16(m.pieces) + m.sha.copyOf(32) +
            byteArrayOf(ext.size.toByte()) + ext + byteArrayOf(file.size.toByte()) + file + MeshRoom.be32(m.durMs) + cap + byteArrayOf(0)
    }

    /** The item's word, or null for another shape, a size past the measure, or pieces that do not match the size (iOS media(ofBody:)). */
    fun media(body: ByteArray): Pair<MeshRoom.Word, Item>? {
        if (body.lastOrNull() != 0.toByte()) return null
        val r = Reader(body.copyOf(body.size - 1))
        if (r.u8() != MeshRoom.MEDIA_VERSION.toInt()) return null
        val run = r.bytes(8) ?: return null; val at = r.u32() ?: return null
        val nl = r.u8() ?: return null; val nd = r.bytes(nl) ?: return null
        val k = r.u8() ?: return null; val item = r.bytes(16) ?: return null; val size = r.u32() ?: return null
        val pieces = r.u16() ?: return null; val sha = r.bytes(32) ?: return null
        val el = r.u8() ?: return null; val ed = r.bytes(el) ?: return null
        val fl = r.u8() ?: return null; val fd = r.bytes(fl) ?: return null; val dur = r.u32() ?: return null
        val name = MeshRoom.utf8(nd) ?: return null; val ext = MeshRoom.utf8(ed) ?: return null
        val file = MeshRoom.utf8(fd) ?: return null; val capB = r.rest(); val cap = MeshRoom.utf8(capB) ?: return null
        val n = size.toInt()
        if (size <= 0 || size > ITEM_MAX || pieces != pieceCount(n) || nl > MeshRoom.NAME_BYTES || capB.size > MeshRoom.WORD_BYTES) return null
        return MeshRoom.Word(run, at, name, cap) to Item(kindOf(k), item, n, pieces, sha, ext, file, dur)
    }

    fun pieceCell(item: ByteArray, index: Int, count: Int, bytes: ByteArray) =
        byteArrayOf(1) + item.copyOf(16) + be16(index) + be16(count) + bytes + byteArrayOf(0)

    private fun piece(body: ByteArray): Piece? {
        if (body.lastOrNull() != 0.toByte()) return null
        val r = Reader(body.copyOf(body.size - 1))
        if (r.u8() != 1) return null
        val item = r.bytes(16) ?: return null; val i = r.u16() ?: return null; val n = r.u16() ?: return null
        val bytes = r.rest()
        if (n <= 0 || n > PIECES_MAX || i >= n || bytes.isEmpty() || bytes.size > PIECE_BYTES) return null
        return Piece(item, i, n, bytes)
    }

    fun askCell(item: ByteArray, missing: List<Int>): ByteArray {
        val list = missing.take(40)
        var d = byteArrayOf(1) + item.copyOf(16) + byteArrayOf(list.size.toByte())
        for (i in list) d += be16(i)
        return d + byteArrayOf(0)
    }

    private fun ask(body: ByteArray): Pair<ByteArray, List<Int>>? {
        if (body.lastOrNull() != 0.toByte()) return null
        val r = Reader(body.copyOf(body.size - 1))
        if (r.u8() != 1) return null
        val item = r.bytes(16) ?: return null; val n = r.u8() ?: return null
        if (n <= 0 || n > 40) return null
        val out = ArrayList<Int>()
        repeat(n) { out.add(r.u16() ?: return null) }
        return item to out
    }

    private fun bytesOfPiece(data: ByteArray, i: Int): ByteArray {
        val from = i * PIECE_BYTES
        return data.copyOfRange(from, minOf(data.size, from + PIECE_BYTES))
    }

    // ── one's own item ──

    /**
     * ONE'S OWN PICTURE, VOICE, FILE OR STICKER IN THE MESH WALL (iOS share, handed by ChatStore.setMediaStatus 2022-2034): the answer
     * says whether it leaves -- a file past the radio's measure does not. A picture is made small enough first; the rest leaves as it is.
     */
    fun share(file: File, kind: String, docName: String?, caption: String, du: Double?): Boolean {
        if (!Mesh.discoverable) return false
        val size = file.length()
        if (size <= 0) return false
        if (kind != "img" && ITEM_MAX < size) {
            Log.i("Montana", "mesh_room not sent -- kind=$kind bytes=$size past the radio's measure"); return false
        }
        val w = MeshRoom.Word(MeshRoom.run, System.currentTimeMillis() / 1000, Prefs.userName.trim(), caption)
        val durMs = ((du ?: 0.0) * 1000).toLong().coerceIn(0, 3_600_000)
        queue.execute {
            val made = if (kind == "img") small(file) else runCatching { file.readBytes() }.getOrNull()?.let { it to file.extension }
            if (made == null || made.first.isEmpty() || made.first.size > ITEM_MAX) {
                Log.i("Montana", "mesh_room not sent -- kind=$kind did not read small enough"); return@execute
            }
            send(w, kind, made.second, docName ?: "", durMs, made.first)
        }
        return true
    }

    /** A picture small enough for the radio: as it is when it fits (a sticker keeps its shape), else a JPEG at a shrinking side and quality. */
    private fun small(f: File): Pair<ByteArray, String>? {
        val orig = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (orig.size <= ITEM_MAX) return orig to f.extension.ifEmpty { "jpg" }
        for ((side, q) in listOf(1280 to 60, 1024 to 55, 800 to 50, 640 to 45, 480 to 40)) {
            val jpeg = Media.photoForSend(orig, side, q) ?: return null
            if (jpeg.size <= ITEM_MAX) return jpeg to "jpg"
        }
        return null
    }

    private fun send(w: MeshRoom.Word, kind: String, ext: String, fileName: String, durMs: Long, data: ByteArray) {
        val m = Item(kind, random(16), data.size, pieceCount(data.size), Wire.sha(data), ext, fileName, durMs)
        synchronized(lock) { keep(Held(w, m, data, System.currentTimeMillis())) }
        val word = mediaCell(w, m)
        BleMesh.sayInRoom(word, 7)
        for (i in 0 until m.pieces) queue.schedule({ BleMesh.sayInRoom(pieceCell(m.item, i, m.pieces, bytesOfPiece(data, i)), 8) }, PIECE_GAP_MS * (i + 1), TimeUnit.MILLISECONDS)
        // the word again after the last piece: a neighbour that came in range midway learns what the pieces are
        queue.schedule({ BleMesh.sayInRoom(word, 7) }, PIECE_GAP_MS * (m.pieces + 2), TimeUnit.MILLISECONDS)
        Log.i("Montana", "mesh_room item out kind=$kind bytes=${data.size} pieces=${m.pieces}")
    }

    /** Under the lock: an item whole is kept to answer asks, within its time and the bytes' measure. */
    private fun keep(h: Held) {
        val now = System.currentTimeMillis()
        held.entries.removeAll { now - it.value.at >= HELD_FOR }
        held[Wire.hex(h.item.item)] = h
        var total = held.values.sumOf { it.data.size }
        while (HELD_BYTES < total) {
            val oldest = held.minByOrNull { it.value.at } ?: break
            held.remove(oldest.key); total -= oldest.value.data.size
        }
    }

    /** Under the lock: the item gathered whole moves to the held once its bytes are the ones its word names (iOS takeWhole). */
    private fun takeWhole(key: String): Pair<Held?, Boolean> {
        val g = gathering[key] ?: return null to false
        val m = g.item ?: return null to false; val w = g.word ?: return null to false
        if (g.got.size != m.pieces) return null to false
        val out = java.io.ByteArrayOutputStream(m.size)
        for (i in 0 until m.pieces) out.write(g.got[i] ?: return null to false)
        gathering.remove(key)
        val data = out.toByteArray()
        if (data.size != m.size || !Wire.sha(data).contentEquals(m.sha)) return null to true
        val h = Held(w, m, data, System.currentTimeMillis())
        keep(h)
        return h to false
    }

    /** The word of an item came (on the radio's thread): the item is gathered from here on; the word keeps the word's measure. */
    fun heardMedia(body: ByteArray): Boolean {
        val (w, m) = media(body) ?: return false
        if (w.run.contentEquals(MeshRoom.run)) return false
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now / 1000 - w.at) >= MeshRoom.FRESH_FOR || !MeshRoom.mayPassWord(w.run, now)) return false
        val key = Wire.hex(m.item)
        val taken = synchronized(lock) {
            if (held[key] == null && (gathering[key] != null || gathering.size < GATHER_MAX)) {
                val g = gathering.getOrPut(key) { Gathering(null, null, HashMap(), now, now, 0) }
                g.word = w; g.item = m; g.last = now
                if (g.got.keys.any { m.pieces <= it }) g.got.clear()   // pieces of another shape than the word says
            }
            takeWhole(key)
        }
        land(taken)
        armRepair()
        return true
    }

    /** A piece came (cell 8): kept for its item, and passed on while the phone's minute of bytes allows (iOS heardPiece). */
    fun heardPiece(body: ByteArray): Boolean {
        val p = piece(body) ?: return false
        val now = System.currentTimeMillis()
        val key = Wire.hex(p.item)
        var may = false
        val taken = synchronized(lock) {
            passedBytes.removeAll { now - it.first >= 60_000 }
            may = passedBytes.sumOf { it.second } + p.bytes.size <= PASS_BYTES_PER_MINUTE
            if (may) passedBytes.add(now to p.bytes.size)
            var t: Pair<Held?, Boolean> = null to false
            if (held[key] == null) {
                val g = gathering[key]
                if (g != null) {
                    if ((g.item?.let { it.pieces == p.count } ?: true) && g.got[p.index] == null) g.got[p.index] = p.bytes
                    g.last = now
                    t = takeWhole(key)
                } else if (gathering.size < GATHER_MAX) {
                    gathering[key] = Gathering(null, null, hashMapOf(p.index to p.bytes), now, now, 0)
                }
            }
            t
        }
        land(taken)
        armRepair()
        return may
    }

    /** An ask came (cell 9): whoever holds the item whole answers with the pieces asked, once in three seconds per item (iOS heardAsk). */
    fun heardAsk(body: ByteArray) {
        val (item, missing) = ask(body) ?: return
        val now = System.currentTimeMillis()
        val key = Wire.hex(item)
        val h = synchronized(lock) {
            val h = held[key]
            if (h != null && 3_000 <= now - (answeredAt[key] ?: 0L)) { answeredAt[key] = now; if (answeredAt.size > 256) answeredAt.entries.removeAll { now - it.value > 60_000 }; h } else null
        } ?: return
        val want = missing.filter { it < h.item.pieces }
        want.forEachIndexed { n, i ->
            queue.schedule({ BleMesh.sayInRoom(pieceCell(h.item.item, i, h.item.pieces, bytesOfPiece(h.data, i)), 8) }, PIECE_GAP_MS * n, TimeUnit.MILLISECONDS)
        }
        Diary.folded("mesh_room", "ask answered pieces=${want.size}", 10_000)
    }

    /** The item whole lands in the room off the radio's thread; a refused one is said in the diary. */
    private fun land(taken: Pair<Held?, Boolean>) {
        if (taken.second) Log.w("Montana", "mesh_room item refused -- its bytes are not the ones its word names")
        val h = taken.first ?: return
        queue.execute { lay(Book.ctx, h) }
    }

    /** iOS lay: the file kept, the row laid under the name its sender spoke under (appendMediaPlaceholder + fillMedia). */
    private fun lay(c: Context, h: Held) {
        if (!Mesh.discoverable) return
        val m = h.item
        val mid = "mid:mesh-" + Wire.hex(m.item)
        if (Book.chat(Book.MESH)?.msgs?.any { it.mid == mid } == true) return
        val f = Media.file(c, "mesh-" + Wire.hex(m.item), m.ext)
        if (!DiskDoor.write(f, h.data, "media")) { Log.w("Montana", "mesh_room item refused by the disk kind=${m.kind}"); return }
        MediaPlace.told(f.path, true)
        val man = JSONObject().put("k", m.kind).put("e", m.ext).put("sz", m.size).apply {
            if (m.fileName.isNotEmpty()) put("n", m.fileName)
            if (h.word.text.isNotEmpty()) put("cap", h.word.text)
            if (0 < m.durMs) put("du", m.durMs / 1000.0)
        }
        Book.appendMeshItem(mid, MeshRoom.ref(h.word), Marks.MEDIA + man.toString(), man.toString(), f.path)
        Log.i("Montana", "mesh_room laid item kind=${m.kind} bytes=${h.data.size}")
    }

    /** The quiet items ask for their missing pieces every five seconds, six times at most; the beat stands only while something gathers. */
    private fun armRepair() {
        synchronized(lock) {
            if (gathering.isNotEmpty() && repair == null)
                repair = queue.scheduleAtFixedRate({ repairBeat() }, ASK_EVERY, ASK_EVERY, TimeUnit.MILLISECONDS)
        }
    }

    private fun repairBeat() {
        val now = System.currentTimeMillis()
        val asks = ArrayList<Pair<ByteArray, List<Int>>>()
        synchronized(lock) {
            val it = gathering.entries.iterator()
            while (it.hasNext()) {
                val (_, g) = it.next()
                if (GATHER_FOR < now - g.first) { it.remove(); continue }
                val m = g.item ?: continue
                if (now - g.last < ASK_EVERY || g.asks >= ASKS_MAX) continue
                val missing = (0 until m.pieces).filter { g.got[it] == null }
                if (missing.isEmpty()) continue
                g.asks++; g.last = now
                asks.add(m.item to missing.take(40))
            }
            if (gathering.isEmpty()) { repair?.cancel(false); repair = null }
        }
        for ((item, missing) in asks) BleMesh.sayInRoom(askCell(item, missing), 9)
        if (asks.isNotEmpty()) Diary.folded("mesh_room", "asked items=${asks.size}", 10_000)
    }
}
