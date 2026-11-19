package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * THIS DEVICE'S LANE OF THE TIMECHAIN (iOS MTTimeChainTip, MTWalletCore 1114-1366; the author's words 04.10.2026 16:34 UTC: «a coin of
 * another device within seconds»): the moves this device alone bore join its own lane -- a running balance with each, the window keeps
 * the last thirteen, and every link is sealed over the one before (SHA-256), so the head seals the lane to its birth. The tip leaves
 * for the nodes at most once in two seconds, sealed under the seed's vault key (ChaCha20-Poly1305 bound to «mt.timechain.tip») on the
 * row «mt-timechain-tip-token»; the other devices of the words read every lane in one round trip to the first node that answers. A
 * lane's new links join the book by their names; a lane further behind than the window, or a window whose seal does not follow the
 * head taken, asks the whole book (CoinVault). `seen` is the balance the freshest tip of another device told after it gathered the
 * book: the wallet shows no less than that until this device has heard the book itself.
 *
 * THE LANE CARRIES WHAT THE DEVICE BORE (the author's «yes» 04.10.2026 14:16 UTC to variant (a)): a letter's coin and a coin letter
 * reach every device of the words by the letter itself, so they ride no lane -- a sum of lanes would count them once a device.
 *
 * A NODE THAT KEEPS NO COPY (decision 1060 = a, 17.11.2026): a door that answers 403 to a put is sent no tip again until the seat
 * moves or the app starts anew, as CoinVault does; its /vault-get is still asked.
 */
object TimeChainTip {
    const val DEPTH = 13
    private const val FILE = "timechain-tip.bin"                      // NOT-UI: the tip's file name
    private val aad = "mt.timechain.tip".toByteArray()                // NOT-UI: the words the tip's seal is bound to
    private val kinds = listOf(CoinEntry.EARN, CoinEntry.RECEIVE, CoinEntry.SPEND, CoinEntry.SEND, CoinEntry.BURN)

    class Link(val n: Long, val at: Long, val k: Int, val c: Long, val b: Long, val ref: String, val peer: String, val on: String) {
        val signed: Long get() = if (k < 2) c else -c
        override fun equals(other: Any?) = other is Link && n == other.n && at == other.at && k == other.k && c == other.c &&
            b == other.b && ref == other.ref && peer == other.peer && on == other.on
        override fun hashCode() = (n * 31 + at).hashCode()
    }
    class Tip(var lane: ByteArray, var seen: Long = 0, var heard: Boolean = false, var prev: ByteArray = ByteArray(32),
              val links: ArrayList<Link> = ArrayList())

    /** The balance of the whole book the freshest tip of another device told, written after that device had gathered the book. */
    @Volatile var seen: Long? = null; private set

    // everything below lives on the lane's own thread
    private val queue = Executors.newSingleThreadExecutor { r -> Thread(r, "montana-timechain-tip") }
    private var tip: Tip? = null
    private val heads = HashMap<String, Pair<Long, ByteArray>>()   // another device's lane (hex): the last link of it this device took
    private var seenAt = 0L
    private val opened = LinkedHashMap<String, ByteArray>()        // a piece's name: its lane, opened once
    private val mine = HashMap<String, String>()                   // a node's door: the piece of this lane it holds now
    private val stale = HashSet<String>()                          // pieces of this lane from before, gone at the next put
    private val relay = HashSet<String>()                          // the doors that keep no copy (403): no tip is sent to them
    private var checked = false
    @Volatile private var putDue = false
    @Volatile private var putAt = 0.0
    private val getQueued = AtomicBoolean(false)

    private fun nowS() = System.currentTimeMillis() / 1000.0
    private fun file() = File(File(Book.ctx.filesDir, "Coins").apply { mkdirs() }, FILE)

    /** The person lifted into the seat: the lane of the person who left goes with them (its file left with their book). */
    fun reread() {
        seen = null
        queue.execute { tip = null; heads.clear(); seenAt = 0; opened.clear(); mine.clear(); stale.clear(); relay.clear() }
    }

    /** This device's lane, read once from its file; born at the first ask where none stands. A file that stands and would not open
     *  is never written over: the lane waits for the next ask. */
    private fun lane(): Tip? {
        tip?.let { return it }
        val f = file()
        if (f.exists()) {
            val read = runCatching { decode(f.readBytes()) }.getOrNull() ?: return null
            if (read.lane.size != 16) return null
            tip = read
        } else {
            tip = Tip(ByteArray(16).also { SecureRandom().nextBytes(it) })
        }
        return tip
    }

    /** This device's own moves (CoinBook.take, iOS MTLocalCoinLedger.keep) join its lane; the tip leaves within two seconds. */
    fun add(moves: List<CoinEntry>, balance: Long) {
        if (!GooglePlay.COINS || moves.isEmpty()) return
        queue.execute {
            val t = lane() ?: return@execute
            for (e in moves) {
                if (!bore(e)) continue
                val k = kinds.indexOf(e.k)
                if (k < 0 || e.c <= 0 || listOf(e.ref, e.peer ?: "", e.on ?: "").any { it.toByteArray().size > 1024 }) continue
                val signed = if (k < 2) e.c else -e.c
                val last = t.links.lastOrNull()
                val was = last?.b ?: 0L
                val b = was + signed
                if (((was xor b) and (signed xor b)) < 0) continue   // a sum the integer cannot hold is no link
                t.links.add(Link((last?.n ?: 0L) + 1, TimeChain.ms(e.at), k, e.c, b, e.ref, e.peer ?: "", e.on ?: ""))
                if (DEPTH < t.links.size) t.prev = seal(t.prev, t.links.removeAt(0))
            }
            t.seen = balance
            t.heard = CoinVault.heard
            runCatching {
                val f = file(); val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeBytes(encode(t)); tmp.renameTo(f)
            }
            soonPut()
        }
    }

    /** iOS bore: the Pantheon, the VPN wall, chess and its Timer, the pull, spending, sending, burning -- never a letter's coin. */
    fun bore(e: CoinEntry): Boolean {
        val s = TimeChain.source(e)
        return !(e.k == CoinEntry.EARN && (s == "chats" || s == "groups" || s == "channels")) && !(e.k == CoinEntry.RECEIVE && s != "chess")
    }

    /** The tips leave at most once in two seconds: the moves of those seconds ride one piece. */
    private fun soonPut() {
        if (putDue) return
        putDue = true
        val wait = maxOf(0.3, putAt + 2 - nowS())
        MainThread.later((wait * 1000).toLong(), Runnable { queue.execute { put() } })
    }

    private fun post(door: String, path: String, body: JSONObject, timeoutMs: Int): Pair<Int, JSONObject?> {
        val (code, text) = Wire.post(door, path, body, timeoutMs)
        return code to text?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun put() {
        putDue = false
        putAt = nowS()
        val t = tip ?: return
        val e = HomeShelf.entropy() ?: return
        val k = CoinVault.keys(e)
        val token = hex(CoinVault.tipToken(e))
        e.fill(0)
        val sealed = sealTip(encode(t), k.seal) ?: return
        val id = hex(MessageDigest.getInstance("SHA-256").digest(sealed))
        var laid = 0
        for (door in Doors.onePerNode(Doors.ordered("vault"))) {
            if (door in relay) continue
            // A piece named in its own replace would be taken off and not laid again (the node checks before it deletes).
            val gone = (stale + listOfNotNull(mine[door])).filter { it != id }
            val (code, o) = post(door, "/vault-put", JSONObject().put("token", token)
                .put("blob", Base64.encodeToString(sealed, Base64.NO_WRAP)).put("replace", JSONArray(gone)), 10_000)
            if (code == 403) { relay.add(door); continue }
            val piece = o?.optString("piece")?.takeIf { code == 200 && it.isNotEmpty() } ?: continue
            mine[door] = piece
            opened[piece] = t.lane
            laid++
        }
        if (0 < laid) stale.clear()
        Log.i("Montana", "tip_put n=${t.links.lastOrNull()?.n ?: 0} b=${t.links.lastOrNull()?.b ?: 0} bytes=${sealed.size} doors=$laid relay=${relay.size}")
    }

    /** The other devices' tips in one round trip (iOS gather): asked at the return, the wallet's opening, and every three seconds
     *  while the wallet stands open. One ask waits at most; the rest fold into it. */
    fun gatherSoon(why: String) {
        if (!GooglePlay.COINS || !getQueued.compareAndSet(false, true)) return
        queue.execute { getQueued.set(false); gather(why) }
    }

    private fun gather(why: String) {
        val e = HomeShelf.entropy() ?: return
        val k = CoinVault.keys(e)
        val token = hex(CoinVault.tipToken(e))
        e.fill(0)
        val gen = CoinBook.generation   // a seat moved during the wait joins nothing
        if (!checked) { checked = true; Log.i("Montana", "tip_self ok=${if (selfCheck()) 1 else 0}") }
        val own = lane()?.lane
        val fresh = ArrayList<CoinEntry>()
        var lanes = 0; var gap = false; var broken = 0; var answered = false
        for (door in Doors.onePerNode(Doors.ordered("vault"))) {
            val (code, o) = post(door, "/vault-get", JSONObject().put("token", token).put("have", JSONArray(opened.keys.take(256))), 6_000)
            val held = o?.optJSONArray("held")
            if (code != 200 || held == null) continue
            if (gen != CoinBook.generation) { Log.i("Montana", "tip_get why=$why the seat moved -- nothing joined"); return }
            answered = true
            val ps = o.optJSONArray("pieces")
            for (i in 0 until (ps?.length() ?: 0)) {
                val x = ps!!.optJSONObject(i) ?: continue
                val id = x.optString("piece")
                val b = runCatching { Base64.decode(x.optString("blob"), Base64.DEFAULT) }.getOrNull() ?: continue
                if (hex(MessageDigest.getInstance("SHA-256").digest(b)) != id) continue   // a piece is its own name
                val t = openTip(b, k.seal)?.let { runCatching { decode(it) }.getOrNull() }?.takeIf { it.lane.size == 16 } ?: continue
                opened[id] = t.lane
                if (own != null && t.lane.contentEquals(own)) { if (mine[door] != id) stale.add(id); continue }
                lanes++
                val first = t.links.firstOrNull() ?: continue
                val last = t.links.last()
                val seals = chain(t)
                val laneKey = hex(t.lane)
                val had = heads[laneKey]
                if (!whole(t) || !follows(had, t, seals)) { broken++; gap = true; continue }
                val hadN = had?.first ?: 0L
                if (last.n <= hadN) continue
                if (hadN + 1 < first.n) gap = true   // further behind than the window: the book carries the rest
                t.links.filter { hadN < it.n }.mapTo(fresh) { entry(it) }
                heads[laneKey] = last.n to seals.last()
                if (t.heard && seenAt < last.at) { seenAt = last.at; seen = t.seen }
            }
            val standing = (0 until held.length()).map { held.optString(it) }.toSet()
            opened.keys.retainAll(standing)   // a piece no node keeps is forgotten here too
            break
        }
        if (!answered) return
        val joined = if (fresh.isEmpty()) 0 else CoinBook.join(fresh)
        if (gap) CoinVault.soon("tip", 1.0)
        if (0 < joined || gap || why != "live") Log.i("Montana", "tip_get why=$why lanes=$lanes joined=$joined gap=${if (gap) 1 else 0} broken=$broken seen=${seen ?: -1}")
    }

    // ── the seal: ChaChaPoly combined = nonce 12 ‖ ciphertext ‖ tag 16, bound to «mt.timechain.tip» ──
    private fun sealTip(plain: ByteArray, key: ByteArray): ByteArray? = runCatching {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        nonce + c.doFinal(plain)
    }.getOrNull()
    private fun openTip(sealed: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (sealed.size < 28) return null
        val c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(sealed, 0, 12))
        c.updateAAD(aad)
        c.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()

    // ── the bytes, little-endian (iOS encode/decode): the format 1, the lane (16), the seen balance (i64), heard (u8), prev (32), the
    // count (u8), the links; a link: n (u32), at in ms (i64), the kind (u8), the coins (u64), the running balance (i64), then ref, peer
    // and on, each a u16 length and its UTF-8 ──
    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun encode(t: Tip): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(1); o.write(t.lane); o.write(le(8).putLong(t.seen).array()); o.write(if (t.heard) 1 else 0); o.write(t.prev)
        o.write(t.links.size)
        for (l in t.links) o.write(bytes(l))
        return o.toByteArray()
    }
    fun bytes(l: Link): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le(4).putInt(l.n.toInt()).array()); o.write(le(8).putLong(l.at).array()); o.write(l.k)
        o.write(le(8).putLong(l.c).array()); o.write(le(8).putLong(l.b).array())
        for (s in listOf(l.ref, l.peer, l.on)) { val u = s.toByteArray(Charsets.UTF_8); o.write(le(2).putShort(u.size.toShort()).array()); o.write(u) }
        return o.toByteArray()
    }
    fun decode(d: ByteArray): Tip? {
        val r = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        fun take(n: Int): ByteArray? = if (n <= r.remaining()) ByteArray(n).also { r.get(it) } else null
        fun u8(): Int? = if (1 <= r.remaining()) r.get().toInt() and 0xff else null
        fun i64(): Long? = if (8 <= r.remaining()) r.long else null
        fun text(): String? {
            if (r.remaining() < 2) return null
            val n = r.short.toInt() and 0xffff
            val b = take(n) ?: return null
            val dec = Charsets.UTF_8.newDecoder()
            return runCatching { dec.decode(ByteBuffer.wrap(b)).toString() }.getOrNull()   // invalid UTF-8 refused, as iOS String(data:)
        }
        if (u8() != 1) return null
        val lane = take(16) ?: return null
        val seen = i64() ?: return null
        val heard = u8() ?: return null
        val prev = take(32) ?: return null
        val count = u8() ?: return null
        if (DEPTH < count) return null
        val t = Tip(lane, seen, heard == 1, prev)
        repeat(count) {
            if (r.remaining() < 4) return null
            val n = r.int.toLong() and 0xffffffffL
            val at = i64() ?: return null
            val k = u8() ?: return null
            val c = i64() ?: return null
            val b = i64() ?: return null
            val ref = text() ?: return null
            val peer = text() ?: return null
            val on = text() ?: return null
            t.links.add(Link(n, at, k, c, b, ref, peer, on))
        }
        return if (r.remaining() == 0) t else null
    }

    // ── the chain: a link's seal is SHA-256 over the seal before it and the link's bytes ──
    fun seal(prev: ByteArray, l: Link): ByteArray = MessageDigest.getInstance("SHA-256").digest(prev + bytes(l))
    fun chain(t: Tip): List<ByteArray> {
        var prev = t.prev
        return t.links.map { l -> seal(prev, l).also { prev = it } }
    }
    /** The window counts on: every link numbered after the one before, its balance the one before's and its own move. A u64 count
     *  above Int.max reads negative here, and is refused as iOS refuses it. */
    fun whole(t: Tip): Boolean {
        for ((i, l) in t.links.withIndex()) {
            if (kinds.size <= l.k || l.c <= 0) return false
            if (i == 0) continue
            val p = t.links[i - 1]
            val b = p.b + l.signed
            if (((p.b xor b) and (l.signed xor b)) < 0) return false
            if (l.n != p.n + 1 || b != l.b) return false
        }
        return true
    }
    /** The window follows the head this device took of that lane: the seal at that link is the one taken, or the window begins right
     *  after it. A lane never taken follows by itself. */
    fun follows(had: Pair<Long, ByteArray>?, t: Tip, seals: List<ByteArray>): Boolean {
        val first = t.links.firstOrNull() ?: return true
        if (had == null) return true
        if (had.first + 1 == first.n) return t.prev.contentEquals(had.second)
        if (first.n <= had.first) {
            val i = t.links.indexOfFirst { it.n == had.first }
            if (0 <= i) return seals[i].contentEquals(had.second)
        }
        return true   // further behind than the window: nothing to compare, the book carries the rest
    }
    /** The bytes read back as they were written (iOS selfCheck): thirteen links of every kind, a peer on every other one, the seal
     *  followed from the sixth -- a writer and a reader that disagree on an order, a width or a length fail here, in the diary. */
    fun selfCheck(): Boolean = runCatching {
        val t = Tip(ByteArray(16) { it.toByte() }, 13, true)
        var b = 0L
        for (n in 1..DEPTH) {
            val k = n % 5; val c = n * 1_000_003L
            b += if (k < 2) c else -c
            t.links.add(Link(n.toLong(), 1_759_000_000_000L + n, k, c, b, "r$n", if (n % 2 == 0) "a:$n" else "", ""))
        }
        val back = decode(encode(t)) ?: return false
        val c0 = chain(t); val c1 = chain(back)
        back.lane.contentEquals(t.lane) && back.seen == t.seen && back.heard && back.prev.contentEquals(t.prev) && back.links == t.links &&
            whole(back) && follows(t.links[5].n to c0[5], back, c1) && !follows(t.links[5].n to c0[4], back, c1)
    }.getOrDefault(false)

    fun entry(l: Link) = CoinEntry(kinds[l.k], l.c, l.ref, l.peer.ifEmpty { null }, l.on.ifEmpty { null }, l.at / 1000.0)
}
