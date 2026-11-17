package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE SHELF'S POST (iOS MTShelfPost, MontanaSeats.swift 735-885; the seats of one phone, session 4): a person waiting on the
 * shelf still receives. Their pipes and their cards lie in their record on the shelf (Seats: the vault's values `pipeSecrets`,
 * `rdvCards`, `rdvPermanent`, `cardKeys`, `mt.twin.ref`), so the box is read under their labels and signed by their own
 * reference, at most once in twenty seconds, asked by every pickup of the person seated (Post.fetchOnce). Every row opened goes
 * into their inbox on the shelf (Seats.fileShelf) before the node is told it was taken; a first letter to one of their cards
 * is opened with that card's own key and its pipe is kept beside them (Seats.meetOnShelf) until their lift, when the landing
 * door gives it birth in their own book (Seats.landShelf). A person's word is answered «delivered» from here, as iOS answers it.
 *
 * Not yet here (iOS has them, session 4b): their own letters riding from their queue (carry), the pair of two persons of this
 * phone (pairWithShelf), the face on the shelf. The phone has no ring of its own for the shelf (iOS wakePairs rides APNs).
 */
object ShelfPost {
    class Person(
        val id: String, val name: String, val twin: String,
        val pipes: Map<String, ByteArray>,      // a correspondence's reference -> its pipe: their book and the pipes met on the shelf
        val invites: Map<String, ByteArray>,    // an invitation of their cards (base64url) -> the card's body (its first 1184 bytes: the root)
        val cardKeys: Map<String, ByteArray>,   // a card's root (base64url) -> its secret key
    )

    private val lock = Any()
    private var running = false
    private var lastAt = 0L
    private val twins = HashMap<String, String>()   // a person's reference derived once per process when their store keeps none

