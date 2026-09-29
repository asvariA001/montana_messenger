package quest.montana.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// THE COPY IS THE ARCHIVE UNDER ONE MORE SEAL (iOS MontanaBackup, 23.09). This file is its way BACK: a copy made on
// any Montana (an iPhone, or this phone later) is opened with the 24 words and laid into this device. It merges, it
// never erases.
//
// THE CONTAINER, version 2, byte for byte:
//   0  32  salt. No mark of its own: without the words the file is noise.
//  32  ..  frames, every one exactly 1 MiB: ChaChaPoly combined (nonce 12, ciphertext, tag 16)
//   backup_key = HKDF-SHA-256(ikm = entropy 32, salt = salt 32, info = "mt-backup-key-v2", 32)
//   AAD of frame i = the salt, u64 LE i, and u8 1 on the final frame (0 on every other).
//   The frames' plaintexts, joined, are ONE stream of records -- u8 kind, u8 more, u16 LE nameLen, name, u32 LE
//   dataLen, data. Kind 1 a sealed history block, 2 a piece of an archive attachment (folder/blob), 3 the account
//   card, 4 a file of the correspondence store, 5 a file of one of the other places (place/file), 6 a piece of the
//   feed, 0 the tail: the count and the SHA-256 of every record before it.
// Version 1 -- magic "MTBAK" 0x00, version 1, kdf 1, salt 32, chunk size u64; then u32 LE lengths, each before one
// record sealed alone, AAD = that 48-byte header and u64 LE index, info "mt-backup-key-v1" -- is still read.

/** What a copy held — COUNTED here, never promised (iOS MontanaBackup.Tally). */
data class Tally(var chats: Int = 0, var records: Int = 0, var media: Int = 0, var bytes: Int = 0, var skipped: Int = 0)

/** Why a copy did not come back. Every one is spoken to the person (iOS MontanaBackup.Refusal). */
sealed class Refusal {
    object NoSeed : Refusal()
    object NoVault : Refusal()
    object Empty : Refusal()
    object NotOurs : Refusal()
    object Version : Refusal()
    object WrongWords : Refusal()
    object Unopened : Refusal()
    object Torn : Refusal()
    object NoSpace : Refusal()
    object Stopped : Refusal()
    class DiskRefused(val why: String) : Refusal()
    class CloudRefused(val why: String) : Refusal()

    /** One reading for every keeper (iOS Refusal.spoken(by: .node)). */
    fun spoken(c: Context): String = when (this) {
        Empty -> c.getString(R.string.node_empty)
        is CloudRefused -> c.getString(R.string.node_refused, why)
        Stopped -> c.getString(R.string.copy_stopped)
        NoSeed -> c.getString(R.string.copy_no_seed)
        NoVault -> c.getString(R.string.copy_no_vault)
        NoSpace -> c.getString(R.string.copy_no_space)
        is DiskRefused -> c.getString(R.string.copy_disk_refused, why)
        NotOurs -> c.getString(R.string.copy_not_ours)
        Version -> c.getString(R.string.copy_version)
        WrongWords -> c.getString(R.string.copy_wrong_words)
        Unopened -> c.getString(R.string.copy_unopened)
        Torn -> c.getString(R.string.copy_torn)
    }
}

sealed class CopyResult {
    class Taken(val tally: Tally) : CopyResult()
    class Refused(val why: Refusal) : CopyResult()
}

/** The folders a copy lays into — the iOS names, under this app's own private storage (Documents / Application Support). */
object MontanaPaths {
    fun archive(c: Context) = File(c.filesDir, "Montana")                  // iOS Documents/Montana (archive.rs root)
    fun chats(c: Context) = File(archive(c), "Chats")                      // = archive.rs CHATS_DIR
    private fun support(c: Context) = File(c.filesDir, "Support")          // iOS Application Support
    fun store(c: Context) = File(support(c), "Montana/Media")              // iOS MontanaMediaStore.dir
    /** The person's other places (iOS MontanaBackup.places), each named once. */
    fun places(c: Context): Map<String, File> = mapOf(
        "avatars" to File(support(c), "Avatars"),
        "wallpapers" to File(support(c), "Wallpapers"),
        "stories" to File(c.filesDir, "stories"),
        "pictures" to File(support(c), "Montana/Pictures"))
    /** What came back and has no owner on this phone yet (the card, the feed), sealed under the device key. */
    fun taken(c: Context) = File(c.noBackupFilesDir, "MontanaTaken")
}

