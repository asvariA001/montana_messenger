package quest.montana.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Date

// ─────────────────────────── what a row says of a letter (iOS MTRowLetter) ───────────────────────────

/** The words a letter shows in the list and the banner: its text, or the name of what it carries. */
fun letterWords(c: Context, t: String, meta: String? = null): String = when {
    t.startsWith(Marks.CALL) -> Calls.info(t)?.let { Calls.words(c, it) } ?: ""
    t.startsWith(Marks.VOICE) -> c.getString(R.string.voice_message)
    t.startsWith(Marks.MEDIA) -> mediaWords(c, meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(t))
    t.startsWith(Marks.STICKER) -> t.removePrefix(Marks.STICKER)
    t.startsWith(Marks.LONG) -> c.getString(R.string.long_letter)
    // a game's letter: the knight and the word (iOS MTRowLetter.preview)
    ChessLetter.parse(t) != null -> ChessLetter.MARK + c.getString(R.string.chess_title)
    // a coin letter: the coin and the number (iOS MTRowLetter.coinCount)
    CoinLetter.parse(t) != null -> CoinLetter.preview(c, t) ?: t
    // a shared secret's every preview names no title and no content (iOS MTRowLetter.preview)
    SecretLetter.isOne(t) -> "\uD83D\uDD11 " + c.getString(R.string.pw_shared)
    else -> t
}
/** The same words for a letter of the book: its manifest, once read, names what it carries. */
fun letterWords(c: Context, m: Msg): String = letterWords(c, m.text, m.meta)

/**
 * THE WORDS OF A MEDIA LETTER (iOS MTRowLetter.mediaWords, mediaPreview): one answer for the row, the banner and the reply
 * strip, read from the manifest — a round note is a video message, as a voice is a voice message; a sticker is a sticker, a
 * moving picture a GIF. A manifest not read yet (sealed in its blob) is «Media».
 */
fun mediaWords(c: Context, man: JSONObject?): String {
    if (man == null) return c.getString(R.string.lw_media)
    val cap = man.optString("cap").trim()
    val name = man.optString("n").ifEmpty { man.optString("name") }
    return when (man.optString("k")) {
        "img" -> if (name == Stickers.CARD) c.getString(R.string.lw_sticker) else "📷 " + cap.ifEmpty { c.getString(R.string.lw_photo) }
        "vid", "video" -> "📹 " + if (man.optBoolean("r")) c.getString(R.string.lw_video_message) else cap.ifEmpty { c.getString(R.string.lw_video) }
        "aud" -> c.getString(R.string.voice_message)
        "doc" -> if (name.lowercase().endsWith(".gif")) c.getString(R.string.lw_gif) else "📄 " + name.ifEmpty { c.getString(R.string.file) }
        else -> c.getString(R.string.lw_media)
    }
}

/** The row's words without the glyph the vocabulary puts first, when a thumbnail stands there (iOS MontanaRowWords.bare). */
fun bareWords(p: String): String = listOf("🎤 ", "📹 ", "📷 ", "📄 ", "📞 ").firstOrNull { p.startsWith(it) }?.let { p.removePrefix(it) } ?: p

private val AUDIO_NAMES = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "aif", "aiff")

/**
 * THE ROW'S ONE WORD FOR WHAT A LETTER CARRIES (iOS ChatStore.rowMediaKind): the voice, the round note, the video, the photo,
 * music, a file — by the manifest, once the file is here — and the link of a letter of words. The thumbnail draws by it.
 */
fun rowKind(m: Msg): String? {
    Calls.info(m.text)?.let { return if (it.video) "vcall" else "acall" }
    if (!m.text.startsWith(Marks.MEDIA)) return if (LinkPreview.webURLs(m.text).isNotEmpty()) "link" else null
    val f = m.file?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return null
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text)
    return when (man?.optString("k")) {
        "aud" -> "aud"
        "vid", "video" -> if (man.optBoolean("r")) "vnote" else "vid"
        "img" -> "img"
        "doc" -> if (man.optString("n").substringAfterLast('.', "").lowercase() in AUDIO_NAMES || f.extension.lowercase() in AUDIO_NAMES) "music" else "doc"
        else -> null
    }
}

private val rowPosters = android.util.LruCache<String, android.graphics.Bitmap>(48)

/** The small picture of a letter: the face its manifest carries, else the file's own first frame, kept while the list lives. */
private fun rowPoster(c: Context, m: Msg, kind: String): android.graphics.Bitmap? {
    rowPosters.get(m.mid)?.let { return it }
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text)
    val pic = Media.thumbOf(man)
        ?: m.file?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { Media.preview(c, it, if (kind == "img") "img" else "vid", 96) }
    pic?.let { rowPosters.put(m.mid, it) }
    return pic
}

/**
 * ONE THUMBNAIL OF WHAT A LETTER CARRIES (iOS MTLetterThumb; side 20 in the list row): the voice's round of glass with the
 * quiet prism sheen (MTVoiceRound), the round note's window with its poster (MontanaNoteFrame), the photo and the video's
 * poster, and the quiet plate with the system's glyph for music, a file, a link.
 */
fun Context.letterThumb(m: Msg, kind: String, sideDp: Int = 20): View {
    val side = dp(sideDp)
    fun plate(glyph: Int) = FrameLayout(this).apply {
        background = rounded(Color.rgb(41, 41, 41), sideDp * 0.22f)
        addView(icon(glyph, MT.gray), FrameLayout.LayoutParams(side / 2, side / 2, Gravity.CENTER))
    }
    return when (kind) {
        "aud" -> FrameLayout(this).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(glassPlate(oval = true), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.SWEEP_GRADIENT
                colors = intArrayOf(Color.argb(56, 50, 173, 230), Color.argb(56, 175, 82, 222), Color.argb(56, 255, 45, 85), Color.argb(56, 50, 173, 230))
                setStroke(1, Color.argb(89, 255, 255, 255))
            }))
            addView(icon(R.drawable.ic_play_fill, Color.WHITE), FrameLayout.LayoutParams(side * 2 / 5, side * 2 / 5, Gravity.CENTER))
        }
        "vnote", "img", "vid" -> {
            val pic = rowPoster(this, m, kind)
            if (pic == null && kind != "vnote") plate(if (kind == "img") R.drawable.ic_photo else R.drawable.ic_peer_video)
            else ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (pic != null) setImageBitmap(pic) else setBackgroundColor(Color.BLACK)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) =
                        if (kind == "vnote") o.setOval(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, v.width * 0.22f)
                }
                clipToOutline = true
            }
        }
        "music" -> plate(R.drawable.ic_play_fill)
        "link" -> plate(R.drawable.ic_link)
        "acall" -> plate(R.drawable.ic_peer_phone)
        "vcall" -> plate(R.drawable.ic_peer_video)
        else -> plate(R.drawable.ic_set_doc)
    }
}

/** The face of a correspondent: the face laid beside the pipe, else the drawn initial (iOS MontanaAvatar). */
fun Context.peerFace(ref: String, name: String, sizeDp: Int): View {
    val f = Book.shownFace(ref)
    val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    return avatar(bmp, name.ifBlank { "?" }, sizeDp)
}

// ─────────────────────────── the banner (iOS MontanaNotify) ───────────────────────────

object Notify {
    private const val CHANNEL = "letters"
    private const val QUIET = "letters_quiet"
    fun letter(ref: String, text: String, who: String? = null) {
        val c = Book.ctx
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!Prefs.bool("notifMessages", true)) return
        if (ChatMarks.isMuted(ref)) return   // muted: no banner and no sound (iOS willPresent «why=muted»)
        // THE SOUND IS THE PERSON'S SWITCH (iOS MontanaNotify 379, NotificationService 400): a channel's sound is the platform's
        // once made, so the quiet letters have a channel of their own
        val sound = Prefs.bool("notifSound", true)
        val channel = if (sound) CHANNEL else QUIET
        nm.createNotificationChannel(NotificationChannel(channel, c.getString(if (sound) R.string.channel_letters else R.string.channel_letters_quiet), NotificationManager.IMPORTANCE_HIGH).apply {
            if (!sound) { setSound(null, null); enableVibration(false) }
        })
        val chat = Book.chat(ref)
        val title = if (Prefs.bool("notifSender", true)) chat?.shown?.ifBlank { null } ?: c.getString(R.string.peer) else "Montana"
        val shown = if (!Prefs.bool("notifPreview", true)) c.getString(R.string.new_message)
            else if (ContentFilter.hides(false, text)) c.getString(R.string.filter_hidden)   // the banner folds as the feed does (iOS NotificationService 399)
            else letterWords(c, text)
        val body = if (who.isNullOrEmpty()) shown else "$who: $shown"   // a group's letter says who wrote it (iOS presentGroup)
        val open = PendingIntent.getActivity(c, ref.hashCode(),
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)   // USER-DATA: the correspondent's name
            .setContentText(body)     // USER-DATA: the letter
            .setAutoCancel(true)
            .setContentIntent(open)
            // THE NAME STANDS ON THE LOCKED SCREEN (iOS 1715, hiddenPreviewsShowTitle, MontanaP2PNode 1268-1290): with the system
            // hiding a locked phone's contents, the person's name stays and the words read «New message»; the switch can take it
            .setPublicVersion(Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (Prefs.bool("notifLockName", true)) title else "Montana")   // USER-DATA: the correspondent's name
                .setContentText(c.getString(R.string.new_message))
                .build())
            .build()
        runCatching { nm.notify(ref.hashCode(), n) }
    }
    fun clear(ref: String) { Book.ctx.getSystemService(NotificationManager::class.java)?.cancel(ref.hashCode()) }
}

// ─────────────────────────── the chats as rows (iOS MTChatListView rows) ───────────────────────────

/** One conversation in the list: the face, the name, the last letter, its time and the unread count. */
fun chatRow(act: MainActivity, chat: Chat): View {
    val c: Context = act
    val last = chat.last
    return c.hstack {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(c.peerFace(chat.ref, chat.shown, 54), lp(dp(54), dp(54)).apply { marginEnd = dp(12) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                addView(callHandset(act, true, chat.ref), lp(dp(44), dp(44)))   // 15.8 (iOS ChatRow 2143)
                addView(c.text(chat.shown.ifBlank { c.getString(R.string.peer) }, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: the name
                addView(callHandset(act, false, chat.ref), lp(dp(44), dp(44)))   // 15.8 (iOS ChatRow 2150)
                // a muted chat wears the struck bell after its name (iOS ChatRow muted)
                if (ChatMarks.isMuted(chat.ref)) addView(c.icon(R.drawable.ic_bell_off, MT.gray, 14), lp(dp(14), dp(14)).apply { marginEnd = dp(6) })
                if (last != null) {
                    // the ladder's dots left of the hour, only when the last word was mine; a letter that did not go, the red mark
                    if (last.mine) addView(if (last.state == -1) c.failedMark() else c.deliveryDots(Ladder.of(chat.ref, last).first),
                        lp(WRAP, WRAP).apply { marginEnd = dp(6) })
                    addView(c.text(rowTime(c, last.at), 13f, MT.gray))
                }
            }, lp())
            addView(c.hstack {
                // THE THUMBNAIL BEFORE THE WORDS (iOS MTLetterThumb, side 20, spacing 6): the voice's round and the round note's
                // poster stand in the row as in the player bar; the words then carry no glyph of their own
                val kind = last?.let { rowKind(it) }
                if (last != null && kind != null) addView(c.letterThumb(last, kind), lp(dp(20), dp(20)).apply { marginEnd = dp(6); gravity = Gravity.CENTER_VERTICAL })
                // a letter the filter hides is told as hidden in the row, opened or not (iOS ChatStore 159)
                val words = last?.let { if (ContentFilter.hides(it.mine, it.text)) c.getString(R.string.filter_hidden) else letterWords(c, it) } ?: ""
                addView(c.text(if (kind != null) bareWords(words) else words, 15f, MT.gray).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))   // USER-DATA: the last letter
                if (chat.unread > 0) addView(c.text(chat.unread.toString(), 13f, Color.WHITE, bold = true, center = true).apply {
                    // a muted chat's number is grey, as iOS draws it
                    background = c.rounded(if (ChatMarks.isMuted(chat.ref)) MT.gray else MT.blue, 11); setPadding(dp(7), dp(1), dp(7), dp(1)); minWidth = dp(22)
                }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
                // the hand mark «unread» where nothing is unread: the row's dot (iOS forcedUnread)
                else if (ChatMarks.handMark(chat.ref)) addView(c.icon(R.drawable.ic_dot, MT.blue, 12), lp(dp(12), dp(12)).apply { marginStart = dp(6) })
                // a pinned chat with nothing unread shows its pin (iOS ChatRow pinned)
                if (chat.unread == 0 && !ChatMarks.handMark(chat.ref) && ChatMarks.isPinned(chat.ref))
                    addView(c.icon(R.drawable.ic_pin, MT.gray, 16), lp(dp(16), dp(16)).apply { marginStart = dp(6) })
            }, lp())
        }, lp(0, WRAP, 1f))
        pressable { act.push { close -> conversationPage(act, chat.ref, close) } }
        // the list sets the long press itself (ChatList.listRow: the person's menu)
    }
}

