package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu

// ─────────────────────────── the main screen (iOS ChatsListView and its panes) ───────────────────────────

/**
 * THE CREST BEHIND THE PAGES (iOS MontanaCrestGround): the Dream Department's arms, centred on the whole screen at seven
 * tenths of its width, softened and half transparent, and the same picture filling the screen edge to edge as a gold
 * haze. The platform softens a view with its own RenderEffect from Android 12; before it, the crest stands unsoftened
 * and quieter, and the haze (a blur and nothing else) is left out.
 */
class CrestGround(c: Context) : FrameLayout(c) {
    private val crest = ImageView(c)

    init {
        setBackgroundColor(Color.BLACK)
        val picture = kept ?: BitmapFactory.decodeResource(resources, R.drawable.chats_crest).also { kept = it }
        val soft = Build.VERSION.SDK_INT >= 31
        if (soft) addView(ImageView(c).apply {
            setImageBitmap(picture)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = HAZE_OPACITY
            setRenderEffect(blur(HAZE_BLUR))
        }, LayoutParams(MATCH, MATCH))
        crest.setImageBitmap(picture)
        crest.scaleType = ImageView.ScaleType.FIT_CENTER
        crest.alpha = if (soft) OPACITY else OPACITY * 0.6f
        if (soft) crest.setRenderEffect(blur(BLUR))
        addView(crest, LayoutParams(0, MATCH, Gravity.CENTER))
        // THE PAGES' GROUND, SOFTENED ONCE MORE (iOS MTSoftGround, every page): the whole ground blurred so no edge of the crest
        // is readable, and dimmed as the App Library dims its wallpaper.
        if (soft) setRenderEffect(RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setScale(SOFT_LIGHT, SOFT_LIGHT, SOFT_LIGHT, 1f) })),
            blur(SOFT_RADIUS)))
    }

    private fun blur(r: Float): RenderEffect {
        val px = dp(r).toFloat()
        return RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP)
    }

    /** A ground asks for no room of its own: it fills what its container is given (the preview's box, the screen). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        val w = size(widthMeasureSpec); val h = size(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val exact = { v: Int -> MeasureSpec.makeMeasureSpec(v, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val cw = (child.layoutParams.width).let { if (it > 0) it else w }
            child.measure(exact(cw), exact(h))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { crest.layoutParams = LayoutParams((w * SCALE).toInt(), MATCH, Gravity.CENTER) }
    }

    private companion object {
        /** The crest decoded once a launch: the preview of «Appearance» draws the ground again at every slider's move. */
        var kept: android.graphics.Bitmap? = null
        const val BLUR = 8f           // the crest's own softness (iOS blur 8)
        const val OPACITY = 0.5f
        const val SCALE = 0.7f        // seven tenths of the screen's width
        const val HAZE_BLUR = 48f
        const val HAZE_OPACITY = 0.35f
        const val SOFT_RADIUS = 36f   // iOS MTSoftGround.radius
        const val SOFT_LIGHT = 0.72f  // iOS MTSoftGround.light
    }
}

/**
 * THE GLASS OF A PLATE (iOS montanaOctagonFace(bar: true), MTGlassCirclePlate): the platform draws no glass of other
 * views, so the plate is what the eye sees of it over the dark crest — a thin light fill and a lighter rim.
 */
fun Context.glassPlate(oval: Boolean = false) = GradientDrawable().apply {
    if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(100).toFloat()
    setColor(Color.argb(28, 255, 255, 255))
    setStroke(dp(1).coerceAtMost(3), Color.argb(38, 255, 255, 255))
}

/** The glyph inside a bar button (iOS MontanaOctagon.barGlyph: Color(white: 0.8)). */
private val barGlyph = Color.rgb(204, 204, 204)

/** The pages under the bar that Android has so far (iOS UIState.Pane: .chats and .contacts). */
enum class Pane { CHATS, CONTACTS }

