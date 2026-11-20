package quest.montana.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import montanaxray.Montanaxray
import montanaxray.Protector
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// ─────────────── the VPN wall's tunnel (iOS PacketTunnel/PacketTunnelProvider.swift, MontanaPacketPump.swift, MTTunnelRecovery.swift;
// Montana/MontanaVPNTunnel.swift; MTWalletCore.swift: MTVPNWallMint; checked against iOS 2460) ───────────────
//
// iOS carries the tunnel in its own process, the packet-tunnel extension, and the app speaks to it by provider messages. On Android
// the tunnel is the app's own VpnService in the app's own process: the same engine (Xray-core through the Montana wrapper), the same
// pump (hev-socks5-tunnel), the same tun settings and the same judges of its life, and the app's side reads it directly where the
// iPhone asks it a message. The engine's own sockets are handed to VpnService.protect (protect_android.go) and its own lookups go out
// through the phone's network (SetResolvers): what the system does for the extension's sockets on iOS.

/** One line of the tunnel in the diary (iOS ExtLog and MontanaVPNLog: vpn.log, ext.log). */
private fun tunnelLog(m: String) { Log.i("Montana", "vpn_tunnel $m") }
private fun appLog(m: String) { Log.i("Montana", "vpn_app $m") }

/** hev-socks5-tunnel (MIT), the pump between the tun and the engine's SOCKS inbound (iOS MontanaPacketPump): its own JNI face, the
 *  natives it registers on this class (build.py hev: -DPKGNAME=quest/montana/app -DCLSNAME=HevTunnel). */
object HevTunnel {
    init { System.loadLibrary("hev-socks5-tunnel") }
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    /** [tx packets, tx bytes, rx packets, rx bytes]: tx read from the tun -- the apps' bytes out --, rx written to it -- the bytes in. */
    @JvmStatic external fun TProxyGetStats(): LongArray?
}

/**
 * THE TUNNEL'S LIVING SECONDS (iOS MTTunnelAlive, MTTunnelRecovery.swift:101-122; the author's words 04.10.2026 17:13-17:24 MSK): the
 * tunnel is the one witness of its own life and counts it -- the stretch from a judge that held it alive to the next judge, an hour at
 * most a stretch; the clock runs through sleep and ignores the wall clock. The count outlives the session under its epoch.
 */
class VpnAlive(total: Double) {
    companion object {
        const val TOTAL_KEY = "tunnelAlive.total"
        const val EPOCH_KEY = "tunnelAlive.epoch"
        const val LONGEST = 3600.0
        fun clock(): Double = SystemClock.elapsedRealtimeNanos() / 1e9
        fun epoch(): String {
            Prefs.str(EPOCH_KEY, "").takeIf { it.isNotEmpty() }?.let { return it }
            val e = UUID.randomUUID().toString().uppercase(Locale.ROOT)
            Prefs.setStr(EPOCH_KEY, e)
            return e
        }
    }
    var total = maxOf(0.0, total); private set
    var last: Double? = null; private set
    @Synchronized fun judge(alive: Boolean, now: Double) { total += stretch(now); last = if (alive) now else null }
    @Synchronized fun told(now: Double): Int = (total + stretch(now)).toInt()
    @Synchronized fun held(): Boolean = last != null
    private fun stretch(now: Double): Double = last?.let { at -> minOf(LONGEST, maxOf(0.0, now - at)) } ?: 0.0
}

/**
 * WHEN A SESSION IS DEAD (iOS MTTunnelDeath, MTTunnelRecovery.swift:71-99; the author's word 29.09 «let a dead session release
 * itself»): the road asked and not answering (VpnRoadWord) for ninety seconds, through every raise of the engine -- the session is released. The
 * system's own word that it carries nothing into the tunnel has no witness here: the tunnel stands in the app's own process, so the
 * restart and the «blocked» release of the iPhone never come (blocked is always 0).
 */
class VpnDeath {
    companion object { const val UPSTREAM_LIMIT = 90.0; const val BLOCKED_AFTER = 20.0 }
    // no restart verdict since iOS a61b0b2f (the author's word 09.11.2026): a blocked session is released and stays down until a finger
    sealed class Verdict { object Alive : Verdict(); class Release(val why: String) : Verdict() }
    var silentSince: Double? = null; private set
    fun reset() { silentSince = null }
    fun judge(now: Double, phonePath: Boolean, answered: Boolean, unanswered: Boolean, blocked: Double): Verdict {
        if (!phonePath) { silentSince = null; return Verdict.Alive }
        if (answered) silentSince = null else if (unanswered && silentSince == null) silentSince = now
        silentSince?.let { if (UPSTREAM_LIMIT <= now - it) return Verdict.Release("upstream") }
        return if (BLOCKED_AFTER <= blocked) Verdict.Release("blocked") else Verdict.Alive
    }
}

/**
 * THE ROAD'S WORD ON A BEAT (iOS MTTunnelRoad, MTTunnelRecovery.swift, 0504c2cf; the critic 17.10.2026, findings 3 and 4): bytes are a
 * hook alone -- what only an upstream gives (ANSWER_FLOOR in a beat) answers by itself, nothing either way is idleness, and everything
 * between is put to the probe, the 204-endpoint asked through the running engine's own outbound (Montanaxray.probeRunning), whose
 * answer holds HELD seconds; a raise of the engine or a new path of the phone forgets it.
 */
class VpnRoadWord {
    enum class Word { IDLE, ANSWERED, UNANSWERED }
    companion object { const val ANSWER_FLOOR = 64 * 1024L; const val HELD = 60.0 }
    var probedAt: Double? = null; private set
    var answered = false; private set
    fun forget() { probedAt = null }
    fun word(dtx: Long, drx: Long, now: Double, probe: () -> Boolean): Word {
        if (ANSWER_FLOOR <= drx) return Word.ANSWERED
        if (dtx <= 0 && drx <= 0) return Word.IDLE
        probedAt?.let { at -> if (now - at < HELD) return if (answered) Word.ANSWERED else Word.UNANSWERED }
        answered = probe()
        probedAt = now
        return if (answered) Word.ANSWERED else Word.UNANSWERED
    }
}

/** The road the person's packets take, asked through the running engine's own outbound (iOS MontanaXrayBridge.probeRunning, the
 *  wrapper's ProbeRunning): its delay, or null when it did not answer within the probe's deadline. No second engine stands. */
