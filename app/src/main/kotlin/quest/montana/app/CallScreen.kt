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
import android.widget.PopupMenu
import android.widget.TextView
import java.util.Locale

/**
 * THE CALL'S OWN SCREEN (iOS CallOverlayView, MontanaCall.swift 6442-6860). In hand (fullScreen, the voice branch): the ground of
 * the peer's face (MTCallGround 6406), the fold-away mark, the system's line — the light, «Montana Audio Call — 0:03» (or the
 * stage) — the name at forty, and the system grid of two rows of three: Speaker/Audio · Video · Mute over Hold · End · Broadcast.
 * Video, hold and the broadcast are later steps of Anna's plan: their places stand empty, so every button keeps the place iOS
 * gives it. Ringing while the app stands in front (incomingScreen 6504): the same face, Decline and Accept. Folded (pillBody
 * 6536): the green pill with the call's clock and the red end over every page; its press brings the screen back.
 */
object CallScreen {
    private const val SIDE = 76   // iOS grid(): the circle of a column in portrait
    private const val COL = 100
    private const val GAP = 24
    private val barGlyph = Color.rgb(204, 204, 204)   // iOS MontanaOctagon.barGlyph
    @Volatile var front: MainActivity? = null   // the activity on the screen (its onResume / onPause): an in-app ring stands there
    private var close: (() -> Unit)? = null
    private var ringing = false   // the page on the screen is the in-app ring, not the call in hand
    private var pill: (() -> Unit)? = null

    /** The call in hand on the screen; an in-app ring standing there gives way to it. */
    fun show(act: MainActivity, ref: String) {
        dropPill()
        if (close != null && !ringing) return
        close?.invoke()
        ringing = false
        close = act.overlay(page(act, ref, ring = false))
    }

    /** A call rings while the app stands in front (iOS incomingScreen: the in-app ring). */
    fun ring(ref: String) = MainThread.post {
        val act = front ?: return@post
        if (close != null) return@post
        ringing = true
        close = act.overlay(page(act, ref, ring = true))
    }

    /** The call ended: the screen and the pill go. */
    fun hide() = MainThread.post { dropPill(); val c = close; close = null; c?.invoke() }

    private fun dropPill() { pill?.invoke(); pill = null }