fun mainScreen(act: MainActivity, onForget: () -> Unit): View {
    val c = act
    act.setGround(CrestGround(c))
    var pane = Pane.CHATS   // iOS: the chats are the first page of a launch
    val panes = FrameLayout(c)
    val chats = chatsPane(act)
    val contacts = contactsPane(act)
    panes.addView(chats, FrameLayout.LayoutParams(MATCH, MATCH))
    panes.addView(contacts, FrameLayout.LayoutParams(MATCH, MATCH))
    lateinit var bar: HomeBar
    fun choose(p: Pane) {
        pane = p
        bar.movePuck(if (p == Pane.CHATS) HomeBar.CHATS else HomeBar.CONTACTS)
        val (on, off) = if (p == Pane.CHATS) chats to contacts else contacts to chats
        off.visibility = View.GONE
        on.alpha = 0f; on.visibility = View.VISIBLE
        on.animate().alpha(1f).setDuration(200).start()   // iOS .easeInOut(duration: 0.2)
    }
    bar = HomeBar(act, onDrawer = { anchor -> drawerMenu(act, anchor, onForget) }, onPane = ::choose)
    contacts.visibility = View.GONE
    bar.movePuck(HomeBar.CHATS, animated = false)
    return c.vstack(Gravity.NO_GRAVITY) {
        addView(bar, lp(MATCH, dp(60)).apply { setMargins(dp(12), dp(4), dp(12), dp(2)) })
        addView(searchRow(c), lp(MATCH, dp(56)))
        addView(panes, lp(MATCH, 0, 1f))
    }
}

/**
 * THE SIDE DRAWER'S PLACE (iOS: the grid glyph opens the drawer, and my page, the settings and the rest live there).
 * Android has no drawer yet: the glyph opens the platform's own menu with what Android already holds.
 */
private fun drawerMenu(act: MainActivity, anchor: View, onForget: () -> Unit) {
    PopupMenu(act, anchor).apply {
        menu.add(0, 1, 0, R.string.my_profile)
        menu.add(0, 4, 1, R.string.appearance)
        menu.add(0, 3, 2, R.string.your_node)
        menu.add(0, 2, 3, R.string.forget_device)
        setOnMenuItemClickListener {
            if (it.itemId == 1) act.showProfile()
            else if (it.itemId == 4) { lateinit var close: () -> Unit; close = act.overlay(AppearancePage(act) { close() }.view) }
            else if (it.itemId == 3) { lateinit var close: () -> Unit; close = act.overlay(NodePage(act) { close() }.view) }
            else AlertDialog.Builder(act).setTitle(R.string.forget_device)
                .setPositiveButton(R.string.forget_device) { _, _ -> onForget() }
                .setNegativeButton(R.string.back, null).show()
            true
        }
        show()
    }
}

/**
 * THE TIME PANEL (iOS chatsTopBarPlate): ONE glass capsule at the bar's height, and on it seven glyphs with equal shares of
 * the width, left to right — the drawer, the contacts, the calls, THE LOGO AT THE CENTRE, the chats, the player and the
 * globe. One puck stands under the chosen glyph and flows to the one tapped next. The calls, the feed under the logo, the
 * player and the network are pages Android does not have yet: their glyphs stand in their places and take no tap.
 */
class HomeBar(private val act: MainActivity, onDrawer: (View) -> Unit, onPane: (Pane) -> Unit) : FrameLayout(act) {
    private val puck = View(act).apply {
        background = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(Color.argb(41, 255, 255, 255)) }   // iOS MTBarPuck: white 0.16
    }
    private val row = act.hstack { }
    private var slot = CHATS

    init {
        background = act.glassPlate()
        addView(puck, LayoutParams(0, 0))
        addView(row, LayoutParams(MATCH, MATCH))
        glyph(R.drawable.ic_bar_drawer, 26) { onDrawer(it) }
        glyph(R.drawable.ic_bar_contacts, 26) { onPane(Pane.CONTACTS) }
        glyph(R.drawable.ic_bar_calls, 26)
        // THE LOGO ON THE GLASS (iOS MTFeedGlyph): the gold sign on a round glass plate, a quarter of its side around it.
        row.addView(FrameLayout(act).apply {
            addView(FrameLayout(act).apply {
                background = act.glassPlate(oval = true)
                addView(ImageView(act).apply { setImageResource(R.drawable.logo); setPadding(dp(9), dp(9), dp(9), dp(9)) },
                    LayoutParams(MATCH, MATCH))
            }, LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }, lp(0, MATCH, 1f))
        glyph(R.drawable.ic_bar_chats, 24) { onPane(Pane.CHATS) }
        glyph(R.drawable.ic_bar_play, 26)
        // THE GLOBE (iOS MTBarGlobe): the network's lamp — unknown wears grey; Android knocks on no node yet.
        glyph(R.drawable.ic_language, 26, tint = Color.rgb(115, 115, 115))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(false) }
    }

    private fun glyph(res: Int, sizeDp: Int, tint: Int = barGlyph, onTap: ((View) -> Unit)? = null) {
        val box = FrameLayout(act)
        box.addView(act.icon(res, tint), LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        if (onTap != null) box.pressable { onTap(box) }
        row.addView(box, lp(0, MATCH, 1f))
    }

    /** The puck goes under the slot (iOS MTPuckSlide: the slot's frame, 6 above and below, 3 at the sides). */
    fun movePuck(to: Int, animated: Boolean = true) { slot = to; place(animated) }

    private fun place(animated: Boolean) {
        if (width == 0) return
        val w = width / 7f
        val lpk = puck.layoutParams as LayoutParams
        val pw = (w - dp(6)).toInt(); val ph = height - dp(12)
        if (lpk.width != pw || lpk.height != ph) {
            lpk.width = pw; lpk.height = ph; lpk.topMargin = dp(6)
            post { puck.layoutParams = lpk }
        }
        val x = slot * w + dp(3)
        if (animated) puck.animate().translationX(x).setDuration(200).setInterpolator(AccelerateDecelerateInterpolator()).start()
        else puck.translationX = x
    }

    companion object { const val CONTACTS = 1; const val CHATS = 4 }
}