    /** A pickup of the person seated asks for one: one walk at a time, and not twice in twenty seconds. */
    fun soon() {
        val go = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (running || now - lastAt < 20_000) false else { running = true; lastAt = now; true }
        }
        if (!go) return
        Thread({
            try { for (p in Seats.shelfPeople()) runCatching { serve(p) }.onFailure { Log.w("Montana", "shelf_post " + it.javaClass.simpleName) } }
            finally { synchronized(lock) { running = false } }
        }, "shelf-post").apply { isDaemon = true; start() }
    }

    /** The reference this person signs their subscriptions with: kept by their store, or derived from their words once. */
    fun twin(id: String, kept: String, words: String): String {
        if (kept.isNotEmpty()) return kept
        synchronized(lock) { twins[id] }?.let { return it }
        val master = MtBindings.nativeMnemonicToMasterSeed(words) ?: return ""
        val owner = Wire.ownerSecret(master).also { master.fill(0) } ?: return ""
        val ref = Wire.hex(Wire.sha(owner).copyOf(16)).also { owner.fill(0) }
        synchronized(lock) { twins[id] = ref }
        return ref
    }

    private fun serve(p: Person) {
        if (p.twin.isEmpty() || !Wire.agreesWithCanon()) return
        val me = p.twin + "#" + Archive.deviceTag()   // their name on this device (Twin.deviceRef for the one seated)
        val w0 = Wire.day()
        val road = HashMap<String, Pair<String, String>>()   // label -> ("r", ref) | ("i", invitation)
        val secretOf = HashMap<String, ByteArray>()
        for ((ref, s) in p.pipes) for (w in (w0 - 2)..(w0 + 1)) { val cw = Wire.convW(s, w); road[cw] = "r" to ref; secretOf[cw] = s }
        for (inv64 in p.invites.keys) {
            val inv = MontanaCard.unb64url(inv64)?.takeIf { it.size == 32 } ?: continue
            val s = Wire.rdvLetterSecret(inv)
            for (w in (w0 - 2)..(w0 + 1)) { val cw = Wire.rdvConvW(inv, w); road[cw] = "i" to inv64; secretOf[cw] = s }
        }
        if (road.isEmpty()) return
        val doors = Doors.onePerNode(Doors.ordered("box"))
        if (doors.isEmpty()) return
        val taken = ArrayList<JSONArray>()
        var kept = 0
        for (chunk in road.keys.toList().chunked(128)) {
            val page = JSONObject().put("subs", JSONArray(chunk)).put("sids", JSONArray(chunk.map { Wire.subId(it, p.twin) }))
                .put("devs", JSONArray(chunk.map { Wire.subId(it, me) }))
            val seen = HashSet<String>()
            for (door in doors) {
                val (code, text) = Wire.post(door, "/fetch", page, 6000)
                if (code != 200 || text == null) continue
                val ls = runCatching { JSONObject(text).optJSONArray("letters") }.getOrNull() ?: continue
                for (i in 0 until ls.length()) {
                    val l = ls.optJSONObject(i) ?: continue
                    val m = l.optString("m")
                    if (m.isEmpty() || !seen.add(m)) continue
                    val cw = l.optString("c")
                    val (kind, name) = road[cw] ?: continue
                    val sealed = runCatching { Base64.decode(l.optString("e"), Base64.DEFAULT) }.getOrNull() ?: continue
                    val plain = Wire.openBoxed(sealed, secretOf.getValue(cw), l.optLong("at")) ?: continue
                    val row = JSONObject().put("m", m).put("p", Base64.encodeToString(plain, Base64.NO_WRAP))
                    var answerOn: ByteArray? = null
                    val f: List<String>
                    if (kind == "i") {
                        val met = meet(plain, name, p) ?: continue
                        row.put("i", name).put("s", Base64.encodeToString(met.first, Base64.NO_WRAP)).put("o", Base64.encodeToString(met.second, Base64.NO_WRAP))
                        answerOn = met.first
                        f = Wire.fields(plain, 1088)
                    } else {
                        row.put("r", name)
                        answerOn = p.pipes[name]
                        f = Wire.fields(plain)
                    }
                    if (!Seats.fileShelf(p.id, row)) continue   // a move runs or the person was seated: the row stays on the node
                    kept++
                    taken.add(JSONArray(listOf(m, Wire.subId(cw, me))))
                    // only what a person reads is receipted here: a service word asks no receipt of a person not on the screen
                    val words = f.getOrElse(1) { "" }
                    if (answerOn != null && f[0].isNotEmpty() && words.isNotEmpty() && !words.startsWith("​") && !words.startsWith("⁣"))
                        receipt(answerOn, p, f[0])
                }
            }
        }
        if (taken.isNotEmpty()) {
            val bodies = taken.chunked(128).map { JSONObject().put("taken", JSONArray(it)) }
            for (door in Doors.ordered("box")) Thread { bodies.forEach { Wire.post(door, "/box-del", it, 5000) } }.start()
        }
        Log.i("Montana", "shelf_post seat=" + p.id + " subs=" + road.size + " kept=" + kept)
    }

    /** A first letter to the person's card, opened with the card's own key and proven by its confirmation -- the pipe is kept on the
     *  shelf until the lift, where the landing door gives it birth in the person's own book (iOS MTShelfPost.meet). */
    private fun meet(plain: ByteArray, inv64: String, p: Person): Pair<ByteArray, ByteArray>? {
        if (plain.size <= 1092) return null
        val body = p.invites[inv64]?.takeIf { it.size >= 1184 } ?: return null
        val root = body.copyOf(1184)
        val sk = p.cardKeys[MontanaCard.b64url(root)]
        val secret = sk?.let { MontanaCard.acceptBy(plain.copyOf(1088), root, it, Wire.fields(plain, 1088).getOrElse(4) { "" }) }
        if (secret == null) { Log.i("Montana", "shelf_meet seat=" + p.id + " refused"); return null }
        val ref = Wire.reference(secret)
        if (!Seats.meetOnShelf(p.id, ref, secret)) return null
        Log.i("Montana", "shelf_meet seat=" + p.id + " ref=" + ref.take(10))
        return secret to root
    }

    /** «Delivered» from the person on the shelf, under their own reference, silent (iOS MTShelfPost.answer). */
    private fun receipt(secret: ByteArray, p: Person, mid: String) {
        val rmid = "rcpt-$mid"
        val head = rmid.toByteArray() + byteArrayOf(0) + (Marks.DELIVERED + mid).toByteArray() + byteArrayOf(0) +
            p.name.trim().toByteArray() + byteArrayOf(0, 0)
        if (head.size > Wire.ENVELOPE) return
        val plain = head + ByteArray(Wire.ENVELOPE - head.size)
        val cw = Wire.convW(secret, Wire.day())
        var boxed = false
        for (door in Signal.writeOrder("box")) {
            val env = Wire.seal(Wire.bodyKey(secret, Wire.minute()), plain) ?: return
            val (code, _) = Wire.post(door, "/wake", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, p.twin)).put("mid", rmid)
                .put("env", Base64.encodeToString(env, Base64.NO_WRAP)).put("silent", true))
            if (code == 200) { Signal.doorAnswered(door); boxed = true; break }
            Signal.doorFailed(door, code)
        }
        Log.i("Montana", "shelf_receipt mid=" + mid.take(8) + " seat=" + p.id + " boxed=" + (if (boxed) 1 else 0))
    }
}
