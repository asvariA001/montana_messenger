package quest.montana.app

/**
 * The Montana core (Rust) — the one source of every derivation. Nothing here is
 * computed in Kotlin: the same words open the same keys on iOS and on Android because both
 * call the same code.
 *
 * Every function is a door of native/montana-jni: the same composition of the core's crates as
 * its iOS twin in mt-bindings/src/ffi_c.rs, proven equal by that crate's tests. There is no door
 * that turns entropy handed in by the app into a phrase: a person is born by the core alone.
 */
object MtBindings {
    init { System.loadLibrary("montana") }

    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeGenerateMnemonic(): String?
    @JvmStatic external fun nativeMnemonicToMasterSeed(mnemonic: String): ByteArray?
    @JvmStatic external fun nativeMnemonicToEntropy(mnemonic: String): ByteArray?
    /** 24 words → pk[1952] ‖ sk[4032]: the iOS door mt_seed_keys. */
    @JvmStatic external fun nativeSeedKeys(mnemonic: String): ByteArray?

    /** master_seed[64] + role → 32 bytes: the core's mt_mldsa_seed_for_role. */
    @JvmStatic external fun nativeRoleSeed(master: ByteArray, role: String): ByteArray?

    const val PUBKEY = 1952
    const val SECKEY = 4032
}