// ─────────────────────────── the delivery ladder (iOS MTDeliveryLine, MTDeliveryDots, MTPlayed) ───────────────────────────

/**
 * THE LADDER OF ONE'S OWN LETTER: sending → sent → delivered → read, each rung with its one word. A voice of mine is played,
 * not read: its third rung is the correspondent's own playing («Listened»); a correspondent whose build has said «played»
 * once keeps a mere «read» of a voice at «delivered» — the chat opened is no playing.
 */
object Ladder {
    private fun isVoice(m: Msg) = m.text.startsWith(Marks.MEDIA) &&
        ((m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text))?.optString("k") == "aud")

    /** The rung the dots stand on (0…3) and the word's resource. */
    fun of(ref: String, m: Msg): Pair<Int, Int> {
        if (m.state == -1) return 0 to R.string.ladder_failed
        if (isVoice(m)) {
            if (m.heard) return 3 to R.string.ladder_listened
            if (m.state == 3 && Prefs.bool("plcap_$ref", false)) return 2 to R.string.ladder_delivered
        }
        return m.state to when (m.state) {
            0 -> R.string.ladder_sending; 1 -> R.string.ladder_sent; 2 -> R.string.ladder_delivered; else -> R.string.ladder_read
        }
    }

    /** The rung's moment: the hour today, the day and the hour before (iOS MTDeliveryLine.moment). */
    fun moment(c: Context, at: Long): String {
        val t = android.text.format.DateFormat.getTimeFormat(c).format(Date(at))
        return if (DateUtils.isToday(at)) t else DateUtils.formatDateTime(c, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " " + t
    }
}

/** THE THREE DOTS (iOS MTDeliveryDots): five points each, five apart; green once their rung is reached, dark grey before. */
fun Context.deliveryDots(rung: Int): View = hstack {
    gravity = Gravity.CENTER_VERTICAL
    for (k in 1..3) addView(View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (rung >= k) MT.green else Color.rgb(77, 77, 77)) }
    }, lp(dp(5), dp(5)).apply { if (k > 1) marginStart = dp(5) })
}

/** THE LADDER LINE (iOS MTDeliveryLine): the dots, the one word of the rung and — over the menu — the moment it was reached. */
fun Context.deliveryLine(ref: String, m: Msg, withMoment: Boolean = false): View = hstack {
    gravity = Gravity.CENTER_VERTICAL
    val (rung, word) = Ladder.of(ref, m)
    addView(deliveryDots(rung))
    addView(text(getString(word), 12f, Color.WHITE, bold = true), lp(WRAP, WRAP).apply { marginStart = dp(5) })
    if (withMoment) addView(text(Ladder.moment(context, m.statusMoment), 12f, Color.WHITE), lp(WRAP, WRAP).apply { marginStart = dp(5) })
}

/** THE RED MARK of a letter that did not go (iOS MTFailedMark): the bubble's corner and the chat row wear the same one. */
fun Context.failedMark(): View = text("!", 9f, Color.WHITE, bold = true, center = true).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SysColor.red) }
    layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
    gravity = Gravity.CENTER
    setPadding(0, 0, 0, 0)
}

// ─────────────────────────── the conversation (iOS ChatConversationView) ───────────────────────────

/**
 * THE CONVERSATION (iOS ChatConversationView): the bar with the back mark, the person's name and face; the letters, newest at
 * the foot, a day's plate over each new day, each letter with its time, its ticks when it is one's own, «edited» when it was,
 * the quote it answers and the reactions it wears; the field on glass with the send artwork. A long press on a letter opens its
 * menu as iOS draws it: the quick reactions over the letter and the actions under it — Reply, Copy, Edit (one's own), Delete
 * (for me / for everyone). The feed follows the book live: a letter that lands, a receipt, a reaction redraw it.
 */
private var noteRing: NoteRing? = null
private var noteTime: TextView? = null

