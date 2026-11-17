package quest.montana.app

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * NOTHING OF A PERSON OUTLIVES THEIR DELETION ON A PHONE THEY WERE ALONE ON (iOS MTNothingLeft, MTKeeping.swift, 456fff61 = 2434;
 * OZ 1 p.5, the author's words «make it so there are no leftovers at all», «erase everything clean»). The forgetting knew the
 * person's files and vault values one by one: a file no list named was nobody's to erase, and the store went on writing after it.
 * This is the one erase of a phone left with no person: every file of the app's folders, every value of its settings, every value
 * of its vault -- what a new install holds -- and the diary with them. It runs again 3 s and 30 s later and at every launch until a
 * seed comes ([ERASED]), so a write already on its way when the person left is swept too.
 *
 * Its one door is iOS MTKeeping.eraseHere (after «Delete account», an ended session, a term run out), with nobody parked on the
 * shelf. Android has none of those doors yet: «Forget this device» seals the history for the 24 words on this phone (iOS
 * sealForForget) and must never call this. [erase] waits for the eraseHere port; the launch's [again] and the seed's [lift] stand.
 */
object NothingLeft {
    /** The whole erase still owed on this phone (iOS MontanaSeed.erasedKey): set by the erase, lifted by the next seed. */
    const val ERASED = "mt.erase.pending"
    private const val TAG = "Montana"

    /** What the walk passes, by its place under the app's data folder: the system's own (the code, its libraries, its caches of
     *  the code), the settings' files (their values leave one by one below) and the diary's folder (it leaves by Diary.erase). */
    private val kept = setOf("lib", "code_cache", "shared_prefs", "files/Montana/Diagnostics")

    /** The person left and nobody waits on the shelf: everything goes now, and the erase stays owed until a seed comes. */
    fun erase(c: Context, why: String) {
        val app = c.applicationContext
        Prefs.setBool(ERASED, true)
        sweep(app, why)
        val main = Handler(Looper.getMainLooper())
        for (after in listOf(3, 30)) main.postDelayed({ again(app, "$why+$after") }, after * 1000L)
    }

    /** The erase owed -- at a launch before any owner is born, and after the erase itself -- while no seed came since. */
    fun again(c: Context, why: String) {
        if (!Prefs.bool(ERASED, false)) return
        sweep(c.applicationContext, why)
    }

    /** A seed came: no erase is owed any more (iOS MontanaSeed.setActive). */
    fun lift() = Prefs.remove(ERASED)

    private fun sweep(c: Context, why: String) {
        Diary.erase()   // first: the line below opens the diary of the phone with no person
        runCatching { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll() }
        var files = 0
        val data = File(c.applicationInfo.dataDir)
        files += clear(data, "")
        for (d in listOfNotNull(c.externalCacheDir, c.getExternalFilesDir(null))) files += clear(d, "-")
        // the settings: every value of every file leaves, but the erase still owed
        var values = 0
        File(data, "shared_prefs").listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            val p = c.getSharedPreferences(name, Context.MODE_PRIVATE)
            val keys = p.all.keys.filter { it != ERASED }
            values += keys.size
            p.edit().apply { keys.forEach { remove(it) } }.commit()
        }
        Log.i(TAG, "erase_whole why=$why files=$files values=$values")
    }

    /** Every item under a folder leaves, but a kept place and the way to it; a link is never followed. */
    private fun clear(dir: File, path: String): Int {
        var gone = 0
        for (f in dir.listFiles() ?: return 0) {
            val p = if (path.isEmpty()) f.name else "$path/${f.name}"
            if (p in kept) continue
            if (runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)) continue
            if (kept.any { it.startsWith("$p/") }) { gone += clear(f, p); continue }
            if (f.deleteRecursively()) gone++
        }
        return gone
    }
}
