//! The Montana core for Android: the doors the client path needs, each one the same composition of
//! the core's crates as its iOS twin in mt-bindings/src/ffi_c.rs. No derivation is written here —
//! every quantity is computed by mt-mnemonic and mt-crypto; this file only carries bytes across JNI.
//! `tests` below prove each door against the iOS door on the Mac.

use mt_codec::domain;
use mt_crypto::keypair_from_seed;
use mt_mnemonic::{mldsa_seed_for_role, mnemonic_to_entropy, mnemonic_to_master_seed};
use zeroize::Zeroizing;

pub const ABI_VERSION: u32 = parse_u32(env!("MT_ABI_VERSION"));
pub const PUBKEY_LEN: usize = 1952;
pub const SECKEY_LEN: usize = 4032;

const fn parse_u32(s: &str) -> u32 {
    let b = s.as_bytes();
    let mut i = 0;
    let mut v = 0u32;
    while i < b.len() {
        v = v * 10 + (b[i] - b'0') as u32;
        i += 1;
    }
    v
}

// ─────────── the doors, platform-free (iOS twin in brackets) ───────────

/// A fresh identity, drawn by the core from its own health-tested sources (mt_generate_mnemonic).
pub fn generate() -> Option<Zeroizing<String>> {
    mt_mnemonic::generate_mnemonic().ok()
}

/// 24 words → master seed[64] (mt_mnemonic_to_master_seed).
pub fn master_seed(words: &str) -> Option<Zeroizing<[u8; 64]>> {
    mnemonic_to_master_seed(words).ok().map(Zeroizing::new)
}

/// 24 words → entropy[32] (mt_mnemonic_to_entropy).
pub fn entropy(words: &str) -> Option<Zeroizing<[u8; 32]>> {
    mnemonic_to_entropy(words).ok().map(Zeroizing::new)
}

/// master seed + role → 32-byte role seed (mt_mldsa_seed_for_role).
pub fn role_seed(master: &[u8; 64], role: &[u8]) -> Zeroizing<[u8; 32]> {
    Zeroizing::new(mldsa_seed_for_role(master, role))
}

/// 24 words → the person's ML-DSA-65 signing keys, pk[1952] ‖ sk[4032] (mt_seed_keys).
pub fn seed_keys(words: &str) -> Option<Zeroizing<Vec<u8>>> {
    let master = master_seed(words)?;
    let sign_seed = role_seed(&master, domain::ACCOUNT_KEY);
    let (pk, sk) = keypair_from_seed(&sign_seed).ok()?;
    let mut out = Zeroizing::new(Vec::with_capacity(PUBKEY_LEN + SECKEY_LEN));
    out.extend_from_slice(pk.as_bytes());
    out.extend_from_slice(sk.as_bytes());
    Some(out)
}

// ─────────── JNI: class quest.montana.app.MtBindings ───────────

#[cfg(target_os = "android")]
mod jni_doors {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jbyteArray, jint, jstring};
    use jni::JNIEnv;

    fn words(env: &mut JNIEnv, s: &JString) -> Option<Zeroizing<String>> {
        env.get_string(s).ok().map(|j| Zeroizing::new(String::from(j)))
    }
    fn bytes(env: &mut JNIEnv, b: &[u8]) -> jbyteArray {
        env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeAbiVersion(_e: JNIEnv, _c: JClass) -> jint {
        ABI_VERSION as jint
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeGenerateMnemonic(mut env: JNIEnv, _c: JClass) -> jstring {
        match generate() {
            Some(m) => env.new_string(m.as_str()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMnemonicToMasterSeed<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, m: JString<'l>,
    ) -> jbyteArray {
        match words(&mut env, &m).and_then(|w| master_seed(&w)) {
            Some(s) => bytes(&mut env, &s[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMnemonicToEntropy<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, m: JString<'l>,
    ) -> jbyteArray {
        match words(&mut env, &m).and_then(|w| entropy(&w)) {
            Some(e) => bytes(&mut env, &e[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeSeedKeys<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, m: JString<'l>,
    ) -> jbyteArray {
        match words(&mut env, &m).and_then(|w| seed_keys(&w)) {
            Some(k) => bytes(&mut env, &k[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeRoleSeed<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, master: JByteArray<'l>, role: JString<'l>,
    ) -> jbyteArray {
        let m = match env.convert_byte_array(&master) {
            Ok(v) if v.len() == 64 => Zeroizing::new(v),
            _ => return std::ptr::null_mut(),
        };
        let role: String = match env.get_string(&role) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let mut arr = Zeroizing::new([0u8; 64]);
        arr.copy_from_slice(&m);
        let seed = role_seed(&arr, role.as_bytes());
        bytes(&mut env, &seed[..])
    }
}

// ─────────── proof: every door answers as the iOS door (mt-bindings C ABI) ───────────

#[cfg(test)]
mod tests {
    use super::*;
    use mt_bindings::ffi_c;
    use std::ffi::CString;

    fn some_words() -> String { generate().expect("the core draws a phrase").to_string() }

    #[test]
    fn abi_version_is_the_cores() {
        assert_eq!(ABI_VERSION, ffi_c::mt_abi_version());
    }

    #[test]
    fn master_seed_and_entropy_match_ios() {
        for _ in 0..3 {
            let w = some_words();
            let c = CString::new(w.clone()).unwrap();
            let mut ms = [0u8; 64];
            let mut en = [0u8; 32];
            assert_eq!(unsafe { ffi_c::mt_mnemonic_to_master_seed(c.as_ptr(), ms.as_mut_ptr()) }, 0);
            assert_eq!(unsafe { ffi_c::mt_mnemonic_to_entropy(c.as_ptr(), en.as_mut_ptr()) }, 0);
            assert_eq!(&master_seed(&w).unwrap()[..], &ms[..]);
            assert_eq!(&entropy(&w).unwrap()[..], &en[..]);
        }
    }

    #[test]
    fn seed_keys_match_ios() {
        let w = some_words();
        let c = CString::new(w.clone()).unwrap();
        let mut pk = vec![0u8; PUBKEY_LEN];
        let mut sk = vec![0u8; SECKEY_LEN];
        assert_eq!(unsafe { ffi_c::mt_seed_keys(c.as_ptr(), pk.as_mut_ptr(), sk.as_mut_ptr()) }, 0);
        let ours = seed_keys(&w).unwrap();
        assert_eq!(&ours[..PUBKEY_LEN], &pk[..]);
        assert_eq!(&ours[PUBKEY_LEN..], &sk[..]);
    }

    #[test]
    fn owner_role_seed_matches_ios() {
        let w = some_words();
        let master = master_seed(&w).unwrap();
        let role = b"mt-owner-key";
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_mldsa_seed_for_role(master.as_ptr(), role.as_ptr(), role.len(), out.as_mut_ptr()) }, 0);
        assert_eq!(&role_seed(&master, role)[..], &out[..]);
    }

    #[test]
    fn a_wrong_phrase_is_refused() {
        let mut w: Vec<String> = some_words().split(' ').map(String::from).collect();
        w.swap(0, 1);
        let swapped = w.join(" ");
        // a swap almost always breaks the checksum; the core and this door must agree either way
        let c = CString::new(swapped.clone()).unwrap();
        let mut ms = [0u8; 64];
        let ios_ok = unsafe { ffi_c::mt_mnemonic_to_master_seed(c.as_ptr(), ms.as_mut_ptr()) } == 0;
        assert_eq!(master_seed(&swapped).is_some(), ios_ok);
        assert!(master_seed("not a phrase").is_none());
    }
}
