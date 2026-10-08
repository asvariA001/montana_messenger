package quest.montana.app

import android.os.Build
import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// ─────────────────────────── the phone's own channels to the network's machines (iOS MontanaP2PDirect, MontanaNetFrames, MontanaNodes.open) ───────────────────────────

/**
 * A MACHINE'S ANSWERING IDENTITY (iOS MontanaOverlayKey.device, MontanaNetFrames 194-243): drawn by the core at random once per
 * device and kept sealed beside the other local secrets — never derived from the seed, so two devices of one person answer under
 * two values and nothing joins their appearances. Forgotten where the device forgets the person.
 */
object NodeIdentity {
    private const val KEY = "mt.node.identity"
    class Ident(val pk: ByteArray, val sk: ByteArray, val tag: ByteArray)
    @Volatile private var cache: Ident? = null

    fun device(): Ident? = cache ?: synchronized(this) {
        cache ?: run {
            var born = false
            val seed = DeviceVault.get(KEY)?.takeIf { it.size == 32 }
                ?: MtBindings.nativeRandom(32)?.takeIf { DeviceVault.set(KEY, it) }?.also { born = true }
                ?: return@run null
            val k = MtBindings.nativeMldsaKeypair(seed)?.takeIf { it.size == MtBindings.PUBKEY + MtBindings.SECKEY } ?: return@run null
            val pk = k.copyOfRange(0, MtBindings.PUBKEY)
            if (born) Log.d("Montana", "NODE identity born (device-local, never derived from the seed)")
            Ident(pk, k.copyOfRange(MtBindings.PUBKEY, k.size), Wire.overlayTag(pk)).also { cache = it }
        }
    }

    fun forget() = synchronized(this) { cache = null; DeviceVault.delete(KEY) }
}

/**
 * THIS MACHINE'S SEALING KEY (iOS MontanaNodeKem.device, MontanaNetFrames 282-321): what a neighbour encapsulates to when it dials
 * this phone. Device-local and drawn at random for the reason the identity is: a key from the seed would be one key for every
 * device of one person, forever. Forgotten with the person.
 */
object NodeKem {
    private const val KEY = "mt.node.kem"
    @Volatile private var cache: Pair<ByteArray, ByteArray>? = null

    fun device(): Pair<ByteArray, ByteArray>? = cache ?: synchronized(this) {
        cache ?: run {
            val seed = DeviceVault.get(KEY)?.takeIf { it.size == 64 }
                ?: MtBindings.nativeRandom(64)?.takeIf { DeviceVault.set(KEY, it) }
                ?: return@run null
            val k = MtBindings.nativeMlkemKeypair(seed)?.takeIf { it.size == 1184 + 2400 } ?: return@run null
            (k.copyOfRange(0, 1184) to k.copyOfRange(1184, k.size)).also { cache = it }
        }
    }

    fun forget() = synchronized(this) { cache = null; DeviceVault.delete(KEY) }
}

/** The messages inside a channel (iOS MontanaNetMsg, MontanaNetFrames 88-118): type[1] ‖ length[4] big-endian ‖ body. */
object NetMsg {
    const val REGISTER_INIT = 0x10
    const val REGISTER_CHALLENGE = 0x11
    const val REGISTER_PROOF = 0x12
    const val REGISTER_RESULT = 0x13
    const val OWNER_REF = 0x14
    const val OVERLAY_FRAME = 0x20

    fun encode(type: Int, body: ByteArray): ByteArray = byteArrayOf(type.toByte()) + be4(body.size) + body
    fun decode(d: ByteArray): Pair<Int, ByteArray>? {
        if (d.size < 5) return null
        val n = be4(d, 1)
        if (n < 0 || d.size != 5 + n) return null
        return (d[0].toInt() and 0xff) to d.copyOfRange(5, 5 + n)
    }
    fun be4(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    fun be4(d: ByteArray, at: Int) =
        ((d[at].toInt() and 0xff) shl 24) or ((d[at + 1].toInt() and 0xff) shl 16) or ((d[at + 2].toInt() and 0xff) shl 8) or (d[at + 3].toInt() and 0xff)
}

/** A cell of the overlay (iOS MontanaOverlayFrame, MontanaNetFrames 120-157): 0x02 ‖ type ‖ dst[32] ‖ msgId[16] ‖ length[4] BE ‖ payload. */
class OverlayFrame(val type: Int, val dst: ByteArray, val msgId: ByteArray, val payload: ByteArray) {
    fun encode(): ByteArray = byteArrayOf(0x02, type.toByte()) + dst + msgId + NetMsg.be4(payload.size) + payload

