package quest.montana.app

import android.content.Context
import android.graphics.Color
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * THE LIVE DRAFT (iOS E2E.sendDraftWord / handleMeshDraft, LiveDraftBubble): the words in my field appear on their screen as
 * I type them — a grey bubble at the feed's foot with the caret where I hold it — and theirs on mine. The word is
 * «⁣mtdraft:» + base64 {t, c, n[, rm][, ck]}: the text, the caret, the moment it was said (strictly growing), the letter it
 * answers, «ck» for a checkpoint of a closing chat. It rides the signal lane (at most four a second, the ending always);
 * an empty text is the ending. It speaks and is heard only in a chat whose Money Flow is on (MoneyFlow, iOS 03.10).
 */
object LiveDraft {
    const val MARK = "⁣mtdraft:"
    class Draft(val text: String, val caret: Int, val replyMid: String)

    private val said = HashMap<String, String>()        // the last text of mine standing on their screen
    private val sentAt = HashMap<String, Long>()
    private val pending = HashMap<String, Runnable>()
    private val heardAt = HashMap<String, Long>()       // the moment of the newest word of theirs shown
    private val shown = HashMap<String, Draft>()
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; MainThread.post { ls.forEach { it() } } }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStamp = 0L
    /** The moment a word was said, strictly growing (iOS draftStamp): two words in one millisecond keep their order. */
    // ONE COUNTER FOR EVERY WORD THIS PHONE SAYS, BY THE NODE'S CLOCK (iOS draftStamp, E2E 1013): the draft's «n» and the presence «T» in one row
    private fun stamp(): Long = Presence.saidMs()

    // ── what I say ──
    /** «d» — the doors alive for me, in the one builder of the word (iOS sendDraftWord 474-475): the peer picks the first we share. */
    private fun doors(body: JSONObject) = body.apply { Signal.aliveHosts().joinToString(",").takeIf { it.isNotEmpty() }?.let { put("d", it) } }
    /** The field as it stands; an empty text ends the draft (and is always said, whatever the switch). */
    fun say(ref: String, text: String, caret: Int = -1, replyMid: String = "", checkpoint: Boolean = false) {
        val ending = text.isEmpty()
        // THE GATE OF THE LIVE WORD, NAMED WHEN IT CLOSES (iOS draft_gate, MontanaConversation.swift:4342-4351 at 2155; atom
        // 4a13e35a76e9): a closed gate looked exactly like a broken feature -- nothing on their screen, nothing in the record.
        // LIVE CHAT LIVES ONLY IN THE MONEY FLOW, AND ALWAYS THERE (iOS sendDraftWord `guard MTApps.flowOn(peer)`, the author's word
        // 03.10: «only in the Money Flow game, out of the ordinary chats»): the gates are this chat's flow and the block. Folded:
        // a minute of typing leaves one line, not two hundred.
        if (!ending && !MoneyFlow.on(ref)) { Diary.folded("draft_gate", "flow=off to=" + ref.take(10), 30_000, ref); return }
        if (PeerSafety.isBlocked(ref)) { if (!ending) Diary.folded("draft_gate", "blocked=1 to=" + ref.take(10), 30_000, ref); return }
        if (!checkpoint && said[ref] == text) return        // the wire already says exactly this
        if (ending && said[ref] == null) return              // nothing of mine stands there
        said[ref] = text
        pending.remove(ref)?.let { h.removeCallbacks(it) }
        val now = System.currentTimeMillis()
        val due = 250 - (now - (sentAt[ref] ?: 0))
        val speak = Runnable {
            sentAt[ref] = System.currentTimeMillis(); pending.remove(ref)
            val body = doors(JSONObject().put("t", text).put("c", caret).put("n", stamp()))
            if (replyMid.isNotEmpty()) body.put("rm", replyMid)
            if (checkpoint) body.put("ck", 1)
            Signal.post(ref, MARK + Base64.encodeToString(body.toString().toByteArray(), Base64.NO_WRAP))
            if (ending) said.remove(ref)
        }
        // four a second fit the node's window; the ending is never held back
        if (ending || due <= 0) speak.run() else { pending[ref] = speak; h.postDelayed(speak, due) }
    }

    // ── the daily links (iOS sendLinkWord, askLinkWord) ──
    private val linkSaidAt = HashMap<String, Long>()
    /**
     * OUR DAILY LINK TO A CORRESPONDENT (iOS sendLinkWord), so they can introduce us to a friend the way they share their own
     * code: said when a chat is opened, at most once a correspondence per six hours (the link lives a day), and at their ask at
     * once. It rides the draft word with the words of mine already standing there — an empty text would end a draft.
     */
    fun sayLink(ref: String, force: Boolean = false) {
        if (PeerSafety.isBlocked(ref) || Book.secret(ref) == null) return
        if (!force && System.currentTimeMillis() - (linkSaidAt[ref] ?: 0L) < 6 * 3600_000L) return
        val (link, born) = MontanaCard.currentShortWithBorn(Book.ctx) ?: return
        linkSaidAt[ref] = System.currentTimeMillis()
        val body = doors(JSONObject().put("t", said[ref] ?: "").put("c", -1).put("n", stamp()).put("rl", link).put("rb", born))
        Signal.post(ref, MARK + Base64.encodeToString(body.toString().toByteArray(), Base64.NO_WRAP))
    }
    /** «Hand me your link» (iOS askLinkWord, share contact): a peer of this build answers with its link at once. */
    fun askLink(ref: String) {
        if (Book.secret(ref) == null) return
        val body = doors(JSONObject().put("t", said[ref] ?: "").put("c", -1).put("n", stamp()).put("rq", 1))
        Signal.post(ref, MARK + Base64.encodeToString(body.toString().toByteArray(), Base64.NO_WRAP))
    }
    /**
     * THE DAILY LINK, PRELOADED (iOS E2E.sendLinkIfNeeded, MontanaE2E.swift:1467-1478; the author's word 15.09: share contact
     * must not wait): my current short link rides to a correspondent as a silent durable letter — the draft word's shape, by
     * the queue, not the live lanes (iOS sendDraftWord durable, 478-482) — once per link per peer; the receiver keeps it a day.
     */
    fun sayLinkIfNeeded(ref: String) {
        if (PeerSafety.isBlocked(ref) || Book.secret(ref) == null) return
        val (link, born) = MontanaCard.currentShortWithBorn(Book.ctx) ?: return
        val key = "linkSent." + ref
        if (Prefs.str(key, "") == link) return
        Prefs.setStr(key, link)
        val body = doors(JSONObject().put("t", said[ref] ?: "").put("c", -1).put("n", stamp()).put("rl", link).put("rb", born))
        Post.send(ref, Marks.mintMid(), MARK + Base64.encodeToString(body.toString().toByteArray(), Base64.NO_WRAP), quiet = true)
        android.util.Log.d("Montana", "link_tx durable to=" + ref.take(10))
    }
    /** Every correspondent gets the current link — launch, foreground, rotation; nothing if they have it (iOS sendLinkToAll 1479-1482). */
    fun sayLinkToAll() { for (ref in Book.refs()) sayLinkIfNeeded(ref) }

    // ── what I hear ──
    /** A draft word of theirs off the lane. true — a keystroke said now (it lights «typing…»). */
    fun hear(ref: String, word: String): Boolean {
        val j = runCatching { JSONObject(String(Base64.decode(word.removePrefix(MARK), Base64.DEFAULT), Charsets.UTF_8)) }.getOrNull() ?: return false
        j.optString("d").takeIf { it.isNotEmpty() }?.let { Signal.notePeerDoor(ref, it) }   // the doors they ask (iOS handleMeshDraft 1032)
        val n = j.optLong("n", 0)
        if (n > 0) { if (n <= (heardAt[ref] ?: 0)) return false; heardAt[ref] = n }   // an older word is an echo of the road
        // THEIR DAILY LINK, handed to us to hand on (iOS MTPeerLinks.note); their ask for mine is answered at once (iOS rq)
        j.optString("rl").takeIf { Meeting.invite(it) != null }?.let { PeerLinks.note(ref, it, j.optDouble("rb", System.currentTimeMillis() / 1000.0)) }
        if (j.optInt("rq") == 1) MainThread.post { sayLink(ref, force = true) }
        val state = j.has("rl") || j.has("rq") || j.has("ab") || j.has("al")   // a word of state, never a keystroke (iOS draftStateKeys)
        // THE REFUSAL IS NAMED (iOS draft_gate / draft_shown, MontanaE2E.swift:1059-1080 at 2155; atom 4a13e35a76e9): the word
        // arriving was written down, the word SHOWN was not, nor the reason a word showed nothing. Folded by the peer.
        // MY MONEY FLOW IS OFF (iOS handleMeshDraft `guard MTApps.flowOn(peer)`): their door and link above are noted -- those are
        // not live chat -- but their words show nothing and light no «typing…»; the peer's own coin decides whether they speak at all
        if (!MoneyFlow.on(ref)) { if (!state && j.optString("t").isNotEmpty()) Diary.folded("draft_gate", "flow=off from=" + ref.take(10), 60_000, ref); return false }
        val text = j.optString("t")
        // WORDS THAT ALREADY BECAME A LETTER ARE NOT A DRAFT (iOS draft_ghost): a word equal to a letter of theirs just landed
        val landed = text.isNotEmpty() && Book.chat(SamePair.root(ref))?.msgs?.takeLast(5)?.any { !it.mine && it.text == text } == true
        if (text.isEmpty() || landed) shown.remove(ref) else {
            shown[ref] = Draft(text, j.optInt("c", -1), j.optString("rm"))
            Diary.folded("draft_shown", "len=" + text.length + " from=" + ref.take(10), 5_000, ref)
        }
        changed()
        val fresh = n == 0L || NodeClock.now() - n <= 7_000   // the draft's «n» is the node's already (iOS 1087: nodeNow - n <= typingWordLife)
        return text.isNotEmpty() && !landed && !j.has("ck") && !state && fresh
    }
    fun of(ref: String): Draft? = shown[ref]
    /**
     * ONE CHAT'S FLOW WENT OFF (iOS E2E.liveTypingSwitched, MontanaE2E.swift:433-438): the words of mine standing on their screen
     * are ended, and theirs leave mine -- that peer's alone; the other chats keep theirs.
     */
    fun flowSwitched(ref: String, on: Boolean) {
        android.util.Log.d("Montana", "live_switch " + (if (on) "on" else "off") + " to=" + ref.take(10))
        if (on) return
        if (!said[ref].isNullOrEmpty()) say(ref, "")
        if (shown.remove(ref) != null) changed()
    }
}

