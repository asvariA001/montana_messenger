# Ядро Montana: Android-вход ведёт к закрытой функции `entropy_to_mnemonic`

**Кому:** сопровождающим ядра (`Montana-Protocol/Code`, крейт `mt-bindings`)
**Проверено на:** `feat/s3-stage1` @ `ae36d1f` (19.09.2026), сервер lauterbourg `/var/backups/montana-protocol.git`
**Срочность:** низкая для безопасности, но сборка ядра под Android сейчас невозможна

## Что происходит

Коммит `8784483` (05.08, «Семь источников случайности…») закрыл функцию
`mt_mnemonic::entropy_to_mnemonic` тестовым флагом:

```rust
// Code/crates/mt-mnemonic/src/lib.rs:35-36
#[cfg(any(test, feature = "vectors"))]
pub use mnemonic::entropy_to_mnemonic;
```

Правило понятное: корень личности рождает только ядро (`generate_mnemonic`, семь источников и
проверка здоровья), и приложение не может принести свою энтропию и получить из неё фразу.
iOS-вход (`ffi_c.rs`) этому правилу уже следует.

Android-вход не обновили. Он по-прежнему импортирует закрытую функцию и открывает дверь наружу:

```rust
// Code/crates/mt-bindings/src/ffi_jni.rs:21
    entropy_to_mnemonic, hkdf_expand, hmac_sha256, mldsa_seed_for_role, mlkem_seed_for_role,

// Code/crates/mt-bindings/src/ffi_jni.rs:104, 119
pub extern "system" fn Java_quest_montana_app_MtBindings_nativeEntropyToMnemonic<'local>(
    ...
    let mnemonic = Zeroizing::new(entropy_to_mnemonic(&arr));
```

## Последствия

1. **Сборка под Android не проходит.** `cargo ndk -t arm64-v8a build -p mt-bindings --release` падает
   с `error[E0432]: unresolved import mt_mnemonic::entropy_to_mnemonic` (ffi_jni.rs:21).
   Файл собирается только при `target_os = "android"`, поэтому ни сборка на Mac, ни сборка для iOS
   ошибку не показывают.
2. **Обход ломает правило.** Если включить `mt-mnemonic/vectors`, сборка проходит, но в
   Android-библиотеку попадает `nativeEntropyToMnemonic`: дверь, через которую приложение
   рождает личность из своей энтропии. Это как раз то, что `8784483` закрывал.

## Предлагаемое исправление (одна правка в `ffi_jni.rs`)

- убрать `entropy_to_mnemonic` из импорта в строке 21;
- удалить функцию `Java_quest_montana_app_MtBindings_nativeEntropyToMnemonic` (строки ~101–125),
  **или** обернуть её в `#[cfg(feature = "vectors")]`, если она нужна тестам.

Рождение личности на Android и так уже идёт правильным путём:
`Java_quest_montana_app_MtBindings_nativeGenerateMnemonic` → `mt_mnemonic::generate_mnemonic()`.

## Попутно (не срочно)

- `crates/mt-bindings/build-android.sh` ждёт NDK в `/opt/homebrew/...` и кладёт `.so` в
  `../../Android/MontanaApp/app/src/main/jniLibs`. Такого пути больше нет.
- Шапка `ffi_jni.rs` (строки 6–7) называет тот же старый путь.

## Как с этим живёт Android-клиент сейчас

Новый Android-клиент (`quest.montana.app`, сборка без Gradle) не линкует `mt-bindings`. Его
библиотека `libmontana.so` (`native/montana-jni`) напрямую собирает те же крейты ядра
(`mt-mnemonic`, `mt-crypto`, `mt-codec`) теми же композициями, что iOS-вход `ffi_c.rs`:

| Android (JNI) | iOS (C ABI) |
|---|---|
| `nativeGenerateMnemonic` | `mt_generate_mnemonic` |
| `nativeMnemonicToMasterSeed` | `mt_mnemonic_to_master_seed` |
| `nativeMnemonicToEntropy` | `mt_mnemonic_to_entropy` |
| `nativeSeedKeys` | `mt_seed_keys` |
| `nativeRoleSeed` | `mt_mldsa_seed_for_role` |

Каждая пара побайтно сверяется тестами (`cargo test` в `native/montana-jni`, на Mac вызываются обе
стороны). Флаг `vectors` не включён, двери из энтропии в библиотеке нет. Когда `ffi_jni.rs` будет
исправлен, клиент может вернуться к прямой линковке `mt-bindings`.
