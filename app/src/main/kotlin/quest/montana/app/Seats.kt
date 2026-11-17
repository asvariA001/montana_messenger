package quest.montana.app

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * THE SEATS OF ONE PHONE (iOS MTSeats, MontanaSeats.swift): several people live on one phone, one of them seated at a time.
 *
 * The BOOK (`mt.seats`, sealed in the device vault, the phone's own, never a person's) names every seat {id, born, name, glyph},
 * the one seated (active), the one before (last) and the turn under way (step {from, to, stage}). Each person off the seat lies
 * on the SHELF: their RECORD (`mt.seat.<id>` in the vault: their words and every value of the vault that is theirs) and their
 * folder `seats/<id>/` beside the app's files (values.json = their layer of mt.defaults, folders/ = their files, strays/ = what
 * was found standing in a place when theirs came back).
 *
 * THE TURN (iOS move: park → clear → lift) runs at the very start of the process, before any owner reads a key or a file
 * (Seats.launch, called right after DeviceVault.init / Prefs.init at every door of the process): a seat asked to move writes
 * the step and starts the process again, so no object of the one leaving still holds their keys in memory -- Android's
 * answer to iOS clearMemory / liftMemory. Every stage is written before the next begins, so a turn broken by a kill is
 * finished at the next start (iOS launch): park again (what moved already is not at its place), clear, lift.
 * A turn is refused during a call (iOS refusedForCall).
 */
object Seats {
    private const val BOOK = "mt.seats"
    private const val RECORD = "mt.seat."
    private const val TAG = "Montana"

    /** The vault's values of the phone itself: never parked, never cleared by a turn (iOS keychainStays / deviceKeys). */
    private val DEVICE_VAULT = setOf(
        BOOK,
        "mt.history.device",   // this install's sixteen bytes (Archive.DEVICE)
        "mt.passwords",        // the retired vault, forgotten at launch (Retired)
        "mt.backup.carried",   // what the account brought to this phone (BackupSeed)
        "diagSalt",            // the diary's salt (Diary)
    )
    fun deviceVault(name: String): Boolean = name in DEVICE_VAULT || name.startsWith(RECORD)

    /** The person's folders, by root: F = filesDir, N = noBackupFilesDir. «Montana» moves child by child, its Diagnostics stays. */
    private val FOLDERS = listOf(
        "F:faces", "F:avatar.jpg", "F:stickers", "F:gifs", "F:media", "F:Montana", "F:TimeChain", "F:board", "F:walllook",
        "F:wallpapers", "F:Support", "F:stories", "F:saved_messages.json", "F:music", "F:Coins", "F:CallRecordings",
        "N:MontanaHistory", "N:MontanaKeeping",
    )
    private val MONTANA_STAYS = setOf("Diagnostics")   // the phone's diary stays at its place (iOS: the log is the device's)

    class Seat(val id: String, val born: Long, var name: String, var glyph: String)
    class Step(val from: String, val to: String, var stage: String)
    private class Book(var active: String, var last: String, val seats: MutableList<Seat>, var step: Step?)

    private val lock = Any()
    @Volatile private var launched = false

    // ───────────────────────── the book ─────────────────────────

