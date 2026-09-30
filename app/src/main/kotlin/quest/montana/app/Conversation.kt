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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Date

// ─────────────────────────── what a row says of a letter (iOS MTRowLetter) ───────────────────────────

/** The words a letter shows in the list and the banner: its text, or the name of what it carries. */
fun letterWords(c: Context, t: String): String = when {
    t.startsWith(Marks.VOICE) -> c.getString(R.string.voice_message)
    t.startsWith(Marks.MEDIA) -> c.getString(R.string.attachment)
    t.startsWith(Marks.STICKER) -> t.removePrefix(Marks.STICKER)
    t.startsWith(Marks.LONG) -> c.getString(R.string.long_letter)
    else -> t
}

/** The face of a correspondent: the face laid beside the pipe, else the drawn initial (iOS MontanaAvatar). */
fun Context.peerFace(ref: String, name: String, sizeDp: Int): View {
    val f = Book.face(ref)
    val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    return avatar(bmp, name.ifBlank { "?" }, sizeDp)
}

// ─────────────────────────── the banner (iOS MontanaNotify) ───────────────────────────

object Notify {
    private const val CHANNEL = "letters"
    fun letter(ref: String, text: String) {
        val c = Book.ctx
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!Prefs.bool("notifMessages", true)) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_letters), NotificationManager.IMPORTANCE_HIGH))
        val chat = Book.chat(ref)
        val title = if (Prefs.bool("notifSender", true)) chat?.name?.ifBlank { null } ?: c.getString(R.string.peer) else "Montana"
        val body = if (Prefs.bool("notifPreview", true)) letterWords(c, text) else c.getString(R.string.new_message)
        val open = PendingIntent.getActivity(c, ref.hashCode(),
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)   // USER-DATA: the correspondent's name
            .setContentText(body)     // USER-DATA: the letter
            .setAutoCancel(true)
            .setContentIntent(open)
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
        addView(c.peerFace(chat.ref, chat.name, 54), lp(dp(54), dp(54)).apply { marginEnd = dp(12) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.hstack {
                addView(c.text(chat.name.ifBlank { c.getString(R.string.peer) }, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: the name
                if (last != null) {
                    // the ladder's dots left of the hour, only when the last word was mine; a letter that did not go, the red mark
                    if (last.mine) addView(if (last.state == -1) c.failedMark() else c.deliveryDots(Ladder.of(chat.ref, last).first),
                        lp(WRAP, WRAP).apply { marginEnd = dp(6) })
                    addView(c.text(rowTime(c, last.at), 13f, MT.gray))
                }
            }, lp())
            addView(c.hstack {
                addView(c.text(last?.let { letterWords(c, it.text) } ?: "", 15f, MT.gray).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))   // USER-DATA: the last letter
                if (chat.unread > 0) addView(c.text(chat.unread.toString(), 13f, Color.WHITE, bold = true, center = true).apply {
                    background = c.rounded(MT.blue, 11); setPadding(dp(7), dp(1), dp(7), dp(1)); minWidth = dp(22)
                }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
            }, lp())
        }, lp(0, WRAP, 1f))
        pressable { act.push { close -> conversationPage(act, chat.ref, close) } }
        setOnLongClickListener {
            AlertDialog.Builder(c).setMessage(R.string.delete_chat_q)
                .setPositiveButton(R.string.delete) { _, _ -> Book.forget(chat.ref) }
                .setNegativeButton(R.string.cancel, null).show()
            true
        }
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

fun conversationPage(act: MainActivity, ref: String, onClose: () -> Unit): View {
    val c: Context = act
    val column = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(10), dp(8), dp(10), dp(8)) }
    val scroll = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(FrameLayout(c).apply { addView(column, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)) })
    }
    fun toBottom() = scroll.post { scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0) }

    var replyTo: Msg? = null
    var editing: Msg? = null

    val field = EditText(c).apply { setText(Prefs.str("draft.$ref", "")) }
    fun hideKeys() = c.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    fun showKeys() { field.requestFocus(); c.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }

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
    fun showStrip() {
        val r = replyTo; val e = editing
        strip.visibility = if (r == null && e == null) View.GONE else View.VISIBLE
        if (e != null) { stripTitle.text = c.getString(R.string.editing); stripWords.text = letterWords(c, e.text) }
        else if (r != null) {
            stripTitle.text = if (r.mine) Prefs.userName.ifBlank { c.getString(R.string.reply) } else Book.chat(ref)?.name?.ifBlank { null } ?: c.getString(R.string.peer)
            stripWords.text = letterWords(c, r.text)   // USER-DATA
        }
    }

    // ── the feed ──
    fun redraw() {
        val chat = Book.chat(ref) ?: return
        column.removeAllViews()
        var lastDay = -1L
        for (m in chat.msgs) {
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
            val b = letterBubble(c, m, chat)
            // THE LADDER UNDER EVERY OWN BUBBLE (the author's word 29.09: «у каждого сообщения, по отдельности»): each letter its own rung
            if (m.mine) (b.getChildAt(0) as LinearLayout).addView(c.deliveryLine(ref, m),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = c.dp(3); marginEnd = c.dp(4) })
            b.getChildAt(0).setOnLongClickListener { letterMenu(act, ref, m, onReply = { replyTo = m; editing = null; showStrip(); showKeys() },
                onEdit = { editing = m; replyTo = null; field.setText(m.text); field.setSelection(field.text.length); showStrip(); showKeys() }); true }
            column.addView(b, lp().apply { topMargin = c.dp(3) })
        }
    }

    // ── sending (iOS sendMessage) ──
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) { if (editing == null) Prefs.setStr("draft.$ref", s?.toString() ?: "") }
    })
    val bar = ChatInputBar(act, field) {
        val words = field.text.toString().trim()
        field.setText("")
        val e = editing
        if (e != null) {
            // THE NEW WORDS OF A LETTER ALREADY SENT (iOS editMessage): the row changes here, the peer is told by ED:{sid,tx}.
            editing = null
            if (words != e.text) {
                Book.edit(ref) { ch -> ch.msgs.find { it.mid == e.mid }?.let { it.text = words; it.edited = true } }
                Post.send(ref, Marks.mintMid(), Marks.EDIT + JSONObject().put("sid", e.mid).put("tx", words))
            }
        } else {
            val r = replyTo
            replyTo = null
            val mid = Marks.mintMid()
            val qt = r?.let { letterWords(c, it.text).take(200) }
            Book.edit(ref) { it.msgs.add(Msg(mid, words, true, Marks.birthMs(mid) ?: System.currentTimeMillis(), qt = qt, qm = r?.mid)) }
            Post.send(ref, mid, words, qt, r?.mid)
        }
        showStrip()
        toBottom()
    }
    // THE GALLERY AND THE FILE KEYS SEND FOR REAL (iOS PhotosPicker / fileImporter → MontanaMedia): read, sealed, laid, lettered.
    fun sendPicked(asDoc: Boolean): (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) Thread {
            val p = Media.read(c, uri, asDoc)
            if (p == null) MainThread.post { android.widget.Toast.makeText(c, R.string.media_failed, android.widget.Toast.LENGTH_LONG).show() }
            else { Media.send(c, ref, p, ""); MainThread.post { toBottom() } }
        }.start()
    }
    bar.onGallery = { act.pickVisual(sendPicked(false)) }
    bar.onFile = { act.openDocument(sendPicked(true)) }
    // THE VOICE (iOS the held mark): the tape starts under the finger and leaves as a media letter of kind «aud».
    // THE SWELL WHILE SPEAKING (iOS the recording row): the red mark, the running time and the wave that rises with the voice.
    val liveWave = WaveView(c).apply { played = Color.WHITE }
    val liveTime = c.text("0:00", 15f, Color.WHITE)
    val dot = View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SysColor.red) } }
    val recStrip = c.hstack {
        visibility = View.GONE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(6), dp(18), dp(6))
        addView(dot, lp(dp(10), dp(10)).apply { marginEnd = dp(8) })
        addView(liveTime, lp(WRAP, WRAP).apply { marginEnd = dp(12) })
        addView(liveWave, lp(0, WRAP, 1f))
    }
    fun endStrip() { recStrip.visibility = View.GONE; VoiceTape.onLevel = null; dot.animate().cancel() }
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
        val page = FrameLayout(c).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            addView(ring, FrameLayout.LayoutParams(side + c.dp(16), side + c.dp(16), Gravity.CENTER))
            addView(tv.round().apply { setOnClickListener { note?.flip() } }, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            addView(time.apply { setShadowLayer(6f, 0f, 0f, Color.BLACK) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = side / 2 + c.dp(30) })
        }
        noteClose = act.overlay(page)
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

    // ── the bar: the back mark, the face and the name; the face's press offers the chat's own actions ──
    val chat0 = Book.chat(ref)
    val title = c.text(chat0?.name?.ifBlank { null } ?: c.getString(R.string.peer), 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() }   // USER-DATA
    val faceSlot = FrameLayout(c)
    fun drawHead() {
        val ch = Book.chat(ref) ?: return
        title.text = ch.name.ifBlank { c.getString(R.string.peer) }
        faceSlot.removeAllViews(); faceSlot.addView(c.peerFace(ref, ch.name, 36), FrameLayout.LayoutParams(c.dp(36), c.dp(36)))
    }
    drawHead()
    val top = FrameLayout(c).apply {
        setPadding(dp(8), 0, dp(8), 0)
        addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); pressable { hideKeys(); onClose() } },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { marginStart = dp(56); marginEnd = dp(56) })
        addView(faceSlot.apply {
            // the face opens the correspondent's page (iOS: the chat's header → MontanaPeerInfoScreen)
            setOnClickListener { hideKeys(); act.push { close -> peerInfoPage(act, ref, close) } }
            setOnLongClickListener {
                AlertDialog.Builder(c).setMessage(R.string.delete_chat_q)
                    .setPositiveButton(R.string.delete) { _, _ -> hideKeys(); onClose(); Book.forget(ref) }
                    .setNegativeButton(R.string.cancel, null).show(); true
            }
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(8) })
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52))
    }

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
        if (column.childCount != before) toBottom()
        if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start()
    }

    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(CrestGround(c), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(top)
            addView(scroll, lp(MATCH, 0, 1f))
            addView(strip, lp())
            addView(recStrip, lp())
            addView(bar, lp())
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.requestApplyInsets()
                Book.openChat = ref; Book.listen(listener); Notify.clear(ref)
                if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start()
            }
            override fun onViewDetachedFromWindow(v: View) { if (Book.openChat == ref) Book.openChat = null; Book.unlisten(listener) }
        })
        scroll.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t < ob - ot) toBottom() }
        redraw()
        toBottom()
    }
}

