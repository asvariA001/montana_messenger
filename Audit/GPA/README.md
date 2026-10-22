# Transport against a global passive adversary: Gate -1, the intersection model, the timing of cover

**Status: open.** Of the six mechanisms a reviewer lists against a global passive adversary (GPA),
two exist in the reference core as code with vectors, one exists in the set and in code as a
derivation but is wired into no sending loop, two are forbidden by the set in the form the reviewer
names, and one is replaced by a different construction. The claim of the set against intersection is
stated in [INTERSECTION-MODEL.md](INTERSECTION-MODEL.md) with every open point named. Ten
constant-time measurements stand over the paths where a padding block and a letter block must cost
the same; two of them found a leak in the cutters of the reference core, fixed in this pull request,
and their numbers before and after are below. Nothing else here is closed.

This report answers issue #5 of this repository. It touches neither the window proof (#4) nor the
scope of the external audit proposal (#2); where it meets them it says so.

**Baseline.** Commit `2c6951f8b887b7e42bfe745f5bb42cdb207508f7` of this repository: the normative set
in `core/docs/`, the reference core in `core/Montana-Core/`, the client core in `core/Code/`, the
messenger application in `apps/messenger/`. Line numbers are of that commit. Two sources outside this
repository are read for completeness and are named as such: the iOS client source tree the published
application is built from (its public snapshot is `apps/messenger/ios`), and the crate `mt-agent` of
the repository `efir369999/montana_core` at commit `5be748583d650703cbcf134a8d8d9abf9cee091a`.

## 1. Gate -1: what exists, and what is words

Rule of this table: a mechanism *exists* only where a line of code is quoted and a test or vector
stands over it; *derivation only* where the formula is implemented and frozen but no running path
calls it; *words* where the set describes it and no code does; *forbidden* where the set rejects the
mechanism in the form named. `core/docs/Montana Registry.md` lines 123-147 is the set's own register:
every row of the network that is not the handshake, the tag or the cell is marked *awaits stage 18*.

| Mechanism named by the reviewer | In the set | Reference core (`core/Montana-Core`) | Client core (`core/Code`) and the application | Verdict |
|---|---|---|---|---|
| **Constant-rate sending** | Forbidden for correspondence: *"A constant rate would be the worst solution ... Synthetic filler therefore does not exist"* (`core/docs/Montana Constitution.md` 389-395; `core/docs/Montana Network.md` 514-520). Required for a stream only: *"A stream runs at a constant rate for the life of the pipe"* (Network 493-494). | None found. No stream code exists; the node writes a unit the moment it seals it (`crates/mt-net/src/lib.rs` 242-251). | None found. No code of the client core or of the application sets a sending rate; the voice and video path of the application rides WebRTC (`apps/messenger/ios`, `MontanaCall.swift`), whose rate is the codec's and is not measured here. | **Forbidden** for correspondence; **words** for streams. |
| **Padding to one length** | *"Its length is `cell_bytes` whatever it carries"*, `cell_bytes` = 1232 (Canon 897, 1929-1961); a unit of a link is the same width (Canon 2198-2224); *"No length crosses the wire"* (Network 182-185). | **Exists.** `crates/mt-wire/src/lib.rs` 49-52 (the one width, read from the Decree); `src/cell.rs` 208-223 (a cell of another length is refused), 236-277 (seal and open); `src/link.rs` 28-54 (the last piece padded with zeros inside the seal), 94-120 (a unit sealed to the one width); `src/draw.rs` 71-83 (a flight's filling is drawn, not zero); `crates/mt-net/src/lib.rs` 123-145 (the four flights cross as whole units). The erasure code pads a body to whole blocks and whole groups with zeros (`src/erasure.rs` 224-263). Used by the node: `crates/montana-node/src/lib.rs` 676, `src/pulse.rs` 770-781. Vectors and shape tests: `crates/mt-wire/tests/wire.rs`, `tests/shape.rs`. | **Partly, by size bucket, not one length.** Media blobs: `crates/mt-messenger-e2e/src/media.rs` 28-38 `pad_len` rounds a size up with overhead below 1/16, zero-filled inside the AEAD; reached from the application through `crates/mt-bindings/src/ffi_e2e.rs` 390-394 and `MontanaMediaKit.swift` 161-184 (zero fill at 163-164). Padding buckets `{256 ... 1 MiB}`: `crates/mt-overlay/src/inbox.rs` 4-10, called only from a test (`tests/e2e_stage2_offline.rs` 32-34). Uniform 1024-byte frames: `crates/mt-net/src/framing.rs` 1-26, 117-147, with no caller outside its own tests and a fuzz target. Handshake messages are padded to a *random* length up to 1024 (`crates/mt-noise-pq/src/xx_libp2p_upgrade.rs` 21-58): variable, not one length. Outside this repository, `mt-agent` pads letters to one of **two** sizes, 2048 and 1536 (`montana_core` `Code/crates/mt-agent/src/letter.rs` 7, 24-31; `src/card.rs` 41, 357-360): finding F3 below. | **Exists** in the reference core. **Buckets, not one length** in the client line. |
| **Poisson delays** | Not Poisson. Deferred mail leaves after a delay *drawn uniformly over the residues of its chain not yet reached*, bounded by the window, never applied to live traffic; the set argues against a shaped profile (Canon 1211-1225; Network 434-442; Constitution 480-485). | **Words.** Registry NET-08 *awaits stage 18*. No code draws a delay for mail. (`crates/mt-derive/src/nullifier.rs` 140-142 draws a delay, but for the redemption of a right, not for a letter.) | None found. The sweep of the client core and of the application for `poisson`, `jitter`, `delay`, `slot` finds no sending delay; sending happens when sealing does. | **Words**, and the words say *uniform*, not Poisson. |
| **Cover traffic** | **Forbidden.** *"Synthetic filler therefore does not exist, and neither does a price for it: the same bytes carry other people's letters"* (Constitution 389-395); a sleeping tenant's link is held *"with the transit it carries in any case"*, *"not with empty filler"* (Network 509-512; conformance point 8, line 716). What stands in its place is carriage, and the padding blocks inside a delivery. | **Exists in its place:** padding blocks of a delivery (`src/erasure.rs` 245-263) ride cells identical to letter blocks (`tests/shape.rs` 124-157). Carriage as a duty: `crates/montana-node/src/lib.rs` 771-793 (a link is carried for its life). No synthetic cell is ever made. | **Scaffolding only.** `crates/mt-net/src/framing.rs` 53-59 builds padding frames and 193-202 requires one in five frames to be padding; nothing outside the file's tests calls either. Were it live, the padding frame would announce itself: `FLAG_PADDING` stands in the three-byte plaintext header (lines 4, 21). Finding F2. | **Forbidden** by the set; dead code in the client line. |
| **Single-use queues** | A tag is *"spent once; it belongs to a window and is spent in that window"* and *"recorded at the holder for the window and nowhere else"* (Constitution 461-479; Network 127-153; conformance point 2, line 710). | **Derivation only.** The tag of a window, the label of a step and the sender's ephemeral identity are implemented and frozen (`crates/mt-derive/src/tag.rs` 36-45, 88-95; vectors in the same file and in `crates/mt-conformance`). The holder's record of a spent right is Registry NET-07, *awaits stage 18*; the node's `is_spent` (`crates/montana-node/src/state.rs` 196-197, 221) is of notes and openings of consensus, not of carriage. | **Rotating, not single-use.** Montana Unlinkable Queues: a queue holds random `recv_id`/`send_id` and a `rotation_epoch` (`crates/mt-overlay/src/muq.rs` 18-27); the postman carries them over TLS in two hops (`crates/mt-postman/src/muq.rs` 1-8); the application derives its queue keys once per launch (`MontanaQueueAddressing.swift` 77-125) and reaches a postman at an operator's address (`MontanaNameBook.swift` 572-574; `MontanaPhoneNode.swift` 9, 352). | **Derivation exists; spending is words** (stage 18). Client queues are long-lived. |
| **Onion routing** | **Replaced.** *"No one lays a route ... A sender knows the keys of nobody but its own acquaintances and builds no layers on their behalf"* (Constitution 450-459). Each hop re-seals the cell under the key of its next handshake and writes one slot of a seal array that is full of drawn values from the origin (Network 205-276; Canon 1949-1967). | **The step exists; the path is words.** `crates/mt-wire/src/cell.rs` 284-303 `carry`: open, write the seal at `seal_slot(relay_seal(owner, path_id(inner)))`, re-seal with a fresh nonce; `src/draw.rs` 85-93 the full drawn array; `crates/mt-derive/src/tag.rs` 61-86 the three derivations; a two-hop path in `tests/wire.rs` 104-175. No node forwards a cell hop by hop: Registry NET-03, NET-04, NET-13 *await stage 18*; the node deposits an object straight at an acquaintance (`crates/montana-node/src/lib.rs` 866-898; `src/pulse.rs` 846-863), and nothing calls `cell::carry` outside the tests of `mt-wire`. | **Scaffolding.** Dandelion++ stem/fluff (`crates/mt-net/src/dandelion.rs` 1-15) has no caller outside its own tests. The postman's two hops (entry proxy, host) are what the application uses: two machines of the operator, TLS between them, no re-sealing per step. | **Step exists as code and vectors; the path over several machines is words** (stage 18). |

Three consequences the table forces, stated so they cannot be read wider or narrower:

1. **Today's shipped transport is reactive.** The reference node writes a unit when it seals it
   (`crates/mt-net/src/lib.rs` 242-251) and the application seals media at the moment of sending
   (`MontanaMediaKit.swift` 161); the canonical slot is a frozen derivation with vectors
   (`crates/mt-derive/src/tag.rs` 110-117; `crates/mt-conformance/src/parse.rs` 2768) and nothing
   else. A GPA therefore sees the keystroke. Every in-window property of the set stands on the slot
   and holds for no running code.
2. **The one-length cell and the per-step re-seal are real and tested** — and they are what the
   timing tests below stand over, because they are the surfaces an implementation could leak through
   even once the slot lands.
3. **The chair's reading of 21.10 — nothing of a GPA model in the client — is confirmed** for the
   application by a fresh sweep: `apps/messenger/ios` and the client source tree searched for `GPA`,
   `global passive`, `intersection`, `chaff`, `cover traffic`, `poisson`, `onion`, `constant rate`,
   `cell_bytes` match on none of them. The application's traffic shaping is the media size bucket
   named above and nothing else that the sweep or the FFI surface (`crates/mt-bindings/src/ffi_e2e.rs`)
   shows.

## 2. The intersection model

[INTERSECTION-MODEL.md](INTERSECTION-MODEL.md): the setting, the adversary, the property the set
claims word for word, what it explicitly does not claim, the intersection attack stated against the
setting, and the five things a proof would require. Each is marked open.

## 3. Timing: a padding block and a letter block cost the same

Montana carries no synthetic cover, so "cover versus real" takes, in this code, the one form the
set allows: a **padding block** (the zeros `erasure::cut` and `erasure::groups` fill a delivery
with, `crates/mt-wire/src/erasure.rs` 224-263) against a **letter block**. Both ride a cell; an origin
seals both twice, every hop carries both, and a link seals a unit of padding as it seals a unit of a
message. If any of those paths cost a different time for the two, an observer with a clock reads the
size of a letter — the one quantity the one width exists to hide.

The instrument is `crates/mt-wire/tests/dudect/mod.rs`, the dudect method of Reparaz, Balasch and
Verbauwhede (DATE 2017): two classes, interleaved samples, the slowest tenth dropped, Welch's t,
threshold 10. It was already in the tree for the erasure code (`tests/timing.rs`); this pull request
moves it into one module both files share and adds the measurements of the cell and the link in
`tests/timing_cell.rs`. Run:

```
cargo test -p mt-wire --test timing_cell --test timing -- --test-threads=1 --nocapture
```

| Test (`tests/timing_cell.rs`) | Path under the clock | Class A | Class B | Wrong implementation it rejects |
|---|---|---|---|---|
| `the_origin_seals_a_padding_block_and_a_letter_block_in_one_time` | `cell::seal_pipe_with_nonce` then `cell::seal_cell_with_nonce` (`src/cell.rs` 95-107, 246-260) | padding block | letter block | an origin that spares a block of zeros the seal (see the power test) |
| `a_hop_carries_a_padding_block_and_a_letter_block_in_one_time` | `cell::carry` (`src/cell.rs` 284-303) | cell of a padding block | cell of a letter block | a hop whose work depends on what the pipe layer holds |
| `a_hop_carries_a_cell_from_the_origin_and_one_that_has_travelled_in_one_time` | `cell::carry` | cell fresh from the origin | the same cell after six owners' hops | a hop that searches the seal array or whose cost grows with the slots written |
| `a_unit_of_padding_and_a_unit_of_a_message_seal_in_one_time` | `link::seal_unit` (`src/link.rs` 94-120) | unit of zeros | unit of a message | a link that seals padding differently |
| `cutting_a_body_that_fills_its_last_block_and_one_that_barely_does_costs_the_same` | `erasure::cut` (`src/erasure.rs` 229-243) | body of three full blocks | body of two blocks and one byte | a cutter whose time follows the length of the body |
| `cutting_a_message_that_fills_its_last_piece_and_one_that_barely_does_costs_the_same` | `link::pieces` (`src/link.rs` 31-54) | message of three full pieces | message of two pieces and one byte | a cutter whose time follows the length of the message |
| `the_measurement_catches_an_origin_that_spares_a_padding_block_the_seal` | the named wrong implementation `seal_pipe_that_leaks` | padding block | letter block | **must fail the threshold**: proves the instrument has power |

`tests/timing.rs` keeps its three measurements of the erasure code (parity, reconstruction, and the
multiplication that returns early on a zero as its own power check), now on the shared instrument.

### Measured

Machine: Apple M1, 8 cores, macOS 26.7; Rust 1.92.0; `cargo` under `taskpolicy -b` with two build
jobs and one test thread, as the workspace pins (`core/Montana-Core/.cargo/config.toml`). Welch's t
over 2000 interleaved samples per class (500 for reconstruction, 250 for the leaking multiplication),
slowest tenth dropped. A t below 10 is "no leak seen at this power"; the two power checks must exceed
10 or the other numbers mean nothing.

| Measurement | t before the fix (release, baseline cutters) | t after the fix (release) |
|---|---|---|
| origin seals padding vs letter (`seal_pipe_with_nonce` + `seal_cell_with_nonce`) | 0.54 | 0.28 |
| hop carries padding vs letter (`carry`) | 0.16 | 0.38 |
| hop carries fresh vs six-hop cell (`carry`) | 0.01 | 0.23 |
| unit seals padding vs message (`seal_unit`) | 0.01 | 0.07 |
| `erasure::cut`, full vs barely-filled last block | **33.64 — fail** | 0.47 |
| `link::pieces`, full vs barely-filled last piece | **51.93 — fail** | 0.12 |
| power: origin sparing a padding block the seal (must exceed 10) | 309.40 | 200.18 |
| `erasure::parity` zeros vs drawn (`tests/timing.rs`) | 0.21 | 0.32 |
| `erasure::reconstruct` zeros vs drawn, 500 samples | 0.96 | 0.03 |
| power: multiplication returning early on a zero, 250 samples (must exceed 10) | 61.96 | 43.84 |

Two runs, each the full `mt-wire` suite in release (`cargo test --release -p mt-wire`): the first
with the cutters as they stood at the baseline, the second with `lay`. Every other test of the crate
(vectors, shape, wire, erasure, handshake) passed in both runs. The same suite in the debug build:
all 59 tests pass; the cutters after the fix measure t = 0.36 (`cut`) and t = 0.62 (`pieces`), the
other cell and link measurements 0.01 to 0.59, the two power checks 152.36 and 48.41. Before the fix
the debug build passed the cutters too (its own overhead hid the copy), which is why the release
numbers are the ones this report stands on.

What the numbers say, and do not say: a t below 10 means that at 2000 samples on this machine the two
classes are not told apart; it is not a proof of constant time, and a GPA with a longer trace or a
quieter machine could in principle see what this did not. The two power checks show that the
instrument sees an early return that skips the work of one block. It would not see a difference of a
few nanoseconds that hides under the noise floor of this machine.

## 4. Findings

**F1 — `erasure::cut` and `link::pieces` took a time that followed the length of the body. Found by
the tests, fixed.** Both copied `end - at` bytes of the last block (`src/erasure.rs` 237-239 and
`src/link.rs` 48-50 at the baseline): one copy of up to one block followed the length. In the release
build the instrument saw it at t = 33.64 (`cut`) and t = 51.93 (`pieces`); the debug build hid it under
its own overhead, which is why release numbers are the ones that count. The quantity leaked is the
length of the body, which the one width hides on the wire; the clock that reads it is the sender's
own, and it reaches the wire through the moment of sending — wholesale today (reactive sending), and
after stage 18 only as far as the slot lets it. The fix is one function, `lay` (`src/lib.rs`), that
writes every byte of a block by a mask on the position and no branch on the length; both cutters call
it. The bytes produced are identical, so every frozen vector stands, and a unit test holds the bytes
(`a_block_is_laid_as_a_copy_and_a_zero_fill_would_lay_it`). After the fix: t = 0.47 (`cut`)
and t = 0.12 (`pieces`).

**F2 — the client line's cover frames would announce themselves.** `crates/mt-net/src/framing.rs`
places `FLAG_PADDING` in a three-byte plaintext header (lines 1-8, 21, 53-59) and requires one in five
frames to be padding (193-202). Nothing outside the file's own tests and a fuzz target calls it, so no
shipped byte carries the flag today. **Documented, not fixed:** the set forbids synthetic filler
outright, so the right change is removal with the rest of the earlier line, not a repair of a flag.

**F3 — `mt-agent` (repository `montana_core`, outside this one) pads letters to two sizes.** A first
letter is padded to 1536 bytes (`Code/crates/mt-agent/src/card.rs` 41, 357-360), every later letter to
2048 (`src/letter.rs` 7, 24-31); the sealed lengths differ by 512 bytes on the wire of the postman
(`src/card.rs` 583 asserts the first). An observer of that line tells a first contact from a
continuing correspondence by size. The padding itself is zeros inside the AEAD and the branch in
`pad_envelope` is on the length bound only. **Documented here, to be filed against `montana_core`**;
this pull request does not touch that repository.

**F4 — the set describes stage 18 and the code runs stage 13.** The canonical slot, the holder rule,
the path, the right spent at the holder, the deferred delay, the digest set and the stable entries
are registered as awaiting stage 18 (`core/docs/Montana Registry.md` 127-144). Until then no sentence
of the set about the GPA is a property of the running code, and the application's transport (a
postman over TLS, two hops of one operator) is outside the set's model altogether. This is the
finding the table in section 1 is; it is restated here so the status line of this folder cannot be
read as anything but open.

## 5. What this pull request changes in the tree

- `core/Montana-Core/crates/mt-wire/tests/dudect/mod.rs` — the instrument, shared.
- `core/Montana-Core/crates/mt-wire/tests/timing.rs` — the erasure measurements on the shared instrument; no measurement changed.
- `core/Montana-Core/crates/mt-wire/tests/timing_cell.rs` — the seven measurements of section 3.
- `core/Montana-Core/crates/mt-wire/src/lib.rs`, `src/erasure.rs`, `src/link.rs` — the fix of F1: `lay`, and the two cutters calling it. Bytes unchanged; a unit test holds them.
- `Audit/GPA/README.md`, `Audit/GPA/INTERSECTION-MODEL.md`, one line in `Audit/README.md`.

No file of the normative set, of the client core or of the application is changed; of the reference
core's library code, the two cutters named above and nothing else. The report is self-verification by the implementing agent on the project's
own machine, not an independent audit.