object MontanaBackup {
    private const val EXT = "mtbak"                       // NOT-UI: the container's own extension
    private const val CHUNK = 1 shl 20
    private const val FRAME = 1 shl 20
    private const val SALT = 32
    private const val HEADER = 48
    private val magic = byteArrayOf(0x4D, 0x54, 0x42, 0x41, 0x4B, 0x00)
    private val labelV1 = "mt-backup-key-v1".toByteArray()   // NOT-UI: the derivation's own label
    private val labelV2 = "mt-backup-key-v2".toByteArray()   // NOT-UI: the derivation's own label
    private const val KIND_TAIL = 0
    private const val KIND_BLOCK = 1
    private const val KIND_MEDIA = 2
    private const val KIND_CARD = 3
    private const val KIND_STORE = 4
    private const val KIND_PLACE = 5
    private const val KIND_FEED = 6

    /** Where a copy waits while it is laid: outside every system backup. */
    fun shelf(c: Context): File? = File(c.noBackupFilesDir, "MontanaBackup").takeIf { it.isDirectory || it.mkdirs() }

    /** A name a copy may write: one plain file, never a path out of its place. */
    fun plainName(n: String) = n.isNotEmpty() && !n.contains('/') && !n.startsWith(".")

    /** The moment a copy was made, read back from its name ("montana-<moment>[-<proof>].mtbak"). */
    fun born(n: String): java.util.Date? {
        if (!n.startsWith("montana-") || !n.endsWith(".$EXT")) return null
        var body = n.removePrefix("montana-").removeSuffix(".$EXT")
        val dash = body.lastIndexOf('-')
        if (dash >= 0) {
            val tail = body.substring(dash + 1)
            if (tail.length == 16 && tail.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) body = body.substring(0, dash)
        }
        for (f in listOf("yyyy-MM-dd-HHmmss", "yyyy-MM-dd-HHmm")) {
            val fmt = java.text.SimpleDateFormat(f, java.util.Locale.US).apply { isLenient = false }
            val p = java.text.ParsePosition(0)
            val d = fmt.parse(body, p)
            if (d != null && p.index == body.length) return d
        }
        return null
    }