    companion object {
        const val RELAY = 0x01
        const val ACK = 0x03
        fun decode(d: ByteArray): OverlayFrame? {
            if (d.size < 54 || d[0] != 0x02.toByte()) return null
            val n = NetMsg.be4(d, 50)
            if (n < 0 || d.size != 54 + n) return null
            return OverlayFrame(d[1].toInt() and 0xff, d.copyOfRange(2, 34), d.copyOfRange(34, 50), d.copyOfRange(54, 54 + n))
        }
    }
}

/**
 * ONE CHANNEL TO ONE DOOR (iOS MontanaP2PDirect: dial 770-839, initiatorHandshake 987-1020, the registration 1025-1147). A node on
 * 443 is reached inside an ordinary secure connection marked from inside as the node (ALPN «mt-node»): a tunnel, an operator and a
 * filter pass what looks like a visit to a site, and the shell is stripped at the node's front. Frames: four bytes of length,
 * big-endian, then the body, at most 1 MiB. The node speaks first — its KEM key — then Noise_PQ XX, and every frame after it is
 * sealed under its direction's key. Both sides register (the key and its tag, a challenge, its signature, the verdict); the
 * channel stands when both are confirmed. Measured from the Mac against both nodes, 08.10 12:3x (timechain/P2P-MAP.md).
 */
class Channel(val host: String, val port: Int, val ref: String = "", private val accepted: Socket? = null) {
    val label = (if (accepted != null) "in:" else "") + host + ":" + port
    val dialAt = System.currentTimeMillis()
    @Volatile var ready = false
        private set
    @Volatile var peerTag: ByteArray? = null
        private set
    @Volatile var peerOwnerRef: ByteArray? = null
        private set
    @Volatile var closeWhy = ""
        private set
    var channelHash = ByteArray(0)
        private set
    var ownerAsks = 0
    @Volatile var lastUsed = System.currentTimeMillis()
    val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()   // only the addressee's ack comes back (iOS 1217-1221)
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: DataInputStream? = null
    @Volatile private var sendKey: ByteArray? = null
    private var peerPk: ByteArray? = null
    private var nonce: ByteArray? = null
    private var myReg = false
    private var peerReg = false
    private var held1 = 0L   // the handshake's states between messages, freed if the channel dies between them
    private var held2 = 0L
    private var held3 = 0L

    /** Greet — as the one dialling or as the one dialled — and read until the channel dies, on the caller's thread. */
    fun run(ident: NodeIdentity.Ident, up: (Channel) -> Unit, frame: (Channel, OverlayFrame) -> Unit) {
        try {
            val rk = (if (accepted != null) respond() else dial()) ?: return
            Log.d("Montana", (if (accepted != null) "noise_srv_done" else "noise_done") + " at=" + label + " ms=" + (System.currentTimeMillis() - dialAt))
            send(NetMsg.REGISTER_INIT, ident.pk + ident.tag)
            while (true) {
                val f = read() ?: return close("peer-closed")
                val plain = Wire.open(rk, f) ?: return close("bad-frame")
                lastUsed = System.currentTimeMillis()
                if (plain.size == 1 && plain[0] == 0x02.toByte()) continue   // the other side's keepalive
                val (type, body) = NetMsg.decode(plain) ?: continue
                when (type) {
                    NetMsg.REGISTER_INIT -> registerInit(body)
                    NetMsg.REGISTER_CHALLENGE -> if (body.size == 16) MtBindings.nativeSign(ident.sk, signable(ident.tag, body))?.let { send(NetMsg.REGISTER_PROOF, it) }
                    NetMsg.REGISTER_PROOF -> registerProof(body, up)
                    NetMsg.REGISTER_RESULT -> if (body.firstOrNull() == 0.toByte()) { myReg = true; maybeReady(up) }
                    NetMsg.OWNER_REF -> if (body.size == 16 && peerOwnerRef == null) { peerOwnerRef = body; Log.d("Montana", "owner_ref at=" + label) }
                    NetMsg.OVERLAY_FRAME -> OverlayFrame.decode(body)?.let { frame(this, it) }
                }
            }
        } catch (e: Exception) {
            close((if (ready) "read-" else "dial-") + e.javaClass.simpleName)
        } finally {
            if (held1 != 0L) MtBindings.nativeNoiseFree(1, held1)
            if (held2 != 0L) MtBindings.nativeNoiseFree(2, held2)
            if (held3 != 0L) MtBindings.nativeNoiseFree(3, held3)
            runCatching { socket?.close() }
        }
    }

    private fun fail(why: String): ByteArray? { close(why); return null }

