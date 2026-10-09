package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
    fun draw() = face.draw(SelfFace.load(act), Prefs.userName, false, MyAbout.bio(), PeerAbout.url(MyAbout.link()), "")
    draw()
    body.addView(face.view, lp())
    // MY OWN WALL UNDER THE HEAD (iOS MTBoardPane(owner: nil)): the row that writes a post, then my posts, the pinned first
    val wall = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(12), dp(16), dp(12), 0) }
    fun drawWall() {
        wall.removeAllViews()
        wall.addView(c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            background = c.glassPlate()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(FrameLayout(c).apply {
                background = c.glassPlate(oval = true)
                addView(c.icon(R.drawable.ic_compose, Color.WHITE), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, lp(dp(44), dp(44)).apply { marginEnd = dp(14) })
            addView(c.text(c.getString(R.string.pi_write_wall), 17f))
            pressable { act.push { close -> newPostPage(act, close) } }
        }, lp())
        val ps = MyWall.posts()
        if (ps.isEmpty()) wall.addView(c.text(c.getString(R.string.pi_wall_empty), 15f, MT.gray, center = true).apply { setPadding(0, dp(28), 0, dp(28)) }, lp())
        else ps.forEach { p -> wall.addView(postCell(act, Board.Item("", p), onItsWall = true), lp().apply { topMargin = dp(10) }) }
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
 * A NEW POST (iOS «New post», MontanaBoardViews 1762; the author's word 24.09: «the new post shows at once as it will be published»):
 * my face and my name over the words, written where they will stand; the check mark publishes it on my wall. Words only for now —
 * the files of a post come with the post's files on the node.
 */
fun newPostPage(act: MainActivity, onClose: () -> Unit): View {
    val c: Context = act
    val field = android.widget.EditText(c).apply {
        hint = c.getString(R.string.write_something)
        setTextColor(Color.WHITE); setHintTextColor(MT.gray); background = null
        textSize = 17f
        minLines = 3
        setPadding(dp(16), dp(12), dp(16), dp(16))
    }
    val done = c.icon(R.drawable.ic_check, Color.WHITE).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        pressable {
            val t = field.text.toString()
            if (t.isBlank()) return@pressable
            Thread { MyWall.publish(t) }.start()
            onClose()
        }
    }
    val page = settingsPage(act, c.getString(R.string.new_post), onClose, cross = true, trailing = done) {
        addView(c.plate {
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), 0)
                addView(c.avatar(SelfFace.load(c), Prefs.userName, 40), lp(dp(40), dp(40)))
                gap(10)
                addView(c.text(Prefs.userName, 15f, Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: my name
            }, lp())
            addView(field, lp())
        }, lp().apply { topMargin = dp(12) })
    }
    field.post {
        field.requestFocus()
        c.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    return page
}