/**
 * THE MONEY FLOW OF A CHAT (iOS MTMoneyFlow, MTWalletCore.swift:653-669; the author's word 04.10.2026: «if my chat is turned with one
 * correspondent, it concerns only the chat with them»): each pair's conversation keeps its own turn, and live chat lives inside it
 * alone (iOS 03.10). OPEN WITHOUT COINS (the author's word 17.11.2026 «Поток без монет»): the turn mints nothing -- only the chain
 * mints (iOS, the author's word 29.10.2026) -- and the wallet stays closed.
 */
object MoneyFlow {
    private fun key(ref: String) = "moneyFlow." + SamePair.root(ref)   // a conversation's own key (iOS SeedScope «moneyFlow.», CopyPlan)
    fun on(ref: String): Boolean = ref.isNotEmpty() && Prefs.bool(key(ref), false)
    fun set(ref: String, v: Boolean) = Prefs.setBool(key(ref), v)
}

/**
 * THE MONEY FLOW BEGAN, A ROW OF THE CHAT (iOS MTMoneyFlowRow, MTRowLetter.swift:223-226; ChatStore.appendMoneyFlow 3348-3358; the
 * author's word 03.10.2026: «a system message that the game of money flow has begun, with one turn of the coin»): this phone's own
 * row, laid the moment the coin comes on, mine and read. COMPAT-LOCAL: it never leaves this phone; a copy arriving from outside is a
 * service word no build knows and is buried unread.
 */
