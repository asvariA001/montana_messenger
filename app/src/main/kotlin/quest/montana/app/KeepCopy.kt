package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

// THE COPY KEPT BY THE PEOPLE ONE WRITES TO, its frozen part (iOS MTKeeping.swift 1-297 and 1074-1093, 37f60ac1; the author's
// words 08.10.2026: «restore by the words fully, byte for byte», «restore over the network from the phones, as it should be»;
// Network «A copy kept by the people one speaks with», Canon «The keeping of a copy»). The copy the backup seals under the words
// is cut by the erasure code of a delivery; the keeper at slot k holds cell p of every group for which p mod n = k, beside the
// head of its share, the seal of that head under a branch of the words it never sees, and the secret of its slot. This file holds
// the code, the three quantities and the ring, each with the Canon's vectors; the owner and keeper sides come after it (KeepCopy).
// Taken from dinetpda's port (PR #43, Keeping.kt) and checked line by line against iOS 310cfb4b. Our `object Keeping` (Twin.kt)
// is the cargo road between one's own devices and keeps its name.

/**
 * THE ERASURE CODE OF A DELIVERY (iOS MTKeepCode 20-224): Reed-Solomon over the field of 256 elements with the modulus 0x11D, the
 * Cauchy matrix cauchy[i][j] = inverse((erasure_data + i) XOR j), the body blocks first and the parity after them. The vectors
 * are the Canon's own (tools/keep_vectors.py makes them again outside both apps); the keeping refuses to start where they fail.
 */
object KeepCode {
    const val CHUNK = 868   // Canon: chunk_bytes
    const val GROUP = 16    // Canon: erasure_group
    const val DATA = 12     // Canon: erasure_data
    const val PARITY = GROUP - DATA

    /** A product in the field without a branch on either operand, the core's own form (mt-wire, erasure.rs). */
    fun mul(a: Int, b: Int): Int {
        var product = 0
        var left = a and 0xff
        var right = b and 0xff
        repeat(8) {
            product = product xor (left and -(right and 1))
            val over = -(left ushr 7)
            left = ((left shl 1) xor (0x1D and over)) and 0xff
            right = right ushr 1
        }
        return product and 0xff
    }
    /** The whole table of the field, walked once: a product is then one read of 64 KiB. */
    private val table: ByteArray by lazy { ByteArray(65536) { mul(it ushr 8, it and 0xff).toByte() } }
    private fun t(a: Int, b: Int) = table[(a shl 8) or b].toInt() and 0xff
    fun inverse(a: Int): Int? {
        if (a == 0) return null
        for (b in 1..255) if (t(a, b) == 1) return b
        return null
    }
    private val cauchy: Array<IntArray> by lazy { Array(PARITY) { i -> IntArray(DATA) { j -> inverse((DATA + i) xor j) ?: 0 } } }

    /** Every block scaled by its coefficient and summed into `o`, byte by byte. */
    private fun addScaled(o: ByteArray, coef: Int, b: ByteArray) {
        val tb = table
        val row = coef shl 8
        for (x in o.indices) o[x] = (o[x].toInt() xor tb[row or (b[x].toInt() and 0xff)].toInt()).toByte()
    }

    /** The parity blocks of one group: every body block scaled by its coefficient and summed, byte by byte. */
    fun parity(blocks: List<ByteArray>): List<ByteArray>? {
        if (blocks.size != DATA) return null
        val width = blocks[0].size
        if (blocks.any { it.size != width }) return null
        return List(PARITY) { i -> ByteArray(width).also { o -> for (j in 0 until DATA) addScaled(o, cauchy[i][j], blocks[j]) } }
    }

    /**
     * Any twelve cells of a group give its twelve body blocks, and no fewer: the rows they stand for are inverted in the field and
     * applied to what they carry. Twelve body cells in hand are the body itself, and nothing is computed.
     */
    fun reconstruct(present: List<Pair<Int, ByteArray>>): List<ByteArray>? {
        val seen = HashSet<Int>()
        val taken = ArrayList<Pair<Int, ByteArray>>()
        for ((p, b) in present) if (p in 0 until GROUP && seen.add(p)) {
            taken.add(p to b)
            if (taken.size == DATA) break
        }
        if (taken.size != DATA) return null
        val width = taken[0].second.size
        if (taken.any { it.second.size != width }) return null
        if (taken.all { it.first < DATA }) {
            val out = arrayOfNulls<ByteArray>(DATA)
            for ((p, b) in taken) out[p] = b
            return out.map { it!! }
        }
        val m = Array(DATA) { r -> taken[r].first.let { p -> if (p < DATA) IntArray(DATA) { if (it == p) 1 else 0 } else cauchy[p - DATA].copyOf() } }
        val inv = Array(DATA) { r -> IntArray(DATA) { if (it == r) 1 else 0 } }
        for (col in 0 until DATA) {
            val piv = (col until DATA).firstOrNull { m[it][col] != 0 } ?: return null
            val iv = inverse(m[piv][col]) ?: return null
            val mr = m[col]; m[col] = m[piv]; m[piv] = mr
            val ir = inv[col]; inv[col] = inv[piv]; inv[piv] = ir
            for (x in 0 until DATA) { m[col][x] = t(iv, m[col][x]); inv[col][x] = t(iv, inv[col][x]) }
            for (r in 0 until DATA) if (r != col && m[r][col] != 0) {
                val f = m[r][col]
                for (x in 0 until DATA) { m[r][x] = m[r][x] xor t(f, m[col][x]); inv[r][x] = inv[r][x] xor t(f, inv[col][x]) }
            }
        }
        return List(DATA) { i -> ByteArray(width).also { o -> for (j in 0 until DATA) if (inv[i][j] != 0) addScaled(o, inv[i][j], taken[j].second) } }
    }

    /**
     * THE CANON'S VECTORS OF THE WIRE (iOS agreesWithCanon 113-130), and one walk the frozen bytes cannot make alone: the body of the
     * vector is twelve repeated bytes, so a second body of counting bytes is rebuilt across a loss that takes three parity cells and
     * one body cell -- a matrix written in another order, or rows taken for the wrong positions, rebuilds neither.
     */
    fun agreesWithCanon(): Boolean {
        val body = List(DATA) { j -> ByteArray(CHUNK) { (0xA0 + j).toByte() } }
        val par = parity(body) ?: return false
        if (par.map { Wire.hex(it.copyOf(4)) } != listOf("a5a5a5a5", "acacacac", "b7b7b7b7", "bebebebe")) return false
        if (Wire.hex(Wire.sha(*par.toTypedArray())) != "8963024273fc41fdf9afe063f3be77e7fbc312a7d8ede4f96a7fd99dd4fd0e69") return false
        val lost = setOf(0, 5, 7, 11)
        val whole = body + par
        val back = reconstruct(whole.indices.filter { it !in lost }.map { it to whole[it] }) ?: return false
        if (back.indices.any { !back[it].contentEquals(body[it]) }) return false
        val counting = List(DATA) { j -> ByteArray(64) { ((j * 64 + it) % 251).toByte() } }
        val all = counting + (parity(counting) ?: return false)
        val loss = setOf(3, 12, 13, 15)
        val again = reconstruct(all.indices.filter { it !in loss }.map { it to all[it] }) ?: return false
        return again.indices.all { again[it].contentEquals(counting[it]) }
    }

