package quest.montana.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The platform's semibold where it has weights (API 28), its medium below. */
fun semibold(): Typeface =
    if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.create("sans-serif-medium", Typeface.NORMAL)

/**
 * A RUNNING LINE (iOS MTMarqueeView, MontanaMusicPlayer.swift:1563-1640; the author's word 21.09): a title longer than its room
 * scrolls past it ONCE -- one pass at a reading pace, 30 points a second, after 1.2 s -- then stands at its start and rests a
 * minute before the next pass; a title that fits never moves (in the middle of its room when `centred`). Semibold, as iOS's
 * font(style) gives it. `runs = false` (a still row of the music list): the name stands, cut at its end. Nothing ticks while it
 * stands: the rest is one delayed post, not a running clock.
 */
class Marquee(ctx: Context, sp: Float, private val centred: Boolean) : View(ctx) {
    private companion object { const val GAP = 48; const val PACE = 30f; const val WAIT = 1200L; const val REST = 60_000L }
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = semibold()
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics)
    }
    private val density = ctx.resources.displayMetrics.density
    private var w = 0f
    private var shift = 0f
    private var pass: ValueAnimator? = null
    private val begin = Runnable { go() }
    var runs = true
        set(v) { if (v != field) { field = v; lay() } }
    var text: String = ""
        set(v) { if (v != field) { field = v; w = paint.measureText(v); contentDescription = v; lay() } }
    var color: Int
        get() = paint.color
        set(v) { if (v != paint.color) { paint.color = v; invalidate() } }

    override fun onMeasure(ws: Int, hs: Int) {
        val fm = paint.fontMetrics
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, ws), resolveSize(ceil(fm.descent - fm.ascent).toInt(), hs))
    }
    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) { if (nw != ow) lay() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); lay() }
    override fun onDetachedFromWindow() { halt(); super.onDetachedFromWindow() }

    private fun long() = runs && width > 1 && width + 1 < w
    private fun halt() { removeCallbacks(begin); pass?.cancel(); pass = null; shift = 0f }
    /** The line laid again (a new name, a new room): back to its start, the first pass after the wait. */
    private fun lay() {
        halt()
        if (isAttachedToWindow && long()) postDelayed(begin, WAIT)
        invalidate()
    }
    private fun go() {
        if (!long()) return
        val travel = w + GAP * density
        pass = ValueAnimator.ofFloat(0f, -travel).apply {
            duration = (travel / (PACE * density) * 1000).toLong(); interpolator = LinearInterpolator()
            addUpdateListener { shift = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (pass !== a) return
                    pass = null; shift = 0f; invalidate()
                    postDelayed(begin, REST + WAIT)   // a minute's rest at the start, then the wait, then the next pass
                }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val fm = paint.fontMetrics
        val y = height / 2f - (fm.ascent + fm.descent) / 2f
        if (long()) {
            c.save(); c.clipRect(0, 0, width, height)
            c.drawText(text, shift, y, paint)
            c.drawText(text, shift + w + GAP * density, y, paint)
            c.restore()
            return
        }
        val shown = if (w <= width) text else TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()
        val sw = if (shown === text) w else paint.measureText(shown)
        c.drawText(shown, if (centred) maxOf(0f, (width - sw) / 2f) else 0f, y, paint)
    }
}

/**
 * THE SOUND'S LINE (iOS MTVolumeLineView, MontanaMusicPlayer.swift:763-814; the author's word 16.10.2026 «a thin horizontal
 * floating volume scale with a tall tap area»): the system's music volume drawn as a thin line, three points high and round at
 * its ends -- the filled part white, the rest its faint shade (22 %) -- with NO thumb: the line itself is the control. The whole
 * height (44) takes the finger: a touch sets the volume where it lands, a drag carries it; the page's swipe gives way while it
 * is held. Set without the system's volume panel, so nothing covers the player.
 */
class VolumeLine(ctx: Context) : View(ctx) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private val thick = 3 * ctx.resources.displayMetrics.density
    private val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(56, 255, 255, 255) }
    private val full = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val box = android.graphics.RectF()
    private var f = 0f
    var held = false; private set

    /** The system's volume read again (the hardware buttons move it), unless the finger holds the line. */
    fun follow() {
        if (held) return
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        if (now != f) { f = now; invalidate() }
    }

    private fun set(x: Float) {
        f = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val v = (f * max).roundToInt()
        if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        invalidate()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { held = true; parent.requestDisallowInterceptTouchEvent(true); set(e.x) }
            MotionEvent.ACTION_MOVE -> set(e.x)
            MotionEvent.ACTION_UP -> { set(e.x); held = false; android.util.Log.d("Montana", "music volume set") }
            MotionEvent.ACTION_CANCEL -> held = false
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        box.set(0f, y - thick / 2, width.toFloat(), y + thick / 2)
        c.drawRoundRect(box, thick / 2, thick / 2, rest)
        if (f > 0f) { box.right = maxOf(thick, width * f); c.drawRoundRect(box, thick / 2, thick / 2, full) }
    }
}
