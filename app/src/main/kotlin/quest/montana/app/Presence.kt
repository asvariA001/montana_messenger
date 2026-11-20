package quest.montana.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

// ─────────────────────────── the live lane and the presence ladder (iOS MontanaWakePush signal lane, E2E beacons, ChatStore ladder) ───────────────────────────

/**
 * THE SIGNAL LANE (iOS MontanaWakePush.postSignal / fetchSignals, epoch «chat»): a word that lives an instant — typing, «in
 * my chat», «in the app» — never takes a place in the letters' queue. It is sealed under the pipe's key of the minute as a
 * letter is, as peer‖0‖epoch‖0‖deflate(text), and posted to the doors' /signal under the conversation's daily label; the
 * other side holds a long question on /signal-fetch and hears it the millisecond it lands. A bare «box» on the lane says the
 * box holds a letter of ours — it is fetched at once.
 */
object Signal {
    fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)   // raw DEFLATE, as Apple's .zlib writes it (no header)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }
    private fun inflate(b: ByteArray): ByteArray? = runCatching {
        val i = Inflater(true); i.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(512)
        var guard = 0
        while (!i.finished() && guard++ < 1000) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; out.write(buf, 0, n) }
        i.end(); out.toByteArray()
    }.getOrNull()

    /** One word on the lane, at the door the other side asks (iOS postSignal): per-node stores do not talk to each other. */
    fun post(ref: String, text: String) = postWord(ref, "chat", text.toByteArray())

    /** A word of any epoch (iOS postSignal 3000-3054): «chat» for the live words, a call's own epoch for its words (Calls). */
    fun postWord(ref: String, epoch: String, payload: ByteArray) {
        val twin = MontanaSeed.twin ?: return
        val secret = Book.secret(ref) ?: return
        Thread {
            val body = twin.toByteArray() + byteArrayOf(0) + epoch.toByteArray() + byteArrayOf(0) + deflate(payload)
            val sealed = Wire.seal(Wire.bodyKey(secret, Wire.minute()), body) ?: return@Thread
            val cw = Wire.convW(secret, Wire.day())
            val req = JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("env", Base64.encodeToString(sealed, Base64.NO_WRAP))
            // ONE DOOR (iOS postSignal 3945-3949, OZ 1 p.2.3, the author's word 25277: «I am in the Montana network, and that must be
            // enough»): the word goes to this phone's own door; the machine there hands it to the machine where the other side listens.
            // No copy at another door, no guess of the peer's door. A call's word steps on to the next door only when the one before
            // refused or stayed silent -- one write at a time (iOS MTCallRoute.advance); a chat word knocks its one door.
            val first = signalDoor()
            if (first.isEmpty()) { Log.d("Montana", "sig_tx SKIP no-door"); return@Thread }
            val callWord = (epoch != "chat" && epoch != "letter") || Calls.busy()
            val doors = if (callWord) listOf(first) + callDoors().filter { it != first } else listOf(first)
            sendAt(doors, 0, ref, epoch, req)
        }.start()
    }
    private fun sendAt(doors: List<String>, i: Int, ref: String, epoch: String, req: JSONObject) {
        val door = doors.getOrNull(i) ?: return
        // ONE LANE PER DOOR (iOS 3031-3053): the word waits its turn; a chat word of this conversation replaces the one still waiting
        onSignalLane(door, if (epoch == "chat") ref else null) {
            var ok = false
            try {
                // A WORD THAT WAITED PAST ITS DOOR'S DEATH dials it only as the minute's probe (iOS postSignal 3967-3971, OZ 1 p.4):
                // the call's route and the room's words alike -- not 29 dials of dead doors beside the media
                if (!DoorBook.mayDial(door)) { Log.d("Montana", "sig_tx SKIP dead door=" + host(door)); return@onSignalLane }
                val (code, _) = onLane { Wire.post(door, "/signal", req, 8000, it) }
                ok = code == 200
                if (ok) doorAnswered(door) else if (code != CUT) doorFailed(door, code)
            } finally {
                leaveSignalLane(door)
                if (!ok && i + 1 < doors.size) sendAt(doors, i + 1, ref, epoch, req)
            }
        }
    }

    /**
     * THE SIGNAL LANE OF A DOOR IS ONE LANE (iOS 2954-2995, 29.09; T1 23:31:45Z on 1988: twenty-five posts in flight the moment a tunnel
     * came up, 912 goroutines and 40 MB in 2.4 s, the extension killed): a door takes two words at a time; the rest wait in the order they
     * were said, a newer chat word of a conversation replacing the older one still waiting, and beyond sixteen the oldest chat word gives
     * way. Under the lock only the two tables move; the word itself runs after it.
     */
    private const val LANE_WIDTH = 2
    private const val LANE_DEPTH = 16
    private val laneBusy = HashMap<String, Int>()
    private val laneQueue = HashMap<String, ArrayDeque<Pair<String?, () -> Unit>>>()

    private fun onSignalLane(door: String, key: String?, run: () -> Unit) {
        synchronized(laneBusy) {
            if ((laneBusy[door] ?: 0) >= LANE_WIDTH) {
                val q = laneQueue.getOrPut(door) { ArrayDeque() }
                if (key != null) q.removeAll { it.first == key }
                if (q.size >= LANE_DEPTH) { val i = q.indexOfFirst { it.first != null }; if (i >= 0) q.removeAt(i) }
                q.addLast(key to run)
                return
            }
            laneBusy[door] = (laneBusy[door] ?: 0) + 1
        }
        Thread { run() }.start()
    }

    private fun leaveSignalLane(door: String) {
        val next = synchronized(laneBusy) {
            laneBusy[door] = maxOf(0, (laneBusy[door] ?: 1) - 1)
            val q = laneQueue[door]
            if (q != null && q.isNotEmpty()) { laneBusy[door] = (laneBusy[door] ?: 0) + 1; q.removeFirst().second } else null
        }
        next?.let { Thread { it() }.start() }
    }

    // ── the doors' life (iOS WakePush baseFailed 406-430, doorSpoke 432-439; the count and the rest are the one book's, DoorBook) ──
    /**
     * No answer, or the door's own failure: two in a row and it rests a minute, then two, four, eight at most; a refusal is an answer.
     * The health round's silence weighs two (iOS verifyRound 157). A door's death unelects it here, whichever road heard the silence --
     * a base, or a node's channel to the same endpoint (matched by the book's key).
     */
    fun doorFailed(door: String, code: Int, weight: Int = 1) {
        if (code != -1 && code < 500) { doorAnswered(door); return }
        val rest = DoorBook.silent(door, weight) ?: return
        val key = DoorBook.door(door)
        val died = synchronized(this) { (elected?.let { DoorBook.door(it) == key } == true).also { if (it) elected = null } }
        Log.d("Montana", "sig_door dead " + key + " code=" + code + " — not asked for " + rest + "s")
        if (died) {
            Prefs.remove(ELECTED)
            Log.d("Montana", "door_elected " + DoorBook.host(door) + " died — the next answering door by the book")
            // THE STANDING QUESTION LEAVES A DEAD DOOR NOW (iOS baseFailed 433-438): the lane is cut and asks the new door the same instant
            cut("door " + DoorBook.host(door) + " dead — the standing question leaves it")
        }
    }
    /** Any answer proves the door alive: its silences, its rest and the streak are forgiven in the one book (iOS doorSpoke 432-439). */
    fun doorAnswered(door: String) {
        if (!DoorBook.answered(door)) return
        // ONE REVIVAL IS ONE PIECE OF NEWS, NOT ONE PER ASKER (iOS doorRevived 449-455)
        val key = DoorBook.door(door)
        val now = System.currentTimeMillis()
        val fresh = synchronized(revivedAt) { (revivedAt[key]?.let { now - it < 10_000L } == true).also { if (!it) revivedAt[key] = now } }
        if (fresh) return
        Log.d("Montana", "door_alive " + DoorBook.host(door) + " answered after silence — the queue drains now")
        Wire.forgetGone()   // a door that was silent answers: what it holds is new knowledge (iOS forgetGoneChunks)
        // A DOOR THAT ANSWERS AFTER SILENCE DRAINS THE QUEUE AT ONCE (iOS drainOnDoorAlive): the letters that waited for it leave now
        if (draining.compareAndSet(false, true)) Thread { try { Post.flush() } finally { draining.set(false) } }.start()
    }
    private val revivedAt = HashMap<String, Long>()
    private val draining = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Alive for me (iOS MTDoorBook.alive): not resting after its own silences -- the health round's silence is one of them. */
    fun doorAlive(door: String) = DoorBook.alive(door)
    /** THE ORDER OF A WALK (iOS MTDoorBook.ordered, OZ 1 p.3): the door placed by its last word, the resting last. */
    fun byProof(doors: List<String>): List<String> = DoorBook.ordered(doors)
    private fun host(door: String) = android.net.Uri.parse(door).host ?: door
    /** The doors alive for me, in the network's order — what I name to the peer with every word (iOS signalDoors 525-527). */
    fun aliveHosts(): List<String> = Doors.ordered("signal").filter { doorAlive(it) }.map { host(it) }

    // ═══ THE DEVICE'S DOOR -- ONE OWNER (iOS deviceDoor, MontanaWakePush 363-412, OZ 1 p.2 N-6; the author's words 25250 and 25256, 19.11.2026) ═══
    // Two places chose a door of this device, each by its own rule: the elected door of the writes (1638) and the signal lane's door
    // (15.2) -- and neither asked where the device already stood. iOS T3 on Wi-Fi, 19.11.2026 22:03:54 UTC: its channel stood at
    // api.montana.quest, every word sent there came back 200, and every refusal came from the choosers that went on dialling door.*.
    // The law: the door of the device's standing connection IS the device's door. deviceDoor() alone chooses it and `elected` alone
    // keeps it (kept for the next life); the writes, the signal lane and the walks read it and choose nothing. It stands while the one
    // book holds it alive and the device stands at it; a connection standing at another door moves it there; with no connection
    // standing it stays while it answers; dead, it falls to the next answering door by the book (DoorBook.ordered).
    // Reads that look for what ANOTHER phone wrote (the box, the chunks) keep sweeping every store: the other phone has a door of its own.
    private const val ELECTED = "mt.door.elected"
    @Volatile private var elected: String? = null
    fun elected(): String? = elected ?: Prefs.str(ELECTED, "").ifEmpty { null }?.also { elected = it }

    /** The device's door (iOS deviceDoor 384-405): the book, the channels and the kept choice are read outside the lock. */
    fun deviceDoor(): String {
        val order = Doors.ordered("signal")
        val living = DoorBook.ordered(order).filter { DoorBook.alive(it) }
        val at = Channels.heldLabels().map { DoorBook.host(it.removePrefix("wss/")) }.toSet()
        val standing = living.filter { host(it) in at }
        val kept = elected()
        val (chosen, was) = synchronized(this) {
            val was = elected ?: kept
            val c = if (was != null && was in living && (standing.isEmpty() || was in standing)) was
                else standing.firstOrNull() ?: living.firstOrNull() ?: was?.takeIf { it in order } ?: order.firstOrNull() ?: ""   // nothing answers: no door is exchanged for another silent one
            if (c.isNotEmpty()) elected = c
            c to was
        }
        if (chosen.isNotEmpty() && chosen != was) {
            Prefs.setStr(ELECTED, chosen)
            Log.d("Montana", "door_elected " + host(chosen) + " by=" + (if (chosen in standing) "standing" else "book"))
        }
        return chosen
    }

    /**
     * The doors of a write (iOS base 407-412 over the capability's doors): the device's door first while its node holds the capability;
     * else the first living door that does -- read from the map, never kept.
     */
    fun writeOrder(cap: String, everyDoor: Boolean = false): List<String> {
        val all = if (everyDoor) Doors.bases(cap) else Doors.ordered(cap)
        val mine = deviceDoor()
        val first = if (mine in all) mine else all.firstOrNull { doorAlive(it) } ?: all.firstOrNull() ?: return all
        return listOf(first) + all.filter { it != first }
    }

    // ═══ THE LANE READS THE DEVICE'S DOOR (iOS signalDoor 492-494, OZ 1 p.2 N-6) ═══
    // The choice moved to its one owner, deviceDoor(); a door's death (doorFailed) and a standing connection move it there, and this
    // lane chooses nothing.
    fun signalDoor(): String = deviceDoor()

    // ONE DOOR, WRITTEN AND READ (iOS MontanaWakePush 520-523, OZ 1 p.2.2-2.3, the author's words 25277, 25279, 25281): a phone writes
    // its words at its own door (signalDoor) and reads at its own door; the machine of that door hands a word in one hop to the machine
    // where the other side listens (montana-node-store.py, «heard here»). The peer's door is never named, never learnt and never chosen.
    /** THE DOORS OF A CALL (iOS callDoors): the one book's order -- answered newest first, unasked, faltering, resting last. */
    fun callDoors(): List<String> = DoorBook.ordered(Doors.ordered("signal"))

    // ── the lanes (iOS sigSessions and postLane: networkPathChanged 520-537, cutStandingQuestion 538-551) ──
    private const val CUT = -2
    private val asking = HashSet<java.net.HttpURLConnection>()   // the long questions in flight (iOS sigSessions)
    private val laneGen = java.util.concurrent.atomic.AtomicInteger()
    private val posting = HashSet<java.net.HttpURLConnection>()   // the words in flight (iOS postLane)
    private val postGen = java.util.concurrent.atomic.AtomicInteger()
    /** A post on the lane: cut by a path change, it leaves again at once on the fresh path — and the cut teaches nothing of the door. */
    private fun onLane(ask: (MutableSet<java.net.HttpURLConnection>) -> Pair<Int, String?>): Pair<Int, String?> {
        repeat(2) {
            val gen = postGen.get()
            val got = ask(posting)
            if (gen == postGen.get()) return got
        }
        return CUT to null
    }
    /**
     * THE STANDING QUESTION IS CUT (iOS cutStandingQuestion 538-551) — one move for two facts: the network path changed, the lane's
     * door died. Every question in flight fails this instant, and each conversation asks again at once, at its door as it stands now.
     * The words in flight are cut by a path change only (iOS networkPathChanged 529-537 swaps the post lane): a word on its way to
     * a door that just died is that door's own answer to wait for.
     */
    private fun cut(why: String, posts: Boolean = false) {
        laneGen.incrementAndGet()
        if (posts) postGen.incrementAndGet()
        val held = synchronized(asking) { asking.toList().also { asking.clear() } } + if (posts) synchronized(posting) { posting.toList().also { posting.clear() } } else emptyList()
        Thread { for (c in held) runCatching { c.disconnect() } }.start()
        Log.d("Montana", "sig_lane " + why + " — asking again now")
    }
    /** A CALL'S BIRTH CUTS THE STANDING QUESTION TOO (iOS MontanaCall.setState 4405-4412, cutStandingQuestion
     * MontanaWakePush 534-547, atom cf9b083f5567): the long question of a phone at rest stands to its own deadline while
     * the call needs the short one — measured on iOS 13.09 15:20, the caller stood deaf fifteen seconds without it. */
    fun cutForCall() = cut("call born")

    private var pathWatched = false
    /**
     * THE NETWORK PATH CHANGED (iOS 548-566): dead on one path says nothing of another. THE NETWORK'S OWN BOOK (iOS MontanaApp
     * 1329-1334, OZ 1 p.5): on every carrying path the book of this network is open -- the interface, the transport, the address
     * families and the gateways name it, under the diary's salted label -- one name in every process of this phone (N-2); a network
     * returned to opens the book it left, and a book already open stays, before anyone knocks.
     */
    fun watchPath(c: Context) {
        if (pathWatched) return
        pathWatched = true
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(n: android.net.Network, lp: android.net.LinkProperties) {
                    DoorBook.networkChanged(netName(cm, n, lp))
                }
                // THE DOORS ARE JUDGED AGAIN ONCE, ON THE FIRST SATISFIED PATH AFTER THE NETWORK CHANGED (iOS 1989, MontanaNetWitness):
                // not on the change itself — a path lost and found again inside a second judged every door twice for nothing
                override fun onAvailable(n: android.net.Network) {
                    cm.getLinkProperties(n)?.let { DoorBook.networkChanged(netName(cm, n, it)) }
                    val sig = n.toString()
                    if (sig == judgedOn) return
                    judgedOn = sig
                    forgive("path available")
                    // THE TLS RELAY ROAD, ONCE PER NETWORK, THE PROCESS'S FIRST NETWORK TOO (iOS MontanaApp 1338-1341, OZ 1 p.7, N-3)
                    if (!Calls.busy()) CallLine.probeRelays()
                }
                override fun onLost(n: android.net.Network) = cut("path lost — the questions on its socket are cut", posts = true)
            })
        }
    }
    /** The network's name for its book (iOS: the interface set and the tunnel, the families, the gateways, under MontanaLog.label). */
    private fun netName(cm: android.net.ConnectivityManager, n: android.net.Network, lp: android.net.LinkProperties): String {
        val caps = cm.getNetworkCapabilities(n)
        val kind = when {
            caps == null -> "?"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            else -> "other"
        }
        val v4 = lp.linkAddresses.any { it.address is java.net.Inet4Address }
        val v6 = lp.linkAddresses.any { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress }
        val fam = (if (v4) "v4" else "-") + (if (v6) "v6" else "-")
        val gws = lp.routes.filter { it.isDefaultRoute && it.hasGateway() }.mapNotNull { it.gateway?.hostAddress }.sorted()
        return Diary.label((listOf(kind + "/" + (lp.interfaceName ?: ""), fam) + gws).joinToString(" "))
    }
    @Volatile private var judgedOn: String? = null
    private fun forgive(why: String) {
        Wire.forgetGone()   // a new path may reach a store the old one could not
        cut(why + " — the network's own book, every lane cut", posts = true)   // the questions in flight stood on the old path's socket (iOS 520-537)
    }

    /**
     * The words that landed for `ref`: opened by the minute (this one and its neighbours), unpacked, handed on by their epoch —
     * «chat» to the presence, any other to the call it names; «box» hints fetch the box.
     */
    private fun open(ref: String, envs: org.json.JSONArray) {
        // the seed's own lane carries the node's «box» alone: a twin's letter lies, the pickup goes at once (iOS THE STORE'S HINT)
        if (ref == Twin.PIPE) {
            if ((0 until envs.length()).any { envs.optString(it) == "box" }) { Log.d("Montana", "twin_lane box"); Thread { Post.fetchWhole() }.start() }
            return
        }
        val secret = Book.secret(ref) ?: return
        for (k in 0 until envs.length()) {
            val e = envs.optString(k)
            if (e == "box") { Thread { Post.fetchWhole() }.start(); continue }   // the node swears a letter lies: every day is asked (iOS fetchBoxNow)
            val sealed = runCatching { Base64.decode(e, Base64.DEFAULT) }.getOrNull() ?: continue
            val w0 = Wire.minute()
            val plain = Wire.openWalked(sealed, secret, w0, Book.healing(ref))?.plain ?: continue   // every walk the pipe gives (iOS bodyKeys)
            Meeting.firstDone(ref)   // a word under the pipe proves the other side holds it, on this road too (iOS 1961)
            val a = plain.indexOf(0); if (a < 0) continue
            val b = (a + 1 until plain.size).firstOrNull { plain[it] == 0.toByte() } ?: continue
            val epoch = String(plain, a + 1, b - a - 1, Charsets.UTF_8)
            val words = inflate(plain.copyOfRange(b + 1, plain.size))?.takeIf { it.isNotEmpty() } ?: continue
            // THE LETTER ON THE LANE (iOS fetchSignals epoch «letter», MontanaWakePush.swift:4109-4126, atom 2ca7a3200d4d): the
            // correspondent's envelope, the one the box holds, sealed once more for the lane. It lands by the box's own road -- opened by
            // the minute, the fields, the one incoming door (Post.fromChannel) -- so the box's copy, read later, is buried as a repeat.
            // An older build reads the epoch as a call's it does not hold and buries it unread; the letter still reaches it by the box.
            if (epoch == "letter") {
                val letter = Book.openBoxedLetter(words, ref, System.currentTimeMillis() / 1000)   // heard by the heal (iOS openLetterEnvelope, 76f5eeb7)
                if (letter == null) { Log.d("Montana", "sig_rx OPEN-FAIL lane=letter"); continue }
                Log.d("Montana", "sig_apply lane=letter mid=" + Wire.fields(letter)[0].take(8) + " from=" + ref.take(10))
                Thread { Post.fromChannel(ref, letter) }.start()   // a long letter's cargo is fetched off the lane's own question
                continue
            }
            if (epoch == "chat") Presence.hear(ref, String(words, Charsets.UTF_8)) else Calls.hear(ref, epoch, words)
        }
    }

    /**
     * THE LAST WORD OF EVERY PEER, IN ONE QUESTION (iOS sweepPresence, WakePush 3180-3269; the author's word 11.09: «T2 does not see
     * that T1 was online until the chat is opened»): the lane hands over a minute of words, the node keeps each side's last sealed word
     * for the box's term (/signal-last), and this asks for all of them at once — at the app's return and on the coin. A swept word is a
     * stamp, never a live word (Presence.stamp). It opens by the node's own moment (Wire.openBoxed): iOS opens it by the minute of the
     * question, so there a word older than two minutes never opens (HANDOVER, session 7).
     */
    fun sweep(quiet: Boolean = false) {
        val twin = MontanaSeed.twin ?: return
        val q = ArrayList<JSONObject>()
        val chatOf = HashMap<String, String>()
        val w0 = Wire.day()
        for (ref in Book.refs()) {
            val secret = Book.secret(ref) ?: continue
            val h = Book.healing(ref)   // a heal in flight: the healed walk's label too (iOS subscriptions under convWs, 76f5eeb7)
            for (w in (w0 - 1)..w0) for (cw in Wire.convWs(secret, w, h)) {
                chatOf[cw] = ref
                q.add(JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)))
            }
        }
        if (q.isEmpty()) return
        val best = HashMap<String, Pair<String, Long>>()   // per conversation, the newest word across the doors (env, the node's second)
        var doors = 0
        for (door in Doors.ordered("signal").filter { doorAlive(it) }) {
            for (start in q.indices step 128) {
                val part = org.json.JSONArray(q.subList(start, minOf(start + 128, q.size)))
                val (code, text) = Wire.post(door, "/signal-last", JSONObject().put("q", part), 6000)
                // a door that does not know the question answers 404 and is skipped (iOS [P2P-COMPAT])
                val rows = (if (code == 200 && text != null) runCatching { JSONObject(text).optJSONArray("last") }.getOrNull() else null) ?: continue
                doors++
                for (i in 0 until rows.length()) {
                    val r = rows.optJSONObject(i) ?: continue
                    val chat = chatOf[r.optString("conv")] ?: continue
                    val env = r.optString("env"); val at = r.optLong("at")
                    if (env.isNotEmpty() && (best[chat]?.second ?: 0L) < at) best[chat] = env to at
                }
            }
        }
        var words = 0
        for ((chat, w) in best) {
            if (Book.secret(chat) == null) continue
            val sealed = runCatching { Base64.decode(w.first, Base64.DEFAULT) }.getOrNull() ?: continue
            val plain = Book.openBoxedLetter(sealed, chat, w.second) ?: continue   // the last words open through the book (iOS 76f5eeb7)
            val a = plain.indexOf(0); if (a < 0) continue
            val b = (a + 1 until plain.size).firstOrNull { plain[it] == 0.toByte() } ?: continue
            if (String(plain, a + 1, b - a - 1, Charsets.UTF_8) != "chat") continue
            val text = inflate(plain.copyOfRange(b + 1, plain.size))?.takeIf { it.isNotEmpty() } ?: continue
            Presence.stamp(chat, String(text, Charsets.UTF_8), w.second * 1000)
            words++
        }
        // THE OPEN WALLET ASKS EVERY THREE SECONDS (iOS sweepPresence(quiet:), MontanaWakePush.swift:3262-3264): its sweeps say a line a minute
        val saidNow = System.currentTimeMillis()
        if (!quiet || 60_000L <= saidNow - sweepSaidAt) { if (quiet) sweepSaidAt = saidNow; Log.d("Montana", "presence_sweep convs=" + chatOf.size / 2 + " doors=" + doors + " words=" + words) }
    }

    @Volatile private var sweepSaidAt = 0L   // the open wallet's sweeps: one line a minute
    private val holders = HashMap<String, MutableSet<String>>()
    private val running = HashMap<String, Int>()

    /** THE LANE OF ONE CONVERSATION stands while anyone holds it — the open chat, a call ringing — one long question, at its door. */
    fun hold(ref: String, why: String) {
        val start = synchronized(holders) {
            holders.getOrPut(ref) { mutableSetOf() }.add(why)
            ((running[ref] ?: 0) == 0).also { if (it) running[ref] = 1 }
        }
        if (start) listen(ref)
    }

    /**
     * THE SEED'S OWN LANE IS HELD AT EVERY NODE (iOS ea31b640 seedLaneDoors, 2419, OZ 1 p.4, the author's words 23266-23268, 23279: T1
     * wrote at 15:54:33, the Mac took it at 15:56:02): each device elects a door of its own, and a letter is boxed -- and its hint told
     * -- only at the node whose door the writer knocked; the stores never talk to each other. The seed's lane asks one living door of
     * every node, so the hint reaches every running device the same second whichever node the writer chose.
     */
    private fun seedLaneDoors(): List<String> = Doors.onePerNode(Doors.ordered("signal")).filter { doorAlive(it) }.ifEmpty { listOf(signalDoor()) }
    private val seedLanes = HashSet<String>()   // under holders: the nodes' doors the seed's lane asks beside its first one
    /** The seed's lane at the nodes beside the first: one long question per node, while the lane is held and the node is one of them. */
    private fun seedLanesBeside(first: String) {
        val others = seedLaneDoors().filter { it != first && it.isNotEmpty() }
        for (door in others) {
            if (!synchronized(holders) { seedLanes.add(door) }) continue
            Log.d("Montana", "twin_lane at " + host(door))
            Thread {
                var fails = 0
                try {
                    while (true) {
                        val twin = Twin.deviceRef(); val secret = Twin.secret()
                        if (twin == null || secret == null || synchronized(holders) { holders[Twin.PIPE].isNullOrEmpty() }) break
                        if (door !in seedLaneDoors()) break   // the node fell silent or is the first door now
                        val cw = Wire.convW(secret, Wire.day())
                        val (code, text) = Wire.post(door, "/signal-fetch", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("wait", 12), 15_000, asking)
                        if (code != 200 || text == null) { doorFailed(door, code); fails++; if (fails >= 3) break; Thread.sleep(1000L * fails); continue }
                        doorAnswered(door)
                        fails = 0
                        runCatching { JSONObject(text).optJSONArray("envs") }.getOrNull()?.let { envs -> if (envs.length() > 0) open(Twin.PIPE, envs) }
                    }
                } finally { synchronized(holders) { seedLanes.remove(door) } }
            }.apply { isDaemon = true }.start()
        }
    }

    /** THE CHAT OR ITS BOARD STANDS ON THE SCREEN (iOS chatConvs ∪ boardConvs): a letter to them then rides the lane beside the box. */
    fun onScreen(ref: String): Boolean = synchronized(holders) { holders[ref]?.let { "chat" in it || "board" in it } == true }

    fun release(ref: String, why: String) {
        synchronized(holders) { holders[ref]?.let { it.remove(why); if (it.isEmpty()) holders.remove(ref) } }
    }

    /**
     * THE APP'S EAR (what the platform's call push — PushKit — gives the iPhone, this phone keeps for itself on our own doors, with
     * no third party): while the app stands — in front, or closed under its EarService — the lane of every conversation is held, so a call's first word rings here
     * the millisecond it lands — as an iPhone's ring does — not at the box's next round. The doors read a held lane as «this
     * phone is here» and keep the bell of a letter silent: the person is here, its screen is receiving it.
     */
    fun ear(on: Boolean) {
        for (ref in Book.refs()) if (on && !PeerSafety.isBlocked(ref)) hold(ref, "ear") else release(ref, "ear")
    }

    /**
     * THE LONG QUESTION (iOS fetchSignals 3270-3321): the conversation's door asked with a wait of twelve seconds — the doors' gate
     * cuts at fifteen — and asked again the moment it answers, while the lane is held.
     */
    private fun listen(ref: String) {
        Thread {
            var fails = 0
            // THE SEED'S OWN LANE (iOS f339b7a3 holdTwinLane, twinConvs, laneSecret): the pipe of this seed's devices asks under its own
            // key and this device's own name in it, so the node's «box» reaches a running device the second a twin's letter is boxed
            val twinLane = ref == Twin.PIPE
            while (true) {
                val twin = if (twinLane) Twin.deviceRef() else MontanaSeed.twin
                val secret = if (twinLane) Twin.secret() else Book.secret(ref)
                val go = synchronized(holders) {
                    val ok = twin != null && secret != null && !holders[ref].isNullOrEmpty()
                    if (!ok) running[ref] = (running[ref] ?: 1) - 1
                    ok
                }
                if (!go || twin == null || secret == null) return@Thread
                // ONE QUESTION, AT THE CONVERSATION'S DOOR (iOS fetchSignals 3276-3277): the first door alive for both, or mine
                // the seed's own lane: one door of every node (iOS seedLaneDoors, 2419, OZ 1 p.4)
                val door = if (twinLane) seedLaneDoors().first().also { seedLanesBeside(it) } else signalDoor()
                if (door.isEmpty()) { Thread.sleep(2000); continue }
                val gen = laneGen.get()
                val cw = Wire.convW(secret, Wire.day())
                // WHILE A CALL IS BUILT THE QUESTIONS ARE SHORT (iOS fetchSignals 3286-3299): a silently dead one costs six seconds, not
                // thirty — three while it rings or connects, ten in a joined call, twelve at rest (the doors' nginx holds fifteen)
                val wait = if (Calls.busy() && CallLine.connectedAt == 0L) 3 else if (CallLine.connectedAt > 0L) 10 else 12
                val (code, text) = Wire.post(door, "/signal-fetch", JSONObject().put("conv", cw).put("from_id", Wire.subId(cw, twin)).put("wait", wait), (wait + 3) * 1000, asking)
                // CUT BY OUR OWN HAND (iOS 3304-3307, 3317): the fresh lane asks now, and a question we cut says nothing of the door
                if (gen != laneGen.get()) { fails = 0; continue }
                EarService.awake()   // the answer woke the phone: it stays up for the word and the next question (the app closed)
                if (code != 200 || text == null) { doorFailed(door, code); fails++; Thread.sleep(minOf(10_000L, 1000L * fails)); continue }
                doorAnswered(door)
                fails = 0
                runCatching { JSONObject(text).optJSONArray("envs") }.getOrNull()?.let { envs -> if (envs.length() > 0) open(ref, envs) }
            }
        }.start()
    }
}

