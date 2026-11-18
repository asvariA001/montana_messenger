package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * THE PHONE'S COPY OF THE NETWORK'S COIN CHAINS (iOS MTCoinReplica, MTWalletCore.swift 3709-3862, COIN-TX-V3 section 6; the author's
 * words 11.10.2026 19:5x UTC: «the core and the nodes are every device ... agreeing to keep a copy they keep a copy of the chain of time
 * of the coins»). EVERY PHONE THAT KEEPS A COPY IS A NODE OF THE COIN CHAINS: with the person's consent to keep for others
 * (KeepCopy.others) the phone pulls what each chain node took, verifies every signature with the core, folds every chain by the nodes'
 * own rules (CoinChainRules.step), computes the root of the newest checkpoint itself and signs it with the seed's coin key; the nodes
 * keep the signature and the Explorer counts it in the quorum. The whole network's chains, never only the correspondents': a holder's
 * signature must not say whose chains it cares about. The diary says what this phone holds (coin_replica), never whose chains.
 */
object CoinReplica {
    private const val TAG = "Montana"
    private const val FILE = "coin-replica.json"   // NOT-UI: the file's name in the TimeChain folder (iOS MTTimeChainPlace)
    private const val ROUND_S = 600.0              // once in ten minutes at most
    private const val PAGE = 512

    /** One record a chain node took: its name, its bytes in base64, the node's second, the node. */
    class Row(val id: String, val rec: String, val at: Long, val node: String)
    /**
     * [at]: the moment of the last round, kept with the rows -- a process a push woke is born with nothing in memory (iOS Held.at).
     * [known]: the names of the rows whose signature verified here once.
     */
    class Held(val seq: MutableMap<String, Long> = HashMap(), val rows: MutableList<Row> = ArrayList(), var at: Double = 0.0, var known: Set<String> = emptySet())

    private val running = AtomicBoolean(false)
    @Volatile private var ranAt = 0.0
    private fun now(): Double = System.currentTimeMillis() / 1000.0

