package quest.montana.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────── the pictures of a conversation, full screen (iOS PhotoPresenter: the platform's viewer) ───────────────────────────

/**
 * EVERY PICTURE IS SEEN ON ITS OWN (iOS PhotoPresenter + MontanaPhotoViewer after ca301892; the author's word 20455 p.4, 12.11.2026:
 * «no Montana gallery while viewing a photo anywhere again»): a tap on a picture -- a chat's, a post's, a correspondent's page --
 * opens that one picture: pinch and a double tap enlarge, a pull down lets it go and closes, a tap hides or shows Share and Save at
 * the bottom. No strip of covers. The moments' gallery (titles: iOS MTMomentsViewer over the platform's viewer, untouched by
 * ca301892) still pages across its moments with its bar -- close, the moment's conversation, share -- and no strip either.
 */
fun openPictures(act: MainActivity, start: File, among: List<File>? = null, films: Set<String> = emptySet(), titles: List<String>? = null) {
    val c: Context = act
    // THE PICTURES IT STANDS AMONG, PAGED BY THE FINGER (iOS MTPhotoLook.pages, 11c34ed7 at 2389; the author's word 21257 p.2,
    // 13.11.2026: «when viewing a photo let it be leafed right and left inside the chat, the group, the channel and so on»): the
    // files of the same chat or post, oldest first; still no strip of covers -- each page is one picture with its own Save and Share
    val files = (if (titles != null) among?.takeIf { l -> l.size == titles.size && l.any { f -> f.path == start.path } }
        else among?.takeIf { l -> 1 < l.size && l.any { f -> f.path == start.path } }) ?: listOf(start)
    val paged = 1 < files.size
    var at = files.indexOfFirst { it.path == start.path }.coerceAtLeast(0)
    lateinit var close: () -> Unit
    var chrome = true
    // THE MOMENTS' BAR (iOS DocPreviewQL in its navigation controller, MontanaFeeds.swift:2702-2716 at 2155, the gallery's viewer
    // MTMomentsViewer 2758): the close on the left, the moment's conversation in the middle, the share on the right
    val head = titles?.takeIf { paged }
    val name = head?.let { c.text(it[at], 17f, Color.WHITE, center = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END } }   // USER-DATA: the conversation's name
    var turned: () -> Unit = {}
    val pager = PicturePager(act, files, at, films) { at = it; if (head != null) name?.text = head[it]; turned() }
    fun mark(glyph: Int, label: Int, work: () -> Unit) = FrameLayout(c).apply {
        background = c.glassPlate(oval = true)
        contentDescription = c.getString(label)
        addView(c.icon(glyph, Color.WHITE, 22), FrameLayout.LayoutParams(c.dp(22), c.dp(22), Gravity.CENTER))
        pressable(work)
    }
    // the file through the app's door to the system's share (iOS MTShare.present / QLPreviewController's share item)
    fun share() {
        val f = files[at]
        val uri = Media.uri(act, f)
        val send = Intent(Intent.ACTION_SEND).setType(act.contentResolver.getType(uri) ?: if (f.path in films) "video/*" else "image/*")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { act.startActivity(Intent.createChooser(send, null)) }
    }
    var bar: View? = null
    if (name != null) {
        bar = FrameLayout(c).apply {
            addView(mark(R.drawable.ic_close, R.string.film_close) { close() }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.START).apply { leftMargin = c.dp(16) })
            addView(name, FrameLayout.LayoutParams(MATCH, c.dp(44), Gravity.CENTER).apply { setMargins(c.dp(72), 0, c.dp(72), 0) })
            name.gravity = Gravity.CENTER
            addView(mark(R.drawable.ic_share, R.string.share) { share() }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.END).apply { rightMargin = c.dp(16) })
        }
    }
    // SAVE AND SHARE UNDER EVERY PICTURE (iOS MontanaPhotoViewer onShare/onSave, ca301892; every page since 11c34ed7 at 2389): Share
    // at the left, Save at the right, for the page on screen; Save lays the file into the library by the one road (SavedMedia, iOS
    // MontanaSavedToPhotos) and turns to the checkmark, done; each page saves on its own. The moments' gallery shares from its bar.
    val foot: FrameLayout? = if (head != null) null else FrameLayout(c).apply {
        addView(mark(R.drawable.ic_share, R.string.share) { share() }, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.START).apply { leftMargin = c.dp(24) })
        var saved = SavedMedia.has(files[at].name)
        lateinit var save: FrameLayout
        fun dress() {
            (save.getChildAt(0) as? android.widget.ImageView)?.setImageResource(if (saved) R.drawable.ic_check else R.drawable.ic_save_media)
            save.contentDescription = c.getString(if (saved) R.string.saved_photo else R.string.save_photo)
            save.isEnabled = !saved
        }
        save = mark(R.drawable.ic_save_media, R.string.save_photo) {
            if (saved || !save.isEnabled) return@mark
            save.isEnabled = false
            val f = files[at]
            Thread {
                val ok = SavedMedia.save(c, f, false)
                android.util.Log.i("Montana", "save_photos ok=" + (if (ok) 1 else 0) + " video=0")
                MainThread.post { if (files[at] == f) saved = ok; save.isEnabled = true; dress() }
            }.start()
        }
        addView(save, FrameLayout.LayoutParams(c.dp(44), c.dp(44), Gravity.END).apply { rightMargin = c.dp(24) })
        dress()
        turned = { saved = SavedMedia.has(files[at].name); dress() }
    }
    fun lay() {
        for (v in listOfNotNull(bar, foot)) {
            val shown = chrome && !pager.zoomed
            v.animate().alpha(if (shown) 1f else 0f).setDuration(220).withEndAction { v.visibility = if (chrome && !pager.zoomed) View.VISIBLE else View.INVISIBLE }.start()
            if (shown) v.visibility = View.VISIBLE
        }
    }
    pager.onTapAt = { x ->
        if (paged && !pager.zoomed && x < 0.15f) pager.turn(-1)
        else if (paged && !pager.zoomed && 0.85f < x) pager.turn(1)
        // A FILM AMONG THE MOMENTS PLAYS IN THE SYSTEM'S PLAYER (iOS MTMomentsViewer over Quick Look, MontanaFeeds.swift:2705-2772
        // at 2155: the swipe between moments, the system's player for a video): its page is its first frame and the play mark
        else if (files[at].path in films) FilmActivity.open(act, files[at])
        else { chrome = !chrome; lay() }
    }
    pager.onPull = { f -> foot?.alpha = if (chrome && f < 0.01f) 1f else 0f }   // steps aside the moment the hands work
    pager.onZoomed = { lay() }
    pager.onGone = { close() }
    val page = FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))
        foot?.let { addView(it, FrameLayout.LayoutParams(MATCH, c.dp(44), Gravity.BOTTOM)) }
        bar?.let { addView(it, FrameLayout.LayoutParams(MATCH, c.dp(44), Gravity.TOP)) }
        setOnApplyWindowInsetsListener { _, ins ->
            val sys = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            (foot?.layoutParams as? FrameLayout.LayoutParams)?.let { it.bottomMargin = max(sys.bottom, c.dp(16)) + c.dp(8); foot.layoutParams = it }
            (bar?.layoutParams as? FrameLayout.LayoutParams)?.let { it.topMargin = sys.top + c.dp(8); bar?.layoutParams = it }
            ins
        }
    }
    pager.ground = page
    close = act.overlay(page)
    page.requestApplyInsets()
}