    /** The one dialling (iOS dial 770-839, initiatorHandshake 987-1020): the far side's KEM key, msg1, msg2, msg3 — the reading key back. */
    private fun dial(): ByteArray? {
        val raw = Socket()
        socket = raw
        raw.connect(InetSocketAddress(host, port), 5000)   // iOS 956-984: five seconds to stand, or the next road's turn
        val s: Socket = if (port == 443) tls(raw) ?: return null else raw
        socket = s
        s.soTimeout = 8000   // the handshake's own budget (iOS handshakeBudget 687)
        input = DataInputStream(s.getInputStream())
        out = s.getOutputStream()
        val kem = read()?.takeIf { it.size == 1184 } ?: return fail("noise-key")
        val seed = MtBindings.nativeRandom(32) ?: return fail("noise-seed")
        val st = LongArray(1)
        val msg1 = MtBindings.nativeNoiseInitiator1(kem, seed, st)
        held1 = st[0]
        if (msg1 == null) return fail("noise-msg1")
        write(msg1)
        val msg2 = read()?.takeIf { it.size == 6349 } ?: return fail("noise-msg2")
        val s1 = held1
        held1 = 0L   // the core consumes it, answer or refusal
        held2 = MtBindings.nativeNoiseInitiator2(s1, msg2)
        if (held2 == 0L) return fail("noise-msg2-open")
        val s2 = held2
        held2 = 0L
        val done = MtBindings.nativeNoiseInitiator3(s2)?.takeIf { it.size == 5261 + 96 } ?: return fail("noise-msg3")
        write(done.copyOfRange(0, 5261))
        channelHash = done.copyOfRange(5325, 5357)
        sendKey = done.copyOfRange(5261, 5293)
        return done.copyOfRange(5293, 5325)
    }

    /** The one dialled (iOS acceptInbound 1591-1644): my KEM key first, then msg1 → msg2 → msg3; the keys are the dialler's mirror. */
    private fun respond(): ByteArray? {
        val s = accepted ?: return null
        socket = s
        s.soTimeout = 8000
        input = DataInputStream(s.getInputStream())
        out = s.getOutputStream()
        val kem = NodeKem.device() ?: return fail("no-kem")
        write(kem.first)
        val msg1 = read()?.takeIf { it.size == 2272 } ?: return fail("noise-msg1")
        val seed = MtBindings.nativeRandom(32) ?: return fail("noise-seed")
        val st = LongArray(1)
        val msg2 = MtBindings.nativeNoiseResponder1(kem.second, seed, msg1, st)
        held3 = st[0]
        if (msg2 == null) return fail("noise-msg2")
        write(msg2)
        val msg3 = read()?.takeIf { it.size == 5261 } ?: return fail("noise-msg3")
        val s3 = held3
        held3 = 0L   // the core consumes it, answer or refusal
        val keys = MtBindings.nativeNoiseResponder3(s3, msg3)?.takeIf { it.size == 96 } ?: return fail("noise-msg3-open")
        channelHash = keys.copyOfRange(64, 96)
        sendKey = keys.copyOfRange(32, 64)   // the one dialled sends responder → initiator
        return keys.copyOfRange(0, 32)       // and reads initiator → responder
    }

    private fun tls(raw: Socket): Socket? {
        // ALPN on the platform's own socket stands from Android 10; an older phone waits for the web-socket door
        if (Build.VERSION.SDK_INT < 29) { close("tls-no-alpn"); return null }
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply { applicationProtocols = arrayOf("mt-node"); serverNames = listOf(SNIHostName(host)) }
        s.soTimeout = 8000
        s.startHandshake()
        // the platform's socket checks the chain, not the name: the name is checked here, as a browser checks it
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) { close("tls-name"); return null }
        return s
    }

    private fun read(): ByteArray? {
        val i = input ?: return null
        val n = i.readInt()
        if (n <= 0 || n > (1 shl 20)) return null   // at most 1 MiB a frame (iOS readFrame 1931-1939)
        return ByteArray(n).also { i.readFully(it) }
    }

    private fun write(body: ByteArray) {
        val o = out ?: return
        synchronized(o) { o.write(NetMsg.be4(body.size) + body); o.flush() }
    }

    /** One sealed message of the channel (iOS sendNet 1032-1035): false — the channel could not take it. */
    fun send(type: Int, body: ByteArray): Boolean = sealed(NetMsg.encode(type, body))

    private fun sealed(plain: ByteArray): Boolean {
        val k = sendKey ?: return false
        val s = Wire.seal(k, plain) ?: return false
        return runCatching { write(s) }.isSuccess.also { if (it) lastUsed = System.currentTimeMillis() }
    }