    /** As many bytes as the buffer holds, or fewer only at the end of the stream (a stream may hand fewer at a time). */
    private fun readUpTo(inp: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) { val r = inp.read(buf, got, buf.size - got); if (r < 0) break; got += r }
        return got
    }

    /**
     * THE CUT (iOS cut 132-183, Canon «The keeping of a copy»): the container is cut into blocks of chunk_bytes, grouped twelve at a
     * time, every group coded into sixteen cells, and cell p of every group laid into the share of slot p mod n, ascending. Streamed
     * one group at a time: a copy of gigabytes never stands whole in memory.
     */
    class Cut(val length: Long, val digest: ByteArray, val shares: List<File>, val shareDigests: List<ByteArray>)
    fun cut(container: File, n: Int, dir: File): Cut? {
        if (n !in setOf(4, 8, 16) || !container.isFile) return null
        val shares = List(n) { File(dir, java.util.UUID.randomUUID().toString() + ".keep") }
        val outs = ArrayList<java.io.OutputStream>()
        try {
            for (f in shares) outs.add(java.io.BufferedOutputStream(java.io.FileOutputStream(f), 1 shl 16))
            val whole = MessageDigest.getInstance("SHA-256")
            val digests = List(n) { MessageDigest.getInstance("SHA-256") }
            var length = 0L
            FileInputStream(container).use { inp ->
                val buf = ByteArray(DATA * CHUNK)
                while (true) {
                    val got = readUpTo(inp, buf)
                    if (got <= 0) break
                    whole.update(buf, 0, got)
                    length += got
                    val blocks = List(DATA) { j -> ByteArray(CHUNK).also { b -> val lo = j * CHUNK; if (lo < got) System.arraycopy(buf, lo, b, 0, minOf(CHUNK, got - lo)) } }
                    val cells = blocks + (parity(blocks) ?: throw java.io.IOException("parity"))
                    for (p in 0 until GROUP) { outs[p % n].write(cells[p]); digests[p % n].update(cells[p]) }
                    if (got < buf.size) break
                }
            }
            outs.forEach { it.close() }
            if (length <= 0) { shares.forEach { it.delete() }; return null }
            return Cut(length, whole.digest(), shares, digests.map { it.digest() })
        } catch (e: Exception) {
            outs.forEach { runCatching { it.close() } }
            shares.forEach { it.delete() }
            Log.d("Montana", "keep_cut refused " + e.javaClass.simpleName)
            return null
        }
    }

    /**
     * THE COPY BUILT BACK from the shares in hand (iOS rebuild 185-223): for every group the cells of each share are read where the
     * rule of positions laid them, any twelve rebuild the body, and the last group is cut at the container's own length. False when
     * a group cannot stand -- a container with a hole in it is never laid.
     */
    class Part(val slot: Int, val n: Int, val file: File)
    fun rebuild(parts: List<Part>, length: Long, dest: File): Boolean {
        val n = parts.firstOrNull()?.n ?: return false
        if (n !in setOf(4, 8, 16) || parts.any { it.n != n } || length <= 0) return false
        val per = GROUP / n
        val blocks = (length + CHUNK - 1) / CHUNK
        val groups = ((blocks + DATA - 1) / DATA).toInt()
        val ins = parts.mapNotNull { p -> runCatching { p.slot to RandomAccessFile(p.file, "r") }.getOrNull() }
        var fine = true
        var written = 0L
        try {
            java.io.BufferedOutputStream(java.io.FileOutputStream(dest), 1 shl 16).use { out ->
                for (g in 0 until groups) {
                    val present = ArrayList<Pair<Int, ByteArray>>()
                    for ((slot, fh) in ins) {
                        val at = g.toLong() * per * CHUNK
                        if (fh.length() < at + per * CHUNK) continue
                        val d = ByteArray(per * CHUNK)
                        fh.seek(at); fh.readFully(d)
                        for (c in 0 until per) present.add((slot + c * n) to d.copyOfRange(c * CHUNK, (c + 1) * CHUNK))
                    }
                    val body = reconstruct(present)
                    if (body == null) { fine = false; break }
                    for (b in body) {
                        if (written >= length) break
                        val take = minOf(CHUNK.toLong(), length - written).toInt()
                        out.write(b, 0, take)
                        written += take
                    }
                }
            }
        } catch (e: Exception) { fine = false } finally { ins.forEach { runCatching { it.second.close() } } }
        if (!fine || written != length) { dest.delete(); return false }
        return true
    }
}

/** THE THREE QUANTITIES OF KEEPING (iOS MTKeepDerive 226-297), as Canon fixes them: the secret of a slot, the keeping key, and the seal of a share's head. */
object KeepDerive {
    const val SLOTS = 16   // a group has sixteen cells: the slots of a copy (Canon: n is erasure_group)
    /** HKDF-Expand(master_seed, info, 32), the branches' one road: the core's own (mt_mldsa_seed_for_role). */
    private fun expand(master: ByteArray, info: String): ByteArray? =
        if (master.size == 64) MtBindings.nativeRoleSeed(master, info)?.takeIf { it.size == 32 } else null
    /** slot_secret(k) = HKDF-Expand(master_seed, "mt-keep-slot" || 0x00 || k_1B, 32) */
    fun slotSecret(master: ByteArray, k: Int): ByteArray? = if (k in 0 until SLOTS) expand(master, "mt-keep-slot" + Char(0) + Char(k)) else null
    /** keep_key = HKDF-Expand(master_seed, "mt-keep-key", 32): it seals every head and is handed to nobody. */
    fun keepKey(master: ByteArray) = expand(master, "mt-keep-key")
    /** light_secret = HKDF-Expand(master_seed, "mt-keep-light", 32): the pipe of light, the owner's alone, in which the road of the light copy is named. */
    fun lightSecret(master: ByteArray) = expand(master, "mt-keep-light")

    /** head = k_1B || n_1B || generation_8B_LE || length_8B_LE || SHA-256(container) || SHA-256(share): eighty-two bytes. */
    class Head(val slot: Int, val n: Int, val generation: Long, val length: Long, val copyDigest: ByteArray, val shareDigest: ByteArray) {
        val bytes: ByteArray get() = byteArrayOf(slot.toByte(), n.toByte()) + Wire.le8(generation) + Wire.le8(length) + copyDigest + shareDigest
        fun same(o: Head) = slot == o.slot && n == o.n && generation == o.generation && length == o.length &&
            copyDigest.contentEquals(o.copyDigest) && shareDigest.contentEquals(o.shareDigest)
        companion object {
            const val SIZE = 82
            fun of(d: ByteArray?): Head? {
                if (d == null || d.size != SIZE) return null
                fun u64(at: Int): Long { var v = 0L; for (b in 0 until 8) v = v or ((d[at + b].toLong() and 0xff) shl (8 * b)); return v }
                val slot = d[0].toInt() and 0xff; val n = d[1].toInt() and 0xff
                if (n !in setOf(4, 8, 16) || slot >= n) return null
                return Head(slot, n, u64(2), u64(10), d.copyOfRange(18, 50), d.copyOfRange(50, 82))
            }
        }
    }
    /** seal = SHA-256("mt-keep-seal" || 0x00 || keep_key || head), by the set's one composition. */
    fun seal(key: ByteArray, head: Head): ByteArray = Wire.domained("mt-keep-seal", key, head.bytes)

    /**
     * THE CANON'S VECTORS OF KEEPING (iOS 277-296), from the master seed of sixty-four distinct bytes. What they reject is named in the
     * set: a slot written as eight bytes, a missing NUL, a seed read backwards, integers written big-endian, the slot and n exchanged.
     */
    fun agreesWithCanon(): Boolean {
        val master = ByteArray(64) { it.toByte() }
        val s3 = slotSecret(master, 3) ?: return false
        val key = keepKey(master) ?: return false
        if (slotSecret(master, 0)?.let { Wire.hex(it) } != "f57057fdd34a297a072f161d54105f98ec23cbbaf6229662b36efb9ae4aaa9d7" ||
            slotSecret(master, 1)?.let { Wire.hex(it) } != "c534306aa283d19c8e83f392bda37a7e59b7ccd7868b8df455bb9b6716e1ab91" ||
            slotSecret(master, 15)?.let { Wire.hex(it) } != "4573fb5ad5dc3b579049f304cc95cf5f3765ceaf07caab730d986c034788edab" ||
            Wire.hex(Wire.pipeTag(s3, 1000)) != "ae67484d6abdb555a43159fc9386d753" ||
            Wire.hex(key) != "9945bec54d8f26ab7859b3e6eb886456ff529fd2637aa2650626b3595e88970c") return false
        // the light secret over the counting seed and over the same seed read backwards: a reader of the seed backwards swaps them
        val light = lightSecret(master) ?: return false
        if (Wire.hex(light) != "23d9bfa1a0151598ea710a15492cb9f8fcb46e5c86d39ba0e8824a69511a3776" ||
            lightSecret(master.reversedArray())?.let { Wire.hex(it) } != "8d14528b640e1d7b114c2d299cf85f4ba7813a2308255dfba6025f01e4f288b9" ||
            Wire.hex(Wire.pipeTag(light, 1000)) != "7b957934998d5c0ad69415e43616020c") return false
        val head = Head(2, 8, 0x1112131415161718L, 0x0102030405060708L, ByteArray(32) { (0x20 + it).toByte() }, ByteArray(32) { (0x40 + it).toByte() })
        return Head.of(head.bytes)?.same(head) == true &&
            Wire.hex(seal(key, head)) == "80b0b97f66a8ef64fce6d692935a82b37bf978c0e215cd8b190bb7a1100475ae"
    }
}

/**
 * THE RING OF KEEPERS (iOS MTKeepRing 1030-1051; the author's word 08.10.2026 23:5x MSK: «between contacts in a ring, so that if
 * someone is offline the restore is full»): every correspondent that allows keeping, two at each position at most, and every share at
 * two neighbouring positions.
 */
object KeepRing {
    const val MOST = 2 * KeepCode.GROUP
    /** The slots the keeper at place i of m keeps: those whose position, k mod q, is its own or the next one around the ring. */
    fun slots(place: Int, of: Int): List<Int> {
        val q = minOf(of, KeepCode.GROUP)
        if (q <= 0 || place < 0 || place >= of) return emptyList()
        val j = place % q
        return (0 until KeepCode.GROUP).filter { it % q == j || it % q == (j + 1) % q }
    }
    /** The rule as cases a wrong reading fails: a ring that does not close, the neighbour taken backwards, one or two keepers holding
     *  all sixteen, and the seventeenth keeper of twenty standing beside the first. */
    fun agrees(): Boolean =
        slots(0, 4) == listOf(0, 1, 4, 5, 8, 9, 12, 13) && slots(3, 4) == listOf(0, 3, 4, 7, 8, 11, 12, 15) &&
            slots(0, 1) == (0 until 16).toList() && slots(1, 2) == (0 until 16).toList() &&
            slots(16, 20) == listOf(0, 1) && slots(15, 16) == listOf(0, 15)
}

