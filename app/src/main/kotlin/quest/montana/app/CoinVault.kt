package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * THE SEED'S BOOK ON THE NODES (iOS MTCoinVault, MTWalletCore 822-1113; the author's words 04.10.2026): every move of the book is
 * sealed under a key only the words derive (ChaCha20-Poly1305 over the LZFSE of the moves' JSON, bound to «mt.coins.vault») and laid
 * on every node in pieces; any device with the same words gathers the union -- a move is its name, so the union is the book, the same
 * on every device of the seed. A node answers with the names of the pieces it keeps, and each node is sent the moves its pieces lack.
 * Past `FOLD` pieces on a node the whole book is laid there anew and the old pieces go in the same breath. `heard` says a node
 * answered this seed's book since the launch: a device that has not gathered its seed's book never tells the table a balance it does
 * not hold (TopNet waits for it).
 *
 * A NODE THAT KEEPS NO COPY IS NOT SENT THE BOOK AGAIN (decision 1060 = a, 17.11.2026, montana-node-store.py: «/vault-put is refused,
 * the sweep empties the table»): a row that answered 403 takes no piece until the seat moves or the app starts anew; its /vault-get
 * is still asked, so the book of a node that keeps one again is read, and `heard` stands as on the iPhone.
 */
object CoinVault {
    private val tokenLabel = "mt-coins-vault-token".toByteArray()   // NOT-UI: the token's own label
    private val aad = "mt.coins.vault".toByteArray()                // NOT-UI: the words the seal is bound to
    private val sealLabel = "mt-coins-vault-key-v1".toByteArray()   // NOT-UI: the seal's derivation label (iOS MTSeedVault.seal)
    private val ownerLabel = "mt-coins-vault-owner-v1".toByteArray()   // NOT-UI: the row's derivation label (iOS MTSeedVault.owner)
    private val nodeLabel = "mt-coins-vault-node-v1".toByteArray()  // NOT-UI: a node's own row label (nodeToken)
    private val tipLabel = "mt-timechain-tip-token".toByteArray()   // NOT-UI: the tips' row label (TimeChainTip)
    private const val KEPT = "coin-vault-pieces"                    // NOT-UI: the file's own name
    /** How many pieces a node keeps for a seed before the whole book is laid there as one; a piece carries `SPAN` moves at most. */
    const val FOLD = 192
    const val SPAN = 8000
    /** The seed's union was gathered within the minting's minute (iOS freshS). */
    private const val FRESH_S = 60.0

    @Volatile var heard = false; private set
    @Volatile private var heardAt = 0.0
    val fresh: Boolean get() = heard && nowS() - heardAt < FRESH_S

    // every field below is touched on the vault's own thread only
    private val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "montana-coin-vault") }
    private var pieces = HashMap<String, Set<String>>()    // a piece's name -> the names of the moves it carries, opened once
    private var held = HashMap<String, List<String>>()     // a row -> the pieces it said it keeps
    private val full = HashSet<String>()                   // the rows that refused a piece as full: laid whole next
    private val relay = HashSet<String>()                  // the rows that keep no copy (403): no piece is sent to them
    private var running = false
    private var again = false
    private var keptRead = false
    private var keptWroteAt = 0.0
    private val dueLock = Any()
    private var dueAt = Double.POSITIVE_INFINITY

    private fun nowS() = System.currentTimeMillis() / 1000.0

    // ── the keys (iOS keys, nodeToken, keyKAT) ──
    class Keys(val seal: ByteArray, val token: ByteArray)
    fun keys(entropy: ByteArray): Keys {
        val owner = owner(entropy)
        return Keys(Hkdf.sha256(entropy, ByteArray(0), sealLabel), Hkdf.hmac(owner, tokenLabel)).also { owner.fill(0) }
    }
    private fun owner(entropy: ByteArray) = Hkdf.sha256(entropy, ByteArray(0), ownerLabel)
    /** The tips' row (iOS keys().tip): HMAC-SHA-256 under the owner key over «mt-timechain-tip-token» (TimeChainTip). */
    fun tipToken(entropy: ByteArray): ByteArray { val owner = owner(entropy); return Hkdf.hmac(owner, tipLabel).also { owner.fill(0) } }
    /** EVERY NODE HOLDS THE BOOK UNDER A ROW OF ITS OWN (iOS nodeToken): HMAC-SHA-256 under the owner key over the label and the
     *  node's host; a node learns its own row and no other. The common row stays beside it. */
    fun nodeToken(entropy: ByteArray, host: String): ByteArray {
        val owner = owner(entropy)
        return Hkdf.hmac(owner, nodeLabel + host.toByteArray()).also { owner.fill(0) }
    }
    private class Row(val slot: String, val door: String, val token: String)
    private fun rows(doors: List<String>, common: String, entropy: ByteArray): List<Row> = doors.flatMap { door ->
        val host = runCatching { java.net.URI(door).host }.getOrNull() ?: door
        listOf(Row("$door#own", door, hex(nodeToken(entropy, host))), Row(door, door, common))
    }
    /** THE FROZEN VECTORS (iOS keyKAT, tools/mt-top-check.py): RFC 5869 HKDF-SHA-256, HMAC-SHA-256 and SHA-256 on the counting bytes. */
    fun keyKAT(): Boolean {
        val k = keys(ByteArray(32) { it.toByte() })
        return hex(k.seal) == "e9720340e7dcb4b6c2befea9a657718560646f727614f732971bf9ea7d3795fb"
            && hex(k.token) == "4b7fc64e903945bee08b4fc45f7ca094d9b61ae2c96f4d97ebd1ccd8b4b9ab86"
            && hex(MessageDigest.getInstance("SHA-256").digest(k.token)) == "9e34605e5200c5f339f4a527f0654af5436a96b629f66835f91495b65e0ba245"
    }

    // ── the seal (iOS seal/open): ChaChaPoly combined = nonce 12 ‖ ciphertext ‖ tag 16 ──
    fun seal(moves: List<CoinEntry>, key: ByteArray): ByteArray? = runCatching {
        val plain = JSONArray().apply { moves.forEach { put(JSONObject(it.line())) } }.toString().toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        nonce + c.doFinal(Lzfse.pack(plain))
    }.getOrNull()
    /** The moves of a piece; null when it does not open or one move does not read (iOS decodes the whole array or nothing). */
    fun open(sealed: ByteArray, key: ByteArray): List<CoinEntry>? = runCatching {
        if (sealed.size < 28) return null
        val c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(sealed, 0, 12))
        c.updateAAD(aad)
        val plain = Lzfse.unpack(c.doFinal(sealed, 12, sealed.size - 12)) ?: return null
        val a = JSONArray(String(plain, Charsets.UTF_8))
        (0 until a.length()).map { CoinEntry.of(a.getJSONObject(it).toString()) ?: return null }
    }.getOrNull()

    // ── the kept pieces (iOS KeptPieces): the names stand across launches, forgotten with the seat ──
    private fun keptFile() = File(File(Book.ctx.filesDir, "Coins").apply { mkdirs() }, KEPT)
    private fun readKept(token: String) {
        if (keptRead) return
        keptRead = true
        val o = runCatching { JSONObject(keptFile().readText()) }.getOrNull() ?: return
        if (o.optString("token") != token || pieces.isNotEmpty()) return
        fun lists(j: JSONObject?): HashMap<String, List<String>> {
            val out = HashMap<String, List<String>>()
            j?.keys()?.forEach { k -> j.optJSONArray(k)?.let { a -> out[k] = (0 until a.length()).map { a.optString(it) } } }
            return out
        }
        pieces = HashMap(lists(o.optJSONObject("pieces")).mapValues { it.value.toSet() })
        held = lists(o.optJSONObject("held"))
        Log.i("Montana", "coins_vault kept pieces=${pieces.size} rows=${held.size}")
    }
    private fun writeKept(token: String) {
        val now = nowS()
        if (now - keptWroteAt <= 600) return
        keptWroteAt = now
        val o = JSONObject().put("token", token)
            .put("pieces", JSONObject().apply { pieces.forEach { (k, v) -> put(k, JSONArray(v.toList())) } })
            .put("held", JSONObject().apply { held.forEach { (k, v) -> put(k, JSONArray(v)) } })
        runCatching {
            val f = keptFile(); val tmp = File(f.parentFile, "$KEPT.tmp")
            tmp.writeText(o.toString()); tmp.renameTo(f)
        }
    }

    /** The person who left the seat takes their vault's memory with them (iOS forget). */
    fun forget() {
        heard = false; heardAt = 0.0
        lane.execute {
            pieces = HashMap(); held = HashMap(); full.clear(); relay.clear()
            keptRead = false; keptWroteAt = 0.0
            runCatching { keptFile().delete() }
        }
    }

    /** A gathering soon: the moves of a minute ride one piece, never one each; the nearest ask wins (iOS soon). */
    fun soon(why: String, afterS: Double = 60.0) {
        if (!GooglePlay.COINS) return
        val at = nowS() + afterS
        synchronized(dueLock) { if (dueAt <= at) return; dueAt = at }
        MainThread.later((afterS * 1000).toLong(), Runnable {
            synchronized(dueLock) { if (dueAt != at) return@Runnable; dueAt = Double.POSITIVE_INFINITY }
            lane.execute { gather(why) }
        })
    }
    /** A gathering now, on the vault's own thread (the wallet's opening). */
    fun gatherSoon(why: String) { if (GooglePlay.COINS) lane.execute { gather(why) } }

    /** The seat moved while the gather waited: nothing of the person who left is written or kept. */
    private fun moved(gen: Int, why: String): Boolean {
        if (gen == CoinBook.generation) return false
        Log.i("Montana", "coins_vault why=$why the seat moved -- the gather writes nothing")
        return true
    }

    private fun post(url: String, path: String, body: JSONObject, timeoutMs: Int): Pair<Int, JSONObject?> {
        val (code, text) = Wire.post(url, path, body, timeoutMs)
        return code to text?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun gather(why: String) {
        if (running) { again = true; return }
        val e = HomeShelf.entropy() ?: return
        try {
            val gen = CoinBook.generation
            val doors = Doors.onePerNode(Doors.ordered("vault"))
            if (doors.isEmpty()) return
            running = true
            val k = keys(e)
            val token = hex(k.token)
            readKept(token)
            if (moved(gen, why)) return
            val heldBefore = HashMap(held); val piecesBefore = pieces.keys.toSet()
            val rows = rows(doors, token, e)
            val answered = ArrayList<String>()
            val got = ArrayList<CoinEntry>()
            for (r in rows) {
                // THE ROW IS ASKED FOR WHAT IT LACKS HERE (iOS 18.10.2026): its own pieces, opened here, are what it need not send
                val have = (held[r.slot] ?: emptyList()).filter { it in pieces }.take(256)
                val (code, o) = post(r.door, "/vault-get", JSONObject().put("token", r.token).put("have", JSONArray(have)), 20_000)
                val ids = o?.optJSONArray("held")
                if (code != 200 || ids == null) continue
                if (moved(gen, why)) return
                val ps = o.optJSONArray("pieces")
                for (i in 0 until (ps?.length() ?: 0)) {
                    val x = ps!!.optJSONObject(i) ?: continue
                    val id = x.optString("piece")
                    val b = runCatching { Base64.decode(x.optString("blob"), Base64.DEFAULT) }.getOrNull() ?: continue
                    if (id.isEmpty() || hex(MessageDigest.getInstance("SHA-256").digest(b)) != id) continue   // a piece is its own name
                    val moves = open(b, k.seal) ?: continue
                    pieces[id] = moves.map { it.id }.toSet()
                    got += moves
                }
                held[r.slot] = (0 until ids.length()).map { ids.optString(it) }
                answered += r.slot
            }
            if (answered.isEmpty() || moved(gen, why)) return
            heard = true
            heardAt = nowS()
            val joined = CoinBook.join(got)
            val book = CoinBook.entries()
            var laid = 0; var whole = 0
            for (p in plan(answered.filter { it !in relay }, book)) {
                val r = rows.firstOrNull { it.slot == p.slot } ?: continue
                var replace = p.replace
                var start = 0
                while (start < p.lacking.size) {
                    // THE OLD PIECES GO WITH THE LAST NEW ONE (the coin audit's sixth point): every part is laid first
                    val part = p.lacking.subList(start, minOf(start + SPAN, p.lacking.size))
                    val last = p.lacking.size <= start + SPAN
                    val sealed = seal(part, k.seal) ?: break
                    if (moved(gen, why)) return
                    val body = JSONObject().put("token", r.token).put("blob", Base64.encodeToString(sealed, Base64.NO_WRAP))
                        .put("replace", JSONArray(if (last) replace else emptyList()))
                    val (code, o) = post(r.door, "/vault-put", body, 30_000)
                    if (moved(gen, why)) return
                    if (code == 413) { full.add(p.slot); break }
                    if (code == 403) { relay.add(p.slot); break }   // decision 1060: this node keeps no copy
                    val id = o?.optString("piece")?.takeIf { code == 200 && it.isNotEmpty() } ?: break
                    pieces[id] = part.map { it.id }.toSet()
                    val gone = if (last) replace.toSet() else emptySet()
                    held[p.slot] = (held[p.slot] ?: emptyList()).filter { it !in gone } + id
                    if (last) replace = emptyList()
                    laid += part.size
                    if (p.anew) whole++
                    start += SPAN
                }
                if (p.anew && whole > 0) full.remove(p.slot)
            }
            val live = held.values.flatten().toSet()
            pieces = HashMap(pieces.filterKeys { it in live })   // a piece no node keeps is forgotten here too
            if (held != heldBefore || pieces.keys != piecesBefore) writeKept(token)
            Log.i("Montana", "coins_vault why=$why rows=${answered.size}/${rows.size} got=${got.size} joined=$joined laid=$laid whole=$whole relay=${relay.size} moves=${book.size}")
            MainThread.post { TopNet.put() }   // the row tells the union just gathered (iOS 1058)
        } catch (t: Throwable) {
            Log.w("Montana", "coins_vault why=$why ${t.javaClass.simpleName}")
        } finally {
            e.fill(0)
            running = false
            if (again) { again = false; soon("again", 1.0) }
        }
    }

    private class Plan(val slot: String, val lacking: List<CoinEntry>, val replace: List<String>, val anew: Boolean)
    private fun plan(answered: List<String>, book: List<CoinEntry>): List<Plan> {
        var inBook: Set<String>? = null
        val out = ArrayList<Plan>()
        for (slot in answered) {
            val ids = held[slot] ?: emptyList()
            val carried = HashSet<String>().apply { ids.forEach { addAll(pieces[it] ?: emptySet()) } }
            val anew = FOLD <= ids.size || slot in full
            val lacking = if (anew) book else book.filter { it.id !in carried }
            if (lacking.isEmpty()) continue
            // ONLY A PIECE READ HERE IS REPLACED (the coin audit's third point): a piece that would not open stays beside the whole
            val replace = if (!anew) emptyList() else {
                val whole = inBook ?: book.map { it.id }.toSet().also { inBook = it }
                ids.filter { id -> pieces[id]?.let { whole.containsAll(it) } ?: false }
            }
            out += Plan(slot, lacking, replace, anew)
        }
        return out
    }

    /** A PERSON ON THE SHELF IS CREDITED IN THEIR OWN BOOK (iOS lay, MTShelfPost): the moves sealed under their words' key and laid
     *  under both rows of every node, as their own gather lays them. The rows that took the piece. Off the screen's thread. */
    fun lay(moves: List<CoinEntry>, entropy: ByteArray): Int {
        if (entropy.size != 32 || moves.isEmpty()) return 0
        val k = keys(entropy)
        val doors = Doors.onePerNode(Doors.ordered("vault"))
        val sealed = seal(moves, k.seal) ?: return 0
        if (doors.isEmpty()) return 0
        val blob = Base64.encodeToString(sealed, Base64.NO_WRAP)
        return rows(doors, hex(k.token), entropy).count { r ->
            post(r.door, "/vault-put", JSONObject().put("token", r.token).put("blob", blob).put("replace", JSONArray()), 30_000).first == 200
        }
    }
}