    private fun read(): Book {
        val o = DeviceVault.get(BOOK)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
        val seats = ArrayList<Seat>()
        o.optJSONArray("seats")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let {
            seats += Seat(it.optString("id"), it.optLong("born"), it.optString("name"), it.optString("glyph")) } }
        val step = o.optJSONObject("step")?.let { Step(it.optString("from"), it.optString("to"), it.optString("stage")) }
        return Book(o.optString("active"), o.optString("last"), seats, step)
    }

    private fun write(b: Book): Boolean {
        val o = JSONObject().put("active", b.active).put("last", b.last)
            .put("seats", JSONArray().apply { b.seats.forEach { put(JSONObject().put("id", it.id).put("born", it.born).put("name", it.name).put("glyph", it.glyph)) } })
        b.step?.let { o.put("step", JSONObject().put("from", it.from).put("to", it.to).put("stage", it.stage)) }
        return DeviceVault.set(BOOK, o.toString().toByteArray(Charsets.UTF_8))
    }

    /** Every seat of the phone, the seated one among them. */
    val seats: List<Seat> get() = synchronized(lock) { read().seats.toList() }
    /** The seat now: "" while an empty place stands (the first screen after «add a person»). */
    val active: String get() = synchronized(lock) { read().active }
    val last: String get() = synchronized(lock) { read().last }

    private fun newId(): String = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // ───────────────────────── the doors ─────────────────────────

    /** Whether a turn may start now: never during a call (iOS refusedForCall). */
    fun mayMove(): Boolean = !CallLine.busy

    /** The face in the drawer: the seated person leaves for the shelf, [id] takes the seat (iOS switchTo). */
    fun switchTo(ctx: Context, id: String): Boolean = move(ctx, id)

    /** «Add a person» (iOS makeRoom): the seated person leaves for the shelf, an empty place opens on the first screen. */
    fun makeRoom(ctx: Context): Boolean = move(ctx, "")

    /** The cross of the first screen on an empty place (iOS returnToLast): the one who made the room comes back. */
    fun returnToLast(ctx: Context): Boolean {
        val l = last.takeIf { it.isNotEmpty() && seats.any { s -> s.id == it } } ?: return false
        return move(ctx, l)
    }

    /**
     * WORDS OF A PERSON ON THE SHELF (iOS lift(words)): words entered on an empty place that some shelved seat already holds raise
     * that seat whole instead of a second copy of the same person. Returns whether a turn began.
     */
    fun lift(ctx: Context, words: String): Boolean {
        val norm = words.trim().split(Regex("\\s+")).joinToString(" ")
        val id = synchronized(lock) { val b = read(); b.seats.firstOrNull { it.id != b.active && recordWords(it.id) == norm }?.id } ?: return false
        return move(ctx, id)
    }

    /** A person born or entered on the empty place takes a seat of their own (iOS born). */
    fun born(name: String) = synchronized(lock) {
        val b = read()
        if (b.active.isNotEmpty() || b.step != null) return@synchronized
        val s = Seat(newId(), System.currentTimeMillis(), name, glyphOf(name))
        b.seats += s; b.active = s.id
        write(b)
        Log.i(TAG, "seats: born ${s.id}")
    }

    /** The seated person renamed: the drawer's face says the new name. */
    fun named(name: String) = synchronized(lock) {
        val b = read()
        b.seats.firstOrNull { it.id == b.active }?.let { if (it.name != name) { it.name = name; it.glyph = glyphOf(name); write(b) } }
    }

    /**
     * THE SEATED PERSON FORGOTTEN (iOS leaveForgotten): «forget this device» with others on the shelf takes only the seated one out of
     * the book; their record goes, their folder on the shelf is set aside as left-<ms> (iOS), the place stays empty.
     */
    fun leaveForgotten(ctx: Context) = synchronized(lock) {
        val b = read()
        val gone = b.active.takeIf { it.isNotEmpty() } ?: return@synchronized
        b.seats.removeAll { it.id == gone }
        b.active = ""
        if (b.last == gone) b.last = ""
        DeviceVault.delete(RECORD + gone)
        shelf(ctx, gone).takeIf { it.exists() }?.renameTo(File(root(ctx), "left-${System.currentTimeMillis()}"))
        write(b)
        Log.i(TAG, "seats: forgotten $gone, ${b.seats.size} left on the shelf")
    }

    private fun move(ctx: Context, to: String): Boolean {
        if (!mayMove()) { Log.i(TAG, "seats: turn refused, a call is on"); return false }
        synchronized(lock) {
            val b = read()
            if (b.step != null || to == b.active) return false
            if (to.isNotEmpty() && b.seats.none { it.id == to }) return false
            b.step = Step(b.active, to, "park")
            if (!write(b)) return false
            Log.i(TAG, "seats: turn ${b.active.ifEmpty { "empty" }} → ${to.ifEmpty { "empty" }} written, the process starts again")
        }
        restart(ctx)
        return true
    }

    /** The process starts again on the first screen, the turn ahead of everything (Seats.launch). */
    private fun restart(ctx: Context) {
        val start = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(start.component))
        Runtime.getRuntime().exit(0)
    }

    // ───────────────────────── the turn ─────────────────────────

    /**
     * AT EVERY START OF THE PROCESS (iOS launch), right after the vault and the defaults are open and before anything reads them:
     * a seated person found without a seat (the phone before seats) takes one; a turn under way is finished stage by stage.
     */
    fun launch(ctx: Context) {
        if (launched) return
        synchronized(lock) {
            if (launched) return
            launched = true
            val b = read()
            if (b.seats.isEmpty() && MontanaSeed.hasSeed) {
                val name = Prefs.userName
                val s = Seat(newId(), System.currentTimeMillis(), name, glyphOf(name))
                b.seats += s; b.active = s.id
                write(b)
                Log.i(TAG, "seats: the person of this phone takes seat ${s.id}")
            }
            val step = b.step ?: return
            runCatching { turn(ctx, b, step) }.onFailure { Log.e(TAG, "seats: turn failed at ${step.stage}: ${it.javaClass.simpleName}") }
        }
    }

    private fun turn(ctx: Context, b: Book, step: Step) {
        if (step.stage == "park") {
            if (step.from.isNotEmpty() && !park(ctx, step.from)) {
                // a folder refused: what moved comes back, the person stays seated (iOS park's roll back)
                unpark(ctx, step.from)
                b.step = null; write(b)
                Log.e(TAG, "seats: park refused, ${step.from} stays")
                return
            }
            step.stage = "clear"; write(b)
        }
        if (step.stage == "clear") {
            clear()
            step.stage = "lift"; write(b)
        }
        if (step.stage == "lift") {
            if (step.to.isNotEmpty() && !liftIn(ctx, step.to)) {
                // the one asked would not rise: the place is cleared again and the one who left comes back (iOS)
                clear()
                if (step.from.isNotEmpty()) liftIn(ctx, step.from)
                b.active = step.from; b.step = null; write(b)
                Log.e(TAG, "seats: lift of ${step.to} refused, ${step.from} back")
                return
            }
            if (step.from.isNotEmpty()) b.last = step.from
            b.active = step.to; b.step = null
            write(b)
            Log.i(TAG, "seats: ${step.to.ifEmpty { "an empty place" }} seated")
        }
    }

    /** The person seated leaves for the shelf: their values, their vault, their files. Nothing is cleared here. */
    private fun park(ctx: Context, id: String): Boolean {
        val dir = shelf(ctx, id).apply { mkdirs() }
        // the values of the defaults (their layer), the vault's values and the words in one record
        val values = Prefs.layer()
        val tmp = File(dir, "values.json.tmp")
        tmp.writeText(values.toString())
        if (!tmp.renameTo(File(dir, "values.json"))) return false
        val chain = JSONObject()
        DeviceVault.names().filterNot { deviceVault(it) }.forEach { n -> DeviceVault.get(n)?.let { chain.put(n, Base64.encodeToString(it, Base64.NO_WRAP)) } }
        val record = JSONObject().put("words", MontanaSeed.mnemonic ?: "").put("chain", chain)
        if (!DeviceVault.set(RECORD + id, record.toString().toByteArray(Charsets.UTF_8))) return false
        // the folders one by one: a folder already gone from its place moved at an earlier try
        for ((place, kept) in places(ctx, dir)) {
            if (!place.exists()) continue
            kept.parentFile?.mkdirs()
            if (kept.exists() && !kept.deleteRecursively()) return false
            if (!place.renameTo(kept)) { Log.e(TAG, "seats: park of ${place.name} refused"); return false }
        }
        Log.i(TAG, "seats: $id parked, ${values.length()} values, ${chain.length()} vault values")
        return true
    }

    /** A park that failed half way: the folders that moved come back (their values were never cleared). */
    private fun unpark(ctx: Context, id: String) {
        for ((place, kept) in places(ctx, shelf(ctx, id))) if (kept.exists() && !place.exists()) { place.parentFile?.mkdirs(); kept.renameTo(place) }
    }

    /** The place is emptied of the person: their layer of the defaults and every vault value of theirs (iOS clearStore). */
    private fun clear() {
        Prefs.clearLayer()
        DeviceVault.names().filterNot { deviceVault(it) }.forEach { DeviceVault.delete(it) }
    }

    /** The person [id] comes back from the shelf: files, values, vault; their record stays until the next park writes it again. */
    private fun liftIn(ctx: Context, id: String): Boolean {
        val rec = DeviceVault.get(RECORD + id)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return false
        val dir = shelf(ctx, id)
        val strays = File(dir, "strays")
        for ((place, kept) in places(ctx, dir)) {
            if (!kept.exists()) continue
            // a place that stands: empty -- taken away; full -- into the strays of the one rising, never lost (iOS put)
            if (place.exists()) {
                if (place.isDirectory && place.list().isNullOrEmpty()) place.delete()
                else { strays.mkdirs(); place.renameTo(File(strays, "${place.name}-${System.currentTimeMillis()}")) }
            }
            place.parentFile?.mkdirs()
            if (!kept.renameTo(place)) { Log.e(TAG, "seats: lift of ${place.name} refused"); return false }
        }
        File(dir, "values.json").takeIf { it.isFile }?.let { f -> runCatching { Prefs.lay(JSONObject(f.readText())) } }
        val chain = rec.optJSONObject("chain") ?: JSONObject()
        for (n in chain.keys()) if (!deviceVault(n)) DeviceVault.set(n, Base64.decode(chain.optString(n), Base64.NO_WRAP))
        val ok = MontanaSeed.hasSeed || rec.optString("words").isEmpty()
        Log.i(TAG, "seats: $id lifted, ${chain.length()} vault values, seed=${MontanaSeed.hasSeed}")
        return ok
    }

    private fun recordWords(id: String): String =
        DeviceVault.get(RECORD + id)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)).optString("words") }.getOrNull() }
            ?.trim()?.split(Regex("\\s+"))?.joinToString(" ") ?: ""

    // ───────────────────────── the shelf ─────────────────────────

    private fun root(ctx: Context) = File(ctx.dataDir, "seats")
    private fun shelf(ctx: Context, id: String) = File(root(ctx), id)

    /** Every folder of the person at its place, beside where it lies on the shelf. */
    private fun places(ctx: Context, dir: File): List<Pair<File, File>> {
        val out = ArrayList<Pair<File, File>>()
        val folders = File(dir, "folders")
        for (f in FOLDERS) {
            val (r, path) = f.split(":", limit = 2)
            val base = if (r == "N") ctx.noBackupFilesDir else ctx.filesDir
            val kept = File(folders, "$r/$path")
            if (path == "Montana") {
                // child by child: what is the phone's stays; a child that lies only on the shelf comes back too
                val names = HashSet<String>()
                File(base, path).list()?.let { names.addAll(it) }
                kept.list()?.let { names.addAll(it) }
                names.removeAll(MONTANA_STAYS)
                for (n in names) out += File(base, "$path/$n") to File(kept, n)
            } else out += File(base, path) to kept
        }
        return out
    }

    private fun glyphOf(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
}