    /** The frame that keeps a standing channel warm (iOS startKeepalive 627-636): [0x02], sealed. */
    fun keepalive() { sealed(byteArrayOf(0x02)) }

    private fun registerInit(body: ByteArray) {
        if (body.size != 1984) return   // the key 1952 ‖ its tag 32
        val pk = body.copyOfRange(0, 1952)
        val tag = body.copyOfRange(1952, 1984)
        if (!Wire.overlayTag(pk).contentEquals(tag)) return   // &3.3: the tag is the key's own
        peerPk = pk
        peerTag = tag
        // the challenge lives the whole greeting and goes inside a signature: drawn from the gathered source
        val n = MtBindings.nativeRandom(16) ?: return close("no-nonce")
        nonce = n
        send(NetMsg.REGISTER_CHALLENGE, n)
    }

    private fun registerProof(body: ByteArray, up: (Channel) -> Unit) {
        val pk = peerPk
        val tag = peerTag
        val n = nonce
        if (body.size != 3309 || pk == null || tag == null || n == null || !MtBindings.nativeVerify(pk, signable(tag, n), body)) {
            send(NetMsg.REGISTER_RESULT, byteArrayOf(0x01))
            return
        }
        peerReg = true
        send(NetMsg.REGISTER_RESULT, byteArrayOf(0x00))
        maybeReady(up)
    }

    /** &5.1 proof of ownership (iOS MontanaOverlayProof 245-265): «mt-reg» ‖ 0 ‖ the tag ‖ the challenge ‖ the channel's hash. */
    private fun signable(resource: ByteArray, challenge: ByteArray) = "mt-reg".toByteArray() + byteArrayOf(0) + resource + challenge + channelHash

    private fun maybeReady(up: (Channel) -> Unit) {
        if (ready || !myReg || !peerReg) return
        ready = true
        // the node keeps the channel warm every eight seconds: thirty silent seconds are a dead road, not a quiet one
        runCatching { socket?.soTimeout = 30_000 }
        Log.d("Montana", "reg_ready at=" + label + " ms=" + (System.currentTimeMillis() - dialAt))
        up(this)
    }

    /** The channel ends, and its first reason stands: a later one is only the echo of the first. */
    fun close(why: String) {
        if (closeWhy.isEmpty()) closeWhy = why
        runCatching { socket?.close() }
    }
}

/**
 * THE PHONE HOLDS ITS NODES (iOS MontanaNodes.open 263-330, MontanaP2PDirect 627-677): a node is a meeting and transit point, and
 * the link to it is held for as long as the app lives. Every bare door of the network's list is knocked unless it stands; a door
 * that did not stand waits its step — two seconds, doubled with a spread at every knock, under fifteen, and up to ten minutes
 * once it refused. Every eight seconds each standing channel is kept warm, and a neighbour that has not named its owner is given
 * ours again, ten times at most. A frame that arrives is acknowledged when it is mine and carried on when it is not.
 */
object Channels {
    private const val FRAME_MEMORY = 8192   // iOS frameMemory 468
    private const val STEP_MIN = 2.0
    private const val STEP_MAX = 15.0
    private const val STEP_DEAD_MAX = 600.0   // iOS MontanaNodes 249-255
    private const val PATH_STEP_MIN = 5.0
    private const val PATH_STEP_MAX = 60.0   // iOS MontanaP2PNode 526-527
    const val PORT = 8447   // iOS MontanaDirectPort.value: the mesh's direct channel
    const val ADDRESS = "​​PA:"   // iOS punchEndpointMark (ContentView 3834): a correspondent's dialable address, inside the pipe only
    private val book = HashMap<String, LinkedHashSet<Pair<String, Int>>>()   // a correspondent → the addresses they named (iOS MontanaOverlayBook)
    private val pathAt = HashMap<String, Pair<Long, Double>>()
    private val announcedTo = HashMap<String, Long>()
    private val live = HashMap<String, Channel>()   // a door's label → its channel, standing or being greeted
    private val tried = HashMap<String, Triple<Long, Double, Boolean>>()   // a door's label → (knocked at, the next step in s, refused)
    private val originated = HashSet<String>()
    private val seen = HashSet<String>()
    @Volatile private var owner: ByteArray? = null
    @Volatile private var started = false

    /** Begin holding the network's machines, once, for the app's whole life. */
    fun start() {
        if (started) return
        started = true
        Thread { while (true) { runCatching { knock(); keepPaths() }; Thread.sleep(5000) } }.start()   // iOS maintainPaths: every five seconds
        Thread { listen() }.start()
        Thread { while (true) { Thread.sleep(8000); runCatching { warm() } } }.start()
    }

