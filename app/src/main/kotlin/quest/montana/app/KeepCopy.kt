package quest.montana.app

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

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
}