/** `jump` — a letter found by the search: the chat opens on it, and it glows once (iOS openChat(jump:)). */
fun conversationPage(act: MainActivity, ref: String, onClose: () -> Unit, jump: String? = null): View {
    val c: Context = act
    val group = Groups.isKey(ref)   // a group's feed: no pipe, no presence, no doors (iOS MTGroup.isKey)
    val column = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(10), dp(8), dp(10), dp(8)) }
    val draftRow = FrameLayout(c).apply { setPadding(c.dp(10), 0, c.dp(10), c.dp(8)); visibility = View.GONE }
    val feedFrame = FrameLayout(c)
    val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        // the feed, and under it their live draft (iOS LiveDraftBubble at the feed's foot)
        addView(feedFrame.apply { addView(c.vstack(Gravity.NO_GRAVITY) { addView(column, lp()); addView(draftRow, lp()) }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)) })
    }
    fun toBottom() = scroll.post { scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0) }

    var replyTo: Msg? = null
    var editing: Msg? = null
    // THE CHAT'S SELECTION (iOS selectingMsgs, selectedMsgs): «Select» in a letter's menu begins it
    var selecting = false
    val picked = linkedSetOf<String>()
    lateinit var drawFoot: () -> Unit
    var toQuoted: (String) -> Unit = {}

    val field = EditText(c).apply { setText(Prefs.str("draft.$ref", "")) }
    // THE KEYBOARD AWAY IS THE FIELD LET GO (iOS inputFocused = false with hideKeyboard): a field kept focused under a hidden keyboard
    // is raised again by the system each time the window comes forward (SHOW_AUTO_EDITOR_FORWARD_NAV, the Pixel's own record 07.10 22:35)
    fun hideKeys() { c.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0); field.clearFocus() }
    fun showKeys() { field.requestFocus(); c.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }

    // AN EMPTY FEED SAYS WHICH EMPTINESS IT IS (iOS MTFeedEmpty, MontanaMessageFeed 227-265; feedEmptyKind, MontanaConversation
    // 2324-2334): a chat that never had a word invites the first one and a tap opens the keyboard; one whose words cannot leave
    // says so — a blank made the person wait for a letter that could not come.
    val emptyGlyph = c.icon(R.drawable.ic_antenna_slash, MT.gray, 30)
    val emptyTitle = c.text("", 17f, Color.WHITE, bold = true, center = true)
    val emptyNote = c.text("", 13f, MT.gray, center = true)
    val emptyView = c.vstack(Gravity.CENTER_HORIZONTAL) {
        visibility = View.GONE
        setPadding(dp(40), 0, dp(40), 0)
        addView(emptyGlyph, LinearLayout.LayoutParams(dp(30), dp(30)).apply { bottomMargin = dp(10) })
        addView(emptyTitle, lp())
        addView(emptyNote, lp().apply { topMargin = dp(10) })
        setOnClickListener { showKeys() }
    }
    feedFrame.addView(emptyView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    // THE OTHER SIDE CLOSED THIS CONVERSATION (iOS peerClosedPipe, the closed composer's note; fork atom 1927): its letters are read,
    // not answered — the words stand where the field stood
    var hideBar: () -> Unit = {}   // the field is born below; it hides once the conversation is closed
    val closedNote = c.text(c.getString(R.string.pipe_closed_note), 13f, MT.gray, center = true).apply {
        setPadding(dp(24), dp(12), dp(24), dp(14)); visibility = if (Post.closed(ref)) View.VISIBLE else View.GONE
    }
    fun drawEmpty(empty: Boolean) {
        if (!empty) { emptyView.visibility = View.GONE; return }
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val net = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val offline = !net || Signal.aliveHosts().isEmpty()
        emptyGlyph.visibility = if (offline) View.VISIBLE else View.GONE
        emptyTitle.setText(if (offline) R.string.feed_no_network else R.string.feed_fresh)
        emptyNote.setText(if (offline) R.string.feed_no_network_note else R.string.feed_fresh_note)
        emptyView.isClickable = !offline   // only the one state with something to do takes a tap (iOS allowsHitTesting)
        emptyView.visibility = View.VISIBLE
    }

    // THE LINE OVER THE FIELD (iOS the reply/edit strip): what the next letter answers, or which letter is being changed.
    val stripTitle = c.text("", 13f, MT.blue, bold = true)
    val stripWords = c.text("", 14f, Color.WHITE).apply { singleLineEllipsis() }
    val strip = c.hstack {
        visibility = View.GONE
        setPadding(dp(16), dp(6), dp(8), dp(2))
        addView(View(c).apply { setBackgroundColor(MT.blue) }, lp(dp(3), dp(34)).apply { marginEnd = dp(8) })
        addView(c.vstack(Gravity.NO_GRAVITY) { addView(stripTitle); addView(stripWords) }, lp(0, WRAP, 1f))
        addView(c.icon(R.drawable.ic_close, MT.gray).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            pressable { if (editing != null) field.setText(""); replyTo = null; editing = null; this@hstack.visibility = View.GONE }
        }, lp(dp(36), dp(36)))
    }
    // THE REPLY'S AND THE EDIT'S PLATE CARRIES THE EYE TO ITS LETTER ON A TAP (iOS build 1868, barPlate onTap — the pinned plate's road),
    // the keys staying up; its cross keeps its own tap
    strip.setOnClickListener { (editing ?: replyTo)?.let { toQuoted(it.mid) } }
    fun showStrip() {
        val r = replyTo; val e = editing
        strip.visibility = if (r == null && e == null) View.GONE else View.VISIBLE
        if (e != null) { stripTitle.text = c.getString(R.string.editing); stripWords.text = letterWords(c, e) }
        else if (r != null) {
            stripTitle.text = if (r.mine) Prefs.userName.ifBlank { c.getString(R.string.reply) }
                else if (group) Groups.speakerName(c, r.from)   // a group's letter is its speaker's (iOS speakerName)
                else Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
            stripWords.text = letterWords(c, r)   // USER-DATA
        }
    }

    // ── the feed ──
    fun redraw() {
        val chat = Book.chat(ref) ?: return
        column.removeAllViews()
        var lastDay = -1L
        // A MEDIA GROUP IS ONE ROW (iOS buildRows): its letters stand as one plate, chosen, forwarded and deleted together
        for (row in feedRows(chat.msgs.filter { !ChessLetter.isStep(it.text) })) {
            val m = row[0]
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = m.at }
            val day = cal.get(java.util.Calendar.YEAR) * 1000L + cal.get(java.util.Calendar.DAY_OF_YEAR)
            if (day != lastDay) {
                lastDay = day
                val words = if (DateUtils.isToday(m.at)) c.getString(R.string.today) else DateUtils.formatDateTime(c, m.at, DateUtils.FORMAT_SHOW_DATE)
                column.addView(FrameLayout(c).apply {
                    addView(c.text(words, 13f, Color.WHITE).apply { background = c.glassPlate(); setPadding(dp(10), dp(3), dp(10), dp(3)) },
                        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, lp().apply { topMargin = c.dp(8); bottomMargin = c.dp(8) })
            }
            val b = letterBubble(c, m, chat, row)
            if (selecting) {
                // WHILE THE CHAT SELECTS a letter is a choice: a circle on the left, a tap chooses (iOS the selection rows); a plate whole
                val chosen = m.mid in picked
                column.addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(FrameLayout(c).apply {
                        background = if (chosen) c.rounded(MT.gold, 11) else c.rounded(Color.TRANSPARENT, 11, Color.WHITE)
                        if (chosen) addView(c.icon(R.drawable.ic_check, Color.BLACK, 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                    }, lp(dp(22), dp(22)).apply { marginEnd = dp(8) })
                    addView(b.apply { isClickable = false }, lp(0, WRAP, 1f))
                    pressable { val mids = row.map { it.mid }; if (chosen) picked.removeAll(mids.toSet()) else picked.addAll(mids); redraw(); drawFoot() }
                }, lp().apply { topMargin = c.dp(3) })
                continue
            }
            // THE LADDER UNDER EVERY OWN BUBBLE (the author's word 29.09: «у каждого сообщения, по отдельности»): each letter its own rung
            if (m.mine) (b.getChildAt(0) as LinearLayout).addView(c.deliveryLine(ref, row.last()),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = c.dp(3); marginEnd = c.dp(4) })
            // THE MENU OPENS ON EVERY LETTER (iOS: the long press is the bubble's, whatever it holds): a picture, a film, a circle, a
            // voice or a link's card keeps its own tap, and its long press opens the letter's menu as the words' does
            b.getChildAt(0).holdEverywhere { letterMenu(act, ref, m, onReply = { replyTo = m; editing = null; showStrip(); showKeys() },
                onEdit = { editing = m; replyTo = null; field.setText(m.text); field.setSelection(field.text.length); showStrip(); showKeys() },
                onForward = { hideKeys(); act.push { close -> forwardPage(act, ref, row, close) { to -> close(); if (to != ref) { onClose(); act.push { c2 -> conversationPage(act, to, c2) } } } } },
                onSelect = { hideKeys(); selecting = true; picked.clear(); picked.addAll(row.map { it.mid }); redraw(); drawFoot() }, group = group, members = row); true }
            // the pull to the left answers, as the menu's «Reply»; in a group, while this phone writes into it
            if (!group || Groups.canWrite(ref)) b.onReply = { replyTo = m; editing = null; showStrip(); showKeys() }
            b.tag = m.mid
            // TEXT WITH A QUOTE — A TAP GOES TO THE ORIGINAL (iOS MontanaBubble 475, jumpTo 2999-3012)
            val q = m.qm
            if (q != null && m.qt != FORWARDED_QUOTE && !Marks.isService(m.text)) b.getChildAt(0).setOnClickListener { toQuoted(q) }
            column.addView(b, lp().apply { topMargin = c.dp(3) })
        }
        drawEmpty(column.childCount == 0)
        // the other side closed it: read, not answered (iOS closedChats) — the note where the field stood
        if (Post.closed(ref)) { hideBar(); closedNote.visibility = View.VISIBLE }
    }

    // ── sending (iOS sendMessage) ──
    var typedAt = 0L
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (editing == null) Prefs.setStr("draft.$ref", s?.toString() ?: "")
            // a group says neither: it has no pipe of its own (iOS: a group streams no draft)
            if (group) return
            // «TYPING…» TO THEM (iOS: a word on the lane, at most every three seconds, only while there are words)
            if (!s.isNullOrBlank() && System.currentTimeMillis() - typedAt > 3000) { typedAt = System.currentTimeMillis(); Presence.sayTyping(ref) }
            // MY WORDS AS I TYPE THEM (iOS sendDraft): the field and its caret; emptied — sent or erased — the draft ends
            if (editing == null) LiveDraft.say(ref, s?.toString()?.takeIf { it.isNotBlank() } ?: "", field.selectionEnd, replyTo?.mid ?: "")
        }
    })
    // WHAT WAITS ABOVE THE FIELD (iOS pastedPreview, MontanaConversation 3087-3159): the chosen pictures themselves, 116 by 78,
    // the corner rounded, the cross in the top corner of each, not a word beside them; more than three travel sideways
    val staged = ArrayList<Pair<Media.Picked, android.graphics.Bitmap?>>()
    val stagedRow = c.hstack { setPadding(dp(16), dp(2), dp(16), dp(2)) }
    val stagedStrip = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false; visibility = View.GONE; setPadding(0, c.dp(6), 0, c.dp(6)); addView(stagedRow)
    }
    lateinit var drawStaged: () -> Unit
    // THE PICTURES LEAVE WITH THE FIELD'S WORDS AS ONE GROUP (iOS sendPasted 3160-3206): the words ride with the first of them
    fun sendStaged() {
        val words = field.text.toString().trim()
        field.setText(""); Prefs.setStr("draft." + ref, "")
        val picks = staged.map { it.first }
        staged.clear(); drawStaged()
        Thread { Media.sendAll(c, ref, picks, words); MainThread.post { toBottom() } }.start()
    }
    val bar = ChatInputBar(act, field) {
        if (staged.isNotEmpty() && editing == null) { sendStaged(); return@ChatInputBar }
        val words = field.text.toString().trim()
        field.setText("")
        val e = editing
        if (e != null) {
            // THE NEW WORDS OF A LETTER ALREADY SENT (iOS editMessage): the row changes here, the peer is told by ED:{sid,tx}.
            editing = null
            // and no edit becomes one (iOS editMessage 3353): the row keeps its words
            if (words != e.text && CoinLetter.parse(words) == null) {
                Book.edit(ref) { ch -> ch.msgs.find { it.mid == e.mid }?.let { it.text = words; it.edited = true } }
                val edit = Marks.EDIT + JSONObject().put("sid", e.mid).put("tx", words)
                // a group's edit rides the group's carrier to every phone of it (iOS editMessage → MTGroup.signal)
                if (group) Groups.signal(ref, edit) else Post.send(ref, Marks.mintMid(), edit)
            }
        } else if (group) {
            // A GROUP'S LETTER HAS ITS OWN CARRIER (iOS ChatStore.send → MTGroup.send): the row here, a copy to everyone it carries to
            val r = replyTo
            replyTo = null
            if (!Groups.send(ref, words, r?.let { letterWords(c, it).take(200) }, r?.mid)) field.setText(words)
        } else {
            val r = replyTo
            replyTo = null
            // A LONG LETTER LEAVES IN PIECES (iOS breakOutgoingText 5917-5945, send 5973-5989): up to 4500 characters a bubble, backing off
            // to the last line break or period, each piece a letter of its own; the quote rides with the first piece only
            Groups.pieces(words).forEachIndexed { i, piece ->
                val mid = Marks.mintMid()
                val qt = if (i == 0) r?.let { letterWords(c, it).take(200) } else null
                val qm = if (i == 0) r?.mid else null
                Book.edit(ref) { it.msgs.add(Msg(mid, piece, true, Marks.birthMs(mid) ?: System.currentTimeMillis(), qt = qt, qm = qm)) }
                Post.send(ref, mid, piece, qt, qm)
                LinkPreview.attend(ref, mid, piece, qt, qm)   // a link's card follows the letter (iOS MTLinkCards.attend)
            }
        }
        showStrip()
        toBottom()
    }
    // THE GALLERY AND THE FILE KEYS SEND FOR REAL (iOS PhotosPicker / fileImporter → MontanaMedia): read, sealed, laid, lettered.
    fun sendPicked(asDoc: Boolean): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) Thread {
            val p = Media.read(c, uri, asDoc)
            if (p == null) MainThread.post { android.widget.Toast.makeText(c, R.string.media_failed, android.widget.Toast.LENGTH_LONG).show() }
            else if (asDoc) { Media.send(c, ref, p, ""); MainThread.post { toBottom() } }   // a file leaves as it is (iOS fileImporter)
            // A PICTURE OR A VIDEO GETS ITS CAPTION FIRST (iOS AttachSheet): the words already typed stand in it (the author's word 19.09)
            else MainThread.post {
                hideKeys()
                val draft = if (editing == null) field.text.toString().trim() else ""
                lateinit var close: () -> Unit
                close = act.overlay(captionPage(act, p, draft, onCancel = { close() }) { caption ->
                    close()
                    // THE DRAFT BECOMES THE CAPTION (iOS consumeDraftAsCaption): what was typed leaves the field with the picture
                    if (draft.isNotEmpty() && caption.startsWith(draft)) { field.setText(""); Prefs.setStr("draft.$ref", "") }
                    Thread { Media.send(c, ref, p, caption); MainThread.post { toBottom() } }.start()
                })
            }
        }.start()
    }
    // THE CAMERA BESIDE THE PHOTOS (iOS MTGalleryPick: the camera's tile, «All photos»): a shot goes the picked picture's road
    drawStaged = {
        stagedRow.removeAllViews()
        for (s in staged.toList()) stagedRow.addView(FrameLayout(c).apply {
            addView(ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (s.second != null) setImageBitmap(s.second) else setBackgroundColor(Color.rgb(51, 51, 51))
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.dp(12).toFloat())
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            if (s.first.kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            // the cross drawn small and taken on the platform's least target: 22 drawn, 44 to the finger, grown inward from the corner
            addView(FrameLayout(c).apply {
                contentDescription = c.getString(R.string.cancel)
                addView(FrameLayout(c).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(191, 0, 0, 0)) }
                    addView(c.icon(R.drawable.ic_close, Color.WHITE), FrameLayout.LayoutParams(c.dp(11), c.dp(11), Gravity.CENTER))
                }, FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, c.dp(4), c.dp(4), 0) })
                pressable { staged.remove(s); drawStaged() }
            }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.TOP or Gravity.END))
        }, lp(c.dp(116), c.dp(78)).apply { marginEnd = c.dp(8) })
        stagedStrip.visibility = if (staged.isEmpty()) View.GONE else View.VISIBLE
        bar.attached = staged.isNotEmpty()
    }
    /** The chosen pictures read whole off the frame and staged above the field, in the order the finger chose them (iOS stagePicks). */
    fun stage(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        Thread {
            val made = uris.mapNotNull { u -> Media.read(c, u, false)?.takeIf { it.kind == "img" || it.kind == "vid" }?.let { p -> p to stagedFace(c, p) } }
            MainThread.post {
                if (made.size < uris.size) android.widget.Toast.makeText(c, R.string.media_failed, android.widget.Toast.LENGTH_LONG).show()
                staged.addAll(made); drawStaged(); toBottom()
            }
        }.start()
    }
    // A PICTURE PASTED, OR HANDED OVER BY THE KEYBOARD, IS AN ATTACHMENT, NOT WORDS (iOS paste, MontanaBubble 2166-2188; attachPasted,
    // MontanaConversation 3052-3087; the author's word 10.09; fork atom 1443): an opaque one waits above the field as a photo in the
    // one shape of a photo on its way out; a see-through one is a sticker, kept as PNG, on the card-sticker road; a GIF keeps its
    // frames. Android took no picture into the field at all. The keyboard's grant lasts only while it hands the picture over, so
    // the bytes are read at once and shaped off the screen.
    fun seeThrough(b: android.graphics.Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val small = android.graphics.Bitmap.createScaledBitmap(b, 64, 64, true)
        for (y in 0 until 64) for (x in 0 until 64) if (android.graphics.Color.alpha(small.getPixel(x, y)) < 128) return true
        return false
    }
    fun pasted(bytes: ByteArray) {
        val gif = 4 <= bytes.size && String(bytes, 0, 4, Charsets.US_ASCII) == "GIF8"
        val bmp = if (gif) null else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null && seeThrough(bmp)) {
            val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            Media.send(c, ref, Media.Picked(png, "img", "png", Stickers.CARD), "")
            MainThread.post { toBottom() }
            return
        }
        val p = if (gif) Media.Picked(bytes, "img", "gif", null) else Media.Picked(Media.photoForSend(bytes) ?: return, "img", "jpg", null)
        val face = stagedFace(c, p)
        MainThread.post { staged.add(p to face); drawStaged(); toBottom() }
    }
    if (android.os.Build.VERSION.SDK_INT >= 31) field.setOnReceiveContentListener(arrayOf("image/*")) { _, payload ->
        val clip = payload.clip
        val held = ArrayList<ByteArray>()
        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { u ->
            runCatching { c.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()?.let { held.add(it) }
        }
        // pictures in hand: the pictures alone ride, as the iPhone's paste takes images over words; none: the words paste as ever
        if (held.isEmpty()) payload else { Thread { held.forEach { pasted(it) } }.start(); null }
    }
    // THE GALLERY OF THE ROW (iOS openGallery → MTGalleryPick): the phone's latest pictures at once, the camera the first tile,
    // «All photos» the system's own picker with its ten; what is chosen waits above the field
    bar.onGallery = { hideKeys(); galleryPick(act, onPick = { stage(it) },
        // THE CAMERA TILE (iOS onCamera → CameraPicker → sendCameraImage / sendCameraVideo): a photo or a film, sent once taken
        onCamera = { cameraPage(act) { f, film ->
            // the camera's photo leaves in the one shape of a photo on its way out, without the camera's record (iOS photoForSend)
            Thread { val b = f.readBytes(); Media.send(c, ref, Media.Picked(if (film) b else Media.photoForSend(b) ?: b, if (film) "vid" else "img", if (film) "mp4" else "jpg", null), ""); f.delete(); MainThread.post { toBottom() } }.start()
        } },
        onAll = { act.pickVisualMany(MediaGroup.CEILING) { stage(it) } }) }
    // «SEND LATER» (iOS: the send key's hold): the words leave the field and wait for their moment (Schedule.kt)
    bar.onSendLater = { v -> sendLaterMenu(act, v, ref, { field.text.toString() }) { field.setText(""); Prefs.setStr("draft.$ref", "") } }
    // A CONTACT OF THE PHONE (iOS ContactPicker): its name and number leave as one letter
    bar.onContact = { hideKeys(); act.pickContact { u -> u?.let { contactLetter(c, it) }?.let { words -> sendWords(ref, words); toBottom() } } }
    bar.onFile = { hideKeys(); act.openDocument(sendPicked(true)) }
    // THE CARD AS A PHOTO (iOS sendCardPhoto): the kept card drawn at the standard size, its words the caption under the card's mark
    bar.onCard = { hideKeys(); act.push { close -> businessCardPage(act, close) { card ->
        close()
        Thread { Media.send(c, ref, Media.Picked(cardPhoto(c, card), "img", "jpg", null), card.wire); MainThread.post { toBottom() } }.start()
    } } }
    // THE PLACE (iOS onLocation → MTLocationPicker → mtSendPlace): this spot leaves as the place's words, by the one road of words
    bar.onLocation = { hideKeys(); act.push { close -> placePage(act, close) { words -> sendWords(ref, words); toBottom() } } }
    bar.onStickers = { hideKeys(); Stickers.panel(act, ref) }   // a hold on the emoji key (Stickers.kt)
    // THE VOICE (iOS the held mark): the tape starts under the finger and leaves as a media letter of kind «aud».
    // THE SWELL WHILE SPEAKING (iOS the recording row): the red mark, the running time and the wave that rises with the voice.
    val liveWave = WaveView(c).apply { played = Color.WHITE }
    val liveTime = c.text("0:00", 15f, Color.WHITE)
    val dot = View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SysColor.red) } }
    // THE CONTROLS OF A LOCKED TAPE (iOS MTHoldOverlayView barRow): each the bar's round glass on a 44-point target
    fun ctl(icon: Int, label: Int, work: () -> Unit): FrameLayout = FrameLayout(c).apply {
        background = c.glassPlate(oval = true); contentDescription = c.getString(label)
        addView(c.icon(icon, Color.WHITE), FrameLayout.LayoutParams(c.dp(20), c.dp(20), Gravity.CENTER))
        visibility = View.GONE
        pressable(work)
    }
    fun paint(key: FrameLayout, pausedNow: Boolean) {
        (key.getChildAt(0) as? ImageView)?.let { it.setImageResource(if (pausedNow) R.drawable.ic_record_circle else R.drawable.ic_pause_fill)
            it.imageTintList = android.content.res.ColorStateList.valueOf(if (pausedNow) SysColor.red else Color.WHITE) }
    }
    val voiceBin = ctl(R.drawable.ic_close, R.string.cancel) { bar.cancelRecording() }
    lateinit var voicePause: FrameLayout
    voicePause = ctl(R.drawable.ic_pause_fill, R.string.rec_pause) { VoiceTape.togglePause(); paint(voicePause, VoiceTape.paused) }
    val recStrip = c.hstack {
        visibility = View.GONE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
        addView(voiceBin, lp(dp(40), dp(40)).apply { marginEnd = dp(10) })
        addView(dot, lp(dp(10), dp(10)).apply { marginEnd = dp(8) })
        addView(liveTime, lp(WRAP, WRAP).apply { marginEnd = dp(12) })
        addView(liveWave, lp(0, WRAP, 1f))
        addView(voicePause, lp(dp(40), dp(40)).apply { marginStart = dp(10) })
    }
    // THE LOCK OVER THE KEY (iOS lockPlate): the padlock and the chevron above the held key; the chevron folds as the finger rises
    val padlock = c.icon(R.drawable.ic_lock_open, Color.WHITE)
    val chevron = c.icon(R.drawable.ic_chevron_up, Color.WHITE)
    val lockPlate = c.vstack(Gravity.CENTER_HORIZONTAL) {
        background = c.glassPlate().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(170, 40, 40, 44)) }
        setPadding(0, dp(14), 0, dp(14))
        addView(padlock, lp(dp(24), dp(24)))
        addView(chevron, lp(dp(18), dp(18)).apply { topMargin = dp(8) })
        visibility = View.GONE
    }
    var noteControls: View? = null
    var plateHome: FrameLayout? = null
    fun plateLp() = FrameLayout.LayoutParams(c.dp(52), WRAP, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = c.dp(150); marginEnd = c.dp(8) }
    /** The plate stands where the tape is watched: over the chat for a voice, over the note's window for a note. */
    fun movePlate(to: FrameLayout?) { val t = to ?: return; if (lockPlate.parent !== t) { (lockPlate.parent as? android.view.ViewGroup)?.removeView(lockPlate); t.addView(lockPlate, plateLp()) } }
    fun endStrip() { recStrip.visibility = View.GONE; VoiceTape.onLevel = null; dot.animate().cancel() }
    bar.onRecLift = { l ->
        lockPlate.visibility = View.VISIBLE; lockPlate.translationY = -c.dp(9) * l
        padlock.setImageResource(if (l >= 1f) R.drawable.ic_lock else R.drawable.ic_lock_open)
        chevron.alpha = 1f - l
    }
    bar.onRecLocked = {
        lockPlate.visibility = View.GONE
        voiceBin.visibility = View.VISIBLE; voicePause.visibility = View.VISIBLE; paint(voicePause, false)
        noteControls?.visibility = View.VISIBLE
    }
    bar.onRecIdle = {
        lockPlate.visibility = View.GONE
        voiceBin.visibility = View.GONE; voicePause.visibility = View.GONE
        noteControls = null
        movePlate(plateHome)
    }
    bar.onVoiceStart = {
        val on = VoiceTape.start(act)
        if (on) {
            liveWave.bars = FloatArray(0); liveWave.progress = 0f
            recStrip.visibility = View.VISIBLE
            dot.animate().alpha(0.2f).setDuration(600).withEndAction { dot.alpha = 1f }.start()
            VoiceTape.onLevel = { a ->
                liveWave.push(a)
                val s = VoiceTape.seconds.toInt(); liveTime.text = "%d:%02d".format(s / 60, s % 60)
                dot.alpha = if ((VoiceTape.levels.size / 20) % 2 == 0) 1f else 0.3f
            }
        }
        on
    }
    // THE ROUND NOTE UNDER THE FINGER (iOS MontanaVideoNoteHold): the window over the feed, the ring for the time; a tap turns
    // the camera; let go to send, slide left to drop. What the window shows is the file itself.
    var note: NoteRecorder? = null
    var noteClose: (() -> Unit)? = null
    val noteTick = object : Runnable {
        override fun run() { val n = note ?: return; noteRing?.progress = (n.elapsed / NoteRecorder.MAX_SECONDS).toFloat(); val s = n.elapsed.toInt(); noteTime?.text = "%d:%02d".format(s / 60, s % 60); MainThread.later(100, this) }
    }
    bar.onNoteStart = start@{
        if (c.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            !VoiceTape.granted(c)) {
            act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO), 8); return@start false
        }
        val tv = TextureView(c)
        val ring = NoteRing(c); val time = c.text("0:00", 15f, Color.WHITE)
        noteRing = ring; noteTime = time
        val side = c.dp(280)
        lateinit var notePause: FrameLayout
        notePause = ctl(R.drawable.ic_pause_fill, R.string.rec_pause) { note?.togglePause(); paint(notePause, note?.paused == true) }
        val controls = c.hstack {
            gravity = Gravity.CENTER
            visibility = View.GONE   // stands once the tape is locked (iOS: the bin, the flip, the pause, and the arrow that sends)
            listOf(ctl(R.drawable.ic_close, R.string.cancel) { bar.cancelRecording() },
                   ctl(R.drawable.ic_camera_rotate, R.string.rec_flip) { note?.flip() },
                   // BOTH CAMERAS (iOS the dual seat): only where the hardware streams a back and a front camera together
                   *(if (NoteRecorder.dualPair(c) != null) arrayOf(ctl(R.drawable.ic_dual_camera, R.string.rec_dual) { note?.toggleDual() }) else emptyArray()),
                   notePause,
                   ctl(R.drawable.ic_arrow_circle_up, R.string.send) { bar.stopRecording() }).forEachIndexed { i, k ->
                k.visibility = View.VISIBLE
                addView(k, lp(dp(48), dp(48)).apply { if (i > 0) marginStart = dp(22) })
            }
        }
        noteControls = controls
        val page = FrameLayout(c).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            addView(controls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = c.dp(60) })
            addView(ring, FrameLayout.LayoutParams(side + c.dp(16), side + c.dp(16), Gravity.CENTER))
            addView(tv.round().apply {
                setOnClickListener { note?.flip() }
                // THE BADGE UNDER THE FINGER (iOS MontanaVideoNoteHold 1658-1672): a finger on the badge drags it, and the badge
                // eases to the nearest corner when let go; a tap anywhere else turns the camera, as before
                setOnTouchListener(object : View.OnTouchListener {
                    var dragging = false
                    var sx = 0f
                    var sy = 0f
                    override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                        val n = note ?: return false
                        val k = v.width / NoteRecorder.SQUARE.toFloat()
                        if (k <= 0f) return false
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                dragging = n.onBadge(e.x / k - NoteRecorder.SQUARE / 2f, e.y / k - NoteRecorder.SQUARE / 2f)
                                if (dragging) { sx = e.x; sy = e.y; n.grabBadge() }
                            }
                            android.view.MotionEvent.ACTION_MOVE -> if (dragging) n.dragBadge((e.x - sx) / k, (e.y - sy) / k)
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) { n.dropBadge(); dragging = false; return true }
                        }
                        return dragging
                    }
                })
            }, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            addView(time.apply { setShadowLayer(6f, 0f, 0f, Color.BLACK) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = side / 2 + c.dp(30) })
        }
        noteClose = act.overlay(page)
        movePlate(page)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                val r = NoteRecorder(c, tv)
                r.onMax = { MainThread.post { bar.stopRecording() } }
                if (r.start()) { note = r; MainThread.later(100, noteTick) } else { noteClose?.invoke(); noteClose = null }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        true
    }
    bar.onNoteEnd = { dropped ->
        val r = note; note = null
        Thread {
            val got = r?.stop(keep = !dropped)
            MainThread.post { noteClose?.invoke(); noteClose = null }
            got?.let { (f, secs) -> Media.send(c, ref, Media.Picked(f.readBytes(), "vid", "mp4", null, secs, round = true), ""); f.delete(); MainThread.post { toBottom() } }
        }.start()
    }
    bar.onVoiceEnd = { dropped ->
        val wave = VoiceTape.waveform()
        endStrip()
        VoiceTape.stop(keep = !dropped)?.let { (f, secs) ->
            Thread { Media.send(c, ref, Media.Picked(f.readBytes(), "aud", "m4a", null, secs, wave), ""); f.delete(); MainThread.post { toBottom() } }.start()
        }
    }

    // ── the bar (iOS chatTop in MTChatTopBar, 8 from each edge): the back on the marks' round, the name's bubble on the rest of the
    // room, a pair's handset at its right ──
    val chat0 = Book.chat(ref)
    val title = c.text(chat0?.shown?.ifBlank { null } ?: c.getString(R.string.peer), 15f, Color.WHITE, bold = true).apply { singleLineEllipsis() }   // USER-DATA
    val faceSlot = FrameLayout(c)
    // THE PRESENCE UNDER THE NAME (iOS presenceWord in chatTitleBubble): the words in grey after a dot — green while the person is live
    // («typing…» / «in chat» / «online»), red on «last seen…»; a group's line is its people's count, with no dot
    val presenceLine = c.text("", 11f, MT.gray).apply { singleLineEllipsis() }
    val presenceDot = View(c)
    val presenceRow = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(presenceDot, lp(dp(9), dp(9)).apply { marginEnd = dp(4) })
        addView(presenceLine, lp(WRAP, WRAP))
    }
    fun drawPresence() {
        if (group) {   // a group has no presence of one person: its people's count (iOS the group chat's head)
            presenceLine.text = Groups.peopleLine(c, ref) ?: ""
            presenceDot.visibility = View.GONE
            presenceRow.visibility = if (presenceLine.text.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        val w = Presence.word(c, ref)
        presenceRow.visibility = if (w == null) View.GONE else View.VISIBLE
        presenceLine.text = w?.first ?: ""
        presenceDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (w?.second == true) SysColor.green else SysColor.red) }
    }
    drawPresence()
    fun drawHead() {
        val ch = Book.chat(ref) ?: return
        title.text = ch.shown.ifBlank { c.getString(R.string.peer) }
        faceSlot.removeAllViews(); faceSlot.addView(c.peerFace(ref, ch.shown, 36), FrameLayout.LayoutParams(c.dp(36), c.dp(36)))
    }
    drawHead()
    // THE NAME'S BUBBLE IS THE ONE DOOR (iOS chatTitleDoor): the face, the name and the presence in one capsule of glass, the whole of it
    // the button into the correspondent's page (a group's into the group's page), the keyboard going down with it; a hold: «Copy name»
    // the page's «Show in chat» and its forward land here (iOS ui.showLetter): this chat jumps to the letter, another opens in its place
    var jumpHere: (String) -> Unit = {}
    val openPage = { hideKeys(); act.push { close -> if (group) groupPage(act, ref, close) else peerInfoPage(act, ref, close) { to, mid ->
        if (to != ref) { onClose(); act.push { c2 -> conversationPage(act, to, c2) } } else if (mid != null) jumpHere(mid)
    } } }
    // one round of the bar (iOS MTBarRoundMark): the glass circle of 44 with the bar's grey glyph (MontanaOctagon.barGlyph)
    fun roundMark(res: Int, label: Int, onTap: (View) -> Unit): View = FrameLayout(c).apply {
        background = c.glassPlate(oval = true)
        contentDescription = c.getString(label)
        addView(c.icon(res, Color.rgb(204, 204, 204)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        pressable { onTap(this) }
    }
    // the menu's own ink for its glyphs, so they read on whichever plate the platform gives the menu
    fun menuInk() = c.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).let { t -> t.getColorStateList(0).also { t.recycle() } }
    val top = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(callHandset(act, true, null), lp(dp(44), dp(44)))   // 15.8: back to the folded call (iOS chatTop 1847)
        addView(roundMark(R.drawable.ic_arrow_back_ios_new, R.string.back) { hideKeys(); onClose() }, lp(dp(44), dp(44)))
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            background = c.glassPlate()
            setPadding(dp(4), 0, dp(14), 0)
            addView(faceSlot, lp(dp(36), dp(36)).apply { marginEnd = dp(8) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(title, lp(WRAP, WRAP))
                addView(presenceRow, lp(WRAP, WRAP).apply { topMargin = dp(1) })
            }, lp(0, WRAP, 1f))
            pressable { openPage() }
            setOnLongClickListener { v ->
                PopupMenu(c, v).apply {
                    menu.add(R.string.copy_name)
                    setOnMenuItemClickListener {
                        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("name", title.text)); true
                    }
                }.show(); true
            }
        }, lp(0, dp(44), 1f).apply { marginStart = dp(6); if (!group) marginEnd = dp(6) })
        // THE HANDSET AS IT WAS (iOS handsetMark, a pair's chat): the calls' menu behind the one bar glyph — a voice call or a video call
        if (!group) addView(roundMark(R.drawable.ic_bar_calls, R.string.pi_voice_call) { v ->
            hideKeys()
            PopupMenu(c, v).apply {
                if (29 <= android.os.Build.VERSION.SDK_INT) setForceShowIcon(true)
                val ink = menuInk()
                menu.add(0, 0, 0, R.string.pi_voice_call).setIcon(R.drawable.ic_peer_phone).iconTintList = ink
                menu.add(0, 1, 1, R.string.pi_video_call).setIcon(R.drawable.ic_peer_video).iconTintList = ink
                setOnMenuItemClickListener { item -> act.callOut(ref, video = item.itemId == 1); true }
            }.show()
        }, lp(dp(44), dp(44)))
        addView(callHandset(act, false, null), lp(dp(44), dp(44)))   // 15.8: end it (iOS chatTop 1852)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52))
    }

    val groupFoot = FrameLayout(c).apply { visibility = View.GONE }.also { if (group) fillGroupFoot(c, ref, it) }
    var lane = false   // the live lane stands while the page does
    fun drawDraft() {
        val d = LiveDraft.of(ref)
        val was = draftRow.visibility == View.VISIBLE
        draftRow.removeAllViews()
        if (d == null) { draftRow.visibility = View.GONE; return }
        draftRow.addView(c.liveDraftBubble(d, Book.chat(ref)), FrameLayout.LayoutParams(MATCH, WRAP))
        draftRow.visibility = View.VISIBLE
        if (!was) toBottom()
    }
    val draftListener: () -> Unit = { drawDraft() }
    val presenceListener: () -> Unit = { drawPresence() }
    lateinit var chatBeat: Runnable
    chatBeat = Runnable { if (lane) { Presence.sayChat(ref, true); MainThread.later(Presence.BEAT, chatBeat) } }   // the 20-second beat (iOS P-109)
    var folded = false
    val listener: () -> Unit = listener@{
        // THIS CHAT WAS FOLDED INTO THE OLDER ONE WITH THE SAME PERSON (iOS foldConversation → openChatRequest): the older opens instead.
        val into = SamePair.root(ref)
        if (into != ref && Book.chat(ref) == null) {
            if (!folded) { folded = true; hideKeys(); onClose(); act.push { close -> conversationPage(act, into, close) } }
            return@listener
        }
        val before = column.childCount
        redraw(); drawHead()
        if (group) {
            drawPresence(); fillGroupFoot(c, ref, groupFoot)
            // taken out of the group, or let back in: the field follows (iOS composeSlot)
            if (!selecting) bar.visibility = if (Groups.canWrite(ref)) View.VISIBLE else View.GONE
        }
        if (column.childCount != before) toBottom()
        if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start()
    }

    // THE EYE CARRIED TO A LETTER (the pinned plate's tap, the search's jump): scrolled a third down and lit for a moment
    fun jumpTo(mid: String) {
        val found = column.findViewWithTag<View>(mid) ?: return
        scroll.post {
            scroll.smoothScrollTo(0, (found.top + column.top - scroll.height / 3).coerceAtLeast(0))
            found.setBackgroundColor(Color.argb(60, 255, 255, 255))
            found.postDelayed({ found.background = null }, 1200)
        }
    }
    jumpHere = { jumpTo(it) }
    // a letter folded into a media group is reached by its plate's row (iOS rowLead)
    toQuoted = { q -> jumpTo(Book.chat(ref)?.let { ch -> feedRows(ch.msgs).firstOrNull { r -> r.any { it.mid == q } }?.get(0)?.mid } ?: q) }
    // THE FOOT WHILE THE CHAT SELECTS (iOS the selection bar): Forward and Delete for the chosen letters, in place of the field
    val foot = FrameLayout(c).apply { visibility = View.GONE }
    drawFoot = {
        foot.removeAllViews()
        foot.visibility = if (selecting) View.VISIBLE else View.GONE
        bar.visibility = if (selecting || (group && !Groups.canWrite(ref)) || Post.closed(ref)) View.GONE else View.VISIBLE
        if (selecting) {
            strip.visibility = View.GONE
            fun done() { selecting = false; picked.clear(); redraw(); drawFoot() }
            fun chosen() = Book.chat(ref)?.msgs?.filter { it.mid in picked } ?: emptyList()
            foot.addView(letterSelectionFoot(act, picked.size, onClose = { done() },
                onForward = {
                    val letters = chosen().filter { canForward(it) }
                    done()
                    if (letters.isNotEmpty()) act.push { close -> forwardPage(act, ref, letters, close) { to -> close(); if (to != ref) { onClose(); act.push { c2 -> conversationPage(act, to, c2) } } } }
                },
                onDelete = {
                    // a coin letter of mine on its way stays, and the person is told why once the rest are gone (iOS deleteSelected 4247-4250)
                    val all = chosen()
                    val letters = all.filter { !CoinSend.travels(it) }
                    val held = letters.size != all.size
                    fun told() { if (held) AlertDialog.Builder(c).setTitle(R.string.coins_travel).setMessage(R.string.coins_travel_note).setPositiveButton(R.string.ok, null).show() }
                    if (letters.isEmpty()) { done(); told(); return@letterSelectionFoot }
                    AlertDialog.Builder(c).setTitle(R.string.delete_message_q)
                        .setItems(arrayOf(c.getString(R.string.delete_for_everyone), c.getString(R.string.delete_for_me))) { _, which ->
                            val mids = letters.map { it.mid }.toSet()
                            Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid in mids } }
                            // in a group a speaker takes away their own letters for everyone (iOS MTGroup.answered checks the seat)
                            if (which == 0) letters.forEach { m ->
                                if (!group) Post.send(ref, Marks.mintMid(), Marks.DELETE + "mid:" + m.mid)
                                else if (m.mine) Groups.signal(ref, Marks.DELETE + "mid:" + m.mid)
                            }
                            done(); told()
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }), FrameLayout.LayoutParams(MATCH, WRAP))
        } else showStrip()
    }

    hideBar = { bar.visibility = View.GONE }
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ref), FrameLayout.LayoutParams(MATCH, MATCH))   // the chat's own ground, or the pages' crest (iOS MTWallpaper)
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(top)
            // THE PINNED PLATE under the header (iOS the pinned bar): shown while a letter of this chat is pinned
            addView(pinnedPlate(act, ref) { mid -> jumpTo(mid) }, lp().apply { marginStart = dp(10); marginEnd = dp(10); bottomMargin = dp(4) })
            addView(scroll, lp(MATCH, 0, 1f))
            addView(strip, lp())
            addView(stagedStrip, lp())
            addView(recStrip, lp())
            addView(bar.apply { if ((group && !Groups.canWrite(ref)) || Post.closed(ref)) visibility = View.GONE }, lp())
            addView(closedNote, lp())
            addView(foot, lp())
            addView(groupFoot, lp())
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        plateHome = this; addView(lockPlate, plateLp())
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.requestApplyInsets()
                Book.openChat = ref; Book.listen(listener); Notify.clear(ref)
                Presence.listen(presenceListener); lane = true
                LiveDraft.listen(draftListener); drawDraft()
                Presence.sayChat(ref, true); MainThread.later(Presence.BEAT, chatBeat)
                Signal.hold(ref, "chat")
                if (!group) LiveDraft.sayLink(ref)   // our daily link, to hand on (iOS sendLinkWord on a chat's opening)
                if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start()
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (Book.openChat == ref) Book.openChat = null; Book.unlisten(listener)
                Presence.unlisten(presenceListener); LiveDraft.unlisten(draftListener)
                // words left in the field stand on their screen as a checkpoint, frozen, not typing (iOS «ck»)
                field.text.toString().takeIf { it.isNotBlank() }?.let { LiveDraft.say(ref, it, field.selectionEnd, checkpoint = true) }
                Signal.release(ref, "chat")
                if (lane) { lane = false; Presence.sayChat(ref, false) }   // leaving says so at once (iOS: the departure word)
            }
        })
        scroll.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t < ob - ot) toBottom() }
        redraw()
        val found = jump?.let { column.findViewWithTag<View>(it) }
        if (found == null) toBottom() else scroll.post {
            scroll.scrollTo(0, (found.top + column.top - scroll.height / 3).coerceAtLeast(0))
            found.setBackgroundColor(Color.argb(60, 255, 255, 255))
            found.postDelayed({ found.background = null }, 1200)
        }
    }
}

