package quest.montana.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * THE ARCHIVE OF LETTERS (iOS MontanaArchive, MontanaPQ 281-1082; the map is timechain/ARCHIVE-MAP.md). Every row of the feed is
 * sealed under the history key of the seed into its conversation's log, Montana/Chats/FOLDER/conversation.mtlog, the files of the
 * media under the media key beside it; the folder's name is a keyed hash that says nothing to whoever sees it. Each folder carries
 * its head — the pipe's secret, the correspondent's name, their face — so that the 24 words alone bring the history back and the
 * conversations stay answerable. The core does every seal (mt_archive_*); this side only names, orders and asks.
 */
object Archive {
    private const val HEAD_DIR = 2   // iOS headDir 844: letters use 0 (mine) and 1 (theirs)
    private const val FACE = "face"  // iOS faceBlob 916
    private const val FOLDERS = "mt.history.folders"     // conv → folder (iOS folderMapKey 820)
    private const val HEADS = "mt.history.heads.form"    // folder → the head's form (iOS headsKey 845)
    private const val NAMES = "mt.history.nameheads"     // folder → the name sealed last (iOS nameHeadsKey 925)
    private const val DEVICE = "mt.history.device"       // this install's sixteen bytes (iOS writerTag16, local)
    private const val PUSHED = "mt.history.pushed"       // folder → the next block number the twin has not been handed (iOS cursorKey 612)
    private const val FACE_KEY = "mt.archive.face"       // the archive's face at its last whole reading (iOS archiveFaceKey; Prefs leave with the person)
    /** A TRANSCRIPT'S KEY, ONE OWNER (iOS ChatStore.transcriptHead/transcriptKey, 4c20068e): the head and the folder of a conversation
     *  nobody on this phone holds -- «Recovered history», read, never answered. */
    private const val TRANSCRIPT_HEAD = "arc:"
    fun transcriptKey(folder: String) = TRANSCRIPT_HEAD + folder
    fun isTranscript(ref: String) = ref.startsWith(TRANSCRIPT_HEAD)
    /** A ROW THE ARCHIVE GAVE BACK, ONE OWNER (iOS MTRestoredMid.archive): its mid's head. */
    private const val RESTORED_MID = "arc:"
    /** NOT-UI: a restored row's file name kept in its text while the file is not on this phone -- its seal asks its sender (Media.restoredFile). */
    const val KEPT_FILE = "kf"
    fun isRestored(mid: String) = mid.startsWith(RESTORED_MID)
    /** A ROW A CORRESPONDENT GAVE BACK, ONE OWNER (iOS MTRestoredMid.given and wire, bb0ba721): its mid's head, and the one unwrap of
     *  the wire name inside it -- the keeping and the restore read it. */
    const val GIVEN_MID = "given:"
    fun isGiven(mid: String) = mid.startsWith(GIVEN_MID)
    fun wire(mid: String) = mid.removePrefix(GIVEN_MID)
    /** A row laid back without its letter's name -- the archive's or a correspondent's (iOS MTRestoredMid.holds). */
    fun holds(mid: String) = isRestored(mid) || isGiven(mid)
    /** THE ONE NAME OF A LETTER BY ITS SECOND, ITS SIDE AND ITS WORDS (iOS ChatStore.rowSig, bb0ba721): the archive's rows, the feed
     *  measured against them and the letters a correspondent gave back are known by it. An «arc:» name hashes these bytes: they
     *  never change. */
    fun rowSig(atSec: Long, mine: Boolean, text: String) = atSec.toString() + "|" + (if (mine) 1 else 0) + "|" + text
    /** THE ARCHIVE KEY OF A ROW, ONE OWNER (iOS 5021b111 archiveKey, OZ 1 p.8): its second, its side, the hash of its words as the
     *  archive writes them -- what the twin's ask is answered by and a chat's print is made of. */
    fun archiveKey(m: Msg): String? = if (m.at <= 0) null else archivedText(m)?.let { rowSig(m.at / 1000, m.mine, rowHash(it)) }
    /** A ROW LEAVES THE HISTORY BY AN EVENT OF ITS OWN CHAIN (iOS MontanaArchive.goneHead, d37699e2, 2441; OZ 1 p.1.2, the author's
     *  words 24247, 24257, 24259 of 17.11.2026): a deletion appends a head naming the row by its archive key; the log rides to the seed's
     *  other devices as every block does, and every reading of the log folds it -- the row stands nowhere after its event. NOT-UI. */
    const val GONE_MARK = "g:"
    /** A COPY LAID THIS DEVICE'S HISTORY (iOS MontanaBackup.copyLaidKey, 2441): a device whose history a copy laid seals no past of its own. */
    const val COPY_LAID = "mt.history.copy.laid"
    /** The past was sealed into the chain once (iOS ChatStore.pastSealedKey, 2441). */
    private const val PAST_SEALED = "mt.history.past.sealed"
    /** THE ARCHIVE KEY OF A ROW BY ITS MOMENT AND ITS WORDS AS THE ARCHIVE WRITES THEM, ONE OWNER (iOS MontanaArchive.rowKey/rowParts). */
    fun rowKey(atSec: Long, mine: Boolean, archived: String) = rowSig(atSec, mine, rowHash(archived))
    /** THE CHAIN'S GONE KEYS AS READ HERE, BY CONVERSATION (iOS ChatStore.chainGone, 078d3483, 2445): held at every reading of the archive,
     *  at a deletion here and at the past sealed, so every road that lays a row asks one door (Book.rowGone). */
    private val chainGone = HashMap<String, HashSet<String>>()
    fun takeChainGone(ref: String, keys: Collection<String>) { if (keys.isNotEmpty()) synchronized(chainGone) { chainGone.getOrPut(ref) { HashSet() }.addAll(keys) } }
    fun chainNamesGone(ref: String, m: Msg): Boolean {
        val k = archiveKey(m) ?: return false
        return synchronized(chainGone) { chainGone[ref]?.contains(k) == true }
    }
    /** The rows that left, each named once in its conversation's folder; the event reaches this seed's other devices with the next blocks. */
    fun goneHead(ref: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        takeChainGone(ref, keys)
        q.execute {
            val k = keys() ?: return@execute
            val f = folder(k, ref)
            val now = System.currentTimeMillis() / 1000
            var n = 0
            for (key in keys) if (append(k, ref, f, HEAD_DIR, now, (GONE_MARK + key).toByteArray())) n++
            Log.d("Montana", "archive_gone folder=" + f.take(8) + " rows=" + n)
            if (n > 0) Twin.histSoon()   // deleted here is deleted everywhere (iOS twinSoon)
        }
    }
    /** A chat's keys, each once, in order: what its print hashes and what the word ck names. */
    fun archiveKeys(rows: List<Msg>): List<String> = rows.mapNotNull { archiveKey(it) }.toSortedSet().toList()
    private val q = Executors.newSingleThreadExecutor { r -> Thread(r, "archive").apply { isDaemon = true } }
    @Volatile private var keys: Keys? = null

    private class Keys(val hk: ByteArray, val mk: ByteArray, val owner: ByteArray)

    private fun root(): File? = runCatching { MontanaPaths.archive(Book.ctx) }.getOrNull()

    /** THE FROZEN VECTOR OF THE HISTORY KEY (iOS MontanaVault.historyKeyKAT): HKDF over 0x55 × 32, computed outside this code. */
    fun historyKeyKAT(): Boolean =
        MtBindings.nativeHistoryKey(ByteArray(32) { 0x55 })?.let { Wire.hex(it) } == "e6a7dc51003770589d9f731c1231c1523be7348c7769383875dd34bd6c578def"

