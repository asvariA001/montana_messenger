package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

// ─────────────────────────── the face (iOS MontanaSelfFace) ───────────────────────────

object SelfFace {
    private fun file(ctx: Context) = File(ctx.filesDir, "avatar.jpg")
    fun load(ctx: Context): Bitmap? = file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun clear(ctx: Context) { file(ctx).delete() }

    /** The picked photo, cut to the centre square and kept as a 512-pixel JPEG. */
    fun set(ctx: Context, uri: Uri): Boolean = try {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val face = Bitmap.createScaledBitmap(square, 512, 512, true)
        file(ctx).outputStream().use { face.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        true
    } catch (e: Exception) { false }
}

/** A page's top bar: an optional back mark on the left, a title, an optional mark on the right. */
fun Context.topBar(title: String? = null, onBack: (() -> Unit)? = null, trailing: View? = null) = FrameLayout(this).apply {
    setPadding(dp(8), 0, dp(8), 0)
    if (onBack != null) addView(icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10)); pressable(onBack)
    }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
    if (title != null) addView(text(title, 17f, Color.WHITE, bold = true, center = true),
        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    if (trailing != null) addView(trailing, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(52))
}

// ─────────────────────────── the name (iOS ProfileView) ───────────────────────────

class Profile(private val act: MainActivity, private val onboarding: Boolean, private val onFinish: () -> Unit) {
    val view = FrameLayout(act)
    private var name = Prefs.userName
    private lateinit var nameField: EditText
    private lateinit var faceSlot: FrameLayout
    private lateinit var photoRowText: TextView

    init { showEditor() }

