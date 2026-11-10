package quest.montana.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * A TRACK'S SMALL COVER (iOS MTMusicCover side 44, corner 8): the file's own picture, else its folder's, else the app's icon —
 * read off the main thread once and kept drawn down to the row's side.
 */
object TrackThumb {
    private const val SIDE = 132
    private val kept = LruCache<String, Bitmap>(256)
    private val none = java.util.Collections.synchronizedSet(HashSet<String>())

    fun into(v: ImageView, t: Track) {
        val key = t.uri.toString()
        v.tag = key
        kept.get(key)?.let { v.setImageBitmap(it); return }
        v.setImageBitmap(TrackArt.icon(v.context))
        if (key in none) return
        val a = v.context.applicationContext
        Thread {
            val own = runCatching { MediaMetadataRetriever().run { try { setDataSource(a, t.uri); embeddedPicture } finally { release() } } }.getOrNull()
            val bytes = own ?: t.art?.let { u -> runCatching { a.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() }
            val img = bytes?.let { decode(it) }
            if (img == null) { none.add(key); return@Thread }
            kept.put(key, img)
            MainThread.post { if (v.tag == key) v.setImageBitmap(img) }
        }.start()
    }

    private fun decode(d: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(d, 0, d.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var k = 1
        while (minOf(o.outWidth, o.outHeight) / (k * 2) >= SIDE) k *= 2
        BitmapFactory.decodeByteArray(d, 0, d.size, BitmapFactory.Options().apply { inSampleSize = k })
    }.getOrNull()
}

/**
 * THE QUEUE'S OWN PAGE (iOS MTQueuePage, MontanaMusicPlayer.swift:792-903 at 2311): «Now playing», the cross; on the right «Show
 * in playlist», the arrows that turn the order over (the author's word 18.10.2026 10:3x UTC) and the lines that move the tracks by
 * hand. The rows: the cover 44, the track's name over its artist, the place in the queue — the one that plays in blue with the
 * wave. A tap plays the row. In the hand's mode each row has the red minus that takes it out and the handle that drags it.
 */
fun queuePage(act: MainActivity, close: () -> Unit, onPlace: () -> Unit): View {
    val c: Context = act
    var editing = false
    lateinit var body: LinearLayout
    lateinit var fill: () -> Unit
    fun mark(res: Int, label: Int, tap: () -> Unit) = c.icon(res, Color.WHITE).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = c.getString(label); pressable(tap)
    }
    val turn = mark(R.drawable.ic_drag_handle, R.string.queue_reorder) {}
    val marks = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        addView(mark(R.drawable.ic_queue_music, R.string.queue_show) { close(); onPlace() }, lp(dp(44), dp(44)))
        addView(mark(R.drawable.ic_swap_vert, R.string.queue_reverse) { MusicPlayer.reverseQueue() }, lp(dp(44), dp(44)))
        addView(turn, lp(dp(44), dp(44)))
    }
    turn.pressable {
        editing = !editing
        turn.setImageResource(if (editing) R.drawable.ic_check else R.drawable.ic_drag_handle)
        android.util.Log.d("Montana", "music_queue editing=${if (editing) 1 else 0}")
        fill()
    }
    val page = settingsPage(act, c.getString(R.string.queue_title), close, cross = true, trailing = marks) { body = this }

    fun row(i: Int, t: Track, playing: Boolean): View = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(if (editing) 8 else 16), dp(8), dp(14), dp(8))
        if (editing) {
            addView(c.icon(R.drawable.ic_minus_circle, SysColor.red).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4)); contentDescription = c.getString(R.string.delete)
                pressable { MusicPlayer.dropFromQueue(t.file) }
            }, lp(dp(32), dp(32)))
            gap(8)
        }
        addView(ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(30, 255, 255, 255)) }
            TrackThumb.into(this, t)
        }, lp(dp(44), dp(44)))
        gap(12)
        addView(c.vstack(Gravity.NO_GRAVITY) {
            // USER-DATA: the track's own name, its tag's or its file's
            addView(c.text(t.title, 17f, if (playing) MT.blue else Color.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                if (playing) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, lp())
            // USER-DATA: the artist the file's own tags name
            t.artist?.let { by -> addView(c.text(by, 15f, MT.gray).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp().apply { topMargin = dp(2) }) }
        }, lp(0, WRAP, 1f))
        gap(8)
        if (editing) addView(c.icon(R.drawable.ic_drag_handle, MT.gray).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = c.getString(R.string.queue_reorder)
            drag(this, this@hstack, i)
        }, lp(dp(40), dp(40)))
        else if (playing) addView(c.icon(R.drawable.ic_waveform, MT.blue).apply {
            // the wave breathes while the track plays (iOS .symbolEffect(.variableColor.iterative, isActive: !paused))
            if (MusicPlayer.playing) ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.35f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }.also { a -> addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { a.cancel() }   // a row laid again leaves no breath running
            }) }
        }, lp(dp(22), dp(22)))
        // USER-DATA: the track's place in the queue, digits
        else addView(c.text((i + 1).toString(), 13f, MT.gray).apply { fontFeatureSettings = "tnum" }, lp(WRAP, WRAP))
        if (!editing) pressable {
            MusicPlayer.current?.let { cur -> if (cur.file == t.file) { MusicPlayer.toggle(); return@pressable } }
            MusicPlayer.play(c, MusicPlayer.queue, i)
            android.util.Log.d("Montana", "music_queue play i=${i + 1} by=queue-page")
        }
    }

    var placed = false
    fill = {
        body.removeAllViews()
        val q = MusicPlayer.queue
        // outside the hand's mode a swipe to the left takes a row out (iOS List .onDelete)
        val rows = q.mapIndexed { i, t -> row(i, t, i == MusicPlayer.index).let { r -> if (editing) r else SwipeOut(c, r) { MusicPlayer.dropFromQueue(t.file) } } }
        if (q.isNotEmpty()) body.section(c.getString(R.string.queue_from, MusicPlayer.from.ifBlank { c.getString(R.string.cf_all) }), null, *rows.toTypedArray())
        // the page opens on the track that plays, in the middle (iOS proxy.scrollTo(file, anchor: .center))
        if (!placed) rows.getOrNull(MusicPlayer.index)?.let { r ->
            placed = true
            r.post {
                var y = r.top; var v = r.parent as? View
                while (v != null && v !is ScrollView) { y += v.top; v = v.parent as? View }
                (v as? ScrollView)?.scrollTo(0, maxOf(0, y + r.height / 2 - v.height / 2))
            }
        }
    }
    fill()
    android.util.Log.d("Montana", "music_queue page n=${MusicPlayer.queue.size} at=${MusicPlayer.index + 1}")
    // THE PAGE FOLLOWS THE QUEUE (iOS .onChange(of: face.queueIndex)): a turn, a move, a track out, the next track
    var seen = ""
    val heard: () -> Unit = {
        val now = MusicPlayer.queue.joinToString("|") { it.file } + "#" + MusicPlayer.index + "#" + MusicPlayer.playing
        if (now != seen) { seen = now; fill() }
    }
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { MusicPlayer.listeners.add(heard) }
        override fun onViewDetachedFromWindow(v: View) { MusicPlayer.listeners.remove(heard) }
    })
    return page
}

