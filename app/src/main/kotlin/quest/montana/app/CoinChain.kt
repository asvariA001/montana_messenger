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
}

object CoinTx {
    private const val TAG = "Montana"
    /** The protocol's chain set, one place (iOS chainNodes): a key's chain lives on the node at its tag's first byte. */
    private val chainNodes = listOf("montana.quest" to "api.montana.quest", "montana.xxx" to "montana.xxx")   // NOT-UI: node names
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
    fun read(rec: ByteArray): Record? {
        if (rec.size < 5 || !rec.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val v = rec[4].toInt()
        if (v == 3) return read3(rec)
        if (v != 1 && v != 2) return null
        val end = if (v == 1) 2021 else 2053; val keyAt = if (v == 1) 69 else 101
        val total = if (v == 1) SIZE1 else SIZE2; val prefix = if (v == 1) SIGNED1 else SIGNED2
        if (rec.size != total) return null
        val coins = u64(rec, 5)
        if (coins <= 0) return null
        val body = rec.copyOfRange(0, end); val pk = rec.copyOfRange(keyAt, end); val sig = rec.copyOfRange(end, total)
        if (!MtBindings.nativeVerify(pk, prefix + body, sig)) return null
        return Record(name(rec), v, coins, u64(rec, 13), rec.copyOfRange(37, 69), if (v == 2) Wire.hex(rec.copyOfRange(69, 101)) else "", keyTag(pk))
    }
    fun read3(rec: ByteArray): Record? {
        if (rec.size != SIZE3 || !rec.copyOfRange(0, 4).contentEquals(MAGIC) || rec[4].toInt() != 3) return null
        val kind = rec[5].toInt() and 0xff
        if (kind !in 1..6) return null
        val amount = u64(rec, 6)
        if (amount < 0 || (amount == 0L && kind != Kind3.GENESIS.raw)) return null
        val pair = rec.copyOfRange(70, 102); val to = rec.copyOfRange(102, 134); val ref = rec.copyOfRange(134, 166)
        val a = u64(rec, 166); val b = u64(rec, 174)
        if (!fieldsHold(kind, amount, pair, to, ref, a, b)) return null
        val body = rec.copyOfRange(0, 2134); val pk = rec.copyOfRange(182, 2134); val sig = rec.copyOfRange(2134, SIZE3)
        if (!MtBindings.nativeVerify(pk, SIGNED3 + body, sig)) return null
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
        if (a.code == 200 && a.obj?.optString("id") == id) return Word.LEFT
        Log.i(TAG, "coin_lay code=" + a.code + " error=" + (a.obj?.optString("error")?.ifEmpty { null } ?: "-") + " unreached=" + (if (a.unreached) 1 else 0) + " id=" + id.take(12))
        if (a.code == 409) return Word.BEHIND
        if (a.code == 429) return Word.PACED
        if (a.code == 400 || a.code == 507 || a.unreached) return Word.NOT_LEFT
        return if (ask("$door/tx-get", JSONObject().put("id", id)).code == 200) Word.LEFT else Word.UNKNOWN
    }
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

    const val REF_PREFIX = "tx:"       // NOT-UI: the book's name of a transfer's moves (iOS refPrefix)
    const val BACK_PREFIX = "txback:"  // NOT-UI: the book's name of a debit no node took (iOS backPrefix)
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
