package quest.montana.app

// ─────────────────────────── LZFSE, the iPhone's packing (Apple's Compression framework, .lzfse) ───────────────────────────

/**
 * THE iPHONE'S PACKING (iOS `NSData.compressed(using: .lzfse)` / `decompressed(using: .lzfse)`): Android has no LZFSE, neither in
 * the system nor in our core, and the coin book the iPhone lays on its nodes is LZFSE inside the seal (iOS MTCoinVault). This is
 * our own codec, no library.
 *
 * A stream is blocks, each opened by four letters, ended by «bvx$»:
 * - «bvx-» a raw block: its length, then the bytes as they are;
 * - «bvxn» an LZVN block: the bytes it makes, the bytes it takes, then LZVN opcodes (iOS writes these for small inputs);
 * - «bvx1» / «bvx2» an LZFSE block: literals and (L, M, D) triples, each coded by finite state entropy and read backwards;
 *   «bvx2» packs the header's frequency tables, «bvx1» writes them out.
 *
 * [unpack] reads every kind. [pack] writes the raw kind only — the iPhone reads it as any other, it only does not shrink;
 * the book is small and travels sealed in pieces either way.
 */
object Lzfse {
    private const val END = 0x24787662        // bvx$
    private const val RAW = 0x2d787662        // bvx-
    private const val V1 = 0x31787662         // bvx1
    private const val V2 = 0x32787662         // bvx2
    private const val LZVN = 0x6e787662       // bvxn

    private const val L_SYMBOLS = 20
    private const val M_SYMBOLS = 20
    private const val D_SYMBOLS = 64
    private const val LITERAL_SYMBOLS = 256
    private const val L_STATES = 64
    private const val M_STATES = 64
    private const val D_STATES = 256
    private const val LITERAL_STATES = 1024
    private const val MATCHES_PER_BLOCK = 10000
    private const val LITERALS_PER_BLOCK = 4 * MATCHES_PER_BLOCK