fun vpnProbeRunning(): Long? = runCatching { Montanaxray.probeRunning() }.getOrNull()?.takeIf { 0 <= it }

/** The recovery's breath (iOS MTTunnelRetry): ten seconds, doubled at every failure, five minutes at most. */
class VpnRetry {
    var failures = 0; private set
    private var next = 0.0
    fun failed(now: Double) { failures = minOf(failures + 1, 6); next = now + minOf(300.0, 10.0 * Math.pow(2.0, (failures - 1).toDouble())) }
    fun reset() { failures = 0; next = 0.0 }
    fun ready(now: Double): Boolean = now >= next
}

/** THE RECONNECT AND THE DEAD MARK (iOS ExtReconnect, PacketTunnelProvider.swift:906-1000): every session's arm takes the dead mark away;
 *  the release of a dead session lifts the reconnect and leaves its moment, which the app reads at the fall or at its next return. */
object VpnReconnect {
    const val DEAD_KEY = "mt.vpn.deadAt"
    const val DEAD_ROW_KEY = "mt.vpn.deadRow"
    fun arm() = Prefs.remove(DEAD_KEY, DEAD_ROW_KEY)
    fun lift(dead: Double, row: String) {
        VpnTunnel.setRequested(false)
        Prefs.setStr(DEAD_KEY, dead.toString())
        Prefs.setStr(DEAD_ROW_KEY, row)
    }
    val deadAt: Double? get() = Prefs.str(DEAD_KEY, "").toDoubleOrNull()
    val deadRow: String get() = Prefs.str(DEAD_ROW_KEY, "")
}

/**
 * THE TUNNEL (iOS PacketTunnelProvider): the tun the system gives, the engine on the chosen server, the pump between them, and the life
 * watch every ten seconds. One line serves every act on the engine (iOS engineLine) -- a start, a server's swap, a raise, the watch --
 * and it is the process's own, so a session that ends and the next that begins never overlap on the one engine.
 */
class MontanaVpnService : VpnService() {
    companion object {
        const val START = "quest.montana.app.vpn.START"
        const val STOP = "quest.montana.app.vpn.STOP"
        const val ROW = "row"
        const val BY = "by"
        const val SOCKS_PORT = 10808
        private const val SAMPLE_S = 10L
        private const val SILENT_AFTER = 20_000L
        private const val OUT_FLOOR = 16 * 1024L   // bytes out per sample that make silence a verdict, not idleness
        private const val IN_FLOOR = 1024L         // bytes in per sample that still count as silence (the ACKs of dead flows)
        /** THE PUMP'S CEILING (iOS sessionCeiling 32, derived from the extension's 50 MB): the app's own process has no such ceiling,
         *  so the library's own number stands -- a page that waits for its thirty-third flow is a harm the iPhone took only to live. */
        private const val SESSIONS = 128
        private val engine = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mt-vpn-engine").apply { isDaemon = true } }
        private val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mt-vpn-clock").apply { isDaemon = true } }
        @Volatile var live: MontanaVpnService? = null; private set
        /** The tunnel's count of its living seconds, whether it holds itself alive, and the count's epoch (iOS «alive»). */
        fun alive(): Triple<Int, Boolean, String>? {
            val s = live ?: return null
            if (!s.standing) return null
            return Triple(s.living.told(VpnAlive.clock()), s.living.held(), VpnAlive.epoch())
        }
        /** The pump's counters and the life watch's word (iOS «stats»): txb, rxb, the seconds of silence, the raises. */
        fun stats(): LongArray? {
            val s = live ?: return null
            if (!s.standing) return null
            val p = HevTunnel.TProxyGetStats() ?: return null
            return longArrayOf(p[1], p[3], s.lifeSilentSince?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0, s.lifeRaises.toLong())
        }
    }

    private class Life { @Volatile var alive = true; @Volatile var up = false; fun stop() { alive = false } }
    private class Sample(val txb: Long, val rxb: Long, val at: Long)
    private class Candidate(val row: String, val outbound: JSONObject)

    private var run = Life().apply { stop() }
    /** A session stands: the tun is up and the engine's line serves it. */
    val standing: Boolean get() = run.alive && run.up
    private var tun: ParcelFileDescriptor? = null
    private var engineConfig: String? = null
    private var candidate: Candidate? = null
    private var profileRow = ""
    private var recovering = false
    private var releasing = false
    private var recoveryAttempts = 0
    private val retry = VpnRetry()
    private val death = VpnDeath()
    private val road = VpnRoadWord()   // the road's word: bytes a hook, the probe through the engine the verdict (iOS road)
    private var pathMark = ""   // the networks and their cost the backoff last started over at (iOS pathMark)
    private var lifeTimer: ScheduledFuture<*>? = null
    private var lifeLast: Sample? = null
    @Volatile private var lifeSilentSince: Long? = null
    private var lifeNextRaise = 0L
    @Volatile private var lifeRaises = 0
    private var lifeRaiseStep = 30_000L
    private var lifeInAfterRaise = false
    private lateinit var living: VpnAlive
    private var livingRx = 0L
    private val paths = HashSet<Network>()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val protector = object : Protector { override fun protect(fd: Long): Boolean = this@MontanaVpnService.protect(fd.toInt()) }
    private val phonePathUp: Boolean get() = synchronized(paths) { paths.isNotEmpty() }

    override fun onCreate() {
        super.onCreate()
        DeviceVault.init(applicationContext)
        Prefs.init(applicationContext)
        Book.ctx = applicationContext
        living = VpnAlive(Prefs.str(VpnAlive.TOTAL_KEY, "0").toDoubleOrNull() ?: 0.0)
        live = this
        watchPaths()
        tunnelLog("provider initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == STOP -> engine.execute { end(0, null, "stop: ${intent.getStringExtra(BY) ?: "the person's off"}") }
            intent?.action == START -> {
                val row = intent.getStringExtra(ROW)
                val by = intent.getStringExtra(BY) ?: "app"
                engine.execute { begin(row, by) }
            }
            // THE SYSTEM'S OWN SWITCH (iOS: the switch in Settings drives the profile): the always-on VPN raises the chosen row
            intent?.action == VpnService.SERVICE_INTERFACE -> { VpnTunnel.setRequested(true); engine.execute { begin(null, "system") } }
            // THE TUNNEL RISES BY THE HAND ALONE (iOS a61b0b2f, the author's word 09.11.2026): nothing of ours raises a session the
            // process lost -- no sticky restart, no reconnect (iOS: no profile carries On Demand)
            else -> engine.execute { end(0, null, "the tunnel rises by the hand alone") }
        }
        return START_NOT_STICKY
    }

