package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * THE CARGO ROAD OF A FILE AND THE KNOCK IN A PIPE OF ITS OWN (iOS MTKeeping.lay / fetch / knock / word, MTKeeping.swift 417-841):
 * a file is sealed in pieces under a fresh key and put on the nodes, its list of pieces sealed as one more piece under a key of
 * its own; the word that names the file carries that piece's name and both keys, never the list (a list outgrows a letter). The
 * word rides the box of a pipe as a letter of the keeping vocabulary («KP:» and JSON). The keeping of a copy with correspondents
 * (the slots, the ring, the parts) has no road on this phone yet: only the road the seed's own pipe needs stands here.
 */
object Keeping {
    const val MARK = "​​KP:"   // iOS keepMark (ContentView.swift:4015): NOT-UI, a word of the keeping vocabulary

    fun word(text: String): JSONObject? =
        if (!text.startsWith(MARK)) null else runCatching { JSONObject(text.removePrefix(MARK)) }.getOrNull()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** A FILE ON THE CARGO ROAD (iOS layPieces 676-686): its pieces under a fresh key, their list sealed as one more piece. */
    fun lay(f: File): JSONObject? {
        val size = f.length()
        if (size <= 0L) return null
        val bk = MtBindings.nativeRandom(32) ?: return null
        val (bk64, chunks) = Media.layFile(f, "", key = bk) {} ?: return null
        val mk = MtBindings.nativeRandom(32) ?: return null
        val sealed = Wire.seal(mk, chunks.toString().toByteArray()) ?: return null
        val mref = Wire.hex(Wire.sha(sealed))
        if (!Wire.putBlob(mref, sealed)) return null
        return JSONObject().put("z", size).put("bk", bk64).put("mref", mref).put("mk", b64(mk))
    }

    /** The file comes back piece by piece (iOS fetch 688-714): the list checked by its name, each piece by its own, the length whole. */
    fun fetch(o: JSONObject, dest: File): Boolean {
        val size = o.optLong("z", 0L)
        val bk = o.optString("bk"); val mref = o.optString("mref")
        val mk = runCatching { Base64.decode(o.optString("mk"), Base64.DEFAULT) }.getOrNull()
        if (size <= 0L || bk.isEmpty() || mref.isEmpty() || mk == null) return false
        val list = Wire.getBlob(mref)?.takeIf { Wire.hex(Wire.sha(it)) == mref } ?: return false
        val chunks = Wire.open(mk, list)?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return false
        dest.delete()
        if (!Media.download(JSONObject().put("bk", bk).put("chunks", chunks), dest)) return false
        return dest.length() == size
    }

    /**
     * ONE LETTER IN A PIPE OF ITS OWN (iOS knock 833-841, MTNodeWire.knockLetter): mid ‖ 0 ‖ «KP:» and the word ‖ 0 ‖ 0 ‖ 0, padded to
     * the envelope, sealed afresh at every door; the first door that boxes it ends the walk. `sender` -- the name the pipe hears it
     * from: the node never gives a letter back to the name that laid it.
     */
    fun knock(secret: ByteArray, o: JSONObject, sender: String, silent: Boolean = true): Boolean = knockText(secret, MARK + o.toString(), sender, silent)
    /** One word of this person's own in a pipe, its text as given (a keeping word above; the seed's read word, Twin.TQ): the one
     *  place here that seals such a letter's head (iOS MTKeeping.knockText, 6166b225). */
    fun knockText(secret: ByteArray, text: String, sender: String, silent: Boolean = true): Boolean {
        val mid = UUID.randomUUID().toString().uppercase()
        val body = mid.toByteArray() + byteArrayOf(0) + text.toByteArray() + byteArrayOf(0, 0, 0)
        if (body.size > Wire.ENVELOPE) return false
        val plain = body + ByteArray(Wire.ENVELOPE - body.size)
        val cw = Wire.convW(secret, Wire.day())
        for (door in Signal.writeOrder("box")) {
            val env = Wire.seal(Wire.bodyKey(secret, Wire.minute()), plain) ?: return false
            val (code, _) = Wire.post(door, "/wake", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, sender)).put("mid", mid)
                .put("env", b64(env)).apply { if (silent) put("silent", true) })
            if (code == 200) { Signal.doorAnswered(door); return true }
            Signal.doorFailed(door, code)
        }
        return false
    }
}

/**
 * THE SEED'S OWN PIPE (iOS MontanaArchive twinSecret … takeMirror, MontanaPQ.swift 879-1153; 349cc053, f376b4ef; the author's words
 * 18.10.2026 10:2x-10:4x MSK: «on T1 the letters of 10:07 are not seen, on the Mac with the same person they are»; «what I posted
 * from the phone is not seen -- it does not sync as the chats do»; «read on the phone -- read on every device»). The node's box gives
 * a letter to whichever device of the seed takes it first, and that device's confirmation erases it for the rest; so every word a
 * device takes from a correspondent, sends, or reads rides to the seed's other devices in the pipe only they derive, on the cargo
 * road, under the name it was born with («lt»), and the history's blocks ride there too («hist»). There each word walks the one
 * landing road (Post.landMirrored): a name standing is a repeat, a buried name stays buried, a mirrored word rides no further.
 */
