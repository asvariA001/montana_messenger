package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

/**
 * A DEVICE JOINS BY QR, THE PHONE'S SIDE (iOS MTDeviceLink, MTKeeping.swift 1357-1471; 2411, 2412; OZ 1 p.3-4, the author's words
 * 22607 and 22627: an identity is born only on the phone, the Mac signs in by QR). The new device draws a QR of 64 bytes: a one-time
 * secret L and the SHA-256 of a one-time ML-KEM-768 key whose secret half never leaves it, and lays the key in a box named by L. The
 * phone takes the key from there, holds it against the QR -- a node cannot swap it -- and, after its owner's own lock, lays back the
 * 24 words sealed to that key alone (the room's seal, iOS MTRoomSeal). Both screens show one number of six digits.
 */
object DeviceLink {
    const val SCHEME = "montana:link:1:"   // NOT-UI: the QR's own prefix
    private const val BOX = "link"         // NOT-UI: the name the link box is heard under, never a pipe of the book
    private const val PUB = 1184           // ML-KEM-768 encapsulation key (MT_MLKEM_PUBKEY_SIZE)
    private const val CT = 1088            // its ciphertext (MT_MLKEM_CT_SIZE)
    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    val ex = Executors.newSingleThreadExecutor()

    /** What the phone took from the box, held against the QR: the new device's key, and the number of both screens. */
    class Key(val l: ByteArray, val pub: ByteArray) { val check: String get() = check(l, pub) }

    private fun sha(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    private fun label(s: String) = s.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private fun boxSecret(l: ByteArray) = sha(label("mt-link-box"), l)   // LOCAL-HASH-OK: the link box's own label
    private fun bind(l: ByteArray, pub: ByteArray) = label("mt-link") + l + sha(pub)
    /** The number both screens show: six digits of the joining's secret and key. */
    fun check(l: ByteArray, pub: ByteArray): String {
        val d = sha(label("mt-link-check"), l, pub)   // LOCAL-HASH-OK: the number both screens show
        var n = 0L
        for (i in 0 until 4) n = n * 256 + (d[i].toInt() and 0xff)
        return String.format(Locale.ROOT, "%06d", n % 1_000_000)
    }
    /** The QR read: its secret and its commitment to the key, or null for any other code. */
    fun parse(code: String): Pair<ByteArray, ByteArray>? {
        val t = code.trim()
        if (!t.startsWith(SCHEME)) return null
        val d = runCatching { Base64.decode(t.removePrefix(SCHEME), B64) }.getOrNull() ?: return null
        return if (d.size == 64) d.copyOf(32) to d.copyOfRange(32, 64) else null
    }
    private fun freshRef(): String = "link#" + Wire.hex(ByteArray(8).also { SecureRandom().nextBytes(it) })   // NOT-UI: a name for one joining
    /** The words of the link box; they stay there (a public key; a failed joining scans the same QR again). */
    private fun words(l: ByteArray, ref: String): List<JSONObject> =
        Post.pickup(mapOf(BOX to boxSecret(l)), take = false, owner = ref).mapNotNull { Keeping.word(it.second) }

    /** The new device's key, taken from the box and held against the QR: a key the QR does not name is never sealed to. Blocks. */
    fun key(code: String): Key? {
        val (l, commit) = parse(code) ?: return null
        val ref = freshRef()
        repeat(10) {
            for (w in words(l, ref)) {
                if (w.optString("w") != "lk") continue
                val pk = runCatching { Base64.decode(w.optString("pk"), B64) }.getOrNull()
                if (pk != null && pk.size == PUB && sha(pk).contentEquals(commit)) {
                    Log.d("Montana", "link_key found")
                    return Key(l, pk)
                }
                Log.d("Montana", "link_key a key the QR does not name")
            }
            Thread.sleep(2000)
        }
        Log.d("Montana", "link_key not found")
        return null
    }

    /** The room's seal (iOS MTRoomSeal.seal): one encapsulation to the key, AES-256-GCM under SHA-256(label ‖ secret ‖ bind). */
    private fun seal(plain: ByteArray, pub: ByteArray, bind: ByteArray): Pair<ByteArray, ByteArray>? {
        if (pub.size != PUB) return null
        val o = MtBindings.nativeMlkemEncaps(pub)?.takeIf { it.size == CT + 32 } ?: return null
        val ct = o.copyOf(CT); val ss = o.copyOfRange(CT, o.size)
        val key = sha("mt-room-word".toByteArray(Charsets.UTF_8), ss, bind)
        ss.fill(0); o.fill(0)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val box = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                nonce + doFinal(plain)   // CryptoKit's combined: nonce ‖ sealed ‖ tag
            }
        }.getOrNull()
        key.fill(0)
        return box?.let { ct to it }
    }

    /**
     * The words, sealed to the new device's key alone; only after the owner's own lock on this phone (the caller). The gift carries
     * the phone's writer tag, the term of its devices and the moment of the giving -- a word end said before it never reaches it.
     * Blocks.
     */
    fun give(k: Key): Boolean {
        val words = MontanaSeed.mnemonic ?: return false
        val g = JSONObject().put("s", words).put("p", Archive.writerHex() ?: "").put("e", LinkedDevices.days)
            .put("t", System.currentTimeMillis() / 1000.0)
        val (ct, box) = seal(g.toString().toByteArray(Charsets.UTF_8), k.pub, bind(k.l, k.pub)) ?: return false
        val o = JSONObject().put("w", "lg").put("v", 1).put("ct", Base64.encodeToString(ct, B64)).put("b", Base64.encodeToString(box, B64))
        val laid = Keeping.knock(boxSecret(k.l), o, freshRef(), silent = true)
        Log.d("Montana", "link_give boxed=" + (if (laid) 1 else 0))
        if (laid) KeepCopy.lightForLink()   // the history's one owner lays the copy for this joining (decision 989 = a)
        return laid
    }
}