    /** The device forgets the person: the owner branch goes, and the channels it named close with it. */
    fun forget() {
        owner = null
        val cs = synchronized(live) { live.values.toList() }
        for (c in cs) c.close("forgotten")
    }

    private fun knock() {
        val ident = NodeIdentity.device() ?: return
        val now = System.currentTimeMillis()
        for ((host, port) in Doors.bare()) {
            val label = host + ":" + port
            val c = synchronized(live) {
                if (live.containsKey(label)) return@synchronized null
                val cur = tried[label]
                val step = cur?.second ?: STEP_MIN
                if (now - (cur?.first ?: 0L) < step * 1000) return@synchronized null
                val spread = 0.8 + Math.random() * 0.6   // the spread of a retry, not a quantity of the protocol
                val ceiling = if (cur?.third == true) STEP_DEAD_MAX else STEP_MAX
                tried[label] = Triple(now, minOf(ceiling, step * 2 * spread), cur?.third ?: false)
                Channel(host, port).also { live[label] = it }
            } ?: continue
            Log.d("Montana", "node_knock at=" + label)
            Thread { serve(c, ident) }.start()
        }
    }

    private fun serve(c: Channel, ident: NodeIdentity.Ident) {
        c.run(ident, { up(it) }, { ch, of -> received(ch, of) })
        synchronized(live) {
            live.remove(c.label)
            // a door that stood is knocked again at once; one that did not keeps its step and is judged refused
            if (c.ready) tried.remove(c.label) else tried[c.label]?.let { tried[c.label] = Triple(it.first, it.second, true) }
        }
        Log.d("Montana", (if (c.closeWhy == "peer-closed") "chan_peer_close" else "node_shut") + " at=" + c.label + " why=" + c.closeWhy +
            " ready=" + (if (c.ready) 1 else 0) + " after_ms=" + (System.currentTimeMillis() - c.dialAt))
    }

    private fun up(c: Channel) {
        val paths = synchronized(live) { live.values.count { it.ready } }
        Log.d("Montana", "path_up at=" + c.label + " ms=" + (System.currentTimeMillis() - c.dialAt) + " paths=" + paths)
        // the owner reference leaves before anything else (iOS maybeReady 1130-1146): without it the node cannot count this owner
        Thread { ownerSecret()?.let { c.send(NetMsg.OWNER_REF, Wire.ownerRef(it, c.channelHash)) } }.start()
    }

    /** The owner branch of this person's seed (iOS ownerSecretCached 1115-1122): stretched once aside, then warm. */
    private fun ownerSecret(): ByteArray? = owner ?: synchronized(this) {
        owner ?: MontanaSeed.mnemonic?.let { MtBindings.nativeMnemonicToMasterSeed(it) }?.let { Wire.ownerSecret(it) }?.also { owner = it }
    }

    /** Every eight seconds (iOS startKeepalive 627-677): each standing channel kept warm, a nameless neighbour given ours again. */
    private fun warm() {
        val cs = synchronized(live) { live.values.filter { it.ready } }
        retire(cs)
        for (c in cs) {
            c.keepalive()
            if (c.peerOwnerRef == null && c.ownerAsks < 10) {
                c.ownerAsks++
                ownerSecret()?.let { c.send(NetMsg.OWNER_REF, Wire.ownerRef(it, c.channelHash)) }
            }
        }
    }

    /**
     * A NEIGHBOUR'S CHANNEL THAT CARRIES NOTHING IS CLOSED (iOS retireIdleChannels, 679-687): three minutes without a frame, and
     * beyond twelve the least recently used goes first; presence brings it back, so being wrong costs one dial. A node's channel
     * is held for the app's whole life and is never retired here.
     */
    private fun retire(cs: List<Channel>) {
        val now = System.currentTimeMillis()
        val doors = Doors.bare().map { it.first + ":" + it.second }.toSet()
        val peers = cs.filter { it.label !in doors }.sortedBy { it.lastUsed }
        for ((i, c) in peers.withIndex()) {
            if (now - c.lastUsed > 180_000L || peers.size - i > 12) c.close("idle")
        }
    }

