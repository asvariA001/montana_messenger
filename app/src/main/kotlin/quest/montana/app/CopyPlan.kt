package quest.montana.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// WHAT GOES INTO A COPY, AND THE PAGES WHERE A PERSON SEES IT AND CHOOSES (iOS MontanaBackupPlan.swift; the author, 23.09 01:31:
// make conversations, records and attachments tappable, see everything inside, choose what stays).
//
// Everything goes by default. The plan is what a person left OUT — a conversation, a kind of attachment, or one file — and it is
// kept on this device, sealed like every other list that names correspondents. The inventory is COUNTED from what this device
// holds: the letters in the feed and the files in the correspondence store, each file with its size. A number on these pages
// measures exactly that.

/** What kind an attachment is (iOS MontanaBackup.Kind): named by the letter that carries it, or by the file's extension. */
enum class CopyKind(val raw: String) {
    PHOTO("photo"), VIDEO("video"), VOICE("voice"), FILE("file");
    companion object {
        fun ofExtension(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "heic", "heif", "gif", "webp" -> PHOTO
            "mov", "mp4", "m4v" -> VIDEO
            "m4a", "aac", "caf", "opus", "wav" -> VOICE
            else -> FILE
        }
        /** The letter's own word for its file (the manifest's «k»), as iOS files it: imageFile, videoFile, audioFile, docFile. */
        fun ofLetter(k: String): CopyKind? = when (k) {
            "img" -> PHOTO
            "vid", "vnote" -> VIDEO
            "aud" -> VOICE
            "doc" -> FILE
            else -> null
        }
    }
}

/** What a person chose to leave out of a copy (iOS CopyPlan). One owner; sealed under the device key. */
class CopyPlan(val skipChats: MutableList<String> = mutableListOf(), val skipKinds: MutableList<String> = mutableListOf(),
               val skipFiles: MutableList<String> = mutableListOf()) {
    fun takes(chat: String) = chat !in skipChats
    fun takes(kind: CopyKind) = kind.raw !in skipKinds
    fun save() {
        DeviceVault.set(KEY, JSONObject().put("skipChats", JSONArray(skipChats)).put("skipKinds", JSONArray(skipKinds))
            .put("skipFiles", JSONArray(skipFiles)).toString().toByteArray())
    }
    companion object {
        private const val KEY = "mt.backup.plan"   // NOT-UI: iOS CopyPlan.key
        // A plan sealed before single files could be left out has no such list: it reads as empty (iOS init(from:)).
        fun load(): CopyPlan {
            val o = DeviceVault.get(KEY)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return CopyPlan()
            fun list(k: String) = o.optJSONArray(k)?.let { a -> MutableList(a.length()) { a.optString(it) } } ?: mutableListOf()
            return CopyPlan(list("skipChats"), list("skipKinds"), list("skipFiles"))
        }
    }
}

/**
 * WHAT A COPY TAKES (iOS MontanaBackup.Scope; the author, 23.09: see what is inside and choose). Everything by default. The owner
 * map names, for every file of the store a letter on this device carries, its conversation and its kind. Every name a left-out
 * conversation is known by and the files of its faces stay out too: the page promises that none of its names or faces travel.
 */
class CopyScope(val skipConvs: Set<String> = emptySet(), val skipKinds: Set<CopyKind> = emptySet(), val skipFiles: Set<String> = emptySet(),
                val owner: Map<String, Pair<String, CopyKind>> = emptyMap(), val skipPeople: Set<String> = emptySet(),
                val skipFaces: Set<String> = emptySet()) {
    val people: Set<String> get() = skipConvs + skipPeople
    fun takes(file: String): Boolean {
        if (file in skipFiles) return false
        val o = owner[file]
        if (o != null && o.first in skipConvs) return false
        return (o?.second ?: CopyKind.ofExtension(file)) !in skipKinds
    }
    /** A conversation's own key — its ground, the ground's softness, what it said of itself — leaves with the conversation. */
    fun takesKey(k: String): Boolean {
        val p = DATA_PREFIXES.firstOrNull { k.startsWith(it) } ?: return true
        return k.removePrefix(p) !in people
    }
    /** A poster, a small copy, a wave or a mark travels exactly when the attachment it stands beside does. */
    fun takesPicture(name: String): Boolean {
        for (s in PICTURE_SUFFIXES) if (name.endsWith(s)) return takes(name.removeSuffix(s))
        return takes(name)
    }
    /** The same scope with more files left out (iOS scope.skipFiles.formUnion: what the node already keeps). */
    fun plus(files: Set<String>) = CopyScope(skipConvs, skipKinds, skipFiles + files, owner, skipPeople, skipFaces)

    companion object {
        // iOS SeedScope.dataPrefixes (MontanaChatStore.swift 6866-6874)
        val DATA_PREFIXES = listOf("chatWall.", "chatWallBlur.", "phide_", "phideAt_", "pcap_", "abcap_", "linkSent.", "aboutSent.",
            "pgHeld.", "pgcap_", "plcap_", "dqcap_", "aboutCoins.", "moneyFlow.", "ownWords.", "coinPair.", "coinBinds.", "autoReact.")
        // iOS MontanaBackup.pictureSuffixes
        val PICTURE_SUFFIXES = listOf(".poster.jpg", ".frame.jpg", ".small.jpg", ".wave", ".mtc")
    }
}