    /** The system took the VPN away -- another app's VPN, or the person's off in the system's settings (iOS: the panel's own off). */
    override fun onRevoke() {
        tunnelLog("revoked by the system")
        VpnTunnel.setRequested(false)
        engine.execute { end(0, null, "revoked by the system") }
    }

    override fun onDestroy() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        val me = this
        engine.execute { if (run.alive) teardown("destroyed"); if (live === me) live = null }
        super.onDestroy()
    }

    // ── the engine's heat (iOS 33861587: the collector at its own pace, one release in half a minute, the heat measured) ──
    private var lastFreed = 0L   // the last release of the runtime's pages
    private var lastCpu = 0L     // the process's own milliseconds of the cores at the minute line before
    private var heatBeats = 0
    /** ONE RELEASE IN HALF A MINUTE (iOS startMemoryPressureWatch; the critic 17.10.2026: fourteen full collections in 200 ms): the
     *  system asks in bursts, and every release is a full collection of the runtime -- a burst is answered once. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val now = SystemClock.elapsedRealtime()
        if (now - lastFreed < 30_000L) return
        lastFreed = now
        engine.execute { if (run.alive) tunnelLog("memory pressure level=$level: ${runCatching { Montanaxray.freeMemory() }.getOrDefault("")}") }
    }
    /** THE HEAT IS MEASURED HERE (iOS startMemoryWatch's minute line): the runtime's memory, the process's own share of one core over the
     *  minute, and the system's thermal state (0 none, 1 light, 2 moderate, 3 severe ...). Once a minute on the life watch's beat. */
    private fun heatLine() {
        if (++heatBeats < (60 / SAMPLE_S).toInt()) return
        heatBeats = 0
        val cpu = android.os.Process.getElapsedCpuTime()
        val pct = if (lastCpu == 0L) 0 else Math.round((cpu - lastCpu) / 600.0).toInt()
        lastCpu = cpu
        val thermal = if (29 <= Build.VERSION.SDK_INT) getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: -1 else -1
        tunnelLog("heat cpu=$pct thermal=$thermal ${runCatching { Montanaxray.memStats() }.getOrDefault("")}")
    }

    // ── the session ──

    private fun begin(rowId: String?, by: String) {
        val want = rowId ?: VpnTunnel.selectedId
        val cfg = VpnStore.load().firstOrNull { it.id == want && it.proto != VpnProto.UNSUPPORTED }
        if (cfg == null || VpnStore.isRetired(cfg.id)) { end(1, "bad tunnel config", "no row the engine speaks"); return }
        if (run.alive) teardown("a new start")   // a later start ends the session of the moment (iOS lifetime.stop())
        val life = Life()
        run = life
        // the startup's own deadline (iOS 45 s): the session ends -- the tunnel rises by the hand alone (iOS a61b0b2f)
        clock.schedule({ if (life.alive && !life.up) engine.execute { if (run === life && !life.up) { tunnelLog("startup deadline reached; the session ends -- the tunnel rises by the hand alone"); restart(8, "Tunnel startup timed out") } } }, 45, TimeUnit.SECONDS)
        tunnelLog("── session start by=$by ──")
        VpnReconnect.arm()   // ON is kept; the dead mark of an earlier release goes with this arm
        // a profile has exactly the outbound the person selected: no rotation is carried into the tunnel
        val outbound = XrayConfig.outbound(cfg)
        candidate = Candidate(cfg.id, outbound)
        death.reset(); releasing = false; recoveryAttempts = 0; retry.reset()
        lifeLast = null; lifeSilentSince = null; lifeRaises = 0; lifeRaiseStep = 30_000L; lifeInAfterRaise = false; lifeNextRaise = 0
        profileRow = cfg.id
        VpnTunnel.moved(VpnTunnel.Status.CONNECTING)
        Montanaxray.setProtector(protector)
        Montanaxray.setResolvers(physicalDns())
        val pfd = try { builder().establish() } catch (e: Exception) { tunnelLog("tun settings error: ${e.javaClass.simpleName}"); null }
        if (pfd == null) { end(9, null, "the system gave no tun: no consent of ours stands"); return }
        if (!life.alive) { runCatching { pfd.close() }; return }
        tun = pfd
        val cfgStr = XrayConfig.tunnel(outbound, SOCKS_PORT)
        engineConfig = cfgStr
        val initialError = runEngine(cfgStr)
        if (initialError != null) tunnelLog("initial engine failed; waiting for a working fallback")
        recovering = initialError != null
        if (!life.alive) { Montanaxray.stop(); return }
        tunnelLog("engine start attempted: ready=${initialError == null}")
        // THE SESSION IS BORN BY THE ROAD'S ANSWER (iOS 0504c2cf; the critic 17.10.2026, finding 3: a session born on a dead server held
        // the phone 1 h 26 min with nothing in): an engine that stood is asked through its own outbound before the tunnel is said to
        // stand; a road that does not answer on the phone's own network leaves its dead mark and the start is refused with its reason.
        if (initialError == null && phonePathUp && vpnProbeRunning() == null) {
            Montanaxray.stop()
            tunnelLog("birth refused: the engine stood and the road did not answer its probe -- the start is refused")
            VpnReconnect.lift(System.currentTimeMillis() / 1000.0, profileRow)
            end(7, "The VPN carried nothing: VPN off", "birth refused")
            return
        }
        if (initialError == null && !startPump()) recovering = true
        life.up = true
        tunnelLog("tunnel UP")
        startLifeWatch()
        pathMark = pathMarkNow()
        VpnTunnel.moved(if (recovering) VpnTunnel.Status.REASSERTING else VpnTunnel.Status.CONNECTED)
    }

    /** THE TUN (iOS NEPacketTunnelNetworkSettings): IPv4 198.18.0.1/16 and IPv6 fd6d:6f6e:7461::1/64, every route, MTU 1400, the four
     *  resolvers; multicast and the local network stay out (iOS excludedRoutes, excludeLocalNetworks); a socket bound to another
     *  network may leave the tunnel, as the iPhone's profile without the protection of all networks lets it (includeAllNetworks off). */
    private fun builder(): Builder = Builder().apply {
        setSession("Montana")
        setMtu(1400)
        addAddress("198.18.0.1", 16)
        addRoute("0.0.0.0", 0)
        addAddress("fd6d:6f6e:7461::1", 64)
        addRoute("::", 0)
        for (d in listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888")) addDnsServer(d)
        if (33 <= Build.VERSION.SDK_INT) {
            runCatching { excludeRoute(IpPrefix(InetAddress.getByName("224.0.0.0"), 4)) }
            runCatching { excludeRoute(IpPrefix(InetAddress.getByName("ff00::"), 8)) }
            for (p in localNetworks()) runCatching { excludeRoute(p) }
        }
        allowBypass()
        if (29 <= Build.VERSION.SDK_INT) setMetered(false)   // the tunnel's meteredness follows the network under it
        setConfigureIntent(PendingIntent.getActivity(this@MontanaVpnService, 0, Intent(this@MontanaVpnService, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE))
    }

    private fun physicalNetworks(): List<Network> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION") return cm.allNetworks.filter { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@filter false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
    /** The phone's own network's resolvers, the tunnel's own when it names none: the engine's lookups leave through it (SetResolvers). */
    private fun physicalDns(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val out = LinkedHashSet<String>()
        for (n in physicalNetworks()) cm?.getLinkProperties(n)?.dnsServers?.forEach { a -> a.hostAddress?.let(out::add) }
        if (out.isEmpty()) out.addAll(listOf("1.1.1.1", "8.8.8.8"))
        return out.joinToString(",")
    }
    /** The networks the phone stands on directly (iOS excludeLocalNetworks): printers and routers are reached as they are. */
    private fun localNetworks(): List<IpPrefix> {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        return physicalNetworks().flatMap { n -> cm.getLinkProperties(n)?.routes.orEmpty() }
            .filter { !it.hasGateway() && 0 < it.destination.prefixLength }.map { it.destination }.distinct()
    }

    /** The phone's own path (iOS defaultPath): any network beside a VPN that reaches the internet; a change asks the recovery again. */
    private fun watchPaths() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { synchronized(paths) { paths.add(n) }; pathMoved() }
            override fun onLost(n: Network) { synchronized(paths) { paths.remove(n) }; pathMoved() }
        }
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
    }
    /** The phone's own road as the backoff reads it (iOS mark(of:)): its networks and their cost -- Wi-Fi to cellular moves it, a lost
     *  network too. */
    private fun pathMarkNow(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        return synchronized(paths) { paths.toList() }.map { n ->
            val metered = cm?.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            n.toString() + (if (metered) "m" else "")
        }.sorted().joinToString(",")
    }
    /** THE BACKOFF HOLDS UNTIL THE PATH ITSELF CHANGES (iOS 33861587; the critic 17.10.2026: a probe every 2-3 s for twenty minutes): a
     *  notice of the same networks and cost is no change of the road, and the reconnect keeps its pace. */
    private fun pathMoved() = engine.execute {
        if (!run.alive) return@execute
        val mark = pathMarkNow()
        if (mark == pathMark) return@execute
        pathMark = mark
        retry.reset()
        road.forget()
        lifeLast = null
        Montanaxray.setResolvers(physicalDns())
        if (phonePathUp && recovering) recoverUpstream()
    }

    private fun runEngine(cfgStr: String): String? = try { Montanaxray.run(cfgStr); null } catch (e: Exception) { e.message ?: "engine error" }

    private fun startPump(): Boolean {
        val fd = tun?.fd ?: return false
        val conf = File(noBackupFilesDir, "hev-socks5-tunnel.yml")
        val logFile = File(noBackupFilesDir, "pump.log")
        // iOS MontanaPacketPump's settings, line for line; limit-nofile is left out: here it would bind the whole app's process
        conf.writeText("""
            tunnel:
              mtu: 1400
            socks5:
              port: $SOCKS_PORT
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 16384
              tcp-buffer-size: 8192
              max-session-count: $SESSIONS
              connect-timeout: 5000
              tcp-read-write-timeout: 30000
              udp-read-write-timeout: 30000
              log-file: ${logFile.absolutePath}
              log-level: warn
        """.trimIndent() + "\n")
        val ok = HevTunnel.TProxyStartService(conf.absolutePath, fd)
        tunnelLog("pump start fd=$fd -> 127.0.0.1:$SOCKS_PORT sessions<=$SESSIONS stack=16k buf=8k ok=$ok")
        return ok
    }
    private fun stopPump() { if (HevTunnel.TProxyIsRunning()) { HevTunnel.TProxyStopService(); tunnelLog("pump stop") } }
    private fun pumpStats(): LongArray = HevTunnel.TProxyGetStats() ?: LongArray(4)

    /** THE ENGINE RAISED ANEW IN PLACE (iOS raiseEngine): the tun stays up, the pump re-dials its flows; one road for a server's swap
     *  and for the watchdog, both on the engine's line. */
    private fun raiseEngine(cfgStr: String, why: String): String? {
        if (!run.alive) return "session stopped"
        stopPump()
        Montanaxray.stop()
        runEngine(cfgStr)?.let { tunnelLog("engine raise ($why) failed: $it"); return it }
        if (!run.alive) { Montanaxray.stop(); return "session stopped" }
        engineConfig = cfgStr
        road.forget()   // the probe's answer was the engine's before (iOS raiseEngine)
        if (!startPump()) { Montanaxray.stop(); return "packet pump did not start" }
        tunnelLog("engine raised ($why)")
        return null
    }

    /** SEAMLESS SERVER SWITCH (iOS «plan:»): the new outbound into the running session, the tun stays up. */
    fun swap(rowId: String) = engine.execute {
        if (!run.alive) return@execute
        val cfg = VpnStore.load().firstOrNull { it.id == rowId && it.proto != VpnProto.UNSUPPORTED } ?: return@execute
        val outbound = XrayConfig.outbound(cfg)
        candidate = Candidate(cfg.id, outbound)
        profileRow = cfg.id
        recoveryAttempts = 0; retry.reset()
        lifeLast = null; lifeSilentSince = null; lifeInAfterRaise = false; death.reset()
        recovering = raiseEngine(XrayConfig.tunnel(outbound, SOCKS_PORT), "chosen server") != null
        VpnTunnel.moved(if (recovering) VpnTunnel.Status.REASSERTING else VpnTunnel.Status.CONNECTED)
    }

    // ── the life watch (iOS startLifeWatch, judgeLife, countLife, judgeDeath) ──

    private fun startLifeWatch() {
        lifeTimer?.cancel(false)
        lifeTimer = engine.scheduleAtFixedRate({ runCatching { judgeLife() }.onFailure { tunnelLog("life watch: ${it.javaClass.simpleName}") } }, SAMPLE_S, SAMPLE_S, TimeUnit.SECONDS)
    }

    /** Silence is a road asked and not answering (VpnRoadWord), for twenty seconds; idleness (nothing either way) is not silence, and
     *  an answer ends it. A silent upstream is raised anew, no sooner than the backoff allows (30 s doubling to five minutes). */
    private fun judgeLife() {
        if (!run.alive || !run.up) return
        try {
            // the pump that left by itself (iOS onUnexpectedExit): the recovery takes the session
            if (!recovering && tun != null && !HevTunnel.TProxyIsRunning()) { tunnelLog("pump exited unexpectedly"); recovering = true; retry.reset(); reassert(true) }
            val s = pumpStats()
            val txb = s[1]; val rxb = s[3]
            val now = System.currentTimeMillis()
            val sample = if (recovering) null else lifeLast?.let { (txb - it.txb) to (rxb - it.rxb) }
            // the road's word on this beat (iOS 0504c2cf): a recovery is a road asked and not answering, so its clock runs from its first
            // beat, a session born into it included
            val word = sample?.let { d -> road.word(d.first, d.second, now / 1000.0) { vpnProbeRunning() != null } }
                ?: if (recovering) VpnRoadWord.Word.UNANSWERED else VpnRoadWord.Word.IDLE
            if (judgeDeath(now, word)) return
            if (recovering) { recoverUpstream(); return }
            if (!phonePathUp) { lifeLast = null; lifeSilentSince = null; recovering = true; reassert(true); return }
            val prev = lifeLast
            lifeLast = Sample(txb, rxb, now)
            if (prev == null) return
            val dtx = txb - prev.txb; val drx = rxb - prev.rxb
            if (word == VpnRoadWord.Word.ANSWERED) {
                lifeInAfterRaise = true
                if (lifeSilentSince != null || lifeRaiseStep != 30_000L) tunnelLog("upstream alive: in=${drx / 1024}KB out=${dtx / 1024}KB in the last ${SAMPLE_S}s raised=$lifeRaises")
                lifeSilentSince = null; lifeRaiseStep = 30_000L
                return
            }
            if (word != VpnRoadWord.Word.UNANSWERED) return   // nothing either way: idleness, not silence
            if (lifeSilentSince == null) lifeSilentSince = prev.at
            val since = lifeSilentSince ?: return
            val silent = now - since
            val cfg = engineConfig
            if (silent < SILENT_AFTER || now < lifeNextRaise || cfg == null) return
            if (1 <= lifeRaises && !lifeInAfterRaise && phonePathUp) { yieldDeadUpstream(dtx, drx, (silent / 1000).toInt(), "silent after a raise"); return }
            lifeInAfterRaise = false
            lifeRaises++
            lifeNextRaise = now + lifeRaiseStep
            tunnelLog("upstream silent: out=${dtx / 1024}KB in=${drx / 1024}KB, ${silent / 1000}s without a byte in -- the engine is raised anew ($lifeRaises), the next no sooner than ${lifeRaiseStep / 1000}s")
            lifeRaiseStep = minOf(300_000L, lifeRaiseStep * 2)
            if (raiseEngine(cfg, "silent upstream") != null) { recovering = true; reassert(true) }
            lifeLast = null          // the pump's counters start over with it; the next sample only records
            lifeSilentSince = null   // judged afresh after the raise
        } finally { countLife(); heatLine() }
    }

    /** The life watch's word on this beat, counted (iOS countLife): alive is the session standing on the phone's own network, out of
     *  recovery and not released, whose upstream brought bytes back since the beat before -- the proof of life. */
    private fun countLife() {
        val rx = pumpStats()[3]
        val proof = IN_FLOOR < rx - livingRx
        livingRx = rx
        val alive = run.alive && phonePathUp && !recovering && !releasing && proof
        living.judge(alive, VpnAlive.clock())
        Prefs.setStr(VpnAlive.TOTAL_KEY, living.total.toString())
    }

    private fun judgeDeath(now: Long, word: VpnRoadWord.Word): Boolean {
        if (releasing) return true
        val verdict = death.judge(now / 1000.0, phonePathUp, answered = word == VpnRoadWord.Word.ANSWERED,
            unanswered = word == VpnRoadWord.Word.UNANSWERED, blocked = 0.0)
        return when (verdict) {
            is VpnDeath.Verdict.Alive -> false
            is VpnDeath.Verdict.Release -> { releaseDeadSession(verdict.why); true }
        }
    }

    /** THE DEAD SESSION LETS THE PHONE GO (iOS releaseDeadSession): the reconnect is lifted first, then the session ends with its
     *  reason, which the app says to the person under the switch; the VPN stays off until the person turns it on again. */
    private fun releaseDeadSession(why: String) {
        if (!run.alive || releasing) return
        releasing = true
        val silent = death.silentSince?.let { (System.currentTimeMillis() / 1000.0 - it).toInt() } ?: 0
        tunnelLog("session dead ($why): silent=${silent}s blocked=0s -- the reconnect is lifted and the phone released")
        VpnReconnect.lift(System.currentTimeMillis() / 1000.0, profileRow)
        end(7, "The VPN carried nothing: VPN off", "dead=$why")
    }

    private fun yieldDeadUpstream(out: Long, inb: Long, silent: Int, why: String) {
        if (!run.alive) return
        tunnelLog("upstream unavailable: out=${out / 1024}KB in=${inb / 1024}KB silent=${silent}s why=$why; keeping tunnel routes")
        recovering = true
        reassert(true)
        recoverUpstream()
    }

    /** One probe at a time, after closing the failed engine; a successful exchange THROUGH the outbound admits it again (iOS
     *  recoverUpstream). The tun and its routes stay installed throughout. */
    private fun recoverUpstream() {
        val up = SystemClock.elapsedRealtime() / 1000.0
        if (!run.alive || !phonePathUp || !retry.ready(up)) return
        stopPump()
        Montanaxray.stop()
        val cand = candidate ?: run { retry.failed(up); return }
        val config = XrayConfig.tunnel(cand.outbound, SOCKS_PORT)
        recoveryAttempts++
        val delay = runCatching { Montanaxray.measureDelay(config, "", "") }.getOrDefault(-1L).takeIf { 0 <= it }
        if (!run.alive) return   // a manual OFF outranks a probe that finished late
        if (delay == null || raiseEngine(config, "verified fallback") != null) {
            recoveryAttempts = 0
            retry.failed(SystemClock.elapsedRealtime() / 1000.0)
            tunnelLog("recovery waiting: candidate=0 failures=${retry.failures}")
            return
        }
        retry.reset()
        recoveryAttempts = 0
        recovering = false
        reassert(false)
        lifeLast = null; lifeSilentSince = null; lifeInAfterRaise = false; death.reset()
        lifeNextRaise = System.currentTimeMillis() + 30_000L
        tunnelLog("recovery connected: candidate=0 delay_ms=$delay")
    }

    private fun reassert(on: Boolean) = VpnTunnel.moved(if (on) VpnTunnel.Status.REASSERTING else VpnTunnel.Status.CONNECTED)

    // ── the end ──

    private fun teardown(why: String) {
        run.stop()
        lifeTimer?.cancel(false); lifeTimer = null
        stopPump()
        Montanaxray.stop()
        runCatching { tun?.close() }
        tun = null
        Montanaxray.setProtector(null)
        Montanaxray.setResolvers("")
        living.judge(false, VpnAlive.clock())   // after a stop the open stretch closes
        Prefs.setStr(VpnAlive.TOTAL_KEY, living.total.toString())
        tunnelLog("session down: $why")
    }

    /** The session ends with its reason (iOS cancelTunnelWithError and the app's fetchLastDisconnectError). */
    private fun end(code: Int, message: String?, why: String) {
        if (run.alive) teardown(why)
        VpnTunnel.ended(code, message, why)
        stopSelf()
    }

    /** A TECHNICAL END (iOS montana.tunnel 8): written, never shown; nothing raises the session again -- the tunnel rises by the hand
     *  alone (iOS a61b0b2f, the author's word 09.11.2026). */
    private fun restart(code: Int, message: String) {
        teardown(message)
        VpnTunnel.ended(code, message, "restart")
        VpnTunnel.setRequested(false)
        stopSelf()
    }
}

