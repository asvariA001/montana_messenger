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

    /** A road's name and key alone (iOS roadsOf, c22a497d at 2418): enough to find its pieces again and take them off the nodes. */
    fun roadsOf(roads: Collection<JSONObject>): JSONArray = JSONArray().apply {
        for (r in roads) {
            val mref = r.optString("mref"); val mk = r.optString("mk")
            if (mref.isNotEmpty() && mk.isNotEmpty()) put(JSONObject().put("mref", mref).put("mk", mk))
        }
    }
    /** Two lists of roads as one (an absent list is empty). */
    fun roadsJoin(a: JSONArray?, b: JSONArray?): JSONArray = JSONArray().apply {
        for (l in listOfNotNull(a, b)) for (i in 0 until l.length()) l.optJSONObject(i)?.let { put(it) }
    }

    /**
     * THE ROADS TAKEN OFF THE NODES (iOS unlay, c22a497d at 2418, OZ 1: «it cannot be that a week left so many pieces»): each list is
     * read under its key, and its pieces and the list itself are dropped from every store, a slice the store takes at a time. A list
     * already gone leaves only its own name to drop. Runs on the caller's thread: call it off the main one.
     */
    fun unlay(roads: JSONArray?) {
        if (roads == null || roads.length() == 0) return
        val bids = ArrayList<String>()
        for (i in 0 until roads.length()) {
            val r = roads.optJSONObject(i) ?: continue
            val mref = r.optString("mref").takeIf { it.isNotEmpty() } ?: continue
            val mk = runCatching { Base64.decode(r.optString("mk"), Base64.DEFAULT) }.getOrNull()
            val list = if (mk == null || mk.isEmpty()) null else Wire.getBlob(mref)?.takeIf { Wire.hex(Wire.sha(it)) == mref }
            val chunks = list?.let { Wire.open(mk!!, it) }?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() }
            if (chunks != null) for (k in 0 until chunks.length()) chunks.optJSONObject(k)?.optString("bid")?.takeIf { it.isNotEmpty() }?.let { bids.add(it) }
            bids.add(mref)
        }
        var landed = 0
        for (start in bids.indices step UNLAY_SLICE) {
            val slice = JSONArray(bids.subList(start, minOf(start + UNLAY_SLICE, bids.size)))
            for (door in Doors.ordered("blob")) if (Wire.post(door, "/blob-drop", JSONObject().put("bids", slice), 15_000).first == 200) landed++   // every store (iOS 1638)
        }
        Log.i("Montana", "keep_unlay roads=${roads.length()} pieces=${bids.size} landed=$landed")
    }
    private const val UNLAY_SLICE = 4096   // the names one /blob-drop takes (the node store refuses more; iOS unlaySlice)

    /** Whether a road still stands on the nodes (iOS stands): its list asked of every store. No answer is no verdict -- the road is used. */
    fun stands(mref: String): Boolean {
        if (mref.isEmpty()) return false
        val have = Wire.blobsHave(listOf(mref), everyStore = true) ?: return true
        return mref in have
    }

    /**
     * ONE LETTER IN A PIPE OF ITS OWN (iOS knock 833-841, MTNodeWire.knockLetter): mid ‖ 0 ‖ «KP:» and the word ‖ 0 ‖ 0 ‖ 0, padded to
     * the envelope, sealed afresh at every door; the first door that boxes it ends the walk. `sender` -- the name the pipe hears it
     * from: the node never gives a letter back to the name that laid it.
     */
    fun knock(secret: ByteArray, o: JSONObject, sender: String, silent: Boolean = true): Boolean = knockText(secret, MARK + o.toString(), sender, silent)
    /**
     * THE SAME WORD ON THE LIVE WIRE (iOS 71e0605a MTKeeping.wire, 2424, OZ 1 p.1.8, the author's words 23559 and 23560: «the nodes are
     * an additional channel, the main one must be direct»): the word a knock boxes at a node's door rides at the same instant as a
     * letter's envelope under the pipe's tag of this minute on every standing channel. Its form and size are a letter's own -- no
     * padding to one measure. False: no carrier stands, or the word is past one letter's measure -- the knock carries it alone then.
     */
    fun wire(secret: ByteArray, o: JSONObject): Boolean {
        val mid = UUID.randomUUID().toString().uppercase()
        val body = mid.toByteArray() + byteArrayOf(0) + (MARK + o.toString()).toByteArray() + byteArrayOf(0, 0, 0)
        if (body.size > Wire.ENVELOPE) return false
        return Channels.lay(secret, mid, body, MontanaSeed.twin ?: "")
    }
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
    // ── THE SEED'S WORD BY THE LIVE WIRE (iOS 71e0605a twinTagged / twinWired, 2424, OZ 1 p.1.8) ──
    // The pipe of the seed's devices stands in no book of pipes and must not, so the wire never knew its tag and carried such a frame
    // on as a stranger's. The wire asks the tag here, held for the minute.
    private var tagsAt = -1L
    private var tagsHeld = emptySet<String>()
    /** Whether a tag of the wire (hex) is the seed's pipe's in an accepted window. */
    fun tagged(tagHex: String): Boolean {
        val w = Wire.minute()
        synchronized(tagLock) {
            if (tagsAt != w) {
                val s = secret() ?: return false
                tagsHeld = longArrayOf(w - 1, w, w + 1).flatMap { k -> Wire.windowTags(s, k).map { Wire.hex(it) } }.toSet()
                tagsAt = w
            }
            return tagHex in tagsHeld
        }
    }
    private val tagLock = Any()
    /** A word of the seed's pipe heard on the wire: opened by the seed's key, landed by the one door the box lands at. */
    fun wired(sealed: ByteArray) {
        val s = secret() ?: return
        val plain = Wire.openWalked(sealed, s, Wire.minute())?.plain ?: return Unit.also { Log.d("Montana", "twin_wire refused -- the seed's seal did not match") }
        val f = Wire.fields(plain)
        val mid = f.getOrNull(0).orEmpty(); val text = f.getOrNull(1).orEmpty()
        if (mid.isEmpty() || text.isEmpty()) return
        Log.d("Montana", "twin_wire heard m=" + mid.take(8) + " bytes=" + sealed.size)
        takes(text, mid)
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
                val secret = secret()
                val me = deviceRef()
                val mine = Archive.writerHex()
                if (secret == null || me == null || mine == null) {
                    // keys not derived yet (the first pickup comes before them): the window stays unspent, the next pickup names the cursor (iOS 2422)
                    synchronized(lock) { if (wantedAt == now) wantedAt = 0L }
                    return@runCatching
                }
                val held = Archive.held()
                val body = held.toString()
                val o = JSONObject().put("w", "hw").put("f", mine)
                LinkedDevices.stamp(o)   // the cursor word is the device's sign of life on every rise: its kind, and the phone's term (OZ 1 p.4)
                val lc = mirrorCursors()
                if (lc.length() > 0) o.put("lc", lc)   // the mirror's cursor rides the same word (iOS 2417, OZ 1 p.2)
                if (!wordRoad(o, body, "c")) return@runCatching Unit.also { Log.d("Montana", "twin_want the cursor did not reach the nodes") }
                val boxed = Keeping.knock(secret, o, me)
                Log.d("Montana", "twin_want folders=" + held.length() + " bytes=" + body.length + " boxed=" + (if (boxed) 1 else 0))
                tellPrints(secret, me, mine)   // the prints of the chats ride beside the cursor word (iOS 5021b111, OZ 1 p.8)
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
        val body = wordBody(o, "c")
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
            // a row of Saved Messages told at its birth (iOS 5474fbcf takeSaved, 2419; an older build takes it unread, the mirror brings the row)
            // a device joined by QR: its copy's cursors held here a day (iOS 84b18c6e heardJoining, 2419; an older build takes it unread)
            // another device's end, naming a device not this one: an ended Mac leaves this list too (iOS 08cce7f3, OZ 1 p.7)
            "end" -> o.optString("t").takeIf { it.isNotEmpty() && it != Archive.writerHex() && o.has("at") }?.let { t ->
                LinkedDevices.heardEnds(JSONObject().put(t, o.optDouble("at")))
            }
            // the prints of the chats, and a chat's keys whose print differs (iOS 5021b111, 2419, OZ 1 p.8; an older build takes them unread)
            "pr" -> ex.execute { runCatching { comparePrints(o) }.onFailure { Log.w("Montana", "twin_print " + it.javaClass.simpleName) } }
            "ck" -> ex.execute { runCatching { takeKeys(o) }.onFailure { Log.w("Montana", "twin_print " + it.javaClass.simpleName) } }
            "jn" -> ex.execute { runCatching { heardJoining(o) }.onFailure { Log.w("Montana", "twin_join " + it.javaClass.simpleName) } }
            "sv" -> ex.execute { runCatching { takeSaved(o) }.onFailure { Log.w("Montana", "twin_saved " + it.javaClass.simpleName) } }
            // words opened on a new device ask a picture of now (iOS 2599c6db, 2430; an older build takes it unread)
            "lw" -> heardWordsOpened(o)
            // a row of Saved Messages or its file stands on the seed's other device (iOS 712652da, 2423, OZ 1 p.1.5; an older build takes it unread)
            "sl" -> heardLanded(o)
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
             fl: String? = null, saved: Boolean = false) {
        if (MontanaSeed.twin == null) return
        val born = synchronized(lock) {
            val reads = x.startsWith(READ)
            val now = System.currentTimeMillis()
            // THE NAME OF A WORD IS ALL OF IT (iOS e451e027, 2424, OZ 1 p.9): an edit of a row rides under the row's own name, and its
            // first words alone named it a repeat of the row for an hour
            val name = c + "/" + m + "/" + Archive.rowHash(x)
            if (!reads && now - (given[name] ?: 0L) <= 3_600_000L) return@synchronized null
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
            w
        } ?: return
        if (saved) savedNow(born)   // a row of Saved Messages rides at once by its own letter (iOS 5474fbcf, OZ 1 p.1)
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
            val laid = try { layFile(w).first } catch (e: Exception) { Log.w("Montana", "twin_file " + e.javaClass.simpleName); false }
            synchronized(lock) { mirrorFiling = false }
            mirrorSoon(if (laid) 1 else 30)
        }, "twin-file").apply { isDaemon = true }.start()
    }
    /**
     * The file of a row given to the twin, laid on the cargo road and its road written into the word on disk (iOS layFile, 2415): a
     * run that ends on the way lays it again at the next launch, and a road laid is never laid twice. A file no longer here, or
     * refused eight times, lets the word ride as words with its file's name, said.
     */
    private fun layFile(w: JSONObject): Pair<Boolean, String?> {
        val path = w.optString("fl").ifEmpty { return true to null }
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
        return (x != null || !here) to x
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
        val busy = synchronized(lock) { HashSet(savedFiling) }   // a file Saved Messages lays itself is not laid twice (iOS 2419)
        for (i in 0 until batch0.length()) batch0.getJSONObject(i).let {
            if (!it.has("fl")) batch.put(it) else if (it.optLong("q", -1L) !in busy) fileSoon(it)
        }
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
    // ── SAVED MESSAGES BY A ROAD OF ITS OWN (iOS 5474fbcf, 2419, OZ 1 p.1, the author's words 23326-23327: «total sync of Saved Messages
    // by a separate road, so that it depends on nothing and syncs at once on every device») ──
    // A row born in Saved Messages waited for the mirror: its words laid as a file on the nodes and fetched back by the other device,
    // its file laid in front of its words, one file at a time behind any other. Now the row's words ride at its birth inside one
    // letter of the seed's pipe -- no file of words, no round, no queue -- and the other device lays the row by its name the moment it
    // hears the letter; a row with a file is laid at once without it, and the road of its file follows in a second letter of the same
    // name. The mirror still carries the same row by the same name, never laid twice: a lost letter is a delay, never a loss.
    private val savedFiling = HashSet<Long>()   // under lock: files Saved Messages lays itself
    /** The row just queued rides at once by its own letter, and its file is laid beside the mirror. */
    private fun savedNow(w: JSONObject) {
        val q = w.optLong("q", -1L)
        val file = w.has("fl")
        if (file) synchronized(lock) { savedFiling.add(q) }
        Thread({
            try {
                tellSaved(w, w.optString("x"))
                if (file) {
                    val laid = try { layFile(w) } finally { synchronized(lock) { savedFiling.remove(q) }; mirrorSoon(1) }
                    laid.second?.let { tellSaved(w, it) }
                }
            } catch (e: Exception) { Log.w("Montana", "twin_saved " + e.javaClass.simpleName) }
        }, "twin-saved").apply { isDaemon = true }.start()
    }
    /** One letter of Saved Messages in the seed's pipe: the row's words, and its file's road once laid. */
    private fun tellSaved(w: JSONObject, x: String) {
        val t0 = System.currentTimeMillis()
        val secret = secret() ?: return
        val me = deviceRef() ?: return
        val o = JSONObject().put("w", "sv").put("f", Archive.writerHex() ?: "").put("c", w.optString("c")).put("m", w.optString("m"))
            .put("x", x).put("at", w.optDouble("at", 0.0))
        if (w.optInt("o") == 1) o.put("o", 1)
        // THE STANDING CHANNELS FIRST, THE KNOCK BESIDE THEM (iOS 71e0605a, 2424, OZ 1 p.1.8 and p.1.2): the wire carries the word in
        // milliseconds to a device that is awake; the knock boxes the same word for one that sleeps. The row is laid by its name once.
        val name = w.optString("m").take(12)
        val wired = runCatching { Keeping.wire(secret, o) }.getOrDefault(false)
        if (wired) Log.d("Montana", "twin_wire sent w=sv m=$name")
        val boxed = Keeping.knock(secret, o, me)
        val road = if (x == w.optString("x")) 0 else 1
        val wd = if (wired) 1 else 0
        Log.d("Montana", "twin_saved " +
            if (boxed) "told ms=" + (System.currentTimeMillis() - t0) + " road=$road wire=$wd m=$name" else "not boxed road=$road wire=$wd m=$name -- the mirror carries it")
    }
    /** A row of Saved Messages the seed's other device told at its birth: laid here by its name, by the mirror's one landing road. */
    private fun takeSaved(o: JSONObject) {
        val f = o.optString("f")
        val m = o.optString("m"); val x = o.optString("x")
        if (f == (Archive.writerHex() ?: "") || o.optString("c") != Book.SAVED || m.isEmpty() || !x.startsWith(ROW)) return
        heardTwin(o)
        val w = JSONObject().put("c", Book.SAVED).put("m", m).put("x", x).put("at", o.optDouble("at", 0.0))
        if (o.optInt("o") == 1) w.put("o", 1)
        land(w)
        // the letter was heard; where its row lands is said there -- twin_row, twin_row dropped, media_fill (iOS 2423, OZ 1 p.1.4)
        Log.d("Montana", "twin_saved heard road=" + (if (x.contains("\"road\":")) 1 else 0) + " m=" + m.take(12))
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
    private const val HOLD_FOR = 86_400.0   // a joining's cursor holds this device's letters after it a day (iOS holdFor, 2419)
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
        // a joining's cursor on this device's run: the letters after it stay a day (iOS 84b18c6e holds, OZ 1 p.6)
        val holds = b.optJSONArray("holds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { now - it.optDouble("at", 0.0) < HOLD_FOR }.takeLast(8)
        if (holds.isEmpty()) b.remove("holds") else b.put("holds", JSONArray(holds))
        val held = holds.minOfOrNull { it.optInt("c") } ?: Int.MAX_VALUE   // a joining's device is heard only after the copy lies
        val low = minOf(held, if (devices.isEmpty()) 0 else devices.minOf { peers.optInt(it, 0) })
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
            var c: Int = start
            val above = TreeSet<Int>()
            had?.optJSONArray("above")?.let { a -> for (i in 0 until a.length()) above.add(a.optInt(i)) }
            if (lo != null && c + 1 < lo) {
                Log.d("Montana", "twin_mirror the writer holds no more " + (c + 1) + ".." + (lo - 1) + " run=" + run)
                c = lo - 1
            }
            if (sn != null && c < sn) above.add(sn)
            while (above.isNotEmpty() && above.first() <= c + 1) c = maxOf(c, above.pollFirst()!!)
            val t = JSONObject().put("c", c).put("above", JSONArray(above.toList())).put("at", now)
            // the letter that started this cursor: a joined device's copy may name a lower one (iOS MirrorCursor.first, OZ 1 p.6)
            (if (had != null) had.optInt("first", 0).takeIf { it > 0 } else sn)?.let { t.put("first", it) }
            took.put(run, t)
            keepBook(b)
        }
        took.keys().asSequence().any { k -> took.optJSONObject(k)?.let { t -> (t.optJSONArray("above")?.length() ?: 0) > 0 && now - t.optDouble("at", 0.0) < 3600 } == true }
    }
    // ── A JOINING BY QR READS THE SAME BOOK (iOS 84b18c6e, 2419, OZ 1 p.6; the author's words 23183, 23239, 23260; decision 989 = a) ──
    // The phone's light copy is a picture of the history at one moment. The joined device started its cursor on the phone's run at the
    // first letter it heard, so the letters laid between the picture and that letter were taken by nobody. Now the copy names the
    // mirror's cursors as they stood when it was gathered (copyCursors); every writer holds its letters after that cursor a day for the
    // joining (the phone at once, the others on the word jn); and the joined device takes the copy's cursors as its own the moment
    // the copy lies (cursorsFromCopy): what came after the picture is laid again from the books, what the copy holds is a repeat.
    /** The mirror's cursors as a copy gathered now stands; for a joining this device's letters after it are held a day. */
    fun copyCursors(holding: Boolean): JSONObject = synchronized(bookLock) {
        val b = readBook()
        val took = b.getJSONObject("took")
        val lc = JSONObject()
        for (k in took.keys()) took.optJSONObject(k)?.let { lc.put(k, it.optInt("c")) }
        val mine = Archive.writerHex().orEmpty()
        if (mine.isNotEmpty()) lc.put(mine + "/" + b.optString("run"), b.optInt("next", 1) - 1)
        if (holding) hold(b, b.optInt("next", 1) - 1)
        lc
    }
    /** Under bookLock: this device's letters after `c` stay in its book a day, whatever cursors it heard. */
    private fun hold(b: JSONObject, c: Int) {
        val now = System.currentTimeMillis() / 1000.0
        b.put("holds", (b.optJSONArray("holds") ?: JSONArray()).put(JSONObject().put("c", c).put("at", now)))
        trimBook(b, now)
        keepBook(b)
        Log.d("Montana", "twin_mirror held for a joining from sn=" + (c + 1))
    }
    /** The phone gave its words: the seed's other devices hold their letters after the copy's cursors a day too, and this one
     *  (`here`) when its copy was gathered before the joining. */
    fun tellJoining(lc: JSONObject, here: Boolean = false) {
        val mine = Archive.writerHex().orEmpty()
        if (here) synchronized(bookLock) { val b = readBook(); if (lc.has(mine + "/" + b.optString("run"))) hold(b, lc.optInt(mine + "/" + b.optString("run"))) }
        ex.execute {
            val secret = secret() ?: return@execute
            val me = deviceRef() ?: return@execute
            val told = Keeping.knock(secret, JSONObject().put("w", "jn").put("f", mine).put("lc", lc), me)
            Log.d("Montana", "twin_mirror a joining told runs=" + lc.length() + " boxed=" + (if (told) 1 else 0))
        }
    }
    /** Another device of the seed gave its words to a new one: this device's letters after the cursor its copy names stay a day. */
    private fun heardJoining(o: JSONObject) {
        val mine = Archive.writerHex().orEmpty()
        val lc = o.optJSONObject("lc") ?: return
        if (mine.isEmpty() || o.optString("f") == mine) return
        synchronized(bookLock) { val b = readBook(); val k = mine + "/" + b.optString("run"); if (lc.has(k)) hold(b, lc.optInt(k)) }
    }
    /** On a device joined by QR the phone's copy lies: its cursors are this device's own. A cursor started at a letter heard before the
     *  copy lay goes back to the copy's -- the letters between are asked, the ones taken stay taken -- and the cursor is named at once. */
    /** WORDS OPENED ON A NEW DEVICE ASK THE PICTURE OF NOW (iOS 2599c6db askTwinsLight, 2430, the author's words 23891, 23893): that
     *  device takes its history as a device joined by QR does -- from a copy named after its asking, with the cursors it stands on. */
    fun askLight() {
        val mine = Archive.writerHex() ?: ""
        ex.execute {
            runCatching {
                val secret = secret() ?: return@runCatching
                val me = deviceRef() ?: return@runCatching
                val told = Keeping.knock(secret, JSONObject().put("w", "lw").put("f", mine).put("at", System.currentTimeMillis() / 1000), me)
                Log.i("Montana", "keep_light words opened: the seed's devices asked boxed=" + (if (told) 1 else 0))
            }.onFailure { Log.w("Montana", "keep_light " + it.javaClass.simpleName) }
        }
    }
    /** Another device opened these words: this one names its picture of now, as for a joining. A device that holds no history, or is
     *  taking one itself, names nothing -- an empty copy named now would stand as the owner. An asking ten minutes old is past. */
    private fun heardWordsOpened(o: JSONObject) {
        if (o.optString("f") == Archive.writerHex() || System.currentTimeMillis() / 1000 - o.optLong("at", 0L) >= 600) return
        if (KeepCopy.isTaking || Book.refs().none { Book.chat(it)?.msgs?.isNotEmpty() == true }) {
            Log.i("Montana", "keep_light asked by words opened elsewhere -- no history here to name"); return
        }
        KeepCopy.lightForLink()
    }
    fun cursorsFromCopy(lc: JSONObject) {
        val mine = Archive.writerHex().orEmpty() + "/"
        var back = 0
        synchronized(bookLock) {
            val b = readBook()
            val took = b.getJSONObject("took")
            val now = System.currentTimeMillis() / 1000.0
            for (r in lc.keys().asSequence().toList()) {
                if (r.startsWith(mine)) continue
                val c = lc.optInt(r)
                val t = took.optJSONObject(r)
                if (t == null) { took.put(r, JSONObject().put("c", c).put("above", JSONArray()).put("at", now)); continue }
                val first = t.optInt("first", 0); val tc = t.optInt("c")
                if (first <= 0 || c + 1 >= first || first > tc) continue
                val above = TreeSet<Int>()
                t.optJSONArray("above")?.let { a -> for (i in 0 until a.length()) above.add(a.optInt(i)) }
                for (n in first..tc) above.add(n)
                took.put(r, JSONObject().put("c", c).put("above", JSONArray(above.toList())).put("at", now))
                back++
            }
            keepBook(b)
        }
        Log.d("Montana", "twin_mirror cursors from the phone's copy runs=" + lc.length() + " back=" + back)
        synchronized(lock) { wantedAt = 0L }
        if (back > 0) mirrorHole = true
        wantHistory(soon = true)
    }
    /**
     * THE ONE DOOR OF A WORD'S BODY (iOS 5021b111 wordRoad / wordBody): inline under `key` while the word fits one envelope with its
     * name and mark (Keeping.knockText) -- the word itself is measured -- or laid on the nodes and named by its road. False: not laid.
     */
    private fun wordRoad(o: JSONObject, body: String, key: String): Boolean {
        val inline = JSONObject(o.toString()).put(key, body)
        if ((Keeping.MARK + inline).toByteArray().size + 48 <= Wire.ENVELOPE) { o.put(key, body); return true }
        val f = scratch()
        try {
            f.writeText(body)
            val road = Keeping.lay(f) ?: return false
            for (k in road.keys()) o.put(k, road.get(k))
            return true
        } finally { f.delete() }
    }
    /** The body a word of the seed carries: inline under `key`, or fetched by its road. */
    private fun wordBody(o: JSONObject, key: String): String? {
        o.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
        if (!o.has("mref")) return null
        val f = scratch()
        try { return if (Keeping.fetch(o, f)) f.readText() else null } finally { f.delete() }
    }

    // ── THE PRINT OF EVERY CHAT (iOS 5021b111, 2419, OZ 1 p.8; the author's words 23282, 23284: «build the sync properly») ──
    // The book's numbers say what a device took of the others' letters, never what its chats hold: a row no letter carried, a letter
    // of a book lost, a copy laid around a hole stayed a silent difference. Each device names, in a word of its own (pr), the print of
    // every chat it holds -- the count of its rows and a hash of their archive keys, the same on every device holding the same rows;
    // the last two minutes are left out, they are on their way. A device that hears a print unlike its own names that chat's keys to
    // it (ck); that one gives back each row the keys lack and asks each key it lacks (heal). Every comparison is said in the diary.
    private var printedAt = 0L   // under lock
    private val healedPrints = HashMap<String, Pair<String, Long>>()   // under lock: device/chat -- its last heal
    private class ChatPrint(val chat: String, val print: String, val keys: List<String>)
    /** This device's chats as the seed's devices compare them: a chat's tag (a hash of its key) -- its key, its print, its keys. */
    private fun chatPrints(): Map<String, ChatPrint> {
        val settled = System.currentTimeMillis() - 120_000L
        val out = HashMap<String, ChatPrint>()
        for (ch in Book.rooms()) {
            val rows = ch.msgs.toList()
            if (rows.isEmpty()) continue
            val keys = Archive.archiveKeys(rows.filter { it.at < settled })
            out[Archive.rowHash("chat/" + ch.ref)] = ChatPrint(ch.ref, keys.size.toString() + "." + Archive.rowHash(keys.joinToString("\n")), keys)
        }
        return out
    }
    /** The word pr: this device's print of every chat it holds, beside the cursor word, at most once in five minutes. */
    private fun tellPrints(secret: ByteArray, me: String, mine: String) {
        val t = System.currentTimeMillis()
        synchronized(lock) { if (t - printedAt <= 300_000L) return; printedAt = t }
        val prints = JSONObject().also { p -> for ((tag, c) in chatPrints()) p.put(tag, c.print) }
        if (prints.length() == 0) return
        val body = prints.toString()
        val o = JSONObject().put("w", "pr").put("f", mine)
        if (!wordRoad(o, body, "p")) return Unit.also { Log.d("Montana", "twin_print the prints did not reach the nodes") }
        val boxed = Keeping.knock(secret, o, me)
        Log.d("Montana", "twin_print told chats=" + prints.length() + " bytes=" + body.length + " boxed=" + (if (boxed) 1 else 0))
    }
    /** Another device's prints: every chat both hold is compared and the count said; a chat that differs names its keys to that device,
     *  once in half an hour for the same two prints. */
    private fun comparePrints(o: JSONObject) {
        val f = o.optString("f")
        if (f.isEmpty() || f == Archive.writerHex() || LinkedDevices.isEnded(f)) return
        val theirs = wordBody(o, "p")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Unit.also { Log.d("Montana", "twin_print the prints did not come whole") }
        val mine = chatPrints()
        var same = 0
        val differ = ArrayList<String>()
        for (tag in theirs.keys()) {
            val m = mine[tag] ?: continue   // a chat this device does not hold: the history and the copy bring it
            if (m.print == theirs.optString(tag)) same++ else differ.add(tag)
        }
        Log.d("Montana", "twin_print from=" + f.take(8) + " theirs=" + theirs.length() + " mine=" + mine.size + " same=$same differ=" + differ.size)
        val now = System.currentTimeMillis()
        for (tag in differ) {
            val m = mine[tag] ?: continue
            val p = theirs.optString(tag)
            val pair = m.print + "/" + p; val slot = "$f/$tag"
            val due = synchronized(lock) {
                val h = healedPrints[slot]
                if (h != null && h.first == pair && now - h.second < 1_800_000L) false else { healedPrints[slot] = pair to now; true }
            }
            if (!due) continue
            Log.d("Montana", "twin_print chat=" + m.chat.take(10) + " mine=" + m.print + " theirs=" + p + " -- its keys go")
            tellKeys(m.chat, m.keys, f)
        }
    }
    /** The word ck: a chat's archive keys, to the one device whose print of it differed. */
    private fun tellKeys(chat: String, keys: List<String>, to: String) {
        val secret = secret() ?: return
        val me = deviceRef() ?: return
        val o = JSONObject().put("w", "ck").put("f", Archive.writerHex() ?: "").put("to", to).put("ch", chat)
        if (!wordRoad(o, keys.joinToString("\n"), "k")) return Unit.also { Log.d("Montana", "twin_print the keys did not reach the nodes") }
        val boxed = Keeping.knock(secret, o, me)
        Log.d("Montana", "twin_print keys told chat=" + chat.take(10) + " n=" + keys.size + " boxed=" + (if (boxed) 1 else 0))
    }
    /** A chat's keys named to this device: it heals the chat by them. */
    private fun takeKeys(o: JSONObject) {
        val mine = Archive.writerHex() ?: return
        val f = o.optString("f"); val chat = o.optString("ch")
        if (f.isEmpty() || f == mine || o.optString("to") != mine || LinkedDevices.isEnded(f) || chat.isEmpty()) return
        val body = wordBody(o, "k") ?: return Unit.also { Log.d("Montana", "twin_print the keys did not come whole") }
        heal(chat, body.split('\n').filter { it.isNotEmpty() }.toHashSet())
    }
    /**
     * A CHAT WHOSE PRINT DIFFERED ON THE SEED'S OTHER DEVICE (iOS ChatStore.healTwin): it named the keys of its rows. Each row held here
     * under a key it lacks is given back by its name, each key it holds that this device lacks is asked -- the roads of the archive's
     * gap; a row buried there is never laid over its tombstone.
     */
    private fun heal(chat: String, theirs: Set<String>) {
        val mine = HashSet<String>()
        var given = 0
        for (m in Book.chat(chat)?.msgs?.toList().orEmpty()) {
            val k = Archive.archiveKey(m) ?: continue
            mine.add(k)
            if (k !in theirs && given < 200) { give(chat, m, born = false); given++ }
        }
        val lack = (theirs - mine).mapNotNull { k ->
            val p = k.split('|')
            val s = p.getOrNull(0)?.toLongOrNull()
            if (p.size != 3 || s == null) null else Triple(s, p[1] == "1", p[2])
        }
        if (lack.isNotEmpty()) ask(chat, lack)
        Log.d("Montana", "twin_heal chat=" + chat.take(10) + " mine=" + mine.size + " theirs=" + theirs.size + " gave=$given asked=" + lack.size)
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
        o.optJSONObject("ed")?.let { LinkedDevices.heardEnds(it) }   // the sessions ended ride every letter (iOS 08cce7f3, OZ 1 p.7)
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
            if (Archive.archiveKey(m) !in want) continue   // one owner of a row's archive key (iOS 5021b111)
            give(chat, m, born = false)   // an answer rides the mirror alone: two hundred rows never ride as two hundred letters
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
    private fun give(chat: String, m: Msg, born: Boolean = true) {
        val now = born && chat == Book.SAVED   // a row of Saved Messages at its birth rides by a letter of its own too (iOS 2419)
        val o = JSONObject()
        m.from?.let { o.put("sr", it) }
        parts(m).takeIf { it.length() > 0 }?.let { o.put("r", it) }   // the whole row: its quote, card, answer, edit (iOS 2424, OZ 1 p.9)
        val at = m.at / 1000.0
        if (!m.text.startsWith(Marks.MEDIA)) {
            if (m.text.isEmpty()) return
            note(chat, m.mid, ROW + o.put("t", m.text).toString(), m.mine, at, saved = now)
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
            note(chat, m.mid, ROW + o.toString(), m.mine, at, saved = now)
            Log.d("Montana", "twin_answer m=" + m.mid.take(8) + " no file here -- the row is given without it")
            return
        }
        // THE FILE RIDES WITH ITS WORD, ON DISK (iOS 0be38b73, 2415, OZ 1 p.9, the author's word 23159: «the media of Saved Messages
        // still do not arrive»): the word used to wait in memory for its file -- a run that ended on the way lost the row for good.
        // The word is queued at once with its file named (local, never ridden: a word with fl stays home), and the mirror lays the
        // file before it rides, eight tries, again at the next launch.
        note(chat, m.mid, ROW + o.toString(), m.mine, at, fl = f.absolutePath, saved = now)
    }

    /**
     * A ROW THE OTHER DEVICE GAVE BACK (iOS ChatStore.takeTwinRow): laid by its own birth, once -- never over a row of the same name or
     * the same archive key standing here, never over its tombstone; a file comes by the cargo road under the name it bears there.
     */
    private fun takeRow(chat: String, mid: String, own: Boolean, at: Double, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (o == null || at <= 0 || mid.isEmpty()) return rowDropped(chat, mid, "a letter that names no row")
        // SAVED MESSAGES IS BORN BY THE TWIN'S ROW (iOS 4bff00b3 savedBirth, the critic of 22541): a device that never wrote to itself
        // holds no Saved Messages, and the row its twin gave left silently; a person's conversation is born only by its pipe
        val rows = Book.chat(chat)?.msgs ?: if (chat == Book.SAVED) emptyList() else return rowDropped(chat, mid, "no chat here")
        if (Book.isBuried(chat, mid) || Book.diedBefore(chat, mid)) return rowDropped(chat, mid, "a grave over its name")
        // A ROW DELETED ON THE SEED'S OTHER DEVICE (iOS e451e027, 2424, OZ 1 p.9): buried here by its name, its row gone by the one road
        // of a deletion, told nobody again
        if (o.optInt("x") == 1) {
            if (rows.any { it.mid == mid }) Book.drop(chat, listOf(mid), tell = false) else Book.bury(chat, listOf(mid))
            Log.d("Montana", "twin_row m=" + mid.take(12) + " gone -- deleted on the seed's other device")
            return
        }
        val r = o.optJSONObject("r")
        // A ROW OF THIS NAME STANDING WITHOUT ITS FILE TAKES THE FILE (iOS 5474fbcf, OZ 1 p.1): Saved Messages lays a row at its first
        // letter, the file's road comes in the second letter of the same name; a row of the name standing whole is a repeat
        val named = rows.firstOrNull { it.mid == mid }
        val text = o.optString("t"); val sr = o.optString("sr").ifEmpty { null }
        val kind = o.optString("k").ifEmpty { null }
        val ms = (at * 1000).toLong()
        // A NEWER STATE OF A ROW STANDING HERE IS WORN BY IT (iOS e451e027, 2424, OZ 1 p.9, the author's word 23611): an edit or an
        // answer on the seed's other device stamped the row's moment; the later stands, an older copy the gap's healing gives back is a
        // repeat. The row's file stays as it stands here.
        val ch = r?.optDouble("ch", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        if (named != null && named.ch < ch) {
            Book.edit(chat) { c -> c.msgs.find { it.mid == mid }?.let { t ->
                wear(t, r)
                if (kind == null) { if (text.isNotEmpty()) t.text = text }
                else if (text != Media.captionOf(t)) Post.reword(t, text)
                t.edited = r?.optBoolean("edited") == true
            } }
            Log.d("Montana", "twin_row m=" + mid.take(12) + " changed at=" + ch.toLong())
        }
        if (kind == null) {
            if (named != null) return
            if (text.isEmpty()) return rowDropped(chat, mid, "a row of no words and no file")
            if (rows.any { it.at / 1000 == ms / 1000 && it.mine == own && it.text == text }) return
            lay(chat, Msg(mid, text, own, ms, state = if (own) 2 else 3, from = sr).also { wear(it, r) })
            return
        }
        val name = o.optString("f")
        if (kind !in setOf("img", "vid", "aud", "doc") || name.isEmpty() || name.contains('/') || name.startsWith(".")) return rowDropped(chat, mid, "a file of no known kind or name")
        val man = JSONObject().put("k", kind).put("e", name.substringAfterLast('.', ""))
        o.optString("n").takeIf { it.isNotEmpty() }?.let { man.put("n", it) }
        if (text.isNotEmpty() && (kind == "img" || kind == "vid" || kind == "doc")) man.put("cap", text)   // a document keeps its caption (iOS 2424)
        o.optDouble("d", 0.0).takeIf { it > 0 }?.let { man.put("du", it) }
        val dest = File(Media.dir(Book.ctx), name)
        val row = { file: String? -> Msg(mid, Marks.MEDIA + man.toString(), own, ms, state = if (own) 2 else 3, file = file, meta = man.toString(), from = sr).also { wear(it, r) } }
        val h = Archive.rowHash(Archive.archivedText(row(dest.path)) ?: "")
        // A ROW STANDING WITHOUT ITS FILE TAKES THE FILE ALONE (iOS 598cb6cf, OZ 1 p.1.4.2): a restored row stands from the archive by
        // the same key (second, side, words and file name) and holds no file; the twin's copy fills it and lays no second row.
        val sig = Archive.rowSig(ms / 1000, own, h)
        val standing = named ?: rows.firstOrNull { m -> Archive.archivedText(m)?.let { Archive.rowSig(m.at / 1000, m.mine, Archive.rowHash(it)) } == sig }
        if (standing != null && standing.file?.let { File(it).isFile } != false) return
        val put = { file: String ->
            // a standing row only wears its file now -- one laid by its first letter while the file came, too
            val cur = standing?.mid ?: mid
            if (standing == null && Book.chat(chat)?.msgs?.none { it.mid == mid } != false) lay(chat, row(file))
            else {
                Book.edit(chat) { c -> c.msgs.find { it.mid == cur }?.let { it.text = Marks.MEDIA + man.toString(); it.meta = man.toString(); it.file = file } }
                Log.d("Montana", "twin_row chat=" + chat.take(10) + " a standing row only wears its file now")
            }
            // THE FILE STANDS THROUGH THE ONE «FILE IS READY» POINT (iOS 712652da fillMedia, 2423, OZ 1 p.1.3): the diary names the
            // file, and the device that wrote the row hears its twin's own word that the file stands
            Log.d("Montana", "media_fill chat=" + chat.take(10) + " file=" + name.take(20))
            if (chat == Book.SAVED && System.currentTimeMillis() / 1000.0 - at < 3600) tellLanded(cur, true)
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
                    // A FILE THAT DID NOT COME IS ASKED AT ONCE (iOS 45bc9c07, 2419, OZ 1 p.5, the author's words 23268, 23280: the photo
                    // stood in the list and not in the chat): the gap's measure never sees a row standing without its file. The twin is
                    // asked again by the row's key, once a span (Media.mayKept, the tap's own clock).
                    if (Media.mayKept("twin:$mid")) ask(chat, listOf(Triple(ms / 1000, own, h)))
                    Log.d("Montana", "twin_row a file did not come whole -- the twin is asked again"); return@execute
                }
            } finally { tmp.delete() }
            put(dest.path)
        }
    }
    /**
     * THE PARTS OF A ROW ITS TWIN SHOWS (iOS e451e027 ChatStore.twinParts, 2424, OZ 1 p.9, the author's word 23611: «any data in Saved
     * Messages shown on every device»): its quote, card, answer, edit and moment, under the keys an iPhone keeps a row by; the words,
     * side, birth, sender and file ride by keys of their own, and the rung, read mark and road stay on this device.
     */
    private fun parts(m: Msg): JSONObject = JSONObject().apply {
        if (m.reactions.isNotEmpty()) put("reactions", JSONArray(m.reactions))
        m.myReact?.let { put("myReact", it) }
        m.peerReact?.let { put("peerReact", it) }
        m.qt?.let { put("replyText", it) }
        m.qm?.let { put("replyToId", it) }
        m.lp?.let { put("linkPreview", it) }
        if (m.edited) put("edited", true)
        if (0 < m.ch) put("ch", m.ch)
    }
    /** The same row wearing the parts its twin gave. A key the given row no longer holds goes here too: an answer taken back is written
     *  as no key at all, and the twin kept naming the old answer as its own (iOS wearingTwinParts, the critic of Agent 1258). */
    private fun wear(m: Msg, r: JSONObject?) {
        if (r == null) return
        m.reactions.clear()
        r.optJSONArray("reactions")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { m.reactions.add(it) } }
        m.myReact = r.optString("myReact").ifEmpty { null }
        m.peerReact = r.optString("peerReact").ifEmpty { null }
        m.qt = r.optString("replyText").ifEmpty { null }
        m.qm = r.optString("replyToId").ifEmpty { null }
        m.lp = r.optString("linkPreview").ifEmpty { null }
        if (r.optBoolean("edited")) m.edited = true
        m.ch = r.optDouble("ch", 0.0).takeIf { !it.isNaN() } ?: 0.0
    }
    /** A ROW OF SAVED MESSAGES CHANGED HERE (iOS e451e027 savedChanged, 2424, OZ 1 p.9): an edit, an answer or a late link card stamps
     *  the row's moment and rides to the seed's other devices at once by the row's name; the later state stands there. */
    fun changed(chat: String, mid: String) {
        if (chat != Book.SAVED || MontanaSeed.twin == null) return
        var row: Msg? = null
        Book.edit(chat) { c -> c.msgs.find { it.mid == mid }?.let { it.ch = System.currentTimeMillis() / 1000.0; row = it } }
        val m = row ?: return
        ex.execute { runCatching { give(chat, m) }.onFailure { Log.w("Montana", "twin_give " + it.javaClass.simpleName) } }
    }
    /** A ROW DELETED HERE, SAID TO THE SEED'S OTHER DEVICES BY ITS NAME (iOS e451e027 giveTwinGone, 2424, OZ 1 p.9): there it is buried
     *  and its row goes; a row of Saved Messages rides at once. */
    fun gone(chat: String, mid: String, own: Boolean, at: Double) {
        if (MontanaSeed.twin == null) return
        note(chat, mid, ROW + JSONObject().put("x", 1).toString(), own, if (0 < at) at else System.currentTimeMillis() / 1000.0, saved = chat == Book.SAVED)
    }
    /** A ROW OF THE TWIN'S THAT IS NOT LAID SAYS SO (iOS 712652da twinRowDropped, 2423, OZ 1 p.1.4): the diary said «taken» for a
     *  letter whose row left here in silence. Folded: one word in half a minute for a chat. */
    private val droppedAt = HashMap<String, Long>()
    private fun rowDropped(chat: String, mid: String, why: String) {
        val now = System.currentTimeMillis()
        synchronized(droppedAt) { if (now - (droppedAt[chat] ?: 0L) < 30_000L) return; droppedAt[chat] = now }
        Log.d("Montana", "twin_row dropped chat=" + chat.take(10) + " m=" + mid.take(12) + ": " + why)
    }
    /** The word sl: a row of Saved Messages, or its file, stands on this device -- said to the seed's other devices, so the one that
     *  wrote it holds its twin's own word that the row landed, not the node's word that a letter was boxed (iOS 712652da, OZ 1 p.1.5). */
    private fun tellLanded(mid: String, file: Boolean) {
        ex.execute {
            runCatching {
                val secret = secret() ?: return@runCatching
                val me = deviceRef() ?: return@runCatching
                val what = if (file) "file" else "row"
                val o = JSONObject().put("w", "sl").put("f", Archive.writerHex() ?: "").put("m", mid).put("k", what)
                val wired = runCatching { Keeping.wire(secret, o) }.getOrDefault(false)   // the answer rides the standing channels too (iOS 2424)
                val boxed = Keeping.knock(secret, o, me)
                Log.d("Montana", "twin_saved landed here " + what + " m=" + mid.take(12) + " told=" + (if (boxed) 1 else 0) + " wire=" + (if (wired) 1 else 0))
            }.onFailure { Log.w("Montana", "twin_saved " + it.javaClass.simpleName) }
        }
    }
    /** Another device of the seed said a row of Saved Messages, or its file, stands there. */
    private fun heardLanded(o: JSONObject) {
        val f = o.optString("f"); val m = o.optString("m")
        if (f.isEmpty() || f == Archive.writerHex() || m.isEmpty()) return
        val what = if (o.optString("k") == "file") "file" else "row"
        Log.d("Montana", "twin_saved landed at=" + f.take(8) + " " + what + " m=" + m.take(12))
    }
    private fun lay(chat: String, m: Msg) {
        if (chat == Book.SAVED) Book.saved(Book.ctx)   // born here at its first row (savedBirth)
        // a row of Saved Messages born within the hour is told back to the device that wrote it (iOS 2423, OZ 1 p.1.5); the past a heal lays is not
        if (chat == Book.SAVED && System.currentTimeMillis() - m.at < 3_600_000L && Book.chat(chat)?.msgs?.none { it.mid == m.mid } != false) tellLanded(m.mid, false)
        var n = 0
        Book.edit(chat) { c ->
            if (c.msgs.none { it.mid == m.mid } && !Book.isBuried(chat, m.mid)) { c.msgs.add(m); c.msgs.sortBy { it.at } }
            n = c.msgs.size
        }
        Log.d("Montana", "twin_row chat=" + chat.take(10) + " sid=" + m.mid.take(16) + " n=" + n)
    }
}