/**
 * THE PRESENCE LADDER (iOS ChatStore presenceWord): «typing…» lives five seconds, «in chat» and «online» one life of 45 —
 * two beats of the 20-second heartbeat and a margin; a departure word ends it at once; a word older than its life is history
 * and lights nothing (the lane hands over a minute of words when a chat opens). When no live word stands, the stamp: «last
 * seen…» — exact when both share it, the coarse class when either hides.
 */
object Presence {
    const val TYPING = "​​TY:"
    const val WATCH = "​​WA:"   // «1» my chat with you is on my screen, «0» left it
    const val APP = "​​AP:"     // «1» the app is on my screen, «0» left it; «0B» — gone for good
    private const val TYPING_LIFE = 5_000L
    private const val WORD_LIFE = 45_000L
    const val BEAT = 20_000L
    private const val CLOCK_SLACK = 2_000L   // iOS clockSlackS: two clocks and the node's whole second may differ by this much

    private val typingUntil = HashMap<String, Long>()
    private val inChatUntil = HashMap<String, Long>()
    private val onlineUntil = HashMap<String, Long>()
    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    /** The live lines redraw: a group's «typing» lives in its own book (Groups.typingLine) and lights the same heads. */
    fun redraw() = changed()
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        MainThread.post { ls.forEach { it() } }
    }

    val sharing get() = Prefs.bool("presenceSharing", true)
    /**
     * THE PERSON HIDES, AND SAYS SO ONCE (iOS MontanaPresencePrivacy.setSharing, E2E.presenceFarewell 849-855): one honest word
     * «0h» to everyone I speak presence to — an old reader takes the digit and the word dies, a new one reads the tail and coarsens
     * my stamp at once — then silence; shown again, «here» at once. Android went silent without the word: their phones kept my exact
     * moment and, for the word's life, «online».
     */
    fun setSharing(v: Boolean) {
        if (v == sharing) return
        Prefs.setBool("presenceSharing", v)
        if (v) appPresence(true, again = true)
        else Thread { for (ref in Book.refs()) if (SamePair.merged(ref) == null && !PeerSafety.isBlocked(ref)) Signal.post(ref, APP + "0h") }.start()
    }
    /**
     * The moment a word was said, as iOS says it (E2E.saidTail 668-683): «T» + seconds + «.» + three digits of milliseconds. THE NODE'S
     * CLOCK, STRICTLY GROWING (iOS draftStamp, 24.09): the phone's own clock put a phone set wrong out of every peer's «now»; the node's
     * clock may step back, so the moment is one counter that never does -- every word this phone says is ordered in one row.
     */
    fun saidTail(): String { val ms = saidMs(); return "T" + (ms / 1000) + "." + (1000 + ms % 1000).toString().drop(1) }
    private var lastSaid = 0L
    @Synchronized fun saidMs(): Long { lastSaid = maxOf(NodeClock.now(), lastSaid + 1); return lastSaid }
    private val saidRe = Regex("T(\\d{9,11})\\.(\\d{3})")
    private fun said(payload: String): Long? = saidRe.find(payload)?.let { m -> m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong() }

    // ── what I say ──
    // «TYPING» SAYS «HERE NOW» (iOS MontanaPresencePrivacy.saysTyping, the critic's K-G1, fa7a5152 at 2240): presence by another name --
    // a person who hides their presence types in silence, and a person this phone blocked hears no typing of it
    fun sayTyping(ref: String) { if (sharing && !PeerSafety.isBlocked(ref)) Signal.post(ref, TYPING + saidTail()) }
    // «K…» — the peer's letters I hold whole (iOS HeldLetters, chatBeacon 700-701, 23.09): before the door, which every build reads to the end
    fun sayChat(ref: String, open: Boolean) { if (sharing) Signal.post(ref, WATCH + (if (open) "1" else "0") + faceTail(ref) + saidTail() + heldTail(ref) + HeldLetters.tail(ref)) }

    /** The tag a word names a thing by (iOS Announced.wireTag, MontanaDeliveryEngine 417-422): SHA-256's first four bytes, big-endian, in digits; none — «0». */
    fun wireTag(d: ByteArray?): String {
        if (d == null || d.isEmpty()) return "0"
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(d)
        return (((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)).toString()
    }
    /**
     * THE ASK STANDS WHILE THE FACE IS MISSING (iOS faceTail, ec7cd30ce458; the author's word 03.10.2026: «why T1 does not show
     * every avatar though Mom has one»): «f» — «I hold no face of you» — rides every presence word until the face lands or the
     * correspondent answers «no face» (faceNone); every build answers it by sending its profile again, once in five minutes.
     */
    val faceNone: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val faceAsked = java.util.Collections.synchronizedSet(HashSet<String>())   // named in the diary once a life
    private fun faceTail(ref: String): String {
        if (Book.chat(ref) == null || Book.face(ref).exists() || ref in faceNone) return ""
        if (faceAsked.add(ref)) Log.d("Montana", "face_ask to=" + ref.take(10))
        return "f"
    }
    private val heldFace = HashMap<String, Pair<Long, String>>()   // ref -> (the face file's moment, its tag): a file is new every update
    /**
     * WHAT I HOLD OF YOU, in every presence word (iOS heldTail, MontanaE2E 543-568; fork atom 1778): «F» + the tag of your face on my
     * screen, «N» + the tag of your name — digits only, after the moment, so every older reader reads nothing of it. The peer compares
     * with what it is and sends again what my screen holds stale.
     */
    private fun heldTail(ref: String): String {
        val f = Book.face(ref)
        val face = if (!f.exists()) "0" else synchronized(heldFace) {
            heldFace[ref]?.takeIf { it.first == f.lastModified() }?.second ?: wireTag(f.readBytes()).also { heldFace[ref] = f.lastModified() to it }
        }
        val n = Book.chat(ref)?.name.orEmpty()
        // «W» + my wall's version and «G» + the tag of their page's ground on my screen, after the name's digits (iOS heldTail 566-567):
        // uppercase and digits, unread by every older build — they stop at the first letter after the digits they read
        // «C» + my balance right after the ground's digits (iOS heldTail 565-567): the same uppercase and digits (coinTail)
        return "F" + face + "N" + wireTag(if (n.isEmpty()) null else n.toByteArray(Charsets.UTF_8)) + "W" + MyWall.spokenVersion(ref) + "G" + PageGround.heldTag(ref) + coinTail()
    }
    /**
     * WHAT THEY HOLD OF ME, heard in their word (iOS heardHeld 629-667): their screen's tags of my face and my name against what I am —
     * a difference forgets the receipted mark and announces anew, at most once a minute a person. Android read nothing of it: an
     * iPhone that held an old face of this phone kept it for ever.
     */
    private val heldAskedAt = HashMap<String, Long>()
    private fun heardHeld(ref: String, payload: String) {
        fun digits(after: Char): String? {
            val i = payload.indexOf(after); if (i < 0) return null
            return payload.substring(i + 1).takeWhile { it.isDigit() }.ifEmpty { null }
        }
        val f = digits('F') ?: return
        val n = digits('N') ?: return
        val faceOff = f != wireTag(SelfFace.bytes(Book.ctx))
        val nameOff = n != wireTag(Prefs.userName.trim().takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
        if (!faceOff && !nameOff) return
        val now = System.currentTimeMillis()
        synchronized(heldAskedAt) { if (now - (heldAskedAt[ref] ?: 0L) < 60_000L) return; heldAskedAt[ref] = now }
        Log.d("Montana", "held_differs to=" + ref.take(10) + " face=" + (if (faceOff) 1 else 0) + " name=" + (if (nameOff) 1 else 0))   // iOS names the peer too
        Thread { Post.announceAgain(ref, faceOff, nameOff, f, n) }.start()
    }
    /**
     * THE BALANCE RIDES EVERY PRESENCE WORD (iOS E2E.coinTail, MontanaE2E.swift:588-597; the author's words 04.10.2026 03:03 and 04:19
     * MSK: «the top is a tunnel, a live set as by a web socket, the update instant from the nodes»): «C» and my balance after the ground's
     * digits — the lane hands it over at once and the node keeps it as my last word, which an open wallet asks for every few seconds.
     * Nothing before the book is read, nothing when the owner hid the coins (CoinShow); Android's balance rode no word at all.
     */
    private fun coinTail(): String {
        val coins = CoinShow.balance ?: return ""   // the chain's available once it spoke (iOS MTCoinBalance.set(available))
        return if (CoinShow.on) "C" + coins else ""
    }
    /** App-level presence for one correspondence (iOS appBeacon 731-738): the word, my moment, what I hold of them, their letters I hold whole. */
    private fun appBeacon(ref: String, open: Boolean) {
        if (!sharing || PeerSafety.isBlocked(ref)) return
        Signal.post(ref, APP + (if (open) "1" else "0") + faceTail(ref) + saidTail() + heldTail(ref) + HeldLetters.tail(ref))
    }
    /** THE SAME PEOPLE HEAR THE GREETING AND THE FAREWELL (iOS presencePeers 850-859): the freshest conversations first, 128 at most. */
    private fun presencePeers(): List<String> = Book.refs().filter { SamePair.merged(it) == null && !PeerSafety.isBlocked(it) && !Groups.isKey(it) }
        .sortedByDescending { Book.chat(it)?.msgs?.lastOrNull()?.at ?: 0L }.take(128)
    private val greeted = ArrayList<String>()   // those greeted in this life: the farewell reaches them whatever the order has become since
    @Volatile private var appSaid: Boolean? = null
    @Volatile private var awayGen = 0
    private const val AWAY_GRACE = 8_000L   // iOS awayGraceS: the farewell waits a breath
    /**
     * «I AM IN THE APP» / «I LEFT IT» — ONCE PER STATE (iOS appPresence 893-951, sayAway 952-959). No timer: between the broadcasts
     * the word lives on the echo to the watcher's 20-second beat (presenceEcho), for those who look and nobody else. A farewell leaves
     * a breath after the app left the screen and only to those greeted in this life; a return within the breath cancels it and says
     * nothing. A greeting said again (the privacy switch, the clock's correction) adds to those greeted, it does not forget them.
     */
    fun appPresence(open: Boolean, again: Boolean = false) {
        if (open && awayPending) {
            awayPending = false; awayGen++
            log("presence_hold back within the grace — the farewell is cancelled, «here» stands")
            if (!again) return
        }
        if (!again && appSaid == open) return
        if (!open && appSaid == null) return   // nobody was greeted in this life, nobody is owed a farewell
        if (!open) {
            awayPending = true
            val gen = ++awayGen
            MainThread.later(AWAY_GRACE, Runnable { if (awayPending && gen == awayGen) { awayPending = false; sayAway("app-away") } })
            return
        }
        appSaid = true
        val now = presencePeers()
        synchronized(greeted) { if (again) now.forEach { if (it !in greeted) greeted.add(it) } else { greeted.clear(); greeted.addAll(now) } }
        Thread {
            for (p in now) appBeacon(p, true)
            Book.openChat?.let { sayChat(it, true) }   // the chat on the screen greets again at once
        }.start()
    }
    /**
     * THE BOOK GREW UNDER A GREETING ALREADY SAID (iOS E2E.peersGrew, 3ed661fa, OZ 1 p.2.5: «the faces of the people come back at once
     * with the restore»): the archive stood the correspondences up after the greeting had left, so the newcomers were neither greeted
     * nor asked for their faces (faceTail) until each chat was opened. The greeting is said again to the grown book, the ask riding
     * it, while the application stands before the person; nobody greeted yet -- the greeting still to come reads the grown book itself.
     */
    fun peersGrew() {
        if (appSaid != true || !shown) return
        log("presence_grew the book grew under a greeting -- said again")
        MainThread.post { appPresence(open = true, again = true) }
    }
    @Volatile private var awayPending = false
    private fun sayAway(why: String) {
        appSaid = false
        val now = presencePeers()
        val peers = synchronized(greeted) { greeted + now.filter { it !in greeted } }
        log("presence_tx kind=app open=0 why=" + why + " n=" + peers.size)
        Thread { for (p in peers) appBeacon(p, false) }.start()
    }
    /** THE ECHO (iOS presenceEcho 961-974): the peer's beat says their chat with me is open -- my state answers at once; copies within
     * 0.8 s fold; my own beat already answers when our chat is on my screen; only the background silences it. */
    private val echoAt = HashMap<String, Long>()
    private fun presenceEcho(ref: String) {
        val now = System.currentTimeMillis()
        synchronized(echoAt) { if (now - (echoAt[ref] ?: 0L) < 800) return; echoAt[ref] = now }
        if (Book.openChat == ref || !appOpen) return
        Thread { appBeacon(ref, true) }.start()
    }
    /** The people in the app now (iOS ChatStore.appOnlineChats): their word «in the app» still lives. */
    fun appOnline(): List<String> {
        val now = System.currentTimeMillis()
        return runCatching { onlineUntil.filterValues { now < it }.keys.toList() }.getOrDefault(emptyList())
    }
    /** MY BALANCE MOVED WHILE I AM IN THE APP (iOS E2E.coinBeacon, MontanaE2E.swift:740-745): the people in the app hear it in the app
     * word itself (coinTail), paced by CoinTell — never from the background, where «in the app» would be a lie. Off the screen's thread. */
    fun coinBeacon(ref: String) { if (appOpen) appBeacon(ref, true) }
    /** MY «NOW» MOVED (iOS clockCorrected 882-892): the greeting said before the node's clock was known is said again by the right
     * clock -- only while the app stands before the person having greeted, once in ten minutes at most. */
    private var clockSaidAt = 0L
    fun clockCorrected() {
        val now = System.currentTimeMillis()
        if (appSaid != true || !appOpen || now - clockSaidAt <= 600_000L) return
        clockSaidAt = now
        log("presence clock corrected — the greeting is said again")
        appPresence(true, again = true)
    }

    /** A blocked person's live words leave with the block (iOS toggleBlocked 979: typing and watching removed). */
    fun drop(ref: String) {
        typingUntil.remove(ref); inChatUntil.remove(ref); onlineUntil.remove(ref)
        changed()
    }

    // ── what I hear ──

    /**
     * A PEER'S CLOCK, READ FROM ITS WORDS (iOS peerClockMoment, MontanaChatStore 939-964, atoms 4c8271c9735f, a5649e092ff8): a build
     * that said «T» by its phone's own time read as history when that phone was set behind. A word's landing minus its moment is
     * the speaker's clock offset plus the road; the smallest over the peer's recent words is the clock, believed only when two
     * landings ten seconds apart show it within the tolerance two node clocks may disagree by, and applied only past it. ONLY A WORD
     * NEAR THE CLOCK IT TEACHES IS SHIFTED: a word whose gap stands above the confirmed minimum by more than the tolerance and a slow
     * road's lateness (ten seconds) is read as said.
     */
    private const val ROAD_LATE = 10_000L
    private val peerGaps = HashMap<String, ArrayList<LongArray>>()
    private fun peerClockMoment(ref: String, said: Long): Long {
        val now = NodeClock.now()
        val gap = now - said
        val g = synchronized(peerGaps) {
            val l = peerGaps.getOrPut(ref) { ArrayList() }
            l.removeAll { 600_000L <= now - it[0] }
            l.add(longArrayOf(now, gap))
            while (16 < l.size) l.removeAt(0)
            l.map { it.copyOf() }
        }
        val lo = g.minByOrNull { it[1] } ?: return said
        if (Math.abs(gap) <= CLOCK_SLACK || Math.abs(lo[1]) <= CLOCK_SLACK || CLOCK_SLACK + ROAD_LATE < gap - lo[1]) return said
        if (g.none { 10_000L <= Math.abs(it[0] - lo[0]) && Math.abs(it[1] - lo[1]) <= CLOCK_SLACK }) return said
        return said + lo[1]
    }

    /**
     * THE LADDER MOVES ONLY BY A WORD SAID NOW, AND NEVER BACK (iOS liveWordMoves, MontanaChatStore 1039-1088): a word older than its
     * life is history -- it stamps, it lights nothing and puts nothing out; a word older than the last one that moved its row is the
     * echo of a second road. TWO ROWS: «in my chat» and «in the app»; arriving in the chat is arriving in the app, leaving the app is
     * leaving the chat. THE ROW HOLDS THE MOMENT AS SAID; a held moment further ahead than two node clocks may disagree was said by a
     * wrong clock and blocks nothing. A word that says no moment (an old build) is believed as before, outside the rows.
     */
    private val liveAt = HashMap<String, Long>()
    private val liveLeft = HashMap<String, Boolean>()
    private fun liveWordMoves(ref: String, said: Long?, chatRow: Boolean, arrival: Boolean, life: Long): Boolean = synchronized(liveAt) {
        if (said == null) { if (chatRow || !arrival) liveLeft[ref] = !arrival; return@synchronized true }
        val now = NodeClock.now()
        if (life < now - said) return@synchronized false
        val ck = "c" + ref; val ak = "a" + ref
        fun blocks(k: String): Boolean { val held = liveAt[k] ?: return false; return said < held && held - now <= CLOCK_SLACK }
        if (chatRow) {
            if (blocks(ck)) return@synchronized false
            liveAt[ck] = said; liveLeft[ref] = !arrival
            if (arrival && !blocks(ak)) liveAt[ak] = said
        } else {
            if (blocks(ak)) return@synchronized false
            liveAt[ak] = said
            if (!arrival && !blocks(ck)) { liveAt[ck] = said; liveLeft[ref] = true }
        }
        true
    }
    /** THE WORD LIVES FROM ITS MOMENT, NOT FROM ITS LANDING (iOS wordAge 1121-1128): what is left of its life. */
    private fun wordAge(said: Long?): Long = if (said == null) 0L else minOf(WORD_LIFE, maxOf(0L, NodeClock.now() - said))

    private fun peerTyping(ref: String) {
        val now = System.currentTimeMillis()
        typingUntil[ref] = now + TYPING_LIFE
        MainThread.later(TYPING_LIFE + 50, Runnable { changed() })
        peerWatching(ref, true, null)   // typing proves the chat is on their screen
    }
    private fun peerWatching(ref: String, open: Boolean, said: Long?) {
        if (!open) { inChatUntil.remove(ref); return }
        val until = System.currentTimeMillis() + WORD_LIFE - wordAge(said)
        inChatUntil[ref] = until
        onlineUntil[ref] = maxOf(onlineUntil[ref] ?: 0L, until)   // being in the chat is being in the app
        MainThread.later(WORD_LIFE - wordAge(said) + 50, Runnable { changed() })
    }
    private fun peerAppOnline(ref: String, open: Boolean, said: Long?) {
        if (!open) { onlineUntil.remove(ref); inChatUntil.remove(ref); typingUntil.remove(ref); return }   // leaving the app is leaving the chat
        val left = WORD_LIFE - wordAge(said)
        onlineUntil[ref] = System.currentTimeMillis() + left
        MainThread.later(left + 50, Runnable { changed() })
    }

    /** A word of the lane from `ref` (iOS append's presence branches 3797-3954 + liveWordMoves). */
    fun hear(ref: String, text: String) {
        if (PeerSafety.isBlocked(ref)) return   // a blocked person reaches nothing
        // THEIR LIVE DRAFT (iOS handleMeshDraft): its bubble, and «typing…» when it is a keystroke said now
        if (text.startsWith(LiveDraft.MARK)) {
            if (LiveDraft.hear(ref, text)) { peerTyping(ref); noteSeen(ref, System.currentTimeMillis(), "draft"); changed() }
            return
        }
        // the word's OWN moment, read on the peer's clock: the ladder orders by it alone (iOS 3801-3805)
        val saidAt = said(text)?.let { peerClockMoment(ref, it) }
        val wordAt = saidAt ?: System.currentTimeMillis()
        val fresh = (if (saidAt != null) NodeClock.now() else System.currentTimeMillis()) - wordAt <= WORD_LIFE
        when {
            text.startsWith(TYPING) -> { if (liveWordMoves(ref, saidAt, true, true, TYPING_LIFE + 2000)) peerTyping(ref) }
            text.startsWith(WATCH) || text.startsWith(APP) -> {
                val chat = text.startsWith(WATCH)
                val payload = text.removePrefix(if (chat) WATCH else APP)
                // THE DOORS THEY ASK (iOS ChatStore 3910-3912, WakePush 3252): my words for them go there
                hides(ref, payload.drop(1).substringBefore('@').contains('h'), wordAt)
                // «f»: they hold no face of mine — my profile goes again (iOS ChatStore: payload.dropFirst(1).contains("f"))
                if (payload.drop(1).substringBefore('@').contains('f')) Thread { Post.resendProfile(ref) }.start()
                val open = payload.startsWith("1")
                if (fresh) Board.heardPresence(ref, payload)   // their wall's version (iOS heardHeld, wallHeard)
                // WHAT A WORD PROVES OF ITS BUILD IS READ FROM EVERY WORD, LATE ONES TOO (iOS heardCapable 615-624): their «G» says they read the ground
                if (PageGround.groundHeld(payload) != null) PageGround.noteCapable(ref)
                if (fresh) heardHeld(ref, payload)   // only a word said now says what their screen holds
                heardHeldLetters(ref, payload)   // which of my letters they hold whole (iOS 3909, 3935): a letter once held whole is delivered for good
                CoinBoard.heard(ref, payload, wordAt)   // the balance the word tells (iOS E2E.heardCoins, MontanaChatStore.swift:3907, 3933)
                if (chat) {
                    if (liveWordMoves(ref, saidAt, true, open, WORD_LIFE)) {
                        peerWatching(ref, open, saidAt)
                        if (open) presenceEcho(ref) else typingUntil.remove(ref)   // answer with my state; left the chat = stopped typing
                    }
                } else if (payload.drop(1).startsWith("B")) {
                    // «GONE» IS A DEPARTURE IN BOTH ROWS, AND A STATE HAS NO LIFE (iOS 3937-3948): ordered like every word, never too old
                    if (liveWordMoves(ref, saidAt, false, false, Long.MAX_VALUE)) gone(ref, wordAt)
                    noteSeen(ref, wordAt, "app"); changed(); return
                } else {
                    back(ref, wordAt)   // a later presence word of theirs: they are here again
                    if (liveWordMoves(ref, saidAt, false, open, WORD_LIFE)) peerAppOnline(ref, open, saidAt)
                }
            }
            else -> return
        }
        noteSeen(ref, wordAt, if (text.startsWith(TYPING)) "typing" else if (text.startsWith(WATCH)) "watch" else "app")
        changed()
    }

    /** THE PEER'S WORD NAMES MY LETTERS IT HOLDS WHOLE (iOS heardHeldLetters, MontanaChatStore 4287-4306): the receipt's second
     * speaker -- a named letter is raised through the one delivery door, whether its row stands or it still waits in the queue. */
    private fun heardHeldLetters(ref: String, payload: String) {
        val named = HeldLetters.named(payload)
        if (named.isEmpty()) return
        val mids = Book.chat(ref)?.msgs?.toList().orEmpty()
            .filter { it.mine && (it.state == 0 || it.state == 1 || it.state == -1) }
            .mapNotNull { m -> HeldLetters.key(m.mid)?.takeIf { it in named }?.let { m.mid } }
        if (mids.isEmpty()) return
        for (mid in mids) { Post.unqueue(mid); Post.markDelivered(ref, mid) }
        Log.d("Montana", "held_rx from=" + ref.take(10) + " named=" + named.size + " raised=" + mids.size)
    }

    /**
     * THE ONE WRITER OF THE STAMP (iOS noteSeen, MontanaChatStore 1680-1694): a moment never later than «now» by the node's clock --
     * the words' own -- never earlier than the one held. THE STAMP MOVES WITH A LINE, folded a minute a correspondent: from whom, by
     * what word, at what moment; THE DIARY KNOWS NO MORE THAN THE SCREEN -- where the screen shows the coarse class, «hidden».
     */
    private val seenSaid = HashMap<String, Long>()
    fun noteSeen(ref: String, atMs: Long?, by: String = "node") {
        val ts = minOf(atMs ?: return, NodeClock.now())
        if (ts <= seenAt(ref)) return
        Prefs.setStr("seenAt.$ref", ts.toString())
        val now = System.currentTimeMillis()
        val say = synchronized(seenSaid) { (60_000L <= now - (seenSaid[ref] ?: 0L)).also { if (it) seenSaid[ref] = now } }
        if (say) {
            val exact = sharing && !Prefs.bool("phide_$ref", false)
            val at = if (exact) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ts)) + "Z" else "hidden"
            Log.d("Montana", "seen_set from=" + ref.take(10) + " by=" + by + " at=" + at)
        }
        changed()
    }

    private fun seenAt(ref: String) = Prefs.str("seenAt.$ref", "0").toLongOrNull() ?: 0L

    /**
     * A SWEPT WORD IS A STAMP (iOS sweepPresence 3232-3264): history never lights «online» or «in chat» (13.09 15:15 — a «1» swept
     * twelve seconds after a phone locked lit «in chat» for a locked phone); it says when the peer was there, its hiding, its doors
     * and «gone». The word's own moment stands unless it runs ahead of the node's by more than the clocks' leeway: a clock set ahead
     * said it, and the node's moment stands (iOS 24.09). A draft or a typing word is proof of the moment alone.
     */
    fun stamp(ref: String, text: String, nodeAt: Long) {
        if (PeerSafety.isBlocked(ref)) return
        val at = said(text)?.let { if (CLOCK_SLACK + 1000 < it - nodeAt) nodeAt else it } ?: nodeAt
        val app = text.startsWith(APP)
        if (app || text.startsWith(WATCH)) {
            val payload = text.removePrefix(if (app) APP else WATCH)
            CoinBoard.heard(ref, payload, at)   // the balance the node's last word tells (iOS sweepPresence, MontanaWakePush.swift:3246)
            hides(ref, payload.drop(1).contains('h'), at)
            if (app && payload.drop(1).startsWith("B")) gone(ref, at) else if (app) back(ref, at)
        }
        noteSeen(ref, at, "node")
        changed()
    }

    /** THE PEER'S HIDING, BY ITS MOMENT (iOS notePeerHides 322-329): an older word never undoes a newer one. */
    private fun hides(ref: String, h: Boolean, at: Long) {
        if (at < (Prefs.str("phideAt_$ref", "0").toLongOrNull() ?: 0L)) return
        Prefs.setStr("phideAt_$ref", at.toString())
        Prefs.setBool("phide_$ref", h)
    }
    /** «GONE FOR GOOD» AND ITS END, BY THEIR MOMENTS (iOS peerGone / peerBack 1720-1732): a word older than the farewell ends nothing. */
    private fun gone(ref: String, at: Long) {
        if (at < goneAt(ref)) return
        Prefs.setStr("peerGoneAt.$ref", at.toString())
        Prefs.setBool("peerGone.$ref", true)
        inChatUntil.remove(ref); typingUntil.remove(ref); onlineUntil.remove(ref)
    }
    private fun back(ref: String, at: Long) {
        if (!Prefs.bool("peerGone.$ref", false) || at <= goneAt(ref)) return
        Prefs.remove("peerGone.$ref"); Prefs.remove("peerGoneAt.$ref")
    }
    private fun goneAt(ref: String) = Prefs.str("peerGoneAt.$ref", "0").toLongOrNull() ?: 0L

    /** THE MOMENT BEHIND THE LINE, as a number to sort by (iOS presenceStamp): a live word above every stamp, «gone» at the bottom. */
    fun stamp(ref: String): Long {
        val now = System.currentTimeMillis()
        if ((typingUntil[ref] ?: 0) > now) return now + 3
        if ((inChatUntil[ref] ?: 0) > now) return now + 2
        if ((onlineUntil[ref] ?: 0) > now) return now + 1
        if (Prefs.bool("peerGone.$ref", false)) return 1
        return seenAt(ref)
    }

    /** The one presence line (iOS presenceWord): the words and whether they are live (green). null — nothing is known. */
    fun word(c: Context, ref: String): Pair<String, Boolean>? {
        val now = System.currentTimeMillis()
        if ((typingUntil[ref] ?: 0) > now) return c.getString(R.string.pr_typing) to true
        if ((inChatUntil[ref] ?: 0) > now) return c.getString(R.string.pr_in_chat) to true
        // a person on the line with me is present for as long as the call stands (iOS presenceWord 15.7)
        if (Calls.talking() == ref) return c.getString(R.string.pr_on_call) to true
        if ((onlineUntil[ref] ?: 0) > now) return c.getString(R.string.pr_online) to true
        if (Prefs.bool("peerGone.$ref", false)) return c.getString(R.string.pr_seen_long_ago) to false
        val seen = seenAt(ref).takeIf { it > 0 } ?: return null
        val exact = sharing && !Prefs.bool("phide_$ref", false)
        return (if (exact) phrase(c, seen) else coarse(c, seen)) to false
    }
    /** iOS MontanaSeen.coarse: the class, never the minute. */
    private fun coarse(c: Context, ts: Long): String {
        val d = NodeClock.now() - ts   // the stamp is said by the node's clock (iOS MontanaShapes 485)
        return c.getString(when {
            d < 3 * 86_400_000L -> R.string.pr_seen_recently
            d < 7 * 86_400_000L -> R.string.pr_seen_week
            d < 30 * 86_400_000L -> R.string.pr_seen_month
            else -> R.string.pr_seen_long_ago
        })
    }
    /** iOS MontanaSeen.phrase: minutes within the hour (never under one), «today at», «yesterday at», else the day. */
    private fun phrase(c: Context, ts: Long): String {
        val diff = NodeClock.now() - ts   // the stamp is said by the node's clock (iOS MontanaShapes 494)
        if (diff < 3_600_000L) { val m = maxOf(1, (diff / 60_000L).toInt()); return c.resources.getQuantityString(R.plurals.pr_minutes, m, m) }
        val time = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(ts))
        if (android.text.format.DateUtils.isToday(ts)) return c.getString(R.string.pr_today_at, time)
        if (android.text.format.DateUtils.isToday(ts + 86_400_000L)) return c.getString(R.string.pr_yesterday_at, time)
        return android.text.format.DateUtils.formatDateTime(c, ts, android.text.format.DateUtils.FORMAT_SHOW_DATE)
    }

    // ── the app on the screen (iOS: the scene's phase) -- its word is said once per state, never by a timer (appPresence) ──
    @Volatile private var appOpen = false
    /** The app stands on the person's screen (iOS: the scene is active). */
    val shown: Boolean get() = appOpen
    // THE RETURN TO THE FOREGROUND READS AGAIN (iOS ContentView.swift:451-452 with MontanaConversation.swift:1495-1501 at
    // 2155: scenePhase turns .active -> store.markRead(chat.name)): a letter that landed under a locked phone with the chat
    // left open stood unread there; coming back reads it now, the one road markRead already is (Post.markRead).
    fun appShown() {
        if (appOpen) return
        appOpen = true
        DoorBook.knockNow()   // every opening knocks NOW, but keeps what the doors said (iOS MontanaNodes.openNow 339-345)
        appPresence(true)
        Book.openChat?.let { ref -> if ((Book.chat(ref)?.unread ?: 0) > 0) Thread { Post.markRead(ref) }.start() }
    }
    fun appHidden() { if (!appOpen) return; appOpen = false; appPresence(false) }
    fun log(s: String) = Log.d("Montana", "presence: $s")

    /**
     * «The app is on my screen» follows the activity's own life (iOS: the scene's phase) — watched from here, so no other
     * file carries it: registered once, on the first pickup of the box (which runs only while the app stands).
     */
    private var watching = false
    fun watchApp(c: Context) {
        if (watching) return
        watching = true
        Signal.watchPath(c)
        Channels.start()   // the phone holds its nodes for the app's whole life (iOS MontanaNodes.open)
        Mesh.start(c)   // and, while the person lets it, is findable on the local network (iOS startLocalMeshIfAccepted)
        (c.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) = appShown()
            override fun onActivityPaused(a: android.app.Activity) = appHidden()
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // on the screen only when an activity really stands in front — a pickup from a background job must not say «online»;
        // the callbacks above say it when the activity resumes later
        val me = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        if (me.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) appShown()
    }
}