/** THE VPN WALL'S WORD UNDER THE PERMITTED LIST (iOS MTVPNWhitelistRoad, MontanaVPNTunnel.swift:856-910): a dead mark is a verdict about
 *  the selected row, never permission to select another -- the page says so under the power. */
object VpnRoad {
    enum class Word { MANUAL, NONE_LIVE }
    @Volatile var word: Word? = null; private set
    private var last: Pair<Double, Long>? = null
    fun line(c: Context): String? = when (word) {
        Word.MANUAL -> c.getString(R.string.vpn_road_manual)
        Word.NONE_LIVE -> c.getString(R.string.vpn_road_none_live)
        null -> null
    }
    fun clear() { if (word != null) { word = null; VpnBus.say() } }
    /** A session stood up or fell: the passive warning never outlives a new session. */
    fun tunnelMoved(@Suppress("UNUSED_PARAMETER") up: Boolean) = clear()
    fun deadMarked(at: Double, row: String) {
        val l = last
        if (l != null && l.first == at && System.currentTimeMillis() - l.second < VpnDead.COOLDOWN * 1000) return
        last = at to System.currentTimeMillis()
        if (row.isNotEmpty()) VpnDead.mark(row)
        word = Word.MANUAL
        appLog("whitelist road: dead selected row; automatic wall selection suppressed")
        VpnBus.say()
    }
}