    private fun showEditor() {
        val c = act
        view.removeAllViews()
        view.setBackgroundColor(Color.BLACK)   // the editor's own ground (iOS: black under the page), over the first screens' picture
        act.back = if (onboarding) null else ({ done() })
        val col = c.vstack(Gravity.NO_GRAVITY) {
            addView(c.topBar(trailing = c.icon(R.drawable.ic_check, MT.gold).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10)); pressable { done() }
            }.also { it.layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)) }))
            val body = c.vstack(Gravity.NO_GRAVITY) {
                setPadding(dp(16), 0, dp(16), dp(16))
                // ── the face ──
                faceSlot = FrameLayout(c)
                addView(faceSlot, lp(MATCH, dp(122)))
                refreshFace()
                gap(16)
                // ── the photo, in the page's own plate ──
                addView(c.plate {
                    val row = c.hstack {
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        addView(c.icon(R.drawable.ic_photo, Color.WHITE), lp(dp(26), dp(24)).apply { marginEnd = dp(12) })
                        photoRowText = c.text("", 17f, MT.gold)
                        addView(photoRowText, lp(0, WRAP, 1f))
                    }
                    row.pressable { pickFace() }
                    addView(row, lp())
                }, lp())
                refreshPhotoRow()
                gap(16)
                // ── first name + the call sign ──
                addView(c.plate {
                    nameField = EditText(c).apply {
                        setText(name); hint = c.getString(R.string.first_name)
                        setTextColor(Color.WHITE); setHintTextColor(MT.gray); background = null
                        isSingleLine = true
                        filters = arrayOf(InputFilter.LengthFilter(64))   // first name: 64 characters (iOS)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        addTextChangedListener(object : TextWatcher {
                            override fun afterTextChanged(s: Editable?) { name = s.toString(); refreshFace() }
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                        })
                    }
                    addView(nameField, lp())
                    divider()
                    val row = c.hstack {
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        addView(c.icon(R.drawable.ic_badge, Color.WHITE), lp(dp(26), dp(24)).apply { marginEnd = dp(12) })
                        addView(c.text(c.getString(R.string.choose_callsign), 17f), lp(0, WRAP, 1f))
                        addView(c.icon(R.drawable.ic_chevron_right, MT.gray))
                    }
                    row.pressable { showCallsigns() }
                    addView(row, lp())
                }, lp())
                if (onboarding) {
                    addView(c.text(c.getString(R.string.profile_onboarding_footer), 13f, MT.gray).apply {
                        setPadding(dp(16), dp(8), dp(16), 0)
                    })
                }
            }
            addView(ScrollView(c).apply { addView(body) }, lp(MATCH, 0, 1f))
        }
        view.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun refreshFace() {
        faceSlot.removeAllViews()
        val a = act.avatar(SelfFace.load(act), name, 110)
        a.pressable { if (SelfFace.load(act) == null) pickFace() else faceMenu(a) }
        faceSlot.addView(a, FrameLayout.LayoutParams(act.dp(110), act.dp(110), Gravity.CENTER))
    }

    private fun refreshPhotoRow() {
        photoRowText.text = act.getString(if (SelfFace.load(act) == null) R.string.choose_photo else R.string.change_photo)
    }

    /** No photo — the system photo picker; a photo — the system menu with two outcomes. */
    private fun faceMenu(anchor: View) {
        PopupMenu(act, anchor).apply {
            menu.add(0, 1, 0, R.string.change_photo)
            menu.add(0, 2, 1, R.string.remove_photo)
            setOnMenuItemClickListener {
                if (it.itemId == 1) pickFace() else { SelfFace.clear(act); refreshFace(); refreshPhotoRow() }
                true
            }
            show()
        }
    }

    private fun pickFace() = act.pickPhoto { uri ->
        if (uri != null && SelfFace.set(act, uri)) { refreshFace(); refreshPhotoRow() }
    }

    /** iOS MontanaCallsignPicker: every row of the table, searchable; the choice becomes the name. */
    private fun showCallsigns() {
        val c = act
        view.removeAllViews()
        act.back = { showEditor() }
        val rows = Callsign.all(c)
        val list = c.vstack(Gravity.NO_GRAVITY)
        fun fill(q: String) {
            list.removeAllViews()
            rows.filter { q.isEmpty() || it.lowercase().contains(q) }.forEach { row ->
                list.addView(c.hstack {
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    addView(c.text(row, 17f), lp(0, WRAP, 1f))
                    if (row == name) addView(c.icon(R.drawable.ic_check, MT.gold))
                    pressable { name = row; Prefs.userName = row; showEditor() }
                }, lp())
            }
        }
        val search = EditText(c).apply {
            background = c.rounded(MT.plate, 10); setTextColor(Color.WHITE); isSingleLine = true
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(MT.gray)
            compoundDrawablePadding = dp(8)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { fill(s.toString().trim().lowercase()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        fill("")
        view.addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.topBar(onBack = { showEditor() }))
            addView(search, lp().apply { setMargins(dp(16), 0, dp(16), dp(8)) })
            addView(ScrollView(c).apply { addView(list) }, lp(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** The person leaves here NAMED: no name written — the call sign lands in the name itself. */
    private fun done() {
        if (name.isBlank()) {
            val sign = Callsign.of(act, MontanaSeed.twin ?: "")
            if (sign.isNotEmpty()) name = sign
        }
        if (name.isNotBlank()) Prefs.userName = name.trim()
        act.back = null
        onFinish()
    }
}

// ─────────────────────────── the terms (iOS MontanaTermsGate) ───────────────────────────

/**
 * The terms. [onAgree] set — the gate after the identity: no way past without «I agree».
 * [onAgree] null — the page opened from the doors' footer (iOS: a sheet), closed by its back mark.
 */
fun termsGate(act: MainActivity, onAgree: (() -> Unit)?, onClose: (() -> Unit)? = null): View {
    val c = act
    fun header(res: Int) = c.text(c.getString(res).uppercase(), 13f, MT.gray).apply { setPadding(dp(16), dp(20), dp(16), dp(6)) }
    fun section(vararg lines: Int) = c.plate {
        lines.forEachIndexed { i, l ->
            if (i > 0) divider()
            addView(c.text(c.getString(l), 16f).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        }
    }
    fun link(iconRes: Int, label: String, intent: Intent) = c.hstack {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(c.icon(iconRes, MT.gold), lp(dp(24), dp(24)).apply { marginEnd = dp(12) })
        addView(c.text(label, 17f, MT.gold))
        pressable { runCatching { c.startActivity(intent) } }
    }
    val body = c.vstack(Gravity.NO_GRAVITY) {
        setPadding(dp(16), 0, dp(16), dp(16))
        addView(section(R.string.terms_intro, R.string.terms_agree_line), lp())
        addView(header(R.string.terms_abuse_h))
        addView(section(R.string.terms_abuse_1, R.string.terms_abuse_2, R.string.terms_abuse_3), lp())
        addView(header(R.string.terms_tools_h))
        addView(section(R.string.terms_tools_1, R.string.terms_tools_2, R.string.terms_tools_3, R.string.terms_tools_4), lp())
        addView(header(R.string.terms_reach_h))
        addView(c.plate {
            addView(link(R.drawable.ic_email, "contact@montana.quest", Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:contact@montana.quest"))), lp())
            divider()
            addView(link(R.drawable.ic_language, "https://montana.quest", Intent(Intent.ACTION_VIEW, Uri.parse("https://montana.quest"))), lp())
        }, lp())
    }
    return c.vstack(Gravity.NO_GRAVITY) {
        setBackgroundColor(Color.BLACK)
        addView(c.topBar(c.getString(R.string.terms_title), onBack = onClose))
        addView(ScrollView(c).apply { addView(body) }, lp(MATCH, 0, 1f))
        if (onAgree != null) addView(GoldButton(c, c.getString(R.string.i_agree), onAgree), lp().apply { setMargins(dp(20), dp(12), dp(20), dp(12)) })
    }
}

// ─────────────────────────── the sign lights up (iOS WelcomeView) ───────────────────────────

fun welcome(act: MainActivity, onFinish: () -> Unit): View {
    val c = act
    val logo = ImageView(c).apply { setImageResource(R.drawable.logo); scaleX = 0.6f; scaleY = 0.6f; alpha = 0f }
    val title = c.text("Montana", 40f, MT.gold, bold = true, center = true).apply {
        alpha = 0f; setShadowLayer(dp(18).toFloat(), 0f, 0f, MT.gold)
    }
    val col = c.vstack(Gravity.CENTER) {
        addView(c.glowingLogo(87, 260, 700).also { g -> g.removeViewAt(1); g.addView(logo, FrameLayout.LayoutParams(dp(87), dp(87), Gravity.CENTER)) },
            lp(MATCH, dp(200)))
        gap(22)
        addView(title)
    }
    logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).setInterpolator(DecelerateInterpolator()).start()
    title.animate().alpha(1f).setDuration(400).start()
    col.postDelayed(onFinish, 600)   // the splash dismisses fast (iOS: 0.6 s)
    return col
}

// ─────────────────────────── the main screen, as a new person first sees it ───────────────────────────

fun mainScreen(act: MainActivity, onForget: () -> Unit): View {
    val c = act
    val name = Prefs.userName
    val settings = c.icon(R.drawable.ic_settings, MT.gold).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
    settings.pressable {
        PopupMenu(c, settings).apply {
            menu.add(0, 1, 0, R.string.my_profile)
            menu.add(0, 2, 1, R.string.forget_device)
            setOnMenuItemClickListener {
                if (it.itemId == 1) act.showProfile()
                else AlertDialog.Builder(c).setTitle(R.string.forget_device)
                    .setPositiveButton(R.string.forget_device) { _, _ -> onForget() }
                    .setNegativeButton(R.string.back, null).show()
                true
            }
            show()
        }
    }
    val bar = FrameLayout(c).apply {
        setPadding(dp(16), 0, dp(8), 0)
        // the tabs moved up into the top bar (iOS 17.09): contacts and chats at its left, the settings at its right
        addView(c.hstack {
            addView(c.text(c.getString(R.string.contacts), 17f, MT.gray))
            gap(16)
            addView(c.text(c.getString(R.string.chats), 17f, Color.WHITE, bold = true))
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(settings, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.END))
    }
    return c.vstack(Gravity.NO_GRAVITY) {
        addView(bar, lp(MATCH, dp(52)))
        // The local room: stands in the list from the first moment.
        addView(c.hstack {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(FrameLayout(c).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(MT.gold) }
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
