package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * THE COINS ARE THE CHAIN'S (iOS MTCoinChainRules, MTCoinTx, MTCoinSplit, MTPresenceWait -- MTWalletCore.swift 2457-2830, 2838-3400;
 * the author's words 23.10.2026 00:27 UTC: «the phone only mirrors the chain»; 14.11.2026 12:13 UTC, word 21842: «as before it must
 * show the minting, 1 coin 1 second, and credit it on the balance»). A person's coins stand on their key's chain on the protocol's
 * chain nodes; this phone asks its node what the chain holds (POST /tx-head) and shows it, and while the person is here -- the app in
 * front of them -- it counts their seconds, which rise in the Time Balance and never in the available: only the core makes coins of
 * them. The book (CoinBook) speaks only before the chain ever spoke for the seated key.
 *
 * ON THE CHAIN THIS PHONE LAYS ONE RECORD SO FAR: the GENESIS that opens a key's chain (iOS reconcile, !v3 -- of 0 coins since the v2
 * cut), through the same door as iOS (build3, layOnHead, headIsMine). SEND, RECV and REFUND come with the transfers, in their own part.
 * Unopened chains (no v3) are told to nobody (iOS heard: guard opened).
 */
object CoinChainRules {
    const val V2_CUT = 1_792_443_600L      // NOT-UI: 19.10.2026 21:00 UTC, the node's TX_V2_CUT (COIN-TX-V3 section 8)
    const val TIME_CUT = 1_792_702_800L    // NOT-UI: 22.10.2026 21:00 UTC, the node's TX_TIME_CUT: the chain mints the key's time itself
    const val ONLINE_CUT = 1_792_962_000L  // NOT-UI: 25.10.2026 21:00 UTC, the node's TX_ONLINE_CUT: minting only while the person is here
    const val MINT_FRESH = 120L
    const val MINT_STOP = 1_793_361_600L   // NOT-UI: 30.10.2026 12:00 UTC, the node's TX_MINT_STOP: no MINT at all from here

    /** What the time step adds to a key folded at the node second [s] by the node second [t], no record between (iOS since). */
    fun since(s: Long, t: Long): Long = if (s < TIME_CUT) 0L else maxOf(0L, minOf(t, ONLINE_CUT) - minOf(s, ONLINE_CUT))
    /** The blind of a SEND: HMAC-SHA-256 under the pair's secret over ASCII mt-coin-blind and the SEND's nonce (iOS blind, section 1). */
    fun blind(secret: ByteArray, nonce: ByteArray): ByteArray = Hkdf.hmac(secret, "mt-coin-blind".toByteArray(Charsets.US_ASCII) + nonce)
    /** The receiver's commitment: SHA-256 of ASCII mt-coin-to, the receiver's from (32 bytes) and the blind (iOS commitment, section 1). */
    fun commitment(from: ByteArray, blind: ByteArray): ByteArray = Wire.sha("mt-coin-to".toByteArray(Charsets.US_ASCII), from, blind)

    // ── THE NODES' FOLD, ONE RECORD AT A TIME (iOS MTCoinChainRules 2455-2622, COIN-TX-V3 sections 3-5): the phone's copy of the chains
    // (CoinReplica) folds every chain by these rules exactly as the node's _tx_step does, so its root is the node's root. ──
    const val COIN_EPOCH = 1_788_210_000L    // NOT-UI: 31.08.2026 21:00 UTC, the node's TX_COIN_EPOCH (section 3)
    const val GENESIS_GATE = 1_792_354_500L  // NOT-UI: the node's TX_GENESIS_GATE: a GENESIS above 0 stands only on the key's own past
    /** 1 second -- 1 coin from the node's TX_SECOND_CUT, the same second as the v2 cut (iOS secondCut, section 4). */
    const val SECOND_CUT = V2_CUT
    const val KIND_SEND = 1; const val KIND_RECV = 2; const val KIND_MINT = 3; const val KIND_REFUND = 4; const val KIND_GENESIS = 5; const val KIND_BURN = 6

    /** A key's state folded over its chain (iOS St, section 3). */
    data class St(val head: String = CoinTx.ZERO, val height: Int = 0, val birth: Long = 0, val mintEnd: Long = 0, val proven: Long = 0,
                  val declared: Long = 0, val v3: Boolean = false, val lastAt: Long = 0, val minted: Long = 0)
    /** A SEND folded, as a RECV or a REFUND that settles it is weighed (iOS SendInfo). */
    class SendInfo(val from: String, val amount: Long, val at: Long, val to: ByteArray, val dec: Long)

    /** A_2(x) of section 4, the doubling rule: 2 x min(13, max(1, bit_length(s) - 1)) a second over the seconds 1..x (iOS timeA). */
    fun timeA(x: Long): Long {
        var total = 0L; var lo = 1L; var k = 0L
        while (0 < x && lo <= x) {
            val hi = minOf(x, 2 * lo - 1)
            total += (hi - lo + 1) * minOf(13L, maxOf(1L, k))
            lo *= 2; k++
        }
        return 2 * total
    }
    val genesisCap: Long get() = timeA(V2_CUT - COIN_EPOCH)
    /** What the seconds 1..x of a key's life may mint by a MINT its node takes at the second [at] (iOS allowTime). */
    fun allowTime(x: Long, at: Long): Long = if (SECOND_CUT <= at) maxOf(0L, x) else timeA(x)
    /** The key's seconds of life up to the node second [at], never past the online cut, credited once (iOS timeStep, fold-6, fold-8). */
    fun timeStep(s: St, at: Long): St {
        if (at < TIME_CUT || s.height <= 0) return s
        val end = minOf(at, ONLINE_CUT)
        val fresh = maxOf(0L, end - maxOf(s.lastAt, TIME_CUT, s.birth))
        val credit = maxOf(end - s.birth - s.minted, fresh)
        if (credit <= 0) return s
        return s.copy(proven = s.proven + credit, minted = s.minted + credit)
    }
    /** The seconds a MINT taken at [at] may count from the online cut: its interval within the last MINT_FRESH (iOS present, fold-7). */
    fun present(t0: Long, t1: Long, at: Long): Long = maxOf(0L, t1 - maxOf(t0, at - MINT_FRESH, ONLINE_CUT))
    /**
     * A V2 RECORD THAT SPENT STANDS OPEN AS A SEND (iOS v2Spent, section 5, fold-4): on an opened chain it takes this declared part;
     * null: a v2 record of the book's past (the chain not opened), or one above the balance.
     */
    fun v2Spent(s: St, amount: Long): Long? = if (!s.v3 || s.proven + s.declared < amount) null else maxOf(0L, amount - s.proven)
    /** A hex name as its bytes (iOS bytes). */
    fun bytes(s: String): ByteArray? =
        if (s.length % 2 != 0) null else runCatching { ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()

    /** One step's answer: the next state, or null for a record that breaks a rule; wait for a RECV or REFUND whose SEND is not folded yet. */
    class Step(val next: St?, val wait: Boolean)
    /**
     * ONE RECORD FOLDED ONTO ITS KEY'S STATE BY THE NODES' RULES (iOS step, MTWalletCore.swift 2572-2622): first the key's time, then
     * the record's kind; a refused record leaves the state as it stood.
     */
    fun step(s0: St, p: CoinTx.Record, at: Long, sends: Map<String, SendInfo>, settled: Set<String>): Step {
        val s = if (p.version == 3) timeStep(s0, at) else s0
        var n = s.copy(head = p.id, height = s.height + 1, lastAt = maxOf(at, s.lastAt))
        if (s.height == 0) n = n.copy(birth = at, mintEnd = at)
        val a = p.coins
        if (p.version == 2) {
            // every transfer moves its balance: after the chain opened, a send that names no receiver spends as a SEND, proven first
            if (!s.v3) return Step(n, false)
            val d = v2Spent(s, a) ?: return Step(null, false)
            return Step(n.copy(proven = s.proven - (a - d), declared = s.declared - d), false)
        }
        n = n.copy(v3 = true)
        when (p.kind) {
            KIND_GENESIS -> {
                if (s.v3 || (0 < a && V2_CUT <= at) || genesisCap < a) return Step(null, false)
                if (0 < a && GENESIS_GATE <= at && (s.height == 0 || timeA(at - s.birth) < a)) return Step(null, false)
                n = n.copy(declared = a)
            }
            KIND_MINT -> {
                val t0 = p.a; val t1 = p.b
                if (s.height == 0 || t0 < s.mintEnd || t1 < t0 + 60 || at < t1) return Step(null, false)
                // the sum of time (fold-3); from the online cut the present seconds alone weigh it (fold-8)
                if (ONLINE_CUT <= at) { if (present(t0, t1, at) < a) return Step(null, false) }
                else if (allowTime(t1 - s.birth, at) - s.minted < a) return Step(null, false)
                n = n.copy(proven = s.proven + a, mintEnd = t1, minted = s.minted + a)
            }
            KIND_SEND, KIND_BURN -> {
                // a BURN spends as a SEND and names no one
                if (p.kind == KIND_BURN && at < ONLINE_CUT) return Step(null, false)
                val dec = p.a
                if (s.proven + s.declared < a || dec != maxOf(0L, a - s.proven)) return Step(null, false)
                n = n.copy(proven = s.proven - (a - dec), declared = s.declared - dec)
            }
            KIND_RECV, KIND_REFUND -> {
                val S = sends[p.ref] ?: return Step(null, true)
                if (S.amount != a || p.ref in settled) return Step(null, false)
                if (p.kind == KIND_RECV) {
                    val f = bytes(p.from) ?: return Step(null, false)
                    if (S.dec != p.a || !commitment(f, p.to).contentEquals(S.to)) return Step(null, false)
                } else if (S.from != p.from || at < S.at + 86_400) return Step(null, false)
                n = n.copy(proven = s.proven + a - S.dec, declared = s.declared + S.dec)
            }
            else -> return Step(null, false)
        }
        return Step(n, false)
    }
}

object CoinTx {
    private const val TAG = "Montana"
    /** The protocol's chain set, one place (iOS chainNodes): a key's chain lives on the node at its tag's first byte. */
    val chainNodes = listOf("montana.quest" to "api.montana.quest", "montana.xxx" to "montana.xxx")   // NOT-UI: node names
    /** The seconds the road to the node may take (iOS presentMargin). */
    const val PRESENT_MARGIN = 20L
    /** The presence's beat: the chain is asked once in it while the person stays (iOS presentBeat). */
    private const val PRESENT_BEAT_MS = 40_000L