/**
 * THE APP'S SIDE OF THE TUNNEL (iOS MontanaVPNTunnel, MontanaVPNTunnel.swift:11-833): the status the page draws, the person's request
 * kept as the durable intent (iOS On Demand, shared with the system's panel), the moment of the press the timer counts from, the
 * tunnel's own error, the chosen row. A profile is born only by the person's hand: the system's question is asked by the tap alone.
 */
object VpnTunnel {
    enum class Status { INVALID, DISCONNECTED, CONNECTING, CONNECTED, REASSERTING, DISCONNECTING }
    private const val REQ_KEY = "mt.vpn.requested"
    private const val START_TICKS = "mtVPNStartTicks"
    private const val START_WALL = "mtVPNStartWall"
    @Volatile var status = Status.INVALID; private set
    @Volatile var lastError = ""; private set
    @Volatile private var sel: String? = null
    /** The chosen row, as the page draws it: spoken by VpnSelection, read at birth from the store. */
    val selectedId: String get() = sel ?: Prefs.str(VpnSelection.KEY, "").also { sel = it }
    fun noteSelection(id: String) { if (selectedId != id) { sel = id; VpnBus.say() } }
    val requested: Boolean get() = Prefs.bool(REQ_KEY, false)
    fun setRequested(on: Boolean) { Prefs.setBool(REQ_KEY, on) }
    /** The start of the running session as a wall-clock moment the timer takes, derived from the press on the clock that runs
     *  through sleep (iOS connectedAt from mach_continuous_time): a correction of the wall clock moves the moment, not the count. */
    @Volatile var connectedAt: Long? = null; private set
    private var ourStop = false
    private var breakAt: Long? = null
    private var commandGeneration = 0
    /** The moment a start was handed to the service: the return that follows the system's question (the activity resumes after its
     *  answer) finds that start on its way, not a requested connection without a session (iOS: one transition, one act). */
    @Volatile private var startAskedAt = 0L
    val isOn: Boolean get() = requested || status == Status.CONNECTED || status == Status.CONNECTING || status == Status.REASSERTING

