package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─────────────────────────── the calls under the bar (iOS CallsTabView, ContentView.swift 2839) ───────────────────────────

/** One call of the log (iOS CallRecord): the row each phone laid for itself in the conversation (Calls.row). */
private class CallRecord(val ref: String, val mid: String, val info: Calls.Info, val at: Long)

/** THE ONE CALL LOG (iOS ChatStore.callRecords 2652): every call's row of every conversation, newest first. */
private fun callRecords(): List<CallRecord> = Book.refs().flatMap { ref ->
    val msgs = Book.chat(ref)?.let { ch -> runCatching { ch.msgs.toList() }.getOrDefault(emptyList()) } ?: emptyList()
    msgs.mapNotNull { m -> Calls.info(m.text)?.let { CallRecord(ref, m.mid, it, m.at) } }
}.sortedByDescending { it.at }

/**
 * iOS CallsTabView.cell: the face; the name — red when the call was missed — and the clock; under them the arrow of the
 * call's direction, the camera of a video call, how it went (the length, «Missed», «No answer», «Connection failed») and the
 * call's own glyph at the end of the line.
 */
private fun callRow(act: MainActivity, r: CallRecord): View {
    val c: Context = act
    val i = r.info
    val name = Book.chat(r.ref)?.shown?.ifBlank { null } ?: c.getString(R.string.peer)
    val how = when {
        i.missed -> c.getString(if (i.incoming) R.string.call_missed_short else R.string.call_no_answer)
        i.dur > 0 -> String.format(Locale.ROOT, "%d:%02d", i.dur / 60, i.dur % 60)
        else -> c.getString(R.string.call_failed)
    }
    return c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(c.peerFace(r.ref, name, 54), lp(dp(54), dp(54)).apply { marginEnd = dp(12) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                addView(c.text(name, 17f, if (i.missed) SysColor.red else Color.WHITE, bold = true).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: the name
                addView(c.text(DateFormat.getTimeFormat(c).format(Date(r.at)), 14f, MT.gray))
            }, lp())
            addView(c.hstack {
                gravity = Gravity.CENTER_VERTICAL
                addView(c.icon(if (i.incoming) R.drawable.ic_call_received else R.drawable.ic_call_made, if (i.missed) SysColor.red else SysColor.green, 12),
                    lp(dp(12), dp(12)).apply { marginEnd = dp(5) })
                if (i.video) addView(c.icon(R.drawable.ic_peer_video, MT.gray, 11), lp(dp(11), dp(11)).apply { marginEnd = dp(5) })
                addView(c.text(how, 16f, MT.gray).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))
                addView(c.icon(if (i.video) R.drawable.ic_peer_video else R.drawable.ic_peer_phone, Color.WHITE, 18), lp(dp(18), dp(18)))
            }, lp().apply { topMargin = dp(4) })
        }, lp(0, WRAP, 1f))
    }
}

/**
 * THE CALLS (iOS CallsTabView): the log of calls, a day's pill before the first call of each day, newest first; a stroke from
 * the right deletes a call's row (iOS swipeTrailing). A tap dials back on iOS; until this phone calls out (step 4 of Anna's plan),
 * it opens the person's chat. «Your audio and video calls will appear here» while there are none.
 */
fun callsPane(act: MainActivity): View {
    val c: Context = act
    val rows = c.vstack(Gravity.NO_GRAVITY)
    val empty = c.text(c.getString(R.string.no_calls), 17f, MT.gray, center = true)
    fun fill() {
        rows.removeAllViews()
        val log = callRecords()
        empty.visibility = if (log.isEmpty()) View.VISIBLE else View.GONE
        val cal = Calendar.getInstance()
        var lastDay = -1L
        for (r in log) {
            cal.timeInMillis = r.at
            val day = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
            if (day != lastDay) {
                lastDay = day
                // the day's pill, the same the conversation stands between its days
                val words = if (DateUtils.isToday(r.at)) c.getString(R.string.today) else DateUtils.formatDateTime(c, r.at, DateUtils.FORMAT_SHOW_DATE)
                rows.addView(FrameLayout(c).apply {
                    addView(c.text(words, 13f, Color.WHITE).apply { background = c.glassPlate(); setPadding(dp(10), dp(3), dp(10), dp(3)) },
                        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, lp().apply { topMargin = c.dp(8); bottomMargin = c.dp(4) })
            }
            val row = callRow(act, r)
            row.pressable { act.push { close -> conversationPage(act, r.ref, close) } }
            rows.addView(SwipeRow(act, row, listOf(Tile(R.drawable.ic_delete, red = true) {
                Book.edit(r.ref) { ch -> ch.msgs.removeAll { it.mid == r.mid } }
            })), lp())
            rows.addView(View(c).apply { setBackgroundColor(MT.hairline) }, lp(MATCH, 1).apply { marginStart = c.dp(82) })
        }
    }
    fill()
    val again: () -> Unit = { act.onMain { fill() } }
    rows.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { Book.listen(again); fill() }
        override fun onViewDetachedFromWindow(v: View) { Book.unlisten(again) }
    })
    return FrameLayout(c).apply {
        addView(ScrollView(c).apply {
            isVerticalScrollBarEnabled = false
            addView(c.vstack(Gravity.NO_GRAVITY) {
                addView(rows, lp())
                gap(120)
            })
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }
}
