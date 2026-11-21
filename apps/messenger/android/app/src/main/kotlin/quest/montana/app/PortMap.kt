package quest.montana.app

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket

/**
 * OPENING THIS NODE'S OWN DOOR, ASKED OF THE ROUTER BY THE NODE ITSELF (iOS MontanaPortMap, [I-10]/[C-1]). A person does not
 * configure a network: a device behind a household router asks that router for a way in, and every router of the last twenty years
 * answers one of two standard requests -- NAT-PMP (RFC 6886, four bytes on UDP 5351) or UPnP IGD (one discovery datagram and one
 * call). Either the node gets a way in and stands as a node for others, or it does not and lives through the nodes it knows; both
 * are normal and neither asks anything of a person.
 *
 * Nothing here talks to a service: the router is one hop away on the local wire, the requests are the ones its own firmware
 * answers, written on plain sockets. What the router reports as the external address is NOT published -- an address is proven by
 * an inbound connection arriving on it, never by a router's opinion of it; the value only tells the diary whether this node stands
 * where it can be dialled at all.
 */
object PortMap {
    private val lock = Any()
    @Volatile private var mapped = false
    @Volatile private var externalIP = ""
    @Volatile private var externalPort = 0
    @Volatile private var timer: Thread? = null
    private const val LIFETIME = 3600L                 // seconds of lease; renewed at half of it
    private val port get() = Channels.PORT              // iOS MontanaDirectPort.value
    // CONFIG-OK: fixed by the standards themselves -- NAT-PMP listens on 5351 (RFC 6886), discovery is the multicast group
    // 239.255.255.250:1900 (UPnP Device Architecture). Neither travels anywhere with a machine, so neither is configuration.
    private const val PMP_PORT = 5351
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /** What the router said about the way in, for the diary and for the network screen (iOS state). */
    val state: Triple<Boolean, String, Int> get() = synchronized(lock) { Triple(mapped, externalIP, externalPort) }

    /** The address this node stands at from outside, as the router assigned it -- host and port together (iOS publicHostPort). */
    val publicHostPort: String? get() = synchronized(lock) { if (mapped && externalIP.isNotEmpty() && externalPort > 0) "$externalIP:$externalPort" else null }

    /** Ask for the door, then keep asking while the app runs: the first at one second, then at half the lease. Idempotent (iOS open). */
    fun open() {
        synchronized(lock) {
            if (timer != null) return
            timer = Thread({
                Thread.sleep(1000)
                while (true) { runCatching { attempt() }; Thread.sleep(LIFETIME / 2 * 1000) }
            }, "portmap").apply { isDaemon = true; start() }
        }
    }

    private fun attempt() {
        val gw = gateway() ?: run { Log.d("Montana", "portmap_no_gateway"); return }
        if (natpmp(gw)) return
        if (upnp()) return
        synchronized(lock) { mapped = false }
        Log.d("Montana", "portmap_none gw=$gw — router answers neither standard")
    }

    // ── where the router stands (iOS gateway): first the router's own answer to the discovery datagram, failing that the first
    // address of this device's own subnet, where a household router stands on every network anybody ships ──
    fun gateway(): String? {
        discover()?.let { return it.second }
        val parts = (lanIP() ?: return null).split('.')
        if (parts.size != 4) return null
        return parts.take(3).joinToString(".") + ".1"
    }

    /** The LAN IPv4 of the Wi-Fi interface (iOS MontanaPhoneNode.lanIP: en0 first, then any en*; here wlan0 first, then any wlan*). */
    private fun lanIP(): String? = runCatching {
        var found: String? = null
        for (i in NetworkInterface.getNetworkInterfaces()) {
            if (!i.isUp || i.isLoopback || !i.name.startsWith("wlan")) continue
            val a = i.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: continue
            found = a
            if (i.name == "wlan0") break
        }
        found
    }.getOrNull()

    // ── NAT-PMP (RFC 6886), four bytes out and sixteen back ──
    private fun natpmp(gw: String): Boolean {
        val ext = pmpExternal(gw) ?: return false
        var ok = false
        var assigned = 0
        for (proto in intArrayOf(1, 2)) {                // 1 = UDP, 2 = TCP
            val req = byteArrayOf(0, proto.toByte(), 0, 0) + be16(port) + be16(port) + be32(LIFETIME)   // internal, requested, lease
            val rep = udpAsk(gw, PMP_PORT, req) ?: continue
            if (rep.size < 16 || rep[3].toInt() != 0) continue
            ok = true
            // The port the router ACTUALLY gave, which is not always the one asked for: several devices of one home ask for the
            // same number, and only the first of them gets it.
            val given = (rep[10].toInt() and 0xff shl 8) or (rep[11].toInt() and 0xff)
            if (proto == 2 && given > 0) assigned = given
        }
        if (!ok) return false
        val out = if (assigned == 0) port else assigned
        synchronized(lock) { mapped = true; externalIP = ext; externalPort = out }
        Log.d("Montana", "portmap_open by=nat-pmp external=$ext:$out asked=$port")
        return true
    }