/**
 * ONE PICTURE THAT ENLARGES (iOS MTZoomImage, the system's zooming scroll view): fitted at rest; pinch between 1× and 4×;
 * a double tap goes to 2.5× at the finger and back; while enlarged a drag moves it within its edges.
 */
class ZoomPicture(c: Context) : ImageView(c) {
    /** A film's page: its first frame with the platform's play mark over it, and no enlarging. */
    var film = false
        set(v) { field = v; invalidate() }
    private val playMark = c.getDrawable(R.drawable.ic_play_circle_fill)
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val m = playMark ?: return
        if (!film) return
        val k = (64 * resources.displayMetrics.density).toInt()
        m.setBounds((width - k) / 2, (height - k) / 2, (width + k) / 2, (height + k) / 2)
        m.draw(canvas)
    }
    private val base = Matrix()
    private val m = Matrix()
    var scale = 1f; private set
    val zoomed get() = scale > 1.01f

    init { scaleType = ScaleType.MATRIX }

    fun show(b: Bitmap?) { setImageBitmap(b); post { fit() } }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        base.setRectToRect(RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        m.reset(); scale = 1f; apply()
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun apply() { imageMatrix = Matrix(base).apply { postConcat(m) } }

    /** Keep the enlarged picture within the screen: no black gap at an edge it can cover. */
    private fun bound() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        Matrix(base).apply { postConcat(m) }.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = width / 2f - r.centerX() else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = height / 2f - r.centerY() else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        m.postTranslate(dx, dy)
    }

    fun zoomBy(f: Float, px: Float, py: Float) {
        val to = (scale * f).coerceIn(1f, 4f)
        m.postScale(to / scale, to / scale, px, py); scale = to
        if (!zoomed) { m.reset(); scale = 1f }
        bound(); apply()
    }
    fun panBy(dx: Float, dy: Float) { m.postTranslate(dx, dy); bound(); apply() }

    /** Returns how far the picture could still move sideways before its edge (to let the page turn at the edge). */
    fun atEdge(dx: Float): Boolean {
        val d = drawable ?: return true
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        Matrix(base).apply { postConcat(m) }.mapRect(r)
        return if (dx > 0) r.left >= -1f else r.right <= width + 1f
    }

    fun toggleAt(px: Float, py: Float) {
        val from = scale; val to = if (zoomed) 1f else 2.5f
        ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            addUpdateListener { a -> val v = a.animatedValue as Float; zoomBy(v / scale, px, py) }
            start()
        }
    }
}

/**
 * THE PAGES (iOS the platform viewer's paging): three pictures stand side by side — the one on the screen and its neighbours;
 * a sideways stroke moves them together and settles on the next page past a third of the width or on a fling. A stroke down
 * on a picture at rest pulls it away: the ground fades, and past a quarter of the height the viewer closes.
 */