/** One letter: the quote it answers, its words, «edited» and the time; its reactions under it (iOS MessageBubble). */
private fun letterBubble(c: Context, m: Msg, chat: Chat): FrameLayout = FrameLayout(c).apply {
    val mine = m.mine
    addView(c.vstack(if (mine) Gravity.END else Gravity.START) {
        addView(c.vstack(Gravity.END) {
            background = BubbleStyle.drawable(context, mine)
            setPadding(dp(12), dp(7), dp(12), dp(6))
            if (m.qt != null) {
                val whose = m.qm?.let { q -> chat.msgs.find { it.mid == q } }
                addView(c.hstack {
                    addView(View(c).apply { setBackgroundColor(BubbleStyle.text(mine)) }, lp(dp(2), MATCH).apply { marginEnd = dp(6) })
                    addView(c.vstack(Gravity.NO_GRAVITY) {
                        addView(c.text(if (whose?.mine == true) Prefs.userName.ifBlank { "·" } else chat.name.ifBlank { c.getString(R.string.peer) }, 13f, BubbleStyle.text(mine), bold = true))
                        addView(c.text(m.qt, 13f, BubbleStyle.time(mine)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(240) })   // USER-DATA
                    })
                }, lp(WRAP, WRAP).apply { bottomMargin = dp(4) })
            }
            if (m.text.startsWith(Marks.MEDIA)) mediaBody(c, m, this)
            else addView(c.text(letterWords(c, m.text), 16f, BubbleStyle.text(mine)).apply { maxWidth = dp(260); setTextIsSelectable(false) }, lp(WRAP, WRAP))   // USER-DATA: the letter
            addView(c.hstack {
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
 * Reply · Copy · Edit (one's own words) · Delete, which asks «for me» or «for everyone».
 */
private fun letterMenu(act: MainActivity, ref: String, m: Msg, onReply: () -> Unit, onEdit: () -> Unit) {
    val c: Context = act
    lateinit var close: () -> Unit
    val canEdit = m.mine && !Marks.isService(m.text)
    val actions = c.vstack(Gravity.NO_GRAVITY) { background = c.rounded(Color.argb(235, 44, 44, 46), 14) }
    fun row(words: String, red: Boolean = false, work: () -> Unit) {
        if (actions.childCount > 0) actions.addView(View(c).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, lp(MATCH, 1))
        actions.addView(c.text(words, 17f, if (red) SysColor.red else Color.WHITE).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); pressable { close(); work() } }, lp())
    }
    fun fillActions() {
        actions.removeAllViews()
        // THE RUNG AND ITS MOMENT over the actions (iOS MontanaMessageMenu ladder): the same line as under the bubble, with the time
        if (m.mine) actions.addView(c.deliveryLine(ref, m, withMoment = true).apply { setPadding(dp(16), dp(11), dp(16), dp(11)) }, lp())
        row(c.getString(R.string.reply)) { onReply() }
        if (!Marks.isService(m.text)) row(c.getString(R.string.copy)) {
            c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", m.text))
        }
        if (canEdit) row(c.getString(R.string.edit)) { onEdit() }
        actions.addView(View(c).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, lp(MATCH, 1))
        actions.addView(c.text(c.getString(R.string.delete), 17f, SysColor.red).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            pressable {
                // «Delete message?» — for me, for everyone (any bubble, mine or theirs: the mid is the same on both sides).
                actions.removeAllViews()
                actions.addView(c.text(c.getString(R.string.delete_message_q), 13f, MT.gray, center = true).apply { setPadding(dp(16), dp(10), dp(16), dp(6)) }, lp())
                row(c.getString(R.string.delete_for_everyone), red = true) {
                    Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid == m.mid } }
                    Post.send(ref, Marks.mintMid(), Marks.DELETE + "mid:" + m.mid)
                }
                row(c.getString(R.string.delete_for_me), red = true) { Book.edit(ref) { ch -> ch.msgs.removeAll { it.mid == m.mid } } }
                row(c.getString(R.string.cancel)) {}
            }
        }, lp())
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
                    fun tell(emoji: String, add: Boolean) = Post.send(ref, Marks.mintMid(), Marks.REACTION +
                        JSONObject().put("sid", "mid:" + m.mid).put("txt", m.text.take(200)).put("e", emoji).put("op", if (add) "add" else "del"))
                    if (prev != null) tell(prev, false)
                    if (added) tell(e, true)
                }
            })
        })
    }
    val chat = Book.chat(ref)
    val copy = if (chat != null) letterBubble(c, m, chat) else View(c)
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isClickable = true
        pressable { close() }
        addView(c.vstack(if (m.mine) Gravity.END else Gravity.START) {
            setPadding(dp(14), 0, dp(14), 0)
            addView(reactions, lp(WRAP, WRAP))
            gap(8)
            addView(copy, lp())
            gap(8)
            addView(actions, lp(dp(240), WRAP))
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
            if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
            else if (kind == "vid") addView(c.icon(R.drawable.ic_play_fill, Color.WHITE).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(140, 0, 0, 0)) }
                setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
            }, FrameLayout.LayoutParams(c.dp(52), c.dp(52), Gravity.CENTER))
            if (f != null) setOnClickListener { (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
    } else {
        val name = man?.optString("n")?.ifEmpty { null } ?: ("file." + (man?.optString("e") ?: ""))
        val size = man?.optLong("sz") ?: 0L
        into.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(c).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(70, 255, 255, 255)) }
                if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER))
                else addView(c.icon(R.drawable.ic_set_doc, tint), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
            }, lp(c.dp(44), c.dp(44)).apply { marginEnd = c.dp(10) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(name, 15f, tint, bold = true).apply { maxWidth = c.dp(190); singleLineEllipsis() })   // USER-DATA: the file's name
                addView(c.text(android.text.format.Formatter.formatShortFileSize(c, size), 13f, BubbleStyle.time(m.mine)))
            })
            if (f != null) setOnClickListener { (c as? MainActivity)?.let { a -> openMedia(a, f, kind) } }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = c.dp(4) })
    }
    if (m.state == -1) into.addView(c.text(c.getString(R.string.media_failed), 12f, SysColor.red))
    man?.optString("cap")?.takeIf { it.isNotEmpty() }?.let { into.addView(c.text(it, 16f, tint).apply { maxWidth = c.dp(240) }) }   // USER-DATA: the caption
}

/** THE FILE WHOLE: a picture on black, a film in the platform's own player; any other file to the system's viewer. */
internal fun openMedia(act: MainActivity, f: java.io.File, kind: String) {
    val c: Context = act
    if (kind != "img" && kind != "vid") {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Media.uri(c, f), c.contentResolver.getType(Media.uri(c, f)) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { act.startActivity(Intent.createChooser(view, null)) }
        return
    }
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
        if (f == null) addView(android.widget.ProgressBar(c), FrameLayout.LayoutParams(c.dp(36), c.dp(36), Gravity.CENTER))
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