    private val L_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 5, 8)
    private val M_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 5, 8, 11)
    private val D_EXTRA = IntArray(D_SYMBOLS) { it / 4 }      // 0,0,0,0, 1,1,1,1, … 15,15,15,15
    private val L_BASE = base(L_EXTRA)
    private val M_BASE = base(M_EXTRA)
    private val D_BASE = base(D_EXTRA)

    /** each symbol's first value: the one before it plus the values its extra bits can carry */
    private fun base(extra: IntArray): IntArray = IntArray(extra.size).also { b -> for (i in 1 until extra.size) b[i] = b[i - 1] + (1 shl extra[i - 1]) }

    // ── packing: raw blocks the iPhone reads as any other ──

    fun pack(plain: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(plain.size + 16)
        fun u32(v: Int) { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
        if (plain.isNotEmpty()) { u32(RAW); u32(plain.size); out.write(plain) }
        u32(END)
        return out.toByteArray()
    }

    // ── the check at launch: streams Apple's own encoder wrote (macOS libcompression, COMPRESSION_LZFSE) ──

    /** 240 coin-like JSON pieces, 4800 bytes: Apple writes them as one «bvx2» block; their first 300 bytes as «bvxn» */
    private fun katPlain(): ByteArray {
        val kinds = arrayOf("earn", "send", "spend", "receive")
        return (0 until 240).joinToString("") { "{\"k\":\"${kinds[it % 4]}\",\"c\":${it % 10}}," }.toByteArray()
    }
    private const val KAT_V2 = "YnZ4MsASAAA0AOABABwAIN1XEPQVIwBgpQAAAD8MQA37N4BAAAACBIDNWgHgHYD1slwAABenAHABAHABAAAAAAAAAAAAAAAAAAAAAAB8GAAA/AjAj/AP/AP/wD/wD/wD" +
        "/8A/8A/8AwAAAAAAAAAAAHDMP3D8SgDHHMM/wDH/wDEcAxxzDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACeIGdru3T6KuRXLbkdpfntrFcoR6qLYYhIPIoEAAAAAAAAAAAA" +
        "dhqk/P++qv2qKCOkDJFqhIa/Y4ciiITPmmtidngk"
    private const val KAT_VN = "YnZ4biwBAABgAAAA4AN7ImsiOiJlYXJuIiwiYyI6MH0sGBPkc2VuZPZuMfFoFHDxbjLncmVjZWl2ZRgWbjM4UG40+m41+242/W43+m44+m45+24w/W4x+m4y+m4z" +
        "++U0fSx7IgYAAAAAAAAAYnZ4JA=="

    /** true when both Apple streams unpack to their bytes, our raw packing comes back, and a cut stream is refused */
    fun kat(): Boolean = try {
        val plain = katPlain()
        val b64 = java.util.Base64.getDecoder()
        val v2 = b64.decode(KAT_V2)
        unpack(v2)?.contentEquals(plain) == true &&
            unpack(b64.decode(KAT_VN))?.contentEquals(plain.copyOf(300)) == true &&
            unpack(pack(plain))?.contentEquals(plain) == true &&
            unpack(v2.copyOf(v2.size - 4)) == null
    } catch (_: Exception) { false }

    // ── unpacking: every kind of block ──

    /** the bytes the stream holds, or null when it is not a whole LZFSE stream (a broken piece never passes as a book) */
    fun unpack(packed: ByteArray): ByteArray? = try { Reader(packed).all() } catch (_: RuntimeException) { null }

    private class Bad : RuntimeException()

    private class Sink(cap: Int) {
        var buf = ByteArray(maxOf(cap, 64))
        var n = 0
        fun room(more: Int) {
            if (more < 0 || n + more < 0) throw Bad()
            if (n + more > buf.size) buf = buf.copyOf(maxOf(n + more, buf.size * 2))
        }
        fun put(src: ByteArray, at: Int, len: Int) {
            if (at < 0 || len < 0 || at + len > src.size) throw Bad()
            room(len); System.arraycopy(src, at, buf, n, len); n += len
        }
        /** M bytes from D behind the end; byte by byte, the copy may read what it just wrote */
        fun match(d: Int, m: Int) {
            if (m == 0) return
            if (d <= 0 || d > n) throw Bad()
            room(m)
            var from = n - d
            repeat(m) { buf[n++] = buf[from++] }
        }
    }

    private class Reader(val src: ByteArray) {
        val out = Sink(src.size * 3)

        fun u32(at: Int): Int {
            if (at < 0 || at + 4 > src.size) throw Bad()
            return (src[at].toInt() and 0xff) or ((src[at + 1].toInt() and 0xff) shl 8) or
                ((src[at + 2].toInt() and 0xff) shl 16) or ((src[at + 3].toInt() and 0xff) shl 24)
        }
        fun u64(at: Int): Long = (u32(at).toLong() and 0xffffffffL) or (u32(at + 4).toLong() shl 32)

        fun all(): ByteArray {
            var p = 0
            while (true) {
                when (u32(p)) {
                    END -> return out.buf.copyOf(out.n)
                    RAW -> {
                        val n = u32(p + 4)
                        out.put(src, p + 8, n)
                        p += 8 + n
                    }
                    LZVN -> {
                        val raw = u32(p + 4); val size = u32(p + 8)
                        if (raw < 0 || size < 0 || p + 12 + size > src.size) throw Bad()
                        lzvn(p + 12, p + 12 + size, out.n + raw)
                        p += 12 + size
                    }
                    V1 -> p = fse(v1(p))
                    V2 -> p = fse(v2(p))
                    else -> throw Bad()
                }
            }
        }

        // ── LZVN: one opcode byte says how many literals follow it, how long a match, from how far behind ──

        fun lzvn(from: Int, to: Int, end: Int) {
            var p = from
            var d = 0
            fun at(i: Int): Int { if (i >= to) throw Bad(); return src[i].toInt() and 0xff }
            while (out.n < end) {
                val op = at(p)
                var l = 0; var m = 0; var len = 1
                when {
                    op == 0x06 -> break                                                  // end of stream
                    op == 0x0e || op == 0x16 -> { p += 1; continue }                     // nop
                    op in 0x70..0x7f || op in 0xd0..0xdf -> throw Bad()                  // undefined
                    op in 0xa0..0xbf -> {                                                // medium distance: 101LLMMM DDDDDDMM DDDDDDDD
                        val b1 = at(p + 1); val b2 = at(p + 2)
                        l = (op shr 3) and 3; m = (((op and 7) shl 2) or (b1 and 3)) + 3; d = (b2 shl 6) or (b1 shr 2); len = 3
                    }
                    op == 0xe0 -> { l = at(p + 1) + 16; len = 2 }                        // large literal
                    op in 0xe1..0xef -> l = op and 0x0f                                  // small literal
                    op == 0xf0 -> { m = at(p + 1) + 16; len = 2 }                        // large match, previous distance
                    op in 0xf1..0xff -> m = op and 0x0f                                  // small match, previous distance
                    (op and 7) == 7 -> {                                                 // large distance: LLMMM111 D16
                        l = op shr 6; m = ((op shr 3) and 7) + 3; d = at(p + 1) or (at(p + 2) shl 8); len = 3
                    }
                    (op and 7) == 6 -> {                                                 // previous distance: LLMMM110
                        if (op < 0x40) throw Bad()
                        l = op shr 6; m = ((op shr 3) and 7) + 3
                    }
                    else -> {                                                            // small distance: LLMMMDDD DDDDDDDD
                        l = op shr 6; m = ((op shr 3) and 7) + 3; d = ((op and 7) shl 8) or at(p + 1); len = 2
                    }
                }
                p += len
                if (l > 0) { if (p + l > to) throw Bad(); out.put(src, p, l); p += l }
                if (m > 0) out.match(d, m)
            }
            if (out.n != end) throw Bad()
        }

        // ── LZFSE blocks ──

        inner class Head {
            var raw = 0; var payload = 0; var literals = 0; var matches = 0
            var litBytes = 0; var lmdBytes = 0; var litBits = 0; var lmdBits = 0
            val litState = IntArray(4); var lState = 0; var mState = 0; var dState = 0
            val lFreq = IntArray(L_SYMBOLS); val mFreq = IntArray(M_SYMBOLS); val dFreq = IntArray(D_SYMBOLS); val litFreq = IntArray(LITERAL_SYMBOLS)
            var start = 0; var size = 0
        }

        fun u16(at: Int): Int { if (at + 2 > src.size) throw Bad(); return (src[at].toInt() and 0xff) or ((src[at + 1].toInt() and 0xff) shl 8) }

        /** «bvx1»: every field written out, 770 bytes */
        fun v1(p: Int): Head = Head().apply {
            start = p; size = 770
            raw = u32(p + 4); payload = u32(p + 8); literals = u32(p + 12); matches = u32(p + 16)
            litBytes = u32(p + 20); lmdBytes = u32(p + 24); litBits = u32(p + 28)
            for (i in 0 until 4) litState[i] = u16(p + 32 + 2 * i)
            lmdBits = u32(p + 40); lState = u16(p + 44); mState = u16(p + 46); dState = u16(p + 48)
            var q = p + 50
            for (t in arrayOf(lFreq, mFreq, dFreq, litFreq)) for (i in t.indices) { t[i] = u16(q); q += 2 }
        }

        /** «bvx2»: the counts in three packed words, the frequency tables in a variable-length code */
        fun v2(p: Int): Head = Head().apply {
            start = p
            raw = u32(p + 4)
            val w0 = u64(p + 8); val w1 = u64(p + 16); val w2 = u64(p + 24)
            fun f(v: Long, off: Int, bits: Int): Int = ((v ushr off) and ((1L shl bits) - 1)).toInt()
            literals = f(w0, 0, 20); litBytes = f(w0, 20, 20); matches = f(w0, 40, 20); litBits = f(w0, 60, 3) - 7
            for (i in 0 until 4) litState[i] = f(w1, 10 * i, 10)
            lmdBytes = f(w1, 40, 20); lmdBits = f(w1, 60, 3) - 7
            size = f(w2, 0, 32); lState = f(w2, 32, 10); mState = f(w2, 42, 10); dState = f(w2, 52, 10)
            payload = litBytes + lmdBytes
            if (size < 32 || p + size > src.size) throw Bad()
            var q = p + 32
            val end = p + size
            if (q == end) return@apply
            var acc = 0L; var accBits = 0
            for (t in arrayOf(lFreq, mFreq, dFreq, litFreq)) for (i in t.indices) {
                while (q < end && accBits + 8 <= 32) { acc = acc or ((src[q].toLong() and 0xff) shl accBits); accBits += 8; q++ }
                val b = (acc and 31).toInt()
                val n = FREQ_BITS[b]
                t[i] = when (n) { 8 -> 8 + ((acc ushr 4) and 0xf).toInt(); 14 -> 24 + ((acc ushr 4) and 0x3ff).toInt(); else -> FREQ_VALUE[b] }
                if (n > accBits) throw Bad()
                acc = acc ushr n; accBits -= n
            }
            if (accBits >= 8 || q != end) throw Bad()
        }

        /** literals first (four interleaved states), then the (L, M, D) triples; both read backwards from their payload's end */
        fun fse(h: Head): Int {
            if (h.literals < 0 || h.literals > LITERALS_PER_BLOCK || h.literals % 4 != 0 || h.matches < 0 || h.matches > MATCHES_PER_BLOCK) throw Bad()
            if (h.litBytes < 0 || h.lmdBytes < 0 || h.litBits !in -7..0 || h.lmdBits !in -7..0) throw Bad()
            if (h.litState.any { it >= LITERAL_STATES } || h.lState >= L_STATES || h.mState >= M_STATES || h.dState >= D_STATES) throw Bad()
            val litTable = symbolTable(LITERAL_STATES, h.litFreq)
            val lTable = valueTable(L_STATES, h.lFreq, L_EXTRA, L_BASE)
            val mTable = valueTable(M_STATES, h.mFreq, M_EXTRA, M_BASE)
            val dTable = valueTable(D_STATES, h.dFreq, D_EXTRA, D_BASE)

            val litStart = h.start + h.size
            val litEnd = litStart + h.litBytes
            val lmdEnd = litEnd + h.lmdBytes
            if (lmdEnd > src.size || lmdEnd < litStart) throw Bad()

            // both streams may read back past their own start into the header (as Apple's reader does: a stream of zero bits still
            // loads its first seven bytes), so their floor is the buffer's start
            val lit = ByteArray(h.literals + 64)
            val a = Bits(0, litEnd, h.litBits)
            val s = h.litState.copyOf()
            var i = 0
            while (i < h.literals) {
                a.flush()
                for (k in 0 until 4) {
                    val e = litTable[s[k]]
                    s[k] = (e shr 16) + a.pull(e and 0xff).toInt()
                    lit[i + k] = (e ushr 8).toByte()
                }
                i += 4
            }

            val b = Bits(0, lmdEnd, h.lmdBits)
            var ls = h.lState; var ms = h.mState; var ds = h.dState
            var d = -1
            var li = 0
            val goal = out.n + h.raw
            repeat(h.matches) {
                b.flush()
                val lv = value(lTable, ls, b); ls = lv.second
                val mv = value(mTable, ms, b); ms = mv.second
                val dv = value(dTable, ds, b); ds = dv.second
                val l = lv.first; val m = mv.first
                if (dv.first != 0) d = dv.first
                if (li + l > h.literals) throw Bad()
                out.put(lit, li, l); li += l
                if (m > 0) out.match(d, m)
            }
            if (out.n != goal) throw Bad()
            return lmdEnd
        }

        /** (value, next state) from one value-decoder entry */
        fun value(t: LongArray, state: Int, b: Bits): Pair<Int, Int> {
            if (state < 0 || state >= t.size) throw Bad()
            val e = t[state]
            val total = (e and 0xff).toInt(); val vbits = ((e ushr 8) and 0xff).toInt()
            val delta = ((e ushr 16) and 0xffff).toInt(); val vbase = (e ushr 32).toInt()
            val sv = b.pull(total)
            val next = delta + (sv ushr vbits).toInt()
            val v = vbase + (sv and ((1L shl vbits) - 1)).toInt()
            return Pair(v, next)
        }

        /** a backward bit stream: bytes come from the end of the payload toward its start, the first word may be part-filled */
        inner class Bits(val begin: Int, var p: Int, n: Int) {
            var acc = 0L
            var bits = 0
            init {
                val take = if (n != 0) 8 else 7
                if (p - take < begin) throw Bad()
                p -= take
                for (k in 0 until take) acc = acc or ((src[p + k].toLong() and 0xff) shl (8 * k))
                bits = n + take * 8
                if (bits < 56 || bits >= 64 || (acc ushr bits) != 0L) throw Bad()
            }
            fun flush() {
                val n = (63 - bits) and -8
                val bytes = n shr 3
                if (p - bytes < begin) throw Bad()
                p -= bytes
                var incoming = 0L
                for (k in 0 until bytes) incoming = incoming or ((src[p + k].toLong() and 0xff) shl (8 * k))
                acc = if (n == 0) acc else (acc shl n) or incoming
                bits += n
            }
            fun pull(n: Int): Long {
                if (n == 0) return 0L
                bits -= n
                if (bits < 0) throw Bad()
                val r = acc ushr bits
                acc = if (bits == 0) 0L else acc and ((1L shl bits) - 1)
                return r
            }
        }
    }

    private val FREQ_BITS = intArrayOf(2, 3, 2, 5, 2, 3, 2, 8, 2, 3, 2, 5, 2, 3, 2, 14, 2, 3, 2, 5, 2, 3, 2, 8, 2, 3, 2, 5, 2, 3, 2, 14)
    private val FREQ_VALUE = intArrayOf(0, 2, 1, 4, 0, 3, 1, -1, 0, 2, 1, 5, 0, 3, 1, -1, 0, 2, 1, 6, 0, 3, 1, -1, 0, 2, 1, 7, 0, 3, 1, -1)

    /** a literal decoder: per state (delta << 16) | (symbol << 8) | k */
    private fun symbolTable(states: Int, freq: IntArray): IntArray {
        val t = IntArray(states)
        val nClz = Integer.numberOfLeadingZeros(states)
        var sum = 0; var at = 0
        for (s in freq.indices) {
            val f = freq[s]
            if (f == 0) continue
            sum += f
            if (sum > states) throw Bad()
            val k = Integer.numberOfLeadingZeros(f) - nClz
            val j0 = ((2 * states) shr k) - f
            for (j in 0 until f) {
                t[at++] = if (j < j0) ((((f + j) shl k) - states) shl 16) or (s shl 8) or k
                else (((j - j0) shl (k - 1)) shl 16) or (s shl 8) or (k - 1)
            }
        }
        return t
    }

    /** an L, M or D decoder: per state vbase << 32 | delta << 16 | value bits << 8 | total bits */
    private fun valueTable(states: Int, freq: IntArray, extra: IntArray, base: IntArray): LongArray {
        val t = LongArray(states)
        val nClz = Integer.numberOfLeadingZeros(states)
        var sum = 0; var at = 0
        for (s in freq.indices) {
            val f = freq[s]
            if (f == 0) continue
            sum += f
            if (sum > states) throw Bad()
            val k = Integer.numberOfLeadingZeros(f) - nClz
            val j0 = ((2 * states) shr k) - f
            for (j in 0 until f) {
                val total: Int; val delta: Int
                if (j < j0) { total = k + extra[s]; delta = ((f + j) shl k) - states }
                else { total = k - 1 + extra[s]; delta = (j - j0) shl (k - 1) }
                t[at++] = (base[s].toLong() shl 32) or ((delta.toLong() and 0xffff) shl 16) or (extra[s].toLong() shl 8) or total.toLong()
            }
        }
        return t
    }
}
