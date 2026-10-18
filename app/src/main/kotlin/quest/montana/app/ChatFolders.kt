package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE FOLDERS OVER THE CHATS (iOS MTChatFolders, d335474b + 786313d3 at 2240; the author's words 17.10.2026 18:4x MSK: «badges over
 * the chats, the groups and the channels»; «left of the chats, the groups and the channels a field All, every chat in it, the groups
 * and the channels too -- and a category Bots, our bot in it»; 18.10.2026 10:2x: «call it simply All; this strip of tabs over the
 * chats' feed as the time panel, by its style and its place»). A folder takes kinds of chats and chats by name, and leaves out states
 * and chats by name; All stands among them and is never taken away. A tab's badge counts the folder's unread conversations and is
 * blue while one of them is not muted. A setting of the person, kept as the iOS «chatFolders».
 */
class ChatFolder(
    val id: String,
    var title: String = "",
    val preset: String? = null,
    val kinds: List<String> = emptyList(),
    val chats: List<String> = emptyList(),
    val leaves: List<String> = listOf(ChatFolders.ARCHIVED),
    val excluded: List<String> = emptyList(),
) {
    val isAll get() = id == ChatFolders.ALL
    /** The folder of one kind alone: the corner of the groups' and the channels' folders gives birth to that kind (iOS onlyKind). */
    val onlyKind get() = if (kinds.size == 1 && chats.isEmpty()) kinds[0] else null
    fun shownTitle(c: Context): String {
        if (isAll) return c.getString(R.string.cf_all)
        if (title.isNotEmpty()) return title   // USER-DATA: the person's own name of a folder
        return when (preset) {
            ChatFolders.GROUPS -> c.getString(R.string.app_groups)
            ChatFolders.CHANNELS -> c.getString(R.string.app_channels)
            ChatFolders.BOTS -> c.getString(R.string.cf_bots)
            else -> c.getString(R.string.chats)
        }
    }
    /** Whether a conversation stands in the folder: taken by its kind or by name, not left out by name or by a state (iOS takes). */
    fun takes(ref: String, kind: String, muted: Boolean, unread: Boolean, archived: Boolean): Boolean {
        if (isAll) return !archived
        if (ref in excluded) return false
        if (kind !in kinds && ref !in chats) return false
        if (muted && ChatFolders.MUTED in leaves) return false
        if (!unread && ChatFolders.READ in leaves) return false
        if (archived && ChatFolders.ARCHIVED in leaves) return false
        return true
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("preset", preset ?: JSONObject.NULL)
        .put("kinds", JSONArray(kinds)).put("chats", JSONArray(chats)).put("leaves", JSONArray(leaves)).put("excluded", JSONArray(excluded))

    companion object {
        fun of(o: JSONObject): ChatFolder {
            fun list(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return ChatFolder(o.getString("id"), o.optString("title"), o.optString("preset").takeIf { it.isNotEmpty() && it != "null" },
                list("kinds"), list("chats"), list("leaves"), list("excluded"))
        }
    }
}

object ChatFolders {
    const val ALL = "all"
    // the kinds (iOS MTChatFolder.Kind) and the states a folder leaves out (iOS Leave), by the iOS names
    const val CONTACTS = "contacts"; const val NON_CONTACTS = "nonContacts"; const val GROUPS = "groups"; const val CHANNELS = "channels"; const val BOTS = "bots"
    const val MUTED = "muted"; const val READ = "read"; const val ARCHIVED = "archived"
    private const val KEY = "chatFolders"

    /** THE FIRST TABS, in the author's order (17.10.2026): All, the chats, the groups, the channels, the bots. */
    private val firstFolders = listOf(
        ChatFolder(ALL, leaves = emptyList()),
        ChatFolder("chats", preset = CONTACTS, kinds = listOf(CONTACTS, NON_CONTACTS)),
        ChatFolder("groups", preset = GROUPS, kinds = listOf(GROUPS)),
        ChatFolder("channels", preset = CHANNELS, kinds = listOf(CHANNELS)),
        ChatFolder("bots", preset = BOTS, kinds = listOf(BOTS)))

    private var held: MutableList<ChatFolder>? = null
    fun folders(): List<ChatFolder> = synchronized(this) {
        held ?: run {
            val kept = runCatching { JSONArray(Prefs.str(KEY, "[]")).let { a -> (0 until a.length()).map { ChatFolder.of(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
            (if (kept.any { it.isAll }) kept else firstFolders).toMutableList().also { held = it }
        }
    }
    fun folder(id: String) = folders().firstOrNull { it.id == id }
    fun remove(id: String) = synchronized(this) {
        if (id == ALL) return
        val f = folders().toMutableList().apply { removeAll { it.id == id } }
        held = f; Prefs.setStr(KEY, JSONArray(f.map { it.json() }).toString())
    }

    /** What a conversation is to the folders -- one answer for the tabs, their badges and their rows (iOS kind(of:)). */
    fun kindOf(ref: String): String = when {
        ref == Bot.REF -> BOTS
        Groups.isKey(ref) -> if (Groups.isChannel(ref)) CHANNELS else GROUPS
        else -> NON_CONTACTS   // the first folders take both; no folder tells the contacts from the rest yet
    }
    /** A folder's rows in the chats' one order: the rows of the list, and the archived ones where the folder keeps them. */
    fun rows(f: ChatFolder, live: List<Chat>, archived: List<Chat>): List<Chat> {
        if (f.isAll) return live
        fun keep(c: Chat, gone: Boolean) = f.takes(c.ref, kindOf(c.ref), ChatMarks.isMuted(c.ref), ChatMarks.hasUnread(c), gone)
        val here = live.filter { keep(it, false) }
        return if (ARCHIVED in f.leaves) here else here + archived.filter { keep(it, true) }
    }
    /** A tab's badge: the folder's unread conversations, and whether one of them is not muted -- the badge's blue. */
    fun badge(rows: List<Chat>): Pair<Int, Boolean> {
        var n = 0; var loud = false
        for (c in rows) if (ChatMarks.hasUnread(c)) { n++; if (!ChatMarks.isMuted(c.ref)) loud = true }
        return n to loud
    }
    /** Whether the folders' own tabs show the room of one's own (iOS: Saved Messages is a personal chat). */
    fun showsSaved(f: ChatFolder) = f.isAll || (CONTACTS in f.kinds || NON_CONTACTS in f.kinds)

    /**
     * A HOLD ON A TAB (iOS folderMenu): Read All while one of the folder's chats is unread, Mute All or Unmute All, Remove (asked
     * first). The folder's editing, its chats and the tabs' order wait for the folders' page.
     */
    fun menu(act: MainActivity, f: ChatFolder, held: View, rows: List<Chat>, removed: () -> Unit) {
        if (f.isAll) return
        val mine = rows.filter { it.ref != Bot.REF }
        val deeds = mutableListOf<Deed>()
        if (mine.any { ChatMarks.hasUnread(it) }) deeds += Deed(R.string.cf_read_all, R.drawable.ic_mark_read) {
            for (c in mine) if (ChatMarks.hasUnread(c)) ChatMarks.toggleUnread(c)
        }
        if (mine.isNotEmpty()) {
            val muted = mine.all { ChatMarks.isMuted(it.ref) }
            deeds += Deed(if (muted) R.string.cf_unmute_all else R.string.cf_mute_all, if (muted) R.drawable.ic_set_bell else R.drawable.ic_bell_off) {
                for (c in mine) if (ChatMarks.isMuted(c.ref) == muted) ChatMarks.toggleMute(c.ref)
            }
        }
        deeds += Deed(R.string.cf_remove, R.drawable.ic_delete, red = true) {
            AlertDialog.Builder(act).setTitle(act.getString(R.string.cf_remove_q, f.shownTitle(act))).setMessage(R.string.cf_remove_note)
                .setPositiveButton(R.string.cf_remove) { _, _ -> remove(f.id); removed() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        holdMenu(act, held, deeds)
    }

    /** The folder holds nothing (iOS folderEmpty): the reference's words under a folder's glyph. */
    fun emptyCard(c: Context): View = c.vstack {
        setPadding(c.dp(24), c.dp(60), c.dp(24), c.dp(20))
        addView(c.text(c.getString(R.string.cf_empty), 17f, Color.WHITE, bold = true, center = true), lp())
        gap(6)
        addView(c.text(c.getString(R.string.cf_empty_note), 15f, MT.gray, center = true), lp())
    }
}

/**
 * OUR BOT (iOS MTBot; the author's word 17.10.2026 18:4x MSK: «a category Bots, and our bot there»): Montana's bot, the door to the
 * wallet and to Montana as its Mini App. A row of the Bots folder and of All; a tap opens the bot where it lives -- it speaks no
 * letter here and keeps no conversation on this phone.
 */
object Bot {
    const val REF = "bot:MTBusiness_bot"
    private const val NAME = "MTBusiness_bot"   // the name the bot claimed where it lives
    private const val LINK = "https://t.me/MTBusiness_bot"
    val chat get() = Chat(REF, NAME)
    fun open(c: Context) { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LINK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    /** The bot's row in the chats page's measure (iOS ChatRow with library: 72, the face 48, the gap 16, the side 18). */
    fun row(c: Context): View = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(c.dp(18), 0, c.dp(18), 0)
        minimumHeight = c.dp(72)
        addView(FrameLayout(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
            clipToOutline = true
            addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.logo); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(c.dp(48), c.dp(48)))
        }, lp(c.dp(48), c.dp(48)).apply { marginEnd = c.dp(16) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.text(NAME, 17f, Color.WHITE, bold = true).apply { maxLines = 1 }, lp(MATCH, c.dp(22)))
            addView(c.text(c.getString(R.string.cf_bot), 16f, MT.gray).apply { maxLines = 1 }, lp(MATCH, c.dp(20)).apply { topMargin = c.dp(4) })
        }, lp(0, WRAP, 1f))
        pressable { open(c) }
    }
}

/**
 * THE FOLDERS' TABS (iOS MTFolderTabs at 2240): one plate the page's width in the time panel's own glass, the names from its left
 * edge (44 high, 6 between two tabs, each at least 44 wide, 10 inside), the chosen one on the time panel's puck that slides to the
 * next, each name followed by its badge -- the folder's unread conversations, blue while one of them is not muted, grey otherwise;
 * All wears none. The strip scrolls under the finger when the names do not fit. A hold on a tab is that folder's menu.
 */
class FolderTabs(private val c: Context, private val pick: (String) -> Unit, private val hold: (String, View) -> Unit) : FrameLayout(c) {
    class Tab(val id: String, val title: String, val unread: Int, val loud: Boolean)
    private val puck = View(c).apply {
        background = GradientDrawable().apply { cornerRadius = c.dp(100).toFloat(); setColor(Color.argb(41, 255, 255, 255)) }   // iOS MTBarPuck
    }
    private val strip = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(4), 0, c.dp(4), 0) }
    private val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
    private var chosen = ChatFolders.ALL
    private var placed = false

    init {
        background = c.glassPlate()   // the time panel's own plate (the author's word 18.10.2026 10:2x MSK)
        scroll.addView(FrameLayout(c).apply {
            addView(puck, LayoutParams(0, c.dp(36), Gravity.CENTER_VERTICAL))
            addView(strip, LayoutParams(WRAP, c.dp(44)))
        }, LayoutParams(WRAP, c.dp(44)))
        addView(scroll, LayoutParams(MATCH, c.dp(44)))
    }

    fun show(tabs: List<Tab>, chosenId: String) {
        val moved = chosenId != chosen
        chosen = chosenId
        strip.removeAllViews()
        for (t in tabs) strip.addView(tab(t), LinearLayout.LayoutParams(WRAP, c.dp(44)).apply { marginEnd = c.dp(6) })
        strip.post { placePuck(animated = moved && placed) }
    }

    private fun tab(t: Tab): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        minimumWidth = c.dp(44)
        setPadding(c.dp(10), 0, c.dp(10), 0)
        tag = t.id
        addView(TextView(c).apply {
            text = t.title; textSize = 14f; setTextColor(Color.WHITE); maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (t.unread > 0) addView(TextView(c).apply {
            text = t.unread.toString(); textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1
            minWidth = c.dp(18); minHeight = c.dp(18); setPadding(c.dp(4), 0, c.dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = c.dp(9).toFloat()
                setColor(if (t.loud || t.id == chosen) SysColor.blue else SysColor.gray)
            }
        }, LinearLayout.LayoutParams(WRAP, c.dp(18)).apply { marginStart = c.dp(4) })
        contentDescription = t.title
        setOnClickListener { pick(t.id) }
        setOnLongClickListener { hold(t.id, this); true }
    }

    /** The puck under the chosen name, and the chosen name brought into sight. */
    private fun placePuck(animated: Boolean) {
        val v = (0 until strip.childCount).map { strip.getChildAt(it) }.firstOrNull { it.tag == chosen } ?: return
        val x = (strip.left + v.left).toFloat()
        val lpk = puck.layoutParams as LayoutParams
        if (lpk.width != v.width) { lpk.width = v.width; puck.layoutParams = lpk }
        if (animated) puck.animate().translationX(x).setDuration(250).start() else puck.translationX = x
        placed = true
        val target = (v.left + v.width / 2 - scroll.width / 2).coerceAtLeast(0)
        if (animated) scroll.smoothScrollTo(target, 0) else scroll.scrollTo(target, 0)
    }
}