    private fun stampStart() { Prefs.setStr(START_TICKS, SystemClock.elapsedRealtime().toString()); Prefs.setStr(START_WALL, System.currentTimeMillis().toString()) }
    private fun clearStart() { Prefs.remove(START_TICKS, START_WALL); connectedAt = null }
    /** Elapsed since the press; a stamp from before a reboot reads negative and falls back to the wall clock's copy. */
    private val elapsedSincePress: Long? get() {
        val ticks = Prefs.str(START_TICKS, "").toLongOrNull() ?: return null
        val e = SystemClock.elapsedRealtime() - ticks
        if (0 <= e) return e
        val wall = Prefs.str(START_WALL, "").toLongOrNull() ?: return null
        return maxOf(0L, System.currentTimeMillis() - wall)
    }
    private fun refreshAnchor() { connectedAt = elapsedSincePress?.let { System.currentTimeMillis() - it } }

    /** The words of the status (iOS statusText). */
    fun statusText(): String = when (status) {
        Status.CONNECTED -> "connected"
        Status.CONNECTING -> "connecting…"
        Status.DISCONNECTING -> "disconnecting…"
        Status.REASSERTING -> "reconnecting…"
        Status.DISCONNECTED, Status.INVALID -> if (requested) "reconnecting…" else "off"
    }

