package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView

/**
 * MY PAGE (iOS MontanaMyProfileView, opened from the drawer as MTMyPageFromDrawer): my profile as the people I write to see it — the
 * one face owner (FaceHead) at its circle, my name as it leaves this phone, my words and my link as they leave it, over my page's
 * ground. The back mark top left; «Edit» top right opens the profile's editor over the page, and its checkmark comes back to the page
 * drawn anew. My own wall stands under the head when the wall comes (iOS MTBoardPane(owner: nil)).
 */
fun myPage(act: MainActivity, onClose: () -> Unit): View {
    val c: Context = act
    val body = c.vstack(Gravity.NO_GRAVITY) { setPadding(0, dp(56), 0, dp(40)) }
    val face = FaceHead(act, body)
    fun draw() = face.draw(SelfFace.load(act), Prefs.userName, false, MyAbout.bio(), PeerAbout.links(MyAbout.link()), "")
    draw()
    body.addView(face.view, lp())
    // MY OWN WALL UNDER THE HEAD (iOS MTBoardPane(owner: nil)): the row that writes a post, then my posts, the pinned first
    val wall = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(12), dp(16), dp(12), 0) }
    // a post that did not change keeps its own cell where it stands: a mark does not shake the wall (PostCells)
    val cells = PostCells(act)
    fun drawWall() {
        val rows = mutableListOf<View>()
        rows += writeFace(act).apply { layoutParams = lp() }
        // MY DRAFT STANDS ON MY WALL (iOS MTBoardRows 165-169), over the posts on their way
        draftCell(act)?.let { rows += it.apply { layoutParams = lp().apply { topMargin = c.dp(10) } } }
        // A POST ON ITS WAY stands at once as it will be published, under the bar of its files (iOS MTBoardRows 167-172)
        val going = MyWall.sending()
        going.forEach { o -> rows += goingCell(act, o).apply { layoutParams = lp().apply { topMargin = c.dp(10) } } }
        val ps = MyWall.posts()
        if (ps.isEmpty() && going.isEmpty()) rows += c.text(c.getString(R.string.pi_wall_empty), 15f, MT.gray, center = true).apply { setPadding(0, dp(28), 0, dp(28)); layoutParams = lp() }
        else ps.forEach { p -> rows += cells.cell(Board.Item("", p), onItsWall = true).also { v -> if (v.layoutParams == null) v.layoutParams = lp().apply { topMargin = c.dp(10) } } }
        wall.relay(rows)
        cells.keepOnly(rows)
    }
    drawWall()
    body.addView(wall, lp())
    val heard: () -> Unit = { drawWall() }
    wall.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { MyWall.listen(heard); drawWall() }
        override fun onViewDetachedFromWindow(v: View) { MyWall.unlisten(heard) }
    })
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))   // my page's ground (iOS MTFacePage of .me)
        addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(body); face.attach(this) }, FrameLayout.LayoutParams(MATCH, MATCH))
        // the back mark on its own round of glass, over the page (iOS MontanaBackMark)
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            contentDescription = c.getString(R.string.back)
            addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            pressable { onClose() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(6), 0, 0) })
        // THE PAGE'S AUTHOR EDITS IT FROM IT (iOS MontanaEditMark, the author's word 25.09): the editor over the page closes itself,
        // never the page under it
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            contentDescription = c.getString(R.string.pe_edit)
            addView(c.icon(R.drawable.ic_more_horiz, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            pressable { act.push { close -> Profile(act, onboarding = false) { close(); draw() }.view } }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(14), 0) })
    }
}

/**
 * A POST ON ITS WAY'S BAR (iOS MTBoardProgress 1542-1577): «Publishing» or «Could not post», the share and the bytes the node confirmed,
 * and -- when the node did not take it -- try again and delete; the platform's own line under them.
 */