    /**
     * THE KEYS, ONCE, ON THE ARCHIVE'S OWN THREAD (iOS keys 390-420): history and media from the phrase's entropy, the owner branch from
     * its seed; and none of it before the frozen vectors of the Canon reproduce — a history sealed under a wrong key is a history lost.
     */
    @Synchronized private fun keys(): Keys? {
        keys?.let { return it }
        val words = MontanaSeed.mnemonic ?: return null
        if (!historyKeyKAT() || !Wire.pipeAgreesWithCanon()) {
            Log.w("Montana", "archive: the frozen vectors of the Canon do not reproduce — history is not written")
            return null
        }
        Log.i("Montana", "canon: heal=${if (Wire.HealKeys.agree()) 1 else 0} keep=${if (KeepCopy.agrees) 1 else 0}")   // iOS audit_canon heal=, MTKeeping.agrees, beside the gate
        val ent = MtBindings.nativeMnemonicToEntropy(words) ?: return null
        val hk = MtBindings.nativeHistoryKey(ent) ?: return null
        val mk = MtBindings.nativeMediaKey(ent) ?: return null
        ent.fill(0)
        val master = MtBindings.nativeMnemonicToMasterSeed(words) ?: return null
        val owner = Wire.ownerSecret(master) ?: return null
        master.fill(0)
        return Keys(hk, mk, owner).also { keys = it }
    }

    /** The person left: the keys leave with them (iOS forgetKeys 348), and the next person's launch reads the archive afresh. */
    fun forgetKeys() { keys = null; launched = false }

    @Volatile private var launched = false

    /** The cold start's reading (iOS ChatStore 2361-2368, 2533): once a process, after the feed stands — rows go only where it has none. */
    fun atLaunch() { if (!launched) { launched = true; restoreSoon() } }
    /** A copy was just taken back (iOS restore → montanaArchiveIngested for every folder): its folders are read into the feed now, not at
     *  the next launch; before the first launch reads them all, atLaunch does it. */
    fun afterCopy() { if (launched) restoreSoon() }

    /** This install's sixteen bytes (iOS writerTag16 438-442): local by nature, they part the nonces of one seed's devices. */
    private fun device(): ByteArray = DeviceVault.get(DEVICE)?.takeIf { it.size == 16 }
        ?: (MtBindings.nativeRandom(16) ?: ByteArray(16)).also { DeviceVault.set(DEVICE, it) }

    /** A folder's name (iOS folderLabel 639-644): SHA-256("mt-archive-label" ‖ 0 ‖ hk ‖ 0 ‖ ref)[0..16], thirty-two hex letters. */
    private fun label(k: Keys, ref: String) = Wire.hex(Wire.sha("mt-archive-label".toByteArray(), byteArrayOf(0), k.hk, byteArrayOf(0), ref.toByteArray()).copyOf(16))

    private fun conv(ref: String) = Wire.sha(ref.toByteArray())   // iOS MontanaQueueKeys.sha256(convRef)

    /** The folder a conversation is filed under (iOS labelOnQueue): the map's word for it, else its label; null without the keys. */
    fun folderOf(ref: String): String? = keys()?.let { k -> map(FOLDERS).optString(Wire.hex(conv(ref))).ifEmpty { label(k, ref) } }

    private fun map(name: String): JSONObject = DeviceVault.get(name)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
    private fun keep(name: String, o: JSONObject) = DeviceVault.set(name, o.toString().toByteArray())

    /** Queue-only: the folder of a conversation, its head written the first time the folder is named (iOS resolveFolder, rememberFolder 497-504, 822-830). */
    private fun folder(k: Keys, ref: String): String {
        val f = label(k, ref)
        val m = map(FOLDERS)
        val c = Wire.hex(conv(ref))
        if (m.optString(c) != f) { keep(FOLDERS, m.put(c, f)); headIn(k, ref, f) }
        return f
    }

    private fun append(k: Keys, ref: String, folder: String, dir: Int, atSec: Long, content: ByteArray): Boolean {
        val base = root()?.path ?: return false
        return MtBindings.nativeArchiveAppend(base, folder, k.hk, k.owner, device(), conv(ref), dir, atSec, content) == 0
    }

    /**
     * WHAT A ROW IS IN THE ARCHIVE (iOS archivedText 3702-3709): a letter goes as its words; a media row as the media mark and a small
     * record — kind, the file's name, the document's name, the caption, the voice's length — keys in sorted order, as iOS encodes them.
     */
    fun archivedText(m: Msg): String? {
        if (!m.text.startsWith(Marks.MEDIA)) return m.text
        val j = runCatching { JSONObject(m.text.removePrefix(Marks.MEDIA)) }.getOrNull() ?: return null
        // a restored row without its file keeps the record's name (KEPT_FILE): its archive key is the one the twin is asked by
        val f = m.file?.let { File(it).name } ?: j.optString(KEPT_FILE).ifEmpty { null } ?: return null
        val rec = JSONObject()
        j.optString("cap").takeIf { it.isNotEmpty() }?.let { rec.put("cap", it) }
        j.optDouble("du", 0.0).takeIf { it > 0 }?.let { rec.put("d", it) }
        rec.put("f", f).put("k", j.optString("k"))
        j.optString("n").takeIf { it.isNotEmpty() }?.let { rec.put("n", it) }
        return Marks.MEDIA + rec.toString()
    }

    /** THE ONE ROAD FROM A ROW TO THE ARCHIVE (iOS archiveRow 3715-3719, archive 570-592): sealed off the screen's thread, in order. */
    fun row(ref: String, m: Msg) {
        val text = archivedText(m)?.takeIf { it.isNotEmpty() } ?: return
        val at = (if (m.at > 0) m.at else System.currentTimeMillis()) / 1000
        val dir = if (m.mine) 0 else 1
        q.execute {
            val k = keys() ?: return@execute
            if (append(k, ref, folder(k, ref), dir, at, text.toByteArray())) Twin.histSoon()   // the block reaches this seed's other devices through the box (iOS twinSoon)
        }
    }

    /**
     * THE HEAD OF A FOLDER (iOS writeHeadIn 861-880): the pipe's secret when this phone holds it — «s:» and base64, the conversation's name
     * being sixteen bytes of its hash — else the bare name «a:»; written again only when its form changes.
     */
    private fun headIn(k: Keys, ref: String, folder: String) {
        val sec = Book.secret(ref)?.takeIf { Wire.reference(it) == ref }
        // from ERASE_DAY the head carries the floor, never the secret (iOS archiveHead): a form of its own, so it is written once
        // more; a restore walks forward from that floor, so it need not follow the floor's later days
        val form = if (sec == null) "a" else if (Wire.isFloor(sec)) "f" else "s"
        val content = if (sec != null) "s:" + Base64.encodeToString(sec, Base64.NO_WRAP) else "a:" + ref
        val heads = map(HEADS)
        if (heads.optString(folder) == form) return
        keep(HEADS, heads.put(folder, form))
        append(k, ref, folder, HEAD_DIR, System.currentTimeMillis() / 1000, content.toByteArray())
        Log.d("Montana", "archive_head form=" + form)
    }

    /**
     * A pipe was born (iOS MTPipe 432): its folder and its head are written now, before its first letter. And the head again whenever
     * the pipe's form changes (iOS writeHead): the floor's head follows the secret's once, from the day the walk erased it -- the
     * folder already named, headIn alone hears the change (before 551 a floored pipe's folder kept the secret's head).
     */
    fun head(ref: String) = q.execute { keys()?.let { k -> headIn(k, ref, folder(k, ref)) } }

    /** THE PIPE CLOSED AT THE OTHER END (iOS closeHead 883-893): the folder says so, so no restore revives it from the secret it holds. */
    fun closeHead(ref: String) = q.execute {
        val k = keys() ?: return@execute
        val f = folder(k, ref)
        keep(HEADS, map(HEADS).put(f, "x"))
        append(k, ref, f, HEAD_DIR, System.currentTimeMillis() / 1000, "x:".toByteArray())
    }

