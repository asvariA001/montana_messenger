package quest.montana.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Switch

/**
 * THE MESH WALL (iOS NetworkTabView on the mesh pane, MontanaNetworkView 70-320; the author's word 29.09: «the network is
 * disbanded — the mesh wall and the P2P wall are applications»). The wall's head (146-174): the antenna on the plain glass, on
 * its badge the people reached phone to phone, green while this phone is in and red while it is not — the moment findability
 * is off — and one line saying what the wall is. Then «How others see you» (283-319): the first letter and the name, said
 * honestly, and the one switch of being findable. Then the mesh card (iOS MontanaMeshCard): who is here, phone to phone.
 */
fun meshPane(act: MainActivity): View {
    val c = act
    val side = c.dp(18)   // iOS MTLibraryRow.side
    val badge = c.text("0", 11f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        val pad = c.dp(5)
        setPadding(pad, pad, pad, pad)
        minWidth = c.dp(22); minHeight = c.dp(22)
        translationX = c.dp(5).toFloat(); translationY = -c.dp(3).toFloat()   // iOS .offset(x: 5, y: -3)
    }
    val head = c.vstack {
        clipChildren = false
        setPadding(c.dp(12), c.dp(16), c.dp(12), c.dp(6))
        addView(FrameLayout(c).apply {
            clipChildren = false
            addView(FrameLayout(c).apply {
                background = c.glassPlate(oval = true)
                addView(c.icon(R.drawable.ic_set_antenna, Color.WHITE, 26), FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        }, lp(c.dp(62), c.dp(62)))
        gap(12)
        addView(c.text(c.getString(R.string.mesh_wall_caption), 13f, MT.gray, center = true).apply { setPadding(c.dp(28), 0, c.dp(28), 0) }, lp())
    }
    val name = Prefs.userName.trim()   // USER-DATA: a person's name
    val first = if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0))).uppercase()   // USER-DATA: its first letter
    val sw = Switch(c).apply {
        isChecked = Mesh.discoverable
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(MT.green, Color.rgb(57, 57, 61)))
        contentDescription = c.getString(R.string.mesh_findable)
    }
    val me = c.vstack(Gravity.NO_GRAVITY) {
        setPadding(side, c.dp(24), side, c.dp(12))   // iOS .padding(.top, 12) over the card's own .padding(.vertical, 12)
        addView(c.text(c.getString(R.string.mesh_seen_as), 12f, MT.gray), lp())
        gap(12)
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(c.text(first, 18f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(26, 255, 255, 255)) }
            }, lp(c.dp(48), c.dp(48)))
            gap(12)
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(name, 15f, Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp())
                gap(2)
                addView(c.text(c.getString(R.string.mesh_seen_only), 11f, MT.gray), lp())
            }, lp(0, WRAP, 1f))
        }, lp())
        gap(12)
        addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(c.text(c.getString(R.string.mesh_findable), 14f), lp())
                gap(2)
                addView(c.text(c.getString(R.string.mesh_findable_off), 11f, MT.gray), lp())
            }, lp(0, WRAP, 1f))
            gap(8)
            addView(sw)
            setOnClickListener { sw.toggle() }   // the whole row is the target
        }, lp())
    }
    fun draw() {
        val on = Channels.nodesHeld() > 0 && Mesh.discoverable   // iOS: node.p2pUp && meshDiscoverable
        badge.text = Channels.peers().toString()   // USER-DATA: a count — the people reached phone to phone (the radio's come with it)
        badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) MT.green else MT.red) }
    }
    // THE MESH CARD (iOS MontanaMeshCard 11-104): «Mesh», a hold on it shows what the network does; then who is here, one list
    // whatever medium reaches them, or the line that nobody is yet. The common room's row (onOpenRoom) and the Bluetooth peers
    // come with the radio (iOS MontanaBLEMesh, MTMeshRoom) — Android has no Bluetooth mesh yet, so its row stays away.
    val nearList = c.vstack(Gravity.NO_GRAVITY)
    val detail = c.vstack(Gravity.NO_GRAVITY).apply { setPadding(side, c.dp(12), side, c.dp(12)); visibility = View.GONE }
    val near = c.vstack(Gravity.NO_GRAVITY) {
        addView(c.text(c.getString(R.string.mesh_title), 17f, Color.WHITE, bold = true).apply {
            setPadding(side, c.dp(12), side, c.dp(12))
            setOnLongClickListener { detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true }
        }, lp())
        addView(nearList, lp())
        addView(detail, lp())
    }
    var shown: List<String>? = null
    fun drawNear() {
        val refs = Channels.nearRefs().sorted()
        if (refs != shown) {
            shown = refs
            nearList.removeAllViews()
            if (refs.isEmpty()) nearList.addView(c.text(c.getString(R.string.mesh_none), 12f, MT.gray).apply { setPadding(side, 0, side, c.dp(12)) }, lp())
            for (ref in refs) {
                val title = Book.chat(ref)?.shown?.takeIf { it.isNotBlank() } ?: ref.take(8)   // USER-DATA: a neighbour's name
                nearList.addView(c.hstack {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(side, 0, side, 0)
                    minimumHeight = c.dp(60)
                    addView(c.chatFace(ref, title, 48), lp(c.dp(48), c.dp(48)).apply { marginEnd = c.dp(12) })
                    addView(c.text(title, 17f, Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))
                    addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MT.green) } }, lp(c.dp(8), c.dp(8)))
                    pressable { act.push { close -> conversationPage(act, ref, close) } }
                }, lp())
            }
        }
        if (detail.visibility == View.VISIBLE) {
            detail.removeAllViews()
            fun row(k: Int, v: String) = detail.addView(c.hstack {
                addView(c.text(c.getString(k), 11f, MT.gray), lp(0, WRAP, 1f))
                addView(c.text(v, 10f, Color.WHITE).apply { typeface = android.graphics.Typeface.MONOSPACE }, lp())   // USER-DATA: a diagnostics value
            }, lp().apply { bottomMargin = c.dp(6) })
            row(R.string.mesh_d_nodes, Channels.nodesHeld().toString())
            row(R.string.mesh_d_bt, c.getString(R.string.mesh_d_off))
            row(R.string.mesh_d_now, refs.size.toString())
        }
    }
    sw.setOnCheckedChangeListener { _, v -> Mesh.setDiscoverable(v); draw() }
    draw(); drawNear()
    val page = ScrollView(c).apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        addView(c.vstack(Gravity.NO_GRAVITY) {
            clipChildren = false
            addView(head, lp())
            addView(me, lp())
            addView(near, lp())
            gap(120)   // the floating player's room at the foot
        })
    }
    // the badge follows the channels while the wall stands (iOS: an observed node redraws it)
    val tick = object : Runnable { override fun run() { if (page.isShown) { draw(); drawNear() }; page.postDelayed(this, 2000) } }
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { page.post(tick) }
        override fun onViewDetachedFromWindow(v: View) { page.removeCallbacks(tick) }
    })
    return CoinPull(act, page)   // one law for every page under the bar (iOS MontanaTimePanelList at 20f752ae: the coin's pull on the mesh page, MontanaMeshView 210 too)
}
