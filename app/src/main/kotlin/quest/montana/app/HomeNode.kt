package quest.montana.app

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// YOUR NODE (iOS MontanaHomeNode, the author's word 28.09: «your node is your backup of the whole phone»).
// A machine the person owns, named by its address alone. The node keeps the sealed container the engine writes
// (MontanaBackup), under the key the 24 words open, the words never in it. A node of one's own knows no account,
// so the phone names a shelf by a TOKEN only the words derive (HKDF of the entropy under one label, then one HMAC);
// the node checks sha256(token) against the shelf's name and stores nothing of anybody. The token rides only inside
// TLS to the person's own machine.
object MontanaHomeNode {
    private const val HOST = "mt.home.node"        // NOT-UI: the node's address, sealed under the device key
    private const val PIN = "mt.home.node.pin"     // NOT-UI: the digest of the certificate the node made for itself
    private const val ON = "mt.home.node.on"       // NOT-UI: the daily copies switch
    private val keyLabel = "mt-vault-owner-v1".toByteArray()   // NOT-UI: the derivation's own label
    private val tokenLabel = "mt-vault-token".toByteArray()    // NOT-UI: the token's own label
    const val TOKEN_HEADER = "X-Montana-Vault"
    const val NAME_HEADER = "X-Montana-Name"
    /** The door's own TLS port on a node put up by address (iOS MontanaNodeSetup.tlsPort). */
    const val TLS_PORT = 8463

    val host: String get() = DeviceVault.get(HOST)?.toString(Charsets.UTF_8) ?: ""
    fun setHost(h: String) {
        val clean = h.trim().lowercase().replace("https://", "").replace("/", "")   // NOT-UI: the address, bare
        if (clean.isEmpty()) DeviceVault.delete(HOST) else DeviceVault.set(HOST, clean.toByteArray())
        Log.i("Montana", "home_node host set=${if (clean.isEmpty()) 0 else 1}")
    }
    fun setOn(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("mt.defaults", Context.MODE_PRIVATE).edit().putBoolean(ON, v).apply()
    }
    /** The digest of the node's own certificate (a node put up by address); empty for a named node. */
    val pin: String get() = DeviceVault.get(PIN)?.toString(Charsets.UTF_8) ?: ""

    /** A pinned node is spoken to straight, on the door's own TLS port; a named one through its front, under /pushwake. */
    fun base(h: String): String? = when {
        h.isEmpty() -> null
        pin.isEmpty() -> "https://$h/pushwake"
        else -> "https://$h:$TLS_PORT"
    }

    // ── the shelf's name ──
    fun vaultKey(entropy: ByteArray): ByteArray = Hkdf.sha256(entropy, ByteArray(0), keyLabel)
    fun token(k: ByteArray): ByteArray = Hkdf.hmac(k, tokenLabel)
    fun shelf(token: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(token))

    /** This seed's token, or null without a seed. */
    fun tokenHex(): String? {
        val m = MontanaSeed.mnemonic ?: return null
        val e = MtBindings.nativeMnemonicToEntropy(m)?.takeIf { it.size == 32 } ?: return null
        val k = vaultKey(e)
        e.fill(0)
        return hex(token(k)).also { k.fill(0) }
    }

    /** THE FROZEN VECTORS (iOS MontanaHomeNode.keyKAT, computed outside this code on the counting bytes 0x00..0x1f). */
    fun keyKAT(): Boolean {
        val counting = ByteArray(32) { it.toByte() }
        val k = vaultKey(counting)
        val t = token(k)
        return hex(k) == "aa75b0afc0c9b1b4e31a8fecf371ee72866865e7a77cf61f9f1f0ad457652097"
            && hex(t) == "0695b3db38b3c8525f8d8b7a270ae0bc5504c2d0d58634a937e52c2b9f7d2d28"
            && shelf(t) == "125ff41ffac97cae3ce9ba4ab20ae5586587f5c8960fee4055f6aaa8c219b49a"
    }
}

/** RFC 5869 HKDF-SHA-256 and HMAC-SHA-256 on the platform's own javax.crypto (iOS: CryptoKit). */
object Hkdf {
    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256")); doFinal(data) }