/**
 * THE NODE'S CLOCK, LEARNED IN PASSING (iOS MontanaWakePush 1000-1050; atoms 7ba462d86e3d, a5649e092ff8): this phone's clock minus
 * the node's, from the Date header of the lane's answer and of the diary's ship -- one owner. «Now» by it is the one moment both
 * ends agree on, however wrong either phone's own clock is. THE CLOCK LEARNED IN THE LAST LIFE STARTS THIS ONE. A CORRECTION IS
 * TOLD ONLY WHEN IT STANDS: the first answer of a life, or two answers in a row that agree -- two doors whose clocks disagreed
 * would flip it at every answer; a correction larger than two clocks may disagree says the greeting again (Presence.clockCorrected).
 */
object NodeClock {
    private const val KEY = "mt.node.skew"
    private const val LEEWAY = 2_000L   // iOS clockSlackS
    @Volatile private var loaded = false
    @Volatile private var skew = 0L
    private var lastSample: Long? = null
    private var told = 0L
    private fun load() {
        if (loaded) return
        synchronized(this) { if (!loaded) { skew = runCatching { Prefs.str(KEY, "0").toLongOrNull() ?: 0L }.getOrDefault(0L); told = skew; loaded = true } }
    }
    /** «Now» by the node's clock, in milliseconds; the local clock only in a first life, until a node answers. */
    fun now(): Long { load(); return System.currentTimeMillis() - skew }
    /** This phone's clock minus the node's, for the diary's head (iOS nodeSkewMs). */
    val skewMs: Long get() { load(); return skew }
    fun learn(conn: java.net.HttpURLConnection) {
        val server = runCatching { conn.getHeaderFieldDate("Date", 0L) }.getOrDefault(0L)
        if (server > 0L) learn(server)   // no Date, or one unread: nothing is learned (iOS learnSkew 1028-1029)
    }
    @Synchronized fun learn(serverMs: Long) {
        PersonChain.answered(serverMs)   // a node's answer is a second of the person on the network (iOS learnSkew, personFeed)
        FloorGuard.heard(serverMs)   // the clock guard of the first floor: c of this run, and M (iOS MTPipeBook.heard, decision 188, #38)
        load()
        val s = System.currentTimeMillis() - serverMs
        val moved = Math.abs(s - skew)
        val stands = lastSample?.let { Math.abs(it - s) <= LEEWAY } ?: true
        val tell = stands && LEEWAY < Math.abs(s - told)
        skew = s; lastSample = s
        if (tell) told = s
        if (250 <= moved) Prefs.setStr(KEY, s.toString())   // the next life starts from it
        if (tell) Presence.clockCorrected()
    }
}