    /**
     * A FRAME THAT ARRIVED (iOS handleOverlayFrame 1315-1428): an ack ends its wait; our own frame back and a repeat go no further;
     * a frame under the tag of one of my pipes, or at my position in an accepted window, is mine — acknowledged down the channel
     * it came by; anything else is carried on.
     */
    private fun received(c: Channel, of: OverlayFrame) {
        val id = Wire.hex(of.msgId)
        if (of.type == OverlayFrame.ACK) {
            c.sentAt.remove(id)?.let { Log.d("Montana", "e2e_ack rtt_ms=" + (System.currentTimeMillis() - it)) }
            return
        }
        synchronized(seen) {
            if (id in originated) return
            if (id in seen) return
            if (seen.size > FRAME_MEMORY) seen.clear()
            seen.add(id)
        }
        val me = NodeIdentity.device() ?: return
        val underTag = (16 until 32).all { of.dst[it] == 0.toByte() }
        val conv = if (underTag) myPipes()[Wire.hex(of.dst.copyOfRange(0, 16))] else null
        val mine = conv != null || (!underTag && Wire.myPositions(me.tag).any { it.contentEquals(of.dst) })
        if (!mine) { carry(c, of, underTag, me); return }
        // an acknowledgement goes back down the channel it came by and addresses nobody (iOS 1423-1428)
        c.send(NetMsg.OVERLAY_FRAME, OverlayFrame(OverlayFrame.ACK, ByteArray(32), of.msgId, ByteArray(0)).encode())
        Log.d("Montana", "of_mine by=" + (if (conv != null) "pipe" else "window-pos") + " bytes=" + of.payload.size)
        if (conv != null) Thread { open(conv, of.payload) }.start()   // the channel's read loop never waits on a letter's landing
    }

    /**
     * MY LETTER OFF A CHANNEL (iOS handleOverlayFrame 1455-1572): 0x01 ‖ the pipe's tag ‖ the body sealed with the pipe's key — the
     * conversation is named by the letter's own tag, never by the channel it came by, and the pipe's seal is the proof of the sender;
     * 0x02 ‖ a first letter's ciphertext ‖ the body, under a pipe that already stands — the tag is older than the envelope, so it
     * opens with the pipe's secret. The words land at the same door as the box's (Post.letter): the second copy is dropped by its mid.
     */
    private fun open(conv: String, env: ByteArray) {
        val (from, sealed) = when (env.firstOrNull()?.toInt()) {
            0x01 -> {
                if (env.size <= 17) return
                val f = myPipes()[Wire.hex(env.copyOfRange(1, 17))] ?: return Unit.also { Log.d("Montana", "open_refused — not our pipe") }
                f to env.copyOfRange(17, env.size)
            }
            0x02 -> if (env.size <= 1 + 1088) return else conv to env.copyOfRange(1 + 1088, env.size)
            else -> return   // a piece, a wake, a point of rendezvous: later steps of the fork
        }
        val secret = Book.secret(from) ?: return
        val w = Wire.minute()
        val plain = listOf(w, w - 1, w + 1).firstNotNullOfOrNull { Wire.open(Wire.bodyKey(secret, it), sealed) }
            ?: return Unit.also { Log.d("Montana", "open_refused — the pipe seal did not match, a forgery or a foreign window") }
        Post.fromChannel(from, plain)
    }

    /**
     * A LETTER LAID BEFORE THE NODES UNDER ITS PIPE'S TAG (iOS MontanaP2PNode.sendP2P 963-971 and 1047-1057, sendUnderTag 1289-1304):
     * 0x01 ‖ the pipe's tag of this window ‖ the body sealed with the pipe's key of this window — whoever holds the tag opens it,
     * the rest carry it, and the node reads zero bytes. Only when the path is assembled: a standing channel whose machine named its
     * owner (MontanaPath.hopMin = 1). The box's road goes on beside it, and the receiving side drops the second copy by its mid.
     */
    fun lay(secret: ByteArray, mid: String, body: ByteArray): Boolean {
        val cs = synchronized(live) { live.values.filter { it.ready } }
        // the correspondent's own channel never counts as a carrier (iOS ownerRefsOfLiveChannels(excluding:) 1008)
        val to = Wire.reference(secret)
        val owners = cs.filter { it.ref != to }.mapNotNull { c -> c.peerOwnerRef?.let { Wire.hex(it) } }.toSet().size
        if (owners < 1) return false
        val w = Wire.minute()
        val tag = Wire.pipeTag(secret, w)
        val sealed = Wire.seal(Wire.bodyKey(secret, w), body) ?: return false
        val id = MtBindings.nativeRandom(16) ?: return false
        synchronized(seen) {
            if (originated.size > FRAME_MEMORY) originated.clear()
            originated.add(Wire.hex(id))
        }
        val frame = OverlayFrame(OverlayFrame.RELAY, tag + ByteArray(16), id, byteArrayOf(0x01) + tag + sealed).encode()
        val used = cs.count { it.send(NetMsg.OVERLAY_FRAME, frame) }
        if (used > 0) Log.d("Montana", "sent_route mid=" + mid.take(8) + " route=node nodes=" + used)
        return used > 0
    }