object FlowRow {
    const val MARK = "\u200B\u200BMF:"   // COMPAT-LOCAL: this phone's own row, never on the wire
    fun of(text: String) = text.startsWith(MARK)
    /** Once in a minute at most: a coin flipped on and off and on again lays one row (iOS 3350). */
    fun lay(pipe: String) {
        val peer = SamePair.root(pipe)
        val now = System.currentTimeMillis()
        Book.edit(peer) { c ->
            c.msgs.lastOrNull()?.let { if (of(it.text) && now - it.at < 60_000) return@edit }
            c.msgs.add(Msg(Marks.mintMid(), MARK, true, now, state = 3))
        }
        android.util.Log.d("Montana", "money_flow begun peer=" + peer.take(10))
    }
}

/**
 * THE DAILY LINKS OUR CORRESPONDENTS HANDED US (iOS MTPeerLinks): each with the moment it was born; a link is shown only while
 * its day runs, rather than hand a friend a code that answers «spent».
 */
object PeerLinks {
    private const val KEY = "peerRdvLinks"
    private fun all(): JSONObject = DeviceVault.get(KEY)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
    @Synchronized fun note(ref: String, link: String, born: Double) {
        DeviceVault.set(KEY, all().put(ref, JSONObject().put("l", link).put("b", born)).toString().toByteArray(Charsets.UTF_8))
        android.util.Log.d("Montana", "link_rx from=" + ref.take(10))
    }
    /** The link, while its day runs. */
    fun fresh(ref: String): String? = all().optJSONObject(ref)
        ?.takeIf { System.currentTimeMillis() / 1000.0 - it.optDouble("b", 0.0) < 24 * 3600 }?.optString("l")?.ifEmpty { null }
    /** The last link they handed, whatever its day: the face their card wears on the node outlives the card's day (iOS
     * MTPeerLinks.any, MontanaE2E.swift:61, atom 796a76a34ae0). */
    fun any(ref: String): String? = all().optJSONObject(ref)?.optString("l")?.ifEmpty { null }
    /** A link whose card wears this seed's own face is mine, noted off the shared pipe (iOS MTPeerLinks.forget, 80a9490f). */
    @Synchronized fun forget(ref: String) {
        val o = all()
        if (o.remove(ref) == null) return
        DeviceVault.set(KEY, o.toString().toByteArray(Charsets.UTF_8))
        android.util.Log.d("Montana", "link_forget conv=" + ref.take(10) + " own card")
    }
    fun wipe() = DeviceVault.delete(KEY)
    /** The links and the moments they were born, for a copy (iOS MTPeerLinks, MontanaE2E.swift 46-57: two sealed maps by conversation, «peerRdvLinks» and «.b»). */
    fun carried(): Pair<Map<String, String>, Map<String, Double>> {
        val o = all()
        val links = HashMap<String, String>()
        val born = HashMap<String, Double>()
        for (r in o.keys()) o.optJSONObject(r)?.let { e -> e.optString("l").takeIf { it.isNotEmpty() }?.let { links[r] = it; born[r] = e.optDouble("b", 0.0) } }
        return links to born
    }
    /** A copy laid (iOS SeedScope.unionKeys 6311): a correspondent's link held here stands, the copy adds the others. */
    @Synchronized fun lay(links: Map<String, String>, born: Map<String, Double>) {
        val o = all()
        for ((r, l) in links) if (l.isNotEmpty() && !o.has(r)) o.put(r, JSONObject().put("l", l).put("b", born[r] ?: 0.0))
        DeviceVault.set(KEY, o.toString().toByteArray(Charsets.UTF_8))
    }
}

