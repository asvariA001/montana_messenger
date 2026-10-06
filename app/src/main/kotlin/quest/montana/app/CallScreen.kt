package quest.montana.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Locale

/**
 * THE CALL'S OWN SCREEN (iOS CallOverlayView.fullScreen, the voice branch, MontanaCall.swift 6561-6860): over every page while
 * the call is in hand — the ground of the peer's face (MTCallGround 6406), the fold-away mark, the system's line «Montana Audio
 * Call — 0:03» (or «— Connecting…»), the name at forty, and the system grid of two rows of three: Speaker · Video · Mute over
 * Hold · End · Broadcast. Video, hold and the broadcast are later steps of Anna's plan: their places stand empty, so every
 * button keeps the place iOS gives it. The fold-away mark lets the screen go; the call stays on the system's notification, and
 * its press brings the screen back.
 */
object CallScreen {
    private const val SIDE = 76   // iOS grid(): the circle of a column in portrait
    private const val COL = 100
    private const val GAP = 24
    private val barGlyph = Color.rgb(204, 204, 204)   // iOS MontanaOctagon.barGlyph
    private var close: (() -> Unit)? = null

    fun show(act: MainActivity, ref: String) {
        if (close != null) return
        close = act.overlay(page(act, ref))
    }

    fun hide() = MainThread.post { val c = close; close = null; c?.invoke() }

    /** The page is laid edge to edge; the system bars' room the overlay gives it goes to the column, not to the ground. */
    private class Page(c: Context) : FrameLayout(c) {
        var inner: View? = null
        override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
            val i = inner
            if (i != null) i.setPadding(left, top, right, bottom) else super.setPadding(left, top, right, bottom)
        }
    }

    private fun page(act: MainActivity, ref: String): View {
        val c: Context = act
        val name = Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
        val status = c.text("", 20f, Color.argb(153, 255, 255, 255), center = true).apply { singleLineEllipsis(); fontFeatureSettings = "tnum" }
        fun line() {
            val since = CallLine.connectedAt
            val tail = if (since > 0L) ((System.currentTimeMillis() - since) / 1000).toInt().let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
                else c.getString(R.string.call_connecting)
            status.text = c.getString(R.string.call_title_audio) + " — " + tail
        }
        line()
        MainThread.later(1000, object : Runnable {
            override fun run() { if (close == null) return; line(); MainThread.later(1000, this) }
        })
        val column = c.vstack(Gravity.CENTER_HORIZONTAL) {
            // the fold-away mark, the chat's back mark one to one (iOS MontanaCallMark chevron.down)
            addView(FrameLayout(c).apply {
                addView(FrameLayout(c).apply {
                    background = c.glassPlate(oval = true)
                    contentDescription = c.getString(R.string.call_back)
                    addView(c.icon(R.drawable.ic_chevron_down, Color.WHITE, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                    pressable { hide() }
                }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START))
            }, lp().apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(8) })
            gap(20)
            addView(status, lp().apply { leftMargin = dp(16); rightMargin = dp(16) })
            gap(10)
            addView(c.text(name, 40f, Color.WHITE, bold = true, center = true).apply {   // USER-DATA: the peer's name
                singleLineEllipsis()
                setAutoSizeTextTypeUniformWithConfiguration(24, 40, 1, TypedValue.COMPLEX_UNIT_SP)
            }, lp(MATCH, dp(52)).apply { leftMargin = dp(16); rightMargin = dp(16) })
            addView(View(c), lp(MATCH, 0, 1f))
            val speaker = button(c, R.string.call_speaker, R.drawable.ic_set_speaker, null, { CallLine.speaker }) { CallLine.setSpeaker(!CallLine.speaker) }
            // the microphone off as the system's own call shows it: the lit plate, the crossed microphone in red (iOS 6801)
            val mute = button(c, R.string.call_mute, R.drawable.ic_mic_slash, null, { CallLine.muted }, SysColor.red) { CallLine.setMuted(!CallLine.muted) }
            val end = button(c, R.string.call_end, R.drawable.ic_phone_down, SysColor.red, { false }) { Thread { Calls.hangUp("hangup") }.start() }
            addView(c.hstack { addView(speaker, lp(dp(COL), WRAP)); gap(GAP); addView(View(c), lp(dp(COL), 1)); gap(GAP); addView(mute, lp(dp(COL), WRAP)) }, lp(WRAP, WRAP))
            gap(30)
            addView(c.hstack { addView(View(c), lp(dp(COL), 1)); gap(GAP); addView(end, lp(dp(COL), WRAP)); gap(GAP); addView(View(c), lp(dp(COL), 1)) }, lp(WRAP, WRAP))
            gap(46)
        }
        return Page(c).apply {
            isClickable = true   // the screen takes every touch: nothing under it is reached
            addView(ground(c, ref, name), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            inner = column
        }
    }

    /** iOS gridBtn: the circle with its glyph, white when lit, and its one-line caption under it; the press answers under the finger. */
    private fun button(c: Context, caption: Int, glyph: Int, tint: Int?, lit: () -> Boolean, litGlyph: Int = Color.BLACK, work: () -> Unit): View {
        val icon = c.icon(glyph, Color.WHITE, 26)
        val face = FrameLayout(c).apply { addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)) }
        fun paint() {
            val on = lit()
            face.background = when {
                tint != null -> c.rounded(tint, SIDE / 2)
                on -> c.rounded(Color.WHITE, SIDE / 2)
                else -> c.glassPlate(oval = true)
            }
            icon.setColorFilter(if (tint != null) Color.WHITE else if (on) litGlyph else barGlyph)
        }
        paint()
        face.contentDescription = c.getString(caption)
        face.pressable { face.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); work(); paint() }
        return c.vstack(Gravity.CENTER_HORIZONTAL) {
            addView(face, lp(dp(SIDE), dp(SIDE)))
            gap(8)
            addView(c.text(c.getString(caption), 15f, Color.WHITE, center = true).apply { singleLineEllipsis() }, lp())
        }
    }

    /** iOS MTCallGround: the blue-grey wash, the face filling it as a haze, and the face itself at seven tenths, softened. */
    private fun ground(c: Context, ref: String, name: String): View = FrameLayout(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TR_BL, intArrayOf(Color.rgb(33, 38, 48), Color.rgb(41, 51, 71), Color.rgb(26, 28, 33)))
        val face = Book.shownFace(ref).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        val soft = Build.VERSION.SDK_INT >= 31
        val m = c.resources.displayMetrics
        val side = (minOf(m.widthPixels, m.heightPixels) * 0.7f).toInt()
        fun blur(r: Int) = RenderEffect.createBlurEffect(c.dp(r).toFloat(), c.dp(r).toFloat(), Shader.TileMode.CLAMP)
        if (face != null) {
            if (soft) addView(ImageView(c).apply {
                setImageBitmap(face); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.35f; setRenderEffect(blur(48))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(ImageView(c).apply {
                setImageBitmap(face); scaleType = ImageView.ScaleType.FIT_CENTER; alpha = if (soft) 0.5f else 0.3f
                if (soft) setRenderEffect(blur(8))
            }, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        } else {
            addView(c.text(name.take(1).uppercase(), 160f, Color.argb(56, 255, 255, 255), bold = true, center = true),   // USER-DATA: the name's first letter
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
    }
}