    // ── the seal ──
    private fun key(entropy: ByteArray, salt: ByteArray, label: ByteArray) = Hkdf.sha256(entropy, salt, label)
    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }
    private fun num(b: ByteArray, o: Int, n: Int): Int { var v = 0; for (k in 0 until n) v = v or ((b[o + k].toInt() and 0xff) shl (8 * k)); return v }

    /** ChaChaPoly combined (nonce 12 ‖ ciphertext ‖ tag 16) opened on the platform's own cipher; null when it does not open. */
    private fun open(k: ByteArray, combined: ByteArray, aad: ByteArray): ByteArray? = try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "ChaCha20"), IvParameterSpec(combined, 0, 12))
            updateAAD(aad)
            doFinal(combined, 12, combined.size - 12)
        }
    } catch (e: javax.crypto.AEADBadTagException) { null } catch (e: java.security.GeneralSecurityException) { null }

    /** THE FROZEN VECTORS OF THE SEAL (iOS MontanaBackup.keyKAT, RFC 5869 computed outside this code). */
    fun keyKAT(): Boolean {
        val counting = ByteArray(32) { it.toByte() }
        val after = ByteArray(32) { (it + 32).toByte() }
        return hex(key(ByteArray(32) { 0x55 }, ByteArray(32), labelV1)) == "bdd50e3824fb05ed88e25d379fa70b2de845cc5de0d27f037d201b39a9fb6792"
            && hex(key(counting, after, labelV1)) == "cb836de01e5c7468cf1b7f8e4c604a1d8ff5af1a2f683e9d9ea091713d04e6dd"
            && hex(key(counting, after, labelV2)) == "ec63f219255c63640c9916705b59e10f71ec3f8a296bb048db4ed08ed4ad48e4"
    }

    // ── taking one back ──
    /**
     * A copy is taken back BY THE ROAD A TWIN'S HISTORY TAKES: every block goes through the core's own ingest, which
     * drops a block it already holds. So a restore MERGES, and a second restore costs nothing. The share measures the
     * copy's own frames read and filed. Runs on the caller's worker thread.
     */
    fun restore(c: Context, file: File, progress: (Double) -> Unit): CopyResult {
        if (!keyKAT() || !MontanaHomeNode.keyKAT()) return CopyResult.Refused(Refusal.NoVault)
        val mn = MontanaSeed.mnemonic ?: return CopyResult.Refused(Refusal.NoSeed)
        val ent = MtBindings.nativeMnemonicToEntropy(mn)?.takeIf { it.size == 32 } ?: return CopyResult.Refused(Refusal.NoSeed)
        val taker = Taker.open(c, mn, ent) ?: return CopyResult.Refused(Refusal.NoVault)
        var shown = -1
        val share = { f: Double -> val p = (f.coerceIn(0.0, 1.0) * 100).toInt(); if (p != shown) { shown = p; progress(p / 100.0) } }
        share(0.0)
        return try {
            RandomAccessFile(file, "r").use { fh ->
                val size = fh.length()
                if (size < HEADER) return CopyResult.Refused(Refusal.NotOurs)
                val head = ByteArray(HEADER).also { fh.readFully(it) }
                val r = if (head.copyOfRange(0, 6).contentEquals(magic)) applyV1(fh, head, ent, size, taker, share)
                        else { fh.seek(0); applyV2(fh, size, ent, taker, share) }
                r ?: taker.finish()
            }
        } catch (e: java.io.IOException) {
            CopyResult.Refused(if (e.message?.contains("ENOSPC") == true) Refusal.NoSpace else Refusal.DiskRefused("read " + e.javaClass.simpleName))
        } finally {
            taker.close()
            ent.fill(0)
        }
    }

    /** Version 1, read and never written: one record per chunk, each behind its own length. */
    private fun applyV1(fh: RandomAccessFile, head: ByteArray, ent: ByteArray, size: Long, t: Taker, share: (Double) -> Unit): CopyResult? {
        if (head[6].toInt() != 1 || head[7].toInt() != 1) return CopyResult.Refused(Refusal.Version)
        val k = key(ent, head.copyOfRange(8, 40), labelV1)
        var index = 0L
        val lenB = ByteArray(4)
        while (t.tail == null) {
            if (fh.read(lenB) != 4) break
            val len = num(lenB, 0, 4)
            if (len < 17 || len > CHUNK + 4096) return CopyResult.Refused(Refusal.Torn)   // judged BEFORE a byte of it is read
            val sealed = ByteArray(len)
            if (fh.read(sealed) != len) return CopyResult.Refused(Refusal.Torn)
            // THE FIRST CHUNK IS THE QUESTION «DO THESE WORDS OPEN THIS COPY?»; a later refusal is damage.
            val opened = open(k, sealed, head + le64(index)) ?: return CopyResult.Refused(if (index == 0L) Refusal.WrongWords else Refusal.Torn)
            index++
            t.take(opened)?.let { return CopyResult.Refused(it) }
            if (size > 0) share(fh.filePointer.toDouble() / size)
        }
        return null
    }

    /** Version 2: frames of one length; inside them, one stream of records. */
    private fun applyV2(fh: RandomAccessFile, size: Long, ent: ByteArray, t: Taker, share: (Double) -> Unit): CopyResult? {
        val body = size - SALT
        if (body < FRAME) return CopyResult.Refused(Refusal.NotOurs)
        val salt = ByteArray(SALT).also { fh.readFully(it) }
        val n = (body / FRAME).toInt()
        val whole = body % FRAME == 0L
        fun last(i: Int) = whole && i == n - 1
        fun aad(i: Int, end: Boolean) = salt + le64(i.toLong()) + byteArrayOf(if (end) 1 else 0)
        val k = key(ent, salt, labelV2)
        var stream = ByteArray(0)
        var ended = false
        val f = ByteArray(FRAME)
        for (i in 0 until n) {
            if (ended) return CopyResult.Refused(Refusal.Torn)   // a frame after the tail's frame is not the writer's
            fh.readFully(f)
            val opened = open(k, f, aad(i, last(i))) ?: run {
                // THE FIRST FRAME IS THE QUESTION «DO THESE WORDS OPEN THIS FILE?». One that opens only under the other
                // end-mark belongs to a copy cut at a frame's edge: that is damage, and is named so.
                if (i != 0) return CopyResult.Refused(Refusal.Torn)
                return CopyResult.Refused(if (open(k, f, aad(0, !last(0))) != null) Refusal.Torn else Refusal.Unopened)
            }
            stream += opened
            share((i + 1).toDouble() / n)
            var at = 0
            while (t.tail == null && stream.size - at >= 8) {
                val nl = num(stream, at + 2, 2)
                if (stream.size - at < 8 + nl) break
                val dl = num(stream, at + 4 + nl, 4)
                if (dl < 0 || dl > CHUNK + 4096) return CopyResult.Refused(Refusal.Torn)
                val len = 8 + nl + dl
                if (stream.size - at < len) break
                t.take(stream.copyOfRange(at, at + len))?.let { return CopyResult.Refused(it) }
                at += len
            }
            if (t.tail != null) {
                // What follows the tail is the final frame's padding, and nothing else.
                ended = true
                for (j in at until stream.size) if (stream[j].toInt() != 0) return CopyResult.Refused(Refusal.Torn)
                stream = ByteArray(0)
            } else {
                stream = stream.copyOfRange(at, stream.size)
            }
        }
        if (!ended || !whole) return CopyResult.Refused(Refusal.Torn)
        return null
    }

    /** A COPY HANDED BACK, RECORD BY RECORD — one reading for both shapes of the container. */
    private class Taker(private val c: Context, private val hk: ByteArray, private val owner: ByteArray) {
        val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
        val tally = Tally()
        var card: ByteArray? = null
        var feed: ByteArray? = null
        var tail: JSONObject? = null
        var placed = 0
        private var sink: FileOutputStream? = null
        private var dropping = false
        private val root = MontanaPaths.archive(c)

        companion object {
            /** The history key and the owner branch of the seed, both from the core (iOS MontanaArchive.keys). */
            fun open(c: Context, mn: String, ent: ByteArray): Taker? {
                val hk = MtBindings.nativeHistoryKey(ent) ?: return null
                val master = MtBindings.nativeMnemonicToMasterSeed(mn) ?: return null
                val owner = MtBindings.nativeRoleSeed(master, "mt-owner-key")
                master.fill(0)
                return owner?.let { Taker(c, hk, it) }
            }
        }

        fun close() { runCatching { sink?.close() }; sink = null; hk.fill(0); owner.fill(0) }

        /** One whole record: null once it is filed, or the refusal that stops the copy. */
        fun take(p: ByteArray): Refusal? {
            if (p.size < 8) return Refusal.Torn
            val kind = p[0].toInt() and 0xff
            val more = p[1].toInt() == 1
            val nl = num(p, 2, 2)
            if (p.size < 8 + nl) return Refusal.Torn
            val name = String(p, 4, nl, Charsets.UTF_8)
            val dl = num(p, 4 + nl, 4)
            if (p.size != 8 + nl + dl) return Refusal.Torn
            val data = p.copyOfRange(8 + nl, 8 + nl + dl)
            if (kind != KIND_TAIL) hash.update(p)
            when (kind) {
                KIND_BLOCK -> { absorb(data); tally.records++ }
                KIND_MEDIA, KIND_STORE, KIND_PLACE -> {
                    if (dropping) { if (!more) dropping = false; return null }
                    if (sink == null) {
                        val dst = destination(kind, name) ?: return null
                        // An attachment already here is never overwritten: a copy adds, it does not erase.
                        if (dst.exists()) { dropping = more; return null }
                        try {
                            dst.parentFile?.mkdirs()
                            sink = FileOutputStream(dst)
                        } catch (e: java.io.IOException) { return Refusal.DiskRefused("media " + e.javaClass.simpleName) }
                        if (kind == KIND_PLACE) placed++ else tally.media++
                    }
                    try { sink?.write(data) } catch (e: java.io.IOException) {
                        close()
                        return if (e.message?.contains("ENOSPC") == true) Refusal.NoSpace else Refusal.DiskRefused("write")
                    }
                    if (!more) { runCatching { sink?.close() }; sink = null }
                }
                KIND_CARD -> card = (card ?: ByteArray(0)) + data
                KIND_FEED -> feed = (feed ?: ByteArray(0)) + data
                KIND_TAIL -> tail = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return Refusal.Torn
                else -> {}   // a kind this build does not know is buried in silence
            }
            return null
        }

        /** Where a file of the copy goes: the place a letter looks for it, one plain file, never a path out. */
        private fun destination(kind: Int, name: String): File? = when (kind) {
            KIND_STORE -> File(MontanaPaths.store(c), sanitize(name))
            KIND_PLACE -> {
                val parts = name.split('/', limit = 2)
                val dir = MontanaPaths.places(c)[parts[0]]
                if (parts.size == 2 && dir != null && plainName(parts[1])) File(dir, parts[1]) else null
            }
            else -> {
                val parts = name.split('/')
                if (parts.size == 2) File(File(File(MontanaPaths.chats(c), sanitize(parts[0])), "Media"), sanitize(parts[1])) else null
            }
        }

        /** A sealed block of history: filed by the core's own ingest if it opens under this person's history key. */
        private fun absorb(sealed: ByteArray) {
            val conv = MtBindings.nativeArchivePeekConv(hk, owner, sealed) ?: return
            // A folder's name comes with the card; before it, the conversation's own id names it, as on iOS.
            MtBindings.nativeArchiveIngest(root.path, hex(conv), hk, owner, sealed)
        }

        /** The copy has been read to its end: it is whole only if its tail meets the stream it closes. */
        fun finish(): CopyResult {
            val t = tail
            if (t == null || hex(hash.digest()) != t.optString("digest")) {
                Log.i("Montana", "backup_torn rec=${tally.records} media=${tally.media}")
                return CopyResult.Refused(Refusal.Torn)
            }
            // The card and the feed are laid only once the whole copy has proved itself.
            if (card != null && !Card.lay(c, card!!)) return CopyResult.Refused(Refusal.NoVault)
            feed?.let { if (!Card.keep(c, "feed", it)) return CopyResult.Refused(Refusal.NoVault) }
            tally.chats = MontanaPaths.chats(c).listFiles { f -> f.isDirectory }?.size ?: 0
            tally.skipped = t.optJSONObject("tally")?.optInt("skipped") ?: 0
            Log.i("Montana", "backup_taken rec=${tally.records} media=${tally.media} places=$placed chats=${tally.chats} card=${if (card != null) 1 else 0} feed=${feed?.size ?: 0}")
            return CopyResult.Taken(tally)
        }
    }

    /** iOS MontanaArchive.sanitizeName: no separators, no leading or trailing dots. */
    fun sanitize(n: String): String {
        val t = n.map { if (it in "/\\:\u0000") '_' else it }.joinToString("").trim().trim('.')
        return if (t.isEmpty()) "_" else t
    }
}

