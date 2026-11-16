package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * THE PHONE KEEPS THE DEVICES OF ITS SEED (iOS MTLinkedDevices, 74a02ccd 2404, a9c33f27 2405, 8a4ed08f 2408, OZ 1 p.4; the author's
 * words 22627 and 22854). This phone lists the seed's other devices it hears in the twin's pipe (Twin.heardTwin, the one book
 * Twin.TWINS), ends a session by the word «end» in that pipe, and chooses the term after which a joined device (a Mac) that has not
 * heard it ends itself. Android is always the phone's side: it joins no Mac's identity, so the word «end» never erases anything here.
 */
object LinkedDevices {
    const val DAYS = "mt.link.days"       // NOT-UI: the phone's choice: a device not heard this many days ends itself
    const val DEFAULT_DAYS = 182
    val CHOICES = listOf(7, 30, 91, 182)
    private const val NAMES = "mt.link.names"   // NOT-UI: {writer: the kind its letters named}
    private const val ENDED = "mt.link.ended"   // NOT-UI: {writer: when the person ended its session, s}
    /** The one kind that erases itself on the word end (iOS joinedKind): a product name. */
    const val JOINED_KIND = "Mac"   // NOT-UI: a product name
    /** The kind this device's letters name it by in the other devices' lists (iOS kind: the platform's own name). */
    const val KIND = "Android"   // NOT-UI: a product name

    var days: Int
        get() = Prefs.int(DAYS, 0).let { if (it > 0) it else DEFAULT_DAYS }
        set(v) { if (v in CHOICES) Prefs.setInt(DAYS, v) }

    /** THE ONE STAMP OF A LETTER IN THE SEED'S PIPE (iOS stamp, constitution 15): its kind and, on the phone, the term of its joined devices. */
    fun stamp(o: JSONObject) { o.put("d", KIND).put("e", days) }

    private fun map(key: String): JSONObject = runCatching { JSONObject(Prefs.str(key, "{}")) }.getOrNull() ?: JSONObject()
    private fun ended(): JSONObject = map(ENDED)

    /** One device of the seed in the phone's list: its writer, its kind, when it was last heard (ms). */
    class Device(val id: String, val kind: String, val heard: Long) { val joined: Boolean get() = kind == JOINED_KIND }

    /** THE LIST IS THE BOOK OF THE SEED'S DEVICES HEARD (Twin.TWINS), within the term, without the sessions ended here, the last heard first. */
    fun list(): List<Device> {
        val now = System.currentTimeMillis() / 1000.0
        val term = days * 86_400.0
        val book = map(Twin.TWINS); val n = map(NAMES); val gone = ended()
        return book.keys().asSequence().mapNotNull { k ->
            val e = book.optJSONArray(k) ?: return@mapNotNull null
            if (e.length() != 2 || now - e.optDouble(0, 0.0) >= term || gone.has(k)) null
            else Device(k, n.optString(k), (e.optDouble(0, 0.0) * 1000).toLong())
        }.sortedByDescending { it.heard }.toList()
    }

    /** «End session»: the word end in the seed's pipe names the device; a joined device erases itself on hearing it. */
    fun endSession(tag: String) {
        Prefs.setStr(ENDED, ended().put(tag, System.currentTimeMillis() / 1000.0).toString())
        tellEnd(tag)
    }
    /** «End all sessions» (the author's word 22854): every device of the list, each by the one door of a single end. */
    fun endAll() { for (d in list()) endSession(d.id) }

    private val ex = Executors.newSingleThreadExecutor()
    /** The word end carries the moment of the ending: a device joined anew after it never obeys it. */
    private fun tellEnd(tag: String) {
        val at = ended().optDouble(tag, System.currentTimeMillis() / 1000.0)
        ex.execute {
            runCatching {
                val secret = Twin.secret() ?: return@runCatching
                val me = Twin.deviceRef() ?: return@runCatching
                val told = Keeping.knock(secret, JSONObject().put("w", "end").put("t", tag).put("at", at), me, silent = true)
                Log.d("Montana", "link_end told=" + (if (told) 1 else 0))
            }.onFailure { Log.w("Montana", "link_end " + it.javaClass.simpleName) }
        }
    }

    private val toldAt = HashMap<String, Double>()
    /**
     * AN ENDED DEVICE STILL HEARD IS TOLD AGAIN (iOS endedAgain; a box keeps a day: a Mac away longer never read the first word): at
     * most once in ten minutes; it never returns to the list -- unless the phone joined it anew after the end (its letter's moment of
     * joining `joined`, by the phone's clock): that session stands, and the end is lifted.
     */
    fun endedAgain(tag: String, joined: Double?): Boolean {
        val e = ended()
        if (!e.has(tag)) return false
        val at = e.optDouble(tag, 0.0)
        if (joined != null && at < joined) {
            e.remove(tag); Prefs.setStr(ENDED, e.toString())
            return false
        }
        val now = System.currentTimeMillis() / 1000.0
        val due = synchronized(toldAt) {
            if (now - (toldAt[tag] ?: 0.0) <= 600) false else { toldAt[tag] = now; true }
        }
        if (due) tellEnd(tag)
        return true
    }