    /**
     * THE ONE ROAD UP (iOS start): the person's hand, the switch of a server, the raise after a return. With no consent of ours the
     * system's own question is asked -- by the person's hand only; an automatic raise finds none and stops. [done] hears the refusal's
     * word for the page («VPN permission denied»), or nothing.
     */
    fun start(act: MainActivity?, cfg: VpnConfig?, byHand: Boolean = false, preservingRequest: Boolean = false, done: (String?) -> Unit = {}) {
        val ctx = Book.ctx
        if (VpnStore.isRetired(cfg?.id ?: selectedId)) { done(null); return }
        VpnRoad.clear()   // a new start is a new word; the road says its own after its raise
        val consent = runCatching { VpnService.prepare(ctx) }.getOrNull()
        if (consent != null) {
            if (!byHand || act == null) {
                appLog("install: no consent of ours stands; a profile is born only by the person's hand")
                done(null); return
            }
            appLog("install: the system asks the person")
            act.askVpn(consent) { ok ->
                if (ok) { startNow(cfg, preservingRequest); done(null) }
                else { appLog("install: refused by the person"); done(ctx.getString(R.string.vpn_permission_denied)) }
            }
            return
        }
        startNow(cfg, preservingRequest)
        done(null)
    }
    private fun startNow(cfg: VpnConfig?, preservingRequest: Boolean) {
        if (preservingRequest && !requested) return   // a later off in the system's panel is never overwritten by a switch
        commandGeneration++
        setRequested(true)
        lastError = ""
        ourStop = false
        stampStart()
        cfg?.let { VpnSelection.adopt(it.id) }   // what is installed is what is chosen
        val row = cfg?.id ?: selectedId
        val live = MontanaVpnService.live
        if (live != null && live.standing && (status == Status.CONNECTED || status == Status.REASSERTING)) {
            refreshAnchor()
            live.swap(row)   // the tun stays, the engine takes the new server (iOS «plan:»)
            appLog("start: the chosen server into the standing session")
            VpnBus.say()
            return
        }
        val ctx = Book.ctx
        startAskedAt = SystemClock.elapsedRealtime()
        runCatching { ctx.startService(Intent(ctx, MontanaVpnService::class.java).setAction(MontanaVpnService.START).putExtra(MontanaVpnService.ROW, row).putExtra(MontanaVpnService.BY, "app")) }
            .onFailure { appLog("start: the platform refused the service ${it.javaClass.simpleName}") }
        appLog("start: requested connection saved")
        VpnBus.say()
    }

    /** Keep the interface while changing servers (iOS restart). */
    fun restart(act: MainActivity?, cfg: VpnConfig) = start(act, cfg, preservingRequest = true)

    /** The person's off, or a stop of ours (iOS stop): the durable intent is lifted first, then the session ends. */
    fun stop(why: String = "the person's off") {
        commandGeneration++
        setRequested(false)
        VpnRoad.clear()
        ourStop = true
        appLog("stop: stopTunnel -- $why")
        val ctx = Book.ctx
        if (MontanaVpnService.live == null && (status == Status.DISCONNECTED || status == Status.INVALID)) { VpnBus.say(); return }
        if (status == Status.CONNECTED || status == Status.REASSERTING || status == Status.CONNECTING) moved(Status.DISCONNECTING)
        runCatching { ctx.startService(Intent(ctx, MontanaVpnService::class.java).setAction(MontanaVpnService.STOP).putExtra(MontanaVpnService.BY, why)) }
            .onFailure { appLog("stop: the platform refused the service ${it.javaClass.simpleName}") }
        VpnBus.say()
    }

    /** A move of the session (iOS statusChanged): one transition, one act. */
    fun moved(s: Status) = MainThread.post {
        val prev = status
        status = s
        if (prev == s) return@post
        when (s) {
            Status.CONNECTED -> {
                val byApp = Prefs.str(START_TICKS, "").isNotEmpty()
                val gap = breakAt?.let { " gap=${(SystemClock.elapsedRealtime() - it) / 1000}s" } ?: ""
                breakAt = null
                ourStop = false
                setRequested(true)
                VpnRoad.tunnelMoved(true)
                vpnMark("vpn_ui", "tunnel connected after=${elapsedSincePress ?: -1}ms by=${if (byApp) "app" else "system"}$gap")
                if (!byApp) stampStart()   // a session the system raised is counted from the moment it was met
                refreshAnchor()
                appLog("CONNECTED since=${connectedAt ?: "-"}")
                VpnWallMint.count()   // THE LIVING TUNNEL WAKES THE MINTING, HOWEVER IT IS FOUND (iOS didSet, T3 05.10.2026)
            }
            Status.DISCONNECTED, Status.INVALID -> {
                val held = elapsedSincePress?.let { it / 1000 }
                val byApp = ourStop
                ourStop = false
                if (!byApp && (prev == Status.CONNECTED || prev == Status.REASSERTING || prev == Status.CONNECTING)) breakAt = SystemClock.elapsedRealtime()
                val hs = held?.let { "${it / 3600}h${(it % 3600) / 60}m${it % 60}s" } ?: "n/a"
                appLog("DISCONNECTED heldFor=$hs prevStatus=${prev.name.lowercase(Locale.ROOT)} by=${if (byApp) "app" else "other"}")
                vpnMark("vpn_ui", "tunnel down held=${held ?: -1}s from=${prev.name.lowercase(Locale.ROOT)} by=${if (byApp) "app" else "other"}")
                clearStart()
                VpnRoad.tunnelMoved(false)
                readDeadMark()
            }
            else -> {
                refreshAnchor()
                vpnMark("vpn_ui", "tunnel ${s.name.lowercase(Locale.ROOT)}")
            }
        }
        VpnBus.say()
    }

