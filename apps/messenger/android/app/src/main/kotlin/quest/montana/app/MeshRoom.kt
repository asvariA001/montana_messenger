package quest.montana.app

import android.content.Context
import android.util.Log

/**
 * THE MESH WALL, part 3a: the room's words (iOS MTMeshRoom, MontanaBLEMesh 898-1006; the author's word 29.09: «the mesh wall and
 * the P2P wall are applications»). A word of the room is addressed to nobody -- the zero slot every beacon wears -- heard by every
 * neighbour and passed on once by each while it is fresh. Only while this phone offers itself to the mesh (Mesh.discoverable).
 *   word: [ver 1 | run 8 | at u32 BE | name length 1 | name | words] 0   -- the trailing zero is the mesh's seal count
 * The run is eight random bytes born with the process and gone with it: nothing on the air names a person beyond the word's own name.
 * A word older than ten minutes is neither laid nor passed on; one run passes on at most thirty words a minute, the phone 120.
 * Pictures, voices, files and stickers ride as their word (version 2) and their pieces (cell 8); a missed piece is asked for (cell 9)
 * and whoever holds the item whole answers: MeshItems.kt.
 */
object MeshRoom {
    private const val VERSION: Byte = 1
    const val MEDIA_VERSION: Byte = 2
    internal const val NAME_BYTES = 96
    internal const val WORD_BYTES = 1024
    internal const val FRESH_FOR = 600L
    private const val PASS_PER_MINUTE = 30
    private const val PASS_ALL_PER_MINUTE = 120

    /** This run's mark: eight random bytes, born with the process and gone with it (iOS MTMeshRoom.run). */
    val run: ByteArray = MtBindings.nativeRandom(8) ?: ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }

    internal val lock = Any()
    private val passed = HashMap<String, MutableList<Long>>()
    private val passedAll = ArrayList<Long>()

    class Word(val run: ByteArray, val at: Long, val name: String, val text: String)

    internal fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** A string cut at a letter's edge to at most `bytes` bytes of UTF-8 (iOS cut). */
    fun cut(s: String, bytes: Int): String {
        val out = StringBuilder()
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val len = ch.toByteArray().size
            if (n + len > bytes) break
            out.append(ch); n += len; i += Character.charCount(cp)
        }
        return out.toString()
    }

    fun encode(w: Word): ByteArray {
        val name = cut(w.name, NAME_BYTES).toByteArray(); val text = cut(w.text, WORD_BYTES).toByteArray()
        return byteArrayOf(VERSION) + w.run.copyOf(8) + be32(w.at) + byteArrayOf(name.size.toByte()) + name + text
    }

    fun decode(d: ByteArray): Word? {
        if (d.size < 14 || d[0] != VERSION) return null
        val at = ((d[9].toLong() and 0xff) shl 24) or ((d[10].toLong() and 0xff) shl 16) or ((d[11].toLong() and 0xff) shl 8) or (d[12].toLong() and 0xff)
        val n = d[13].toInt() and 0xff
        if (14 + n >= d.size || n > NAME_BYTES || d.size - 14 - n > WORD_BYTES) return null
        val name = utf8(d.copyOfRange(14, 14 + n)) ?: return null
        val text = utf8(d.copyOfRange(14 + n, d.size)) ?: return null
        if (text.isBlank()) return null
        return Word(d.copyOfRange(1, 9), at, name, text)
    }

    /** iOS String(data:encoding:.utf8): bytes that are not UTF-8 are no word at all. */
    internal fun utf8(b: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
    }.getOrNull()

    /** The cell ends with a seal count of zero (iOS cell): without it the last byte of the words was read as a count. */
    fun cell(w: Word): ByteArray = encode(w) + byteArrayOf(0)
    fun word(body: ByteArray): Word? = if (body.lastOrNull() != 0.toByte()) null else decode(body.copyOf(body.size - 1))

    /** The word's own name in the room (iOS mid(of:)): a replay under a new cell id lays nothing twice. */
    fun mid(w: Word): String {
        val d = w.run + be32(w.at) + w.name.toByteArray() + byteArrayOf(0) + w.text.toByteArray()
        return "mid:mesh-" + Wire.hex(Wire.sha(d).copyOf(16))
    }

    /** The ref a word's row carries here: the run and the name, so the room names its writer from the row itself (COMPAT-LOCAL). */
    fun ref(w: Word) = "mesh:" + Wire.hex(w.run) + ":" + w.name

    /** iOS name(of:): the speaker's name a mesh row carries, «Someone» when it gave none; null for a row not of the mesh. */
    fun name(c: Context, ref: String?): String? {
        if (ref == null || !ref.startsWith("mesh:")) return null
        val parts = ref.split(":", limit = 3)
        if (parts.size != 3) return null
        return parts[2].trim().ifEmpty { c.getString(R.string.mesh_someone) }
    }

    /** iOS mayPassWord: a run passes on at most thirty words a minute, the phone at most 120, whatever marks they wear. */
    internal fun mayPassWord(r: ByteArray, now: Long): Boolean = synchronized(lock) {
        val k = Wire.hex(r)
        val seen = (passed[k] ?: mutableListOf()).filter { now - it < 60_000 }.toMutableList()
        passedAll.removeAll { now - it >= 60_000 }
        val may = seen.size < PASS_PER_MINUTE && passedAll.size < PASS_ALL_PER_MINUTE
        if (may) { seen.add(now); passedAll.add(now) }
        passed[k] = seen
        if (passed.size > 256) passed.entries.removeAll { e -> e.value.none { now - it < 60_000 } }
        may
    }

    /** The person's words on the mesh wall: on the air at once, to everyone on the mesh; a service word never leaves by the room. */
    fun say(text: String) {
        if (!Mesh.discoverable || text.startsWith("​") || Marks.isService(text)) return
        val w = Word(run, System.currentTimeMillis() / 1000, Prefs.userName.trim(), text)
        BleMesh.sayInRoom(cell(w), 7)
    }

    /** A cell of the room came by the radio: the word is laid, and the answer says whether it may be passed on (iOS heard). */
    fun heard(body: ByteArray): Boolean {
        if (body.firstOrNull() == MEDIA_VERSION) return MeshItems.heardMedia(body)   // the word of a picture, voice, file or sticker
        val w = word(body) ?: return false
        if (w.run.contentEquals(run)) return false
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now / 1000 - w.at) >= FRESH_FOR) return false
        if (!mayPassWord(w.run, now)) { Diary.folded("mesh_room", "held -- a run over its minute's measure", 60_000); return false }
        val mid = mid(w)
        Thread { Book.appendMeshRoom(mid, ref(w), w.text, w.at * 1000) }.start()
        return true
    }

    init { Log.d("Montana", "mesh_room run born") }
}