    /** A letter of the seed's other device was heard: the kind it names stands by its writer (iOS heard; the phone keeps no phone of its own). */
    fun heard(tag: String, kind: String?) {
        if (kind.isNullOrEmpty() || kind.length >= 17) return
        val n = map(NAMES)
        if (n.optString(tag) == kind) return
        Prefs.setStr(NAMES, n.put(tag, kind).toString())
    }
}

// ─────────────────────────── Settings — Devices (iOS DevicesView) ───────────────────────────

/**
 * THE DEVICES OF ONE IDENTITY (iOS DevicesView, OZ 1 p.4): the seed's devices this phone hears, each with «End session», «End all
 * sessions» under them, and the term after which a connected device that has not heard this phone ends itself. Connecting a Mac by
 * its QR comes with the next build.
 */
fun devicesPage(act: MainActivity, onBack: () -> Unit): View {
    val c: Context = act
    lateinit var list: LinearLayout
    fun termName(d: Int): String = c.getString(when (d) { 7 -> R.string.dv_1w; 30 -> R.string.dv_1m; 91 -> R.string.dv_3m; else -> R.string.dv_6m })
    fun glyph(kind: String): Int = when (kind) { "Mac" -> R.drawable.ic_laptop; "iPad" -> R.drawable.ic_tablet; else -> R.drawable.ic_smartphone }
    fun draw() {
        list.removeAllViews()
        val devices = LinkedDevices.list()
        val rows = ArrayList<View>()
        if (devices.isEmpty()) rows += c.text(c.getString(R.string.dv_none), 16f, MT.gray).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        for (d in devices) rows += c.hstack {
            setPadding(dp(16), dp(8), dp(6), dp(8))
            minimumHeight = dp(52)
            addView(c.icon(glyph(d.kind), Color.WHITE), lp(dp(24), dp(24)).apply { marginEnd = dp(12) })
            addView(c.vstack(Gravity.NO_GRAVITY) {
                // USER-DATA: a product name its letters carried (Mac, iPhone, iPad, Android), never translated
                addView(c.text(d.kind.ifEmpty { c.getString(R.string.dv_device) }, 16f, Color.WHITE).apply { singleLineEllipsis() })
                addView(c.text(android.text.format.DateUtils.getRelativeTimeSpanString(d.heard, System.currentTimeMillis(), 60_000L).toString(), 13f, MT.gray))
            }, lp(0, WRAP, 1f))
            // EVERY SESSION ENDS (the author's word 22854): a joined Mac erases itself, a device of the words leaves the list
            addView(c.text(c.getString(R.string.dv_end), 16f, MT.red).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
                pressable {
                    AlertDialog.Builder(act).setTitle(R.string.dv_end_q)
                        .setMessage(if (d.joined) R.string.dv_end_joined else R.string.dv_end_words)
                        .setPositiveButton(R.string.dv_end) { _, _ -> LinkedDevices.endSession(d.id); draw() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }, lp(WRAP, dp(48)))
        }
        if (devices.isNotEmpty()) rows += c.text(c.getString(R.string.dv_end_all), 16f, MT.red).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            pressable {
                AlertDialog.Builder(act).setTitle(R.string.dv_end_all_q).setMessage(R.string.dv_end_all_msg)
                    .setPositiveButton(R.string.dv_end_all) { _, _ -> LinkedDevices.endAll(); draw() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
        list.section(c.getString(R.string.devices), null, *rows.toTypedArray())
        // the term: a menu of four, as the iOS Picker
        val value = c.text(termName(LinkedDevices.days), 15f, MT.gray)
        val term = c.settingsRow(null, 0, c.getString(R.string.dv_term), value = value, chevron = false) {
            PopupMenu(act, value).apply {
                LinkedDevices.CHOICES.forEachIndexed { i, d -> menu.add(0, i, i, termName(d)) }
                setOnMenuItemClickListener { LinkedDevices.days = LinkedDevices.CHOICES[it.itemId]; value.text = termName(LinkedDevices.days); true }
                show()
            }
        }
        list.section(null, c.getString(R.string.dv_term_footer), term)
    }
    val page = settingsPage(act, c.getString(R.string.devices), onBack) { list = this; draw() }
    // the list is read again each time the page comes back on screen (iOS onAppear)
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { draw() }
        override fun onViewDetachedFromWindow(v: View) {}
    })
    return page
}