/**
 * THE KEEPING'S GATE (iOS MTKeeping.agrees 331): the code, the three quantities and the ring reproduce the Canon on THIS device,
 * or nothing is kept and nothing is taken back -- a copy cut by a drifted code is a copy no phone can build again.
 */
object KeepCopy {
    val agrees: Boolean by lazy {
        val code = KeepCode.agreesWithCanon()
        val derive = KeepDerive.agreesWithCanon()
        val ring = KeepRing.agrees()
        Log.i("Montana", "keep_canon code=${if (code) 1 else 0} derive=${if (derive) 1 else 0} ring=${if (ring) 1 else 0}")
        code && derive && ring
    }

    // ── the person's choices, settings of this device (iOS MTKeeping 305-325) ──
    const val OTHERS = "mt.keep.others"       // NOT-UI: this phone keeps parts for the people who write to me
    const val BUDGET = "mt.keep.budget.mb"    // NOT-UI: what this phone keeps for others, in megabytes
    val others: Boolean get() = Prefs.bool(OTHERS, true)
    val budget: Long get() = Prefs.dbl(BUDGET, 1024.0).toLong() * 1_048_576L

    // ── the keeper's side (iOS MTKeeping 365-372, 796-914) ──
    /** A part held for a correspondent: its slot of n, the slot's pipe secret, the head and its seal, and the owner's road on the nodes. */
    class Held(val slot: Int, val n: Int, val secret: ByteArray, val head: ByteArray, val seal: ByteArray, val bytes: Long, val at: Double,
               var answeredAt: Double = 0.0, val road: Map<String, String>? = null, val roadAt: Double? = null) {
        val generation: Long get() = KeepDerive.Head.of(head)?.generation ?: 0L
        fun json(): JSONObject = JSONObject().put("slot", slot).put("n", n).put("secret", Wire.hex(secret)).put("head", Wire.hex(head))
            .put("seal", Wire.hex(seal)).put("bytes", bytes).put("at", at).put("answeredAt", answeredAt)
            .apply { road?.let { put("road", JSONObject(it)) }; roadAt?.let { put("roadAt", it) } }
        companion object {
            fun of(o: JSONObject): Held? {
                val secret = unhex(o.optString("secret")) ?: return null
                val head = unhex(o.optString("head")) ?: return null
                val seal = unhex(o.optString("seal")) ?: return null
                val road = o.optJSONObject("road")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } }
                return Held(o.optInt("slot"), o.optInt("n"), secret, head, seal, o.optLong("bytes"), o.optDouble("at", 0.0),
                    o.optDouble("answeredAt", 0.0), road, if (o.has("roadAt")) o.optDouble("roadAt") else null)
            }
        }
    }
    private const val HELD = "mt.keep.held"   // NOT-UI: this device's own, sealed under the device key (iOS heldKey)
    private val lock = Any()
    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keep").apply { isDaemon = true } }
    /** The copy made and handed around: minutes of work, on a thread of its own, so the words of the keeping are answered meanwhile. */
    private val bulk = Executors.newSingleThreadExecutor { r -> Thread(r, "keep-copy").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private var heldCache: MutableMap<String, Held>? = null
    /** The owner's pieces of a part laid again by this phone, shared by a day's answers (iOS roads). */
    private val roads = HashMap<String, Pair<Double, JSONObject>>()
    private fun held(): MutableMap<String, Held> = synchronized(lock) {
        heldCache ?: HashMap<String, Held>().also { m ->
            DeviceVault.get(HELD)?.let { d -> runCatching { JSONObject(String(d, Charsets.UTF_8)) }.getOrNull() }?.let { o ->
                for (k in o.keys()) o.optJSONObject(k)?.let { Held.of(it) }?.let { m[k] = it }
            }
            heldCache = m
        }
    }
    private fun saveHeld() = synchronized(lock) {
        val o = JSONObject(); held().forEach { (k, h) -> o.put(k, h.json()) }
        DeviceVault.set(HELD, o.toString().toByteArray(Charsets.UTF_8))
    }
    private fun now() = System.currentTimeMillis() / 1000.0

    /** THE PARTS THIS PHONE KEEPS FOR OTHERS lie in a folder of their own, outside this person's copy and the vendor's backup (iOS folder). */
    private fun folder(): File? = runCatching { File(Book.ctx.noBackupFilesDir, "MontanaKeeping").apply { mkdirs() } }.getOrNull()
    private fun partFile(key: String): File? {
        val name = key.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = (b.toInt() and 0xff).toChar(); if (c.isLetterOrDigit() && c.code < 128) c.toString() else "%%%02X".format(b.toInt() and 0xff)
        }
        if (name.isEmpty()) return null
        return folder()?.let { File(it, "$name.keep") }
    }
    fun keyOf(conv: String, k: Int) = conv + "#" + k
    fun convOf(key: String) = key.substringBefore('#')
    private fun heldBytes(except: String) = synchronized(lock) { held().filter { convOf(it.key) != except }.values.sumOf { it.bytes } }
    /** The people whose parts this phone holds, and how much of each (iOS keptFor, bytes(for:)). */
    val keptFor: List<String> get() = synchronized(lock) { held().keys.map(::convOf).toSet().sorted() }
    fun bytes(conv: String): Long = synchronized(lock) { held().filter { convOf(it.key) == conv }.values.sumOf { it.bytes } }

    // ── the owner's side (iOS MTKeeping 305-362, 477-651) ──
    const val MINE = "mt.keep.mine"           // NOT-UI: my copy is kept by the people I write to
    const val TOLD = "mt.keep.mine.told"      // NOT-UI: the person was told and chose
    /** ON BY DEFAULT, ASKED AT THE OPENING (iOS mine/told): nothing leaves before the person has been told what each keeper learns. */
    val mine: Boolean get() = Prefs.bool(MINE, true)
    val told: Boolean get() = Prefs.bool(TOLD, false)
    /** The crossed glyph: the person chose to keep no copy with their contacts. */
    val offShown: Boolean get() = told && !mine
    /** The answer to the question at the opening. */
    fun answer(keep: Boolean) { Prefs.setBool(TOLD, true); setMine(keep) }

    /** A keeper of the ring holds the slots of its position and of the next (KeepRing); a keeper of an older build held one. */
    class Keeper(val slot: Int, var held: Boolean, val slots: List<Int>, var heldSlots: List<Int>) {
        fun json(): JSONObject = JSONObject().put("slot", slot).put("held", held).put("slots", ints(slots)).put("heldSlots", ints(heldSlots))
        companion object {
            fun of(o: JSONObject): Keeper {
                val slot = o.optInt("slot")
                return Keeper(slot, o.optBoolean("held"), list(o, "slots") ?: listOf(slot), list(o, "heldSlots") ?: emptyList())
            }
        }
    }
    /** The owner's state, the fields of iOS Owner by their own names; the light copy's fields ride unread until the light copy comes. */
    class Owner(var generation: Long = 0L) {
        var asking: Long? = null              // a renewal asking for keepers
        var askedAt = 0.0
        val yes = mutableListOf<String>()     // who said yes to it, in the order they said it
        var n = 0
        var keepers = LinkedHashMap<String, Keeper>()
        var renewedAt = 0.0
        var short = false                     // the last asking found nobody
        /** THE ATTEMPT IS THE MOMENT THE RHYTHM COUNTS FROM (iOS triedAt): a renewal refused on the way waits the day as one done. */
        var triedAt: Double? = null
        /** THE ASKING A NEW ATTACHMENT MADE DUE (iOS Owner.urgent, 11c34ed7 at 2389, decision 872 = v): its copy goes on the battery. */
        var urgent: Boolean? = null
        var light = JSONObject()              // lightGen, lightAt, lightTriedAt, lightFace, lightName, lightPeople
        fun json(): JSONObject = JSONObject(light.toString()).put("generation", generation).put("askedAt", askedAt).put("yes", org.json.JSONArray(yes))
            .put("n", n).put("keepers", JSONObject().apply { keepers.forEach { (c, k) -> put(c, k.json()) } }).put("renewedAt", renewedAt).put("short", short)
            .apply { asking?.let { put("asking", it) }; triedAt?.let { put("triedAt", it) }; urgent?.let { put("urgent", it) } }
        companion object {
            fun of(o: JSONObject): Owner = Owner(o.optLong("generation")).apply {
                asking = if (o.has("asking")) o.optLong("asking") else null
                askedAt = o.optDouble("askedAt", 0.0)
                o.optJSONArray("yes")?.let { a -> for (i in 0 until a.length()) yes.add(a.optString(i)) }
                n = o.optInt("n")
                o.optJSONObject("keepers")?.let { k -> for (c in k.keys()) k.optJSONObject(c)?.let { keepers[c] = Keeper.of(it) } }
                renewedAt = o.optDouble("renewedAt", 0.0)
                short = o.optBoolean("short")
                triedAt = if (o.has("triedAt")) o.optDouble("triedAt") else null
                urgent = if (o.optBoolean("urgent")) true else null
                light = JSONObject().also { l -> for (k in o.keys()) if (k.startsWith("light")) l.put(k, o.get(k)) }
            }
        }
    }
    private fun ints(l: List<Int>) = org.json.JSONArray(l)
    private fun list(o: JSONObject, k: String): List<Int>? = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optInt(it) } }
    private const val OWNER = "mt.keep.owner"   // NOT-UI: sealed under the device key; the person's own, it leaves with the words
    private var ownerCache: Owner? = null
    private fun owner(): Owner = synchronized(lock) {
        ownerCache ?: (DeviceVault.get(OWNER)?.let { d -> runCatching { Owner.of(JSONObject(String(d, Charsets.UTF_8))) }.getOrNull() } ?: Owner())
            .also { ownerCache = it }
    }
    private fun saveOwner() = synchronized(lock) { DeviceVault.set(OWNER, owner().json().toString().toByteArray(Charsets.UTF_8)) }
    /** The words leave the phone: the owner's state is the person's (MontanaSeed.clear). The parts held for others are this device's. */
    fun forget() = synchronized(lock) { DeviceVault.delete(OWNER); ownerCache = null }
    /** What the screen shows of my copy: who keeps it, whole or not yet, and whether the last asking found nobody. */
    fun keepers(): Map<String, Boolean> = synchronized(lock) { owner().keepers.mapValues { it.value.held } }
    val short: Boolean get() = synchronized(lock) { owner().short }
    val renewedAt: Double get() = synchronized(lock) { owner().renewedAt }
    val asking: Boolean get() = synchronized(lock) { owner().asking != null }

    fun setMine(on: Boolean) {
        Prefs.setBool(MINE, on)
        Prefs.setBool(TOLD, true)   // the switch stands under its own explanation
        ex.execute {
            synchronized(lock) {
                val o = owner()
                if (on) { o.renewedAt = 0.0; o.triedAt = null; saveOwner() }
                else { o.keepers.keys.forEach { say(it, JSONObject().put("w", "release")) }; ownerCache = Owner(o.generation); saveOwner() }
            }
            if (on) tick()
        }
    }

    /**
     * A WORK OF GIGABYTES WAITS FOR THE CHARGER (iOS MontanaPower.bulkHeld, the author's word 04.11.2026): the copy runs only on the
     * charger, outside the battery saver, at a heat no higher than fair (Android LIGHT and MODERATE) and on a path that is not
     * metered. Null -- go; else why it waits. A COPY A NEW ATTACHMENT MADE DUE GOES ON THE BATTERY (iOS bulkHeld(onBattery:), 11c34ed7
     * at 2389, decision 872 = v): onBattery lifts the charger alone; the battery saver, the heat and a metered path hold it still.
     */
    private fun bulkHeld(onBattery: Boolean = false): String? {
        val c = Book.ctx
        if (!onBattery && c.getSystemService(android.os.BatteryManager::class.java)?.isCharging != true) return "battery"
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return "lowpower"
        if (android.os.Build.VERSION.SDK_INT >= 29 && (pm?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_SEVERE) return "thermal"
        if (c.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false) return "metered"
        return null
    }
    @Volatile private var renewing = false
    @Volatile private var tickPending = false
    private var heldSaid: String? = null
    private fun heldOnce(what: String, why: String) { if (heldSaid != what + why) { heldSaid = what + why; Log.d("Montana", what + " held: " + why + " -- the copy waits for the charger") } }
    /** The rhythm looks again in half a minute (iOS tickSoon): the box read and the launch call it, many calls make one look. */
    fun tickSoon() {
        if (tickPending || !mine || !told) return
        tickPending = true
        ex.schedule({ tickPending = false; tick() }, 30, TimeUnit.SECONDS)
    }
    private fun later(seconds: Long) { ex.schedule({ tick() }, seconds, TimeUnit.SECONDS) }
    /**
     * THE OWNER'S RHYTHM (iOS tick): a renewal is due a day after the last; it asks the people I write to, waits ten minutes for
     * one yes at least (a full ring goes at once), and an hour without one leaves the copy where it stands until tomorrow.
     */
    private fun tick() {
        if (!mine || !told || !agrees || !MontanaSeed.hasSeed) return
        val t = now()
        val o = owner()
        // THE LIGHT COPY'S RHYTHM (iOS tick 513-521): once a day; one's own name or face moved -- a minute; the people and the
        // groups -- ten minutes; a light engine older than the card's -- once more at once (iOS 0be1c720)
        val (face, name) = profileTags()
        val l = synchronized(lock) { JSONObject(o.light.toString()) }
        val moved = l.optString("lightFace", "") != face || l.optString("lightName", "") != name || l.optInt("lightEngine", -1) != Card.ENGINE
        val since = t - maxOf(l.optDouble("lightAt", 0.0), l.optDouble("lightTriedAt", 0.0))
        val people = peopleTag()
        val met = l.optString("lightPeople", "") != people
        if (!lighting && (since >= 86_400 || (moved && since >= 60) || (met && since >= 600))) layLight(face, name, people)
        if (renewing) return
        val g = synchronized(lock) { o.asking }
        if (g != null) {
            val yes = synchronized(lock) { o.yes.size }
            if (yes >= KeepRing.MOST || (yes > 0 && t - o.askedAt >= 600)) return renew(g)
            if (t - o.askedAt >= 3600) {
                synchronized(lock) { o.short = true; o.asking = null; o.yes.clear(); o.renewedAt = t; saveOwner() }
                return Unit.also { Log.i("Montana", "keep_ask nobody said yes in an hour") }
            }
            return later(120)
        }
        val fresh = attachmentDue(t, o)
        if (!fresh && t - maxOf(o.renewedAt, o.triedAt ?: 0.0) < 86_400) return
        bulkHeld(onBattery = fresh)?.let { return heldOnce("keep_ask", it) }
        synchronized(lock) { o.urgent = if (fresh) true else null }
        ask()
    }
    /**
     * THE FULL COPY FOLLOWS A NEW ATTACHMENT (iOS attachmentDue, 11c34ed7 at 2389, decision 872 = v): a gif never reached the restored
     * iPad -- no copy was laid with it, its renewal waited the day and the charger. A letter whose attachment is newer than the last
     * copy asked or tried makes the copy due at once, ten minutes after that one at the least, so a run of photos folds into one copy.
     */
    private fun attachmentDue(t: Double, o: Owner): Boolean {
        val last = synchronized(lock) { maxOf(o.renewedAt, o.triedAt ?: 0.0, o.askedAt) }
        if (t - last < 600) return false
        val edge = (last * 1000).toLong()
        return Book.refs().any { ref ->
            runCatching { Book.chat(ref)?.msgs?.lastOrNull { it.text.startsWith(Marks.MEDIA) || it.text.startsWith(Marks.VOICE) }?.let { it.at > edge } == true }
                .getOrDefault(false)   // a feed written this moment is read at the next look
        }
    }
    private fun ask() {
        val people = people().take(2 * KeepRing.MOST)   // twice a full ring, so that the ring fills when some stay silent
        val t = now()
        val o = owner()
        if (people.isEmpty()) {
            synchronized(lock) { o.short = true; o.renewedAt = t; saveOwner() }
            return Unit.also { Log.i("Montana", "keep_ask people=0: nobody to ask") }
        }
        val g = o.generation + 1
        synchronized(lock) { o.asking = g; o.askedAt = t; o.yes.clear(); saveOwner() }
        for (conv in people) say(conv, JSONObject().put("w", "ask").put("g", g))
        Log.i("Montana", "keep_ask g=$g people=${people.size}")
        later(120)
    }
    /** The people I write to, the latest first: a correspondence of a person -- never my own twin, a group, or someone I block. */
    private fun people(): List<String> {
        val twin = MontanaSeed.twin ?: ""
        val last = HashMap<String, Long>()
        for (ref in Book.refs()) {
            if (ref == twin || ref == Book.SAVED || Groups.isKey(ref) || PeerSafety.isBlocked(ref)) continue
            val chat = Book.chat(ref) ?: continue
            last[ref] = chat.msgs.lastOrNull()?.at ?: 0L
        }
        return last.keys.sortedByDescending { last[it] }
    }
    private fun tookYes(conv: String, w: JSONObject) {
        val g = (w.opt("g") as? Number)?.toLong() ?: return
        val o = owner()
        val full = synchronized(lock) {
            val a = o.asking ?: return
            if (g != a || conv in o.yes) return
            o.yes.add(conv); saveOwner()
            o.yes.size >= KeepRing.MOST
        }
        if (full) renew(g)
    }
    /** The keeper's own word: only this makes a part «held» on my screen; a keeper of the ring is whole once it holds every slot of its place. */
    private fun tookHeld(conv: String, w: JSONObject) {
        val g = (w.opt("g") as? Number)?.toLong() ?: return
        val s = (w.opt("k") as? Number)?.toInt()
        synchronized(lock) {
            val o = owner()
            val k = o.keepers[conv] ?: return
            if (g != o.generation) return
            if (s != null) {
                val have = (k.heldSlots + s).toSortedSet()
                k.heldSlots = have.toList()
                k.held = have.containsAll(k.slots)
            } else k.held = true   // a keeper of an older build holds one part and names no slot
            saveOwner()
            Log.i("Montana", "keep_held slot=${s ?: k.slot} whole=${k.held}")
        }
    }
    private fun renew(g: Long) {
        if (renewing) return
        val o = owner()
        if (synchronized(lock) { o.yes.isEmpty() }) return
        val urgent = synchronized(lock) { o.urgent == true }
        bulkHeld(onBattery = urgent)?.let { heldOnce("keep_copy", it); return later(300) }
        renewing = true
        val chosen = synchronized(lock) {
            o.asking = null; o.urgent = null; o.triedAt = now(); saveOwner()
            // THE RING IN THE ORDER OF THE LATEST LETTER (Network): the people written to last stand first, a full ring at most
            val rank = people().withIndex().associate { it.value to it.index }
            o.yes.sortedBy { rank[it] ?: Int.MAX_VALUE }.take(KeepRing.MOST)
        }
        bulk.execute {
            try {
                val c = Book.ctx
                // ONE COPY ON THE SHELF AT A TIME: create clears the shelf, so a copy the person or the home node is making this
                // moment (its unfinished .part touched in the last ten minutes) is never swept away; the renewal waits ten minutes
                val busy = MontanaBackup.shelf(c)?.listFiles { f -> f.name.endsWith(".part") && System.currentTimeMillis() - f.lastModified() < 600_000 }
                if (!busy.isNullOrEmpty()) {
                    synchronized(lock) { owner().apply { asking = g; triedAt = null }; saveOwner() }
                    Log.i("Montana", "keep_copy waits: another copy is being made"); later(600); return@execute
                }
                val made = MontanaBackup.create(c, CopyInventory.gather(c).scope(CopyPlan.load())) {}
                if (made !is CopyResult.Made) Log.i("Montana", "keep_copy refused " + ((made as? CopyResult.Refused)?.why?.javaClass?.simpleName ?: "?"))
                else try { hand(made.file, g, chosen, urgent) } finally { made.file.delete() }
            } catch (e: Throwable) { Log.w("Montana", "keep_copy " + e.javaClass.simpleName) }
            finally { renewing = false }
        }
    }
    /**
     * THE COPY HANDED AROUND THE RING (iOS hand): cut at the sixteen slots of a group, every share laid on the nodes ONCE and its road
     * handed to each keeper of its slot -- the ring costs the network a copy and a third, whatever the number of keepers.
     */
    private fun hand(copy: File, g: Long, keepers: List<String>, urgent: Boolean) {
        val n = KeepCode.GROUP
        val master = MontanaSeed.mnemonic?.let { MtBindings.nativeMnemonicToMasterSeed(it) } ?: return
        val key = KeepDerive.keepKey(master) ?: return
        val dir = File(Book.ctx.noBackupFilesDir, "MontanaKeepCut").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        // the shares are a third more than the copy, and the pieces of one share are laid beside them
        if (dir.usableSpace < copy.length() / KeepCode.DATA * (KeepCode.GROUP + 1) + 64L * 1_048_576) return Unit.also { Log.i("Montana", "keep_cut refused: no room") }
        val cut = KeepCode.cut(copy, n, dir) ?: return Unit.also { Log.i("Montana", "keep_cut refused n=$n") }
        try {
            val roads = HashMap<Int, JSONObject>()
            for (k in 0 until n) {
                // the charger taken away (unless a new attachment made the copy due), the phone grown hot, a metered path: the rest
                // of the copy waits for the next renewal
                val why = bulkHeld(onBattery = urgent)
                if (why != null) { Log.i("Montana", "keep_hand held at share $k of $n: $why -- the rest waits for the next renewal"); return }
                Keeping.lay(cut.shares[k])?.let { roads[k] = it }
            }
            val sent = LinkedHashMap<String, Keeper>()
            var parts = 0
            for ((i, conv) in keepers.withIndex()) {
                val slots = KeepRing.slots(i, keepers.size).filter { roads[it] != null }
                for (k in slots) {
                    val secret = KeepDerive.slotSecret(master, k) ?: continue
                    val road = roads[k] ?: continue
                    val head = KeepDerive.Head(k, n, g, cut.length, cut.digest, cut.shareDigests[k])
                    val o = JSONObject().put("w", "part").put("k", k).put("n", n).put("s", Base64.encodeToString(secret, Base64.NO_WRAP))
                        .put("h", Wire.hex(head.bytes)).put("e", Wire.hex(KeepDerive.seal(key, head)))
                    for (a in road.keys()) o.put(a, road.get(a))
                    say(conv, o)
                    parts++
                }
                slots.firstOrNull()?.let { sent[conv] = Keeper(it, false, slots, emptyList()) }
            }
            if (sent.isEmpty()) return Unit.also { Log.i("Montana", "keep_hand no part left the phone") }
            synchronized(lock) {
                val o = owner()
                for (conv in o.keepers.keys) if (conv !in sent) say(conv, JSONObject().put("w", "release"))
                o.keepers = sent; o.n = n; o.generation = g; o.yes.clear(); o.short = false; o.renewedAt = now()
                saveOwner()
            }
            Log.i("Montana", "keep_hand g=$g keepers=${sent.size} parts=$parts laid=${roads.size} bytes=${cut.length}")
        } finally { cut.shares.forEach { it.delete() } }
    }

    // ── the light copy (iOS MTKeeping 652-702, 0be1c720): «everything else back by the words» ──
    @Volatile private var lighting = false
    const val LIGHT_NAME = "light"   // NOT-UI: the name the pipe of light is heard and sent under (iOS lightName)
    /** One's own face and name as their tags -- the very tags a correspondent's word compares (iOS profileTags, Presence.heardHeld). */
    fun profileTags(): Pair<String, String> =
        Presence.wireTag(SelfFace.bytes(Book.ctx)) to Presence.wireTag(Prefs.userName.trim().takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
    /**
     * The correspondents' names and faces and the groups as one digest (iOS peopleTag): it moves when a person is met, a group is
     * born, or someone shows a new name or face. A face stands by its file's moment and length: the digest is read only here.
     */
    fun peopleTag(): String {
        val refs = Book.refs().sorted()
        val names = refs.map { it + "=" + (Book.chat(it)?.name ?: "") }
        val faces = refs.mapNotNull { r -> Book.face(r).takeIf { it.exists() }?.let { r + "=" + it.lastModified() + "." + it.length() } }
        val all = (names + listOf("") + faces + listOf("") + Groups.keys().sorted()).joinToString("\n")
        return Wire.hex(MessageDigest.getInstance("SHA-256").digest(all.toByteArray(Charsets.UTF_8)))
    }
    /**
     * THE COPY WITHOUT ITS ATTACHMENTS (iOS layLight) -- every letter, every room, the profile, the faces and the head of every
     * correspondence -- sealed as every copy is, laid on the nodes and named by one letter in the pipe of light, once a day while the
     * copy is kept. The pieces live a week there and the letter in the box as long, so a phone lost today is opened by the words
     * alone tomorrow. It waits behind a renewal on the same thread: create clears the shelf, two copies never stand on it at once.
     */
    private fun layLight(face: String, name: String, people: String) {
        if (lighting) return
        lighting = true
        val g = synchronized(lock) { owner().let { o -> o.light.put("lightTriedAt", now()); saveOwner(); o.light.optLong("lightGen", 0L) + 1 } }
        bulk.execute {
            try {
                val c = Book.ctx
                val busy = MontanaBackup.shelf(c)?.listFiles { f -> f.name.endsWith(".part") && System.currentTimeMillis() - f.lastModified() < 600_000 }
                if (!busy.isNullOrEmpty()) { Log.i("Montana", "keep_light waits: another copy is being made"); return@execute }
                val s = CopyInventory.gather(c).scope(CopyPlan.load())
                // every letter, no attachment: the attachments come from the keepers
                val scope = CopyScope(s.skipConvs, CopyKind.values().toSet(), s.skipFiles, s.owner, s.skipPeople, s.skipFaces)
                val made = MontanaBackup.create(c, scope) {}
                if (made !is CopyResult.Made) Log.i("Montana", "keep_light refused " + ((made as? CopyResult.Refused)?.why?.javaClass?.simpleName ?: "?"))
                else try { nameLight(made, g, face, name, people) } finally { made.file.delete() }
            } catch (e: Throwable) { Log.w("Montana", "keep_light " + e.javaClass.simpleName) }
            finally { lighting = false }
        }
    }
    private fun nameLight(made: CopyResult.Made, g: Long, face: String, name: String, people: String) {
        val master = MontanaSeed.mnemonic?.let { MtBindings.nativeMnemonicToMasterSeed(it) }
        val secret = master?.let { KeepDerive.lightSecret(it) }
        val digest = shaHex(made.file)
        val road = if (secret == null || digest == null) null else Keeping.lay(made.file)
        if (secret == null || road == null) return Unit.also { Log.i("Montana", "keep_light the light copy did not reach the nodes") }
        road.put("w", "light").put("g", g).put("d", digest).put("v", Archive.writerHex() ?: "")   // the device that laid it
            .put("a", Book.ctx.packageName)   // and the app on it: two apps of one phone are two writers of the same words
        // The sender of the pipe of light is named «light», never this person's own reference: a phone opening the same words reads
        // the box under that reference, and a letter of its own name the node would keep from it.
        if (!Keeping.knock(secret, road, LIGHT_NAME)) return Unit.also { Log.i("Montana", "keep_light no door boxed the road") }
        synchronized(lock) {
            owner().light.put("lightGen", g).put("lightAt", now()).put("lightFace", face).put("lightName", name)
                .put("lightPeople", people).put("lightEngine", Card.ENGINE)
            saveOwner()
        }
        Log.i("Montana", "keep_light named g=$g bytes=${made.file.length()} chats=${made.tally.chats}")
    }

    // ── the words (iOS MTKeeping 446-476) ──
    /** A word of the keeping to a correspondent: a silent letter of the queue (Marks.SILENT), never a row. */
    private fun say(conv: String, o: JSONObject) { Post.send(conv, Marks.mintMid(), Keeping.MARK + o.toString()) }
    /** A keeping word landed in a correspondence (Pipes): never a row, answered here. */
    fun heard(text: String, conv: String) {
        if (!agrees) return
        val o = Keeping.word(text) ?: return
        val w = o.optString("w")
        Log.d("Montana", "keep_word w=" + w + " conv=" + conv.take(10))
        when (w) {
            "ask" -> answerAsk(conv, o)
            "yes" -> ex.execute { tookYes(conv, o) }
            "held" -> ex.execute { tookHeld(conv, o) }
            "part" -> ex.execute { runCatching { takePart(conv, o) }.onFailure { Log.w("Montana", "keep_part " + it.javaClass.simpleName) } }
            "release" -> drop(conv)
            "back" -> ex.execute { runCatching { answerBack(conv, o) }.onFailure { Log.w("Montana", "keep_back " + it.javaClass.simpleName) } }
            "given" -> ex.execute { runCatching { takeGiven(conv, o) }.onFailure { Log.w("Montana", "keep_back " + it.javaClass.simpleName) } }
        }
    }
    /** A question is answered only by a phone whose person allows keeping and whose budget has room; anything else is silence. */
    private fun answerAsk(conv: String, o: JSONObject) {
        val g = o.opt("g") as? Number
        if (!others || g == null || heldBytes(conv) >= budget) { Log.d("Montana", "keep_quiet conv=" + conv.take(10)); return }
        say(conv, JSONObject().put("w", "yes").put("g", g.toLong()))
    }
    private fun takePart(conv: String, o: JSONObject) {
        fun refuse(why: String = "refused in silence") { Log.d("Montana", "keep_part " + why) }
        if (!others) return refuse()
        val k = (o.opt("k") as? Number)?.toInt() ?: return refuse()
        val n = (o.opt("n") as? Number)?.toInt() ?: return refuse()
        val secret = runCatching { Base64.decode(o.optString("s"), Base64.DEFAULT) }.getOrNull()?.takeIf { it.size == 32 } ?: return refuse()
        val hd = unhex(o.optString("h")) ?: return refuse()
        val head = KeepDerive.Head.of(hd)?.takeIf { it.slot == k && it.n == n } ?: return refuse()
        val seal = unhex(o.optString("e"))?.takeIf { it.size == 32 } ?: return refuse()
        val size = (o.opt("z") as? Number)?.toLong()?.takeIf { it > 0 } ?: return refuse()
        val key = keyOf(conv, k)
        // the budget counts every other person's parts and this person's parts of the same copy: an older copy leaves below
        val sameCopy = synchronized(lock) { held().filter { convOf(it.key) == conv && it.key != key && it.value.generation == head.generation }.values.sumOf { it.bytes } }
        if (heldBytes(conv) + sameCopy + size > budget) return refuse()
        val dest = partFile(key) ?: return refuse()
        val landing = File(dest.path + ".part")
        if (!Keeping.fetch(o, landing) || shaHex(landing) != Wire.hex(head.shareDigest)) { landing.delete(); return refuse("the part did not come whole") }
        dest.delete()
        if (!landing.renameTo(dest)) { landing.delete(); return }
        val t = now()
        val road = HashMap<String, String>().apply { put("z", size.toString()); for (name in listOf("bk", "mref", "mk")) put(name, o.optString(name)) }
        synchronized(lock) {
            // A RENEWAL REPLACES (Network): this person's parts of an older copy leave as the newer one arrives
            for ((old, h) in held().toList()) if (convOf(old) == conv && old != key && h.generation < head.generation) dropKey(old)
            held()[key] = Held(k, n, secret, hd, seal, size, t, road = road, roadAt = t)
            roads.remove(key)
            saveHeld()
        }
        say(conv, JSONObject().put("w", "held").put("g", head.generation).put("d", Wire.hex(head.shareDigest)).put("k", k))
        Log.i("Montana", "keep_part held slot=$k of $n bytes=$size")
    }
    private fun dropKey(key: String) = synchronized(lock) { held().remove(key); roads.remove(key); partFile(key)?.delete() }
    fun drop(conv: String) {
        val c = convOf(conv)
        val keys = synchronized(lock) { held().keys.filter { convOf(it) == c } }
        if (keys.isEmpty()) return
        synchronized(lock) { keys.forEach(::dropKey); saveHeld() }
        Log.i("Montana", "keep_drop conv=" + c.take(10) + " parts=" + keys.size)
    }
    fun setOthers(on: Boolean) { Prefs.setBool(OTHERS, on); if (!on) keptFor.forEach(::drop) }

    /** The pipes of the slots this phone holds, read in the box beside every correspondence (iOS listening; Pipes box fetch). */
    fun listening(): Map<String, ByteArray> {
        val h = synchronized(lock) { held().mapValues { it.value.secret } }
        return if (h.isEmpty() || !agrees) emptyMap() else h
    }
    /** A row of the box under the pipe of a slot is this file's own: a call is answered, and the row is in hand either way. */
    fun takes(key: String, text: String) {
        if (Keeping.word(text)?.optString("w") == "call") ex.execute { runCatching { answerCall(key) }.onFailure { Log.w("Montana", "keep_answer " + it.javaClass.simpleName) } }
    }
    private fun answerCall(key: String) {
        val h = synchronized(lock) { held()[key] } ?: return
        val file = partFile(key)?.takeIf { it.exists() } ?: return
        if (!others) return
        val t = now()
        if (t - h.answeredAt < 600) return   // a call repeats every two minutes; one answer serves ten
        val r = h.road; val at = h.roadAt
        val road: JSONObject = if (r != null && at != null && t - at < 6 * 86_400 && r["z"]?.toLongOrNull() == h.bytes) {
            JSONObject().put("z", h.bytes).put("bk", r["bk"] ?: "").put("mref", r["mref"] ?: "").put("mk", r["mk"] ?: "")   // the owner's pieces, alive a week
        } else synchronized(lock) { roads[key]?.takeIf { t - it.first < 86_400 }?.second }?.let { JSONObject(it.toString()) } ?: run {
            val fresh = Keeping.lay(file) ?: return Unit.also { Log.d("Montana", "keep_answer the part did not reach the nodes") }
            synchronized(lock) { roads[key] = t to fresh }
            JSONObject(fresh.toString())
        }
        road.put("w", "answer").put("h", Wire.hex(h.head)).put("e", Wire.hex(h.seal))
        val twin = MontanaSeed.twin ?: return
        if (!Keeping.knock(h.secret, road, twin)) return Unit.also { Log.d("Montana", "keep_answer no door boxed it") }
        synchronized(lock) { h.answeredAt = t; saveHeld() }
        Log.i("Montana", "keep_answer slot=${h.slot} of ${h.n}")
    }

    // ── the copy taken back (iOS MTKeeping 380-391, 704-753, 1012-1121) ──
    /** Where the walk back stands: the light copy coming down, the keepers' parts in hand, a copy being laid, the end. */
    sealed class Taking {
        object Idle : Taking()
        class Fetching(val f: Double) : Taking()
        class Calling(val have: Int, val need: Int) : Taking()
        class Laying(val f: Double) : Taking()
        object Done : Taking()
        object Failed : Taking()
    }
    /** The screen that shows the walk (Settings, the first screen) listens here; called on the keeping's own thread. */
    @Volatile var watch: (() -> Unit)? = null
    @Volatile var taking: Taking = Taking.Idle
        private set(v) { field = v; watch?.invoke() }
    /** The first screen lets the person in the moment the light copy is laid (iOS onLight; App: «once the light copy is laid»). */
    @Volatile var onLight: (() -> Unit)? = null
    @Volatile private var takingRun: Thread? = null
    val isTaking: Boolean get() = takingRun != null
    const val CALL_PREFIX = "kslot:"   // NOT-UI: the pipes a phone opening the words calls in (iOS callPrefix)

    /** What a laid copy brought: this person's state of keeping is read again from the store (iOS reread). */
    private fun reread() = synchronized(lock) { ownerCache = null; heldCache = null }

    /**
     * THE LIGHT COPY TAKEN BACK (iOS takeLight): the pipe of light read, and the newest road OF EVERY DEVICE that names one there taken --
     * two devices of the same words count their generations each from one, so the highest number is no measure across them, and the
     * poorer copy could stand in for the richer. Each is checked by its digest and laid, the largest first; a copy laid merges and
     * erases nothing, so one laid after another adds only what it alone holds. The person stands in their correspondences from the
     * first laid. The box keeps the letters: the next phone of the same words reads them too.
     */
    private fun takeLight(secret: ByteArray, dir: File): Boolean {
        val newest = HashMap<String, JSONObject>()
        for ((_, text) in Post.pickup(mapOf(LIGHT_NAME to secret), take = false)) {
            val o = Keeping.word(text)?.takeIf { it.optString("w") == "light" } ?: continue
            // THE NEWEST ROAD OF EVERY WRITER, A DEVICE AND THE APP ON IT (iOS 09.10.2026): two apps of one phone and the same words
            // count their generations each from one, and one standing for both laid a copy without the other's profile
            val v = o.optString("v") + "/" + o.optString("a")
            if (o.optLong("g", 0L) >= (newest[v]?.optLong("g", -1L) ?: -1L)) newest[v] = o
        }
        val roads = newest.values.sortedByDescending { it.optLong("z", 0L) }
        if (roads.isEmpty()) return false.also { Log.i("Montana", "keep_light the pipe of light holds no road") }
        var laid = 0
        for (road in roads) {
            val d = road.optString("d").ifEmpty { null } ?: continue
            val f = File(dir, UUID.randomUUID().toString() + "." + MontanaBackup.EXT)
            try {
                taking = Taking.Fetching(0.0)
                if (!Keeping.fetch(road, f) || shaHex(f) != d) { Log.i("Montana", "keep_light a light copy did not come whole"); continue }
                taking = Taking.Laying(0.0)
                val r = MontanaBackup.restore(Book.ctx, f, ofMany = true) { taking = Taking.Laying(it) }
                reread()
                val own = road.optString("v") == (Archive.writerHex() ?: "")   // this device's own copy, or another's
                Log.i("Montana", "keep_light laid=" + r.javaClass.simpleName + " devices=" + roads.size + " own=" + own + " a=" + road.optString("a"))
                if (r !is CopyResult.Taken) continue
                laid++
                if (laid == 1) { val letIn = onLight; onLight = null; letIn?.invoke() }
            } finally { f.delete() }
        }
        return laid > 0
    }

    /**
     * THE COPY TAKEN BACK FROM THE PEOPLE ONE WRITES TO (iOS takeBack): the pipe of every slot is called, every answer's seal is checked
     * against the words, and the copy of the highest generation whose every group stands is built, checked against its digest and laid
     * by the one road of a copy (MontanaBackup.restore). The calls repeat every two minutes; the walk ends with the copy laid, or after
     * six hours without one. On a thread of its own: the walk sleeps between reads.
     */
    fun takeBack(done: (CopyResult) -> Unit = {}) = synchronized(lock) {
        if (takingRun != null) return@synchronized
        takingRun = Thread({
            val r = runCatching { callAndBuild() }.getOrElse {
                Log.w("Montana", "keep_build " + it.javaClass.simpleName); taking = Taking.Failed; CopyResult.Refused(Refusal.Empty)
            }
            synchronized(lock) { if (takingRun === Thread.currentThread()) takingRun = null }
            done(r)
        }, "keep-take").apply { isDaemon = true; start() }
    }
    fun stopTaking() {
        val t = synchronized(lock) { takingRun.also { takingRun = null } }
        t?.interrupt()
        if (taking is Taking.Calling) taking = Taking.Idle
    }

    private fun callAndBuild(): CopyResult {
        val master = MontanaSeed.mnemonic?.let { MtBindings.nativeMnemonicToMasterSeed(it) }
        val key = master?.let { KeepDerive.keepKey(it) }
        val dir = MontanaBackup.shelf(Book.ctx)
        if (!agrees || master == null || key == null || dir == null) { taking = Taking.Failed; return CopyResult.Refused(Refusal.NoSeed) }
        val secrets = (0 until KeepDerive.SLOTS).mapNotNull { KeepDerive.slotSecret(master, it) }
        if (secrets.size != KeepDerive.SLOTS) { taking = Taking.Failed; return CopyResult.Refused(Refusal.NoSeed) }
        val pipes = HashMap<String, ByteArray>()
        secrets.forEachIndexed { k, s -> pipes[CALL_PREFIX + k] = s }
        val twin = MontanaSeed.twin ?: ""
        val answers = HashMap<String, Pair<KeepDerive.Head, File>>()
        var lastCall = 0.0
        var lastLoud = 0.0
        val began = now()
        taking = Taking.Fetching(0.0)
        // THE LIGHT COPY FIRST (Network «The light copy»): the pipe of light is read before any call, and the person is let in;
        // the pipes it named are asked to give back
        KeepDerive.lightSecret(master)?.let { ls -> if (takeLight(ls, dir)) askBack() }
        taking = Taking.Calling(0, 0)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val t = now()
                if (t - lastCall >= 120) {
                    lastCall = t
                    var boxed = 0
                    for (s in secrets) if (Keeping.knock(s, JSONObject().put("w", "call"), twin)) boxed++
                    Log.i("Montana", "keep_call slots=${secrets.size} boxed=$boxed")
                }
                // IF NEEDED, A LOUD PUSH (iOS, the author's word 08.10.2026): six minutes of silence from a slot, and the call in its
                // pipe rings the keeper's screen with the owner's name, every ten minutes. The slots that answered the newest copy are
                // rung no more.
                if (t - began >= 360 && t - lastLoud >= 600) {
                    lastLoud = t
                    val newest = answers.values.maxByOrNull { it.first.generation }?.first
                    val heard = answers.values.filter { newest != null && it.first.copyDigest.contentEquals(newest.copyDigest) }.mapTo(HashSet()) { it.first.slot }
                    var rung = 0
                    for (k in 0 until (newest?.n ?: KeepDerive.SLOTS)) if (k !in heard && k < secrets.size) {
                        if (Keeping.knock(secrets[k], JSONObject().put("w", "call").put("loud", 1), twin, silent = false)) rung++
                    }
                    Log.i("Montana", "keep_call loud rung=$rung heard=${heard.size}")
                }
                for ((name, text) in Post.pickup(pipes, take = true)) {
                    val k = name.removePrefix(CALL_PREFIX).toIntOrNull() ?: continue
                    val o = Keeping.word(text)?.takeIf { it.optString("w") == "answer" } ?: continue
                    val head = unhex(o.optString("h"))?.let { KeepDerive.Head.of(it) }?.takeIf { it.slot == k } ?: continue
                    val seal = unhex(o.optString("e")) ?: continue
                    if (!seal.contentEquals(KeepDerive.seal(key, head))) continue
                    val id = Wire.hex(head.copyDigest) + "." + k
                    if (id in answers) continue
                    val f = File(dir, UUID.randomUUID().toString() + ".keep")
                    if (!Keeping.fetch(o, f) || shaHex(f) != Wire.hex(head.shareDigest)) { f.delete(); continue }
                    answers[id] = head to f
                    Log.i("Montana", "keep_took slot=$k of ${head.n} generation=${head.generation}")
                }
                // The copies in hand, the newest first; a copy stands when its parts hold twelve cells of every group.
                val ranked = answers.values.groupBy { Wire.hex(it.first.copyDigest) }.values.sortedByDescending { it.first().first.generation }
                ranked.firstOrNull()?.let { best -> taking = Taking.Calling(best.size, 3 * best.first().first.n / 4) }
                for (parts in ranked) {
                    val h0 = parts.first().first
                    val same = parts.filter { it.first.n == h0.n && it.first.length == h0.length }
                    if (h0.n <= 0 || same.size * (KeepCode.GROUP / h0.n) < KeepCode.DATA) continue
                    val out = File(dir, UUID.randomUUID().toString() + "." + MontanaBackup.EXT)
                    try {
                        val built = KeepCode.rebuild(same.map { KeepCode.Part(it.first.slot, it.first.n, it.second) }, h0.length, out)
                        if (!built || shaHex(out) != Wire.hex(h0.copyDigest)) { Log.i("Montana", "keep_build generation=${h0.generation} did not stand"); continue }
                        taking = Taking.Laying(0.0)
                        val r = MontanaBackup.restore(Book.ctx, out, ofMany = true) { taking = Taking.Laying(it) }
                        reread()   // the copy laid this person's state of keeping too
                        if (r is CopyResult.Taken) {
                            synchronized(lock) { owner().let { o -> o.generation = maxOf(o.generation, h0.generation) }; saveOwner() }
                            askBack()   // the pipes the full copy named, the ones the light copy did not already ask
                            taking = Taking.Done
                        } else taking = Taking.Failed
                        Log.i("Montana", "keep_build generation=${h0.generation} laid=" + r.javaClass.simpleName)
                        return r
                    } finally { out.delete() }
                }
                if (now() - began > 6 * 3600) { taking = Taking.Failed; return CopyResult.Refused(Refusal.Empty) }
                Thread.sleep(15_000)
            }
        } catch (stopped: InterruptedException) {
        } finally { answers.values.forEach { it.second.delete() } }
        taking = Taking.Idle
        return CopyResult.Refused(Refusal.Stopped)
    }

    // ── the correspondence given back (iOS MTKeeping 919-1010; Network «The correspondence given back»; the author's word 08.10.2026:
    // «by default every correspondent keeps the conversation with their correspondent and, if it is saved, gives it to the one who
    // restores») ──
    const val ASKED_BACK = "mt.keep.back.asked"   // NOT-UI: the correspondences this phone asked to give back, and when (a setting of this device)
    private val answeredBack = HashMap<String, Double>()
    private fun askedBack(): MutableMap<String, Double> = runCatching {
        val o = JSONObject(Prefs.str(ASKED_BACK, "{}"))
        o.keys().asSequence().associateWithTo(HashMap()) { o.optDouble(it, 0.0) }
    }.getOrElse { HashMap() }
    private fun lastLetter(conv: String): Double = (Book.chat(conv)?.msgs?.maxOfOrNull { it.at } ?: 0L) / 1000.0
    /** THE REQUEST: once a copy is laid, every correspondence of a person it names is asked in its own pipe, with the moment of the last
     *  letter laid there; the answer is awaited a week, as long as the pieces it rides live. */
    private fun askBack() {
        val t = now()
        val asked = askedBack().filterValues { t - it < 7 * 86_400 }.toMutableMap()
        var n = 0
        for (conv in people()) if (t - (asked[conv] ?: 0.0) >= 600) {
            say(conv, JSONObject().put("w", "back").put("a", lastLetter(conv)))
            asked[conv] = t; n++
        }
        val o = JSONObject(); asked.forEach { (k, v) -> o.put(k, v) }
        Prefs.setStr(ASKED_BACK, o.toString())
        Log.i("Montana", "keep_back asked=$n")
    }
    /**
     * THE ANSWER: the letters of this correspondence this phone holds, later than the asker's last, on the cargo road and named in the
     * same correspondence -- nothing kept for the purpose, nothing but this correspondence, and nothing while the person keeps nothing
     * for others. iOS lets a letter of the asker ride with the author field it came with; this phone keeps no such field on a row, so
     * the asker's own letters go without it, and their phone lays only the ones it can prove (takeGiven).
     */
    private fun answerBack(conv: String, o: JSONObject) {
        val t = now()
        if (!others || t - (answeredBack[conv] ?: 0.0) < 600) return Unit.also { Log.d("Montana", "keep_back quiet conv=" + conv.take(10)) }
        answeredBack[conv] = t
        val since = o.optDouble("a", 0.0)
        // A FILE'S ROW OF MINE RIDES TOO (iOS 82064e7a, OZ 2 p.1.4.1, the author's words 22141, 22151): the file I sent, under the wire
        // name it went by, with no words in «x» (a build before this one skips the row) and the archive's record in «md» -- the asker
        // takes it as its received letter and asks the file by that name (Media.serveKept).
        val rows = Book.chat(conv)?.msgs.orEmpty().filter {
            it.at / 1000.0 > since && (it.text.isNotEmpty() && it.file == null && !it.text.startsWith(Marks.MEDIA) ||
                it.mine && it.text.startsWith(Marks.MEDIA) && Groups.isLetterName(it.mid) && Archive.archivedText(it) != null)
        }
        if (rows.isEmpty()) { say(conv, JSONObject().put("w", "given").put("c", 0)); return }
        val list = org.json.JSONArray()
        for (r in rows) {
            val file = r.text.startsWith(Marks.MEDIA)
            val e = JSONObject().put("i", if (file) "mid:" + r.mid else Archive.wire(r.mid)).put("m", if (r.mine) 1 else 0).put("t", r.at / 1000.0)
                .put("x", if (file) "" else r.text)
            if (file) Archive.archivedText(r)?.let { e.put("md", it) }
            list.put(e)
        }
        val dir = MontanaBackup.shelf(Book.ctx) ?: return
        val f = File(dir, UUID.randomUUID().toString() + ".given")
        try {
            val body = list.toString().toByteArray(Charsets.UTF_8)
            f.writeBytes(body)
            val digest = shaHex(f)
            val road = digest?.let { Keeping.lay(f) } ?: return Unit.also { Log.i("Montana", "keep_back the letters did not reach the nodes") }
            road.put("w", "given").put("c", rows.size).put("d", digest)
            say(conv, road)
            Log.i("Montana", "keep_back gave=${rows.size} bytes=${body.size} conv=" + conv.take(10))
        } finally { f.delete() }
    }
    /**
     * THE CORRESPONDENCE TAKEN BACK: only an answer to this phone's own request, only the letters later than the last one in that
     * correspondence here -- a letter erased before the copy stays erased -- each on the side of the one who wrote it.
     * WHICH LETTER IS MINE IS SAID BY MY OWN WORDS (iOS, the critic R-6): a letter they name as theirs is theirs; one they name as mine
     * stands as mine only when the author field it rode with is made again under this person's key (Wire.authorHolds) -- else it is
     * not laid at all, and the count says so.
     */
    private fun takeGiven(conv: String, o: JSONObject) {
        val at = askedBack()[conv] ?: return
        if (now() - at >= 7 * 86_400 || o.optInt("c", 0) <= 0) return
        val d = o.optString("d").ifEmpty { null } ?: return
        val dir = MontanaBackup.shelf(Book.ctx) ?: return
        val f = File(dir, UUID.randomUUID().toString() + ".given")
        try {
            val list = if (Keeping.fetch(o, f) && shaHex(f) == d) runCatching { org.json.JSONArray(f.readText(Charsets.UTF_8)) }.getOrNull() else null
            if (list == null) return Unit.also { Log.i("Montana", "keep_back the given letters did not come whole") }
            // The copies' last letter is the last one older than the request: a letter that came live after it is no measure.
            val atMs = (at * 1000).toLong()
            val since = Book.chat(conv)?.msgs?.filter { it.at < atMs }?.maxOfOrNull { it.at } ?: 0L
            val secret = Book.secret(conv)
            val key = LetterAuthor.key
            var unproven = 0
            val rows = ArrayList<Msg>()
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val t = e.optDouble("t", 0.0)
                val ms = (t * 1000).toLong()
                val x = e.optString("x")
                if (ms <= since) continue
                // A FILE'S ROW OF THE OTHER SIDE (iOS 82064e7a, OZ 2 p.1.4.1): the giver's own sent file is this phone's received one,
                // named by the letter's wire name as a received file is (Media.file), so a tap asks the giver by that name and the
                // row takes the name on the copy's arrival (Media.nameRestored). A row of my own is not taken here: own: carries it.
                val md = e.optString("md")
                if (md.startsWith(Marks.MEDIA)) {
                    val w = e.optString("i")
                    val rec = runCatching { JSONObject(md.removePrefix(Marks.MEDIA)) }.getOrNull()
                    val k = rec?.optString("k").orEmpty()
                    val bare = w.removePrefix("mid:")
                    if (e.optInt("m", 0) != 1 || !w.startsWith("mid:") || !Groups.isLetterName(bare) || rec == null || k !in listOf("img", "vid", "aud", "doc")) continue
                    val ext = rec.optString("f").substringAfterLast('.', "")
                    val man = JSONObject().put("k", k).put("e", ext).put(Archive.KEPT_FILE, Media.file(Book.ctx, bare, ext).name)
                    rec.optString("n").takeIf { it.isNotEmpty() }?.let { man.put("n", it) }
                    rec.optString("cap").takeIf { it.isNotEmpty() }?.let { man.put("cap", it) }
                    rec.optDouble("d", 0.0).takeIf { it > 0 }?.let { man.put("du", it) }
                    rows.add(Msg(Archive.GIVEN_MID + w, Marks.MEDIA + man.toString(), false, ms, state = 3))
                    continue
                }
                if (x.isEmpty()) continue
                val wire = e.optString("i").ifEmpty { null }
                val claimedMine = e.optInt("m", 0) == 0   // the other side's own letter is the other side's here
                if (claimedMine) {
                    val bare = wire?.removePrefix("mid:") ?: ""
                    if (bare.isEmpty() || secret == null || !Wire.authorHolds(e.optString("a"), key, secret, bare, x)) { unproven++; continue }
                }
                rows.add(Msg(Archive.GIVEN_MID + (wire ?: t.toString()), x, claimedMine, ms, state = if (claimedMine) 1 else 3))
            }
            val laid = Book.layGivenBack(conv, rows)
            Log.i("Montana", "keep_back took=$laid of ${list.length()} unproven_mine=$unproven conv=" + conv.take(10))
        } finally { f.delete() }
    }

    private fun shaHex(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { s -> val b = ByteArray(65536); while (true) { val r = s.read(b); if (r < 0) break; md.update(b, 0, r) } }
        Wire.hex(md.digest())
    }.getOrNull()
    private fun unhex(h: String): ByteArray? {
        if (h.length % 2 != 0 || h.isEmpty()) return null
        return runCatching { ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()
    }
}