    class Hands(val pk: ByteArray, val sk: ByteArray) { val from: String get() = keyTag(pk) }
    @Volatile private var keyCache: Pair<String, Hands>? = null

    /** The seed's coin key: HKDF-Expand(master_seed, «mt-coin-tx», 32) into ML-DSA-65 (iOS keys/hands); one on every device of the words. */
    fun keys(): Hands? {
        val words = MontanaSeed.mnemonic ?: return null
        val tag = Wire.hex(Wire.sha(words.toByteArray()))
        keyCache?.let { (t, h) -> if (t == tag) return h }
        val h = derive(words) ?: return null
        keyCache = tag to h
        return h
    }
    fun derive(words: String): Hands? {
        val master = MtBindings.nativeMnemonicToMasterSeed(words)?.takeIf { it.size == 64 } ?: return null
        val seed = MtBindings.nativeRoleSeed(master, "mt-coin-tx").also { master.fill(0) }?.takeIf { it.size == 32 } ?: return null
        val kp = MtBindings.nativeMldsaKeypair(seed).also { seed.fill(0) }?.takeIf { it.size == MtBindings.PUBKEY + MtBindings.SECKEY } ?: return null
        return Hands(kp.copyOfRange(0, MtBindings.PUBKEY), kp.copyOfRange(MtBindings.PUBKEY, kp.size))
    }
    /** The coin key's tag on the wire: SHA-256 of the coin key, the sender's name in every record. */
    fun keyTag(pk: ByteArray): String = Wire.hex(Wire.sha(pk))
    /** The seated key's tag, held once known (the words are read through the Keystore; a seat's turn restarts the process). */
    @Volatile private var tagHeld: String? = null
    val myKeyTag: String? get() = tagHeld ?: keys()?.from?.also { tagHeld = it }
    /** The coin key already derived in this process, never derived here (iOS cachedKeyTag: a caller must not pay the seed's derivation). */
    val cachedKeyTag: String? get() = tagHeld ?: keyCache?.second?.from
    /**
     * THE KEY DERIVED FOR THE WORD ABOUT ONESELF (iOS deriveForTheWord, MTWalletCore.swift 3452; the author's word 21.10.2026 09:41 UTC:
     * «if there is a chat there is everything, and it must work at once»): once at a time, off the caller's thread; when it stands,
     * every correspondent not yet told hears it (MyAbout.broadcast).
     */
    private val deriving = java.util.concurrent.atomic.AtomicBoolean(false)
    fun deriveForTheWord() {
        if (!deriving.compareAndSet(false, true)) return
        Thread {
            val k = runCatching { myKeyTag }.getOrNull()
            deriving.set(false)
            if (k != null) MyAbout.broadcast()
        }.start()
    }

    fun chainNode(from: String): String? = from.take(2).toIntOrNull(16)?.let { chainNodes[it % chainNodes.size].first }
    fun chainDoor(from: String): String? = chainNode(from)?.let { n -> chainNodes.firstOrNull { it.first == n } }?.let { "https://" + it.second + "/pushwake" }
    private fun nodeOf(door: String): String = runCatching { URL(door).host }.getOrNull()?.let { h -> chainNodes.firstOrNull { it.second == h }?.first } ?: "?"

    /** A node's answer, and whether the request never reached it (iOS ask: only such a silence proves nothing was laid). */
    class Answer(val code: Int, val obj: JSONObject?, val unreached: Boolean)
    fun ask(url: String, body: JSONObject, timeoutMs: Int = 15_000): Answer {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = timeoutMs; readTimeout = timeoutMs; doOutput = true
                setRequestProperty("content-type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }
            Answer(code, text?.let { runCatching { JSONObject(it) }.getOrNull() }, false)
        } catch (e: java.net.UnknownHostException) { Answer(-1, null, true)
        } catch (e: java.net.ConnectException) { Answer(-1, null, true)
        } catch (e: Exception) { Answer(-1, null, false)
        } finally { conn?.disconnect() }
    }

    /** The state of a key's chain as its node folds it (POST /tx-head), and the node's own second (iOS Chain3). */
    class Chain3(val head: String, val height: Int, val birth: Long, val mintEnd: Long, val proven: Long, val declared: Long, val v3: Boolean, val now: Long, val minted: Long?)
    private var chainSaid = 0L
    fun chain3(me: String, door: String): Chain3? {
        val h = ask("$door/tx-head", JSONObject().put("from", me))
        val o = h.obj
        val head = o?.optString("head").orEmpty()
        if (h.code != 200 || o == null || head.length != 64) {
            // THE ROAD THAT FAILED IS SAID, folded over ten minutes, never the key (iOS coin_chain3)
            val now = System.currentTimeMillis()
            if (600_000 <= now - chainSaid) { chainSaid = now; Log.i(TAG, "coin_chain3 code=" + h.code + " unreached=" + (if (h.unreached) 1 else 0) + " node=" + nodeOf(door)) }
            return null
        }
        fun n(k: String): Long = o.opt(k)?.toString()?.toLongOrNull() ?: o.optDouble(k, 0.0).toLong()
        val now = n("now")
        return Chain3(head, n("height").toInt(), n("birth"), n("mint_end"), n("proven"), n("declared"), n("v3") == 1L,
            if (now == 0L) System.currentTimeMillis() / 1000 else now, if (o.has("minted")) n("minted") else null)
    }

    /** THE PERSON HERE (iOS here): the app in front of them. The VPN's living tunnel joins it with the VPN. */
    @Volatile var onScreen = false
    val here: Boolean get() = onScreen
    /** The node second the person came, null while away (iOS hereFrom): a MINT never counted from before it. */
    @Volatile var hereFrom: Long? = null; private set
    @Volatile private var presence: Thread? = null
    private val sleeper = Object()