    /**
     * NOT MINE (iOS 1367-1421): an envelope under a foreign label goes on to every other channel — its holder opens it, the rest
     * carry it, and a repeat is free because every machine filters by the frame's id. A frame at a position goes to the neighbour
     * standing at it, else to the one nearest it and strictly nearer than me, else its road ends here.
     */
    private fun carry(from: Channel, of: OverlayFrame, underTag: Boolean, me: NodeIdentity.Ident) {
        val others = synchronized(live) { live.values.filter { it.ready && it !== from && !(it.peerTag contentEquals from.peerTag) } }
        val body = of.encode()
        if (underTag) {
            for (o in others) o.send(NetMsg.OVERLAY_FRAME, body)
            return
        }
        val w = Wire.minute()
        others.firstOrNull { o -> o.peerTag?.let { Wire.windowPos(it, w).contentEquals(of.dst) } == true }?.let { it.send(NetMsg.OVERLAY_FRAME, body); return }
        val myDist = distance(Wire.windowPos(me.tag, w), of.dst)
        var best: Channel? = null
        var bestDist = myDist
        for (o in others) {
            val t = o.peerTag ?: continue
            val d = distance(Wire.windowPos(t, w), of.dst)
            if (nearer(d, bestDist)) { best = o; bestDist = d }
        }
        if (best != null) best.send(NetMsg.OVERLAY_FRAME, body) else Log.d("Montana", "of_relay_drop — nobody is nearer than us")
    }

