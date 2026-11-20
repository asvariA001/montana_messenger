package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

/**
 * THE MONTANA TOP (iOS MTTopNet, MTWalletCore 685-835; the author's words 04.10.2026 00:03, 02:40 and 02:47 UTC: «a live set as by a
 * web socket»; «everyone has one source of all data»; «who does not show is not in the top»). One public table of the game's coins,
 * the same on every node (/top-put, /top-get): this phone puts its own row and the table comes back whole. A row is named by sha256 of
 * a token only the words derive -- HKDF of the entropy under «mt-top-owner-v1», then one HMAC over «mt-top-token» -- so only its owner
 * rewrites or withdraws it. The row tells the seed's union, never one device's share: it waits for the seed's book gathered within the
 * minute (CoinVault.fresh), and the gathering puts it. The row proves its coin key: the seed's ML-DSA key signs «mt-top-key-v1» and the
 * token, and the node shows that key's chain as it folds it, never the number told here.
 */
object TopNet {
    class Row(val id: String, val name: String, val coins: Long)

    /** The rows the nodes answered, the most coins first; empty until a node that keeps the table speaks. */
    @Volatile var rows: List<Row> = emptyList(); private set
    /** A node answered: before the nodes keep the table the wallet shows the pairs' rating (CoinBoard). */
    @Volatile var answered = false; private set
    /** This person's place in the whole table, or null when they are not in it. */
    @Volatile var rank: Int? = null; private set
    /** The wallet's redraw while it stands open. */
    @Volatile var changed: (() -> Unit)? = null

    private val keyLabel = "mt-top-owner-v1".toByteArray()   // NOT-UI: the derivation's own label
    private val tokenLabel = "mt-top-token".toByteArray()    // NOT-UI: the token's own label
    const val TOLD_KEY = "top.told"                           // NOT-UI: the wallet explained the public ranking (iOS MTCoinShow.toldKey)
    private const val UNASKED_KEY = "top.unaskedWithdrawn"    // NOT-UI: this device withdrew the row of a person never asked
    private const val TOLD_BEFORE_KEY = "top.toldBeforeConsent"   // NOT-UI: the told mark as this build first found it
    @Volatile private var asking = false

    fun key(entropy: ByteArray): ByteArray = Hkdf.sha256(entropy, ByteArray(0), keyLabel)
    fun token(k: ByteArray): ByteArray = Hkdf.hmac(k, tokenLabel)
    fun row(t: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(t))
    private fun myToken(): ByteArray? {
        val e = HomeShelf.entropy() ?: return null
        val k = key(e); e.fill(0)
        return token(k).also { k.fill(0) }
    }
    /** The row this seed owns, or null without a seed. */
    fun myRow(): String? = myToken()?.let { row(it) }

    /** THE FROZEN VECTORS (iOS keyKAT, tools/mt-top-check.py): RFC 5869 HKDF-SHA-256, HMAC-SHA-256 and SHA-256 on the counting bytes. */
    fun keyKAT(): Boolean {
        val k = key(ByteArray(32) { it.toByte() })
        val t = token(k)
        return hex(k) == "7a504b1911419189651d037cfa3776404731270ebe01a2cbefe7aabbca5140d5"
            && hex(t) == "67b2f9e4f8ba74bffee41cdfd8de2d3efca20554a495910ebc823b8ef4545ce2"
            && row(t) == "f0b3cb47ae97a4849fd8d42d75bbbcc395f93b9b4adae16c5bb0e91b4641d369"
    }

    /** The coins the row tells: the chain's available once it has spoken (iOS MTCoinBalance.now, set by MTCoinSplit); nothing before. */
    private fun coinsNow(): Long? = if (CoinSplit.spoke) CoinSplit.available else null