// ─────────────────────────── Settings — Devices (iOS DevicesView) ───────────────────────────

/**
 * THE DEVICES OF ONE IDENTITY (iOS DevicesView, OZ 1 p.4): the seed's devices this phone hears, each with «End session», «End all
 * sessions» under them, and the term after which a connected device that has not heard this phone ends itself. Above them «Connect
 * a device»: the phone scans the new device's QR (DeviceLink), shows the number of both screens, and gives nothing before its
 * owner's own lock.
 */
private enum class LinkStage { IDLE, SEEKING, CONFIRM, GIVING, GIVEN, NOT_FOUND, NO_LOCK, FAILED }

fun devicesPage(act: MainActivity, onBack: () -> Unit): View {
    val c: Context = act
    lateinit var list: LinearLayout
    var stage = LinkStage.IDLE
    var key: DeviceLink.Key? = null
    lateinit var redraw: () -> Unit
    /** The new device's key, from its box and held against the QR (DeviceLink.key): a key the QR does not name is never found. */
    fun seek(code: String) {
        stage = LinkStage.SEEKING; key = null; redraw()
        DeviceLink.ex.execute {
            val k = runCatching { DeviceLink.key(code) }.getOrNull()
            act.onMain { key = k; stage = if (k == null) LinkStage.NOT_FOUND else LinkStage.CONFIRM; redraw() }
        }
    }
    /** AN ABSENT CHECK IS A FAILED CHECK (as the seed's page): the words leave this phone only after its owner's own lock. */
    fun connect(k: DeviceLink.Key) {
        OwnerCheck.ask(act, c.getString(R.string.dv_link)) { w ->
            when (w) {
                OwnerWord.NO_LOCK -> { stage = LinkStage.NO_LOCK; redraw() }
                OwnerWord.FAILED -> {}
                OwnerWord.CONFIRMED -> {
                    stage = LinkStage.GIVING; redraw()
                    DeviceLink.ex.execute {
                        val ok = runCatching { DeviceLink.give(k) }.getOrDefault(false)
                        act.onMain {
                            if (ok) { key = null; stage = LinkStage.GIVEN } else stage = LinkStage.FAILED
                            redraw()   // the device stands in the list by its first letter in the seed's pipe
                        }
                    }
                }
            }
        }
    }
    fun linkRow(): View? {
        fun busy(words: Int) = c.hstack {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(android.widget.ProgressBar(c).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(MT.gray) }, lp(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(c.text(c.getString(words), 16f, MT.gray), lp(0, WRAP, 1f))
        }
        fun said(words: Int, color: Int) = c.text(c.getString(words), 16f, color).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        return when (stage) {
            LinkStage.IDLE -> null
            LinkStage.SEEKING -> busy(R.string.dv_link_seeking)
            LinkStage.CONFIRM -> key?.let { k -> c.vstack(Gravity.NO_GRAVITY) {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(c.text(c.getString(R.string.dv_link_check, k.check), 17f, Color.WHITE, bold = true))
                addView(c.text(c.getString(R.string.dv_link_warn), 13f, MT.gray), lp().apply { topMargin = dp(8) })
                addView(c.text(c.getString(R.string.dv_link_connect), 16f, Color.WHITE, bold = true, center = true).apply {
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    background = c.rounded(MT.blue, 10)
                    pressable { connect(k) }
                }, lp(WRAP, WRAP).apply { topMargin = dp(10) })
            } }
            LinkStage.GIVING -> busy(R.string.dv_link_giving)
            LinkStage.GIVEN -> said(R.string.dv_link_given, MT.green)
            LinkStage.NOT_FOUND -> said(R.string.dv_link_not_found, MT.red)
            LinkStage.NO_LOCK -> said(R.string.dv_link_no_lock, MT.red)
            LinkStage.FAILED -> said(R.string.dv_link_failed, MT.red)
        }
    }
    fun termName(d: Int): String = c.getString(when (d) { 7 -> R.string.dv_1w; 30 -> R.string.dv_1m; 91 -> R.string.dv_3m; else -> R.string.dv_6m })
    fun glyph(kind: String): Int = when (kind) { "Mac" -> R.drawable.ic_laptop; "iPad" -> R.drawable.ic_tablet; else -> R.drawable.ic_smartphone }
    fun draw() {
        list.removeAllViews()
        // CONNECT A DEVICE (iOS DevicesView's first section): its QR read by the camera; any other code keeps the camera looking
        val busyLink = stage == LinkStage.SEEKING || stage == LinkStage.GIVING
        val connectRow = c.settingsRow(null, 0, c.getString(R.string.dv_link), chevron = false, words = if (busyLink) MT.gray else MT.blue) {
            if (busyLink) return@settingsRow
            key = null; stage = LinkStage.IDLE; redraw()
            act.push { close ->
                scannerPage(act, close) { code ->
                    if (DeviceLink.parse(code) == null) return@scannerPage false
                    act.onMain { seek(code) }
                    true
                }
            }
        }.apply { (this as LinearLayout).addView(c.icon(R.drawable.ic_qr_scan, if (busyLink) MT.gray else MT.blue), 0, lp(dp(22), dp(22)).apply { marginEnd = dp(12) }) }
        list.section(null, c.getString(R.string.dv_link_footer), *listOfNotNull(connectRow, linkRow()).toTypedArray())
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
    redraw = { draw() }
    val page = settingsPage(act, c.getString(R.string.devices), onBack) { list = this; draw() }
    // the list is read again each time the page comes back on screen (iOS onAppear)
    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { draw() }
        override fun onViewDetachedFromWindow(v: View) {}
    })
    return page
}