class PicturePager(private val act: MainActivity, private val files: List<File>, start: Int, private val films: Set<String> = emptySet(),
                   private val onPage: (Int) -> Unit) : FrameLayout(act) {
    var onTap: () -> Unit = {}
    var onTapAt: (Float) -> Unit = {}   // where across the page the tap landed, 0…1 (iOS MTAlbumPage onTap)
    var onZoomed: (Boolean) -> Unit = {}
    var onPull: (Float) -> Unit = {}
    var onGone: () -> Unit = {}
    var ground: View? = null
    private var at = start
    private val views = List(3) { ZoomPicture(act) }   // left, centre, right
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private var axis = 0   // 0 undecided, 1 sideways, 2 pull
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var tracker: VelocityTracker? = null
    private var scaling = false
    private val cache = HashMap<Int, Bitmap?>()

    private val scaler = ScaleGestureDetector(act, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { views[1].zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
    })
    private val taps = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTapAt(e.x / max(1, width)); onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (views[1].film) return false   // a film's page does not enlarge: its frame is a door to the player
            views[1].toggleAt(e.x, e.y); postDelayed({ onZoomed(views[1].zoomed) }, 260); return true
        }
    })

    init {
        views.forEach { addView(it, LayoutParams(MATCH, MATCH)) }
        load()
    }

    private var turning = false
    val zoomed get() = views[1].zoomed
    /** An edge tap turns the page the way a swipe would (iOS MontanaPhotoViewer.turn); one turn at a time. */
    fun turn(d: Int) { if (!turning && at + d in files.indices) settle(d) }
    /** The strip chose a page: a neighbour slides in, a far one stands at once. */
    fun goTo(i: Int) {
        if (turning || i == at || i !in files.indices) return
        if (abs(i - at) == 1) settle(i - at) else { at = i; load() }
    }

    private fun bitmap(i: Int, done: (Bitmap?) -> Unit) {
        if (i !in files.indices) { done(null); return }
        if (cache.containsKey(i)) { done(cache[i]); return }
        act.background {
            val b = Media.preview(act, files[i], if (files[i].path in films) "vid" else "img", 2400)
            act.onMain { cache[i] = b; if (cache.size > 7) cache.keys.filter { abs(it - at) > 2 }.forEach { cache.remove(it) }; done(b) }
        }
    }

    private fun load() {
        for (k in 0..2) {
            val i = at - 1 + k
            val v = views[k]
            v.show(null); v.visibility = if (i in files.indices) VISIBLE else INVISIBLE
            v.film = i in files.indices && files[i].path in films
            bitmap(i) { b -> if (at - 1 + k == i) v.show(b) }
        }
        place(0f)
        onPage(at)
    }

    private fun place(dx: Float) {
        views[0].translationX = dx - width
        views[1].translationX = dx
        views[2].translationX = dx + width
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); place(views[1].translationX) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; axis = 0; scaling = false }
            MotionEvent.ACTION_MOVE -> {
                if (scaling || e.pointerCount > 1) { lastX = e.x; lastY = e.y; return true }
                val dx = e.x - lastX; val dy = e.y - lastY
                val centre = views[1]
                if (axis == 0 && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    axis = when {
                        centre.zoomed -> if (centre.atEdge(e.x - downX) && abs(e.x - downX) > abs(e.y - downY) && files.size > 1) 1 else 3
                        abs(e.x - downX) > abs(e.y - downY) -> if (files.size > 1) 1 else 0
                        e.y > downY -> 2
                        else -> 0
                    }
                }
                when (axis) {
                    1 -> place(views[1].translationX + dx)
                    2 -> {
                        val f = ((e.y - downY) / height).coerceAtLeast(0f)
                        centre.translationY = e.y - downY
                        ground?.setBackgroundColor(Color.argb((255 * (1 - min(0.75f, f * 2))).toInt(), 0, 0, 0))
                        onPull(f)
                    }
                    3 -> centre.panBy(dx, dy)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f; val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                when (axis) {
                    1 -> {
                        val x = views[1].translationX
                        val go = when {
                            x < -width / 3f || vx < -act.dp(600) -> 1
                            x > width / 3f || vx > act.dp(600) -> -1
                            else -> 0
                        }.let { if (at + it in files.indices) it else 0 }
                        settle(go)
                    }
                    2 -> {
                        val c = views[1]
                        if (c.translationY > height / 4f || vy > act.dp(900)) {
                            c.animate().translationY(height.toFloat()).alpha(0f).setDuration(180).withEndAction { onGone() }.start()
                        } else {
                            c.animate().translationY(0f).setDuration(200).start()
                            ground?.setBackgroundColor(Color.BLACK); onPull(0f)
                        }
                    }
                }
                axis = 0
                onZoomed(views[1].zoomed)
            }
        }
        return true
    }

    private fun settle(go: Int) {
        turning = true
        val from = views[1].translationX
        val to = -go * width.toFloat()
        ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            addUpdateListener { place(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { turning = false; if (go != 0) { at += go; load() } }
            })
            start()
        }
    }
}