    /** The row says the coins now -- paced by CoinTell, never before the wallet told its owner what showing does, never when the
     *  owner hid the coins. */
    fun put() {
        if (!GooglePlay.COINS) return
        if (!CoinShow.on) { withdrawUnasked(); return }
        if (!Prefs.bool(TOLD_KEY, false)) return
        val t = myToken() ?: return
        val coins = coinsNow() ?: return
        // THE ROW TELLS THE SEED'S UNION, NEVER ONE DEVICE'S SHARE: it gathers first, and the gathering puts the row
        if (!CoinVault.fresh) { CoinVault.soon("put", 1.0); return }
        Thread {
            val b = JSONObject().put("token", hex(t)).put("name", Prefs.userName.take(64)).put("coins", coins)
            keyProof(t)?.let { (pk, sig) -> b.put("pk", pk).put("sig", sig) }
            send(b, "put")
        }.start()
    }
    /** The seed's coin key signs ASCII mt-top-key-v1 and the row's token: the node verifies it with the core and reads that key's chain. */
    private fun keyProof(token: ByteArray): Pair<String, String>? {
        val k = CoinTx.keys() ?: return null
        val sig = MtBindings.nativeSign(k.sk, "mt-top-key-v1".toByteArray() + token) ?: return null
        return Base64.encodeToString(k.pk, Base64.NO_WRAP) to Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    /** WHAT THIS BUILD FOUND AT ITS FIRST LAUNCH (iOS noteFirstLaunch): whether an earlier build had already published the row. */
    fun noteFirstLaunch() {
        if (!Prefs.has(TOLD_BEFORE_KEY)) Prefs.setBool(TOLD_BEFORE_KEY, Prefs.bool(TOLD_KEY, false))
    }
    /** A ROW PUT BEFORE THE YES WAS ASKED LEAVES (iOS withdrawUnasked, 5.1.2(i)): a person who never turned the switch has their row
     *  withdrawn from every node once. */
    private fun withdrawUnasked() {
        if (Prefs.has(CoinShow.KEY) || !Prefs.bool(TOLD_BEFORE_KEY, false) || Prefs.bool(UNASKED_KEY, false) || myToken() == null) return
        Prefs.setBool(UNASKED_KEY, true)
        withdraw()
    }
    /** The owner hid the coins, or the words are being forgotten: the row leaves the table on every node, and this screen at once. */
    fun withdraw() {
        val t = myToken() ?: return
        val mine = row(t)
        rows = rows.filter { it.id != mine }
        rank = null
        changed?.let { MainThread.post(it) }
        Thread { send(JSONObject().put("token", hex(t)).put("hide", true), "hide", everyDoor = true) }.start()
    }
    /** The owner's switch moved: the table and the correspondents hear it now, not at the next coin. */
    fun showChanged(on: Boolean) {
        if (on) put() else withdraw()
        // every pair hears it now in the word about myself, past its ten minutes (iOS showChanged + E2E.tellBalance 1696-1700)
        Thread { Book.refs().forEach { MyAbout.tellBalance(it) } }.start()
        Log.i("Montana", "coins_shown " + if (on) "on" else "off")
    }

    /** A withdrawal knocks every node itself (everyDoor): a row hidden on one node and forwarded nowhere would stand on the other. */
    private fun send(body: JSONObject, why: String, everyDoor: Boolean = false) {
        var heard = 0
        for (door in Doors.ordered("top")) {
            val (code, _) = Wire.post(door, "/top-put", body, 8_000)
            if (code == 200) { heard++; if (!everyDoor) break }
        }
        Log.i("Montana", "top_put why=$why doors=$heard")
    }

    /** The whole table and this person's place in it, asked of the first node that keeps it (off the screen's thread). */
    fun read() {
        if (!GooglePlay.COINS || asking) return
        asking = true
        try {
            val body = JSONObject().apply { myRow()?.let { put("row", it) } }
            for (door in Doors.ordered("top")) {
                val (code, text) = Wire.post(door, "/top-get", body, 6_000)
                val o = text?.takeIf { code == 200 }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                val list = o.optJSONArray("rows") ?: continue
                rows = (0 until list.length()).mapNotNull { i ->
                    val x = list.optJSONObject(i) ?: return@mapNotNull null
                    val id = x.optString("row").ifEmpty { return@mapNotNull null }
                    if (!x.has("coins")) return@mapNotNull null
                    Row(id, letters(x.optString("name")), x.optLong("coins"))
                }
                rank = if (o.has("rank")) o.optInt("rank") else null
                if (!answered) Log.i("Montana", "top_get rows=${rows.size} rank=${rank ?: 0}")
                answered = true
                changed?.let { MainThread.post(it) }
                return
            }
        } finally { asking = false }
    }
    /** A NAME IS SHOWN AS LETTERS ONLY (iOS read): the node strips the controls and the direction marks, and the phone does again. */
    private fun letters(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val t = Character.getType(cp)
            if (t != Character.CONTROL.toInt() && t != Character.FORMAT.toInt()) b.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return b.toString()
    }
}