/**
 * THE LETTERS I HOLD WHOLE (iOS HeldLetters, MontanaDeliveryEngine 466-523, atom 1f4a4fb0f092): the receipt's second speaker, on
 * the road that works -- every presence word names, after «K», the peer's letters this side holds whole (the latest touched first,
 * twelve at most), and the sender raises what it names through the one delivery door. A letter is named by its birth: «ms.n», the
 * birth millisecond of the name «t…-…» and the uuid's first four hex digits read as a number. Said by the one receipt door alone.
 */
object HeldLetters {
    private const val CAPACITY = 12
    private val recent = HashMap<String, ArrayList<String>>()
    fun key(mid0: String): String? {
        val mid = mid0.removePrefix("mid:")
        if (!mid.startsWith("t")) return null
        val dash = mid.indexOf('-')
        if (dash < 2) return null
        val ms = mid.substring(1, dash)
        if (!ms.all { it.isDigit() }) return null
        val head = mid.substring(dash + 1).take(4)
        if (head.length != 4) return null
        val n = head.toIntOrNull(16) ?: return null
        return ms + "." + n
    }
    fun note(peer: String, mid: String) {
        val k = key(mid) ?: return
        synchronized(recent) {
            val l = recent.getOrPut(peer) { ArrayList() }
            l.remove(k); l.add(0, k)
            while (CAPACITY < l.size) l.removeAt(l.size - 1)
        }
    }
    fun tail(peer: String): String = synchronized(recent) { recent[peer]?.takeIf { it.isNotEmpty() }?.let { "K" + it.joinToString(",") } ?: "" }
    /** The keys a peer's word names: the run after its «K», before the door's «@». */
    fun named(payload: String): Set<String> {
        val head = payload.substringBefore('@')
        val k = head.indexOf('K')
        if (k < 0) return emptySet()
        val run = head.substring(k + 1).takeWhile { it.isDigit() || it == '.' || it == ',' }
        return run.split(',').filter { e -> val p = e.split('.'); p.size == 2 && p.all { it.isNotEmpty() && it.all(Char::isDigit) } }.toSet()
    }
}
