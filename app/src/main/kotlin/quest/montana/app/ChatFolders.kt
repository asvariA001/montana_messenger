package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE FOLDERS THE GROUPS AND CHANNELS PAGES READ (iOS MTChatFolders after fc8e3744, build 2372; the author's words 19110 and 19116,
 * 11.11.2026: Groups and Channels go to the side panel, the strip of folders under the time panel on the chats page goes entirely;
 * decision 831 = a: the folders' page in Settings goes with it). Only the model stays: a folder takes kinds of chats and chats by
 * name and leaves out states and chats by name; the drawer's Groups and Channels show the folder of their kind (ChatsPane.showKind).
 * Folders an older build kept are still read as the iOS «chatFolders», and no page edits them any more. No bot and no Bots folder
 * (the author's words 16283, 16284 and decision 617, 04.11.2026; iOS 650002fb): a kept folder loses a kind this build does not know.
 */
data class ChatFolder(
    val id: String,
    val title: String = "",
    val preset: String? = null,
    val kinds: List<String> = emptyList(),
    val chats: List<String> = emptyList(),
    val leaves: List<String> = listOf(ChatFolders.ARCHIVED),
    val excluded: List<String> = emptyList(),
) {
    val isAll get() = id == ChatFolders.ALL
    /** The folder of one kind alone: the corner of the groups' and the channels' folders gives birth to that kind (iOS onlyKind). */
    val onlyKind get() = if (kinds.size == 1 && chats.isEmpty()) kinds[0] else null
    val takesAnything get() = kinds.isNotEmpty() || chats.isNotEmpty()
    fun shownTitle(c: Context): String {
        if (isAll) return c.getString(R.string.cf_all)
        if (title.isNotEmpty()) return title   // USER-DATA: the person's own name of a folder
        return when (preset) {
            ChatFolders.GROUPS -> c.getString(R.string.app_groups)
            ChatFolders.CHANNELS -> c.getString(R.string.app_channels)
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
    const val CONTACTS = "contacts"; const val NON_CONTACTS = "nonContacts"; const val GROUPS = "groups"; const val CHANNELS = "channels"
    const val MUTED = "muted"; const val READ = "read"; const val ARCHIVED = "archived"
    val KINDS = listOf(CONTACTS, NON_CONTACTS, GROUPS, CHANNELS)
    private const val KEY = "chatFolders"

    /**
     * THE THREE PAGES' FOLDERS, ONE OWNER EACH (iOS MTChatFolder.chats / .groups / .channels, 25d91347, OZ 1 p.1.11; the author's
     * word 21137: «in the chats only chats, in the groups only groups and in the channels only channels»): the first folders and
     * the pages read these, none states them again.
     */
    val CHATS_FOLDER = ChatFolder("chats", preset = CONTACTS, kinds = listOf(CONTACTS, NON_CONTACTS))
    val GROUPS_FOLDER = ChatFolder("groups", preset = GROUPS, kinds = listOf(GROUPS))
    val CHANNELS_FOLDER = ChatFolder("channels", preset = CHANNELS, kinds = listOf(CHANNELS))
    /** THE FIRST FOLDERS (17.10.2026): All, the chats, the groups, the channels. */
    private val firstFolders = listOf(ChatFolder(ALL, leaves = emptyList()), CHATS_FOLDER, GROUPS_FOLDER, CHANNELS_FOLDER)

    private var held: List<ChatFolder>? = null
    fun folders(): List<ChatFolder> = synchronized(this) {
        (held ?: run {
            val kept = runCatching { JSONArray(Prefs.str(KEY, "[]")).let { a -> (0 until a.length()).map { ChatFolder.of(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
                .map { f -> f.copy(kinds = f.kinds.filter { it in KINDS }) }.filter { it.isAll || it.takesAnything }   // the Bots folder of older builds leaves
            (if (kept.any { it.isAll }) kept else firstFolders).also { held = it }
        }).toList()
    }
    fun folder(id: String) = folders().firstOrNull { it.id == id }
    /** The people are read only while a folder tells the contacts from the rest: the first folders take both (iOS tellsContacts). */
    fun contacts(): Set<String>? =
        if (folders().any { (CONTACTS in it.kinds) != (NON_CONTACTS in it.kinds) }) contactPeople().map { it.ref }.toSet() else null
    /** What a conversation is to the folders (iOS kind(of:)). */
    fun kindOf(ref: String, contacts: Set<String>?): String = room(ref) ?: if (contacts?.contains(ref) == true) CONTACTS else NON_CONTACTS
    /**
     * THE LIST A CONVERSATION STANDS IN ON THE TIME PANEL, ONE ANSWER (iOS MTChatFolders.room, d096082e, OZ 1 p.5, the author's word
     * 21393: «every notification in its place, over its own icon»): the Montana room and every channel -- CHANNELS, a group -- GROUPS,
     * null -- a person's chat (Chats). The folders, the drawer's counts and the panel's badges read it here.
     */
    fun room(ref: String): String? = when {
        Groups.isKey(ref) -> if (Groups.isChannel(ref)) CHANNELS else GROUPS
        // THE MONTANA ROOM IS A CHANNEL (iOS 11c34ed7 at 2389, OZ 1 p.1.5, the author's words 21257 and 21282: «the official Montana
        // channel with the updates must not stand in the list of chats -- it is a channel»)
        Book.isMontana(ref) -> CHANNELS
        else -> null
    }
    /** The conversations with something unread in one list of the panel (iOS MTApplication.badge for .chats, .groups, .channels). */
    fun unread(room: String?): Int = Book.listed().count { room(it.ref) == room && ChatMarks.hasUnread(it) }
    /** A folder's rows in the chats' one order: the rows of the list, and the archived ones where the folder keeps them. */
    fun rows(f: ChatFolder, live: List<Chat>, archived: List<Chat>, contacts: Set<String>? = contacts()): List<Chat> {
        if (f.isAll) return live
        fun keep(c: Chat, gone: Boolean) = f.takes(c.ref, kindOf(c.ref, contacts), ChatMarks.isMuted(c.ref), ChatMarks.hasUnread(c), gone)
        val here = live.filter { keep(it, false) }
        return if (ARCHIVED in f.leaves) here else here + archived.filter { keep(it, true) }
    }
    /** Whether a folder shows the room of one's own (iOS: Saved Messages is a personal chat). */
    fun showsSaved(f: ChatFolder) = f.isAll || (CONTACTS in f.kinds || NON_CONTACTS in f.kinds)

    /** The folder holds nothing (iOS folderEmpty): the reference's words under a folder's glyph. */
    fun emptyCard(c: Context): View = c.vstack {
        setPadding(c.dp(24), c.dp(60), c.dp(24), c.dp(20))
        addView(c.text(c.getString(R.string.cf_empty), 17f, Color.WHITE, bold = true, center = true), lp())
        gap(6)
        addView(c.text(c.getString(R.string.cf_empty_note), 15f, MT.gray, center = true), lp())
    }
}
