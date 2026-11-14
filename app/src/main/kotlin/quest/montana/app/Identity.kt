package quest.montana.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device-only vault (iOS: E2EKeychain.setDeviceOnly — AfterFirstUnlockThisDeviceOnly, not
 * synchronized, not in backups). A value is sealed with AES-256-GCM under a key that lives in the
 * Android Keystore and cannot be exported; the sealed bytes sit in app-private preferences that
 * the manifest keeps out of every system backup and device transfer.
 */
object DeviceVault {
    private const val KEY_ALIAS = "mt.device.vault"
    private const val PREFS = "mt.vault"
    private lateinit var prefs: SharedPreferences

    private lateinit var loads: java.io.File

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loads = java.io.File(ctx.noBackupFilesDir, "MontanaVault")
    }

    /**
     * A LOAD LIES IN A FILE OF ITS OWN (iOS MontanaLocalVault, bb1df962 and 52b57e0e, OZ 1 p.1.3): the vault's settings file is read
     * whole at its first word and written whole at every change, and the twin's asked rows alone stood hundreds of KB in it (iOS T1:
     * 326 KB of 1.67 MB, 699 ms on the main thread). A sealed value from 64 KB on is laid in its own file, written whole before its
     * name is kept; the settings keep only «file:» and that name. A load still inside the settings leaves for its file at its first
     * read. The words of the seed and every small value stay where they were.
     */
    private const val LOAD = 64 * 1024
    private const val FILE_MARK = "file:"
    private fun loadFile(name: String): java.io.File =
        java.io.File(loads, MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".vault")
    private fun keep(name: String, sealed: ByteArray): Boolean {
        if (sealed.size < LOAD) {
            val ok = prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
            if (ok) runCatching { loadFile(name).delete() }
            return ok
        }
        val f = loadFile(name)
        loads.mkdirs()
        val tmp = java.io.File(loads, f.name + ".tmp")
        java.io.FileOutputStream(tmp).use { it.write(sealed); it.fd.sync() }
        if (!tmp.renameTo(f)) { tmp.delete(); return false }
        return prefs.edit().putString(name, FILE_MARK + f.name).commit()
    }
    private fun sealedOf(name: String): ByteArray? {
        val s = prefs.getString(name, null) ?: return null
        if (s.startsWith(FILE_MARK)) return java.io.File(loads, s.removePrefix(FILE_MARK)).takeIf { it.isFile }?.readBytes()
        return Base64.decode(s, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Writes and reads back: true only when the value is really in storage. */
    fun set(name: String, value: ByteArray): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.iv + c.doFinal(value)
        keep(name, sealed) && get(name)?.contentEquals(value) == true
    } catch (e: Exception) {
        Log.e("Montana", "vault write refused: ${e.javaClass.simpleName}")
        false
    }

    fun get(name: String): ByteArray? = try {
        sealedOf(name)?.let { sealed ->
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            val open = c.doFinal(sealed, 12, sealed.size - 12)
            // a load found inside the settings leaves for its file now, the same sealed bytes (iOS bb1df962)
            if (LOAD <= sealed.size && prefs.getString(name, null)?.startsWith(FILE_MARK) == false)
                if (runCatching { keep(name, sealed) }.getOrDefault(false)) Log.i("Montana", "vault_load_out bytes=${sealed.size}")
            open
        }
    } catch (e: Exception) {
        Log.e("Montana", "vault read refused: ${e.javaClass.simpleName}")
        null
    }

    fun delete(name: String) { prefs.edit().remove(name).commit(); runCatching { loadFile(name).delete() } }
    /** Whether a value is stored at all, opened or not: a store that would not open is not an empty one (Groups). */
    fun has(name: String): Boolean = prefs.contains(name)

    /** Bytes sealed under the device key for a file of their own (iv ‖ ciphertext ‖ tag); null when the key refuses. */
    fun seal(value: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, key()); iv + doFinal(value) }
    } catch (e: Exception) { null }

    fun unseal(sealed: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            doFinal(sealed, 12, sealed.size - 12)
        }
    } catch (e: Exception) { null }
}