fun goingBar(act: MainActivity, o: MyWall.Going, onRetry: (() -> Unit)?, onDiscard: (() -> Unit)?): View {
    val c: Context = act
    fun glyph(res: Int, words: Int, deed: () -> Unit) = c.icon(res, Color.WHITE, 20).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12)); contentDescription = c.getString(words); pressable(deed)
    }
    return c.vstack(Gravity.NO_GRAVITY) {
        background = c.glassPlate()
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            addView(c.text(c.getString(if (o.failed) R.string.wall_could_not_post else R.string.wall_publishing), 15f, Color.WHITE, bold = true)
                .apply { singleLineEllipsis() }, lp(0, WRAP, 1f))
            gap(6)
            addView(c.text(c.getString(R.string.wall_share_of, (o.share * 100).toInt(), Formatter.formatShortFileSize(c, o.done),
                Formatter.formatShortFileSize(c, o.total)), 15f, MT.gray).apply { singleLineEllipsis() })
            if (o.failed && onRetry != null) addView(glyph(R.drawable.ic_refresh, R.string.try_again, onRetry), lp(dp(44), dp(44)))
            if (o.failed && onDiscard != null) addView(glyph(R.drawable.ic_delete, R.string.delete, onDiscard), lp(dp(44), dp(44)))
        }, lp())
        gap(8)
        addView(ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (o.share * 1000).toInt()
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(MT.gray)
        }, lp())
    }
}

/** THE AUTHOR'S ARTWORK OF A NEW POST (iOS MTBoardWriteIcon, MontanaBoardViews.swift:113-119 at 2336, atom 2fb6abb2): the supplied
 *  WriteOnWall picture in its own colours, on every control that begins a post on a wall. */
fun writeArt(c: Context, side: Int): View = ImageView(c).apply {
    setImageResource(R.drawable.write_on_wall)
    scaleType = ImageView.ScaleType.FIT_CENTER
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    layoutParams = android.widget.LinearLayout.LayoutParams(c.dp(side), c.dp(side))
}

/** THE WALL'S WRITE BUTTON, ONE DRAWING (iOS MTBoardWriteFace 122-135 at 2336): the artwork at 40, the words, the plate's glass, 50
 *  high; my wall ("") and a person's wall draw the same one, and it opens the new post's page on that wall. */
fun writeFace(act: MainActivity, wall: String = ""): View = act.hstack {
    background = act.glassPlate()
    minimumHeight = dp(50)
    setPadding(dp(16), dp(5), dp(16), dp(5))
    addView(writeArt(act, 40), lp(dp(40), dp(40)).apply { marginEnd = dp(10) })
    addView(act.text(act.getString(R.string.pi_write_wall), 17f))
    pressable { act.push { close -> newPostPage(act, close, wall = wall) } }
}

/** The post as the wall will draw it, from a draft's words (iOS MTBoardDraft.seen): my name, my face on another's wall, the moment it
 *  was begun, every count at nothing. */
fun draftPost(wall: String, at: Double, text: String, files: List<MyWall.Attachment> = emptyList()): org.json.JSONObject {
    val name = Prefs.userName.trim()
    return org.json.JSONObject().put("id", "").put("author", "").put("byName", name).put("byGlyph", name.take(1).uppercase())
        .put("at", at).put("text", text).put("media", draftMedia(files)).put("pinned", false)
        .put("keepers", org.json.JSONArray().put("")).put("likes", org.json.JSONArray()).put("downloads", org.json.JSONArray())
        .put("reposts", org.json.JSONArray()).put("comments", org.json.JSONArray())
        // on my own wall the post is the owner's (own): it wears my face as my published posts do
        .put("own", wall.isEmpty()).apply { if (wall.isNotEmpty()) Board.myFace()?.let { put("face", it) } }
}

/** The files of a post being written as the post will carry them (iOS MTBoardDraft.seen). */
private fun draftMedia(files: List<MyWall.Attachment>) = org.json.JSONArray().apply {
    for (f in files) put(org.json.JSONObject().put("kind", f.kind).put("name", f.name).put("ext", f.ext).put("size", f.file.length())
        .put("key", "").put("chunks", org.json.JSONArray()).apply { f.dur?.let { put("dur", it) }; f.frame?.let { put("fr", it) } })
}