/**
 * THE SEARCH ROW (iOS MTSearchField, the platform's own search bar in its minimal style): a capsule with the magnifier and
 * «Search». Nothing is searched yet — the rows it will narrow come with the network.
 */
private fun searchRow(c: Context): View = FrameLayout(c).apply {
    addView(c.hstack {
        background = c.glassPlate()
        setPadding(dp(12), 0, dp(12), 0)
        addView(c.icon(R.drawable.ic_search, Color.rgb(230, 230, 230)), lp(dp(22), dp(22)).apply { marginEnd = dp(8) })
        addView(EditText(c).apply {
            hint = c.getString(R.string.search)
            setHintTextColor(MT.gray); setTextColor(Color.WHITE)
            textSize = 17f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
        }, lp(0, WRAP, 1f))
    }, FrameLayout.LayoutParams(MATCH, dp(40), Gravity.CENTER_VERTICAL).apply { setMargins(dp(19), 0, dp(19), 0) })
}

/** THE CHATS PAGE as a new person first sees it: the local room, «No chats yet», one's own face and name. */
private fun chatsPane(act: MainActivity): View {
    val c = act
    val name = Prefs.userName
    return c.vstack(Gravity.NO_GRAVITY) {
        // The local room: stands in the list from the first moment.
        addView(c.hstack {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(FrameLayout(c).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MT.gold) }
                addView(c.icon(R.drawable.ic_bookmark, Color.WHITE), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, lp(dp(54), dp(54)).apply { marginEnd = dp(12) })
            addView(c.text(c.getString(R.string.saved_messages), 17f, Color.WHITE, bold = true))
        }, lp())
        addView(View(c).apply { setBackgroundColor(MT.hairline) }, lp(MATCH, 1).apply { marginStart = dp(82) })
        gap(20)
        addView(c.text(c.getString(R.string.no_chats), 20f, Color.WHITE, bold = true, center = true), lp())
        gap(16)
        // The person's code page (MontanaCodeView) comes with the network step; the face and name stand here.
        addView(c.avatar(SelfFace.load(c), name, 96), lp(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        gap(10)
        addView(c.text(name, 18f, MT.gold, bold = true, center = true), lp())
        spacer()
        addView(c.text(versionFooter(c), 11f, MT.withAlpha(MT.gray, 0.7f), center = true).apply { setPadding(0, dp(8), 0, dp(8)) }, lp())
    }
}

/**
 * THE CONTACTS PAGE (iOS ContactsTab): «No contacts yet» at the centre, and at the bottom right the round glass button with
 * the share glyph. On iOS it hands out one's card (MontanaCardShare: the words and the link); the card is born with the
 * network step, so on Android the button stands in its place and hands out nothing yet.
 */
private fun contactsPane(act: MainActivity): View {
    val c = act
    return FrameLayout(c).apply {
        addView(c.text(c.getString(R.string.no_contacts), 17f, MT.gray, center = true),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        addView(FrameLayout(c).apply {
            background = c.glassPlate(oval = true)
            contentDescription = c.getString(R.string.share)
            addView(c.icon(R.drawable.ic_share, barGlyph), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(36)) })
    }
}