/** What a seed opens (iOS MontanaSeedKeys.Keys): the words and the ML-DSA-65 signing keys. */
class SeedKeys(val mnemonic: String, val pubkey: ByteArray, val seckey: ByteArray) {
    companion object {
        /** 24 words → keys, through the core; null for words the core refuses. */
        fun from(mnemonic: String): SeedKeys? {
            val b = MtBindings.nativeSeedKeys(mnemonic) ?: return null
            if (b.size < MtBindings.PUBKEY + MtBindings.SECKEY) return null
            val pk = b.copyOfRange(0, MtBindings.PUBKEY)
            val sk = b.copyOfRange(MtBindings.PUBKEY, MtBindings.PUBKEY + MtBindings.SECKEY)
            b.fill(0)
            return SeedKeys(mnemonic, pk, sk)
        }

        /** The root is drawn by the core, never by the app (iOS MontanaSeedKeys.generate). */
        fun generate(): SeedKeys? = MtBindings.nativeGenerateMnemonic()?.let { from(it) }
    }
}

/**
 * SINGLE SOURCE OF TRUTH of the active identity (iOS MontanaSeed): the words, in one
 * representation, in the device vault. Everything else is derived from them on use.
 */
/**
 * A BIRTH IS WHOLE OR IT IS NOT (iOS MontanaBirth, cfc6ec68 at 2351; the author's word 02.11.2026: «in the middle of the registration
 * I left, and it created the identity all the same -- check it and fix it, do not allow it»). The seed lands at «Create», before the
 * words are saved and the name is given: until the profile's checkmark the person is a making, not a person. The mark holds the print
 * of the words being made, never the words; a launch that finds it beside the same words forgets the making before any screen.
 */
object MontanaBirth {
    private const val K = "mt.birth.unfinished"   // NOT-UI: this phone's own, never a copy's
    private fun seal(words: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(words.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun begun(words: String) = Prefs.setStr(K, seal(words))
    fun finished() = Prefs.remove(K)
    fun forgetUnfinished(wipe: () -> Unit) {
        val mark = Prefs.str(K, "").takeIf { it.isNotEmpty() } ?: return
        val held = MontanaSeed.mnemonic ?: return
        Prefs.remove(K)
        if (seal(held) != mark) return
        Log.i("Montana", "birth_unfinished forgotten -- a making left before its profile")
        wipe()
    }
}

object MontanaSeed {
    private const val MN = "mt_active_mnemonic"
    private const val TWIN = "mt.twin.ref"

    val mnemonic: String?
        get() = DeviceVault.get(MN)?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }

    val hasSeed: Boolean get() = mnemonic != null

    /** The seed must land in storage; false means no identity was created. */
    fun enter(keys: SeedKeys): Boolean {
        clear()
        val stored = DeviceVault.set(MN, keys.mnemonic.toByteArray(Charsets.UTF_8))
        Log.i("Montana", "seed write: words=${keys.mnemonic.split(' ').size} stored=$stored (value not logged)")
        if (stored) Prefs.setStr(ENTERED, System.currentTimeMillis().toString())   // the past ends here (iOS 3ed661fa, decision 865 a)
        return stored
    }

    /**
     * THE MOMENT THIS PERSON CAME IN ON THIS PHONE (iOS MontanaSeed.entered, 3ed661fa, decision 865 = a): a word born before it -- a
     * group's letter, a room's word, an invitation -- is what this phone catches up on after the 24 words, never news: carried on, it
     * rings nobody. Written by enter alone, never by a copy (the past would be judged by another phone's moment); a person who came
     * in before builds knew the moment has none, and nothing is judged by it.
     */
    private const val ENTERED = "mt.seed.entered"
    val entered: Long? get() = Prefs.str(ENTERED, "").toLongOrNull()?.takeIf { it > 0 }
    /** Whether a word born at [born] (Marks.birthMs) is older than this person's coming in on this phone. */
    fun before(born: Long?): Boolean { val b = born ?: return false; val e = entered ?: return false; return b < e }

    fun clear() {
        DeviceVault.delete(MN)
        DeviceVault.delete(TWIN)
        NodeIdentity.forget()   // a machine's value outliving the person would join the two for anyone listening (iOS MontanaOverlayKey.forget)
        NodeKem.forget()
        Channels.forget()
        DeviceVault.delete("mt.name")   // the name's record and its blinding factor are the person's: they leave with the seed (iOS SeedScope)
        Meeting.forgetClaimedNames()
        LetterAuthor.forget()   // the author's key is the person's: it leaves with the words
        PersonChain.forget()   // the person's seconds and their level leave with the words
        Prefs.setStr(Card.MOMENTS, "")   // the moment each value was set here is the person's (iOS SeedScope.seatKeys: mt.card.moments)
        KeepCopy.forget()   // my copy's keepers and its rhythm are the person's (iOS SeedScope.dataKeys: mt.keep.owner)
        Twin.forget()   // the seed's own pipe's queue and its memory are the person's (iOS SeedScope: mt.twin.asked, mt.twin.mirrored)
    }

    /**
     * The person's own reference (iOS MontanaSeed.twin): the owner branch of the seed,
     * `mt_mldsa_seed_for_role(master, "mt-owner-key")`, filed under the first sixteen bytes of
     * its SHA-256 in hex (MTPipeBook.reference). Never leaves the device.
     */
    val twin: String?
        get() {
            DeviceVault.get(TWIN)?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }?.let { return it }
            val m = mnemonic ?: return null
            val master = MtBindings.nativeMnemonicToMasterSeed(m) ?: return null
            val owner = MtBindings.nativeRoleSeed(master, "mt-owner-key")
            master.fill(0)
            owner ?: return null
            val ref = MessageDigest.getInstance("SHA-256").digest(owner).copyOfRange(0, 16)
                .joinToString("") { "%02x".format(it) }
            owner.fill(0)
            DeviceVault.set(TWIN, ref.toByteArray(Charsets.UTF_8))
            return ref
        }
}