/** One letter: the quote it answers, its words, «edited» and the time; its reactions under it (iOS MessageBubble). */
/**
 * A LETTER PULLED TO THE LEFT IS ANSWERED (iOS MTReplySwipe + SwipeReplyRow): the two first points decide — a stroke to the
 * right or an upright one is the scroll's, a flat one to the left is ours; the letter rides by the finger, the round glass with
 * the reply arrow comes in from beyond the edge, growing and clearing; past the threshold (60 for one's own, 45 for theirs)
 * the pull stiffens and the phone knocks once; let go armed — the reply strip opens. The row slides back either way.
 */
class ReplySwipe(c: Context) : FrameLayout(c) {
    var onReply: (() -> Unit)? = null
    var mine = false
    private var badge: View? = null
    private var x0 = 0f; private var y0 = 0f
    private var validated = false; private var failed = false; private var armed = false
    private val slop = dp(2).toFloat()

    private fun threshold() = dp(if (mine) 60 else 45).toFloat()
    /** The pull past the threshold stiffens (iOS band: range 100, k 0.4). */
    private fun band(off: Float, start: Float): Float {
        if (off < start) return off
        val range = dp(100).toFloat()
        return start + (1 - 1 / ((off - start) * 0.4f / range + 1)) * range
    }
    private fun badge(): View = badge ?: FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(90, 120, 120, 128)); setStroke(dp(1), Color.argb(38, 255, 255, 255)) }
        addView(context.icon(R.drawable.ic_reply, Color.WHITE), LayoutParams(dp(17), dp(17), Gravity.CENTER))
        alpha = 0f
        this@ReplySwipe.addView(this, LayoutParams(dp(34), dp(34), Gravity.END or Gravity.CENTER_VERTICAL))
    }.also { badge = it }

    private fun ride(pull: Float) {
        val tx = band(pull, threshold()); val p = (pull / threshold()).coerceAtMost(1f)
        getChildAt(0)?.translationX = -tx
        badge().apply { translationX = dp(43) - tx; alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = scaleX }
    }
    private fun settle() {
        val content = getChildAt(0) ?: return
        val from = -content.translationX
        if (from == 0f) return
        android.animation.ValueAnimator.ofFloat(from, 0f).apply {
            duration = 240; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { v -> val tx = v.animatedValue as Float; content.translationX = -tx
                badge?.let { b -> b.translationX = dp(43) - tx; val p = (tx / threshold()).coerceIn(0f, 1f); b.alpha = p; b.scaleX = 0.6f + 0.4f * p; b.scaleY = b.scaleX } }
            start()
        }
    }

    /** The first points of a touch: whether it is a pull to the left. */
    private fun judge(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; validated = false; failed = false; armed = false }
            android.view.MotionEvent.ACTION_MOVE -> if (!validated && !failed) {
                // two-point validation — quick enough to win the race against the scroll's own pan
                val dx = e.rawX - x0; val dy = e.rawY - y0
                if (dx > slop) failed = true
                else if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 2) failed = true
                else if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dy) * 2 < kotlin.math.abs(dx)) {
                    validated = true; parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
    }

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onReply == null) return false
        judge(e)
        return validated
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onReply == null) return false
        // A PULL BESIDE THE BUBBLE ANSWERS TOO (iOS: the letter's line, not the bubble's face): the row keeps the touch
        // that no bubble took, and judges it itself.
        if (!validated) { judge(e); return !failed }
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                val pull = (x0 - e.rawX).coerceAtLeast(0f)
                ride(pull)
                if (pull >= threshold() && !armed) {
                    armed = true
                    performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                          else android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
            android.view.MotionEvent.ACTION_UP -> { if (armed) onReply?.invoke(); settle(); validated = false }
            android.view.MotionEvent.ACTION_CANCEL -> { settle(); validated = false }
        }
        return true
    }
}