/** The handle drags its row along the list; let go, the row takes the place it stands over (iOS List .onMove). */
private fun drag(handle: View, row: View, i: Int) {
    var y0 = 0f
    handle.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { y0 = e.rawY; row.elevation = row.dp(6).toFloat(); row.parent.requestDisallowInterceptTouchEvent(true); true }
            MotionEvent.ACTION_MOVE -> { row.translationY = e.rawY - y0; true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val steps = (row.translationY / (row.height + 1)).roundToInt()
                row.translationY = 0f; row.elevation = 0f
                val to = (i + steps).coerceIn(0, maxOf(0, MusicPlayer.queue.size - 1))
                if (e.actionMasked == MotionEvent.ACTION_UP && to != i) MusicPlayer.moveInQueue(i, to)
                true
            }
            else -> false
        }
    }
}

/**
 * A ROW THAT A SWIPE TO THE LEFT TAKES OUT (iOS List .onDelete outside the edit mode, MTQueuePage): the row slides over the
 * platform's red with «Delete»; let go past half the row and it is out, past half the red's width and the red stays open --
 * a tap on it takes the row out, a tap on the row closes it; short of that the row slides back.
 */
private class SwipeOut(c: Context, private val row: View, private val out: () -> Unit) : FrameLayout(c) {
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val red = c.text(c.getString(R.string.delete), 17f, Color.WHITE).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, 0, dp(20), 0); setBackgroundColor(SysColor.red)
        visibility = View.INVISIBLE
    }
    private val reach = dp(88)
    private val shown = Rect()
    private var x0 = 0f; private var y0 = 0f; private var base = 0f
    private var dragging = false

    init {
        addView(red, LayoutParams(MATCH, MATCH))
        addView(row, LayoutParams(MATCH, WRAP))
    }

    private fun slide(x: Float) {
        row.translationX = x
        red.visibility = if (x < 0) View.VISIBLE else View.INVISIBLE
        shown.set((width + x).toInt(), 0, width, height); red.clipBounds = shown   // the red only where the row has left
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; base = row.translationX; dragging = false; return base < 0 }   // open: the touch is the red's or the closing's
            MotionEvent.ACTION_MOVE -> if (!dragging && kotlin.math.abs(e.x - x0) > slop && kotlin.math.abs(e.x - x0) > kotlin.math.abs(e.y - y0)) {
                dragging = true; parent.requestDisallowInterceptTouchEvent(true); return true
            }
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(e.x - x0) > slop) { dragging = true; parent.requestDisallowInterceptTouchEvent(true) }
                if (dragging) slide((base + e.x - x0).coerceIn(-width.toFloat(), 0f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val x = row.translationX
                when {
                    !dragging && e.actionMasked == MotionEvent.ACTION_UP -> if (e.x >= width + x) settle(-width.toFloat(), true) else settle(0f, false)
                    x < -width / 2f -> settle(-width.toFloat(), true)
                    x < -reach / 2f -> settle(-reach.toFloat(), false)
                    else -> settle(0f, false)
                }
                dragging = false
            }
        }
        return true
    }

    private fun settle(to: Float, gone: Boolean) {
        ValueAnimator.ofFloat(row.translationX, to).apply {
            duration = 200
            addUpdateListener { slide(it.animatedValue as Float) }
            if (gone) doOnEnd { android.util.Log.d("Montana", "music_queue out by=swipe"); out() }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) = f()
    })
}
