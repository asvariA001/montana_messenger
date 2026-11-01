package quest.montana.app

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * THE HISTORY BY CONVERSATION (iOS MTHistoryFile, P-6, the law of 01.11.2026: «work is in proportion to the change»). Every
 * conversation stood as one entry of the device vault, and the vault is one SharedPreferences file: every letter re-wrote the
 * whole file, every conversation's sealed history with it. Each conversation is its own sealed file now (the device key, as
 * the vault), outside every system backup; a write lays only a conversation whose bytes moved and reads its bytes back from
 * the disk (iOS P-123); the book's index stays in the vault. A conversation the vault still holds moves into its file the
 * first time it is read, and leaves the vault only once its file reads back.
 */
object HistoryFile {
    private const val OLD = "chat:"   // the vault's own name of a conversation, before this shape
    private val held = HashMap<String, String>()   // a part's name: the digest of its plain bytes, as this process wrote or read them
    var moved = 0; private set   // conversations moved out of the vault by this process

    private fun dir() = File(Book.ctx.noBackupFilesDir, "MontanaHistory/chats")
    /** The folder the disk's measure counts as history (CopyPlan). */
    fun folder() = File(Book.ctx.noBackupFilesDir, "MontanaHistory")
    private fun name(ref: String) = digest(ref.toByteArray()).take(32)   // LOCAL-HASH-OK: a file name on this device
    private fun part(n: String) = File(dir(), "$n.sealed")
    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The conversation's bytes: its file, else the vault's old entry (moved into a file of its own here); null when neither opens. */
    @Synchronized fun get(ref: String): ByteArray? {
        val n = name(ref)
        val f = part(n)
        if (f.exists()) {
            val plain = runCatching { f.readBytes() }.getOrNull()?.let { DeviceVault.unseal(it) }
            if (plain == null) { Log.d("Montana", "history_part_unread part=" + n.take(8)); return null }
            held[n] = digest(plain)
            return plain
        }
        val old = DeviceVault.get(OLD + ref) ?: return null
        if (lay(n, old)) { DeviceVault.delete(OLD + ref); moved++ }
        return old
    }

    /** Lays the conversation when its bytes moved; true when the file holds them (read back from the disk). */
    @Synchronized fun put(ref: String, plain: ByteArray): Boolean {
        val n = name(ref)
        if (held[n] == digest(plain) && part(n).exists()) return true   // the part already holds these very bytes
        if (!lay(n, plain)) return false
        if (DeviceVault.has(OLD + ref)) DeviceVault.delete(OLD + ref)   // an old entry a refused move left behind
        return true
    }

    private fun lay(n: String, plain: ByteArray): Boolean {
        val sealed = DeviceVault.seal(plain) ?: return refused(n, "no device key")
        val d = dir().apply { mkdirs() }
        val tmp = File(d, "$n.tmp")
        val ok = runCatching {
            FileOutputStream(tmp).use { it.write(sealed); it.fd.sync() }
            tmp.renameTo(part(n))
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); return refused(n, "write failed") }
        val back = runCatching { part(n).readBytes() }.getOrNull()?.let { DeviceVault.unseal(it) }
        if (back == null || !back.contentEquals(plain)) { held.remove(n); return refused(n, "read-back differs") }
        held[n] = digest(plain)
        return true
    }

    private fun refused(n: String, why: String): Boolean { Log.d("Montana", "history_write_refused part=" + n.take(8) + " " + why); return false }

    /** The conversation leaves with its file (and any entry the vault still held of it). */
    @Synchronized fun drop(ref: String) {
        val n = name(ref)
        part(n).delete(); held.remove(n)
        DeviceVault.delete(OLD + ref)
    }

    /** The person leaves: every conversation's file with them. */
    @Synchronized fun dropAll() { folder().deleteRecursively(); held.clear() }
}