/**
 * THE GROUP'S FOOT IN PLACE OF THE FIELD (iOS composeSlot): out of the group — the note that its letters stay to read
 * (groupOutNote); a channel's subscriber — the platform's bell, the channel's sound off or on, the same switch as the list's
 * swipe (channelMuteBar).
 */
private fun fillGroupFoot(c: Context, ref: String, into: FrameLayout) {
    into.removeAllViews()
    if (Groups.isOut(ref)) into.addView(c.text(c.getString(R.string.gr_out_note), 13f, MT.gray, center = true).apply {
        setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(12))
    }, FrameLayout.LayoutParams(MATCH, WRAP))
    else if (!Groups.canWrite(ref)) {
        val muted = ChatMarks.isMuted(ref)
        into.addView(c.icon(if (muted) R.drawable.ic_bell_off else R.drawable.ic_set_bell, Color.WHITE).apply {
            setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            contentDescription = c.getString(if (muted) R.string.cl_unmute else R.string.cl_mute)
            pressable { ChatMarks.toggleMute(ref); fillGroupFoot(c, ref, into) }
        }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.CENTER).apply { topMargin = c.dp(6); bottomMargin = c.dp(6) })
    }
    into.visibility = if (into.childCount > 0) View.VISIBLE else View.GONE
}

