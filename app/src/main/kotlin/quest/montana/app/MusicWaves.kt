package quest.montana.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * THE WAVES' CLOCK (iOS MTCoverPulse, MontanaMusicPlayer.swift:1102-1138 at 2311): the level smoothed -- quick to rise, slow to
 * fall -- and a running average under it. A WAVE PARTS ON A BEAT ONLY (the author's word 01.10 00:46: «the pulse exactly in
 * rhythm with the music»): the moment the heard level crosses over its running average by a beat's margin, on the rising edge,
 * never twice within a quarter of a second; only a long quiet of beats (two seconds) lets one go, so a soft track still breathes.
 * Sixteen places held for the page's life: a frame writes into them and allocates nothing.
 */
class CoverPulse {
    class Wave { var born = -100.0; var strength = 0.0 }
    companion object { const val SLOTS = 16; const val LIFE = 4.0 }   // slow, to the screen's edges
    val waves = Array(SLOTS) { Wave() }
    private var next = 0
    private var level = 0.0
    private var floor = 0.0
    private var last = 0.0
    private var lastWave = -100.0
    private var over = false   // the heard level stands over the beat's margin: its rising edge is the beat

    /** One frame: the level taken in, a wave parted on a beat; the icon's breath returned. */
    fun step(now: Double, heard: Float): Float {
        val dt = min(0.1, max(0.0, now - last))
        last = now
        val x = heard.toDouble()
        level += (x - level) * (1 - exp(-dt / (if (level < x) 0.03 else 0.25)))
        floor += (x - floor) * (1 - exp(-dt / 1.2))
        val above = max(0.05, floor * 0.3) < x - floor
        val beat = above && !over && 0.25 < now - lastWave
        over = above
        if (0.04 < level && (beat || 2 < now - lastWave)) {
            lastWave = now
            waves[next].born = now; waves[next].strength = max(0.25, min(1.0, level * 1.3))
            next = (next + 1) % SLOTS
        }
        return (1 + 0.02 * level + 0.1 * max(0.0, level - floor)).toFloat()
    }
}

/**
 * THE ICON'S WAVES (iOS MTCoverWaves, MontanaMusicPlayer.swift:1035-1100 at 2311; the author's words 30.09 ~01:45 «our icon sends
 * out waves to the music's rhythm, smoothly -- glass waves, colourless, like living glass» and 01.10 00:46 «to the screen's end in
 * all directions, slowly and beautifully, in rhythm»): out of the app's icon on the cover's stage, in its own shape, rings part and
 * spread past the screen's farthest corner, rounding into circles at once -- colourless, a light rim as glass has. The music's
 * level (MusicPlayer.level) sets how often a wave parts, how strong it stands and the icon's breath. ONE LAYER FOR ALL THE RINGS:
 * the window's own overlay, drawn over whatever stands there and taking no touch. At rest no wave parts, the ones already out run
 * their course and the frames stop. Animations off in the system (iOS Reduce Motion): the icon stands still, nothing spreads,
 * nothing is measured.
 */
class CoverWaves(private val icon: View) : Drawable(), Choreographer.FrameCallback {
    private val pulse = CoverPulse()
    private val rim = LinearGradient(0f, 0f, 1f, 1f, android.graphics.Color.argb(191, 255, 255, 255), android.graphics.Color.argb(46, 255, 255, 255), Shader.TileMode.CLAMP)
    private val place = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; shader = rim }
    private val box = RectF()
    private val iconBox = RectF()
    private val hole = android.graphics.Path()
    private val at = IntArray(2)
    private val density = icon.resources.displayMetrics.density
    private var host: View? = null
    private var ticking = false
    private var restSince = -1.0

    /** The stage shows the icon: the layer laid over the window, the meter asked, the frames begun. */
    fun start() {
        if (host != null || !ValueAnimator.areAnimatorsEnabled()) return
        val root = icon.rootView ?: return
        host = root
        setBounds(0, 0, root.width, root.height)
        root.overlay.add(this)
        MusicPlayer.metering = true
        android.util.Log.d("Montana", "cover_waves on")
        wake()
    }

    /** The stage folds, a cover of its own comes, the player leaves: the layer goes, the meter stops, the icon stands still. */
    fun stop() {
        val root = host ?: return
        host = null
        root.overlay.remove(this)
        MusicPlayer.metering = false
        if (ticking) Choreographer.getInstance().removeFrameCallback(this)
        ticking = false
        icon.scaleX = 1f; icon.scaleY = 1f
        android.util.Log.d("Montana", "cover_waves off")
    }

    /** The player's word: play went on, the frames run again. */
    fun wake() {
        if (host == null || ticking) return
        ticking = true; restSince = -1.0
        Choreographer.getInstance().postFrameCallback(this)
    }

    private var saidAt = 0.0
    override fun doFrame(ns: Long) {
        val root = host ?: run { ticking = false; return }
        if (bounds.width() != root.width || bounds.height() != root.height) setBounds(0, 0, root.width, root.height)   // a turn
        val now = ns / 1e9
        val heard = MusicPlayer.level()
        val breath = pulse.step(now, heard)
        // the heard level in the diary every two seconds while the waves run: a silent track parts no wave, and only this tells why
        if (now - saidAt > 2) { saidAt = now; android.util.Log.d("Montana", "cover_waves heard=" + "%.2f".format(heard)) }
        icon.scaleX = breath; icon.scaleY = breath
        invalidateSelf()
        // a wave's life after the music rests (iOS quiet after life × 1.2): then the frames stop
        if (MusicPlayer.playing) restSince = -1.0 else if (restSince < 0) restSince = now
        if (restSince >= 0 && now - restSince > CoverPulse.LIFE * 1.2) { ticking = false; return }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun draw(c: Canvas) {
        val root = host ?: return
        icon.getLocationInWindow(at)
        val cx = at[0] + icon.width * icon.scaleX / 2f
        val cy = at[1] + icon.height * icon.scaleY / 2f
        val side = icon.width.toFloat()
        val reach = hypot(root.width.toFloat(), root.height.toFloat())
        val now = System.nanoTime() / 1e9
        // THE RINGS PART FROM UNDER THE ICON (iOS ZStack: the rings, then the icon over them): the icon's own shape is cut out of
        // the window's layer, so no ring is drawn across the icon
        val iw = icon.width * icon.scaleX; val ih = icon.height * icon.scaleY
        iconBox.set(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2)
        hole.reset(); hole.addRoundRect(iconBox, iw * TrackArt.ICON_CORNER, iw * TrackArt.ICON_CORNER, android.graphics.Path.Direction.CW)
        c.save(); c.clipOutPath(hole)
        for (w in pulse.waves) {
            val t = (now - w.born) / CoverPulse.LIFE
            if (t < 0 || t >= 1) continue
            val e = (1 - (1 - t).pow(1.6)).toFloat()   // an even, slow spread
            val size = side + (reach * 2 - side) * e
            val round = TrackArt.ICON_CORNER + (0.5f - TrackArt.ICON_CORNER) * min(1f, e * 6)   // a circle almost at once
            box.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            place.setScale(size, size); place.postTranslate(box.left, box.top)
            rim.setLocalMatrix(place)
            paint.strokeWidth = ((2.5 + 9 * w.strength) * (1 - 0.55 * e)).toFloat() * density
            paint.alpha = (255 * (0.35 + 0.65 * w.strength) * (1 - t).pow(1.6) * 0.5).toInt().coerceIn(0, 255)
            c.drawRoundRect(box, size * round, size * round, paint)
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