object Twin {
    const val PIPE = "twin:history"                 // iOS twinPipe: the box name of the pipe of this seed's devices
    const val READ = "​​TR:"              // iOS twinReadMark: a read mark for this seed's devices alone
    const val ASK = "​​TA:"               // iOS twinAskMark: the asker's keys
    const val CARD = "​​TC:"              // iOS twinCardMark: a card the other device gave birth to
    const val CARD_PIPE = "twin:card"              // iOS twinCardPipe: the mirror's name for the cards' words, never a conversation
    const val ROW = "​​TW:"               // iOS twinRowMark: one row given back
    /** iOS MTTwinRead.mark (6166b225): a chat read on one device of the seed, said at the reading, alone, in the seed's own pipe. */
    const val TQ = "\u200B\u200BTQ:"
    const val TWINS = "mt.twin.devices"     // iOS twinDevicesKey: each other device of the seed heard -- {writer: [last heard s, quiet]}
    private const val TWIN_HEARD_FOR = 30 * 86_400.0  // iOS twinHeardFor: a month
    private const val READ_DRIFT_MS = 10_000L        // iOS MTTwinRead.drift: two devices' clocks, forgiven
    // THE PIPE IS THE APP'S, NOT THE PACKAGE'S (iOS twinSecret mixes Bundle.main.bundleIdentifier, quest.montana.app for Montana): two
    // apps of one seed keep two pipes; the iPhone's Montana and this one are one app, whatever this build's package is called
    private const val APP = "quest.montana.app"
    private const val MIRROR = "mt.twin.mirror"     // the queue (iOS twin-mirror.json), sealed in the vault: it holds letters
    private const val SEEN = "mt.history.twinSeen"  // iOS twinSeenKey: the letters of the other devices already taken
    private const val ASKED = "mt.twin.asked"       // iOS askedKey
    private const val KEPT = 4000                   // iOS mirrorKept
    private const val ROUND = 500                   // iOS mirrorRound
    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "twin").apply { isDaemon = true } }
    private val lock = Any()

    fun forget() = synchronized(lock) { listOf(MIRROR, SEEN, ASKED, MBOOK, TwinSelf.HELD, TwinSelf.BOOK).forEach { DeviceVault.delete(it) }; synchronized(bookLock) { book = null }; given.clear(); laneHeld = false; Archive.forgetHeld() }

    /**
     * THE SEED'S OWN LANE STANDS WHILE THE APP RUNS (iOS f339b7a3, 2403, holdTwinLane, the author's words 22320, 22581, 22631: heal the
     * root, not the sores): the node tells a held lane «box» the millisecond a letter is boxed, and the app fetches at once -- but the
     * pipe of the seed's devices stood in no held lane, so another device learned what this one sent only at its next round. One long
     * question every twelve seconds, the price of one open chat, from the first pickup on.
     */
    @Volatile private var laneHeld = false
    fun holdLane() {
        if (laneHeld || MontanaSeed.twin == null || secret() == null) return
        laneHeld = true
        Signal.hold(PIPE, "twin")
        Log.d("Montana", "twin_lane held")
        mirrorResume()
    }

    fun secret(): ByteArray? = Archive.historyKey()?.let { hk ->
        Wire.sha("mt-twin-box".toByteArray(), byteArrayOf(0), APP.toByteArray(), byteArrayOf(0), hk)
    }
    /** This device's name in the pipe (iOS twinDeviceRef): the node rings every device of the pipe but the one that spoke. */
    fun deviceRef(): String? = MontanaSeed.twin?.let { it + "#" + Archive.deviceTag() }
    /** The one place a label of the seed's own pipe is asked: under this device's name, never the person's (iOS twinSid). */
    fun sid(cw: String): String? = deviceRef()?.let { Wire.subId(cw, it) }

    private fun vault(key: String): JSONArray = DeviceVault.get(key)?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONArray()
    private fun strings(key: String): MutableList<String> = vault(key).let { a -> MutableList(a.length()) { a.optString(it) } }
    private fun keepStrings(key: String, l: List<String>) = DeviceVault.set(key, JSONArray(l).toString().toByteArray())
    private fun scratch(): File = File(Book.ctx.cacheDir, UUID.randomUUID().toString() + ".twin")

    // ── the history's blocks (iOS twinSoon / layForTwins / takeFromTwin) ──
    @Volatile private var histDue = false
    /** A block was written: the other devices get it within half a minute, in one letter with whatever came beside it. */
    fun histSoon() {
        synchronized(lock) { if (histDue) return; histDue = true }
        ex.schedule({ synchronized(lock) { histDue = false }; runCatching { layHist() }.onFailure { Log.w("Montana", "twin_hist " + it.javaClass.simpleName) } }, 30, TimeUnit.SECONDS)
    }
    private fun layHist(want: Map<String, Long>? = null) {
        val g = Archive.twinBlocks(want) ?: return
        if (g.n == 0) return
        val secret = secret() ?: return
        val me = deviceRef() ?: return
        val f = scratch()
        try {
            f.writeBytes(g.blob)
            val road = Keeping.lay(f) ?: return Unit.also { Log.d("Montana", "twin_hist the blocks did not reach the nodes n=" + g.n) }
            road.put("w", "hist").put("n", g.n).put("f", Archive.writerHex() ?: "")
            LinkedDevices.stamp(road)   // its kind, and the phone's term for its joined devices (iOS MTLinkedDevices.stamp, OZ 1 p.4)
            if (!Keeping.knock(secret, road, me)) { Log.d("Montana", "twin_hist no door boxed the letter n=" + g.n); return }
            Archive.twinBoxed(g.tops)
            Log.d("Montana", "twin_hist laid blocks=" + g.n + " bytes=" + g.blob.size + " more=" + (if (g.more) 1 else 0) + " asked=" + (if (want == null) 0 else 1))
            // a named cursor is walked to its end, round by round (iOS layForTwins(from:))
            if (g.more && want != null) { f.delete(); return layHist(want + g.tops) }
            if (g.more) histSoon()
        } finally { f.delete() }
    }

    // ── THE CURSOR OF HISTORY STANDS WITH THE RECEIVER (iOS f339b7a3, 2403, wantHistory / giveHistory, OZ 1 p.2) ──
    @Volatile private var wantedAt = 0L
    /** The word that names this device's cursor to the seed's other devices: at a pickup, at most once in ten minutes. A word too long
     *  for a letter rides the cargo road. */
    fun wantHistory(soon: Boolean = false) {
        if (MontanaSeed.twin == null) return
        val now = System.currentTimeMillis()
        val floor = if (soon || mirrorHole) 20_000L else 600_000L   // a hole of the mirror is named within twenty seconds (iOS 2417)
        synchronized(lock) { if (now - wantedAt <= floor) return; wantedAt = now }
        ex.execute {
            runCatching {
                val secret = secret() ?: return@runCatching
                val me = deviceRef() ?: return@runCatching
                val mine = Archive.writerHex() ?: return@runCatching
                val held = Archive.held()
                val body = held.toString()
                val o = JSONObject().put("w", "hw").put("f", mine)
                LinkedDevices.stamp(o)   // the cursor word is the device's sign of life on every rise: its kind, and the phone's term (OZ 1 p.4)
                val lc = mirrorCursors()
                if (lc.length() > 0) o.put("lc", lc)   // the mirror's cursor rides the same word (iOS 2417, OZ 1 p.2)
                // iOS says 2000 bytes; here the word must fit one envelope with its name and mark (Keeping.knockText), so the word
                // itself is measured, and a longer one rides the cargo road
                val inline = JSONObject(o.toString()).put("c", body)
                if ((Keeping.MARK + inline).toByteArray().size + 48 <= Wire.ENVELOPE) o.put("c", body) else {
                    val f = scratch()
                    try {
                        f.writeText(body)
                        val road = Keeping.lay(f) ?: return@runCatching Unit.also { Log.d("Montana", "twin_want the cursor did not reach the nodes") }
                        for (k in road.keys()) o.put(k, road.get(k))
                    } finally { f.delete() }
                }
                val boxed = Keeping.knock(secret, o, me)
                Log.d("Montana", "twin_want folders=" + held.length() + " bytes=" + body.length + " boxed=" + (if (boxed) 1 else 0))
            }.onFailure { Log.w("Montana", "twin_want " + it.javaClass.simpleName) }
        }
    }
    /** Another device of the seed named its cursor: this device's own blocks are laid from there, every folder this device holds. */
    private fun giveHistory(o: JSONObject) {
        val mine = Archive.writerHex() ?: return
        val f0 = o.optString("f")
        if (f0.isEmpty() || f0 == mine) return   // this device's own word, heard back from the box
        heardTwin(o)   // a device that only reads is heard too: the phone lists it (iOS giveHistory, OZ 1 p.4)
        if (LinkedDevices.isEnded(f0)) return   // an ended device is told again (heardTwin), never given history
        giveMirror(o, f0)   // the mirror's letters it lacks, from this device's book (iOS 2417, OZ 1 p.1-2)
        var body = o.optString("c").ifEmpty { null }
        if (body == null && o.has("mref")) {
            val f = scratch()
            try { if (Keeping.fetch(o, f)) body = f.readText() } finally { f.delete() }
        }
        val held = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Unit.also { Log.d("Montana", "twin_want the cursor did not come whole") }
        val want = Archive.folders().associateWith { c -> held.optJSONObject(c)?.optLong(mine, 0L) ?: 0L }
        layHist(want)
    }
    private fun takeHist(o: JSONObject, mid: String) {
        if (o.optString("f") == Archive.writerHex()) return   // this device's own letter, heard back from the box
        heardTwin(o)
        if (mid in strings(SEEN)) return
        val f = scratch()
        try {
            if (!Keeping.fetch(o, f)) { Log.d("Montana", "twin_hist the blocks did not come whole"); return }
            val b = f.readBytes()
            var i = 0; var filed = 0
            var whole = true
            var runs: Map<String, Long>? = null
            while (i + 4 <= b.size) {
                val len = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
                i += 4
                if (len <= 0 || b.size < i + len) break
                val sealed = b.copyOfRange(i, i + len)
                i += len
                // THE RUNS OF A LETTER (iOS 2403): the first record says where each folder's run starts; the cursor moves over a run
                // only while every block before filed (a block that did not file breaks every run after it)
                if (runs == null) { val r = Archive.runsOf(sealed); if (r != null) { runs = r; continue } }
                val (taken, folder) = Archive.fileBlock(sealed)
                if (taken) filed++ else whole = false
                val from = folder?.let { runs?.get(it) }
                if (whole && folder != null && from != null) Archive.noteHeld(folder, sealed, from)
            }
            seen(mid)
            Log.d("Montana", "twin_hist taken blocks=" + filed)
        } finally { f.delete() }
    }
    private fun seen(mid: String) = synchronized(lock) { keepStrings(SEEN, (strings(SEEN) + mid).takeLast(512)) }

    // ── the pickup (iOS twinTakes) ──
    /** A row of the box in the pipe of this seed's devices is this file's own, never a letter of the feed. */
    fun takes(text: String, mid: String) {
        // A CHAT READ ON ANOTHER DEVICE OF THE SEED (iOS twinTakes → MTTwinRead.takeOff, 6166b225, OZ 1 p.14.7): its banners
        // delivered no later than the reading leave this screen at once; nothing else moves -- the mirror's read mark reads the rows
        readWord(text)?.let { (chat, atMs) ->
            val n = Notify.takeOff(chat, atMs + READ_DRIFT_MS)
            Log.d("Montana", "twin_read took notices off n=$n")
            return
        }
        val o = Keeping.word(text) ?: return
        if (mid.isEmpty()) return
        when (o.optString("w")) {
            "hist" -> ex.execute { runCatching { takeHist(o, mid) }.onFailure { Log.w("Montana", "twin_hist " + it.javaClass.simpleName) } }
            "lt" -> ex.execute { runCatching { takeMirror(o, mid) }.onFailure { Log.w("Montana", "twin_mirror " + it.javaClass.simpleName) } }
            // another device names its cursor of history (iOS 2403 giveHistory; an older build takes it unread)
            "hw" -> ex.execute { runCatching { giveHistory(o) }.onFailure { Log.w("Montana", "twin_want " + it.javaClass.simpleName) } }
        }
    }

    // ── the mirror (iOS TwinWord, mirrorNote, layMirror, takeMirror) ──
    /**
     * THE MIRROR'S CHOICE OF WORDS (iOS MontanaDeliveryEngine.mirrors): what stands in a conversation or moves a row -- a letter of
     * every kind, a reaction's row, an edit, a deletion, a receipt, a group's word -- rides; what says only «here, now» (typing,
     * presence, a call's signal, a draft, a name, a face) does not: it would be stale where it landed.
     */
    fun mirrors(text: String): Boolean {
        if (text.startsWith(Groups.MARK)) return runCatching { JSONObject(text.removePrefix(Groups.MARK)).optString("t") != "ty" }.getOrDefault(false)
        if (text.startsWith(Marks.DELIVERED) || text.startsWith(Marks.READ) || text.startsWith(Marks.DELETE)) return true
        if (!Marks.isService(text) || text.startsWith(Marks.MEDIA) || text.startsWith(Marks.VOICE) || text.startsWith(Marks.STICKER)) return true
        return changesRow(text)
    }
    /** iOS MTRowLetter.changesRow: a row's own words, never the machinery. */
    private fun changesRow(text: String): Boolean {
        if (text.isEmpty() || ChessLetter.isStep(text)) return false
        if (text.startsWith(Marks.STICKER)) return true
        if (text.startsWith("​​")) return text.drop(2).take(3) in setOf("VC:", "MD:", "MC:", "CL:", "GE:")
        if (text.startsWith(Marks.LONG)) return true
        return !(text.startsWith("⁣") || text.startsWith("⁤") || text.startsWith("​"))
    }

    private val given = HashMap<String, Long>()   // a name given within the hour: a later copy of it by another road stays
    // THE MIRROR HAS A QUEUE OF ITS OWN (iOS 6517242a, 2412, OZ 1 p.6, the author's word 23053 p.1): on a shared queue a word waited
    // behind the history's lay and the half-minute round -- a photo in Saved Messages reached the Mac 74 s after its birth. One's own
    // letter is laid within a second; others' words and read marks within half a minute; a lay on its way never runs twice.
    private val mx = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "twin-mirror").apply { isDaemon = true } }
    private var mirrorDue = 0L          // the moment the called lay runs, while one is called (0 -- none); under lock
    private var mirrorLaying = false    // under lock

    /**
     * A WORD FOR THE SEED'S OTHER DEVICES (iOS mirrorNote): kept in the vault until it rode, laid within half a minute with whatever
     * came beside it. A repeat within the hour is not queued twice; a newer read mark of a conversation stands for the older.
     * `at` -- seconds, as iOS writes it.
     */
    fun note(c: String, m: String, x: String, own: Boolean = false, at: Double? = null, qt: String? = null, qm: String? = null, lp: String? = null,
             fl: String? = null) {
        if (MontanaSeed.twin == null) return
        synchronized(lock) {
            val reads = x.startsWith(READ)
            val now = System.currentTimeMillis()
            val name = c + "/" + m + "/" + x.take(24)
            if (!reads && now - (given[name] ?: 0L) <= 3_600_000L) return
            given[name] = now
            if (2000 < given.size) given.entries.removeAll { now - it.value >= 3_600_000L }
            val a = vault(MIRROR)
            val keep = JSONArray()
            var top = 0L
            for (i in 0 until a.length()) a.getJSONObject(i).let { e ->
                top = maxOf(top, e.optLong("q"))
                if (!(reads && e.optString("c") == c && e.optString("x").startsWith(READ))) keep.put(e)
            }
            val w = JSONObject().put("c", c).put("m", m).put("x", x).put("q", top + 1)
            if (own) w.put("o", 1)
            at?.takeIf { it > 0 }?.let { w.put("at", it) }
            qt?.takeIf { it.isNotEmpty() }?.let { w.put("qt", it) }
            qm?.takeIf { it.isNotEmpty() }?.let { w.put("qm", it) }
            lp?.takeIf { it.isNotEmpty() }?.let { w.put("lp", it) }
            fl?.takeIf { it.isNotEmpty() }?.let { w.put("fl", it) }   // a row's file still to lay: the mirror lays it before the word rides
            keep.put(w)
            val out = if (keep.length() <= KEPT) keep else JSONArray().also { t -> for (i in keep.length() - KEPT until keep.length()) t.put(keep.get(i)) }
            DeviceVault.set(MIRROR, out.toString().toByteArray())
        }
        mirrorSoon(if (own && !x.startsWith(READ)) 1 else 30)
    }
    /** The next lay, in `wait` seconds: a sooner call stands for a later one, and a lay on its way is never run twice at once. */
    private fun mirrorSoon(wait: Long = 30) {
        val at = System.currentTimeMillis() + wait * 1000
        synchronized(lock) { if (mirrorDue in 1..at) return; mirrorDue = at }
        mx.schedule({
            val go = synchronized(lock) {
                if (mirrorDue != at) return@synchronized false   // a sooner call took its place
                mirrorDue = 0L
                if (mirrorLaying) null else { mirrorLaying = true; true }
            }
            if (go == null) { mirrorSoon(1); return@schedule }
            if (go == false) return@schedule
            var again: Long? = null
            try { again = layMirror() } catch (e: Exception) { Log.w("Montana", "twin_mirror " + e.javaClass.simpleName) }
            finally { synchronized(lock) { mirrorLaying = false } }
            again?.let { mirrorSoon(it) }
            Unit
        }, wait, TimeUnit.SECONDS)
    }
    /** The words a run left on disk ride once the keys stand (iOS mirrorResume, 2415): before, they waited for a new word. */
    private fun mirrorResume() { if (synchronized(lock) { vault(MIRROR).length() } > 0) mirrorSoon(1) }

    private var mirrorFiling = false   // a row's file is on its way to the nodes (layFile); under lock
    /** A ROW'S FILE IS LAID BESIDE THE ROUND, NEVER IN FRONT OF IT (iOS fileSoon, 2415, OZ 1 p.9): one file at a time, while the words ready ride. */
    private fun fileSoon(w: JSONObject) {
        synchronized(lock) { if (mirrorFiling) return; mirrorFiling = true }
        Thread({
            val laid = try { layFile(w) } catch (e: Exception) { Log.w("Montana", "twin_file " + e.javaClass.simpleName); false }
            synchronized(lock) { mirrorFiling = false }
            mirrorSoon(if (laid) 1 else 30)
        }, "twin-file").apply { isDaemon = true }.start()
    }
    /**
     * The file of a row given to the twin, laid on the cargo road and its road written into the word on disk (iOS layFile, 2415): a
     * run that ends on the way lays it again at the next launch, and a road laid is never laid twice. A file no longer here, or
     * refused eight times, lets the word ride as words with its file's name, said.
     */
    private fun layFile(w: JSONObject): Boolean {
        val path = w.optString("fl").ifEmpty { return true }
        val q = w.optLong("q", -1L)
        val f = File(path)
        val here = f.isFile
        val t0 = System.currentTimeMillis()
        val x = (if (here) Keeping.lay(f) else null)?.let { road -> rowWithRoad(w.optString("x"), road) }
        val ms = System.currentTimeMillis() - t0
        val tries = synchronized(lock) {
            val a = vault(MIRROR)
            var n = 0
            for (i in 0 until a.length()) a.getJSONObject(i).takeIf { it.optLong("q", -2L) == q }?.let { e ->
                n = e.optInt("ft", 0) + 1
                if (x != null) { e.put("x", x); e.remove("fl"); e.remove("ft") }
                else if (!here || n >= 8) e.remove("fl")
                else e.put("ft", n)   // a file the nodes refused is laid again at the next round
            }
            DeviceVault.set(MIRROR, a.toString().toByteArray())
            n
        }
        Log.d("Montana", "twin_file m=" + w.optString("m").take(8) + " " + when {
            x != null -> "laid ms=$ms"
            !here -> "no file here -- the row rides without it"
            else -> "a file did not reach the nodes try=$tries"
        })
        return x != null || !here
    }
    /** A row's words with the road of its file: the one place a road is written into a row given to the twin. */
    private fun rowWithRoad(x: String, road: JSONObject): String? {
        if (!x.startsWith(ROW)) return null
        val o = runCatching { JSONObject(x.removePrefix(ROW)) }.getOrNull() ?: return null
        return ROW + o.put("road", road).toString()
    }

    /** One lay of the queue; the wait of the next one, or null when none is owed. */
    private fun layMirror(): Long? {
        val batch0 = synchronized(lock) { vault(MIRROR).let { a -> JSONArray().also { b -> for (i in 0 until minOf(ROUND, a.length())) b.put(a.get(i)) } } }
        if (batch0.length() == 0) return null
        // a word whose file is still on its way stays in the queue; its file goes beside the round (iOS 2415)
        val batch = JSONArray()
        for (i in 0 until batch0.length()) batch0.getJSONObject(i).let { if (it.has("fl")) fileSoon(it) else batch.put(it) }
        if (batch.length() == 0) return null
        val secret = secret() ?: return null
        val me = deviceRef() ?: return null
        val f = scratch()
        try {
            f.writeBytes(batch.toString().toByteArray())
            val road = Keeping.lay(f) ?: return 30L.also { Log.d("Montana", "twin_mirror the words did not reach the nodes n=" + batch.length()) }
            road.put("w", "lt").put("n", batch.length()).put("f", Archive.writerHex() ?: "")
            LinkedDevices.stamp(road)   // its kind, and the phone's term for its joined devices (OZ 1 p.4)
            numberLetter(road)   // the road is in the book before the box (iOS 2417)
            if (!Keeping.knock(secret, road, me)) { Log.d("Montana", "twin_mirror no door boxed the letter n=" + batch.length()); return 30L }
            val sent = HashSet<Long>()
            for (i in 0 until batch.length()) sent.add(batch.getJSONObject(i).optLong("q", -1L))
            val left = synchronized(lock) {
                val a = vault(MIRROR)
                val keep = JSONArray()
                for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optLong("q", -1L) !in sent) keep.put(it) }
                DeviceVault.set(MIRROR, keep.toString().toByteArray())
                keep.length()
            }
            Log.d("Montana", "twin_mirror laid words=" + batch.length() + " left=" + left + " sn=" + road.optInt("sn"))
            return if (left > 0) 1L else null
        } finally { f.delete() }
    }
    /** The words of the seed's other device: each lands here by the one landing road, in the order that device heard them. A numbered
     *  letter this device took is not fetched again, and a hole it shows is asked at once (the mirror's book, below; iOS 2417). */
    private fun takeMirror(o: JSONObject, mid: String) {
        if (o.optString("f") == Archive.writerHex()) return
        heardTwin(o)
        val run = runOf(o)
        val sn = if (o.has("sn")) o.optInt("sn") else null
        val lo = if (o.has("lo")) o.optInt("lo") else null
        if (run != null) {
            if (!o.has("mref")) { noteHole(takeLetter(run, null, lo)); return }   // the writer's oldest, named: nothing to fetch
            if (sn != null && tookLetter(run, sn)) { Log.d("Montana", "twin_mirror a letter taken before -- not fetched again sn=$sn"); return }
        }
        if (mid in strings(SEEN)) return
        val f = scratch()
        val words = try {
            if (!Keeping.fetch(o, f)) { Log.d("Montana", "twin_mirror the words did not come whole"); return }
            runCatching { JSONArray(f.readText()) }.getOrNull() ?: return
        } finally { f.delete() }
        seen(mid)
        for (i in 0 until words.length()) runCatching { land(words.getJSONObject(i)) }.onFailure { Log.w("Montana", "twin_land " + it.javaClass.simpleName) }
        Log.d("Montana", "twin_mirror taken words=" + words.length() + " sn=" + (sn ?: 0))
        if (run != null) noteHole(takeLetter(run, sn, lo))
    }

    // ── THE MIRROR'S BOOK: A NUMBER FOR EVERY LETTER, A CURSOR ON EVERY DEVICE (iOS d7672fe4, 2417, OZ 1 p.1-2, the author's words
    // 23282 and 23284: «build the sync properly, not "someone took it or not"») ──
    // A mirror letter was a box letter and nothing more: its words left this device's queue the moment a node boxed it (layMirror),
    // a box keeps a pipe's last 64 letters for a day, and the receiver knew the last 512 letters' names alone -- a device away
    // longer, or behind more, lost those words for good. Now each mirror letter of a device takes the next number of that device's
    // run, and the device keeps the letter's road in its book until every other device of the seed named a cursor past it (six days
    // at most: the pieces live a week on the nodes). Each device keeps, per writer and run, the number up to which it took every
    // letter and the numbers it took above it; a repeat is never fetched again, a hole is named at once in the cursor word (hw,
    // wantHistory), and the writer lays the letters after that number again by the same roads. A number the writer no longer holds
    // is named by its oldest («lo»). A book lost starts a new run, so no cursor skips its letters as old.
    // The book: {run, next, laid: [{sn, at, road}], took: {writer/run: {c, above: [..], at}}, peers: {device: cursor on this run}}.
    private const val MBOOK = "mt.twin.mirrorBook"   // iOS twin-mirror-book.json, sealed in the vault: it holds the letters' roads
    private const val BOOK_KEPT = 1000
    private const val BOOK_DAYS = 6 * 86_400.0
    private val bookLock = Any()
    private var book: JSONObject? = null   // under bookLock
    @Volatile private var mirrorHole = false   // a hole stands, and the cursor word goes within twenty seconds

    /** Under bookLock. */
    private fun readBook(): JSONObject {
        book?.let { return it }
        val b = DeviceVault.get(MBOOK)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
        if (b.optString("run").isEmpty()) b.put("run", Wire.hex(ByteArray(4).also { SecureRandom().nextBytes(it) }))
        if (b.optInt("next", 0) < 1) b.put("next", 1)
        if (b.optJSONArray("laid") == null) b.put("laid", JSONArray())
        if (b.optJSONObject("took") == null) b.put("took", JSONObject())
        if (b.optJSONObject("peers") == null) b.put("peers", JSONObject())
        book = b
        return b
    }
    /** Under bookLock. */
    private fun keepBook(b: JSONObject) { book = b; DeviceVault.set(MBOOK, b.toString().toByteArray()) }
    /** Under bookLock: a letter every other device heard within the month took, or older than its pieces, leaves the book. */
    private fun trimBook(b: JSONObject, now: Double) {
        val devices = twinsHeard(now).keys().asSequence().toList()
        val peers = b.getJSONObject("peers")
        val low = if (devices.isEmpty()) 0 else devices.minOf { peers.optInt(it, 0) }
        val laid = b.getJSONArray("laid")
        val keep = ArrayList<JSONObject>()
        for (i in 0 until laid.length()) laid.optJSONObject(i)?.let { e -> if (e.optInt("sn") > low && now - e.optDouble("at", 0.0) <= BOOK_DAYS) keep.add(e) }
        b.put("laid", JSONArray(keep.takeLast(BOOK_KEPT)))
        val took = b.getJSONObject("took")
        for (k in took.keys().asSequence().toList()) if (now - (took.optJSONObject(k)?.optDouble("at", 0.0) ?: 0.0) >= TWIN_HEARD_FOR) took.remove(k)
    }
    /** The next number of this device's run is written into the letter, and the letter's road into the book. */
    private fun numberLetter(road: JSONObject) = synchronized(bookLock) {
        val b = readBook()
        val now = System.currentTimeMillis() / 1000.0
        val n = b.optInt("next", 1)
        road.put("sn", n).put("se", b.optString("run"))
        b.getJSONArray("laid").put(JSONObject().put("sn", n).put("at", now).put("road", road.toString()))
        b.put("next", n + 1)
        trimBook(b, now)
        keepBook(b)
    }
    /** The writer's run, when the writer's build numbers its letters. */
    private fun runOf(o: JSONObject): String? {
        val f = o.optString("f"); val se = o.optString("se")
        return if (f.isEmpty() || se.isEmpty()) null else "$f/$se"
    }
    /** This device took that letter already. */
    private fun tookLetter(run: String, sn: Int): Boolean = synchronized(bookLock) {
        val t = readBook().getJSONObject("took").optJSONObject(run) ?: return@synchronized false
        if (sn <= t.optInt("c")) return@synchronized true
        val a = t.optJSONArray("above") ?: return@synchronized false
        (0 until a.length()).any { a.optInt(it) == sn }
    }
    /** A letter taken (sn), or the writer's oldest held number (lo) closing what it can no longer give. The first letter of a run
     *  starts its cursor there: what came before rides the history and the phone's copy. True while a hole of the last hour stands. */
    private fun takeLetter(run: String, sn: Int?, lo: Int?): Boolean = synchronized(bookLock) {
        val b = readBook()
        val now = System.currentTimeMillis() / 1000.0
        val took = b.getJSONObject("took")
        val had = took.optJSONObject(run)
        val start = had?.optInt("c") ?: sn?.let { it - 1 }
        if (start != null) {
            var c = start
            val above = TreeSet<Int>()
            had?.optJSONArray("above")?.let { a -> for (i in 0 until a.length()) above.add(a.optInt(i)) }
            if (lo != null && c + 1 < lo) {
                Log.d("Montana", "twin_mirror the writer holds no more " + (c + 1) + ".." + (lo - 1) + " run=" + run)
                c = lo - 1
            }
            if (sn != null && c < sn) above.add(sn)
            while (above.isNotEmpty() && above.first() <= c + 1) c = maxOf(c, above.pollFirst()!!)
            took.put(run, JSONObject().put("c", c).put("above", JSONArray(above.toList())).put("at", now))
            keepBook(b)
        }
        took.keys().asSequence().any { k -> took.optJSONObject(k)?.let { t -> (t.optJSONArray("above")?.length() ?: 0) > 0 && now - t.optDouble("at", 0.0) < 3600 } == true }
    }
    /** A hole stands: the cursor word names it within twenty seconds, and every pickup names it again until it closes. */
    private fun noteHole(hole: Boolean) {
        mirrorHole = hole
        if (hole) wantHistory(soon = true)
    }
    /** The cursor word's part of the mirror: per writer and run, the number up to which every letter was taken. */
    private fun mirrorCursors(): JSONObject = synchronized(bookLock) {
        val took = readBook().getJSONObject("took")
        JSONObject().also { r -> for (k in took.keys()) took.optJSONObject(k)?.let { r.put(k, it.optInt("c")) } }
    }
    /** Another device named its cursor on this device's run: the letters after it are laid again from the book, by their roads, 64 at
     *  most a word; a cursor below the book's oldest is told that oldest. */
    private fun giveMirror(o: JSONObject, f: String) {
        val lc = o.optJSONObject("lc") ?: return
        val secret = secret() ?: return
        val me = deviceRef() ?: return
        val mine = Archive.writerHex() ?: return
        val again: List<JSONObject> = synchronized(bookLock) {
            val b = readBook()
            val key = mine + "/" + b.optString("run")
            if (!lc.has(key)) return@synchronized emptyList()
            val c = lc.optInt(key)
            b.getJSONObject("peers").put(f, c)
            trimBook(b, System.currentTimeMillis() / 1000.0)
            keepBook(b)
            val laid = b.getJSONArray("laid")
            val lo = if (laid.length() > 0) laid.getJSONObject(0).optInt("sn") else b.optInt("next", 1)
            val rows = ArrayList<JSONObject>()
            for (i in 0 until laid.length()) {
                if (rows.size >= 64) break
                val e = laid.optJSONObject(i) ?: continue
                if (e.optInt("sn") <= c) continue
                runCatching { JSONObject(e.optString("road")) }.getOrNull()?.let { rows.add(it.put("lo", lo)) }
            }
            if (rows.isEmpty() && c + 1 < lo) listOf(JSONObject().put("w", "lt").put("f", mine).put("se", b.optString("run")).put("lo", lo))   // nothing to lay: the oldest, named
            else rows
        }
        if (again.isEmpty()) return
        var boxed = 0
        for (r in again) if (Keeping.knock(secret, r, me)) boxed++
        Log.d("Montana", "twin_mirror laid again letters=" + again.size + " boxed=" + boxed + " to=" + f.take(8))
    }

    /**
     * A WORD THE SEED'S OTHER DEVICE MIRRORED HERE (iOS MontanaDeliveryEngine.landMirrored): a read mark reads the conversation here
     * too and takes its banners down; the twin's past is asked and given back by name; every other word walks the one landing road
     * under the name it was born with.
     */
    private fun land(w: JSONObject) {
        val c = w.optString("c"); val m = w.optString("m"); val x = w.optString("x")
        if (c.isEmpty() || m.isEmpty() || x.isEmpty()) return
        when {
            x.startsWith(READ) -> readByTwin(c, x.removePrefix(READ).toLongOrNull())
            // one's own name, face or words, or the person's choices, the seed's other device changed (iOS landMirrored, 56177404)
            x.startsWith(TwinSelf.MARK) -> TwinSelf.take(x.removePrefix(TwinSelf.MARK))
            x.startsWith(ASK) -> giveRows(c, x.removePrefix(ASK))
            // a card the other device gave birth to: this device opens the introductions it brings (iOS MontanaCard.takeTwin)
            x.startsWith(CARD) -> MontanaCard.takeTwin(x.removePrefix(CARD))
            x.startsWith(ROW) -> takeRow(c, m, w.optInt("o") == 1, w.optDouble("at", 0.0), x.removePrefix(ROW))
            else -> Post.landMirrored(c, m, x, w.optInt("o") == 1, w.optString("qt").ifEmpty { null }, w.optString("qm").ifEmpty { null },
                w.optString("lp").ifEmpty { null })
        }
    }

    /**
     * THE SEED'S OTHER DEVICE READ THIS CONVERSATION (iOS ChatStore.readByTwin): its letters up to the same one are read here too, the
     * count follows what is left, and the banners of the conversation leave -- nothing is said to the correspondent: the device that
     * read already said it, if the person tells it at all.
     */
    private fun readByTwin(ref: String, upTo: Long?) {
        if (upTo == null || upTo <= 0L) return
        val chat = Book.chat(ref) ?: return
        val left = chat.msgs.count { !it.mine && (Marks.birthMs(it.mid) ?: it.at) > upTo }
        if (left < chat.unread) Book.edit(ref) { it.unread = minOf(it.unread, left) }
        if (left == 0) MainThread.post { Notify.clear(ref) }
        Log.d("Montana", "twin_read chat=" + ref.take(10) + " left=" + left)
    }
    /** The read mark for the seed's other devices (iOS markRead's mirrorNote): what was unread here is read there up to this letter. */
    fun read(ref: String, newestMs: Long) = note(ref, UUID.randomUUID().toString().uppercase(), READ + newestMs, own = true)

    // ── a chat read here leaves the other devices' screens at once (iOS MontanaArchive.tellTwinsRead, MTTwinRead, 6166b225) ──
    /**
     * THE READING ITSELF TELLS THE SEED'S OTHER DEVICES (OZ 1 p.14.7; the author's word 20712): the mirror's read mark rides a file
     * and a silent push half a minute later and wakes nothing that sleeps; this word rides at the reading, alone, in the same pipe.
     * It is loud -- it wakes an iPhone's extension -- only when every other device heard within a month said its extension takes it
     * quietly (rq): an older extension would show the box's last letter instead. Silent, it still reaches a running app. This
     * phone has no extension and never says rq: its app takes the word itself (takes).
     */
    fun tellRead(ref: String) {
        if (ref.isEmpty() || MontanaSeed.twin == null) return
        val text = TQ + JSONObject().put("c", ref).put("t", System.currentTimeMillis()).toString()
        ex.execute {
            runCatching {
                val secret = secret() ?: return@runCatching
                val sender = deviceRef() ?: return@runCatching
                val loud = twinsQuiet()
                val boxed = Keeping.knockText(secret, text, sender, silent = !loud)
                Log.d("Montana", "twin_read told loud=" + (if (loud) 1 else 0) + " boxed=" + (if (boxed) 1 else 0))
            }.onFailure { Log.w("Montana", "twin_read " + it.javaClass.simpleName) }
        }
    }
    /** The read word's chat and moment (iOS MTTwinRead.read): null for every other text. */
    private fun readWord(text: String): Pair<String, Long>? {
        if (!text.startsWith(TQ)) return null
        val o = runCatching { JSONObject(text.removePrefix(TQ)) }.getOrNull() ?: return null
        val c = o.optString("c"); val t = o.optLong("t", 0L)
        return if (c.isEmpty() || t <= 0L) null else c to t
    }
    private fun twinsHeard(now: Double, kept: Double = TWIN_HEARD_FOR): JSONObject {
        val m = runCatching { JSONObject(Prefs.str(TWINS, "{}")) }.getOrNull() ?: JSONObject()
        for (k in m.keys().asSequence().toList()) {
            val e = m.optJSONArray(k)
            if (e == null || e.length() != 2 || now - e.optDouble(0, 0.0) >= kept) m.remove(k)
        }
        return m
    }
    /** One other device of the seed at least was heard within the month, and every one heard said it takes the word quietly. */
    private fun twinsQuiet(): Boolean {
        val m = twinsHeard(System.currentTimeMillis() / 1000.0)
        val keys = m.keys().asSequence().toList()
        return keys.isNotEmpty() && keys.all { m.optJSONArray(it)?.optDouble(1, 0.0) == 1.0 }
    }
    /** A mirror or history letter of another device of the seed: its writer is heard now, with what it said of its extension. */
    private fun heardTwin(o: JSONObject) {
        val f = o.optString("f").ifEmpty { return }
        // an ended device is told again, never listed (iOS MTLinkedDevices.endedAgain, 2405/2408)
        if (LinkedDevices.endedAgain(f, if (o.has("j")) o.optDouble("j") else null)) return
        val now = System.currentTimeMillis() / 1000.0
        val m = twinsHeard(now, maxOf(TWIN_HEARD_FOR, LinkedDevices.days * 86_400.0))   // the book keeps a device the phone's term long (OZ 1 p.4)
        m.put(f, JSONArray().put(now).put(if (o.optInt("rq") == 1) 1 else 0))
        Prefs.setStr(TWINS, m.toString())
        LinkedDevices.heard(f, o.optString("d").ifEmpty { null })
    }

    // ── the twin's past (iOS askTwin / giveTwinRows / takeTwinRow, f376b4ef) ──
    /** The rows of a conversation the archive holds and the feed does not: asked once, two hundred at most a round. */
    fun ask(chat: String, items: List<Triple<Long, Boolean, String>>, again: Boolean = false) {
        if (MontanaSeed.twin == null) return
        val fresh = synchronized(lock) {
            val asked = strings(ASKED)
            val have = if (again) emptySet() else asked.toHashSet()   // a tap asks a row once more (its clock is Media.mayKept's)
            val f = items.filter { (s, _, h) -> askedName(chat, s, h) !in have }.take(200)
            if (f.isEmpty()) return
            keepStrings(ASKED, (asked + f.map { (s, _, h) -> askedName(chat, s, h) }).takeLast(5000))
            f
        }
        val js = JSONObject().put("i", JSONArray(fresh.map { (s, mine, h) -> JSONArray().put(s).put(if (mine) 1 else 0).put(h) }))
        note(chat, UUID.randomUUID().toString().uppercase(), ASK + js.toString(), own = true)
        Log.d("Montana", "twin_ask chat=" + chat.take(10) + " rows=" + fresh.size)
    }
    /** How many rows stand asked (iOS mt.twin.asked count): a row unasked changes the archive's face, so the gap is measured again. */
    fun askedCount(): Int = synchronized(lock) { strings(ASKED).size }
    /** A row whose file did not come whole is asked again the next time the gap is measured. */
    private fun unask(chat: String, s: Long, h: String) = synchronized(lock) { keepStrings(ASKED, strings(ASKED).filter { it != askedName(chat, s, h) }) }
    /**
     * THE NAME OF ONE ASK, ONE OWNER (iOS askedName, 93023210, the author's word 21972): Saved Messages asks under a name of its own
     * since iOS 2397 -- before it an iPhone gave back no named row of Saved Messages, and the rows asked then stood «asked» for ever;
     * a new name asks them once more of a device that now answers. Every conversation asks under 2402's name (iOS 4bff00b3, the
     * critic of 22541): before it the other device gave back no named row of a pair, and the rows asked stood «asked» for ever.
     */
    private fun askedName(chat: String, s: Long, h: String) = "$chat#2402/$s/$h"

    /**
     * THE OTHER DEVICE ASKS FOR ROWS ITS ARCHIVE HOLDS AND ITS FEED DOES NOT (iOS ChatStore.giveTwinRows): each row held here under the
     * archive's own key -- its second, its side, the hash of its words -- is given back by its name, a file by the cargo road; a row
     * this device does not hold is the truth too (deleted here, or never here) and nothing is said of it.
     */
    private fun giveRows(chat: String, body: String) {
        val items = runCatching { JSONObject(body).getJSONArray("i") }.getOrNull() ?: return
        val rows = Book.chat(chat)?.msgs ?: return
        val want = HashSet<String>()
        for (i in 0 until minOf(200, items.length())) items.optJSONArray(i)?.takeIf { it.length() == 3 }?.let { a ->
            want.add(Archive.rowSig(a.optLong(0), a.optInt(1) == 1, a.optString(2)))
        }
        var n = 0
        // EVERY ROW ASKED IS GIVEN BACK BY ITS NAME (iOS 4bff00b3, 2402, the critic of 22541): the asker asks only what its feed lacks,
        // and takeRow lays no second row of a name or archive key
        for (m in rows) {
            if (m.at <= 0) continue
            val t = Archive.archivedText(m) ?: continue
            if (Archive.rowSig(m.at / 1000, m.mine, Archive.rowHash(t)) !in want) continue
            give(chat, m)
            n++
        }
        Log.d("Montana", "twin_answer chat=" + chat.take(10) + " asked=" + want.size + " given=" + n)
    }
    /**
     * SAVED MESSAGES REACH THE SEED'S OTHER DEVICES AT THEIR BIRTH (iOS 93023210, giveToTwin, P-207, the author's word 21972: «sent to
     * Saved from T1, nothing on the Mac»): the room has no wire, so no queue and no mirror carried its letter. A row born there -- a
     * text where it is written, a file where it settles -- is given to the twin by its name, as the answer to the twin's ask gives it.
     */
    fun born(chat: String, mid: String) {
        if (chat != Book.SAVED || MontanaSeed.twin == null) return
        ex.execute { Book.chat(chat)?.msgs?.firstOrNull { it.mid == mid && it.mine }?.let { m -> give(chat, m); Log.d("Montana", "twin_give saved") } }
    }
    private fun give(chat: String, m: Msg) {
        val o = JSONObject()
        m.from?.let { o.put("sr", it) }
        val at = m.at / 1000.0
        if (!m.text.startsWith(Marks.MEDIA)) {
            if (m.text.isEmpty()) return
            note(chat, m.mid, ROW + o.put("t", m.text).toString(), m.mine, at)
            return
        }
        // a media row: the words iOS keeps beside the file (a caption), the kind, the file's name, a document's name, a voice's length
        val man = Media.manifestOf(m) ?: return
        val f = m.file?.let { File(it) } ?: return   // a row keeps its file's name even while the file is not here
        val name = f.name
        man.optString("cap").takeIf { it.isNotEmpty() }?.let { o.put("t", it) }
        o.put("k", man.optString("k").ifEmpty { "doc" }).put("f", name)
        man.optString("n").takeIf { it.isNotEmpty() }?.let { o.put("n", it) }
        man.optDouble("du", 0.0).takeIf { it > 0 }?.let { o.put("d", it) }
        // NO ROW IS LOST IN SILENCE (iOS 4bff00b3, 2402, the critic of 22541): a row whose file this device does not hold is given as
        // words with its file's name -- the twin lays it and says the file it has not been given (2401)
        if (!f.isFile) {
            note(chat, m.mid, ROW + o.toString(), m.mine, at)
            Log.d("Montana", "twin_answer m=" + m.mid.take(8) + " no file here -- the row is given without it")
            return
        }
        // THE FILE RIDES WITH ITS WORD, ON DISK (iOS 0be38b73, 2415, OZ 1 p.9, the author's word 23159: «the media of Saved Messages
        // still do not arrive»): the word used to wait in memory for its file -- a run that ended on the way lost the row for good.
        // The word is queued at once with its file named (local, never ridden: a word with fl stays home), and the mirror lays the
        // file before it rides, eight tries, again at the next launch.
        note(chat, m.mid, ROW + o.toString(), m.mine, at, fl = f.absolutePath)
    }

    /**
     * A ROW THE OTHER DEVICE GAVE BACK (iOS ChatStore.takeTwinRow): laid by its own birth, once -- never over a row of the same name or
     * the same archive key standing here, never over its tombstone; a file comes by the cargo road under the name it bears there.
     */
    private fun takeRow(chat: String, mid: String, own: Boolean, at: Double, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return
        if (at <= 0 || mid.isEmpty()) return
        // SAVED MESSAGES IS BORN BY THE TWIN'S ROW (iOS 4bff00b3 savedBirth, the critic of 22541): a device that never wrote to itself
        // holds no Saved Messages, and the row its twin gave left silently; a person's conversation is born only by its pipe
        val rows = Book.chat(chat)?.msgs ?: if (chat == Book.SAVED) emptyList() else return
        if (rows.any { it.mid == mid } || Book.isBuried(chat, mid) || Book.diedBefore(chat, mid)) return
        val text = o.optString("t"); val sr = o.optString("sr").ifEmpty { null }
        val kind = o.optString("k").ifEmpty { null }
        val ms = (at * 1000).toLong()
        if (kind == null) {
            if (text.isEmpty()) return
            if (rows.any { it.at / 1000 == ms / 1000 && it.mine == own && it.text == text }) return
            lay(chat, Msg(mid, text, own, ms, state = if (own) 2 else 3, from = sr))
            return
        }
        val name = o.optString("f")
        if (kind !in setOf("img", "vid", "aud", "doc") || name.isEmpty() || name.contains('/') || name.startsWith(".")) return
        val man = JSONObject().put("k", kind).put("e", name.substringAfterLast('.', ""))
        o.optString("n").takeIf { it.isNotEmpty() }?.let { man.put("n", it) }
        if (text.isNotEmpty() && (kind == "img" || kind == "vid")) man.put("cap", text)
        o.optDouble("d", 0.0).takeIf { it > 0 }?.let { man.put("du", it) }
        val dest = File(Media.dir(Book.ctx), name)
        val row = { file: String? -> Msg(mid, Marks.MEDIA + man.toString(), own, ms, state = if (own) 2 else 3, file = file, meta = man.toString(), from = sr) }
        val h = Archive.rowHash(Archive.archivedText(row(dest.path)) ?: "")
        // A ROW STANDING WITHOUT ITS FILE TAKES THE FILE ALONE (iOS 598cb6cf, OZ 1 p.1.4.2): a restored row stands from the archive by
        // the same key (second, side, words and file name) and holds no file; the twin's copy fills it and lays no second row.
        val sig = Archive.rowSig(ms / 1000, own, h)
        val standing = rows.firstOrNull { m -> Archive.archivedText(m)?.let { Archive.rowSig(m.at / 1000, m.mine, Archive.rowHash(it)) } == sig }
        if (standing != null && standing.file?.let { File(it).isFile } != false) return
        val put = { file: String ->
            if (standing == null) lay(chat, row(file))
            else {
                Book.edit(chat) { c -> c.msgs.find { it.mid == standing.mid }?.let { it.text = Marks.MEDIA + man.toString(); it.meta = man.toString(); it.file = file } }
                Log.d("Montana", "twin_row chat=" + chat.take(10) + " a standing row only wears its file now")
            }
        }
        if (dest.isFile) { put(dest.path); return }
        // A ROW GIVEN WITHOUT ITS FILE STANDS (iOS takeTwinRow, 2401/2402): laid with its file's name and no file -- a tap asks the
        // twin again and the plaque says where the file is kept (Media.twinSilent)
        val road = o.optJSONObject("road") ?: run { if (standing == null) lay(chat, row(dest.path)); return }
        ex.execute {
            val tmp = scratch()
            try {
                if (!Keeping.fetch(road, tmp) || !tmp.renameTo(dest)) {
                    unask(chat, ms / 1000, h)
                    Log.d("Montana", "twin_row a file did not come whole -- asked again at the next gap"); return@execute
                }
            } finally { tmp.delete() }
            put(dest.path)
        }
    }
    private fun lay(chat: String, m: Msg) {
        if (chat == Book.SAVED) Book.saved(Book.ctx)   // born here at its first row (savedBirth)
        var n = 0
        Book.edit(chat) { c ->
            if (c.msgs.none { it.mid == m.mid } && !Book.isBuried(chat, m.mid)) { c.msgs.add(m); c.msgs.sortBy { it.at } }
            n = c.msgs.size
        }
        Log.d("Montana", "twin_row chat=" + chat.take(10) + " sid=" + m.mid.take(16) + " n=" + n)
    }
}