    /**
     * THE PHONE ANSWERS ON ITS DIRECT PORT (iOS MontanaP2PDirect listener 526-560, acceptInbound 1591-1644): a neighbour or a
     * correspondent who was named this phone's address dials it, and the greeting is the same, mirrored.
     */
    private fun listen() {
        val server = runCatching { java.net.ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull()
        if (server == null) { Log.d("Montana", "listen_fail port=" + PORT); return }
        Log.d("Montana", "listen port=" + PORT)
        while (true) {
            val s = runCatching { server.accept() }.getOrNull()
            if (s == null) { Thread.sleep(1000); continue }
            val ip = s.inetAddress?.hostAddress?.substringBefore('%')
            val ident = NodeIdentity.device()
            if (ip == null || ident == null) { runCatching { s.close() }; continue }
            Log.d("Montana", "conn_in")
            val c = Channel(ip, s.port, accepted = s)
            synchronized(live) { live[c.label] = c }
            Thread { serve(c, ident) }.start()
        }
    }

    /** A correspondent's own door, by TCP (iOS ensureChannel): the channel a node gets, bound to the correspondent's name. */
    private fun dialPeer(ip: String, port: Int, ref: String) {
        val ident = NodeIdentity.device() ?: return
        val c = synchronized(live) {
            val label = ip + ":" + port
            if (live.containsKey(label)) return@synchronized null
            Channel(ip, port, ref).also { live[label] = it }
        } ?: return
        Thread { serve(c, ident) }.start()
    }

    /**
     * THE PATHS TO CORRESPONDENTS ARE KEPT (iOS maintainPaths 530-573): the addresses a correspondent named are dialled on our own
     * cadence, each backing off from five seconds to a minute, four dials a round at most; one who stands on a channel forgets the
     * rest. A call owns the radio: while it stands only its correspondent is walked. An IPv6 door of theirs is dialled only from
     * an IPv6 of our own (8d611789f1): without one the dial dies before it leaves.
     */
    private fun keepPaths() {
        val now = System.currentTimeMillis()
        val callPeer = if (Calls.busy()) Calls.peer() else null
        val v6Here = selfV6().isNotEmpty()
        val entries = synchronized(book) { book.mapValues { it.value.toList() } }
        var attempts = 0
        for ((ref, eps) in entries) {
            if (callPeer != null && ref != callPeer) continue
            if (synchronized(live) { live.values.any { it.ready && it.ref == ref } }) {
                synchronized(pathAt) { for (e in eps) pathAt.remove(e.first + ":" + e.second) }
                continue
            }
            for ((ip, port) in eps) {
                if (!isGlobal(ip) || !(v6Here || !ip.contains(':'))) continue
                if (attempts >= 4) return
                val key = ip + ":" + port
                val due = synchronized(pathAt) {
                    val cur = pathAt[key]
                    val step = cur?.second ?: PATH_STEP_MIN
                    if (now - (cur?.first ?: 0L) < step * 1000) false else { pathAt[key] = now to minOf(step * 2, PATH_STEP_MAX); true }
                }
                if (!due) continue
                attempts++
                Log.d("Montana", "path_try")
                dialPeer(ip, port, ref)
            }
        }
    }

    /** The global IPv6 of a real interface (iOS MontanaSelfEndpoint.candidates): a tunnel's address is not the phone's own. */
    private fun selfV6(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet6Address>()
            .mapNotNull { it.hostAddress?.substringBefore('%') }.filter { isGlobal(it) }
    }.getOrDefault(emptyList())

    /**
     * THE ONE ADDRESS OF MY OWN A CORRESPONDENT CAN DIAL BY TCP (iOS MontanaNATService.selfEndpoint 27-39): a global IPv6 of a real
     * interface with the direct port. A carrier-NAT IPv4 is never named — nobody can dial it. (A port the router forwards: the
     * router's own word is asked by iOS MontanaPortMap — not on this side yet.)
     */
    private fun selfEndpoint(): String? = selfV6().firstOrNull()?.let { "[" + it + "]:" + PORT }

    /** My dialable address to a correspondent (iOS MontanaNATService.announce 41-56): a silent word in the pipe, once a minute each. */
    fun announce(ref: String) {
        val now = System.currentTimeMillis()
        synchronized(announcedTo) {
            if (now - (announcedTo[ref] ?: 0L) < 60_000L) return
            announcedTo[ref] = now
        }
        val mine = selfEndpoint() ?: return
        Post.send(ref, Marks.mintMid(), ADDRESS + mine, quiet = true)
        Log.d("Montana", "addr_announce")
    }

    /** A word from a correspondent: the pipe lives, and if no road of mine reaches them, my address is named (iOS ChatStore 3938-3947). */
    fun heard(ref: String) {
        if (synchronized(live) { live.values.none { it.ready && it.ref == ref } }) announce(ref)
    }

    /** A correspondent named their address (iOS MontanaNATService.peerAnnounced 58-66): into the book, dialled at once, mine named back. */
    fun peerAnnounced(ref: String, endpoint: String) {
        val (ip, port) = split(endpoint) ?: return
        synchronized(book) { book.getOrPut(ref) { LinkedHashSet() }.add(ip to port) }
        Log.d("Montana", "addr_peer")
        dialPeer(ip, port, ref)
        announce(ref)
    }

    /** «[v6]:port» or «v4:port» (iOS MontanaEndpointParse.split): a string without a port is not an address. */
    private fun split(ep: String): Pair<String, Int>? {
        val e = ep.trim()
        if (e.startsWith("[")) {
            val close = e.indexOf(']')
            if (close < 0) return null
            val p = e.substring(close + 1).removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return e.substring(1, close) to p
        }
        if (e.count { it == ':' } != 1) return null
        val p = e.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return e.substringBefore(':') to p
    }

    /** A global address (iOS MontanaTransport.isGlobalIP, MontanaNetFrames 47-54): not private, not link-local, not a loopback. */
    fun isGlobal(raw: String): Boolean {
        val ip = raw.substringBefore('%')
        val low = ip.lowercase()
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.") || ip == "127.0.0.1") return false
        if (ip.startsWith("172.")) { val o = ip.split('.').getOrNull(1)?.toIntOrNull(); if (o != null && o in 16..31) return false }
        if (low.startsWith("fe80") || low.startsWith("fc") || low.startsWith("fd") || low == "::1") return false
        return true
    }

    /** The distance between two claims: a bytewise exclusive OR (iOS MontanaP2PDirect.distance 1800-1806). */
    private fun distance(a: ByteArray, b: ByteArray) = if (a.size != b.size) ByteArray(32) { -1 } else ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    private fun nearer(a: ByteArray, b: ByteArray): Boolean {
        for (i in a.indices) { val x = a[i].toInt() and 0xff; val y = b[i].toInt() and 0xff; if (x != y) return x < y }
        return false
    }

    /** The tags of my pipes across the accepted windows (iOS MTPipeBook.tagIndex 624-637): rebuilt once a window. */
    private var pipesAt = -1L
    private var pipes = emptyMap<String, String>()
    private fun myPipes(): Map<String, String> = synchronized(this) {
        val w = Wire.minute()
        if (w != pipesAt) {
            val m = HashMap<String, String>()
            for (ref in Book.refs()) {
                val s = Book.secret(ref) ?: continue
                for (k in longArrayOf(w - 1, w, w + 1)) m[Wire.hex(Wire.pipeTag(s, k))] = ref
            }
            pipes = m
            pipesAt = w
        }
        pipes
    }
}