private fun letterBubble(c: Context, m: Msg, chat: Chat, members: List<Msg> = listOf(m)): ReplySwipe = ReplySwipe(c).apply {
    mine = m.mine
    val mine = m.mine
    addView(c.vstack(if (mine) Gravity.END else Gravity.START) {
        // THE SPEAKER OVER A GROUP'S LETTER (iOS speakerLine): every letter of another says who wrote it; a channel speaks as itself
        Groups.speakerLine(c, m, chat.ref)?.let { who ->
            addView(c.text(who, 12f, MT.gray, bold = true).apply { singleLineEllipsis(); setPadding(c.dp(8), c.dp(6), 0, c.dp(2)) }, lp(WRAP, WRAP))   // USER-DATA: the speaker's name
        }
        addView(c.vstack(Gravity.END) {
            background = if (Stickers.bare(m)) null else BubbleStyle.drawable(context, mine)   // a sticker stands without a bubble
            setPadding(dp(12), dp(7), dp(12), dp(6))
            // A FORWARDED LETTER (iOS isForwarded): one grey line over its words, no name and no bar
            if (m.qt == FORWARDED_QUOTE) addView(c.text(c.getString(R.string.ld_forwarded), 13f, BubbleStyle.time(mine)), lp(WRAP, WRAP).apply { bottomMargin = dp(3) })
            else if (m.qt != null) {
                val whose = m.qm?.let { q -> chat.msgs.find { it.mid == q } }
                addView(c.hstack {
                    addView(View(c).apply { setBackgroundColor(BubbleStyle.text(mine)) }, lp(dp(2), MATCH).apply { marginEnd = dp(6) })
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        addView(c.text(if (whose?.mine == true) Prefs.userName.ifBlank { "·" } else if (Groups.isKey(chat.ref)) Groups.speakerName(c, whose?.from) else chat.shown.ifBlank { c.getString(R.string.peer) }, 13f, BubbleStyle.text(mine), bold = true))
                        addView(c.text(m.qt, 13f, BubbleStyle.time(mine)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(240) })   // USER-DATA
                    })
                }, lp(WRAP, WRAP).apply { bottomMargin = dp(4) })
            }
            val call = Calls.info(m.text) != null
            if (1 < members.size) albumBody(c, members, this)   // the plate of a media group (Album.kt)
            else if (Stickers.body(c, m, this)) Unit else if (m.text.startsWith(Marks.MEDIA)) mediaBody(c, m, this)
            else if (call) Calls.body(c, m, this)   // a call's row carries its moment inside, as iOS callBubble
            // A GAME'S INVITATION (iOS the chess letter's bubble): the knight, the seat and the clock; Accept and Decline while it waits for me
            else if (ChessSend.letterOf(m)?.kind == ChessLetter.INVITE) addView(c.chessPlate(c as? MainActivity, chat.ref, m, ChessSend.letterOf(m)!!, mine), lp(WRAP, WRAP))
            // A COIN LETTER (iOS MTCoinLetter's bubble): our coin, the signed number, which way the coins went
            else if (CoinSend.coinOf(m) != null) addView(c.coinPlate(m, CoinSend.coinOf(m)!!, mine), lp(WRAP, WRAP))
            // A SECRET SHARED FROM PASSWORDS (iOS MTSecretLetter): the key and the title; the receiver's tap saves it
            else if (SecretLetter.parse(m.text) != null) addView(c.secretPlate(c as? MainActivity, SecretLetter.parse(m.text)!!, mine), lp(WRAP, WRAP))
            // A PLACE LETTER (iOS MTPlaceLetter.parse → the map plate): the pin, the name and the spot instead of the link's words
            else if (PlaceLetter.parse(c, m.text) != null) addView(c.placePlate(PlaceLetter.parse(c, m.text)!!, mine), lp(WRAP, WRAP))
            // THE FILTER (iOS MontanaBubble 672-691): a letter of theirs with a word of the list stands folded; the tap unfolds it
            else if (ContentFilter.folded(m)) {
                val words = c.text(letterWords(c, m.text), 16f, BubbleStyle.text(mine)).apply { maxWidth = dp(260); setTextIsSelectable(false); visibility = View.GONE }   // USER-DATA: the letter
                val fold = c.vstack(Gravity.NO_GRAVITY) {
                    addView(c.hstack {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(c.icon(R.drawable.ic_set_eye_slash, BubbleStyle.text(mine), 16), lp(dp(16), dp(16)).apply { marginEnd = dp(6) })
                        addView(c.text(c.getString(R.string.filter_hidden), 16f, BubbleStyle.text(mine)), lp(WRAP, WRAP))
                    }, lp(WRAP, WRAP))
                    addView(c.text(c.getString(R.string.filter_tap), 12f, BubbleStyle.text(mine)).apply { alpha = 0.7f }, lp(WRAP, WRAP).apply { topMargin = dp(4) })
                    pressable { ContentFilter.open(m.mid); visibility = View.GONE; words.visibility = View.VISIBLE }
                }
                addView(fold, lp(WRAP, WRAP))
                addView(words, lp(WRAP, WRAP))
            }
            else addView(c.text(letterWords(c, m.text), 16f, BubbleStyle.text(mine)).apply { maxWidth = dp(260); setTextIsSelectable(false) }, lp(WRAP, WRAP))   // USER-DATA: the letter
            // THE LINK'S CARD UNDER THE WORDS (iOS the link card in the bubble), drawn from the letter's own bytes
            LinkCard.parse(m.lp)?.let { addView(linkCardView(c, it, mine), lp(WRAP, WRAP)) }
            if (!call) addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                if (m.edited) addView(c.text(c.getString(R.string.edited) + " ", 11f, BubbleStyle.time(mine)))
                addView(c.text(android.text.format.DateFormat.getTimeFormat(c).format(Date(m.at)), 11f, BubbleStyle.time(mine)))
                // no ticks in the bubble (iOS 15.47): the words stand under the last own letter; only «not sent» marks the corner
                if (mine && m.state == -1) addView(c.failedMark(), lp(dp(12), dp(12)).apply { marginStart = dp(4) })
            }, lp(WRAP, WRAP))
        }, lp(WRAP, WRAP))
        if (m.reactions.isNotEmpty()) addView(c.hstack {
            m.reactions.groupingBy { it }.eachCount().forEach { (e, n) ->
                addView(c.text(if (n > 1) "$e $n" else e, 14f, Color.WHITE).apply {
                    background = c.rounded(if (e == m.myReact) Color.argb(120, 10, 133, 255) else Color.argb(70, 255, 255, 255), 12)
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                }, lp(WRAP, WRAP).apply { marginEnd = dp(4) })
            }
        }, lp(WRAP, WRAP).apply { topMargin = dp(2) })
    }, FrameLayout.LayoutParams(WRAP, WRAP, if (mine) Gravity.END else Gravity.START))
}