    /** The app came to the screen or the wallet opened: while the person stays every beat asks the chain (iOS beHere). */
    fun beHere() {
        synchronized(this) {
            // a stay the app's leaving closed reopens on its return at once, even inside a beat that still sleeps (iOS beHere):
            // the beat wakes, opens it again and asks the chain; the key is derived off the screen's thread
            if (presence != null) { synchronized(sleeper) { sleeper.notifyAll() }; return }
            hereFrom = null
            presence = Thread {
                while (here) {
                    PresenceWait.open()
                    runCatching { reconcile() }
                    PresenceWait.fold()
                    synchronized(sleeper) { runCatching { sleeper.wait(PRESENT_BEAT_MS) } }
                }
                PresenceWait.close()
                synchronized(this) { presence = null }
                hereFrom = null
                CoinSplit.beat()
                if (here) beHere()   // the person came back while this beat was ending
            }.apply { name = "coin-presence"; isDaemon = true; start() }
        }
    }
    /** The app left the screen: the stay ends at once (iOS PresenceWait.watch); the beat thread ends at its next wake. */
    fun left() { onScreen = false; PresenceWait.close(); synchronized(sleeper) { sleeper.notifyAll() } }

    // -- version 3 (iOS MTCoinTx build3/read3/layOnHead, COIN-TX-V3): every move of a key's balance is a signed record of its chain
    const val ZERO = "0000000000000000000000000000000000000000000000000000000000000000"   // NOT-UI: the empty head
    private val MAGIC = "MTTX".toByteArray()
    private val SIGNED1 = "mt-coin-tx-v1".toByteArray()
    private val SIGNED2 = "mt-coin-tx-v2".toByteArray()
    private val SIGNED3 = "mt-coin-tx-v3".toByteArray()
    private const val SIZE1 = 5330   // version 1, read only
    private const val SIZE2 = 5362   // version 2
    const val SIZE3 = 5443
    private const val SIG = 3309
    enum class Kind3(val raw: Int) { SEND(1), RECV(2), MINT(3), REFUND(4), GENESIS(5), BURN(6) }
    private val random = SecureRandom()
    fun fresh(): ByteArray = ByteArray(16).also { random.nextBytes(it) }
    /** A record's name: SHA-256 of all its bytes, the signature's among them (iOS name). */
    fun name(rec: ByteArray): String = Wire.hex(Wire.sha(rec))
    private fun le(v: Long): ByteArray = ByteArray(8) { (v ushr (8 * it)).toByte() }
    private fun u64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { v, i -> v or ((b[at + i].toLong() and 0xff) shl (8 * i)) }
    private fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0) null else runCatching { ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()

    /** Section 1: a field its kind does not use is zero, as the node's reader holds them (iOS MTCoinChainRules.fieldsHold). */
    private fun fieldsHold(kind: Int, amount: Long, pair: ByteArray, to: ByteArray, ref: ByteArray, a: Long, b: Long): Boolean {
        fun none(x: ByteArray) = x.all { it == 0.toByte() }
        val ua = java.lang.Long.compareUnsigned(a, amount) <= 0
        return when (kind) {
            1 -> none(ref) && b == 0L && !none(to) && ua
            2 -> none(pair) && b == 0L && !none(ref) && ua
            3 -> none(pair) && none(to) && none(ref)
            4 -> none(pair) && none(to) && !none(ref) && a == 0L && b == 0L
            5 -> none(pair) && none(to) && none(ref) && a == 0L && b == 0L
            6 -> none(pair) && none(to) && none(ref) && b == 0L && ua
            else -> false
        }
    }

    /** The record of version 3, signed by the core (null: no words, a field of the wrong size, or the core refused) (iOS build3). */
    fun build3(kind: Kind3, amount: Long, prev: String, nonce: ByteArray, pair: ByteArray = ByteArray(32), to: ByteArray = ByteArray(32),
               ref: String = ZERO, a: Long = 0, b: Long = 0, hands: Hands? = null): ByteArray? {
        val k = hands ?: keys() ?: return null
        val p = unhex(prev)?.takeIf { it.size == 32 } ?: return null
        val r = unhex(ref)?.takeIf { it.size == 32 } ?: return null
        if (amount < 0 || nonce.size != 16 || pair.size != 32 || to.size != 32) return null
        val body = ByteArrayOutputStream().apply {
            write(MAGIC); write(3); write(kind.raw); write(le(amount)); write(le(maxOf(0L, System.currentTimeMillis())))
            write(nonce); write(p); write(pair); write(to); write(r); write(le(a)); write(le(b)); write(k.pk)
        }.toByteArray()
        if (body.size != 2134) return null
        val sig = MtBindings.nativeSign(k.sk, SIGNED3 + body)?.takeIf { it.size == SIG } ?: return null
        return body + sig
    }

    /** A record read back, believed only when its shape holds and the core verifies its signature (iOS Record, read/read3). */
    class Record(val id: String, val version: Int, val coins: Long, val birthMs: Long, val pair: ByteArray, val prev: String, val from: String,
                 val kind: Int = 1, val to: ByteArray = ByteArray(0), val ref: String = "", val a: Long = 0, val b: Long = 0, val nonce: ByteArray = ByteArray(0))
    /**
     * [known]: the caller already saw a record of this very name verify, and the name is the hash of every byte, the signature's among
     * them -- the same bytes, the same answer (iOS read(known:)); the caller still holds the read record's name to the one it knew.
     */
    fun read(rec: ByteArray, known: Boolean = false): Record? {
        if (rec.size < 5 || !rec.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val v = rec[4].toInt()
        if (v == 3) return read3(rec, known)
        if (v != 1 && v != 2) return null
        val end = if (v == 1) 2021 else 2053; val keyAt = if (v == 1) 69 else 101
        val total = if (v == 1) SIZE1 else SIZE2; val prefix = if (v == 1) SIGNED1 else SIGNED2
        if (rec.size != total) return null
        val coins = u64(rec, 5)
        if (coins <= 0) return null
        val body = rec.copyOfRange(0, end); val pk = rec.copyOfRange(keyAt, end); val sig = rec.copyOfRange(end, total)
        if (!known && !MtBindings.nativeVerify(pk, prefix + body, sig)) return null
        return Record(name(rec), v, coins, u64(rec, 13), rec.copyOfRange(37, 69), if (v == 2) Wire.hex(rec.copyOfRange(69, 101)) else "", keyTag(pk))
    }
    fun read3(rec: ByteArray, known: Boolean = false): Record? {
        if (rec.size != SIZE3 || !rec.copyOfRange(0, 4).contentEquals(MAGIC) || rec[4].toInt() != 3) return null
        val kind = rec[5].toInt() and 0xff
        if (kind !in 1..6) return null
        val amount = u64(rec, 6)
        if (amount < 0 || (amount == 0L && kind != Kind3.GENESIS.raw)) return null
        val pair = rec.copyOfRange(70, 102); val to = rec.copyOfRange(102, 134); val ref = rec.copyOfRange(134, 166)
        val a = u64(rec, 166); val b = u64(rec, 174)
        if (!fieldsHold(kind, amount, pair, to, ref, a, b)) return null
        val body = rec.copyOfRange(0, 2134); val pk = rec.copyOfRange(182, 2134); val sig = rec.copyOfRange(2134, SIZE3)
        if (!known && !MtBindings.nativeVerify(pk, SIGNED3 + body, sig)) return null
        return Record(name(rec), 3, amount, u64(rec, 14), pair, Wire.hex(rec.copyOfRange(38, 70)), keyTag(pk),
            kind, to, Wire.hex(ref), a, b, rec.copyOfRange(22, 38))
    }

    /** My chain's head read back and verified as my key's own record of version 2 or 3 (iOS headIsMine). */
    private fun headIsMine(head: String, me: String, door: String): Boolean {
        if (head == ZERO) return true
        val g = ask("$door/tx-get", JSONObject().put("id", head))
        val b = g.obj?.optString("rec")?.takeIf { g.code == 200 && it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return false
        val r = read(b) ?: return false
        return r.id == head && r.from == me && (r.version == 2 || r.version == 3)
    }

    /** One answer of the node to a record laid: its code, its word, the record (iOS layOnHead's tuple). */
    class Laid(val code: Int, val word: String, val rec: ByteArray?)
    /**
     * One record of my key laid on my chain's head, the head read back and verified as mine first; a head that moved is asked again
     * once (iOS layOnHead). A put whose answer was lost is found by its name (tx-get) before it is called failed.
     */
    fun layOnHead(hands: Hands? = null, make: (Chain3) -> ByteArray?): Laid {
        val me = hands?.from ?: myKeyTag ?: return Laid(-1, "no key", null)
        val door = chainDoor(me) ?: return Laid(-1, "no key", null)
        repeat(2) {
            val st = chain3(me, door) ?: return Laid(-1, "no head", null)
            if (!headIsMine(st.head, me, door)) return Laid(-1, "head unverified", null)
            val rec = make(st) ?: return Laid(-1, "no record", null)
            val a = ask("$door/tx-put", JSONObject().put("rec", Base64.encodeToString(rec, Base64.NO_WRAP)))
            val word = a.obj?.optString("error").orEmpty()
            if (a.code == 200) return Laid(200, "", rec)
            if (a.code == 409 && word == "not on the head") return@repeat
            if (a.code == -1 && !a.unreached && ask("$door/tx-get", JSONObject().put("id", name(rec))).code == 200) return Laid(200, "", rec)
            return Laid(a.code, word, rec)
        }
        return Laid(409, "not on the head", null)
    }

    /** The node's word on a record laid (iOS MTCoinTx.Word). */
    enum class Word { LEFT, NOT_LEFT, UNKNOWN, BEHIND, PACED }
    /**
     * THE NODE'S WORD DECIDES (iOS lay, MTWalletCore.swift 2998-3020, the v3 road): a record of version 3 goes to its key's chain node
     * alone -- taken there, left; standing on a head that moved (409), behind; the key's pace spent (429), paced; refused (400, 507)
     * or never reached, not left; anything else waits for that node's word (tx-get: found, left; else unknown). The node's code and
     * word go to the diary (coin_lay), never guessed.
     */
    fun lay(rec: ByteArray): Word {
        if (rec.size != SIZE3 || rec[4].toInt() != 3) return Word.NOT_LEFT
        val id = name(rec)
        val door = chainDoor(keyTag(rec.copyOfRange(182, 2134))) ?: return Word.NOT_LEFT
        val a = ask("$door/tx-put", JSONObject().put("rec", Base64.encodeToString(rec, Base64.NO_WRAP)))
        if (a.code == 200 && a.obj?.optString("id") == id) { tookOne(); return Word.LEFT }
        Log.i(TAG, "coin_lay code=" + a.code + " error=" + (a.obj?.optString("error")?.ifEmpty { null } ?: "-") + " unreached=" + (if (a.unreached) 1 else 0) + " id=" + id.take(12))
        if (a.code == 409) return Word.BEHIND
        if (a.code == 429) return Word.PACED
        if (a.code == 400 || a.code == 507 || a.unreached) return Word.NOT_LEFT
        return if (ask("$door/tx-get", JSONObject().put("id", id)).code == 200) Word.LEFT else Word.UNKNOWN
    }
    /**
     * THE KEY'S PACE HAS ONE OWNER (iOS MTCoinTx.keyPace, MTWalletCore.swift 2848-2862; the critic of 14486): the node takes thirty
     * records a minute from one key (V3 section 7), and the paid reactions -- a person's and the auto-reactions' -- must not spend the
     * minute a send of the person's own then needs. Every record the node took from this phone stands here by its moment; a reaction
     * leaves six of the thirty to the person's sends (reactionRoom), and a send that still meets the pace waits the minute out (send3).
     */
    const val KEY_PACE = 30
    const val KEPT_FOR_SENDS = 6
    private val laidAt = ArrayList<Long>()   // under its own lock: one list, never held across a wait
    fun laidThisMinute(): Int = synchronized(laidAt) {
        val now = System.currentTimeMillis()
        laidAt.removeAll { 60_000 <= now - it }
        laidAt.size
    }
    private fun tookOne() = synchronized(laidAt) { laidAt.add(System.currentTimeMillis()) }
    val reactionRoom: Boolean get() = laidThisMinute() < KEY_PACE - KEPT_FOR_SENDS

    /** Where a SEND waits for its node's word, in the person's folder (iOS pendingURL: tx-pending-<id>.rec; settle reads it). */
    fun pendingFile(id: String): File = File(File(Book.ctx.filesDir, "Coins").apply { mkdirs() }, "tx-pending-$id.rec")

    // THE PAIR'S COIN STATE, THE PERSON'S OWN (iOS load/save of coin3-peers.json and coin3-open.json, MTWalletCore.swift 3406-3468):
    // one held copy per file path, so the next person seated never reads the one before's correspondents.
    private val held = HashMap<String, JSONObject>()
    private fun stateFile(name: String): File = File(File(Book.ctx.filesDir, "Coins").apply { mkdirs() }, name)
    private fun load(name: String): JSONObject {
        val f = stateFile(name)
        synchronized(held) { held[f.path]?.let { return it } }
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
        synchronized(held) { return held.getOrPut(f.path) { o } }
    }
    private fun save(name: String, o: JSONObject) {
        val f = stateFile(name)
        synchronized(held) { held[f.path] = o }
        runCatching { val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) }
    }
    private const val PEERS = "coin3-peers.json"   // NOT-UI: the file's name
    private const val OPEN = "coin3-open.json"     // NOT-UI: the file's name
    /** A correspondent's coin key, told in their about word (k): the key a SEND of version 3 commits to (iOS peerFrom). */
    fun peerFrom(conv: String): String? = load(PEERS).optJSONObject("k")?.optString(conv)?.takeIf { it.isNotEmpty() }
    fun notePeerFrom(conv: String, from: String) {
        if (from.length != 64 || !from.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return
        synchronized(held) {
            val o = load(PEERS); val m = o.optJSONObject("k") ?: JSONObject()
            if (m.optString(conv) == from) return
            save(PEERS, JSONObject(o.toString()).put("k", m.put(conv, from)))
        }
    }
    /** Whether this correspondent was already told my coin key (the about word carries it until then; iOS toldKey). */
    fun toldKey(conv: String, k: String): Boolean = load(PEERS).optJSONObject("told")?.optString(conv) == k
    fun noteToldKey(conv: String, k: String) = synchronized(held) {
        val o = load(PEERS); val m = o.optJSONObject("told") ?: JSONObject()
        save(PEERS, JSONObject(o.toString()).put("told", m.put(conv, k)))
    }
    /** Their SEND found no key of mine («q» in their about word): told again (iOS forgetToldKey). */
    fun forgetToldKey(conv: String) = synchronized(held) {
        val o = load(PEERS); val m = o.optJSONObject("told") ?: return@synchronized
        if (m.remove(conv) == null) return@synchronized
        save(PEERS, JSONObject(o.toString()).put("told", m))
    }
    /** SENDs laid and not yet taken or refunded (iOS openSends/keepOpen: rows of id, conv, coins, at). */
    fun openSends(): List<JSONObject> = load(OPEN).optJSONArray("open")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    fun keepOpen(rows: List<JSONObject>) = save(OPEN, JSONObject().put("open", org.json.JSONArray(rows)))

    /**
     * THE LETTER A PAID REACTION'S SEND STANDS UNDER, AS ITS WORD NAMED IT (iOS MTCoinTx.heard, MTWalletCore.swift 3469-3483): the
     * word moves no coins; it names the SEND, and the scan asked at once credits that SEND under the letter. A word later than the
     * scan leaves the coins right and under no letter. The newest 256 names are kept.
     */
    private const val HEARD = "coin3-heard.json"   // NOT-UI: the file's name
    fun heard(tx: String, on: String) {
        if (tx.length != 64 || !tx.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } || on.isEmpty()) return
        synchronized(held) {
            val a = load(HEARD).optJSONArray("on") ?: org.json.JSONArray()
            val rows = (0 until a.length()).mapNotNull { a.optJSONArray(it) }.filter { it.optString(0) != tx }.toMutableList()
            rows.add(org.json.JSONArray().put(tx).put(on))
            save(HEARD, JSONObject().put("on", org.json.JSONArray(rows.takeLast(256))))
        }
        if (CoinBook.holds(CoinEntry.RECEIVE, REF_PREFIX + tx)) return
        Thread { scanFresh("reaction") }.start()
    }
    private fun heardOn(tx: String): String? {
        val a = load(HEARD).optJSONArray("on") ?: return null
        for (i in a.length() - 1 downTo 0) {
            val r = a.optJSONArray(i) ?: continue
            if (r.optString(0) == tx) return if (r.length() == 2) r.optString(1).ifEmpty { null } else null
        }
        return null
    }
    /**
     * A name taken once (an auto-reaction's «auto:» and its letter; iOS MTCoinTx.once/release 3486-3499): false if it stands. Two
     * days are kept -- an auto-reaction pays only a letter born within a day (CoinSend.FRESH).
     */
    private const val ONCE = "coin3-once.json"   // NOT-UI: the file's name
    fun once(name: String): Boolean = synchronized(held) {
        val now = System.currentTimeMillis() / 1000.0
        val was = load(ONCE).optJSONObject("n") ?: JSONObject()
        val o = JSONObject()
        for (k in was.keys()) { val at = was.optDouble(k); if (now - at < 2 * CoinSend.FRESH) o.put(k, at) }
        if (o.has(name)) return@synchronized false
        o.put(name, now)
        save(ONCE, JSONObject().put("n", o))
        true
    }
    fun release(name: String) = synchronized(held) {
        val o = JSONObject((load(ONCE).optJSONObject("n") ?: JSONObject()).toString())
        if (o.remove(name) != null) save(ONCE, JSONObject().put("n", o))
    }

    const val REF_PREFIX = "tx:"       // NOT-UI: the book's name of a transfer's moves (iOS refPrefix)
    const val BACK_PREFIX = "txback:"  // NOT-UI: the book's name of a debit no node took (iOS backPrefix)
    const val LINK = "https://montana.xxx/explorer/#tx="   // NOT-UI: the transfer's confirmation link (iOS MTCoinTx.link, the Explorer)
    const val NO_RECEIVER_KEY = "no receiver key"   // NOT-UI: the trace's reason, and the one the send page names
    /** What a v3 send came to (iOS Send3); PACED: the key's node took its thirty records this minute and the next. */
    sealed class Send3 {
        class Sent(val id: String) : Send3()
        object Pending : Send3()
        class NotApplicable(val why: String) : Send3()
        object Failed : Send3()
        object Paced : Send3()
    }
    /**
     * SENDS (version 3; iOS send3, MTWalletCore.swift 3506-3558): the receiver's key known (their about word), my chain opened and
     * holding the sum, the record names the declared part of it (proven is spent first), commits to the receiver's key under the
     * pair's blind, and is laid on my chain's head. THE KEY'S PACE IS WAITED, NOT REFUSED (the author's word 14463): a 429 goes again
     * every five seconds, a minute at most. Off the main thread: it asks the node.
     */
    fun send3(coins: Long, conv: String, on: String? = null): Send3 {
        val me = myKeyTag ?: return Send3.NotApplicable("no pipe")
        val door = chainDoor(me) ?: return Send3.NotApplicable("no pipe")
        val secret = Book.secret(conv)?.takeIf { Wire.isWhole(it) } ?: return Send3.NotApplicable("no pipe")
        val pair = Wire.pairTag(secret)
        val peerBytes = peerFrom(conv)?.takeIf { it.length == 64 }?.let { h -> runCatching { ByteArray(32) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull() }
            ?: return Send3.NotApplicable(NO_RECEIVER_KEY)
        val first = reconcile()
        if (first == null || !first.v3 || first.proven + first.declared < coins) return Send3.NotApplicable("chain short")
        val nonce = fresh()
        val blind = CoinChainRules.blind(Wire.itemKey(secret), nonce)   // the item key's blind (iOS pipe.blind)
        val commit = CoinChainRules.commitment(peerBytes, blind)
        repeat(2) { attempt ->
            val st = chain3(me, door)
            if (st == null || !st.v3 || st.proven + st.declared < coins) return Send3.NotApplicable("chain short")
            if (!headIsMine(st.head, me, door)) return Send3.Failed
            if (me != myKeyTag) return Send3.Failed
            val dec = maxOf(0L, coins - st.proven)
            val rec = build3(Kind3.SEND, coins, st.head, nonce, pair = pair, to = commit, a = dec) ?: return Send3.Failed
            val id = name(rec)
            if (!CoinBook.sendHeld(coins, conv, REF_PREFIX + id, on)) return Send3.Failed
            val pending = pendingFile(id)
            runCatching { val t = File(pending.path + ".tmp"); t.writeBytes(rec); t.renameTo(pending) }
            var word = lay(rec)
            var paced = 0
            while (word == Word.PACED && paced < 12) { paced++; Thread.sleep(5_000); word = lay(rec) }
            Log.i(TAG, "coin_v3 send word=" + word + " coins=" + coins + " dec=" + dec + " id=" + id.take(12) + " attempt=" + (attempt + 1) + " paced=" + paced)
            if (me != myKeyTag) return Send3.Pending   // the record waits in its person's folder (settle)
            when (word) {
                Word.LEFT -> {
                    pending.delete()
                    keepOpen(openSends() + JSONObject().put("id", id).put("conv", conv).put("coins", coins).put("at", st.now))
                    CoinSplit.laid(id, coins, st.height)
                    return Send3.Sent(id)
                }
                Word.NOT_LEFT, Word.BEHIND, Word.PACED -> {
                    CoinBook.receive(coins, conv, BACK_PREFIX + id, on)
                    pending.delete()
                    if (word == Word.NOT_LEFT) return Send3.Failed
                    if (word == Word.PACED) return Send3.Paced
                }
                Word.UNKNOWN -> {
                    keepOpen(openSends() + JSONObject().put("id", id).put("conv", conv).put("coins", coins).put("at", st.now))
                    CoinSplit.laid(id, coins, st.height)
                    return Send3.Pending
                }
            }
        }
        return Send3.Failed
    }

    /**
     * THE CHAIN HOLDS WHAT THE BOOK HOLDS (iOS reconcile): what the key's node folds is told to the split. A key's first v3 record is its
     * GENESIS, laid here when the chain is not opened yet: since the v2 cut (19.10.2026) the node takes a GENESIS of 0 alone
     * (iOS genesisRoom), and no node second is before that cut any more. After the mint stop the node takes no MINT, so none is laid.
     */
    fun reconcile(): Chain3? {
        val me = myKeyTag ?: return null
        val door = chainDoor(me) ?: return null
        var st = chain3(me, door) ?: return null
        if (me != myKeyTag) { Log.i(TAG, "coin_v3 the seat moved -- nothing told"); return null }
        if (!st.v3) {
            val amount = 0L   // iOS min(book, genesisRoom(...)): genesisRoom is 0 from the v2 cut
            val r = layOnHead { s -> if (me == myKeyTag) build3(Kind3.GENESIS, amount, s.head, fresh()) else null }
            Log.i(TAG, "coin_v3 genesis code=" + r.code + " word=" + r.word + " amount=" + amount)
            if (r.code != 200) return null
            st = chain3(me, door) ?: return null
        }
        if (here && hereFrom == null && CoinChainRules.ONLINE_CUT <= st.now) hereFrom = st.now
        CoinSplit.heard(st.proven, st.declared, st.v3, false, st.height, st.now, st.mintEnd)
        return st
    }

    /** The records an ended process left waiting, each decided by a node's word; then the pipes are asked (iOS settle, 3108). Off the main thread. */
    fun settle() {
        val me = myKeyTag ?: return
        File(Book.ctx.filesDir, "Coins").listFiles { f -> f.name.startsWith("tx-pending-") && f.name.endsWith(".rec") }?.forEach { u ->
            val rec = runCatching { u.readBytes() }.getOrNull()
            val r = rec?.let { read(it) }
            if (rec == null || r == null) { u.delete(); return@forEach }
            val conv = Book.refs().firstOrNull { c -> Book.secret(c)?.takeIf { Wire.isWhole(it) }?.let { Wire.pairTag(it).contentEquals(r.pair) } == true }
            val word = lay(rec)
            if (me != myKeyTag) return   // the records wait in their person's folder
            when (word) {
                Word.LEFT -> u.delete()
                Word.NOT_LEFT, Word.BEHIND -> {
                    if (conv != null) CoinBook.receive(r.coins, conv, BACK_PREFIX + r.id, CoinBook.entry(CoinEntry.SEND, REF_PREFIX + r.id)?.on)
                    u.delete()
                }
                Word.UNKNOWN, Word.PACED -> {}   // paced: the record stays in its folder, the next settle lays it again
            }
        }
        scan("launch")
    }

    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var again = false
    /**
     * RECEIVES (iOS scan, 3141-3195): every pipe's tag asked on every chain node; a verified record from another key is a credit, a
     * record from my own key my own send from another device of the words -- each once, by its name; a version 2 or 3 record is
     * believed only from its key's chain node, so a record laid on another node (a fork a dishonest client tried) moves nothing.
     * Then the chain is reconciled and the untaken SENDs of a day come back (refundDue). Off the main thread: it asks the nodes.
     */
    /** The v2 records this book credited, each with its sender's key, its coins and its pair: a REFUND by the sender takes them back (iOS noteCredit2, 3677-3686). */
    private const val CREDITS2 = "coin2-credits.json"   // NOT-UI: the file's name
    private const val GONE_PREFIX = "txgone:"           // NOT-UI: the book's name of a credit its sender took back
    fun noteCredit2(id: String, from: String, coins: Long, conv: String) = synchronized(held) {
        val o = JSONObject(load(CREDITS2).toString())
        val m = o.optJSONObject("c") ?: JSONObject()
        if (m.has(id)) return@synchronized
        m.put(id, JSONObject().put("f", from).put("n", coins).put("p", conv))
        save(CREDITS2, o.put("c", m))
    }
    /**
     * THE COINS ARE EITHER GONE OR NOT (constitution, article 8; iOS creditsGone, 3687-3707): a v2 record credited here whose sender's
     * REFUND took it back at its chain node leaves this book by one move under its name, whatever the balance (retake: the truth of
     * a debt). Asked once an hour. Off the main thread: it asks the nodes.
     */
    fun creditsGone() {
        val me = myKeyTag ?: return
        val o = load(CREDITS2)
        val m = JSONObject((o.optJSONObject("c") ?: JSONObject()).toString())
        val now = System.currentTimeMillis() / 1000.0
        if (m.length() == 0 || now - o.optDouble("at", 0.0) < 3600) return
        for (id in m.keys().asSequence().toList()) {
            val c = m.optJSONObject(id)
            val from = c?.optString("f").orEmpty(); val coins = c?.optLong("n") ?: 0L; val conv = c?.optString("p").orEmpty()
            val door = if (from.isEmpty() || conv.isEmpty() || coins <= 0) null else chainDoor(from)
            if (door == null) { m.remove(id); continue }
            val f = ask("$door/tx-settle", JSONObject().put("send", id))
            if (me != myKeyTag) return   // the credits' file stays in its person's folder
            if (f.code != 200 || f.obj?.optString("state") != "refund") continue
            if (CoinBook.holds(CoinEntry.SEND, GONE_PREFIX + id) || CoinBook.retake(coins, conv, GONE_PREFIX + id)) m.remove(id)
            Log.i(TAG, "coin_v3 credit gone coins=" + coins + " id=" + id.take(12))
        }
        synchronized(held) { save(CREDITS2, JSONObject(load(CREDITS2).toString()).put("c", m).put("at", now)) }
    }
    /** A scan that starts after the one in flight ends (iOS scanFresh, 3132): what the node holds now is in the book when it returns. */
    fun scanFresh(why: String) {
        var waited = 0
        while (scanning.get() && waited < 150) { waited++; Thread.sleep(100) }
        scan(why)
    }
    fun scan(why: String) {
        if (!scanning.compareAndSet(false, true)) { again = true; return }
        try {
            val me = myKeyTag ?: return
            val conv = HashMap<String, String>()
            for (c in Book.refs()) Book.secret(c)?.takeIf { Wire.isWhole(it) }?.let { conv[Wire.hex(Wire.pairTag(it))] = c }
            if (conv.isEmpty()) return
            val tags = conv.keys.toList()
            var got = 0; var credited = 0; var own = 0; var bad = 0; var foreign = 0
            val seen = HashSet<String>()
            for ((node, host) in chainNodes) {
                val door = "https://$host/pushwake"
                for (part in tags.chunked(256)) {
                    var since = 0L
                    while (true) {
                        val a = ask("$door/tx-pair", JSONObject().put("tags", org.json.JSONArray(part)).put("since", since))
                        val txs = a.obj?.optJSONArray("txs")
                        if (a.code != 200 || txs == null) break
                        for (i in 0 until txs.length()) {
                            val x = txs.optJSONObject(i) ?: continue
                            val id = x.optString("id"); if (id.isEmpty() || id in seen) continue
                            val b = runCatching { Base64.decode(x.optString("rec"), Base64.DEFAULT) }.getOrNull() ?: continue
                            got++
                            val r = read(b); val c = r?.let { conv[Wire.hex(it.pair)] }
                            if (r == null || r.id != id || c == null) { bad++; continue }
                            if (r.version != 1 && chainNode(r.from) != node) { foreign++; continue }
                            seen.add(id)
                            if (me != myKeyTag) return   // the pipes and the key were the person's who left
                            if (r.from == me) {
                                if (!CoinBook.holds(CoinEntry.SEND, REF_PREFIX + r.id) && CoinBook.retake(r.coins, c, REF_PREFIX + r.id)) own++
                            } else if (r.version == 3) {
                                if (receive3(r, c)) credited++
                            } else {
                                if (CoinBook.receive(r.coins, c, REF_PREFIX + r.id)) credited++
                                if (r.version == 2 && CoinBook.holds(CoinEntry.RECEIVE, REF_PREFIX + r.id)) noteCredit2(r.id, r.from, r.coins, c)
                            }
                        }
                        val last = a.obj?.optLong("last", since) ?: since
                        if (txs.length() != 512 || since >= last) break
                        since = last
                    }
                }
            }
            Log.i(TAG, "coin_tx_scan why=$why pipes=${conv.size} got=$got credited=$credited own=$own bad=$bad foreign=$foreign")
            if (me != myKeyTag) return
            reconcile()   // the chain announced (COIN-TX-V3 sections 3-4)
            refundDue()   // a SEND no one took in a day comes back (section 5)
            creditsGone() // a v2 record its sender took back leaves this book too (fold-4)
            CoinReplica.run(why)   // this phone's copy of the network's chains and its signed root (section 6)
        } finally {
            scanning.set(false)
            if (again) { again = false; Thread { scan("again") }.start() }
        }
    }

    /**
     * A SEND OF VERSION 3 TO ME IS TAKEN BY A RECV ON MY CHAIN (iOS receive3, 3560-3576): the SEND commits to my key under one of the
     * pipe's blinds; the RECV names it and the blind, and only a RECV the node took (or a send it says I already took) credits the book.
     */
    fun receive3(s: Record, conv: String): Boolean {
        val me = myKeyTag ?: return false
        if (s.kind != Kind3.SEND.raw) return false
        val mine = unhex(me) ?: return false
        val secret = Book.secret(conv)?.takeIf { Wire.isWhole(it) } ?: return false
        val blind = Wire.itemKeys(secret).map { CoinChainRules.blind(it, s.nonce) }
            .firstOrNull { CoinChainRules.commitment(mine, it).contentEquals(s.to) } ?: return false
        if (CoinBook.holds(CoinEntry.RECEIVE, REF_PREFIX + s.id)) return false
        val st = reconcile(); if (st == null || !st.v3) return false
        val r = layOnHead { c -> if (me == myKeyTag) build3(Kind3.RECV, s.coins, c.head, fresh(), to = blind, ref = s.id, a = s.a) else null }
        var taken = r.code == 200
        if (!taken && (r.word == "settled" || r.word == "not yours")) chainDoor(s.from)?.let { door ->
            val f = ask("$door/tx-settle", JSONObject().put("send", s.id))
            taken = f.obj?.optString("state") == "recv" && f.obj?.optString("by") == me
        }
        Log.i(TAG, "coin_v3 recv code=" + r.code + " word=" + r.word + " taken=" + (if (taken) 1 else 0) + " id=" + s.id.take(12))
        return taken && me == myKeyTag && CoinBook.receive(s.coins, conv, REF_PREFIX + s.id, heardOn(s.id))
    }

    /**
     * A TRANSFER TO ONE'S OWN OTHER ACCOUNT IS ONE PAIR, SENT AND RECEIVED IN THE SAME STEP (iOS knowOwn/landOwn, MTWalletCore.swift
     * 3578-3630; the author's words 12.10.2026, No.66: «a transfer to oneself in Montana must become one pair of records, sent and
     * received»). The phone holds both seeds -- the person seated and the person on its shelf -- so the SEND names the other key
     * (knowOwn), and the RECV is laid at once on the other key's own chain with that key, the chain opened first by its GENESIS, as
     * its own phone would open it. Only then do the coins stand in the other person's book (Seats.shelfLay), under the name their own
     * scan credits them by: a SEND whose RECV did not stand stays the receiver's to take when seated, or comes back to its sender by
     * the REFUND after a day -- never both. Off the main thread.
     */
    fun knowOwn(seat: String, conv: String): Hands? {
        val hands = Seats.shelfCoins(seat)?.let { derive(it.first) } ?: run { Log.i(TAG, "coin_own seat=$seat no hands"); return null }
        notePeerFrom(conv, hands.from)
        return hands
    }
    fun landOwn(seat: String, id: String, coins: Long, conv: String) {
        val shelf = Seats.shelfCoins(seat)
        val hands = shelf?.let { derive(it.first) }
        val me = myKeyTag
        val door = me?.let { chainDoor(it) }
        if (shelf == null || hands == null || door == null) { Log.i(TAG, "coin_own seat=$seat no hands -- the receiver takes it when seated"); return }
        var v3 = false; var taken = false; var said = "v2"
        val g = ask("$door/tx-get", JSONObject().put("id", id))
        val b = g.obj?.optString("rec")?.takeIf { g.code == 200 && it.isNotEmpty() }?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        if (b != null) {
            v3 = b.size == SIZE3
            val s = if (v3) read3(b)?.takeIf { it.id == id } else null
            val secret = Book.secret(conv)?.takeIf { Wire.isWhole(it) }
            val theirs = unhex(hands.from)
            val tdoor = chainDoor(hands.from)
            if (s != null && secret != null && theirs != null && tdoor != null) {
                val blind = Wire.itemKeys(secret).map { CoinChainRules.blind(it, s.nonce) }
                    .firstOrNull { CoinChainRules.commitment(theirs, it).contentEquals(s.to) }
                if (blind != null) {
                    chain3(hands.from, tdoor)?.takeIf { !it.v3 }?.let {
                        val r = layOnHead(hands) { c -> build3(Kind3.GENESIS, 0L, c.head, fresh(), hands = hands) }   // genesisRoom is 0 from the v2 cut
                        Log.i(TAG, "coin_own seat=$seat genesis code=" + r.code + " word=" + r.word + " amount=0")
                    }
                    val r = layOnHead(hands) { c -> build3(Kind3.RECV, s.coins, c.head, fresh(), to = blind, ref = s.id, a = s.a, hands = hands) }
                    taken = r.code == 200
                    said = "recv code=" + r.code + " word=" + r.word
                } else said = "not their key"
            }
        } else said = "no record code=" + g.code
        if (!taken && !(g.code == 200 && !v3)) {
            Log.i(TAG, "coin_own seat=$seat coins=$coins $said -- the receiver takes it when seated, or it comes back")
            return
        }
        val move = CoinEntry(CoinEntry.RECEIVE, coins, REF_PREFIX + id, conv, null, System.currentTimeMillis() / 1000.0)
        val laid = Seats.shelfLay(seat, move)
        // and on the nodes under their words' rows, as the iPhone lays it (iOS MTCoinVault.lay): any device of their words gathers it
        val rows = MtBindings.nativeMnemonicToEntropy(shelf.first)?.takeIf { it.size == 32 }?.let { e -> CoinVault.lay(listOf(move), e).also { e.fill(0) } } ?: 0
        Log.i(TAG, "coin_own seat=$seat coins=$coins $said book=" + (if (laid) 1 else 0) + " rows=" + rows + " id=" + id.take(12))
    }

    /** An untaken SEND comes back after a day by a REFUND on my chain (iOS refundDue, 3632; section 5): its node's clock decides, one fate a SEND. */
    fun refundDue() {
        val me = myKeyTag ?: return
        val door = chainDoor(me) ?: return
        refundSpent2(me, door)
        val rows = openSends(); if (rows.isEmpty()) return
        val left = ArrayList<JSONObject>()
        for (o in rows) {
            val id = o.optString("id"); val conv = o.optString("conv"); val coins = o.optLong("coins", 0)
            if (id.isEmpty() || conv.isEmpty() || coins <= 0) continue
            val at = o.optLong("at", 0)
            val f = ask("$door/tx-settle", JSONObject().put("send", id))
            if (me != myKeyTag) return   // the open sends stay in their person's folder
            val state = f.obj?.optString("state") ?: ""
            if (state == "recv") continue
            val on = CoinBook.entry(CoinEntry.SEND, REF_PREFIX + id)?.on
            if (state == "refund") { CoinBook.receive(coins, conv, BACK_PREFIX + id, on); continue }
            val st = if (state == "open") chain3(me, door) else null
            if (st == null || at + 86_460 > st.now) { left.add(o); continue }
            val r = layOnHead { c -> if (me == myKeyTag) build3(Kind3.REFUND, coins, c.head, fresh(), ref = id) else null }
            Log.i(TAG, "coin_v3 refund code=" + r.code + " word=" + r.word + " id=" + id.take(12))
            if (me != myKeyTag) return
            if (r.code == 200) CoinBook.receive(coins, conv, BACK_PREFIX + id, on) else left.add(o)
        }
        if (me == myKeyTag) keepOpen(left)
    }

    /**
     * A V2 RECORD THAT SPENT COMES BACK AFTER ITS DAY (iOS refundSpent2, fold-4): my chain's node names my unsettled sends; a v2 one a
     * day old names no receiver, so no chain could ever take it -- it is taken back by a REFUND, under the send's own name.
     */
    private fun refundSpent2(me: String, door: String) {
        val u = ask("$door/tx-unsettled", JSONObject().put("from", me))
        val rows = u.obj?.optJSONArray("sends")
        if (u.code != 200 || rows == null || rows.length() == 0) return
        val st = chain3(me, door) ?: return
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val id = o.optString("id"); val coins = o.optString("amount").toLongOrNull() ?: continue
            if (o.optInt("v") != 2 || id.isEmpty() || !o.has("at") || o.optLong("at") + 86_460 > st.now) continue
            val r = layOnHead { c -> if (me == myKeyTag) build3(Kind3.REFUND, coins, c.head, fresh(), ref = id) else null }
            Log.i(TAG, "coin_v3 refund v2 code=" + r.code + " word=" + r.word + " coins=" + coins + " id=" + id.take(12))
            if (me != myKeyTag) return
            if (r.code == 200) CoinBook.receive(coins, CoinBook.entry(CoinEntry.SEND, REF_PREFIX + id)?.peer ?: "", BACK_PREFIX + id)
        }
    }
}

/**
 * THE BALANCE AS ITS CHAIN HOLDS IT (iOS MTCoinSplit, MTWalletCore.swift 2633-2752): what the key's chain proves and announced, the
 * newest height speaking; its last word kept in the person's folder (filesDir/Coins moves with the seat), so a road that fails never
 * shows the book's smaller number (link 11329).
 */
object CoinSplit {
    class Split(val proven: Long, val declared: Long, val fromCopy: Boolean, val at: Double, val height: Int, val second: Long, val mintEnd: Long)
    @Volatile var split: Split? = null; private set
    private class LastWord(val key: String, val coins: Long, val height: Int, val at: Double)
    private const val LAST = "chain-last-word.json"   // NOT-UI: the file's name in the person's folder
    private var last: LastWord? = null
    private var lastRead = false
    /** The sends on their way to the chain (iOS flying): id → (height laid on, coins). */
    private val flying = HashMap<String, Pair<Int, Long>>()

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private fun nowS(): Double = System.currentTimeMillis() / 1000.0
    private fun file(): File = File(File(Book.ctx.filesDir, "Coins").apply { mkdirs() }, LAST)

    @Synchronized private fun lastWord(): LastWord? {
        if (lastRead) return last
        lastRead = true
        val key = CoinTx.myKeyTag ?: return null
        last = runCatching { JSONObject(file().readText()) }.getOrNull()?.let { o ->
            LastWord(o.optString("key"), o.optLong("coins"), o.optInt("height"), o.optDouble("at")).takeIf { it.key == key }
        }
        return last
    }
    private fun keep(coins: Long, height: Int) {
        val key = CoinTx.myKeyTag ?: return
        val w = LastWord(key, coins, height, nowS())
        synchronized(this) { last = w; lastRead = true }
        val o = JSONObject().put("key", w.key).put("coins", w.coins).put("height", w.height).put("at", w.at)
        runCatching { val f = file(); val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) }
    }

    /** Whether the chain ever spoke for the seated key, in this run or one before it. */
    val spoke: Boolean get() = split != null || lastWord() != null

    /** THE NEWEST STATE OF THE CHAIN SPEAKS (iOS heard): one height is one state; a chain not opened is told to nobody. */
    fun heard(proven: Long, declared: Long, opened: Boolean, fromCopy: Boolean, height: Int, second: Long, mintEnd: Long) {
        if (!opened) return
        synchronized(this) {
            val s = split
            if (s != null && height < s.height) return
            if (s?.proven != proven || s.declared != declared || s.height != height || s.mintEnd != mintEnd) {
                split = Split(proven, declared, fromCopy, nowS(), height, second, mintEnd)
                keep(proven + declared, height)
            }
            flying.entries.removeAll { it.value.first < height }
        }
        told()
        Log.i("Montana", "coin_split proven=" + proven + " declared=" + declared + " copy=" + (if (fromCopy) 1 else 0))
    }
    /** A send laid on the head of height h, on its way until the chain tells a state above h (iOS laid). */
    fun laid(id: String, coins: Long, height: Int) { synchronized(this) { flying[id] = height to coins }; told() }

    /**
     * THE AVAILABLE, ONE NUMBER EVERYWHERE (iOS available; the author's word 16.10.2026): what the chain holds for its one owner, less
     * the sends on their way; before the chain spoke in this run its last word, and the book only when it never spoke for this key.
     */
    val available: Long get() {
        val s = split ?: return lastWord()?.coins?.coerceAtLeast(0L) ?: CoinBook.balance
        val away = synchronized(this) { flying.values.filter { s.height <= it.first }.sumOf { it.second } }
        val lived = CoinChainRules.since(s.second, s.second + maxOf(0L, (nowS() - s.at).toLong()))
        return maxOf(0L, s.proven + s.declared + lived - away)
    }
    /** The seconds a MINT would name before the stop (iOS minting); nothing from the stop. */
    val minting: Long get() {
        val s = split ?: return 0L
        val from = CoinTx.hereFrom ?: return 0L
        if (!CoinTx.here) return 0L
        val now = s.second + maxOf(0L, (nowS() - s.at).toLong())
        if (now < CoinChainRules.ONLINE_CUT || CoinChainRules.MINT_STOP <= now) return 0L
        val t0 = maxOf(s.mintEnd, now - CoinChainRules.MINT_FRESH + CoinTx.PRESENT_MARGIN, from)
        return maxOf(0L, now - t0)
    }
    /** THE TIME BALANCE (iOS growing, word 21842): the available and the seconds on their way to it, 1 coin a second, on the balance. */
    val growing: Long get() = available + (if (split == null) 0L else minting) + PresenceWait.seconds

    /** The number readers off the screen hear (iOS MTCoinBalance.set(available)). */
    @Volatile var said: Long? = null; private set
    @Volatile private var shown: Long? = null
    fun told() {
        shown = growing
        val was = said; val now = available; said = now
        if (was != null && was != now) CoinTell.moved()
        changed()
    }
    /** THE CHAIN'S SECOND ON THE SCREEN (iOS beat): the figure told again; a rise flashes its sum (the wallet's own flash). */
    fun beat() {
        if (!spoke) return
        val before = shown ?: run { shown = growing; changed(); return }
        val now = growing
        if (before == now) return
        told()
        if (before < now) CoinFlash.born(now - before)   // the seconds born rise at the wallet's sides (iOS beat, MTWalletCore.swift 2745)
    }
}

/**
 * THE SECONDS IN THE NETWORK WAIT FOR THE CORE (iOS MTPresenceWait, decision 443 = b): from the mint stop the seconds the person is
 * here are counted on this phone and rise in the Time Balance (CoinSplit.growing), never in the available, and nothing spends them.
 * Kept in the person's folder, folded at every beat of presence; the open stay lives in memory alone, so a run killed never counts
 * the time it was dead.
 */
object PresenceWait {
    private const val NAME = "presence-waiting.json"   // NOT-UI: the file's name in the person's folder
    private var keptKey: String? = null
    private var keptSeconds = 0L
    private var from: Pair<String, Double>? = null
    private fun file(): File = File(File(Book.ctx.filesDir, "Coins").apply { mkdirs() }, NAME)
    private fun nowS(): Double = System.currentTimeMillis() / 1000.0