/**
 * THE PERSON'S AUTHOR KEY ON THIS PHONE (iOS MTLetterAuthor, MontanaQueueAddressing.swift:131-169): author_key = the role seed
 * «mt-letter-author» of the master seed, derived from the words once off the main thread (the master costs 2^20 stretching),
 * held in memory for the words seated and kept in the vault beside them (iOS wakeMyAuthor). Asked before it stands, it answers
 * with the kept one and starts the derivation; a letter born in that instant rides unsigned, as every letter of an older build
 * does. The pickup waits for it (await): a letter of mine told before the key stood would land as the correspondent's. A tag
 * drifting from its vectors (Wire.authorAgreesWithCanon) leaves the key absent: nothing is signed and nothing is told mine.
 */
object LetterAuthor {
    private const val KEPT = "mt.letter.author"
    private val lock = Any()
    private val derive = Any()
    @Volatile private var held: Pair<String, ByteArray>? = null
    @Volatile private var warming = false
    private val canon by lazy { Wire.authorAgreesWithCanon().also { if (!it) Log.e("Montana", "letter_author: the tag disagrees with its vectors -- nothing is signed") } }

    val key: ByteArray?
        get() {
            val m = MontanaSeed.mnemonic ?: return null
            if (!canon) return null
            val words = Wire.hex(Wire.sha(m.toByteArray()))
            held?.let { (w, k) -> if (w == words) return k }
            synchronized(lock) { if (!warming) { warming = true; Thread { try { make(m, words) } finally { warming = false } }.start() } }
            return DeviceVault.get(KEPT)?.takeIf { it.size == 32 }
        }

    fun await(): ByteArray? {
        val m = MontanaSeed.mnemonic ?: return null
        if (!canon) return null
        return make(m, Wire.hex(Wire.sha(m.toByteArray())))
    }

    private fun make(m: String, words: String): ByteArray? = synchronized(derive) {
        held?.let { (w, k) -> if (w == words) return k }
        val master = MtBindings.nativeMnemonicToMasterSeed(m)
        val k = master?.let { MtBindings.nativeRoleSeed(it, "mt-letter-author") }
        master?.fill(0)
        if (k != null && MontanaSeed.mnemonic == m) { held = words to k; DeviceVault.set(KEPT, k) }
        Log.d("Montana", "letter_author key=" + (if (k == null) "0" else "1"))
        k
    }

    fun forget() { held = null; DeviceVault.delete(KEPT) }
}