    private fun file(): File = File(File(Book.ctx.filesDir, "TimeChain").apply { mkdirs() }, FILE)
    private fun load(): Held {
        val o = runCatching { JSONObject(file().readText()) }.getOrNull() ?: return Held()
        val h = Held(at = o.optDouble("at", 0.0))
        o.optJSONObject("seq")?.let { s -> for (k in s.keys()) h.seq[k] = s.optLong(k) }
        o.optJSONArray("rows")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                h.rows.add(Row(r.optString("id"), r.optString("rec"), r.optLong("at"), r.optString("node")))
            }
        }
        h.known = o.optJSONArray("known")?.let { a -> (0 until a.length()).map { a.optString(it) }.toHashSet() } ?: emptySet()
        return h
    }
    private fun save(h: Held) {
        val o = JSONObject().put("at", h.at)
        o.put("seq", JSONObject().apply { for ((k, v) in h.seq) put(k, v) })
        o.put("rows", JSONArray().apply { for (r in h.rows) put(JSONObject().put("id", r.id).put("rec", r.rec).put("at", r.at).put("node", r.node)) })
        o.put("known", JSONArray(h.known.sorted()))
        runCatching { val f = file(); val t = File(f.path + ".tmp"); t.writeText(o.toString()); t.renameTo(f) }
    }
    private fun le(v: Long): ByteArray = ByteArray(8) { (v ushr (8 * it)).toByte() }

    class Folded(var keys: Int = 0, var records: Int = 0, var root: String = "", var bad: Int = 0, var waiting: Int = 0,
                 var mine: CoinChainRules.St? = null, val verified: MutableSet<String> = HashSet())
    /**
     * EVERY CHAIN FOLDED FROM ITS FIRST RECORD IN ITS OWN ORDER (iOS fold): prev links a record to the one before, a RECV after the
     * SEND it settles; then the root of the checkpoint [T] exactly as the node computes it (section 6): for every key in ascending
     * from, its last record and its count at T.
     */
    fun fold(rows: List<Row>, T: Long, me: String? = null, known: Set<String> = emptySet()): Folded {
        val out = Folded()
        val byPrev = HashMap<String, HashMap<String, CoinTx.Record>>()
        val atOf = HashMap<String, Long>()
        val seen = HashSet<String>()
        for (row in rows) {
            if (!seen.add(row.id)) continue
            val d = runCatching { Base64.decode(row.rec, Base64.DEFAULT) }.getOrNull()
            val r = d?.let { CoinTx.read(it, row.id in known) }
            if (r == null || r.id != row.id) { out.bad++; continue }
            out.verified.add(r.id)
            if (r.version == 1) continue   // a version 1 row stands on no chain
            if (CoinTx.chainNode(r.from) != row.node) { out.bad++; continue }   // a node serves only its own chains
            byPrev.getOrPut(r.from) { HashMap() }[r.prev] = r
            atOf[r.id] = row.at
        }
        val st = HashMap<String, CoinChainRules.St>()
        val sends = HashMap<String, CoinChainRules.SendInfo>()
        val settled = HashSet<String>(); val broken = HashSet<String>()
        val order = HashMap<String, MutableList<String>>()
        var moved = true
        while (moved) {
            moved = false
            for ((from, chain) in byPrev) {
                if (from in broken) continue
                var s = st[from] ?: CoinChainRules.St()
                while (true) {
                    val r = chain[s.head] ?: break
                    val at = atOf[r.id] ?: 0L
                    val next = CoinChainRules.step(s, r, at, sends, settled)
                    if (next.wait) break
                    val n = next.next
                    if (n == null) { broken.add(from); out.bad++; break }
                    if (r.version == 3 && r.kind == CoinChainRules.KIND_SEND) sends[r.id] = CoinChainRules.SendInfo(from, r.coins, at, r.to, r.a)
                    if (r.version == 2) CoinChainRules.v2Spent(s, r.coins)?.let { d -> sends[r.id] = CoinChainRules.SendInfo(from, r.coins, at, ByteArray(32), d) }
                    if (r.version == 3 && (r.kind == CoinChainRules.KIND_RECV || r.kind == CoinChainRules.KIND_REFUND)) settled.add(r.ref)
                    order.getOrPut(from) { ArrayList() }.add(r.id)
                    s = n; moved = true
                }
                st[from] = s
            }
        }
        for ((from, chain) in byPrev) if (from !in broken && chain[st[from]?.head ?: CoinTx.ZERO] != null) out.waiting++
        if (me != null && me !in broken) out.mine = st[me]
        val h = MessageDigest.getInstance("SHA-256")
        h.update("mt-coin-root-v1".toByteArray(Charsets.US_ASCII))
        for (from in order.keys.sorted()) {
            val ids = order[from].orEmpty().filter { (atOf[it] ?: Long.MAX_VALUE) <= T }
            val last = ids.lastOrNull() ?: continue
            val f = CoinChainRules.bytes(from) ?: continue
            val l = CoinChainRules.bytes(last) ?: continue
            h.update(f); h.update(l); h.update(le(ids.size.toLong()))
            out.keys++; out.records += ids.size
        }
        out.root = Wire.hex(h.digest())
        return out
    }

    /**
     * ONE ROUND (iOS run): what each chain node took since the last round, the whole fold, the root of the nodes' newest checkpoint
     * computed here and signed with the seed's coin key at both nodes. Once in ten minutes at most -- across processes too, the round's
     * moment stands in the file it folds -- and only with the consent to keep for others. Off the main thread: it asks the nodes.
     */
    fun run(why: String) {
        if (!KeepCopy.others || now() - ranAt < ROUND_S) return
        val k = CoinTx.keys() ?: return
        if (!running.compareAndSet(false, true)) return
        try {
            ranAt = now()
            val held = load()
            if (now() - held.at < ROUND_S) return
            held.at = now()
            var pulled = 0
            for ((name, host) in CoinTx.chainNodes) {
                val door = "https://$host/pushwake"
                var since = held.seq[name] ?: 0L
                for (page in 0 until 64) {
                    val a = CoinTx.ask("$door/tx-since", JSONObject().put("since", since))
                    val txs = a.obj?.optJSONArray("txs")
                    if (a.code != 200 || txs == null) break
                    for (i in 0 until txs.length()) {
                        val x = txs.optJSONObject(i) ?: continue
                        val id = x.optString("id"); val rec = x.optString("rec")
                        if (id.isEmpty() || rec.isEmpty() || !x.has("seq")) continue
                        held.rows.add(Row(id, rec, x.optLong("at", 0L), name))
                        since = maxOf(since, x.optLong("seq")); pulled++
                    }
                    held.seq[name] = since
                    if (txs.length() < PAGE) break
                }
            }
            save(held)
            val me = CoinTx.keyTag(k.pk)
            val door = CoinTx.chainDoor(me) ?: return
            val r = CoinTx.ask("$door/tx-root", JSONObject())
            val T = r.obj?.takeIf { r.code == 200 && it.has("at") }?.optLong("at")
            val nodeRoot = r.obj?.optString("root").orEmpty()
            if (T == null || nodeRoot.isEmpty()) {
                Log.i(TAG, "coin_replica why=$why pulled=$pulled rows=${held.rows.size} no checkpoint code=${r.code}")
                return
            }
            val f = fold(held.rows, T, me, held.known)
            if (f.verified != held.known) { held.known = f.verified; save(held) }
            // the person's own chain as this copy folded it: the wallet's split hears it before the node's word (CoinSplit)
            val mine = f.mine
            if (f.bad == 0 && mine != null) CoinSplit.heard(mine.proven, mine.declared, mine.v3, true, mine.height, T, mine.mintEnd)
            var signed = 0
            val root = CoinChainRules.bytes(f.root)
            if (f.bad == 0 && 0 < f.keys && root != null) {
                val msg = "mt-coin-root-sig-v1".toByteArray(Charsets.US_ASCII) + le(T) + root
                val sig = MtBindings.nativeSign(k.sk, msg)
                if (sig != null) for ((_, host) in CoinTx.chainNodes) {
                    val a = CoinTx.ask("https://$host/pushwake/tx-root-sign", JSONObject()
                        .put("pk", Base64.encodeToString(k.pk, Base64.NO_WRAP)).put("at", T).put("root", f.root)
                        .put("sig", Base64.encodeToString(sig, Base64.NO_WRAP)))
                    if (a.code == 200) signed++
                }
            }
            Log.i(TAG, "coin_replica why=$why keys=${f.keys} records=${f.records} pulled=$pulled bad=${f.bad} waiting=${f.waiting} T=$T " +
                "root=${f.root.take(12)} same=${if (f.root == nodeRoot) 1 else 0} signed=$signed")
        } finally {
            running.set(false)
        }
    }
}