    private fun pmpExternal(gw: String): String? {
        val rep = udpAsk(gw, PMP_PORT, byteArrayOf(0, 0)) ?: return null
        if (rep.size < 12 || rep[3].toInt() != 0) return null
        return (8..11).joinToString(".") { (rep[it].toInt() and 0xff).toString() }
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun udpAsk(host: String, port: Int, payload: ByteArray, timeout: Int = 2, multicast: Boolean = false): ByteArray? =
        udpAskFrom(host, port, payload, timeout, multicast)?.first

    /** One datagram out and the first one back, with the address it came from (iOS udpAskFrom). */
    private fun udpAskFrom(host: String, port: Int, payload: ByteArray, timeout: Int = 2, multicast: Boolean = false): Pair<ByteArray, String>? = runCatching {
        val s: DatagramSocket = if (multicast) MulticastSocket().apply { timeToLive = 2 } else DatagramSocket()
        s.use {
            it.soTimeout = timeout * 1000
            it.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), port))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            it.receive(p)
            if (p.length <= 0) return@runCatching null
            Pair(buf.copyOf(p.length), p.address.hostAddress ?: "")
        }
    }.getOrNull()

    // ── UPnP IGD: discovery, description, one call -- all on plain sockets ──
    private fun upnp(): Boolean {
        val loc = discover()?.first ?: return false
        val (host, hport, path) = split(loc) ?: return false
        val xml = httpGet(host, hport, path) ?: return false
        val control = controlPath(xml) ?: return false
        val (ch, cp, cpath) = (if (control.startsWith("http")) split(control) else Triple(host, hport, control)) ?: return false
        var any = false
        for (proto in listOf("TCP", "UDP")) if (addMapping(ch, cp, cpath, proto)) any = true
        if (!any) return false
        synchronized(lock) { mapped = true; externalPort = port }
        Log.d("Montana", "portmap_open by=upnp port=$port")
        return true
    }

    /** The description the router publishes, and the address it answered from. */
    private fun discover(): Pair<String, String>? {
        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nMX: 2\r\n" +
            "MAN: \"ssdp:discover\"\r\n\r\n").toByteArray(Charsets.UTF_8)
        val (d, from) = udpAskFrom(SSDP_HOST, SSDP_PORT, msg, timeout = 3, multicast = true) ?: return null
        for (line in String(d, Charsets.UTF_8).split("\r\n")) {
            if (line.lowercase().startsWith("location:")) return Pair(line.substring("location:".length).trim(), from)
        }
        return null
    }

    private fun split(url: String): Triple<String, Int, String>? {
        val r = url.indexOf("://")
        if (r < 0) return null
        val rest = url.substring(r + 3)
        val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val hostPort = rest.substring(0, slash)
        val path = if (slash == rest.length) "/" else rest.substring(slash)
        val colon = hostPort.lastIndexOf(':')
        if (colon >= 0) hostPort.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..65535 }?.let { return Triple(hostPort.substring(0, colon), it, path) }
        return Triple(hostPort, 80, path)
    }

    private fun controlPath(xml: String): String? {
        for (service in listOf("WANIPConnection", "WANPPPConnection")) {
            val r = xml.indexOf(service)
            if (r < 0) continue
            val tail = xml.substring(r + service.length)
            val open = tail.indexOf("<controlURL>")
            val close = tail.indexOf("</controlURL>")
            if (open < 0 || close < 0) continue
            return tail.substring(open + "<controlURL>".length, close).trim()
        }
        return null
    }

    private fun httpGet(host: String, port: Int, path: String): String? =
        call(host, port, "GET $path HTTP/1.0\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))

    private fun addMapping(host: String, cport: Int, path: String, proto: String): Boolean {
        val mine = lanIP() ?: return false
        val soap = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">" +
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort>" +
            "<NewProtocol>$proto</NewProtocol><NewInternalPort>$port</NewInternalPort>" +
            "<NewInternalClient>$mine</NewInternalClient><NewEnabled>1</NewEnabled>" +
            "<NewPortMappingDescription>Montana</NewPortMappingDescription>" +
            "<NewLeaseDuration>$LIFETIME</NewLeaseDuration>" +
            "</u:AddPortMapping></s:Body></s:Envelope>"
        val body = soap.toByteArray(Charsets.UTF_8)
        val req = "POST $path HTTP/1.0\r\nHost: $host:$cport\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
            "SOAPAction: \"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\"\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val reply = call(host, cport, req.toByteArray(Charsets.UTF_8) + body) ?: return false
        return reply.startsWith("HTTP/1.1 200") || reply.startsWith("HTTP/1.0 200")
    }

    /** One request on a TCP connection to the router and its whole answer, a quarter of a megabyte at most (iOS call). */
    private fun call(host: String, port: Int, request: ByteArray): String? = runCatching {
        Socket().use { s ->
            s.soTimeout = 4000
            s.connect(InetSocketAddress(host, port), 4000)
            s.getOutputStream().apply { write(request); flush() }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val ins = s.getInputStream()
            while (true) {
                val n = runCatching { ins.read(buf) }.getOrDefault(-1)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > 262_144) break
            }
            out.toString("UTF-8")
        }
    }.getOrNull()
}