/**
 * THE LETTER'S MENU (iOS MontanaMessageMenu): the room dims, the quick reactions stand over the letter, the actions under it —
 * Reply · Copy · Edit (one's own words) · Delete, which asks «for me» or «for everyone». The correspondent's page opens the same
 * cloud with its own rows (iOS onShowInChat).
 */
fun letterMenu(act: MainActivity, ref: String, m: Msg, onReply: () -> Unit, onEdit: () -> Unit, onForward: () -> Unit, onSelect: () -> Unit, group: Boolean = false, members: List<Msg> = listOf(m),
               onShowInChat: (() -> Unit)? = null) {
    val c: Context = act
    lateinit var close: () -> Unit
    // A GROUP'S LETTER (iOS MontanaMessageMenu in a group): the deeds that tell the group ride its carrier (Groups.signal); the
    // field's deeds stand while this phone writes into it
    val writes = !group || Groups.canWrite(ref)
    // a coin letter is never edited (iOS editMessage 3352-3353, 06.10): its coins were taken once, under its words
    val canEdit = m.mine && writes && !Marks.isService(m.text) && CoinLetter.parse(m.text) == null
    val actions = c.vstack(Gravity.NO_GRAVITY) { background = c.rounded(Color.argb(235, 44, 44, 46), 16, Color.argb(26, 255, 255, 255)) }
    val line = Color.argb(77, 142, 142, 147)   // iOS MTMenuDivider: the gray at three tenths
    lateinit var fillActions: () -> Unit
    // iOS MTMenuRow: the title left, the glyph right — red for a deed that cannot be undone; «keep» stays in the menu
    fun row(words: String, glyph: Int, red: Boolean = false, mirrored: Boolean = false, keep: Boolean = false, work: () -> Unit) {
        if (actions.childCount > 0) actions.addView(View(c).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        actions.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            addView(c.text(words, 16f, if (red) SysColor.red else Color.WHITE), lp(0, WRAP, 1f))
            addView(c.icon(glyph, if (red) SysColor.red else Color.argb(204, 255, 255, 255), 20).apply { if (mirrored) scaleX = -1f }, lp(dp(20), dp(20)))
            pressable { if (!keep) close(); work() }
        }, lp())
    }
    fillActions = {
        actions.removeAllViews()
        // THE PROFILE'S MENU (iOS MessageContextOverlay with onShowInChat): the same cloud, other rows — show in chat and forward, then
        // the chat's own delete (for me alone) and select
        val profile = onShowInChat
        if (profile != null) {
            row(c.getString(R.string.gl_show_in_chat), R.drawable.ic_bar_chats) { profile() }
            if (canForward(m)) row(c.getString(R.string.ld_forward), R.drawable.ic_reply, mirrored = true) { onForward() }
        } else {
            // THE RUNG AND ITS MOMENT over the actions (iOS MontanaMessageMenu ladder): the same line as under the bubble, with the time
            if (m.mine) actions.addView(c.deliveryLine(ref, m, withMoment = true).apply { setPadding(dp(16), dp(11), dp(16), dp(11)) }, lp())
            if (writes) row(c.getString(R.string.reply), R.drawable.ic_reply) { onReply() }
            if (!Marks.isService(m.text)) row(c.getString(R.string.copy), R.drawable.ic_content_copy) {
                c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", m.text))
            }
            if (canEdit) row(c.getString(R.string.edit), R.drawable.ic_pencil) { onEdit() }
            // PIN · FORWARD (iOS MontanaMessageMenu 488-492): the pin asks «for both / for me», the unpin is told at once
            if (!Marks.isService(m.text) || m.text.startsWith(Marks.MEDIA))
                row(c.getString(if (MsgPins.isPinned(ref, m.mid)) R.string.ld_unpin else R.string.ld_pin), R.drawable.ic_pin) { pinOrUnpin(act, ref, m) }
            if (canForward(m)) row(c.getString(R.string.ld_forward), R.drawable.ic_reply, mirrored = true) { onForward() }
        }
        // «Delete» asks in place (iOS confirmingDelete 403-414): for everyone, for me — any bubble, mine or theirs, the mid is the same
        // on both sides — and «Cancel» goes back to the deeds. A coin letter of mine on its way is offered no deletion (iOS canDelete:
        // MTCoinSend.travels, MontanaConversation 2293): it keeps its coins and its row until its road ends
        if (members.none { CoinSend.travels(it) }) row(c.getString(R.string.delete), R.drawable.ic_delete, red = true, keep = true) {
            actions.removeAllViews()
            actions.addView(c.text(c.getString(R.string.delete_message_q), 13f, MT.gray, center = true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }, lp())
            // in a group a speaker takes away their own letter for everyone (iOS canDeleteForEveryone: chat.isGroup ? m.isMine)
            // a plate of a media group goes whole (iOS rowLetters: a group is one bubble)
            val mids = members.map { it.mid }.toSet()
            if (profile == null && (!group || m.mine)) row(c.getString(R.string.delete_for_everyone), R.drawable.ic_delete, red = true) {
                Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid in mids } }
                for (x in mids) if (group) Groups.signal(ref, Marks.DELETE + "mid:" + x) else Post.send(ref, Marks.mintMid(), Marks.DELETE + "mid:" + x)
            }
            row(c.getString(R.string.delete_for_me), R.drawable.ic_delete, red = true) { Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid in mids } } }
            row(c.getString(R.string.cancel), R.drawable.ic_close, keep = true) { fillActions() }
        }
        // «Report» on their letter (iOS 499-502): the report of its sender
        if (profile == null && !m.mine && !group) row(c.getString(R.string.pi_report), R.drawable.ic_report, red = true) {
            val name = Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
            act.push { cl -> reportPage(act, ref, name, cl) }
        }
        // «SELECT» stands apart at the foot (iOS 503-504: after a wider gap)
        actions.addView(View(c).apply { setBackgroundColor(line) }, lp(MATCH, c.dp(6)))
        val before = actions.childCount
        row(c.getString(R.string.ld_select), R.drawable.ic_set_check_circle) { onSelect() }
        if (actions.childCount - before == 2) actions.removeViewAt(before)   // the row's own hairline under the wide gap goes
    }
    fillActions()
    // THE QUICK REACTIONS (iOS MTReactions.common): a press puts it on or takes it off; the peer hears RC:{sid,txt,e,op}.
    val reactions = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        background = c.rounded(Color.argb(235, 44, 44, 46), 24)
        addView(c.hstack {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            for (e in listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "🎉", "🙏", "👏")) addView(c.text(e, 28f).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                pressable {
                    close()
                    var added = false
                    val prev = m.myReact
                    Book.edit(ref) { ch ->
                        ch.msgs.find { it.mid == m.mid }?.let { t ->
                            t.myReact?.let { old -> t.reactions.remove(old) }
                            if (prev == e) t.myReact = null else { t.reactions.add(e); t.myReact = e; added = true }
                        }
                    }
                    fun tell(emoji: String, add: Boolean) {
                        val word = Marks.REACTION + JSONObject().put("sid", "mid:" + m.mid).put("txt", m.text.take(200)).put("e", emoji).put("op", if (add) "add" else "del")
                        // a group's answer rides the group's own carrier to every phone of it (iOS react → MTGroup.signal)
                        if (group) Groups.signal(ref, word) else Post.send(ref, Marks.mintMid(), word)
                    }
                    if (prev != null) tell(prev, false)
                    if (added) tell(e, true)
                }
            })
        })
    }
    val chat = Book.chat(ref)
    val copy = if (chat != null) letterBubble(c, m, chat, members) else View(c)
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isClickable = true
        pressable { close() }
        addView(c.vstack(if (m.mine) Gravity.END else Gravity.START) {
            setPadding(dp(14), 0, dp(14), 0)
            if (!group || !Groups.isOut(ref)) { addView(reactions, lp(WRAP, WRAP)); gap(8) }
            addView(copy, lp())
            gap(8)
            addView(actions, lp(dp(250), WRAP))
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    }
    close = act.overlay(page)
}


/**
 * A MEDIA LETTER IN ITS BUBBLE (iOS MessageBubble media): the picture itself, a film's first frame under the play mark, or
 * a file's name and size; the small face that rode the manifest stands while the file is on its way; the caption under it.
 * A press opens it whole: a picture or a film in the app, a file in the system's viewer.
 */