    private fun page(act: MainActivity, ref: String, ring: Boolean): View {
        val c: Context = act
        val name = Book.chat(ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
        var hold: (() -> Unit)? = null   // the hold button repaints with the tick: the far side's word may change nothing, mine may
        val light = c.icon(R.drawable.ic_dot, SysColor.green, 11)
        val status = c.text("", 20f, Color.argb(153, 255, 255, 255)).apply { singleLineEllipsis(); fontFeatureSettings = "tnum" }
        val route = if (ring) null else routeButton(c)
        // under the name, the call's own state in bold (iOS 6725-6738): the hold, either side's, and the lost link
        val state = c.text("", 17f, Color.WHITE, bold = true, center = true).apply { singleLineEllipsis() }
        fun line() {
            light.setColorFilter(if (CallLine.lost) SysColor.red else SysColor.green)   // iOS MTCallLight: red while the link is lost
            val since = CallLine.connectedAt
            val tail = when {
                ring -> null
                since > 0L -> ((System.currentTimeMillis() - since) / 1000).toInt().let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
                else -> c.getString(Calls.stage() ?: R.string.call_connecting)   // «Calling…», «Ringing…», then «Connecting…»
            }
            status.text = c.getString(R.string.call_title_audio) + (tail?.let { " — " + it } ?: "")
            route?.second?.invoke()
            val word = when {
                ring -> null
                CallLine.held || CallLine.heldByPeer -> R.string.call_on_hold
                CallLine.lost -> R.string.call_lost
                else -> null
            }
            state.visibility = if (word == null) View.GONE else View.VISIBLE
            if (word != null) state.text = c.getString(word)
            hold?.invoke()
        }
        line()
        val column = c.vstack(Gravity.CENTER_HORIZONTAL) {
            if (!ring) {
                // the fold-away mark, the chat's back mark one to one (iOS MontanaCallMark chevron.down)
                addView(FrameLayout(c).apply {
                    addView(FrameLayout(c).apply {
                        background = c.glassPlate(oval = true)
                        contentDescription = c.getString(R.string.call_back)
                        addView(c.icon(R.drawable.ic_chevron_down, Color.WHITE, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        pressable { close?.invoke() }
                    }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START))
                }, lp().apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(8) })
                gap(20)
            } else gap(22)
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                addView(light, lp(dp(11), dp(11)).apply { marginEnd = dp(6) })
                addView(status, lp(WRAP, WRAP))
            }, lp(WRAP, WRAP).apply { leftMargin = dp(16); rightMargin = dp(16) })
            gap(10)
            addView(c.text(name, 40f, Color.WHITE, bold = true, center = true).apply {   // USER-DATA: the peer's name
                singleLineEllipsis()
                setAutoSizeTextTypeUniformWithConfiguration(24, 40, 1, TypedValue.COMPLEX_UNIT_SP)
            }, lp(MATCH, dp(52)).apply { leftMargin = dp(16); rightMargin = dp(16) })
            addView(state, lp().apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(2) })
            addView(View(c), lp(MATCH, 0, 1f))
            if (ring) {
                // iOS 6520-6527: Decline and Accept, seventy-two apart
                val decline = button(c, R.string.call_decline, R.drawable.ic_phone_down, SysColor.red, { false }) { Thread { Calls.hangUp() }.start() }
                val accept = button(c, R.string.call_accept, R.drawable.ic_peer_phone, SysColor.green, { false }) {
                    val cl = close; close = null; ringing = false; cl?.invoke()
                    act.answerCall()
                }
                addView(c.hstack { addView(decline, lp(dp(COL), WRAP)); gap(72); addView(accept, lp(dp(COL), WRAP)) }, lp(WRAP, WRAP))
            } else {
                // the microphone off as the system's own call shows it: the lit plate, the crossed microphone in red (iOS 6801)
                val mute = button(c, R.string.call_mute, R.drawable.ic_mic_slash, null, { CallLine.muted }, SysColor.red) { CallLine.setMuted(!CallLine.muted) }
                val end = button(c, R.string.call_end, R.drawable.ic_phone_down, SysColor.red, { false }) { Thread { Calls.hangUp("hangup") }.start() }
                addView(c.hstack { addView(route!!.first, lp(dp(COL), WRAP)); gap(GAP); addView(View(c), lp(dp(COL), 1)); gap(GAP); addView(mute, lp(dp(COL), WRAP)) }, lp(WRAP, WRAP))
                gap(30)
                val holdBtn = button(c, R.string.call_hold, R.drawable.ic_pause_fill, null, { CallLine.held }) { CallLine.setHeld(!CallLine.held) }
                hold = { holdBtn.tag?.let { (it as () -> Unit)() } }
                addView(c.hstack { addView(holdBtn, lp(dp(COL), WRAP)); gap(GAP); addView(end, lp(dp(COL), WRAP)); gap(GAP); addView(View(c), lp(dp(COL), 1)) }, lp(WRAP, WRAP))
            }
            gap(46)
        }
        val page = Page(c).apply {
            isClickable = true   // the screen takes every touch: nothing under it is reached
            addView(ground(c, ref, name), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            inner = column
        }
        var live = true
        MainThread.later(1000, object : Runnable {
            override fun run() { if (!live) return; line(); MainThread.later(1000, this) }
        })
        // the page goes by the fold, by the system's back or by the call's end; a call still in hand leaves its pill
        page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                live = false
                if (ring) return
                close = null
                if (Calls.held() == ref) showPill(act, ref)
            }
        })
        return page
    }

    /** The page is laid edge to edge; the system bars' room the overlay gives it goes to the column, not to the ground. */
    private class Page(c: Context) : FrameLayout(c) {
        var inner: View? = null
        override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
            val i = inner
            if (i != null) i.setPadding(left, top, right, bottom) else super.setPadding(left, top, right, bottom)
        }
    }

    /**
     * THE AUDIO BUTTON WEARS THE SYSTEM CALL'S OWN WORDS (iOS routeBtn 6923, MontanaAudioRoute.face 7261): the phone alone —
     * «Speaker», lit on the loudspeaker, one press moves the sound; a device outside the phone — «Audio» with the glyph of where
     * the sound is, and a press opens the platform's own menu of the ways, the one in use checked.
     */
    private fun routeButton(c: Context): Pair<View, () -> Unit> {
        val icon = c.icon(R.drawable.ic_speaker_wave3, barGlyph, 26)
        val face = FrameLayout(c).apply { addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)) }
        val caption = c.text("", 15f, Color.WHITE, center = true).apply { singleLineEllipsis() }
        var shown: CallLine.Face? = null
        fun paint() {
            val f = CallLine.face()
            if (f == shown) return
            shown = f
            icon.setImageResource(f.icon)
            icon.setColorFilter(if (f.lit) Color.BLACK else barGlyph)
            face.background = if (f.lit) c.rounded(Color.WHITE, SIDE / 2) else c.glassPlate(oval = true)
            caption.text = c.getString(f.caption)
            face.contentDescription = caption.text
        }
        paint()
        face.pressable {
            val f = CallLine.face()
            if (!f.menu) {
                face.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                CallLine.setSpeaker(!f.lit)
                paint()
                return@pressable
            }
            val ways = CallLine.ways(c)
            PopupMenu(c, face).apply {
                ways.forEachIndexed { i, w -> menu.add(0, i, i, w.name).apply { isCheckable = true; isChecked = w.chosen } }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item -> ways.getOrNull(item.itemId)?.take?.invoke(); paint(); true }
            }.show()
        }
        val col = c.vstack(Gravity.CENTER_HORIZONTAL) {
            addView(face, lp(dp(SIDE), dp(SIDE)))
            gap(8)
            addView(caption, lp())
        }
        return col to { paint() }
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
            tag = { paint() }
            addView(face, lp(dp(SIDE), dp(SIDE)))
            gap(8)
            addView(c.text(c.getString(caption), 15f, Color.WHITE, center = true).apply { singleLineEllipsis() }, lp())
        }
    }

    /** iOS pillBody (6536-6559): the green capsule — the call's glyph and its clock, «Call» before it joins — and the red end. */
    private fun showPill(act: MainActivity, ref: String) {
        if (pill != null) return
        val c: Context = act
        val clock: TextView = c.text("", 13f, Color.WHITE, bold = true).apply { fontFeatureSettings = "tnum" }
        fun tick() {
            val since = CallLine.connectedAt
            clock.text = if (since > 0L) ((System.currentTimeMillis() - since) / 1000).toInt().let { String.format(Locale.ROOT, "%d:%02d", it / 60, it % 60) }
                else c.getString(R.string.call_pill)
        }
        tick()
        val v = c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                background = c.rounded(SysColor.green, 18)
                elevation = dp(4).toFloat()
                setPadding(dp(14), dp(8), dp(14), dp(8))
                addView(c.icon(R.drawable.ic_peer_phone, Color.WHITE, 14), lp(dp(14), dp(14)).apply { marginEnd = dp(8) })
                addView(clock, lp(WRAP, WRAP))
                pressable { show(act, ref) }
            }, lp(WRAP, WRAP))
            gap(8)
            // the red end, its 32-point face inside the finger's 44
            addView(FrameLayout(c).apply {
                addView(FrameLayout(c).apply {
                    background = c.rounded(SysColor.red, 16)
                    elevation = dp(4).toFloat()
                    addView(c.icon(R.drawable.ic_phone_down, Color.WHITE, 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                contentDescription = c.getString(R.string.call_end)
                pressable { Thread { Calls.hangUp("pill") }.start() }
            }, lp(dp(44), dp(44)))
        }
        val gone = act.floating(v, 40)
        var live = true
        pill = { live = false; gone() }
        MainThread.later(1000, object : Runnable {
            override fun run() { if (!live) return; tick(); MainThread.later(1000, this) }
        })
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