    /** Extract then expand to 32 bytes; an empty salt keys the extract with zeros, as the RFC says. */
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).also { prk.fill(0) }
    }
}

fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

/**
 * WHAT THE NODE ANSWERS, AND THIS PHONE'S OWN WORK ON THE WAY TO IT (iOS HomeNodeWatch). The bar reads two shares:
 * the bytes come (this phone's own count), then the frames filed.
 */
object HomeNodeWatch {
    @Volatile var fetching: Double? = null; private set
    @Volatile var restoring: Double? = null; private set
    @Volatile private var busy = false
    /** Told on every move of a share; the page that shows the bar sets it. */
    @Volatile var onChange: (() -> Unit)? = null
    private fun said() { onChange?.invoke() }

    private fun open(path: String, token: String): HttpURLConnection? {
        val b = MontanaHomeNode.base(MontanaHomeNode.host) ?: return null
        val c = URL(b + path).openConnection() as HttpURLConnection
        c.setRequestProperty(MontanaHomeNode.TOKEN_HEADER, token)
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        if (c is HttpsURLConnection) NodeTrust.apply(c)
        return c
    }

    /**
     * THE NEWEST COPY THE NODE HOLDS comes back to the shelf and is laid into this device by the engine: it merges,
     * it never erases (MontanaBackup.restore). Runs on the caller's worker thread; `done` answers there.
     */
    fun takeBack(ctx: Context, done: (CopyResult) -> Unit) {
        val tok = MontanaHomeNode.tokenHex()
        if (busy || tok == null) return done(CopyResult.Refused(Refusal.Stopped))
        val shelf = MontanaBackup.shelf(ctx) ?: return done(CopyResult.Refused(Refusal.Stopped))
        val conn = open("/vault-get", tok) ?: return done(CopyResult.Refused(Refusal.Stopped))
        busy = true
        fetching = 0.0; said()
        val landing = File(shelf, java.util.UUID.randomUUID().toString() + ".part")   // NOT-UI: the unfinished name
        try {
            val code = conn.responseCode
            val name = conn.getHeaderField(MontanaHomeNode.NAME_HEADER)
            if (code != 200 || name == null || MontanaBackup.born(name) == null || !MontanaBackup.plainName(name)) {
                Log.i("Montana", "home_node get refused code=$code")
                return done(CopyResult.Refused(if (code == 404) Refusal.Empty else Refusal.CloudRefused("get $code")))
            }
            val total = conn.contentLengthLong
            var got = 0L
            conn.inputStream.use { inp ->
                landing.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var shown = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != shown) { shown = pct; fetching = pct / 100.0; said() }
                        }
                    }
                }
            }
            val dst = File(shelf, name)
            dst.delete()
            if (!landing.renameTo(dst)) return done(CopyResult.Refused(Refusal.DiskRefused("move")))
            fetching = null; restoring = 0.0; said()
            Log.i("Montana", "home_node fetched $name")
            val r = MontanaBackup.restore(ctx, dst) { f -> restoring = f; said() }
            dst.delete()
            done(r)
        } catch (e: Exception) {
            val why = "get " + e.javaClass.simpleName
            Log.i("Montana", "home_node get no answer $why")
            done(CopyResult.Refused(if (e is java.io.IOException && e.message?.contains("ENOSPC") == true) Refusal.NoSpace else Refusal.CloudRefused(why)))
        } finally {
            landing.delete()
            conn.disconnect()
            fetching = null; restoring = null; busy = false; said()
        }
    }
}

/**
 * THE NODE'S CERTIFICATE, TRUSTED BY ITS DIGEST ALONE (iOS MTNodeTrust): a node put up by address has no name and no
 * authority; the digest the set-up brought back is the whole trust. Without a pin the platform's own judgement stands.
 */
private object NodeTrust {
    fun apply(c: HttpsURLConnection) {
        val pin = MontanaHomeNode.pin
        if (pin.isEmpty()) return
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException("no chain")
                if (hex(MessageDigest.getInstance("SHA-256").digest(leaf.encoded)) != pin) throw java.security.cert.CertificateException("pin")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
        // The digest names the machine; its address carries no name a certificate could be issued to.
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }
}