    /** How the session ended (iOS fetchLastDisconnectError): ONE RULE OF WHAT THE PERSON READS -- a technical restart (8) is written,
     *  never shown; a released dead session (7) is theirs to know, in their language; the refused tun (9) too; anything else as said. */
    fun ended(code: Int, message: String?, why: String) = MainThread.post {
        val ctx = Book.ctx
        when (code) {
            0, 8 -> {}
            7 -> lastError = ctx.getString(R.string.vpn_dead_off)
            9 -> { lastError = ctx.getString(R.string.vpn_permission_denied); setRequested(false) }
            else -> if (!message.isNullOrEmpty()) lastError = message
        }
        appLog("lastDisconnectError: code=$code ${message ?: "-"} why=$why${if (code == 8) " -- a technical restart, not shown" else ""}")
        moved(Status.DISCONNECTED)
    }

    /**
     * THE TUNNEL IS READ AT EVERY RETURN (iOS load, MontanaApp appBecameActive): a requested connection with no session standing --
     * the process the system took, an update, a reboot -- is no longer requested: nothing raises it, the tunnel rises by the hand alone
     * (iOS a61b0b2f, the author's word 09.11.2026); a retired row is no choice; the dead mark of a release is learned here.
     */
    fun load() {
        val live = MontanaVpnService.live
        if (live == null || !live.standing) {
            val onItsWay = SystemClock.elapsedRealtime() - startAskedAt < 15_000L
            if (!onItsWay && (status == Status.CONNECTED || status == Status.REASSERTING)) moved(Status.DISCONNECTED)
            if (requested && status != Status.CONNECTING && !onItsWay) {
                appLog("load: the requested connection had no session; nothing raises it -- the tunnel rises by the hand alone")
                setRequested(false)
            }
        } else refreshAnchor()
        if (VpnStore.isRetired(selectedId)) VpnSelection.adopt("")
        readDeadMark()
        VpnBus.say()
    }

    /** THE TUNNEL'S DEAD MARK, READ WHERE THE VPN STANDS OFF (iOS readDeadMark): at the fall, and at every load. */
    private fun readDeadMark() {
        if (isOn) return
        val at = VpnReconnect.deadAt ?: return
        VpnRoad.deadMarked(at, VpnReconnect.deadRow)
    }

    // THE TUNNEL'S THROUGHPUT IN THE DIARY (iOS pollTunnelStats): every 30 s while it stands -- bytes, nothing else
    private var lastStats: Triple<Long, Long, Long>? = null
    private var lastDead = 0L
    private val statsBeat = object : Runnable {
        override fun run() {
            MainThread.later(30_000, this)
            val s = if (status == Status.CONNECTED) MontanaVpnService.stats() else null
            if (s == null) { lastStats = null; return }
            val (txb, rxb, deadS) = Triple(s[0], s[1], s[2])
            val raised = s[3]
            if (0 < deadS || 0 < raised) {
                val word = if (0 < deadS) "no byte in for ${deadS / 10 * 10}s while bytes go out raised=$raised" else "bytes come in again raised=$raised"
                if (deadS != lastDead) vpnMark("vpn_dead", word)
                lastDead = deadS
            }
            val now = System.currentTimeMillis()
            lastStats?.let { (ptx, prx, at) ->
                val ms = maxOf(1L, now - at)
                vpnMark("vpn_stats", "out_kb=${txb / 1024} in_kb=${rxb / 1024} out_kbps=${maxOf(0, txb - ptx) * 8 / ms} in_kbps=${maxOf(0, rxb - prx) * 8 / ms}")
            }
            lastStats = Triple(txb, rxb, now)
        }
    }
    private var beating = false
    fun startStatsBeat() { if (beating) return; beating = true; MainThread.later(30_000, statsBeat) }
}

/**
 * ANY VPN DOUBLES, FOR NOW (iOS MTForeignVPN, MontanaVPNTunnel.swift:1002-1028; the author's word 04.10.2026 18:48 MSK): a VPN another
 * app raised, witnessed by the phone while Montana's tunnel is down. The platform names the link (a network of the VPN kind) but gives
 * an app no count of one interface's bytes, so the bytes the phone took in, all of them, stand as the witness that bytes moved.
 */
object ForeignVpn {
    fun link(): String? {
        val cm = Book.ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") val vpn = cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            ?: return null
        return cm.getLinkProperties(vpn)?.interfaceName ?: "vpn"
    }
    fun bytesIn(): Long? = TrafficStats.getTotalRxBytes().takeIf { it != TrafficStats.UNSUPPORTED.toLong() }
}

/**
 * VPN WALL, THE LIVING TUNNEL HOLDS THE PERSON HERE (iOS 2460 MTVPNWallMint, MTWalletCore.swift:1374-1380): the phone mints nothing of
 * its own (iOS 86854488, decision 375 «a»); while the app stands in front of the person and a tunnel lives -- ours or another app's --
 * the one beat of the seconds runs (iOS MTMintBeat: CoinSplit.beat, a fifth of a second, CoinFlash.rise) and the chain hears the person
 * (CoinTx.beHere). The coins of those seconds are the chain's, under its source vpnwall (TimeChain.SOURCES).
 */
object VpnWallMint {
    const val REF_PREFIX = "vpnwall:"
    /** The app is not in the background (iOS applicationState != .background): set by the activity's start and stop. */
    @Volatile var front = false
    /** iOS wanted: in front, and a tunnel lives (iOS MTApps.vpn?.anyTunnel: ours connected, or another app's VPN on the link). */
    val wanted: Boolean get() = front && (VpnTunnel.status == VpnTunnel.Status.CONNECTED || ForeignVpn.link() != null)
    /** The app came to the screen or the tunnel rose (iOS count): the beat runs and the chain hears the person. */
    fun count() { MintBeat.run(); CoinTx.beHere() }
}

/** THE ONE BEAT OF THE MINTING BY THE SECOND (iOS MTMintBeat): while the VPN wall wants it, once a second the seconds on their way
 *  rise (CoinSplit.beat), and a fifth of a second later their sum flashes (CoinFlash.rise); the wallet page's own beat, while it
 *  stands, is the same beat, so this one waits it out. The last second's coins rise too. On the screen's thread. */
object MintBeat {
    private var beating = false
    fun run() = MainThread.post {
        if (beating) return@post
        beating = true
        tick()
    }
    private fun tick() {
        if (!VpnWallMint.wanted) { beating = false; CoinFlash.rise(); return }
        if (!CoinFlash.beats) { CoinSplit.beat(); MainThread.later(CoinFlash.GATHER_MS, Runnable { CoinFlash.rise() }) }
        MainThread.later(1000, Runnable { tick() })
    }
}