/**
 * A POST NOT YET PUBLISHED, ON ITS WALL (iOS MTBoardDraftCell, MontanaBoardViews.swift:1716-1755 at 2336; the author's word 25.09:
 * «the wall keeps even a draft not published»): a plate naming it a draft -- the artwork at 36 and «Draft», its trash throwing the draft
 * away with its files -- over the post as it will stand; a touch on the plate or the post opens the page that writes it, where it was
 * left. Null while the wall holds no draft.
 */
fun draftCell(act: MainActivity, wall: String = ""): View? {
    val d = MyWall.draft(wall) ?: return null
    val c: Context = act
    val open = { act.push { close -> newPostPage(act, close, wall = wall) } }
    return c.vstack(Gravity.NO_GRAVITY) {
        addView(c.hstack {
            background = c.glassPlate()
            setPadding(dp(16), dp(4), dp(4), dp(4))
            addView(c.hstack {
                minimumHeight = dp(44)
                addView(writeArt(c, 36), lp(dp(36), dp(36)).apply { marginEnd = dp(8) })
                addView(c.text(c.getString(R.string.wall_draft), 15f, bold = true).apply { maxLines = 1 })
                pressable { open() }
            }, lp(0, WRAP, 1f))
            addView(FrameLayout(c).apply {
                contentDescription = c.getString(R.string.delete)
                addView(c.icon(R.drawable.ic_delete, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                pressable { MyWall.dropDraft(wall, "trash", files = true) }
            }, lp(dp(44), dp(44)))
        }, lp())
        gap(8)
        // the whole post is the button: nothing inside it answers by itself
        addView(FrameLayout(c).apply {
            addView(postCell(act, Board.Item(wall, draftPost(wall, d.at, d.text, d.files)), onItsWall = true, sending = true, sources = d.files.map { f -> f.file }), FrameLayout.LayoutParams(MATCH, WRAP))
            addView(View(c).apply { pressable { open() } }, FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp())
    }
}

/** A POST ON ITS WAY, ON ITS WALL (iOS MTBoardSendingCell 1581-1592): the bar of its files over the post as it will stand. */
fun goingCell(act: MainActivity, o: MyWall.Going): View = act.vstack(Gravity.NO_GRAVITY) {
    addView(goingBar(act, o, onRetry = { Thread { MyWall.publish(o.id) }.start() }, onDiscard = { Thread { MyWall.discard(o.id) }.start() }), lp())
    gap(8)
    addView(postCell(act, Board.Item(o.wall, o.post), onItsWall = true, sending = true), lp())
}

/**
 * A NEW POST (iOS MTBoardComposer, MontanaBoardViews 1666-2028; the author's word 24.09: «the new post shows at once as it will be
 * published»): my face and my name over the words, written where they will stand; under them «Photos and videos» and «Tracks and
 * files», and the files taken, each with its miniature, its name and its size, the cross taking it off. The check mark sets the post
 * on its way (MyWall.begin) and lays its files on the node under the bar at the page's top (MyWall.publish); the page leaves when the
 * post is out, and a post the node did not take keeps its try again here and on the wall. Opened from a person's page (wall, their
 * reference) the post goes to their wall (iOS MTBoardComposer.present(on: owner), MTBoard.begin(on:) 1772).
 */
fun newPostPage(act: MainActivity, onClose: () -> Unit, sharedWords: String = "", sharedFiles: List<android.net.Uri> = emptyList(), wall: String = ""): View {
    val c: Context = act
    val files = mutableListOf<MyWall.Attachment>()
    var taking = 0
    var sending: String? = null
    var closed = false
    // THE PAGE OPENS AS IT WAS LEFT (iOS MTBoardComposer.init, 25.09): the wall's draft -- its words, its files and the moment it was begun
    val held = MyWall.draft(wall)
    val at = held?.at ?: (System.currentTimeMillis() / 1000.0)
    held?.let { files.addAll(it.files) }
    // THE WORDS STAND WHERE THE POST'S WORDS WILL STAND (iOS MTBoardDrafting, 0ba63ede): the post's own font, no frame of its own
    val field = android.widget.EditText(c).apply {
        hint = c.getString(R.string.write_something)
        setTextColor(Color.WHITE); setHintTextColor(MT.gray); background = null
        textSize = 16f
        minLines = 2
        setPadding(0, 0, 0, 0)
        held?.let { setText(it.text) }   // USER-DATA: my draft's own words
    }
    val barSlot = FrameLayout(c)
    val tiles = FrameLayout(c).apply { visibility = View.GONE }
    lateinit var openFrame: (MyWall.Attachment) -> Unit
    val picks = c.plate {}
    val taken = c.plate {}
    val failedLine = c.text(c.getString(R.string.wall_could_not_post), 15f, MT.red).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE }
    lateinit var done: View
    fun empty() = field.text.isBlank() && files.isEmpty()
    fun dress() {
        val off = empty() || 0 < taking || sending != null
        done.alpha = if (off) 0.4f else 1f
        picks.visibility = if (sending == null) View.VISIBLE else View.GONE
    }
    // EVERY CHANGE IS LAID INTO THE WALL'S STORE (iOS lay, 25.09): the words as they are typed (a burst folded into one write), a
    // file the moment it is picked or taken off; while the post is on its way the page holds nothing of its own
    val layNow = Runnable { if (sending == null) MyWall.layDraft(wall, field.text.toString(), files.toList(), at) }
    fun lay(soon: Boolean = false) {
        field.removeCallbacks(layNow)
        if (soon) field.postDelayed(layNow, 400) else layNow.run()
    }
    fun leave() {
        // the draft stays (iOS 25.09): its words and files wait for the page's next opening, and the wall under the page draws them
        field.removeCallbacks(layNow)
        if (sending == null) MyWall.layDraft(wall, field.text.toString(), files.toList(), at, told = true)
        closed = true
        onClose()
    }
    // THE FILES STAND IN THE POST AS IT WILL BE PUBLISHED (iOS MTBoardCell's draft, 0ba63ede): the tiles in their frames from the files
    // themselves, a touch on a picture opens its frame; a post on its way keeps what is drawn, its files have left for the store
    fun drawTiles() {
        if (sending != null) return
        tiles.removeAllViews()
        tiles.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        if (files.isEmpty()) return
        val shown = files.toList()
        tiles.addView(c.vstack(Gravity.NO_GRAVITY) {
            postMedia(act, Board.Item(wall, draftPost(wall, at, "", shown)), true, true, shown.map { f -> f.file }) { i ->
                shown.getOrNull(i)?.takeIf { f -> sending == null && (f.kind == "img" || f.kind == "vid") }?.let(openFrame)
            }
        }, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    fun drawTaken() {
        drawTiles()
        taken.removeAllViews()
        for (f in files) taken.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(6), dp(4), dp(6))
            val visual = f.kind == "img" || f.kind == "vid"
            // a touch on a picture's row opens its frame, as a touch on its tile does (iOS attached)
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                addView(if (visual) ImageView(c).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = c.rounded(MT.hairline, 8); outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
                    Thread { WallFiles.drawn(c, f.file, f.kind, f.frame, 132, 132)?.let { b -> MainThread.post { setImageBitmap(b) } } }.start()
                } else FrameLayout(c).apply {
                    background = c.rounded(Color.rgb(41, 41, 41), 8)
                    addView(c.icon(if (f.kind == "aud") R.drawable.ic_music_note else R.drawable.ic_compose_doc, MT.gray, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                }, lp(dp(44), dp(44)))
                gap(12)
                addView(c.vstack(Gravity.NO_GRAVITY) {
                    addView(c.text(f.name, 17f).apply { singleLineEllipsis() }, lp())   // USER-DATA: the file's own name
                    addView(c.text(Formatter.formatShortFileSize(c, f.file.length()), 12f, MT.gray), lp())
                }, lp(0, WRAP, 1f))
                if (visual) {
                    gap(8)
                    addView(c.icon(R.drawable.ic_crop, MT.gray, 20), lp(dp(20), dp(20)))
                    pressable { if (sending == null) openFrame(f) }
                }
            }, lp(0, WRAP, 1f))
            if (sending == null) addView(c.icon(R.drawable.ic_cancel, MT.gray, 22).apply {
                setPadding(dp(11), dp(11), dp(11), dp(11))
                contentDescription = c.getString(R.string.wall_remove)
                pressable { files.remove(f); f.file.delete(); drawTaken(); lay() }
            }, lp(dp(44), dp(44)))
        }, lp())
        // a file on its way from the picker: the platform's wheel where its row will stand
        if (0 < taking) taken.addView(ProgressBar(c).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }, lp(MATCH, c.dp(44)).apply { setMargins(0, c.dp(6), 0, c.dp(6)) })
        taken.visibility = if (files.isEmpty() && taking == 0) View.GONE else View.VISIBLE
        dress()
    }
    fun take(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        taking += uris.size
        drawTaken()
        // THE PAGE'S ORDER IS THE POST'S ORDER (iOS MTBoardComposer.take, 2312): one hand copies the files one after another, and
        // the diary says how many were picked and how many came
        Thread {
            var got = 0
            for (u in uris) {
                val a = WallFiles.take(c, u)?.also { x -> WallFiles.dress(c, x) }
                if (a != null) got++
                MainThread.post {
                    taking--
                    if (a != null && files.size < MEDIA_LIMIT && !closed) { files.add(a); lay() } else a?.file?.delete()
                    drawTaken()
                }
            }
            android.util.Log.i("Montana", "post_files picked=${uris.size} staged=$got")
        }.start()
    }
    // THE PICTURE'S FRAME (iOS MTBoardComposer.frame, 0ba63ede): the picture upright, as large as the page draws it, under the one crop
    var framing = false
    openFrame = { f ->
        if (!framing) {
            framing = true
            Thread {
                val image = Media.preview(c, f.file, f.kind, 2048)
                MainThread.post {
                    if (image == null || closed || image.width <= 0 || image.height <= 0) framing = false
                    else {
                        c.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
                        lateinit var close: () -> Unit
                        val crop = frameCropPage(act, image, f.frame ?: WallFiles.ownFrame(image.width, image.height)) { kept ->
                            close()
                            framing = false
                            android.util.Log.i("Montana", "post_frame " + (if (kept == null) "left" else "set"))
                            if (kept != null) { f.frame = kept; drawTaken(); lay() }
                        }
                        close = act.overlay(crop)
                        // the keys are on their way down: the page stands on the system's bar alone, not on the height the keys held (seen on A1, 852)
                        act.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.let { b -> crop.setPadding(crop.paddingLeft, crop.paddingTop, crop.paddingRight, b.bottom) }
                    }
                }
            }.start()
        }
    }
    fun showBar() {
        barSlot.removeAllViews()
        val pid = sending?.takeIf { it.isNotEmpty() } ?: return
        val o = MyWall.sending().firstOrNull { it.id == pid } ?: return
        barSlot.addView(goingBar(act, o, onRetry = {
            Thread { val ok = MyWall.publish(pid); MainThread.post { if (ok && !closed) leave() } }.start()
        }, onDiscard = null), FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(c.dp(16), c.dp(6), c.dp(16), c.dp(6)) })
    }
    fun post() {
        if (sending != null || empty() || 0 < taking) return
        failedLine.visibility = View.GONE
        sending = ""   // the check stands down at once; the bar comes with the post
        drawTaken()
        val words = field.text.toString()
        val atts = files.toList()
        Thread {
            val pid = MyWall.begin(words, atts, wall)
            if (pid == null) { MainThread.post { sending = null; failedLine.visibility = View.VISIBLE; drawTaken() }; return@Thread }
            MyWall.dropDraft(wall, "posted")   // its files moved into the post's store
            MainThread.post { sending = pid; files.clear(); drawTaken(); showBar() }
            val ok = MyWall.publish(pid)
            MainThread.post { if (ok && !closed) leave() }
        }.start()
    }
    done = c.icon(R.drawable.ic_check, Color.WHITE).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = c.getString(R.string.wall_publish)
        pressable { post() }
    }
    field.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { dress(); lay(soon = true) }
    })
    fun pickRow(glyph: Int, words: Int, deed: () -> Unit) = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
        addView(c.icon(glyph, Color.WHITE, 22), lp(dp(22), dp(22)))
        gap(14)
        addView(c.text(c.getString(words), 17f).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))
        pressable(deed)
    }
    picks.addView(pickRow(R.drawable.ic_photo, R.string.wall_photos_videos) {
        val room = MEDIA_LIMIT - files.size - taking
        if (0 < room) act.pickVisualMany(room) { take(it.take(room)) }
    }, lp())
    picks.addView(pickRow(R.drawable.ic_queue_music, R.string.wall_tracks_files) {
        val room = MEDIA_LIMIT - files.size - taking
        if (0 < room) act.openDocuments { take(it.take(room)) }
    }, lp())
    val page = settingsPage(act, c.getString(R.string.new_post), { leave() }, cross = true, trailing = done) {
        addView(barSlot, lp())
        // THE NEW POST'S PAGE IS THE POST ONE TO ONE (iOS MTBoardComposer's MTBoardCell with its draft, 0ba63ede, 24.09): my face,
        // my name and the moment it was begun as the wall will draw them, the words a field where the post's words stand, at the
        // wall's own width; a touch anywhere on the post gives the field the keys again (24.09)
        addView(postCell(act, Board.Item(wall, draftPost(wall, at, "")), whole = true, onItsWall = true, sending = true, field = field, tiles = tiles, nought = true).apply {
            setOnClickListener { if (sending == null && !field.hasFocus()) { field.requestFocus(); c.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0) } }
        }, lp().apply { topMargin = dp(12) })
        addView(picks, lp().apply { topMargin = dp(16) })
        addView(taken, lp().apply { topMargin = dp(16) })
        addView(failedLine, lp().apply { topMargin = dp(16) })
    }
    drawTaken()
    // a draft from before the frames: its pictures take their own, off the main thread
    files.filter { f -> f.frame == null && (f.kind == "img" || f.kind == "vid") }.takeIf { bare -> bare.isNotEmpty() }?.let { bare ->
        Thread { for (f in bare) WallFiles.dress(c, f); MainThread.post { if (!closed) { drawTaken(); lay() } } }.start()
    }
    // WHAT ANOTHER APP HANDED IN STANDS ON THE PAGE ALREADY TAKEN (iOS MTBoard.laySheet 1974-1991: the words joined, the files held to
    // what a post takes, then MTBoardComposer.reopen 2054-2058): the words to change, more files to pick, the check to post
    // joined to the draft the wall already holds (iOS MTBoard.laySheet: «joins the words and the files to the draft my wall already holds»)
    if (sharedWords.isNotEmpty()) { field.setText(listOf(field.text.toString(), sharedWords).filter { it.isNotBlank() }.joinToString("\n")); field.setSelection(field.text.length) }
    take(sharedFiles.take(MEDIA_LIMIT - files.size))
    // the bar follows the node's count while the post is on its way
    val heard: () -> Unit = { if (sending != null) showBar() }
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { MyWall.listen(heard) }
        override fun onViewDetachedFromWindow(v: View) { MyWall.unlisten(heard) }
    })
    field.post {
        field.requestFocus()
        field.setSelection(field.text.length)   // a draft opens with the cursor after its last word
        c.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    return page
}

private const val MEDIA_LIMIT = 10   // iOS MTPostMeasure.files