/**
 * THEIR WORDS AS THEY TYPE THEM (iOS LiveDraftBubble): the correspondent's side, a grey bubble without a tail, the caret
 * «│» exactly where they hold it; the quote of the letter they answer above the words.
 */
fun Context.liveDraftBubble(d: LiveDraft.Draft, chat: Chat?): View = FrameLayout(this).apply {
    val caret = if (d.caret < 0 || d.caret > d.text.length) d.text.length else d.caret
    addView(vstack(Gravity.NO_GRAVITY) {
        background = rounded(Color.rgb(33, 33, 33), 17, Color.argb(90, 255, 255, 255))   // iOS: grey 0.13 with a light rim, no tail
        setPadding(dp(13), dp(8), dp(13), dp(8))
        if (d.replyMid.isNotEmpty()) chat?.msgs?.find { it.mid == d.replyMid }?.let { q ->
            addView(hstack {
                addView(View(context).apply { setBackgroundColor(Color.WHITE) }, lp(dp(3), dp(16)).apply { marginEnd = dp(6) })
                addView(text(letterWords(context, q), 12f, Color.argb(180, 255, 255, 255)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })   // USER-DATA
            }, lp().apply { bottomMargin = dp(2) })
        }
        addView(text(d.text.substring(0, caret) + "│" + d.text.substring(caret), 17f, Color.WHITE).apply { maxWidth = dp(260) })   // USER-DATA: their draft
    }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
}