/** What this device holds for a copy, counted (iOS CopyInventory). */
class CopyInventory {
    class Conv(val id: String, val title: String, val letters: Int, val files: List<String>, var bytes: Long)
    class Taken(val chats: Int, val letters: Int, val files: Int, val bytes: Long)
    val convs = mutableListOf<Conv>()
    val owner = HashMap<String, Pair<String, CopyKind>>()
    val sizes = HashMap<String, Long>()   // every file of the store, by name
    var missing = 0                        // files the letters name and this device does not hold

    fun kindOf(file: String) = owner[file]?.second ?: CopyKind.ofExtension(file)
    fun whose(file: String): String? = owner[file]?.first?.let { c -> convs.firstOrNull { it.id == c }?.title }

    /** The one translation of a plan into what the engine takes (iOS scope [C-1]): the engine and these pages read the same answer. */
    fun scope(plan: CopyPlan): CopyScope {
        val out = convs.filter { !plan.takes(it.id) }.map { it.id }.toSet()
        return CopyScope(plan.skipChats.toSet(), plan.skipKinds.mapNotNull { k -> CopyKind.values().firstOrNull { it.raw == k } }.toSet(),
            plan.skipFiles.toSet(), HashMap(owner), out, Card.facesOf(Book.ctx, out))
    }
    fun taken(plan: CopyPlan): Taken {
        val s = scope(plan)
        val conv = convs.filter { plan.takes(it.id) }
        val files = sizes.filter { s.takes(it.key) }
        return Taken(conv.size, conv.sumOf { it.letters }, files.size, files.values.sum())
    }
    fun kindTotals(k: CopyKind): Pair<Int, Long> = sizes.filter { kindOf(it.key) == k }.let { it.size to it.values.sum() }

