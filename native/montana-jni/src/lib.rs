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

/// Bytes drawn by the core from its own sources — a copy's salt (mt_random_fast); None when the core refuses.
pub fn random(len: usize) -> Option<Vec<u8>> {
    if len == 0 || len > 4096 { return None; }
    let mut out = vec![0u8; len];
    mt_mnemonic::random_fast(&mut out).ok()?;
    Some(out)
}

/// entropy[32] → history_key[32]: HKDF-SHA-256(salt=0×32, ikm=entropy, info="mt-history-key") (mt_history_key).
pub fn history_key(entropy: &[u8; 32]) -> Zeroizing<[u8; 32]> {
    let prk = Zeroizing::new(mt_mnemonic::hmac_sha256(&[0u8; 32], entropy));
    let okm = Zeroizing::new(mt_mnemonic::hkdf_expand(&prk[..], domain::MSG_HISTORY_KEY, 32));
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&okm);
    out
}

/// Which conversation a sealed block belongs to, readable only under the history key (mt_archive_peek_conv).
pub fn archive_peek_conv(hk: &[u8; 32], owner: &[u8; 32], sealed: &[u8]) -> Option<[u8; 32]> {
    mt_messenger_e2e::archive::peek_conv(hk, owner, sealed)
}

/// A sealed block filed as-stored into the chat's log: 1 appended, 0 already held, <0 refused (mt_archive_ingest).
pub fn archive_ingest(base: &str, chat: &str, hk: &[u8; 32], owner: &[u8; 32], sealed: &[u8]) -> i32 {
    let store = match mt_messenger_e2e::archive::ArchiveStore::open(base) {
        Ok(s) => s,
        Err(_) => return -5, // MT_ERR_IO
    };
    match store.ingest_block(chat, hk, owner, sealed) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) if e.kind() == std::io::ErrorKind::InvalidData => -4, // MT_ERR_DECODE
        Err(_) => -5,
    }
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

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeRandom(mut env: JNIEnv, _c: JClass, len: jint) -> jbyteArray {
        match random(len.max(0) as usize) {
            Some(b) => bytes(&mut env, &b),
            None => std::ptr::null_mut(),
        }
    }

    fn key32(env: &mut JNIEnv, b: &JByteArray) -> Option<Zeroizing<[u8; 32]>> {
        let v = Zeroizing::new(env.convert_byte_array(b).ok()?);
        if v.len() != 32 { return None; }
        let mut k = Zeroizing::new([0u8; 32]);
        k.copy_from_slice(&v);
        Some(k)
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeHistoryKey<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, ent: JByteArray<'l>,
    ) -> jbyteArray {
        match key32(&mut env, &ent) {
            Some(e) => { let hk = history_key(&e); bytes(&mut env, &hk[..]) }
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeArchivePeekConv<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, hk: JByteArray<'l>, owner: JByteArray<'l>, sealed: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(h), Some(o), Ok(s)) = (key32(&mut env, &hk), key32(&mut env, &owner), env.convert_byte_array(&sealed)) else {
            return std::ptr::null_mut();
        };
        match archive_peek_conv(&h, &o, &s) {
            Some(conv) => bytes(&mut env, &conv),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeArchiveIngest<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, base: JString<'l>, chat: JString<'l>,
        hk: JByteArray<'l>, owner: JByteArray<'l>, sealed: JByteArray<'l>,
    ) -> jint {
        let base: String = match env.get_string(&base) { Ok(s) => s.into(), Err(_) => return -3 };
        let chat: String = match env.get_string(&chat) { Ok(s) => s.into(), Err(_) => return -3 };
        let (Some(h), Some(o), Ok(s)) = (key32(&mut env, &hk), key32(&mut env, &owner), env.convert_byte_array(&sealed)) else {
            return -1;
        };
        archive_ingest(&base, &chat, &h, &o, &s)
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
    fn random_is_the_cores() {
        let a = random(32).unwrap();
        let b = random(32).unwrap();
        assert_eq!(a.len(), 32);
        assert_ne!(a, b);
        assert!(random(0).is_none());
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_random_fast(out.as_mut_ptr(), 32) }, 0);
    }

    #[test]
    fn history_key_matches_ios() {
        // iOS E2EVault.historyKeyKAT: the frozen vector on 0x55 × 32
        let k = history_key(&[0x55; 32]);
        let hexed: String = k.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hexed, "e6a7dc51003770589d9f731c1231c1523be7348c7769383875dd34bd6c578def");
        let e = entropy(&some_words()).unwrap();
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_history_key(e.as_ptr(), out.as_mut_ptr()) }, 0);
        assert_eq!(&history_key(&e)[..], &out[..]);
    }

    #[test]
    fn archive_doors_match_ios() {
        // a block this device seals, taken back by both doors into two stores: the same answers, the same files
        let hk = [7u8; 32];
        let owner = [9u8; 32];
        let conv = [3u8; 32];
        let tmp = std::env::temp_dir().join(format!("mt-jni-archive-{}", std::process::id()));
        let (a, b) = (tmp.join("a"), tmp.join("b"));
        use mt_messenger_e2e::archive::{seal_block, HistoryBlock, HistoryItem};
        let item = HistoryItem { conv_id: conv, dir: 1, send_time: 1_700_000_000, content: b"hello".to_vec() };
        let sealed = seal_block(&hk, &owner, &[1u8; 16], &HistoryBlock { block_seq: 1, items: vec![item] });
        assert_eq!(archive_peek_conv(&hk, &owner, &sealed), Some(conv));
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_archive_peek_conv(hk.as_ptr(), owner.as_ptr(), sealed.as_ptr(), sealed.len(), out.as_mut_ptr()) }, 0);
        assert_eq!(out, conv);
        let bs = CString::new(b.to_str().unwrap()).unwrap();
        let chat = CString::new("c1").unwrap();
        let ios = unsafe { ffi_c::mt_archive_ingest(bs.as_ptr(), chat.as_ptr(), hk.as_ptr(), owner.as_ptr(), sealed.as_ptr(), sealed.len()) };
        let ours = archive_ingest(a.to_str().unwrap(), "c1", &hk, &owner, &sealed);
        assert_eq!((ours, ios), (1, 1));
        assert_eq!(archive_ingest(a.to_str().unwrap(), "c1", &hk, &owner, &sealed), 0); // held already: merged, not doubled
        assert!(archive_ingest(a.to_str().unwrap(), "c1", &[8u8; 32], &owner, &sealed) < 0); // another key: refused
        let _ = std::fs::remove_dir_all(&tmp);
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
