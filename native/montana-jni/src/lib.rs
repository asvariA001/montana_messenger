//! The Montana core for Android.
//!
//! Everything the core already says to Android (`Java_quest_montana_app_MtBindings_*` in
//! mt-bindings/src/ffi_jni.rs) is linked in unchanged. The iOS client reaches a few more
//! quantities through the C ABI; the same C functions are handed to Kotlin here, so no
//! derivation is written twice.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use mt_bindings::ffi_c;

const MASTER_SEED_LEN: usize = 64;
const ROLE_SEED_LEN: usize = 32;

/// master_seed[64] + role → 32-byte role seed, the core's `mt_mldsa_seed_for_role`
/// (iOS: MTPipe.ownerSecret with role "mt-owner-key").
#[no_mangle]
pub extern "system" fn Java_quest_montana_app_MtBindings_nativeRoleSeed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    master: JByteArray<'local>,
    role: JString<'local>,
) -> jbyteArray {
    let null = std::ptr::null_mut();
    let mut master = match env.convert_byte_array(&master) {
        Ok(v) if v.len() == MASTER_SEED_LEN => v,
        _ => return null,
    };
    let role: String = match env.get_string(&role) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let mut out = [0u8; ROLE_SEED_LEN];
    let rc = unsafe {
        ffi_c::mt_mldsa_seed_for_role(master.as_ptr(), role.as_ptr(), role.len(), out.as_mut_ptr())
    };
    master.iter_mut().for_each(|b| *b = 0);
    if rc != 0 {
        return null;
    }
    let arr = env.byte_array_from_slice(&out);
    out.iter_mut().for_each(|b| *b = 0);
    arr.map(|a| a.into_raw()).unwrap_or(null)
}

/// The core's ABI version through the C door too — a check that both doors are one library.
#[no_mangle]
pub extern "system" fn Java_quest_montana_app_MtBindings_nativeCAbiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ffi_c::mt_abi_version() as jint
}
