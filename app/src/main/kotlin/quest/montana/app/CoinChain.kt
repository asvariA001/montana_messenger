package quest.montana.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE COINS ARE THE CHAIN'S (iOS MTCoinChainRules, MTCoinTx, MTCoinSplit, MTPresenceWait -- MTWalletCore.swift 2457-2830, 2838-3400;
 * the author's words 23.10.2026 00:27 UTC: «the phone only mirrors the chain»; 14.11.2026 12:13 UTC, word 21842: «as before it must
 * show the minting, 1 coin 1 second, and credit it on the balance»). A person's coins stand on their key's chain on the protocol's
 * chain nodes; this phone asks its node what the chain holds (POST /tx-head) and shows it, and while the person is here -- the app in
 * front of them -- it counts their seconds, which rise in the Time Balance and never in the available: only the core makes coins of
 * them. The book (CoinBook) speaks only before the chain ever spoke for the seated key.
 *
 * THIS PART READS AND WRITES NOTHING ON THE CHAIN: the chain's records (GENESIS, SEND, RECV, REFUND, iOS layOnHead) come with the
 * transfers, in their own part. Unopened chains (no v3) are told to nobody (iOS heard: guard opened).
 */
object CoinChainRules {
    const val V2_CUT = 1_792_443_600L      // NOT-UI: 19.10.2026 21:00 UTC, the node's TX_V2_CUT (COIN-TX-V3 section 8)
    const val TIME_CUT = 1_792_702_800L    // NOT-UI: 22.10.2026 21:00 UTC, the node's TX_TIME_CUT: the chain mints the key's time itself
    const val ONLINE_CUT = 1_792_962_000L  // NOT-UI: 25.10.2026 21:00 UTC, the node's TX_ONLINE_CUT: minting only while the person is here
    const val MINT_FRESH = 120L
    const val MINT_STOP = 1_793_361_600L   // NOT-UI: 30.10.2026 12:00 UTC, the node's TX_MINT_STOP: no MINT at all from here

    /** What the time step adds to a key folded at the node second [s] by the node second [t], no record between (iOS since). */
    fun since(s: Long, t: Long): Long = if (s < TIME_CUT) 0L else maxOf(0L, minOf(t, ONLINE_CUT) - minOf(s, ONLINE_CUT))
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

    /**
     * THE CHAIN READ (iOS reconcile, read half): what the key's node folds is told to the split. After the mint stop the node takes no
     * MINT, so nothing is laid here; a chain not opened yet (no v3) waits for its GENESIS, which the transfers' part lays.
     */
    fun reconcile(): Chain3? {
        val me = myKeyTag ?: return null
        val door = chainDoor(me) ?: return null
        val st = chain3(me, door) ?: return null
        if (me != myKeyTag) { Log.i(TAG, "coin_v3 the seat moved -- nothing told"); return null }
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
