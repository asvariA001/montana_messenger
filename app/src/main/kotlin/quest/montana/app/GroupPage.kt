package quest.montana.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Switch

// ─────────────────────────── the group's page (iOS MontanaPeerInfoScreen for a group, MTGroupPageAsks) ───────────────────────────

/**
 * THE GROUP'S PAGE (iOS MontanaPeerInfoScreen for a group): its face, its name and its count; its people — this phone first, as
 * the creator or a member, then the people it sees (the owner's phone everyone it carries to, a member's the owner). The owner
 * adds people, takes a person out (asked first) and changes the name and the face («Edit»); a member leaves (asked first). Every
 * change is the owner's word to every phone of the group (Groups.renew, add, remove, leave), never one phone's alone.
 */
fun groupPage(act: MainActivity, key: String, onClose: () -> Unit): View {
    val c: Context = act
    val edit = c.text(c.getString(R.string.edit), 17f, SysColor.blue).apply { setPadding(c.dp(8), c.dp(10), c.dp(8), c.dp(10)) }
    edit.pressable { act.push { back -> groupEditPage(act, key, back) } }
    val bar = FrameLayout(c).apply {
        setPadding(dp(8), 0, dp(8), 0)
        addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); pressable(onClose) },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(edit, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
    }
    val body = c.vstack(Gravity.NO_GRAVITY) { setPadding(dp(16), 0, dp(16), dp(32)) }
    fun memberRow(face: View, name: String, role: String, onTap: (() -> Unit)?) = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(6))
        minimumHeight = dp(52)
        addView(face, lp(dp(40), dp(40)).apply { marginEnd = dp(12) })
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(c.text(name, 17f, Color.WHITE).apply { singleLineEllipsis() }, lp())   // USER-DATA: a person's name
            addView(c.text(role, 13f, MT.gray), lp())
        }, lp(0, WRAP, 1f))
        if (onTap != null) pressable(onTap)
    }
    fun actionRow(glyph: Int, words: String, red: Boolean, onTap: () -> Unit) = c.hstack {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(48)
        val tint = if (red) SysColor.red else SysColor.blue
        addView(c.icon(glyph, tint, 24), lp(dp(24), dp(24)).apply { marginEnd = dp(16) })
        addView(c.text(words, 17f, tint), lp(0, WRAP, 1f))
        pressable(onTap)
    }
    fun draw() {
        body.removeAllViews()
        val ch = Book.chat(key)
        val title = ch?.shown?.ifBlank { null } ?: c.getString(R.string.app_groups)
        val owns = Groups.owns(key)
        val channel = Groups.isChannel(key)
        edit.visibility = if (owns) View.VISIBLE else View.GONE
        body.addView(c.vstack(Gravity.CENTER_HORIZONTAL) {
            setPadding(0, dp(8), 0, dp(8))
            addView(c.peerFace(key, title, 100), lp(dp(100), dp(100)))
            gap(12)
            addView(c.text(title, 24f, Color.WHITE, bold = true, center = true), lp(WRAP, WRAP))   // USER-DATA: the group's title
            Groups.peopleLine(c, key)?.let { addView(c.text(it, 15f, MT.gray, center = true), lp(WRAP, WRAP)) }
        }, lp())
        // THE GROUP'S DESCRIPTION (iOS the group page's info above its people, stage R): the owner's words, read by all
        Groups.about(key).takeIf { it.isNotEmpty() && !channel }?.let { about ->
            body.section(null, null, c.vstack(Gravity.NO_GRAVITY) {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(c.text(about, 17f, Color.WHITE).apply { setTextIsSelectable(true) }, lp())   // USER-DATA: the owner's words
                addView(c.text(c.getString(R.string.gp_about), 13f, MT.gray), lp())
            })
        }
        val rows = mutableListOf<View>()
        // «Add Member» heads the people, as the reference folder's group page has it (iOS stage R)
        if (owns) rows += actionRow(R.drawable.ic_person_add, c.getString(R.string.gp_add), red = false) {
            act.push { close -> groupPickPage(act, close, {}, adding = key) }
        }
        // THE ROLES, AS THE REFERENCE'S MEMBER LIST NAMES THEM (iOS stage R.6): owner, admin, member; the owner's hold on a person names
        // or dismisses an administrator and takes the person out; an administrator's takes out a member who is none
        val mySeat = Groups.mySeat(key)
        val iAdmin = !owns && mySeat?.let { Groups.isAdmin(key, it) } == true
        // an administrator takes a member out only while the owner left it «Ban Users» (iOS adminCan .ban, G.6)
        val iBan = !owns && Groups.mayBan(key)
        // the custom title the owner gave stands instead of the role's own word (iOS rank, G.6)
        fun roleWord(seat: String?, role: Int) = seat?.takeIf { role != R.string.gp_member }?.let { Groups.rank(key, it) } ?: c.getString(role)
        rows += memberRow(c.avatar(SelfFace.load(c), Prefs.userName, 40), c.getString(R.string.gp_you),
            roleWord(mySeat, if (owns) R.string.gp_owner else if (iAdmin) R.string.gp_admin else R.string.gp_member), null)
        Groups.pagePeople(c, key).forEach { s ->
            val theOwner = !owns && s.seat == Groups.OWNER
            val admin = Groups.isAdmin(key, s.seat)
            val mayRemove = owns || (iBan && !theOwner && !admin)
            val deeds = mutableListOf<Pair<String, () -> Unit>>()
            // the admin's rights page, as the reference's «Promote» and «Edit Admin Rights» open it (iOS MTGroupAdminRightsPage, G.6)
            if (owns && !channel) {
                if (admin) {
                    deeds += c.getString(R.string.gp_edit_rights) to { act.push { back -> groupAdminRightsPage(act, key, s.seat, s.name, isNew = false) { back(); draw() } } }
                    deeds += c.getString(R.string.gp_dismiss) to { Groups.setAdmin(key, s.seat, false); draw() }
                } else deeds += c.getString(R.string.gp_promote) to { act.push { back -> groupAdminRightsPage(act, key, s.seat, s.name, isNew = true) { back(); draw() } } }
            }
            if (mayRemove) deeds += c.getString(R.string.gp_remove_from) to {
                AlertDialog.Builder(c).setTitle(R.string.gp_remove_q)
                    .setPositiveButton(R.string.gp_remove) { _, _ -> Groups.kick(key, s.seat); draw() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            val face = if (s.pipe.isEmpty()) c.avatar(null, s.name, 40) else c.peerFace(SamePair.root(s.pipe), s.name, 40)
            rows += memberRow(face, s.name, roleWord(s.seat, if (theOwner) R.string.gp_owner else if (admin) R.string.gp_admin else R.string.gp_member),
                if (deeds.isEmpty()) null else ({
                    AlertDialog.Builder(c).setTitle(s.name)   // USER-DATA: a person's name
                        .setItems(deeds.map { it.first }.toTypedArray()) { _, i -> deeds[i].second() }
                        .setNegativeButton(R.string.cancel, null).show()
                }))
        }
        val title0 = Book.chat(key)?.shown ?: ""
        // the owner's way out is the group's end for everyone (iOS «Delete Group», «Delete for All»)
        if (owns && !channel) rows += actionRow(R.drawable.ic_delete, c.getString(R.string.gp_dissolve), red = true) {
            AlertDialog.Builder(c).setTitle(c.getString(R.string.gp_dissolve_q, title0))   // USER-DATA: the group's title
                .setPositiveButton(R.string.gp_dissolve_all) { _, _ -> Groups.dissolve(key); onClose() }
                .setNegativeButton(R.string.cancel, null).show()
        } else if (!owns && !Groups.isOut(key)) rows += actionRow(R.drawable.ic_leave,
            c.getString(if (channel) R.string.gp_leave_channel else R.string.gp_leave_group), red = true) {
            // LEAVE GROUP (iOS «Are you sure you want to leave and delete %@?»): the owner is told, and the chat goes; a channel is left as before
            if (channel) AlertDialog.Builder(c).setTitle(R.string.gp_leave_channel_q)
                .setPositiveButton(R.string.gp_leave_channel) { _, _ -> Groups.leave(key); draw() }
                .setNegativeButton(R.string.cancel, null).show()
            else AlertDialog.Builder(c).setTitle(c.getString(R.string.gr_erase_q, title0))
                .setPositiveButton(R.string.gp_leave_group) { _, _ -> Groups.leaveAndErase(key); onClose() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        body.section(c.getString(R.string.gp_members), null, *rows.toTypedArray())
    }
    draw()
    val again: () -> Unit = { act.onMain { draw() } }
    body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { Book.listen(again); draw() }
        override fun onViewDetachedFromWindow(v: View) { Book.unlisten(again) }
    })
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(bar, lp(MATCH, dp(52)))
            addView(ScrollView(c).apply { addView(body) }, lp(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

/** THE OWNER'S EDIT (iOS the group page's edit mode → MTGroup.renew): the face and the name; «Done» tells every member. */
private fun groupEditPage(act: MainActivity, key: String, onBack: () -> Unit): View {
    val c: Context = act
    var face: ByteArray? = null
    val faceBox = FrameLayout(c)
    fun drawFace() {
        faceBox.removeAllViews()
        val bmp = face?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) faceBox.addView(c.avatar(bmp, "", 100), FrameLayout.LayoutParams(c.dp(100), c.dp(100)))
        else faceBox.addView(c.peerFace(key, Book.chat(key)?.shown ?: "", 100), FrameLayout.LayoutParams(c.dp(100), c.dp(100)))
    }
    drawFace()
    faceBox.pressable {
        act.pickPhoto { uri ->
            val image = uri?.let { SelfFace.decode(act, it) } ?: return@pickPhoto
            lateinit var close: () -> Unit
            close = act.overlay(cropPage(act, image) { jpeg -> close(); if (jpeg != null) { face = jpeg; drawFace() } })
        }
    }
    val name = EditText(c).apply {
        setText(Book.chat(key)?.shown ?: "")   // USER-DATA: the group's title
        hint = c.getString(R.string.gr_name)
        setTextColor(Color.WHITE); setHintTextColor(MT.gray)
        background = null
        isSingleLine = true
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
    }
    // THE DESCRIPTION, AS THE REFERENCE'S GROUP EDITOR HAS IT (iOS stage R): written by the owner, carried to every member (Groups.renew)
    val channel = Groups.isChannel(key)
    val about = EditText(c).apply {
        setText(Groups.about(key))   // USER-DATA: the owner's words about the group
        hint = c.getString(R.string.gp_about_hint)
        setTextColor(Color.WHITE); setHintTextColor(MT.gray)
        background = null
        minLines = 1; maxLines = 5
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
    }
    val done = c.text(c.getString(R.string.gp_done), 17f, SysColor.blue, bold = true).apply { setPadding(c.dp(8), c.dp(10), c.dp(8), c.dp(10)) }
    done.pressable {
        c.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(name.windowToken, 0)
        Groups.renew(key, name.text.toString(), face, if (channel) null else about.text.toString())
        onBack()
    }
    val bar = FrameLayout(c).apply {
        setPadding(dp(8), 0, dp(8), 0)
        addView(c.icon(R.drawable.ic_arrow_back_ios_new, Color.WHITE).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); pressable(onBack) },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
        addView(done, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
    }
    return FrameLayout(c).apply {
        setBackgroundColor(Color.BLACK)
        addView(c.chatGround(ChatWall.PAGE), FrameLayout.LayoutParams(MATCH, MATCH))
        addView(c.vstack(Gravity.NO_GRAVITY) {
            addView(bar, lp(MATCH, dp(52)))
            addView(ScrollView(c).apply {
                addView(c.vstack(Gravity.CENTER_HORIZONTAL) {
                    setPadding(dp(16), dp(8), dp(16), dp(32))
                    addView(faceBox, lp(dp(100), dp(100)))
                    gap(20)
                    addView(c.plate { addView(name, lp()) }, lp())
                    if (!channel) {
                        gap(20)
                        addView(c.plate { addView(about, lp()) }, lp())
                        addView(c.text(c.getString(R.string.gp_about_note), 13f, MT.gray).apply { setPadding(dp(16), dp(6), dp(16), 0) }, lp())
                        // THE ADMINISTRATORS, as the reference's editor lists them, with their count (iOS MTGroupAdminsPage, G.6)
                        gap(20)
                        val count = c.text("", 17f, MT.gray)
                        fun recount() { count.text = (1 + Groups.admins(key).size).toString() }   // USER-DATA: a count
                        recount()
                        val admins = c.navRow(R.drawable.ic_checkmark_shield, c.getString(R.string.gp_admins), count) {
                            act.push { back -> groupAdminsPage(act, key) { back(); recount() } }
                        }
                        addView(c.plate { addView(admins, lp()) }, lp())
                    }
                })
            }, lp(MATCH, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

// ─────────────────────────── the group's administrators (iOS MTGroupAdminsPage, MTGroupAdminRightsPage, G.6) ───────────────────────────

/** A row that opens a page: the glyph, the words, what it holds, the chevron (iOS NavigationLink in a List). */
private fun Context.navRow(glyph: Int?, words: String, trail: View?, onTap: () -> Unit) = hstack {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(16), dp(12), dp(12), dp(12))
    minimumHeight = dp(48)
    if (glyph != null) addView(icon(glyph, SysColor.blue, 24), lp(dp(24), dp(24)).apply { marginEnd = dp(16) })
    addView(text(words, 17f, Color.WHITE).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: may be a person's name
    if (trail != null) addView(trail, lp(WRAP, WRAP).apply { marginEnd = dp(6) })
    addView(icon(R.drawable.ic_chevron_right, MT.gray, 16), lp(dp(16), dp(16)))
    pressable(onTap)
}

/** The rights' rows in the reference's own words (iOS MTAdminRight.title). */
private fun rightWord(r: String) = when (r) {
    Groups.INFO -> R.string.gp_r_info
    Groups.DELETE -> R.string.gp_r_delete
    Groups.BAN -> R.string.gp_r_ban
    Groups.INVITE -> R.string.gp_r_invite
    else -> R.string.gp_r_pin
}

/**
 * THE GROUP'S ADMINISTRATORS, AS THE REFERENCE'S PAGE LISTS THEM (iOS MTGroupAdminsPage, G.6): the owner first, every administrator
 * with its custom title or its role, each opening its rights; «Add Admin» chooses among the members.
 */
private fun groupAdminsPage(act: MainActivity, key: String, onBack: () -> Unit): View {
    val c: Context = act
    val body = c.vstack(Gravity.NO_GRAVITY) {}
    fun role(seat: String, owner: Boolean) = c.text(Groups.rank(key, seat) ?: c.getString(if (owner) R.string.gp_owner_cap else R.string.gp_admin_cap), 17f, MT.gray)   // USER-DATA: the owner's custom title
    fun draw() {
        body.removeAllViews()
        val people = Groups.pagePeople(c, key)
        val admins = Groups.admins(key).mapNotNull { s -> people.firstOrNull { it.seat == s } }
        val rows = mutableListOf<View>()
        rows += c.hstack {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(48)
            addView(c.text(Prefs.userName, 17f, Color.WHITE).apply { singleLineEllipsis() }, lp(0, WRAP, 1f))   // USER-DATA: my name
            addView(role(Groups.OWNER, owner = true))
        }
        admins.forEach { p ->
            rows += c.navRow(null, p.name, role(p.seat, owner = false)) {
                act.push { back -> groupAdminRightsPage(act, key, p.seat, p.name, isNew = false) { back(); draw() } }
            }
        }
        body.section(null, null, *rows.toTypedArray())
        body.section(null, c.getString(R.string.gp_add_admin_note), c.navRow(R.drawable.ic_person_add, c.getString(R.string.gp_add_admin), null) {
            act.push { back -> groupAdminPickPage(act, key) { back(); draw() } }
        })
    }
    draw()   // a rights page or «Add Admin» closed: the list is drawn again (iOS onAppear tick)
    return settingsPage(act, c.getString(R.string.gp_admins), onBack) { addView(body, lp()) }
}

/** «Add Admin» (iOS MTGroupAdminPickPage): the members who are none yet, each opening the rights it would have. */
private fun groupAdminPickPage(act: MainActivity, key: String, onBack: () -> Unit): View {
    val c: Context = act
    val admins = Groups.admins(key)
    val people = Groups.pagePeople(c, key).filter { it.seat !in admins && it.seat != Groups.OWNER }
    return settingsPage(act, c.getString(R.string.gp_add_admin), onBack) {
        if (people.isNotEmpty()) section(null, null, *people.map { p ->
            c.navRow(null, p.name, null) {
                act.push { back -> groupAdminRightsPage(act, key, p.seat, p.name, isNew = true) { back(); onBack() } }
            }
        }.toTypedArray())
    }
}

/**
 * AN ADMINISTRATOR'S RIGHTS, AS THE REFERENCE'S PAGE KEEPS THEM (iOS MTGroupAdminRightsPage, G.6): the custom title, «What can this
 * admin do?» one switch a right, and «Dismiss Admin»; the checkmark names or renews the administrator, carried to every member.
 */
private fun groupAdminRightsPage(act: MainActivity, key: String, seat: String, name: String, isNew: Boolean, onBack: () -> Unit): View {
    val c: Context = act
    val taken = Groups.taken(key, seat).toMutableSet()
    val rank = EditText(c).apply {
        setText(Groups.rank(key, seat) ?: "")   // USER-DATA: the owner's custom title
        hint = c.getString(R.string.gp_admin)
        setTextColor(Color.WHITE); setHintTextColor(MT.gray)
        background = null
        isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
    }
    fun hideKeys() = c.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(rank.windowToken, 0)
    val done = c.icon(R.drawable.ic_check, Color.WHITE).apply {
        setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10))
        contentDescription = c.getString(R.string.gp_done)
        pressable { hideKeys(); Groups.setAdmin(key, seat, true, taken, rank.text.toString()); onBack() }
    }
    return settingsPage(act, c.getString(R.string.gp_rights), { hideKeys(); onBack() }, trailing = done) {
        section(null, null, c.text(name, 17f, Color.WHITE, bold = true).apply {   // USER-DATA: a person's name
            setPadding(dp(16), dp(12), dp(16), dp(12)); singleLineEllipsis()
        })
        section(c.getString(R.string.gp_rank), c.getString(R.string.gp_rank_note), rank)
        section(c.getString(R.string.gp_rights_q), null, *Groups.RIGHTS.map { r ->
            var ready = false   // the switch set to what is stored is not the owner's tap
            c.toggleRow(c.getString(rightWord(r))) { on ->
                if (!ready) return@toggleRow
                if (on) taken.remove(r) else taken.add(r)
            }.apply {
                setPadding(dp(16), dp(10), dp(16), dp(10))
                (0 until childCount).map { getChildAt(it) }.filterIsInstance<Switch>().firstOrNull()?.isChecked = r !in taken
                ready = true
            }
        }.toTypedArray())
        if (!isNew) section(null, null, c.text(c.getString(R.string.gp_dismiss), 17f, SysColor.red).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(48)
            pressable { hideKeys(); Groups.setAdmin(key, seat, false); onBack() }
        })
    }
}