    companion object {
        /** Counted on the caller's worker thread from a reader's copy of the feed, and sized from the disk. */
        fun gather(c: Context): CopyInventory {
            val inv = CopyInventory()
            val named = HashSet<String>()
            for (chat in Book.rooms()) {
                val files = mutableListOf<String>()
                for (m in chat.msgs) {
                    val f = m.file?.let { File(it).name }?.takeIf { MontanaBackup.plainName(it) } ?: continue
                    val k = runCatching { JSONObject(m.text.removePrefix(Marks.MEDIA)).optString("k") }.getOrNull()?.let { CopyKind.ofLetter(it) }
                        ?: CopyKind.ofExtension(f)
                    inv.owner[f] = chat.ref to k
                    files.add(f)
                    named.add(f)
                }
                val title = if (Book.isLocal(chat.ref)) c.getString(R.string.saved_messages) else chat.shown.ifBlank { chat.ref.take(8) }
                inv.convs.add(Conv(chat.ref, title, chat.msgs.size, files, 0))
            }
            MontanaPaths.store(c).listFiles { f -> f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part") }?.forEach { inv.sizes[it.name] = it.length() }
            inv.missing = named.count { it !in inv.sizes }
            for (v in inv.convs) v.bytes = v.files.distinct().sumOf { inv.sizes[it] ?: 0L }
            inv.convs.sortWith(compareByDescending<Conv> { it.bytes }.thenByDescending { it.letters })
            return inv
        }
    }
}

/** The count and the plan the copy's pages share (iOS the @State inventory and plan bound into every page); a change saves the plan. */
class CopyDesk(val act: MainActivity, var inv: CopyInventory, val plan: CopyPlan) {
    private val shown = mutableListOf<() -> Unit>()
    fun on(f: () -> Unit) { shown.add(f); f() }
    fun changed() { plan.save(); shown.toList().forEach { it() } }
}

fun Context.copySize(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(this, bytes)   // iOS mtSize

/** A kind's words, glyph and plate (iOS CopyKindsPage.face). */
private fun CopyKind.face(): Triple<Int, Int, Int> = when (this) {
    CopyKind.PHOTO -> Triple(R.string.cp_photos, R.drawable.ic_photo, SysColor.blue)
    CopyKind.VIDEO -> Triple(R.string.cp_videos, R.drawable.ic_peer_video, SysColor.purple)
    CopyKind.VOICE -> Triple(R.string.cp_voice, R.drawable.ic_speaker_wave3, SysColor.orange)
    CopyKind.FILE -> Triple(R.string.cp_files, R.drawable.ic_set_doc, SysColor.gray)
}

/** The value at a row's end and the mark that says whether the copy takes it. */
private fun Context.valueMark(value: TextView, mark: ImageView?): View = hstack {
    addView(value)
    if (mark != null) addView(mark, lp(dp(20), dp(20)).apply { marginStart = dp(8) })
}
private fun Context.mark() = icon(R.drawable.ic_check, Color.WHITE, 20)
private fun LinearLayout.footerLine(): TextView = context.text("", 12f, MT.gray).apply { setPadding(dp(16), dp(8), dp(16), 0) }.also { addView(it, lp()) }

/** The rows of the copy: what it takes of the conversations, and of each kind of attachment, each opening what is inside. */
private fun copyRows(desk: CopyDesk): List<View> {
    val act = desk.act
    val c: Context = act
    val convValue = c.text("", 15f, MT.gray)
    val rows = mutableListOf<View>(c.settingsRow(R.drawable.ic_set_textbubble, SysColor.blue, c.getString(R.string.cp_conversations),
        value = convValue) { act.push { copyChatsPage(desk, it) } })
    desk.on { convValue.text = "${desk.inv.taken(desk.plan).chats} / ${desk.inv.convs.size}" }   // USER-DATA: counts
    for (k in CopyKind.values()) {
        val (title, glyph, color) = k.face()
        val value = c.text("", 15f, MT.gray)
        val mark = c.mark()
        rows.add(c.settingsRow(glyph, color, c.getString(title), value = c.valueMark(value, mark)) { act.push { copyFilesPage(desk, k, it) } })
        desk.on {
            val (n, b) = desk.inv.kindTotals(k)
            value.text = "$n · ${c.copySize(b)}"   // USER-DATA: a count and a size
            mark.visibility = if (desk.plan.takes(k)) View.VISIBLE else View.INVISIBLE
        }
    }
    return rows
}

/**
 * WHAT THE COPY TAKES, COUNTED FROM THIS DEVICE, under the backup's rows of Data and Storage (iOS MontanaSettings 1712-1737):
 * conversations and their letters on one page, attachments by kind on the other.
 */
fun copySummary(desk: CopyDesk): List<View> {
    val act = desk.act
    val c: Context = act
    val convValue = c.text("", 15f, MT.gray)
    val recValue = c.text("", 15f, MT.gray)
    val attValue = c.text("", 15f, MT.gray)
    val rows = listOf(
        c.settingsRow(R.drawable.ic_set_textbubble, SysColor.blue, c.getString(R.string.cp_conversations), value = convValue) { act.push { copyChatsPage(desk, it) } },
        c.settingsRow(R.drawable.ic_list_bullet, SysColor.teal, c.getString(R.string.cp_records), value = recValue) { act.push { copyChatsPage(desk, it) } },
        c.settingsRow(R.drawable.ic_attach, SysColor.indigo, c.getString(R.string.cp_attachments), value = attValue) { act.push { copyKindsPage(desk, it) } })
    desk.on {
        val now = desk.inv.taken(desk.plan)
        convValue.text = "${now.chats} / ${desk.inv.convs.size}"   // USER-DATA: counts
        recValue.text = "${now.letters}"
        attValue.text = "${now.files} · ${c.copySize(now.bytes)}"
    }
    return rows
}

/**
 * THE CONVERSATIONS OF A COPY (iOS CopyChatsPage) — every one this device holds, with its face, its letters and the weight of its
 * attachments. The whole row answers the finger; the mark says what the copy takes.
 */
fun copyChatsPage(desk: CopyDesk, close: () -> Unit): View {
    val act = desk.act
    val c: Context = act
    return settingsPage(act, c.getString(R.string.cp_conversations), close) {
        val rows = desk.inv.convs.map { v ->
            val mark = c.mark()
            c.hstack {
                setPadding(dp(16), dp(8), dp(14), dp(8))
                addView(c.peerFace(v.id, v.title, 36), lp(dp(36), dp(36)))
                gap(12)
                addView(c.vstack(Gravity.NO_GRAVITY) {
                    addView(c.text(v.title, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp())   // USER-DATA: the correspondent's name
                    addView(c.text(c.getString(R.string.cp_letters, v.letters, c.copySize(v.bytes)), 13f, MT.gray), lp())
                }, lp(0, WRAP, 1f))
                addView(mark, lp(dp(20), dp(20)))
                desk.on { mark.visibility = if (desk.plan.takes(v.id)) View.VISIBLE else View.INVISIBLE }
                pressable {
                    if (!desk.plan.skipChats.remove(v.id)) desk.plan.skipChats.add(v.id)
                    desk.changed()
                }
            }
        }
        section(null, c.getString(R.string.cp_left_out_footer), *rows.toTypedArray())
    }
}

/** THE ATTACHMENTS OF A COPY (iOS CopyKindsPage) — by kind, each with its count and weight here; a row opens the kind's own files. */
fun copyKindsPage(desk: CopyDesk, close: () -> Unit): View {
    val act = desk.act
    val c: Context = act
    return settingsPage(act, c.getString(R.string.cp_attachments), close) {
        val inv = desk.inv
        section(null, if (inv.missing > 0) c.getString(R.string.cp_missing, inv.missing) else c.getString(R.string.cp_all_here),
            *copyRows(desk).drop(1).toTypedArray())
    }
}

/**
 * ONE KIND'S FILES (iOS CopyFilesPage; the author, 23.09 01:50 and 02:40): every file of the kind this device holds, heaviest
 * first, each with its own picture. Each file is chosen on its own: the round mark says whether the copy takes it. The row opens
 * the file in the platform's viewer and the back returns here. The switch at the top is the whole kind's place in the copy.
 */
fun copyFilesPage(desk: CopyDesk, kind: CopyKind, close: () -> Unit): View {
    val act = desk.act
    val c: Context = act
    val inv = desk.inv
    val files = inv.sizes.filter { inv.kindOf(it.key) == kind }.toList().sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
    /** A file is chosen on its own while neither its kind nor its conversation is left out whole. */
    fun free(f: String): Boolean = desk.plan.takes(kind) && (inv.owner[f]?.first?.let { desk.plan.takes(it) } ?: true)
    return settingsPage(act, c.getString(kind.face().first), close) {
        val sw = c.switchLine(kind.face().second, kind.face().third, c.getString(R.string.cp_in_copy), desk.plan.takes(kind)) { on ->
            desk.plan.skipKinds.remove(kind.raw)
            if (!on) desk.plan.skipKinds.add(kind.raw)
            desk.changed()
        }
        section(null, null, sw)
        val sum = footerLine()
        desk.on {
            val s = inv.scope(desk.plan)
            val kept = files.filter { s.takes(it.first) }
            sum.text = c.getString(R.string.cp_in_copy_of, kept.size, files.size, c.copySize(kept.sumOf { it.second }))
        }
        val rows = files.map { (name, bytes) ->
            val f = File(MontanaPaths.store(c), name)
            val circle = ImageView(c).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
            val thumb = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = c.rounded(MT.plate, 6); clipToOutline = true
                val (_, glyph, _) = kind.face()
                setImageResource(glyph); imageTintList = ColorStateList.valueOf(MT.gray)
            }
            if (kind == CopyKind.PHOTO || kind == CopyKind.VIDEO) act.background {
                val b = if (kind == CopyKind.PHOTO) SmallPicture.of(f)
                    else runCatching { @Suppress("DEPRECATION") android.media.ThumbnailUtils.createVideoThumbnail(f.path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND) }.getOrNull()
                if (b != null) act.onMain { thumb.imageTintList = null; thumb.setImageBitmap(b) }
            }
            c.hstack {
                setPadding(dp(8), dp(6), dp(14), dp(6))
                addView(circle, lp(dp(40), dp(40)))
                circle.pressable {
                    if (!free(name)) return@pressable
                    if (!desk.plan.skipFiles.remove(name)) desk.plan.skipFiles.add(name)
                    desk.changed()
                }
                desk.on {
                    val takes = inv.scope(desk.plan).takes(name)
                    circle.setImageResource(if (takes) R.drawable.ic_set_check_circle else R.drawable.ic_circle)
                    circle.imageTintList = ColorStateList.valueOf(if (takes) Color.WHITE else MT.gray)
                    circle.alpha = if (free(name)) 1f else 0.4f
                    circle.contentDescription = c.getString(if (takes) R.string.cp_leave_out else R.string.cp_put_back)
                }
                gap(6)
                addView(thumb, lp(dp(44), dp(44)))
                gap(10)
                addView(c.vstack(Gravity.NO_GRAVITY) {
                    val who = inv.whose(name)
                    // USER-DATA: the correspondent's name, as the list shows it
                    addView((if (who != null) c.text(who, 16f) else c.text(c.getString(R.string.cp_no_letter), 16f, MT.gray))
                        .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp())
                    addView(c.text(c.copySize(bytes), 13f, MT.gray), lp())   // USER-DATA: a size
                }, lp(0, WRAP, 1f))
                pressable { openCopyFile(act, f, kind) }
            }
        }
        if (rows.isNotEmpty()) section(null, null, *rows.toTypedArray())
    }
}

/** One file, opened by the platform's own viewer (iOS CopyFileView: Quick Look pushed into the page); the back returns to the list. */
private fun openCopyFile(act: MainActivity, f: File, kind: CopyKind) {
    if (kind == CopyKind.FILE || !f.exists()) return openDocument(act, f)
    val uri = Media.uri(act, f)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, act.contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { act.startActivity(view) }.onFailure { openDocument(act, f) }
}

/**
 * THE COPY BEFORE IT IS MADE (iOS CopyPreviewSheet; the author, 23.09 01:50): what the copy will take, by kind, each opening its
 * files; the conversations it takes; the whole weight. The checkmark makes it; then the bar measures bytes sealed, and the
 * finished file goes on as before. While it seals, nothing closes it.
 */
fun copyPreviewPage(desk: CopyDesk, close: () -> Unit, done: (CopyResult) -> Unit): View {
    val act = desk.act
    val c: Context = act
    var sealing = false
    val progress = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(16), dp(10), dp(16), dp(12)) }
    val share = c.text("", 15f, MT.gray)
    val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = ColorStateList.valueOf(Color.WHITE) }
    progress.addView(c.hstack { addView(c.text(c.getString(R.string.sealing_copy), 15f), lp(0, WRAP, 1f)); addView(share) }, lp())
    progress.addView(bar, lp())
    lateinit var rowsBox: LinearLayout
    lateinit var progressBox: View
    val seal = c.icon(R.drawable.ic_check, Color.WHITE).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
    seal.pressable {
        if (sealing) return@pressable
        sealing = true
        seal.visibility = View.INVISIBLE
        progressBox.visibility = View.VISIBLE
        rowsBox.alpha = 0.4f
        val scope = desk.inv.scope(desk.plan)
        act.background {
            val r = MontanaBackup.create(act, scope) { f -> act.onMain { share.text = "${(f * 100).toInt()}%"; bar.progress = (f * 100).toInt() } }   // USER-DATA: a share
            act.onMain { sealing = false; close(); done(r) }
        }
    }
    return settingsPage(act, c.getString(R.string.cp_create), { if (!sealing) close() }, cross = true, trailing = seal) {
        progressBox = c.plate { addView(progress, lp()) }.apply { visibility = View.GONE }
        addView(progressBox, lp().apply { topMargin = dp(12) })
        rowsBox = c.vstack(Gravity.NO_GRAVITY) {
            section(c.getString(R.string.cp_in_copy_header), null, *copyRows(desk).toTypedArray())
            val total = footerLine()
            desk.on { val now = desk.inv.taken(desk.plan); total.text = c.getString(R.string.cp_total, c.copySize(now.bytes), now.letters) }
        }
        addView(rowsBox, lp())
    }
}
