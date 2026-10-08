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
    private fun keys(): Keys? {
        keys?.let { return it }
        val words = MontanaSeed.mnemonic ?: return null
        if (!historyKeyKAT() || !Wire.pipeAgreesWithCanon()) {
            Log.w("Montana", "archive: the frozen vectors of the Canon do not reproduce — history is not written")
            return null
        }
        val ent = MtBindings.nativeMnemonicToEntropy(words) ?: return null
        val hk = MtBindings.nativeHistoryKey(ent) ?: return null
        val mk = MtBindings.nativeMediaKey(ent) ?: return null
        ent.fill(0)
        val master = MtBindings.nativeMnemonicToMasterSeed(words) ?: return null
        val owner = Wire.ownerSecret(master) ?: return null
        master.fill(0)
        return Keys(hk, mk, owner).also { keys = it }
    }

    /** The person left: the keys leave with them (iOS forgetKeys 348). */
    fun forgetKeys() { keys = null }

    /** This install's sixteen bytes (iOS writerTag16 438-442): local by nature, they part the nonces of one seed's devices. */
    private fun device(): ByteArray = DeviceVault.get(DEVICE)?.takeIf { it.size == 16 }
        ?: (MtBindings.nativeRandom(16) ?: ByteArray(16)).also { DeviceVault.set(DEVICE, it) }

    /** A folder's name (iOS folderLabel 639-644): SHA-256("mt-archive-label" ‖ 0 ‖ hk ‖ 0 ‖ ref)[0..16], thirty-two hex letters. */
    private fun label(k: Keys, ref: String) = Wire.hex(Wire.sha("mt-archive-label".toByteArray(), byteArrayOf(0), k.hk, byteArrayOf(0), ref.toByteArray()).copyOf(16))

    private fun conv(ref: String) = Wire.sha(ref.toByteArray())   // iOS MontanaQueueKeys.sha256(convRef)

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
        val f = m.file?.let { File(it).name } ?: return null
        val j = runCatching { JSONObject(m.text.removePrefix(Marks.MEDIA)) }.getOrNull() ?: return null
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
            append(k, ref, folder(k, ref), dir, at, text.toByteArray())
        }
    }

    /**
     * THE HEAD OF A FOLDER (iOS writeHeadIn 861-880): the pipe's secret when this phone holds it — «s:» and base64, the conversation's name
     * being sixteen bytes of its hash — else the bare name «a:»; written again only when its form changes.
     */
    private fun headIn(k: Keys, ref: String, folder: String) {
        val sec = Book.secret(ref)?.takeIf { Wire.reference(it) == ref }
        val (content, form) = if (sec != null) ("s:" + Base64.encodeToString(sec, Base64.NO_WRAP)) to "s" else ("a:" + ref) to "a"
        val heads = map(HEADS)
        if (heads.optString(folder) == form) return
        keep(HEADS, heads.put(folder, form))
        append(k, ref, folder, HEAD_DIR, System.currentTimeMillis() / 1000, content.toByteArray())
        Log.d("Montana", "archive_head form=" + form)
    }

    /** A pipe was born (iOS MTPipe 432): its folder and its head are written now, before its first letter. */
    fun head(ref: String) = q.execute { keys()?.let { k -> folder(k, ref) } }

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

    /** No conversation, no trace of it on disk (iOS deleteChatFolder 706-712). */
    fun drop(ref: String) = q.execute {
        val k = keys() ?: return@execute
        val dir = File(File(root() ?: return@execute, "Chats"), label(k, ref))
        dir.deleteRecursively()
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
