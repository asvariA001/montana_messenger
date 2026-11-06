package quest.montana.app

import android.util.Log
import java.security.MessageDigest

/**
 * WHAT LEFT THIS APP LEAVES THE PHONE TOO (iOS MTRetired, SeedScope.forgetRetired; 07e1e6ee, the author's words 17083 and 17086: no
 * passwords anywhere). A secret letter of an older build is known by the print of its last line's opening, as on iOS: it comes buried
 * unread, rings nothing, is never edited and no screen shows it. The vault of the Passwords is erased at every launch and with the person.
 */
object Retired {
    private val letterLines = setOf("81e52bba0644a78b")
    private const val VAULT = "mt.passwords"   // NOT-UI: the stored name of what left

    private fun print(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).copyOf(8))

    /** An older build's letter: its last line opens with a retired machine line (iOS MTRetired.letter). */
    fun letter(text: String): Boolean {
        if (text.length < 17 || 65_536 < text.toByteArray(Charsets.UTF_8).size) return false
        val last = text.substringAfterLast('\n')
        if (last.length < 17 || last[0] != 'm') return false   // the cheap gate: this runs on every row
        return intArrayOf(17, 19).any { n -> n <= last.length && print(last.take(n)) in letterLines }
    }

    fun forget() {
        if (!DeviceVault.has(VAULT)) return
        DeviceVault.delete(VAULT)
        Log.i("Montana", "retired_keychain forgot=1")
    }
}