/**
 * THE ACCOUNT CARD a copy carries (iOS MontanaBackup.layCard): the person's settings and records, each tagged by the
 * store it lived in on iOS. This phone lays what it already has an owner for — the plain words its own settings read
 * (the name among them) — and never over a value already here; the whole card is kept sealed under the device key for
 * the owners still to come (the chats, their names and choices).
 */
object Card {
    fun lay(c: Context, raw: ByteArray): Boolean {
        val map = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return false
        val prefs = c.getSharedPreferences("mt.defaults", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        var laid = 0
        for (tagged in map.keys()) {
            if (!tagged.startsWith("s:")) continue
            val k = tagged.substring(2)
            if (prefs.contains(k)) continue
            edit.putString(k, map.optString(tagged)); laid++
        }
        edit.commit()
        Log.i("Montana", "card laid words=$laid of ${map.length()}")
        return keep(c, "card", raw)
    }

    /** A part of the copy with no owner here yet, sealed under the device key (iOS: the vault's own seal). */
    fun keep(c: Context, name: String, raw: ByteArray): Boolean {
        val dir = MontanaPaths.taken(c).apply { mkdirs() }
        val sealed = DeviceVault.seal(raw) ?: return false
        return runCatching { File(dir, name).writeBytes(sealed) }.isSuccess
    }
}
