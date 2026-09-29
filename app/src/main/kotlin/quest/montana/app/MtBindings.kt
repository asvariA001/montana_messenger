package quest.montana.app

/**
 * The Montana core (Rust, mt-bindings) — the one source of every derivation. Nothing here is
 * computed in Kotlin: the same words open the same keys on iOS and on Android because both
 * call the same code.
 *
 * The first group is the core's own JNI surface (mt-bindings/src/ffi_jni.rs); the second is
 * native/montana-jni, which hands Kotlin the C functions the iOS client calls directly.
 */
object MtBindings {
    init { System.loadLibrary("montana") }

    // mt-bindings/src/ffi_jni.rs
    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeGenerateMnemonic(): String?
    @JvmStatic external fun nativeMnemonicToMasterSeed(mnemonic: String): ByteArray?
    @JvmStatic external fun nativeMnemonicToEntropy(mnemonic: String): ByteArray?
    /** 24 words → pk[1952] ‖ sk[4032] ‖ account_id[32]; the keys equal iOS mt_seed_keys. */
    @JvmStatic external fun nativeAccountFromMnemonic(mnemonic: String): ByteArray?

    // native/montana-jni
    /** master_seed[64] + role → 32 bytes: the core's mt_mldsa_seed_for_role. */
    @JvmStatic external fun nativeRoleSeed(master: ByteArray, role: String): ByteArray?
    @JvmStatic external fun nativeCAbiVersion(): Int

    const val PUBKEY = 1952
    const val SECKEY = 4032
}