/**
 * The call sign of a person who has claimed no name (iOS MontanaCallsign): a row of
 * callsigns.tsv picked by a fold of the reference, read in the device's language.
 */
object Callsign {
    private class Row(val en: String, val ru: String, val zh: String, val emoji: String)
    @Volatile private var rows: List<Row> = emptyList()

    private fun table(ctx: Context): List<Row> {
        if (rows.isNotEmpty()) return rows
        val text = ctx.resources.openRawResource(R.raw.callsigns).bufferedReader(Charsets.UTF_8).readText()
        rows = text.split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) null else Row(f[1], f[2], f[4], if (f.size >= 6) f[5] else "")
        }
        return rows
    }

    private fun named(r: Row, lang: String): String {
        val word = when { lang.startsWith("ru") -> r.ru; lang.startsWith("zh") -> r.zh; else -> r.en }
        return if (r.emoji.isEmpty()) word else r.emoji + " " + word
    }

    private fun lang(ctx: Context) = ctx.resources.configuration.locales[0].language.lowercase()

    fun all(ctx: Context): List<String> = table(ctx).map { named(it, lang(ctx)) }

    /** The same fold as iOS: sum = sum*31 + byte over the first 8 UTF-8 bytes, UInt32 wrapping. */
    fun of(ctx: Context, reference: String): String {
        val t = table(ctx)
        if (t.isEmpty() || reference.isEmpty()) return ""
        var sum = 0
        for (b in reference.toByteArray(Charsets.UTF_8).take(8)) sum = sum * 31 + (b.toInt() and 0xff)
        val idx = (sum.toUInt() % t.size.toUInt()).toInt()
        return named(t[idx], lang(ctx))
    }
}

/** Settings of the person that are not secrets (iOS UserDefaults / @AppStorage). */
object Prefs {
    private lateinit var p: SharedPreferences
    fun init(ctx: Context) { p = ctx.getSharedPreferences("mt.defaults", Context.MODE_PRIVATE) }

    const val TERMS_VERSION = 1
    var termsAccepted: Boolean
        get() = p.getInt("termsAcceptedVersion", 0) >= TERMS_VERSION
        set(v) { p.edit().putInt("termsAcceptedVersion", if (v) TERMS_VERSION else 0).apply() }

    var userName: String
        get() = p.getString("userName", "") ?: ""
        set(v) { p.edit().putString("userName", v).apply() }

    /** The notifications question was asked once (iOS MontanaNotifyGate.asked, "mt.notify.asked"). */
    var notifyAsked: Boolean
        get() = p.getBoolean("mt.notify.asked", false)
        set(v) { p.edit().putBoolean("mt.notify.asked", v).apply() }

    /** The appearance's own settings, under the iOS UserDefaults keys (bubbleStyle, cbMineFill1, noteQuality…). */
    fun has(key: String): Boolean = p.contains(key)
    fun str(key: String, def: String): String = p.getString(key, def) ?: def
    fun setStr(key: String, v: String) { p.edit().putString(key, v).apply() }
    fun dbl(key: String, def: Double): Double = if (p.contains(key)) p.getFloat(key, def.toFloat()).toDouble() else def
    fun setDbl(key: String, v: Double) { p.edit().putFloat(key, v.toFloat()).apply() }
    fun bool(key: String, def: Boolean): Boolean = p.getBoolean(key, def)
    fun setBool(key: String, v: Boolean) { p.edit().putBoolean(key, v).apply() }
    fun int(key: String, def: Int): Int = runCatching { p.getInt(key, def) }.getOrDefault(def)
    fun setInt(key: String, v: Int) { p.edit().putInt(key, v).apply() }
    fun remove(vararg keys: String) { p.edit().apply { keys.forEach { remove(it) } }.apply() }
    /** One listener of every change of the defaults (TwinSelf, as iOS hears UserDefaults.didChangeNotification); held strongly by its owner. */
    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = p.registerOnSharedPreferenceChangeListener(l)
    /** Every key of the store under one beginning: the marks kept per person, which a copy carries (Card.build). */
    fun keys(prefix: String): List<String> = p.all.keys.filter { it.startsWith(prefix) }

    fun forgetPerson() { p.edit().clear().commit() }
}