    @Synchronized private fun load(key: String): Long {
        if (keptKey == key) return keptSeconds
        val o = runCatching { JSONObject(file().readText()) }.getOrNull()
        keptKey = key
        keptSeconds = if (o != null && o.optString("key") == key) o.optLong("seconds") else 0L
        return keptSeconds
    }
    @Synchronized private fun save(key: String, seconds: Long) {
        keptKey = key; keptSeconds = seconds
        val o = JSONObject().put("key", key).put("seconds", seconds)
        runCatching { val f = file(); val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) }
    }
    /** The seconds waiting for the core: the kept ones and the open stay's. */
    val seconds: Long get() {
        val key = CoinTx.myKeyTag ?: return 0L
        val open = synchronized(this) { from?.let { f -> if (f.first == key) maxOf(0.0, nowS() - f.second) else 0.0 } ?: 0.0 }
        return load(key) + open.toLong()
    }
    /** The person came: a stay opens, never before the stop. */
    fun open() {
        val key = CoinTx.myKeyTag ?: return
        synchronized(this) {
            if (from != null) return
            val now = nowS()
            if (now < CoinChainRules.MINT_STOP) return
            from = key to now
        }
    }
    /** The open stay's seconds join the kept ones, and the stay goes on from now. */
    fun fold() {
        val (key, at) = synchronized(this) { from } ?: return
        val now = nowS()
        save(key, load(key) + maxOf(0.0, now - at).toLong())
        synchronized(this) { if (from != null) from = key to now }
    }
    /** The person left: the stay's seconds are kept and it closes. */
    fun close() { fold(); synchronized(this) { from = null } }
}
