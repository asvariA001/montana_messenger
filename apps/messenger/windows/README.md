# Montana for Windows — Work in Progress

**Status: Work in Progress · Experimental · Partial Implementation · Not Yet Verified as a whole.**
Not a release. Do not use it with an identity that holds anything of value.

A port of Montana for iPhone (`apps/messenger/ios`, the original and the reference, build 1.0 (2305), commit `3926188`) to
Windows 10/11 as a [Tauri 2](https://tauri.app) program: Rust underneath, the interface in React. The goal is a copy of the iOS
app as exact as Windows allows, speaking the same wire with iPhones, Android phones and other Windows computers.

Porting and adaptation to Windows: **DinetPDA — Linkora**. Montana itself — the protocol, the core, the iOS app, its design and
its assets — is the work of its own authors; this port follows it and claims none of it. See [`CREDITS.md`](CREDITS.md).

## What is here

| Path | What it is | Lines |
|---|---|---|
| `app/src-tauri/src/` | The Windows side in Rust: identity, pipes and letters, archive and copies, groups, channels, calls' signalling, coins and the wallet, music, the VPN wall's client, the home node, Windows Hello, notifications | ~34 600 |
| `app/src/` | The interface (React 19 + Vite), drawn after the iOS screens | ~18 800 |
| `app/protocol-core/` | `montana_core.dll`: the core's boundary crate `core/Montana-Core/crates/mt-boundary`, unchanged, as one Windows library | 42 |
| `app/src-tauri/node/` | The home-node files the app installs on a machine one owns — byte for byte iOS's `Code/Node` | ~1 200 |
| `app/tools/` | UI-automation scenes (PowerShell + UI Automation) that drive two or more real windows with test identities on the live network | ~6 800 |

The core is not reimplemented. `mt_bindings.dll` is built from `core/Code` (crate `mt-bindings`, the C ABI iOS links as
`MontanaBindings.xcframework`) and called through that ABI; the keys and the 24 words stay inside the core and the Rust side and
never reach the web view.

## Honest state

Measured against a map of the iOS interface (1 501 rows: screens, pages, sheets, alerts, menus, actions, transitions, states):

| | Rows |
|---|---|
| Code present on Windows / partial / missing / not applicable / unclear | 610 / 495 / 321 / 52 / 23 |
| Functionally verified by a scene | 9 |
| Visually confirmed against iOS | 0 |
| Animations confirmed | 0 |

The UI-automation suite (`app/tools/all.ps1`) has 71 scenes, each driving two or more real windows with throwaway test
identities on the live Montana nodes. Its last full run (24.10.2026, against iOS 2304) passed 52 of 71; the 19 failures were
gone through one by one (12 scenes were out of date and rewritten, one program defect fixed, 6 passed on a fresh pair or on a
repeat). A full re-run since then has not been made. Scenes run since, on the current code: Saved Messages between two Windows
computers of one identity (archive blocks, both ways); drafts per chat; the photo album; deleting an account and coming back by
the same 24 words with another identity in between; the pipe secret's daily walk (iOS 2305, anchor day 20754 = 28.10.2026 UTC)
between Windows and the Android port on an emulator, with the clocks moved past the anchor.

**Not yet verified:** anything against a current iPhone (no iPhone at hand for these checks); most of the interface visually
against iOS; the VPN wall end to end; coin transfers other than between test identities; installation on a clean machine other than
the developer's.

Known gaps, among others: Live Text, Apple-only system features (Wallet passes, iCloud).

## One identity on Windows and Android (iOS's twin mirror) — Experimental

The Android port now carries iOS's twin mirror (`MontanaPQ` 872-1155: the mirror's words, the archive's blocks, the ask and
answer of rows, cards born on another device) in three stages, Work in Progress. Those Android changes are the Windows checks'
counterpart, not a release of the Android client: they live on branch `dinetpda/android-windows-counterpart` of this repository
(moved out of this pull request on 25.10.2026, so that `apps/messenger/android` stays the Android client's own line). The owner's scenarios were run as scenes in `app/tools/`, each with Windows and the Android port on **emulators**, test
identities and the live nodes — **not on a real phone and not against an iPhone**:

| Scenario | Scene | Result (25.10.2026) |
|---|---|---|
| 1–4: own letters both ways; a correspondent's letter taken by one device | `twin3.ps1` | passed, 4 of 4, each once |
| 5: a device away for an hour | `twinhour.ps1` | passed, 40 of 40 once |
| 6, 8a: both on line take the same letters | `twinrace.ps1` | passed, 10 of 10 once on each; a mirror word heard twice (crossing) laid once; reading on Windows left nothing unread on Android |
| 7: both write at once | `twinboth.ps1` | passed, 11 of 11 once on each, 10 of 10 at the correspondent |
| 8b: written offline, app killed, network back | `twinoffline.ps1` | passed, 3 of 3 once everywhere |
| 8c: the mirror's road cut the instant it is fetched | `twincut.ps1` | passed: the row stayed in the box (`fails=1`) and was taken 15 s after the network came back, once |
| 9a: both apps restarted | `twinrestart.ps1` | passed, list and conversation the same word for word |
| 9b: a wiped phone opened by the same words | `twinrestore.ps1` | passed: 6 conversations and 47 rows from the copy, the day's mirror word laid once, no letter twice |
| 9c: the pipe secret's step at 29.10.2026 00:00 UTC | `walk.ps1`, `twin3.ps1` | **not yet run** on the real date |
| 10: three devices of one identity | `twinthree.ps1 -Strict` | **passed since the port of iOS 2306** (25.10.2026 11:2x): every letter on both Androids. Before it, the first device to take a row erased it for the others (2 of 3 letters reached one Android only) |
| groups; rows asked back (`TA:`/`TW:`); cards (`TC:`); a row the mirror never carried | `twingroup.ps1`, `twinask.ps1`, `twincard.ps1`, `twingap.ps1` | passed |
| two Windows computers of one identity: own letters, the reading, deletion for all | `twinwin.ps1` | passed: reading on one took the other's unread off in about 13 s; each letter once on both; a letter deleted for all left the other computer and the correspondent's phone |

Scenario 10 was a limit of the iOS design until iOS 2306 marked a letter taken per device; with that ported, it passes.

## Following iOS 2306–2310 (the protocol reference) — Partial Implementation

The interface is compared with iOS 2305 screen by screen; the protocol and security follow the current agreed original apart.
Upstream `main` carries the iOS source up to 2308; 2309 and 2310 are in the changelog only so far.

| iOS build | Change | Windows | Android (the Windows checks' counterpart) |
|---|---|---|---|
| 2306 | each device takes a letter on its own: `/fetch` names the device per label (`devs`), `/box-del {"taken": [[mid, device]]}`; `{"mids"}` only for a withdrawal | **ported**; the live nodes answer `taken` (a probe with a letter that does not exist: 200); scenario 10 passes | **ported** |
| 2307 | a call packet goes to every door at once | **ported** | left to the Android client's own line (it already knocks every door) |
| 2308 | a ring of a call that ended is answered «delivered» | **ported** | left to the Android client's own line |
| 2309 | one owner of the conversation secret (wire unchanged) | not yet (refactor, carries 2310) | not yet |
| 2310 | the pipe tag on `pipe_secret(day)` from 01.11.2026, both tags 01–02.11, only the new from 03.11, the floor F = D-2 and erasure of the established secret | **not yet**: waiting for the spec text and byte vectors (PR #13, 24.10 23:34 UTC) and the 2310 source | not yet |

The transition is tested on an isolated node, never on the network or a real wallet: `app/tools/clocknode.py` runs the
original's `apps/messenger/ios/Code/Node/montana-home-node.py` on a clock of its own (moved while it runs) on the loopback, with the
live box's 2306 behaviour added as the chairman described it (the live nodes run `montana-node-store.py`, which is not published);
a check's run of Windows is pointed at it by `MONTANA_TEST_DOOR` (only with `MONTANA_DATA_DIR` set), an emulator by a file only its
root can lay (`Doors.testDoor`, cleartext to 10.0.2.2 only).

## Deleting an identity from this computer vs switching

Switching identity keeps every person's folder whole and closed to the others. Deleting an identity from this computer (Settings,
with a confirmation that says what is erased for good) erases its passwords, its VPN book with keys and subscription links, the
seed of its files' keys and the window's browser data (storage, cookies, cache); its coin book, TimeChain and archive go aside
first and come back to the same words; the network identity, the coins on the chain and the person's backups are never touched;
another identity's folder is never touched. The files of letters are served with `no-store`, never laid in the web view's disk
cache. This departs from iOS on purpose (iOS keeps its keychain for whoever comes next): the owner's decision of 25.10.2026.
Checked by unit tests with two identities and by `leave.ps1` (25.10.2026).

## Build

Windows 11, MSVC Build Tools, Rust 1.92.0 (`x86_64-pc-windows-msvc`), Node 22, Perl for OpenSSL (Strawberry or Git's).

```powershell
# one target folder for the core and the app: the app finds mt_bindings.dll in its release folder
$env:CARGO_TARGET_DIR = "<repo>/apps/messenger/windows/target"
$env:RUSTUP_TOOLCHAIN = "1.92.0-x86_64-pc-windows-msvc"

# 1. the core's C ABI, in core/Code -- with the Windows portability commit of branch dinetpda/core-windows-portability
#    (git cherry-pick 291869b): core/ is the private history's tree, kept out of this pull request
cd <repo>/core/Code
$env:OPENSSL_SRC_PERL = "<path to perl.exe>"
cargo build --release -p mt-bindings

# 2. the protocol core's boundary (its own target folder inside, the DLL copied to target/release)
<repo>/apps/messenger/windows/app/tools/protocol-core.ps1

# 3. the program and its installer
cd <repo>/apps/messenger/windows/app
npm ci
npx tauri build
```

The result is `target/release/montana-desktop.exe` beside `mt_bindings.dll` and `montana_core.dll`, and an NSIS installer in
`target/release/bundle/nsis/` that installs for the current user without administrator rights.

## Checks, as last run (25.10.2026, Windows 11, Rust 1.92.0 MSVC)

In `app/src-tauri`:

| Command | Exit code | Result |
|---|---|---|
| `cargo test --lib` (debug) | 0 | 147 passed, 0 failed, 19 ignored |
| `cargo test --release --lib` | 0 | 147 passed, 0 failed, 19 ignored |
| `cargo clippy --all-targets -- -D warnings` | 0 | clean since the second commit of this port (24.10.2026): lints fixed, dead code removed or gated to tests, the core's ABI mirror kept with an `allow`; run again 25.10.2026 with the new negative tests |

The ignored tests need the live network or a second window and are run by the scenes in `app/tools/` instead.
The negative cases of one identity's devices are unit tests: a twin's archive block changed by one bit stands nothing, another
identity's blocks stand nothing, and a `.mtbak` copy with one bit changed in its salt or any frame, or with two frames swapped, is
refused rather than read in part (`twin::a_forged_block_or_another_persons_stands_nothing`,
`history::a_copy_reads_back_whole_and_a_cut_or_strange_one_is_refused`). A copy brings a conversation's files back to a new
machine once (`history::a_copy_brings_the_files_back_to_a_new_machine`); restoring the same copy again neither rewrites nor
counts a file already there, as iOS `MontanaBackup` ("a copy adds, it does not erase").
In `core/Code`, with the portability commit of `dinetpda/core-windows-portability` (19.10.2026): `cargo test --release
--workspace` — 1 421 passed, 0 failed, 1 ignored upstream (DEV-012).

No constant-time measurements (dudect or otherwise) have been made for this port; none are claimed. The Windows side adds no
secret-dependent cryptographic code of its own beyond what is named below.

## Cryptography

Montana's own protection is the core's: ML-KEM (FIPS 203), ML-DSA (FIPS 204), SHA-256 — called through the core's ABI, unchanged.
The Windows side uses, as iOS does at the same places:

- ChaCha20-Poly1305 and HKDF-SHA-256 for the `.mtbak` v2 backup file (the format iOS and Android read);
- AES-256-GCM (Windows CNG) for a group room's pair words, as iOS `MTRoomSeal` (`aesgcm.rs`);
- the Windows data-protection API (DPAPI) for this computer's seal of the local stores, where iOS uses the Keychain (`vault.rs`).

Classical public-key cryptography appears only where an external standard requires it and is not counted as Montana's
protection: TLS (WinHTTP, the nodes' doors), WebRTC (DTLS-SRTP of calls), SSH (setting up one's own node, `russh`), and the VPN
wall's own protocols. Nothing here was changed to satisfy a "PQ only" rule by removing those: they belong to the external
standards, exactly as on iOS.

**Dinet Device Sync** — a planned, separate add-on by DinetPDA — Linkora for one identity's own devices where iOS has no
mechanism. Design stage only; **no code in this branch**. It will be hybrid (classical + ML-KEM) with mandatory PQ, no silent
downgrade, and will not change the iOS wire.

## License

The core is dual-licensed MIT OR Apache-2.0 (`core/Code/LICENSE-MIT`, `core/Code/LICENSE-APACHE`). This port is offered to the
Montana project on the same terms as the repository it is proposed to. Third-party crates and npm packages keep their own
licenses (see `Cargo.lock`, `package-lock.json`). Every picture in `app/src/assets` and the app icon are Montana's own iOS or
Android assets from this repository, used for the same app; no Apple system artwork is shipped.