private fun mediaBody(c: Context, m: Msg, into: LinearLayout) {
    val man = m.meta?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Media.inline(m.text)
    val f = m.file?.let { java.io.File(it) }?.takeIf { it.exists() }
    val byExt = when (f?.extension?.lowercase()) { "jpg", "jpeg", "png", "gif", "webp", "heic", "heif" -> "img"; "mp4", "mov", "m4v", "webm", "3gp" -> "vid"; else -> "doc" }
    val kind = man?.optString("k")?.ifEmpty { null } ?: byExt
    val tint = BubbleStyle.text(m.mine)
    if (kind == "aud") {
        val wv = man?.optString("wv")?.takeIf { it.isNotEmpty() }?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
        voiceBody(c, m, man?.optDouble("du", 0.0) ?: 0.0, into, wv?.let { b -> FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f } })
        return
    }
    if (kind == "vid" && man?.optBoolean("r") == true) { noteBody(c, m, f, man, into); return }
    if (kind == "img" || kind == "vid") {
        val pic = (f?.let { Media.preview(c, it, kind, 800) } ?: Media.thumbOf(man))
        into.addView(FrameLayout(c).apply {
            val w = c.dp(240)
            val h = if (pic != null) (w * pic.height / maxOf(1, pic.width)).coerceIn(c.dp(120), c.dp(320)) else c.dp(180)
            addView(ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (pic != null) setImageBitmap(pic) else setBackgroundColor(Color.argb(60, 255, 255, 255))
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, v.dp(12).toFloat())
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(w, h))
            // automatic download off: the attachment waits for a tap (iOS queuePendingMedia), the platform's arrow down on it
            if (f == null && Media.waiting(c, m)) addView(c.icon(R.drawable.ic_arrow_circle_down, Color.WHITE).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(140, 0, 0, 0)) }
                setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10))
            }, FrameLayout.LayoutParams(c.dp(52), c.dp(52), Gravity.CENTER))
            else if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
            else if (kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(140, 0, 0, 0)) }
                setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            }, FrameLayout.LayoutParams(c.dp(52), c.dp(52), Gravity.CENTER))
            if (f != null) setOnClickListener { (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
            else if (Media.waiting(c, m)) setOnClickListener { Media.tapped(c, m) }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
    } else {
        val name = man?.optString("n")?.ifEmpty { null } ?: ("file." + (man?.optString("e") ?: ""))
        val size = man?.optLong("sz") ?: 0L
        into.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(c).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(70, 255, 255, 255)) }
                if (f == null && Media.waiting(c, m)) addView(c.icon(R.drawable.ic_arrow_circle_down, tint), FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.CENTER))
                else if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER))
                else addView(c.icon(R.drawable.ic_set_doc, tint), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }, lp(c.dp(44), c.dp(44)).apply { marginEnd = c.dp(10) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(name, 15f, tint, bold = true).apply { maxWidth = c.dp(190); singleLineEllipsis() })   // USER-DATA: the file's name
                addView(c.text(android.text.format.Formatter.formatShortFileSize(c, size), 13f, BubbleStyle.time(m.mine)))
            })
            if (f != null) setOnClickListener { (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
            else if (Media.waiting(c, m)) setOnClickListener { Media.tapped(c, m) }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
    }
    if (m.state == -1) into.addView(c.text(c.getString(R.string.media_failed), 12f, SysColor.red))
    val cap = man?.optString("cap")?.takeIf { it.isNotEmpty() }
    // A CARD'S PHOTO (iOS: a build that knows the mark draws the photo alone, and offers its words to the contacts — MTCardContactBadge)
    val card = cap?.let { BusinessCard.lines(it) }
    if (card != null && !m.mine) into.addView(c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        addView(FrameLayout(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(115, 0, 0, 0)) }
            addView(c.icon(R.drawable.ic_person_add, Color.WHITE, 18), FrameLayout.LayoutParams(c.dp(18), c.dp(18), Gravity.CENTER))
        }, lp(c.dp(40), c.dp(40)).apply { marginEnd = c.dp(8) })
        addView(c.text(c.getString(R.string.bc_add_contacts), 15f, tint), lp(WRAP, WRAP))
        pressable { (c as? MainActivity)?.let { a -> cardToContacts(a, card) } }
    }, lp(WRAP, WRAP))
    else if (card == null) cap?.let { into.addView(c.text(it, 16f, tint).apply { maxWidth = c.dp(240) }) }   // USER-DATA: the caption
}

/** THE FILE WHOLE: a picture on black, a film in the platform's own player; any other file to the system's viewer. */
internal fun openMedia(act: MainActivity, f: java.io.File, kind: String) {
    val c: Context = act
    if (kind == "doc") { openDocument(act, f); return }   // a document opens in its own page (iOS DocPreview, DocView.kt)
    if (kind != "img" && kind != "vid") {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Media.uri(c, f), c.contentResolver.getType(Media.uri(c, f)) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { act.startActivity(Intent.createChooser(view, null)) }
        return
    }
    // A PICTURE OPENS AMONG THE CONVERSATION'S PICTURES (iOS PhotoPresenter): pages, pinch, a pull down to close (Viewer.kt)
    if (kind == "img") { openPictures(act, f); return }
    // A FILM OPENS IN ITS OWN PLAYER THAT FOLDS INTO A WINDOW OVER THE CHAT (iOS VideoPresenter, picture in picture): FilmActivity
    if (kind == "vid") { FilmActivity.open(act, f); return }
    lateinit var close: () -> Unit
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        if (kind == "img") addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(Media.preview(c, f, "img", 2400))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        else addView(android.widget.VideoView(c).apply {
            setVideoURI(android.net.Uri.fromFile(f))
            setMediaController(android.widget.MediaController(c).also { it.setAnchorView(this) })
            setOnPreparedListener { start() }
        }, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        addView(c.icon(R.drawable.ic_close, Color.WHITE).apply { setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12)); pressable { close() } },
            FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.TOP or Gravity.START).apply { setMargins(c.dp(8), c.dp(8), 0, 0) })
        addView(c.icon(R.drawable.ic_share, Color.WHITE).apply {
            setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            pressable {
                val send = Intent(Intent.ACTION_SEND).setType(c.contentResolver.getType(Media.uri(c, f)) ?: "*/*")
                    .putExtra(Intent.EXTRA_STREAM, Media.uri(c, f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(send, null))
            }
        }, FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, c.dp(8), c.dp(8), 0) })
    }
    close = act.overlay(page)
}


/** A ROUND NOTE IN ITS BUBBLE (iOS videoNoteBubble): the circle with the note's own first frame; a press plays it large, with sound. */
private fun noteBody(c: Context, m: Msg, f: java.io.File?, man: JSONObject?, into: LinearLayout) {
    val side = c.dp(220)
    val pic = f?.let { Media.preview(c, it, "vid", 600) } ?: Media.thumbOf(man)
    into.addView(FrameLayout(c).apply {
        addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (pic != null) setImageBitmap(pic) else setBackgroundColor(Color.argb(60, 255, 255, 255))
        }.round(), FrameLayout.LayoutParams(side, side))
        if (f == null && Media.waiting(c, m)) {
            addView(c.icon(R.drawable.ic_arrow_circle_down, Color.WHITE), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
            setOnClickListener { Media.tapped(c, m) }   // automatic download off: the round note waits for a tap
        }
        else if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
        else setOnClickListener { (c as? MainActivity)?.let { a -> playNote(a, f) } }
    }, LinearLayout.LayoutParams(side, side).apply { bottomMargin = c.dp(4) })
    // A note stands without the bubble's plate: the circle is its own shape.
    (into.background as? android.graphics.drawable.Drawable)?.let { into.background = null; into.setPadding(0, 0, 0, 0) }
}

/** The note large, with sound, the ring for its time; a tap or its end closes it (iOS MontanaNotePlayback's own window). */
private fun playNote(act: MainActivity, f: java.io.File) {
    val c: Context = act
    lateinit var close: () -> Unit
    val side = c.dp(300)
    val ring = NoteRing(c)
    val tv = TextureView(c)
    var player: android.media.MediaPlayer? = null
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        isClickable = true
        pressable { close() }
        addView(ring, FrameLayout.LayoutParams(side + c.dp(16), side + c.dp(16), Gravity.CENTER))
        addView(tv.round(), FrameLayout.LayoutParams(side, side, Gravity.CENTER))
    }
    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            player = runCatching { android.media.MediaPlayer().apply {
                setDataSource(f.path); setSurface(android.view.Surface(st)); prepare(); start()
                setOnCompletionListener { close() }
            } }.getOrNull()
            val tick = object : Runnable { override fun run() { val p = player ?: return; runCatching { ring.progress = p.currentPosition.toFloat() / maxOf(1, p.duration) }; MainThread.later(50, this) } }
            MainThread.later(50, tick)
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { player?.runCatching { stop(); release() }; player = null; return true }
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }
    val done = act.overlay(page)
    close = { player?.runCatching { stop(); release() }; player = null; done() }
}


/**
 * THE PICTURE WITH ITS CAPTION (iOS AttachSheet's album page: «under the album stand the caption field and the send button»):
 * the picked picture or the video's first frame across the page, the field «Caption…» under it, prefilled with what was
 * typed, and the chat's own send key. The cross leaves without sending. iOS picks in its own grid; Android's picker is the
 * system's, so the caption comes on this page right after it.
 */
fun captionPage(act: MainActivity, p: Media.Picked, draft: String, onCancel: () -> Unit, onSend: (String) -> Unit): View {
    val c: Context = act
    val preview: android.graphics.Bitmap? = when (p.kind) {
        "img" -> runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, o)
            val side = maxOf(o.outWidth, o.outHeight); var s = 1
            while (side / (s * 2) >= 2048) s *= 2
            BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
        "vid" -> runCatching {
            val f = java.io.File(c.cacheDir, "cap-preview.${p.ext}").apply { writeBytes(p.bytes) }
            android.media.MediaMetadataRetriever().run { setDataSource(f.path); val b = frameAtTime; release(); f.delete(); b }
        }.getOrNull()
        else -> null
    }
    val field = EditText(c).apply {
        hint = c.getString(R.string.caption_hint); setText(draft); setSelection(text.length)
        setHintTextColor(MT.gray); setTextColor(Color.WHITE); textSize = 17f
        background = c.glassPlate().apply { cornerRadius = c.dp(20).toFloat() }
        setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 5
    }
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(ImageView(c).apply { setImageBitmap(preview); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        if (p.kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE), FrameLayout.LayoutParams(c.dp(56), c.dp(56), Gravity.CENTER))
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true); contentDescription = c.getString(R.string.cancel)
            addView(c.icon(R.drawable.ic_close, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            pressable { onCancel() }
        }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.TOP or Gravity.START).apply { setMargins(c.dp(14), c.dp(6), 0, 0) })
        addView(c.hstack {
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            addView(field, lp(0, WRAP, 1f))
            addView(ImageView(c).apply {
                setImageResource(R.drawable.send_button); scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = c.getString(R.string.send)
                pressable { onSend(field.text.toString().trim()) }
            }, lp(dp(40), dp(40)).apply { marginStart = dp(8) })
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
    }
}

/** The long press reaches the letter's menu through every part of the bubble that takes its own tap. */
private fun View.holdEverywhere(root: Boolean = true, menu: () -> Unit) {
    if (root || isClickable) setOnLongClickListener { menu(); true }
    if (this is android.view.ViewGroup) for (i in 0 until childCount) getChildAt(i).holdEverywhere(false, menu)
}