    /** The correspondent's name, sealed beside their letters, written again when it changes (iOS writeNameHeadIn 936-960). */
    fun nameHead(ref: String, name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.length > 64) return
        q.execute {
            val k = keys() ?: return@execute
            val f = folder(k, ref)
            val marks = map(NAMES)
            if (marks.optString(f) == n) return@execute
            keep(NAMES, marks.put(f, n))
            append(k, ref, f, HEAD_DIR, System.currentTimeMillis() / 1000, ("n:" + n).toByteArray())
        }
    }

    /** Their face, sealed under the media key into the folder as «face» (iOS sealFace 919-923). */
    private fun faceIn(k: Keys, ref: String) {
        val bytes = Book.face(ref).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() } ?: return
        val base = root()?.path ?: return
        MtBindings.nativeArchivePutMedia(base, folder(k, ref), FACE, k.mk, k.owner, bytes)
    }

    // ── THE READING ROAD (iOS 832-1082; ChatStore restoreFromArchive 3006-3077, applyRestored 3084-3130) ──

    private class Rec(val conv: ByteArray, val dir: Int, val at: Long, val content: ByteArray)
    private class Item(val at: Long, val mine: Boolean, val text: String)

    private fun u32(d: ByteArray, o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8) or
        ((d[o + 2].toInt() and 0xff) shl 16) or ((d[o + 3].toInt() and 0xff) shl 24)

    /** The sealed blocks of one folder in file order: (u32 LE length ‖ sealed)×N, the layout the core writes (iOS readLog 964-976). */
    private fun readLog(f: File): List<ByteArray> {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return emptyList()
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= d.size) {
            val n = u32(d, i)
            i += 4
            if (n <= 0 || d.size < i + n) break
            out.add(d.copyOfRange(i, i + n))
            i += n
        }
        return out
    }

    /** One block's canon (iOS openBlock 979-1001): block_seq u64 ‖ count u32 at 8 ‖ (conv[32] ‖ dir ‖ at u64 ‖ len u32 ‖ content)×count, LE. */
    private fun opened(d: ByteArray): List<Rec> {
        if (d.size <= 12) return emptyList()
        val out = ArrayList<Rec>()
        var o = 12
        for (x in 0 until u32(d, 8)) {
            if (d.size < o + 45) break
            val conv = d.copyOfRange(o, o + 32)
            o += 32
            val dir = d[o].toInt() and 0xff
            o += 1
            var at = 0L
            for (b in 0 until 8) at = at or ((d[o + b].toLong() and 0xff) shl (8 * b))
            o += 8
            val n = u32(d, o)
            o += 4
            if (n < 0 || d.size < o + n) break
            out.add(Rec(conv, dir, at, d.copyOfRange(o, o + n)))
            o += n
        }
        return out
    }

    /**
     * The rows of a restored conversation (iOS ChatStore 3041-3066): one per signature — the second, mine or theirs, the words, joined
     * by the bar — named «arc:» and twelve bytes of its hash, read history; a media row is its record again, the file found by its name
     * in this phone's media store, if it stayed.
     */
    private fun rows(items: List<Item>): List<Msg> {
        val seen = HashSet<String>()
        val out = ArrayList<Msg>()
        for (it in items.sortedBy { it.at }) {
            val sig = rowSig(it.at, it.mine, it.text)
            if (!seen.add(sig)) continue
            val mid = RESTORED_MID + Wire.hex(Wire.sha(sig.toByteArray()).copyOf(12))
            val state = if (it.mine) 1 else 3
            if (!it.text.startsWith(Marks.MEDIA)) { out.add(Msg(mid, it.text, it.mine, it.at * 1000, state = state)); continue }
            val rec = runCatching { JSONObject(it.text.removePrefix(Marks.MEDIA)) }.getOrNull() ?: continue
            val f = rec.optString("f").ifEmpty { null } ?: continue   // a record without a file's name is not a row (iOS 3056)
            val man = JSONObject().put("k", rec.optString("k")).put("e", f.substringAfterLast('.', ""))
            rec.optString("n").takeIf { n -> n.isNotEmpty() }?.let { n -> man.put("n", n) }
            rec.optString("cap").takeIf { c -> c.isNotEmpty() }?.let { c -> man.put("cap", c) }
            rec.optDouble("d", 0.0).takeIf { d -> d > 0 }?.let { d -> man.put("du", d) }
            val file = File(Media.dir(Book.ctx), f).takeIf { x -> x.isFile }?.path
            if (file == null) man.put(KEPT_FILE, f)   // iOS keeps the row's attachment name: it is the one tie to its letter (6549e6c6)
            out.add(Msg(mid, Marks.MEDIA + man.toString(), it.mine, it.at * 1000, state = state, file = file))
        }
        return out
    }

    /**
     * THE FOLDERS A COPY LAID TWICE (the builds before 512 filed a copy's blocks under the conversation's hash, not by the map
     * absorb walks): a folder named by a hash the map files under another folder gives its blocks to that folder — the core drops
     * every block it already holds — and leaves only once each block is filed there. A folder holding anything beside its log stays.
     */
    private fun twins(k: Keys, chatsDir: File) {
        val m = map(FOLDERS)
        var healed = 0
        for (d in chatsDir.listFiles()?.filter { it.isDirectory && it.name.length == 64 } ?: return) {
            val to = m.optString(d.name).takeIf { it.isNotEmpty() && it != d.name } ?: continue
            if (!File(chatsDir, to).isDirectory || d.list()?.all { it == "conversation.mtlog" } != true) continue
            val base = root()?.path ?: return
            if (readLog(File(d, "conversation.mtlog")).all { MtBindings.nativeArchiveIngest(base, to, k.hk, k.owner, it) >= 0 }) {
                d.deleteRecursively(); healed++
            }
        }
        if (healed > 0) Log.i("Montana", "archive_twins healed=$healed")
    }

    /** Read the archive into the feed, off the screen's thread; `only` — the folders a twin's block just reached. */
    fun restoreSoon(only: List<String>? = null) = q.execute { runCatching { restore(only) }.onFailure { Log.w("Montana", "archive_restore " + it.javaClass.simpleName) } }

    /**
     * EVERY FOLDER OPENED UNDER THE SEED (iOS restore 1015-1077): the head gives the conversation back — «s:» re-establishes the pipe,
     * «x:» names it without reviving it, «a:» and a bare name of an older build name it when the book holds it, «n:» is its name; a
     * folder without a usable head is named by its blocks' own conversation (convBook). Rows go only into conversations the feed holds no row of — the feed is
     * the truth, its edits and deletions are never resurrected (iOS 3036-3043); a folder nobody can answer is «Recovered history».
     */
    private fun restore(only: List<String>?) {
        val k = keys() ?: return
        val chatsDir = File(root() ?: return, "Chats")
        if (only == null) twins(k, chatsDir)
        val folders = only ?: chatFolders(chatsDir).ifEmpty { if (chatsDir.isDirectory) emptyList() else return }
        // THE SAME ARCHIVE IS NOT READ TWICE (iOS MontanaArchive.face, ae663723, link 14244): a cold start whose archive, feed, pipe book
        // and asked rows stand as at the last whole reading would find the same gaps and build nothing, so it reads no log; any change
        // (a block written, a chat born or emptied, a pipe, a row unasked) changes the face, and the archive is read
        val face = if (only == null) face(chatsDir, folders) else null
        if (face != null && face == Prefs.str(FACE_KEY, "")) {
            Log.d("Montana", "archive_same chats=" + Book.held().size); Post.adoptRestored()
            if (eraseOrphans(k, chatsDir, folders) > 0) Prefs.setStr(FACE_KEY, face(chatsDir, chatFolders(chatsDir)))
            return
        }
        // A DELETED CHAT HOLDS NO FEED (iOS auditListAgainstFeed, 34ce186e, 2443; OZ 1 p.1, the author's words 24228, 24302: «everything
        // else -- clean it up, at the root»): a feed still standing under a deletion mark goes by the one road a deletion takes, at every
        // whole reading, before the books are counted
        if (only == null && Book.readWhole()) {
            val graveFeed = Book.held().filter { Book.erased(it) && !Book.isLocal(it) && Book.chat(it)?.msgs?.isNotEmpty() == true }
            if (graveFeed.isNotEmpty()) {
                graveFeed.forEach { forgetChat(it) }
                Log.d("Montana", "grave_feed erased=" + graveFeed.size + " keys=" + graveFeed.map { it.take(10) }.sorted().joinToString(","))
            }
        }
        val book = convBook()
        val pipesBefore = Book.refs().size
        var chats = 0; var added = 0; var headed = 0; var hs = 0; var ha = 0; var hn = 0; var faces = 0; var gapChats = 0; var gapRows = 0; var retired = 0; var goneN = 0
        // THE PAST SEALED ONCE ON THE SOURCE (iOS ChatStore.sealPast, 2441, OZ 1 p.1.2): on a device whose history no copy laid, a row its
        // archive holds and its feed does not is a row deleted before deletions were events -- named gone once, so the chain and the
        // feed are one. Only at a whole reading: a twin's block just filed is not a deletion.
        val sealing = only == null && !Prefs.bool(COPY_LAID, false) && !Prefs.bool(PAST_SEALED, false) && Book.readWhole()
        val seal = ArrayList<Pair<String, List<String>>>()
        var folded = 0
        // THE ROAD BACK FLOORS BY THE LATER DAY (iOS MTPipeBook.recoveryDay, 67332869, decision 188, E): the device's day or the trusted
        // mark M, whichever is later, so a clock behind keeps no secret a node's Date says is past its floor
        val backDay = FloorGuard.recoveryDay()
        for (folder in folders) {
            var ref: String? = null
            var named: String? = null   // the conversation the blocks name (convBook)
            var name: String? = null
            var secret: ByteArray? = null
            var closedThere = false
            val gone = HashSet<String>()
            val items = ArrayList<Item>()
            for (sealed in readLog(File(File(chatsDir, folder), "conversation.mtlog"))) {
                val plain = MtBindings.nativeArchiveOpenBlock(k.hk, k.owner, sealed) ?: continue
                for (r in opened(plain)) {
                    if (named == null) named = book[Wire.hex(r.conv)]
                    val s = String(r.content, Charsets.UTF_8)
                    if (r.dir == HEAD_DIR) {
                        when {
                            s.startsWith("s:") -> headSecret(s, backDay)?.let { secret = it; hs++ }
                            s.startsWith("x:") -> closedThere = true
                            s.startsWith("a:") -> { ha++; val n = s.drop(2); if (ref == null && (Book.secret(n) != null || Book.isLocal(n) || Groups.isKey(n))) ref = n }
                            s.startsWith("n:") -> { hn++; name = s.drop(2) }
                            s.startsWith(GONE_MARK) -> gone.add(s.drop(GONE_MARK.length))
                            ref == null && Book.secret(s) != null -> ref = s   // the head of build 1345: the bare name
                        }
                        continue
                    }
                    if (s.isNotEmpty()) items.add(Item(r.at, r.dir == 0, s))
                }
            }
            // THE CHAIN FOLDS ITS DELETIONS (iOS restore, 2441): a row the chain names gone is never laid
            if (gone.isNotEmpty()) { items.removeAll { rowKey(it.at, it.mine, it.text) in gone }; goneN += gone.size }
            secret?.let { sec ->
                ref = if (closedThere) Wire.reference(sec).also { Prefs.setBool("pipeClosed." + it, true) } else Book.establish(sec)
            }
            // No head, or none this device can use: the blocks' own name of their conversation is the answer (iOS 4c20068e)
            if (ref == null) ref = named
            // THE CHAIN'S KEYS ARE HELD FOR EVERY ROAD THAT LAYS A ROW (iOS 2445): a chat the feed does not hold yet is named too
            ref?.let { takeChainGone(it, gone) }
            // A FOLDER THAT NAMES ITS CONVERSATION HAS NO TRANSCRIPT (iOS retireTranscripts, 4c20068e; build 427 did it for the local
            // room, 470 for a group with an «a:» head): one an older pass laid for it is that conversation's second name and leaves
            if (ref != null && Book.chat(transcriptKey(folder)) != null) { Book.discard(transcriptKey(folder)); retired++ }
            // a group gone from this phone is the group store's word, its archive never brings the chat back
            if (ref != null && Groups.isKey(ref!!) && Book.chat(ref!!) == null) continue
            if (items.isEmpty() && ref == null) continue
            if (ref != null) headed++
            val key = ref ?: transcriptKey(folder)
            var feed = Book.chat(key)?.msgs?.toList().orEmpty()
            // A LETTER A CORRESPONDENT GAVE BACK STANDS IN FOR THE COPY'S OWN ROW UNTIL THE COPY COMES (iOS takeRestoredFeed, bb0ba721,
            // OZ 1 p.5.5): the light copy asks the people first and the full copy lands later. A given row known by the letter's one
            // name (rowSig) yields its place to the copy's row; a feed of given rows alone takes the copy's rows as an empty one would
            // -- here the feed is the truth, and a feed the given rows alone filled kept the copy out entirely.
            if (items.isNotEmpty() && feed.any { isGiven(it.mid) }) {
                val built = rows(items)
                val bySig = built.associateBy { rowSig(it.at / 1000, it.mine, it.text) }
                val alone = feed.all { isGiven(it.mid) }
                var yielded = 0; var laid = 0
                Book.edit(key) { c ->
                    val gone = c.msgs.filter { isGiven(it.mid) && rowSig(it.at / 1000, it.mine, it.text) in bySig }
                    val put = if (alone) built else gone.mapNotNull { bySig[rowSig(it.at / 1000, it.mine, it.text)] }
                    val goneMids = gone.mapTo(HashSet()) { it.mid }
                    c.msgs.removeAll { it.mid in goneMids }
                    val have = c.msgs.mapTo(HashSet()) { it.mid }
                    c.msgs.addAll(put.filter { it.mid !in have }); c.msgs.sortBy { it.at }
                    yielded = gone.size; laid = put.size
                }
                if (yielded > 0 || laid > 0) Log.d("Montana", "archive_given chat=" + key.take(10) + " yielded=" + yielded + " laid=" + laid + " alone=" + (if (alone) 1 else 0))
                feed = Book.chat(key)?.msgs?.toList().orEmpty()
            }
            // THE CHAIN'S DELETIONS FOLD INTO THE FEED (iOS foldGone, 2441): a row the chain names gone leaves a feed that still holds it
            // -- a copy laid before the event, or a twin's block filed later -- by the one road of a deletion, told nobody
            if (gone.isNotEmpty() && feed.isNotEmpty()) {
                val mids = feed.filter { m -> archiveKey(m)?.let { it in gone } == true }.map { it.mid }
                if (mids.isNotEmpty()) { Book.drop(key, mids, tell = false); folded += mids.size; feed = Book.chat(key)?.msgs?.toList().orEmpty() }
            }
            if (feed.isNotEmpty()) {
                // THE ARCHIVE IS MEASURED AGAINST THE FEED (iOS restoreFromArchive 3008-3035, atom 2b8069c63096): a letter the archive
                // holds and the feed does not is named with its count and its newest moment -- measured here, never inserted (the
                // feed is the truth); a media letter is matched by its second and its side, its record and its row differing in form
                fun sig(at: Long, mine: Boolean, text: String) = rowSig(at, mine, if (text.startsWith(Marks.MEDIA)) "media" else text)
                val have = feed.mapTo(HashSet()) { sig(it.at / 1000, it.mine, it.text) }
                val seen = HashSet<String>()
                val missing = items.filter { seen.add(sig(it.at, it.mine, it.text)) && sig(it.at, it.mine, it.text) !in have }
                if (missing.isNotEmpty()) {
                    gapChats++; gapRows += missing.size
                    if (sealing && ref != null) seal.add(key to missing.map { rowKey(it.at, it.mine, it.text) })
                    val newest = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(missing.maxOf { it.at } * 1000))
                    Log.d("Montana", "archive_gap chat=" + key.take(10) + " feed=" + feed.size + " archive=" + items.size + " missing=" + missing.size +
                        " media=" + missing.count { it.text.startsWith(Marks.MEDIA) } + " theirs=" + missing.count { !it.mine } + " newest=" + newest)
                    // THE SEED'S OTHER DEVICE IS ASKED FOR THESE ROWS BY THEIR NAMES (iOS MontanaArchive.askTwin, f376b4ef): it gives back
                    // what it holds, and each lands by its own birth (Twin.takeRow)
                    if (ref != null) Twin.ask(key, missing.map { Triple(it.at, it.mine, rowHash(it.text)) })
                }
                continue
            }
            // AN EMPTIED CONVERSATION STAYS EMPTY (iOS 95294cf8 at 2240): a feed whose letters were deleted here -- by me or by the
            // correspondent's word -- holds graves, and the append-only archive gave every erased letter back at the next launch; an
            // empty feed never deleted from (a chat just named by its head, a twin's history landing) still takes its transcript
            if (Book.chat(key) != null && Book.hasGraves(key)) { Log.d("Montana", "archive_skip chat=" + key.take(10) + " emptied here"); continue }
            val built = rows(items)
            if (built.isEmpty()) continue
            val shown = name?.trim()?.takeIf { it.isNotEmpty() && !Book.isOwnName(it) } ?: if (ref == null) Book.ctx.getString(R.string.recovered_history) else ""
            Book.open(key, shown, false)
            Book.edit(key) { c ->
                if (c.name.isEmpty() && shown.isNotEmpty()) c.name = shown
                if (c.msgs.isEmpty()) { c.msgs.addAll(built); c.msgs.sortBy { it.at }; c.unread = 0 }
            }
            val r0 = ref
            if (r0 != null && !Book.face(r0).isFile) {
                val base = root()?.path
                if (base != null) MtBindings.nativeArchiveGetMedia(base, folder, FACE, k.mk, k.owner)?.takeIf { it.isNotEmpty() }?.let { if (Book.setFace(r0, it)) faces++ }
            }
            chats++; added += built.size
        }
        // A FACE THE ARCHIVE HOLDS FOR MORE THAN ONE CORRESPONDENT NAMES NONE OF THEM (iOS restore, 4a24b66c): a copy an older build
        // sealed stays out of the book, and its true owner is asked for it
        if (0 < faces) Book.dropSharedFaces()
        if (0 < retired) Log.d("Montana", "archive_named transcripts=" + retired)
        if (0 < gapRows) Log.d("Montana", "archive_gap total chats=" + gapChats + " rows=" + gapRows)
        if (0 < goneN) Log.d("Montana", "archive_gone folded=" + goneN + " feed rows=" + folded)
        if (sealing) {
            Prefs.setBool(PAST_SEALED, true)
            for ((c, ks) in seal) goneHead(c, ks)
            Log.d("Montana", "archive_gone past sealed rows=" + seal.sumOf { it.second.size } + " chats=" + seal.size)
        }
        Post.adoptRestored()   // the pipes stand: a laid copy's letters join the queue only now (iOS afterPipes → adoptRestored, cc147308)
        if (Book.refs().size > pipesBefore) {
            Presence.peersGrew()   // the grown book is greeted and asked for its faces at once (iOS 3ed661fa)
            Book.redraw()   // the rows of the pipes just re-established are drawn now (iOS afterPipes objectWillChange, 2452)
        }
        Log.d("Montana", "archive_restore chats=" + chats + " rows=" + added + " headed=" + headed + " heads=s" + hs + "/a" + ha + "/n" + hn + " faces=" + faces)
        if (face != null) Prefs.setStr(FACE_KEY, if (eraseOrphans(k, chatsDir, folders) > 0) face(chatsDir, chatFolders(chatsDir)) else face)
    }

    private fun chatFolders(chatsDir: File): List<String> = chatsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }.orEmpty()

    /** A folder's name as the archive gives it: 32 lowercase hex digits (iOS MontanaArchive.isLabel). */
    private fun isLabel(n: String) = n.length == 32 && n.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * NO FOLDER OUTLIVES ITS CONVERSATION (iOS MontanaArchive.eraseOrphanFolders + the list audit's orphans, 74c5d1fd, 2440; OZ 1 p.1.1,
     * the author's word 24228, 17.11.2026): a folder under Chats that no conversation of this phone answers for -- a chat of the book,
     * a pipe, a group, a recovered transcript, Saved Messages -- is erased with its marks at the whole reading of the archive, so the
     * other devices' blocks are never filed into it again and no light copy carries it on. Only with the keys warm and both shelves
     * read whole: a chat that did not open would name its live folder an orphan. A folder whose log was written in the last ten
     * minutes is spared -- a first letter's row, or a copy being laid, may be on its way; the next pass takes it. Sealed labels only.
     * Runs on the archive's queue (restore).
     */
    private fun eraseOrphans(k: Keys, chatsDir: File, folders: List<String>): Int {
        val labels = folders.filter { isLabel(it) }
        if (labels.isEmpty() || !Book.readWhole() || !Groups.readWhole()) return 0
        val keysNamed = LinkedHashSet<String>()
        keysNamed.addAll(Book.held()); keysNamed.addAll(Book.refs()); keysNamed.addAll(Groups.keys()); keysNamed.add(Book.SAVED)
        for (r in keysNamed.toList()) keysNamed.addAll(SamePair.folded(r))
        val m = map(FOLDERS)
        val named = HashSet<String>()
        for (c in keysNamed) {
            if (c.isEmpty()) continue
            if (isTranscript(c)) { named.add(c.removePrefix(TRANSCRIPT_HEAD)); continue }
            named.add(label(k, c)); m.optString(Wire.hex(conv(c))).takeIf { it.isNotEmpty() }?.let(named::add)
        }
        val now = System.currentTimeMillis()
        var gone = 0; var spared = 0
        for (f in labels.sorted()) {
            if (f in named) continue
            val dir = File(chatsDir, f)
            val log = File(dir, "conversation.mtlog")
            if (log.isFile && now - log.lastModified() < 600_000L) { spared++; continue }
            if (!dir.deleteRecursively()) continue
            forgetFolderMarks(null, f)
            gone++
        }
        if (0 < gone + spared) Log.i("Montana", "archive_orphans erased=$gone spared=$spared")
        return gone
    }

    /**
     * THE FOLDER'S MARKS GO WITH THE FOLDER (iOS forgetFolderMarks, the critic B-3 11.10.2026; 2440: an orphan is known by its folder
     * alone): the head, name-head, folder-map and cursor marks say «this folder already holds it», and outliving the directory a folder
     * born again for the same conversation never got its head again. Queue-only, beside the removal.
     */
    private fun forgetFolderMarks(ref: String?, folder: String) {
        for (n in listOf(HEADS, NAMES, PUSHED, BOXED)) { val o = map(n); if (o.has(folder)) { o.remove(folder); keep(n, o) } }
        val m = map(FOLDERS)
        val drop = ArrayList<String>()
        ref?.let { drop.add(Wire.hex(conv(it))) }
        m.keys().forEach { c -> if (m.optString(c) == folder) drop.add(c) }
        if (drop.count { m.remove(it) != null } > 0) keep(FOLDERS, m)
        Log.d("Montana", "archive_forget folder=" + folder.take(8) + " marks cleared")
    }

    /**
     * THE CONVERSATION A FOLDER IS, ONE OWNER (iOS MontanaArchive.convBook, 4c20068e): every block names its conversation by the hash
     * of its reference (append: conv), matched against every conversation this phone holds -- its pipes, its feed, its groups, its
     * own Saved Messages. The book of the pipes' folder labels stood here: a group's folder matched none and came back as a second
     * chat, «Recovered history», beside the group. A transcript is only a folder whose conversation nobody here holds.
     */
    private fun convBook(): HashMap<String, String> {
        val all = LinkedHashSet<String>()
        all.addAll(Book.refs()); all.addAll(Book.held()); all.addAll(Groups.keys()); all.add(Book.SAVED)
        synchronized(copyHeld) { all.addAll(copyHeld) }
        val m = HashMap<String, String>()
        for (c in all) if (c.isNotEmpty() && !isTranscript(c)) m[Wire.hex(conv(c))] = c
        return m
    }

    /**
     * THE ARCHIVE'S FACE (iOS MontanaArchive.face, ae663723 + 5e69054f): each log's folder, size and last write, with the feed's chats,
     * the pipe count, the asked rows and the day the road back floors by, in one hash — a stat per folder, never a byte of a log. It is
     * asked only once the keys stand (restore returns without them), so a reading that could open nothing is never remembered as read.
     */
    private fun face(chatsDir: File, folders: List<String>): String {
        val parts = arrayListOf("feed=" + Book.held().sorted().joinToString(","), "pipes=" + Book.refs().size,
            "asked=" + Twin.askedCount(), "day=" + FloorGuard.recoveryDay(), "held=" + Groups.keys().size, "chain=g2")
        for (f in folders.sorted()) {
            val log = File(File(chatsDir, f), "conversation.mtlog")
            parts.add(f + ":" + (if (log.isFile) log.length() else -1) + ":" + log.lastModified())
        }
        return Wire.hex(java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray()))
    }

    // ── HISTORY ACROSS A PERSON'S OWN DEVICES (iOS 595-769) ──

    /** The history key, for the pipe only this seed's devices derive (Twin.secret, iOS twinSecret). */
    fun historyKey(): ByteArray? = keys()?.hk
    /** This install's writer tag in hex: a letter of the seed's pipe names the device that laid it (iOS writerTag().montanaHexString). */
    fun writerHex(): String? = MtBindings.nativeWriterTag(device())?.let { Wire.hex(it) }
    /** This device's own name among the seed's devices (iOS E2E.deviceTag): local, never a quantity of the protocol. */
    fun deviceTag(): String = "and-" + Wire.hex(Wire.sha("mt-device-tag".toByteArray(), byteArrayOf(0), device())).take(12)
    /** The archive's key of a row's words, the same on every device of the seed (iOS MontanaArchive.rowHash). */
    fun rowHash(archived: String): String = Wire.hex(Wire.sha(archived.toByteArray()).copyOf(8))

    /**
     * THE FEED'S ROWS IN THE DIARY, AND ITS PRINT (iOS traceRows, c22a497d at 2418, OZ 1 p.4 and p.7): on every opening the count, the
     * tail -- each row by its last eight signs and its kind read from its file (img, vid, aud, doc; «!» when the file is not here) --
     * and the print: the hash of the feed's archive keys (second, side, the hash of the words), the same on every device of the seed
     * for the same rows: two devices that show one chat alike write one print.
     */
    fun traceRows(ref: String) {
        val a = runCatching { Book.chat(ref)?.msgs?.toList() }.getOrNull() ?: return
        q.execute {
            val tail = a.takeLast(6).joinToString(",") { m ->
                val id = m.mid.takeLast(8)
                if (!m.text.startsWith(Marks.MEDIA)) return@joinToString "$id:text"
                val k = runCatching { JSONObject(m.text.removePrefix(Marks.MEDIA)).optString("k") }.getOrNull().orEmpty().ifEmpty { "media" }
                id + ":" + k + (if (m.file?.let { File(it).exists() } == true) "" else "!")
            }
            val keys = a.mapNotNull { m -> archivedText(m)?.let { t -> (m.at / 1000).toString() + (if (m.mine) "1" else "0") + rowHash(t) } }
            val print = rowHash(keys.sorted().joinToString(","))
            Log.i("Montana", "chat_rows key=${ref.take(10)} n=${a.size} keys=${keys.size} print=$print tail=[$tail]")
        }
    }

    private const val BOXED = "mt.history.boxed"   // folder → the first block not yet laid for the other devices (iOS boxedKey)
    private const val TWIN_CAP = 8 shl 20           // one letter's file stays small; what is left rides the next round (iOS twinCap)
    /** What the box road gathers for the seed's other devices (iOS layForTwins): length-framed blocks, each folder's top, more left. */
    class Gathered(val blob: ByteArray, val tops: Map<String, Long>, val n: Int, val more: Boolean)
    /** `want`: another device named its cursor (iOS layForTwins(from:), 2403) -- this device's blocks are gathered from there, not
     *  from the boxed cursor. Every letter opens with the record of where its runs start (runsRecord). */
    fun twinBlocks(want: Map<String, Long>? = null): Gathered? = q.submit<Gathered?> {
        keys() ?: return@submit null
        val wt = MtBindings.nativeWriterTag(device()) ?: return@submit null
        val base = root()?.path ?: return@submit null
        val cursor = map(BOXED)
        val blob = java.io.ByteArrayOutputStream()
        val tops = HashMap<String, Long>()
        val starts = HashMap<String, Long>()
        var n = 0; var more = false
        for (chat in File(base, "Chats").listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()) {
            if (TWIN_CAP < blob.size()) { more = true; break }
            val from = if (want != null) want[chat] ?: 0L else cursor.optLong(chat, 0L)
            val stream = MtBindings.nativeArchiveExport(base, chat, wt, from) ?: continue
            var top = from
            var i = 0
            while (i + 4 <= stream.size) {
                val len = u32(stream, i)
                if (len <= 0 || stream.size < i + 4 + len) break
                val sealed = stream.copyOfRange(i + 4, i + 4 + len)
                val id = MtBindings.nativeArchiveBlockId(sealed)?.takeIf { it.size == 12 }
                i += 4 + len
                id ?: continue
                var seq = 0L
                for (b in 0 until 8) seq = seq or ((id[4 + b].toLong() and 0xff) shl (8 * b))
                blob.write(stream, i - 4 - len, 4 + len)
                top = maxOf(top, seq + 1); n++
            }
            if (top != from) { tops[chat] = top; starts[chat] = from }
        }
        Gathered(runsRecord(starts) + blob.toByteArray(), tops, n, more)
    }.get()
    /** The folders this device's archive holds (iOS conversations()). */
    fun folders(): List<String> = q.submit<List<String>> {
        val base = root()?.path ?: return@submit emptyList()
        File(base, "Chats").listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
    }.get()

    // ── THE CURSOR OF HISTORY STANDS WITH THE RECEIVER (iOS f339b7a3, 2403, OZ 1 p.2; the author's words 22583 and 22631) ──
    // The giver's cursor (BOXED) moved when a node boxed the letter, and a box keeps a day and the pipe's last 64 letters: a device
    // away longer lost those blocks for good, the head of a pipe among them. Now each device keeps, per folder and per writer, the
    // next block it lacks, and names it to the others in one word (hw) at its pickups; each giver lays its own blocks from there.
    // The cursor moves only over a whole run (runsRecord), never by the highest number heard. A block is immutable and named by its
    // writer and number, so a repeat is filed as held and changes nothing.
    private const val HELD = "mt.history.held"   // NOT-UI: {folder: {writer tag: the next block this device lacks}}
    private val heldLock = Any()
    fun forgetHeld() = synchronized(heldLock) { DeviceVault.delete(HELD) }
    /** This device's cursor, as the «hw» word names it. */
    fun held(): JSONObject = synchronized(heldLock) { map(HELD) }
    /** A block's writer tag (hex, the «f» of a history letter) and its number in that writer's log (iOS blockMark). */
    private fun blockMark(sealed: ByteArray): Pair<String, Long>? {
        val id = MtBindings.nativeArchiveBlockId(sealed)?.takeIf { it.size == 12 } ?: return null
        var seq = 0L
        for (b in 0 until 8) seq = seq or ((id[4 + b].toLong() and 0xff) shl (8 * b))
        return Wire.hex(id.copyOf(4)) to seq
    }
    /** A block filed whole inside a run that starts at or below the cursor moves the cursor past it (iOS noteHeld). */
    fun noteHeld(folder: String, sealed: ByteArray, runFrom: Long) {
        val (w, seq) = blockMark(sealed) ?: return
        synchronized(heldLock) {
            val m = map(HELD)
            val f = m.optJSONObject(folder) ?: JSONObject()
            val at = f.optLong(w, 0L)
            if (runFrom > at || at > seq) return
            f.put(w, seq + 1); m.put(folder, f)
            keep(HELD, m)
        }
    }
    /**
     * THE RUNS OF A LETTER (iOS runsRecord / runsOf, Agent 1220): a block's number is counted per device across every folder, so within
     * one folder the numbers skip by nature and a hole cannot be read off them. Each «hist» letter opens with one record naming, per
     * folder, the number its run starts from; the receiver moves its cursor only over a run that starts at or below it. An older build
     * opens the record as a block of nobody's history and passes it by.
     */
    private val RUNS = "mt-twin-runs".toByteArray() + byteArrayOf(0)
    private fun framed(d: ByteArray): ByteArray = byteArrayOf(d.size.toByte(), (d.size shr 8).toByte(), (d.size shr 16).toByte(), (d.size shr 24).toByte()) + d
    private fun runsRecord(starts: Map<String, Long>): ByteArray =
        if (starts.isEmpty()) ByteArray(0) else framed(RUNS + JSONObject(starts as Map<*, *>).toString().toByteArray())
    fun runsOf(record: ByteArray): Map<String, Long>? {
        if (record.size < RUNS.size || !record.copyOf(RUNS.size).contentEquals(RUNS)) return null
        val o = runCatching { JSONObject(String(record, RUNS.size, record.size - RUNS.size, Charsets.UTF_8)) }.getOrNull() ?: return null
        return o.keys().asSequence().associateWith { o.optLong(it, 0L) }
    }
    /** The blocks rode: the cursor moves past them (iOS layForTwins, after the knock). */
    fun twinBoxed(tops: Map<String, Long>) = q.execute {
        val cursor = map(BOXED)
        for ((k, v) in tops) cursor.put(k, maxOf(cursor.optLong(k, 0L), v))
        keep(BOXED, cursor)
    }

    /**
     * Every block this device wrote and the twin has not been handed (iOS replicate 721-745): each folder's blocks of this writer from the
     * cursor on, each by its own wire name — the digest a media piece is named by, so the shape of what travels says nothing of what it
     * is — down the channel that stands with the person's own reference; the cursor moves past what the channel took.
     */
    fun replicate() = q.execute {
        val twin = MontanaSeed.twin ?: return@execute
        keys() ?: return@execute
        val wt = MtBindings.nativeWriterTag(device()) ?: return@execute
        val base = root()?.path ?: return@execute
        val cursor = map(PUSHED)
        var sent = 0
        for (chat in File(base, "Chats").listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()) {
            val from = cursor.optLong(chat, 0L)
            val stream = MtBindings.nativeArchiveExport(base, chat, wt, from) ?: continue
            var top = from
            var i = 0
            while (i + 4 <= stream.size) {
                val n = u32(stream, i)
                i += 4
                if (n <= 0 || stream.size < i + n) break
                val sealed = stream.copyOfRange(i, i + n)
                i += n
                val id = MtBindings.nativeArchiveBlockId(sealed)?.takeIf { it.size == 12 } ?: continue
                var seq = 0L
                for (b in 0 until 8) seq = seq or ((id[4 + b].toLong() and 0xff) shl (8 * b))
                if (Channels.sendBlob(twin, Wire.hex(Wire.sha(sealed)), sealed)) { sent++; top = maxOf(top, seq + 1) }
            }
            if (top != from) cursor.put(chat, top)
        }
        keep(PUSHED, cursor)
        if (sent > 0) Log.d("Montana", "history_push blocks=" + sent)
    }

    /**
     * A sealed piece arrived (iOS absorb 750-769): if it opens under this person's history key it is a block of their own history — filed
     * by the core, which drops a block it already holds, into the folder its conversation is filed under, and that folder is read
     * into the feed again. False: the piece belongs to another road and is left untouched.
     */
    @Volatile private var refusedAt = 0L
    fun absorb(sealed: ByteArray): Boolean = fileBlock(sealed).first
    /**
     * A BLOCK OF THE PERSON'S OWN COPY NAMES ITS CONVERSATION BY ITS OWN HEAD (iOS absorbFromCopy, 21f1a6b8 at 2452, OZ 1 p.1.3): the
     * pipe that makes a conversation held is re-established only from its folder's head, and the head rode in the very block refused,
     * so every person's chat of a restored copy was taken unfiled. On the copy's road alone (never the twin's, which carries other
     * lives' chats) a head whose pipe is this block's conversation makes it held, and the block files under its label. False: no
     * conversation held here and no head of its own yet -- the copy files it once more at its end (Backup.finish).
     */
    fun absorbFromCopy(sealed: ByteArray): Boolean {
        keys()?.let { k -> headRef(k, sealed)?.let { synchronized(copyHeld) { copyHeld.add(it) } } }
        return fileBlock(sealed).second != null
    }
    /** The conversations a copy's own heads made held this run (iOS heldHint at absorbFromCopy): convBook names them. */
    private val copyHeld = HashSet<String>()
    /** A head's pipe, read on the archive's day (iOS headPipe): the one reading of an «s:» head for a restore and for a copy's block. */
    private fun headSecret(s: String, day: Long): ByteArray? = headPipe(s)?.let { Wire.floored(it, day) }
    /** The conversation a block's own pipe head names, when it is the block's conversation (iOS headRef). */
    private fun headRef(k: Keys, sealed: ByteArray): String? {
        val conv = MtBindings.nativeArchivePeekConv(k.hk, k.owner, sealed) ?: return null
        val day = FloorGuard.recoveryDay()
        for (r in MtBindings.nativeArchiveOpenBlock(k.hk, k.owner, sealed)?.let(::opened) ?: emptyList()) {
            if (r.dir != HEAD_DIR || !r.conv.contentEquals(conv)) continue
            val ref = headSecret(String(r.content, Charsets.UTF_8), day)?.let(Wire::reference) ?: continue
            if (conv(ref).contentEquals(conv)) return ref
        }
        return null
    }
    /** The filing itself, with the folder a block of this person's history now stands in (null when it was taken unfiled; iOS fileBlock). */
    fun fileBlock(sealed: ByteArray): Pair<Boolean, String?> {
        val k = keys() ?: return false to null
        val conv = MtBindings.nativeArchivePeekConv(k.hk, k.owner, sealed) ?: return false to null
        val c = Wire.hex(conv)
        // A BLOCK OF A CONVERSATION ERASED HERE IS TAKEN AND NOT FILED (iOS absorb deletedHere, 44f44060; the critic B-4): my other
        // device's blocks, laid before it heard of the deletion, came back by the twin's road and stood the conversation again
        if (Book.erasedHere(c)) {
            val now = System.currentTimeMillis()
            if (now - refusedAt >= 60_000) { refusedAt = now; Log.d("Montana", "archive_refused a block of a deleted conversation is not filed") }
            return true to null
        }
        // A HEAD WITH THE ESTABLISHED SECRET IS NOT FILED FROM THE FLOOR DAY (iOS absorb, bf55cd1a, decision 191): the twin's copy of an
        // old head would lay the secret under the seed again after this phone's pass took it out (dropSecretHeads)
        if (Book.floorDay() >= Wire.ERASE_DAY && onlySecretHeads(MtBindings.nativeArchiveOpenBlock(k.hk, k.owner, sealed)?.let(::opened) ?: emptyList())) {
            val now = System.currentTimeMillis()
            if (now - refusedAt >= 60_000) { refusedAt = now; Log.d("Montana", "archive_refused a head with the established secret is not filed from the floor day") }
            return true to null
        }
        // A BLOCK FILES ONLY UNDER A CONVERSATION THIS DEVICE HOLDS (iOS fileBlock, 17b1f7c4 at 2446, OZ 1 p.1.3): the twin's blocks of
        // other lives' chats were filed under the chat's hash -- folders no row answered for. A conversation the book names (its pipe,
        // a held chat, a group, Saved Messages) files under its label; a block of no conversation held here is taken unfiled.
        val folder = map(FOLDERS).optString(c).ifEmpty {
            val ref = convBook()[c]
            if (ref == null) {
                val now = System.currentTimeMillis()
                if (now - refusedAt >= 60_000) { refusedAt = now; Log.d("Montana", "archive_refused a block of no conversation held here is not filed") }
                return true to null
            }
            q.execute { keys()?.let { kk -> folder(kk, ref) } }
            label(k, ref)
        }
        val base = root()?.path ?: return false to null
        val rc = synchronized(logLock) { MtBindings.nativeArchiveIngest(base, folder, k.hk, k.owner, sealed) }
        if (rc == 1) { Log.d("Montana", "history_rx block filed"); restoreSoon(listOf(folder)) }
        return (rc >= 0) to (if (rc >= 0) folder else null)
    }

    // ── THE SECRET LEAVES THE ARCHIVE ON THE FLOOR DAY (iOS MontanaArchive.dropSecretHeads, bf55cd1a and 67332869, decision 191) ──
    // Every head written before ERASE_DAY carries the established secret («s:» and its thirty-two bytes), and a folder's log is only
    // ever appended to. From the floor day the archive holds the floor, never the secret: every folder's log is rewritten once without
    // those blocks. A folder whose last secret head has no floor head after it gets the floor of that very head in its place, so the
    // last head still decides at a restore as before; nothing else in the log moves. The twin's copy is cleaned by its own pass, and a
    // secret head it still sends is not filed here (absorb).
    private const val PURGED = "archiveSecretHeadsPurged"   // this seat's archive is through the pass (iOS purgedKey; never a copy's)
    private val logLock = Any()                             // the rewrite against absorb, the one writer off the queue
    @Volatile private var purgeAsked = 0L                   // the floor day this run last asked the pass on: once a day at most

    private fun headPipe(head: String): ByteArray? =
        if (!head.startsWith("s:")) null else runCatching { Base64.decode(head.drop(2), Base64.DEFAULT) }.getOrNull()?.takeIf { Wire.isWhole(it) }
    /** Whether a block is nothing but heads carrying the established secret (iOS MTPipeKey.onlySecretHeads, headHoldsSecret). */
    private fun onlySecretHeads(rs: List<Rec>) =
        rs.isNotEmpty() && rs.all { it.dir == HEAD_DIR && headPipe(String(it.content, Charsets.UTF_8))?.let(Wire::holdsOrigin) == true }

    /** `day`: the day the book floored by (Book.floorDay), from ERASE_DAY. */
    fun dropSecretHeads(day: Long) {
        if (day < Wire.ERASE_DAY || purgeAsked == day) return
        purgeAsked = day
        q.execute { runCatching { dropSecretHeadsIn(day) }.onFailure { Log.w("Montana", "archive_secret_heads " + it.javaClass.simpleName) } }
    }
    /** The seat leaves: the next person's archive is passed again (iOS SeedScope.seatKeys). */
    fun forgetPurge() { purgeAsked = 0L; DeviceVault.delete(PURGED); synchronized(copyHeld) { copyHeld.clear() } }

    /** Queue-only: the pass, once per archive from the floor day (again after a seat change brings an older archive). */
    private fun dropSecretHeadsIn(day: Long) {
        if (DeviceVault.has(PURGED)) return
        val k = keys() ?: run { purgeAsked = 0L; return }   // no seed at hand yet: the next read asks again
        val chatsDir = File(root() ?: run { purgeAsked = 0L; return }, "Chats")
        var cut = 0; var floored = 0; var folders = 0; var failed = 0
        for (dir in chatsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()) {
            val r = synchronized(logLock) { purgeFolder(k, File(dir, "conversation.mtlog"), day) }
            if (r == null) { failed++; continue }
            if (r[0] > 0) { folders++; cut += r[0]; floored += r[1] }
        }
        if (failed == 0) DeviceVault.set(PURGED, day.toString().toByteArray())
        Log.d("Montana", "archive_secret_heads folders=$folders cut=$cut floored=$floored failed=$failed")
    }

    /** One folder's log (queue and logLock): [blocks cut, floor heads laid], or null when it must be passed again at the next start. */
    private fun purgeFolder(k: Keys, log: File, day: Long): IntArray? {
        val data = runCatching { log.readBytes() }.getOrNull() ?: return intArrayOf(0, 0)
        // the frames in file order: (u32 LE length ‖ sealed), as readLog reads them; a torn tail is kept as it lay
        val frames = ArrayList<Pair<ByteArray, List<Rec>>>()
        var i = 0
        while (i + 4 <= data.size) {
            val n = u32(data, i)
            if (n <= 0 || data.size < i + 4 + n) break
            val rs = MtBindings.nativeArchiveOpenBlock(k.hk, k.owner, data.copyOfRange(i + 4, i + 4 + n))?.let(::opened) ?: emptyList()
            frames.add(data.copyOfRange(i, i + 4 + n) to rs)
            i += 4 + n
        }
        val tail = data.copyOfRange(i, data.size)
        // 1 a block of secret heads, 2 one with a floor head, 0 anything else (iOS MTPipeKey.LogFrame, purge)
        val kinds = frames.map { (_, rs) ->
            when {
                onlySecretHeads(rs) -> 1
                rs.any { it.dir == HEAD_DIR && headPipe(String(it.content, Charsets.UTF_8))?.let(Wire::isFloor) == true } -> 2
                else -> 0
            }
        }
        val last = kinds.lastIndexOf(1)
        if (last < 0) return intArrayOf(0, 0)
        var put: ByteArray? = null
        if (2 !in kinds.subList(last + 1, kinds.size)) {
            // the floor head could not be made or laid: the old head stays rather than leave the folder with no head, and the pass
            // is not marked done -- the next start tries again
            val head = frames[last].second.first()
            val floor = headPipe(String(head.content, Charsets.UTF_8))?.let { Wire.floored(it, day) }?.takeIf { Wire.isFloor(it) } ?: return null
            val base = root()?.path ?: return null
            val content = ("s:" + Base64.encodeToString(floor, Base64.NO_WRAP)).toByteArray()
            if (MtBindings.nativeArchiveAppend(base, log.parentFile!!.name, k.hk, k.owner, device(), head.conv, HEAD_DIR, System.currentTimeMillis() / 1000, content) != 0) return null
            // the block just appended is the log's last frame: the queue and the lock let no other writer in between
            val after = runCatching { log.readBytes() }.getOrNull() ?: return null
            if (after.size <= data.size || !after.copyOf(data.size).contentEquals(data)) return null
            put = after.copyOfRange(data.size, after.size)
        }
        val out = java.io.ByteArrayOutputStream(data.size)
        var cut = 0
        frames.forEachIndexed { x, (frame, _) ->
            if (kinds[x] == 1) { cut++; if (x == last) put?.let(out::write) } else out.write(frame)
        }
        out.write(tail)
        val tmp = File(log.parentFile, log.name + ".purge")
        val ok = DiskDoor.write(tmp, out.toByteArray(), "archive-purge") && tmp.renameTo(log)
        if (!ok) { tmp.delete(); return null }
        return intArrayOf(cut, if (put != null) 1 else 0)
    }

    /** No conversation, no trace of it on disk (iOS deleteChatFolder 706-712). */
    fun drop(ref: String) = q.execute {
        val k = keys() ?: return@execute
        val folder = map(FOLDERS).optString(Wire.hex(conv(ref))).ifEmpty { label(k, ref) }
        File(File(root() ?: return@execute, "Chats"), folder).deleteRecursively()
        forgetFolderMarks(ref, folder)
    }

    /**
     * THE DOOR OUT SEALS EVERYTHING THE SEED CAN REOPEN (iOS sealForForget 3721-3745; the author's word 07.09: «everything stored locally
     * and restored exactly on leaving without deleting the app»): every conversation's head, name and face into its folder, on disk
     * before the seed leaves; `then` follows on the main thread once the queue is through.
     */
    fun sealForForget(then: () -> Unit) {
        if (MontanaSeed.mnemonic == null) { Log.d("Montana", "forget_seal sealed=0 — no seed at hand"); then(); return }
        val chats = Book.all().filter { Book.secret(it.ref) != null }.map { it.ref to it.name }
        q.execute {
            val k = keys()
            if (k != null) for ((ref, name) in chats) {
                folder(k, ref)
                if (name.isNotBlank()) nameHead(ref, name)
                faceIn(k, ref)
            }
            q.execute { Log.d("Montana", "forget_seal sealed=" + (if (k != null) 1 else 0) + " held=" + chats.size); MainThread.post(then) }
        }
    }
}
