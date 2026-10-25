# The intersection model: what Montana claims against a global passive adversary, and what is open

**Status: open. No proof exists for the specification, and the shipped code implements only part of
the construction the claim stands on.** This file states the claim precisely so that the gap can be
measured rather than argued. This is a source reading, not a traffic measurement or a proof.

Baseline: commit `2c6951f8b887b7e42bfe745f5bb42cdb207508f7` of this repository (the normative set in
`core/docs/`, the reference core in `core/Montana-Core/`). Line numbers refer to that commit.

## 1. Setting

- **Time** is counted in windows `W`, the height of the chain (`core/docs/Montana Constitution.md`
  [I-15], lines 336-364: no physical clock enters the protocol).
- **Machines** form the active set of a window. Every device reachable from outside carries others'
  traffic by that fact (Network, "Carrying is the work", lines 498-507 in `core/docs/Montana Network.md`).
- **A pipe** between two correspondents has, per window, one tag derived from their shared secret
  (`core/Montana-Core/crates/mt-derive/src/tag.rs` lines 36-40); the holder of that tag is the machine
  whose commitment stands least above it (Network conformance point 13, line 721).
- **A delivery** is a body cut into blocks of `chunk_bytes` and padded with zeros to whole blocks and
  whole groups (`core/Montana-Core/crates/mt-wire/src/erasure.rs` lines 224-263); every block rides one
  cell of `cell_bytes` = 1232 (`core/docs/Montana Canon.md` line 897; `mt-wire/src/lib.rs` lines 49-52).
- **A step** re-seals a cell under the key of the next hop, rewriting one slot of a seal array that
  is full of drawn values from the origin (`mt-wire/src/cell.rs` lines 284-303; `mt-wire/src/draw.rs`
  lines 85-93).
- **The moment of sending** is, by the set, the canonical slot of the window derived from the sender's
  ephemeral identity (Network, "The canonical slot", lines 328-356; `mt-derive/src/tag.rs` lines 110-117).
- **Deferred mail** to a sleeping device leaves the holder after a delay drawn uniformly over the
  residues of the window not yet reached, never for live traffic (Canon lines 1211-1225; Network
  lines 434-442).

## 2. The adversary

**GPA.** A global passive adversary reads every link of the network: every byte, its length, its
direction and the instant it crosses, at every point, for as long as it likes. It does not inject,
delay or drop (an active variant is addressed by the set separately: Network lines 346-356, 671-672).
The proposed security assumptions are post-quantum: ML-KEM, ML-DSA and SHA-256.
The length accounting below records existing seal and external transport overhead; it does not
count TLS or a byte layout as a proof of Montana's protection.

**GPA plus a share of machines.** The set also prices an adversary who owns a fraction `f` of the
active set and so reads what a hop reads: the label of its own step, the target commitment and the
fact of forwarding (Network lines 273-276; the price `f` cubed at the first steps, lines 233-238).

## 3. What the set claims — exactly

**[I-16] Transport indistinguishability** (`core/docs/Montana Constitution.md` lines 366-375): for any
object the network carries, an observer of the wire at one point, *and at any set of points that does
not hold both ends of the same delivery*, cannot determine what the object is, who originated it, for
whom it is destined, or whether a given machine is its origin or a relay.

What the set explicitly does **not** claim:

- **Both ends.** An observer holding both ends of one delivery correlates envelopes; the price is set
  by the delay profile and the volume carried (Constitution lines 371-375 and 396-413; Network
  observability ledger line 691: *a named physical prohibition*).
- **Inseparability of summed flows.** The sum of others' traffic with one's own removes the profile
  of one correspondence from one line; it does not make the summands inseparable (Constitution lines
  396-404, citing Murdoch and Danezis 2005, Johnson et al. 2013, DeepCorr 2018).
- **The degenerate case.** A device that is not reachable (behind a translator, asleep) exposes to the
  owner of its line and to its neighbouring hop *that a source is here, and the moments and volumes
  of its own activity* (Constitution lines 507-514). Content, recipient, path and the correspondent's
  address are claimed closed there by the same rules as everywhere else.
- **A stream** (voice, video) is a limit of volume, named (Network ledger line 690).

## 4. Today's leakage: source-derived, with open boundaries

### 4.1 Revision, observer and units

**M = `2c7eb0df782da1679d20fc220499e9e3ab72c6b6`**, the `main` revision read for
this contribution. **Every source citation in sections 4 and 5 is at M**, unless another
repository/commit is explicitly named. Sections 1–3 retain their historical baseline above;
they describe the intended construction, not an implemented scheduling guarantee.
The published iOS snapshot is build 2311, source `648c3225b9ae`
([RELEASES.md:18](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/RELEASES.md#L18)). This identifies source, not a device observed running it.

Three observation levels must remain separate:

- **G**, the passive observer of every physical link: endpoints, connection lifetime,
  direction, packet bytes and lengths, timestamps, ordering and repeats. The observer sees
  encrypted transport bytes, not automatically the plaintext inside TLS.
- **P**, a postman or other transport endpoint after transport decryption: the application
  request, lengths and public routing fields at that endpoint. Giving these fields to G is
  a stronger observer, **G+P**, and must be declared.
- **U**, an application write or reference-wire unit before TCP segmentation. A write count
  is not a packet count. The two relevant writers are
  [core/Code/crates/mt-postman/src/wire.rs:28-58](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-postman/src/wire.rs#L28) and
  [core/Montana-Core/crates/mt-net/src/lib.rs:118-145](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-net/src/lib.rs#L118).

Below, `N` is the byte length of the text or body explicitly named by the row, not a
character count. `m`, `d`, `g`, `f` are the UTF-8 lengths of message ID, display name,
glyph and confirmation; optional fields are included in the encoded body length `B`.
`b64(x) = 4 ceil(x/3)` is the standard padded base64 length before JSON escaping.
`S(x) = x + 12 + 16` accounts for the existing nonce and seal tag. This is byte
accounting, not a new primitive or security claim: the phone delegates its body to
`MontanaDirect.seal` ([apps/messenger/ios/Code/Montana/MTPipe.swift:58-60](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MTPipe.swift#L58),
[apps/messenger/ios/Code/Montana/MontanaDirect.swift:1946-1954](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaDirect.swift#L1946)), the FFI calls
`seal_blob` ([core/Code/crates/mt-bindings/src/ffi_e2e.rs:291-323](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-bindings/src/ffi_e2e.rs#L291)), and that function
prepends the nonce to the sealed body
([core/Code/crates/mt-messenger-e2e/src/media.rs:40-47](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-messenger-e2e/src/media.rs#L40),
[core/Code/crates/mt-messenger-e2e/src/ratchet.rs:49-55](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-messenger-e2e/src/ratchet.rs#L49); the same 12/16 widths are
explicit at [core/Montana-Core/crates/mt-codec/src/lib.rs:430-431](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-codec/src/lib.rs#L430)).
All arithmetic below is derived from these encodings; **none is a measured packet size,
latency, throughput or sample count from a new run**.

### 4.2 Phone and postman's line

Every row describes successful local construction/one indicated operation, before retries
unless stated otherwise. The evidence column is at M. An **open** entry is part of the
leakage specification, not an assumption of zero traffic.

| Event / line | Size, count and cells at U/P | Moment and what G can infer; open boundary | Evidence at M (path:lines) |
|---|---|---|---|
| First-contact wake, phone → wake endpoint | Plain body `1088 + m + N + d + g + f + 5`; if it fits, pad to 1536, seal to 1564, base64 `env` length 2088. One `/wake` JSON request per admitted `fire`; fields `conv`, `from_id`, `mid`, `env` visible to P. No 1232-byte cell encoder on this call path. | Triggered by `wakeRdv`, suppressed for the same invite/ID in its minute window; if the text does not fit, the blob row applies before `fire`. G sees the request's transport trace. **open:** exact JSON/HTTP/TLS packet sizes and timings need the serialized fields, runtime/transport configuration and capture. | [apps/messenger/ios/Code/Montana/MontanaWakePush.swift:1739-1754](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaWakePush.swift#L1739), `:1761-1797`; [apps/messenger/ios/Code/Montana/MTPipe.swift:27-30](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MTPipe.swift#L27); seal chain in 4.1. |
| Continuing-letter wake, phone → wake endpoint | `MTNodeWire.letterHead` plus optional tails/author field, bounded to 2048, then seal to 2076, base64 `env` length 2768. One JSON request per attempted door; possible extra `silent`, `recall`, `look`, `slot` fields. P sees the envelope class; it is 512 bytes larger before base64, 680 larger after base64, with otherwise equal fields. These are payload differences, not measured TLS differences. | `wake` reseals at each door, stops on first 200, proceeds after other outcomes. The live lane can also send a copy. **open:** number of attempted doors, replies, lane copies and total bytes per user letter depend on state and responses. | [apps/messenger/ios/Code/Montana/MTNodeWire.swift:107-126](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MTNodeWire.swift#L107), `:145-154`; [apps/messenger/ios/Code/Montana/MontanaWakePush.swift:1542-1569](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaWakePush.swift#L1542), `:1632-1658`, `:1664-1721`. |
| Live phone letter, before its channel wrapper | `B = m + N + d + g + 3 + optional_tail_bytes`; body is sealed directly, **without** `padEnvelope`. First-contact envelope: `1 + ct.count + S(B)` (1088-byte ML-KEM ciphertext on the first-contact format); continuing envelope: `1 + 16 + S(B)`. Each successful `sendLetter` constructs one envelope, but may select/fan out to several channels. | This path leaks a variable length; the wake bucket cannot be applied to it. First-contact and continuing envelopes of equal `B` differ by 1072 bytes before the channel wrapper. **open:** route multiplicity and per-hop wrapping require the selected route, carrier implementations and trace; no inference that these are reference-core cells. | [apps/messenger/ios/Code/Montana/MontanaPhoneNode.swift:913-933](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaPhoneNode.swift#L913), `:945-985`, `:1006-1060`, `:1158-1162`, `:1216-1219`. |
| One direct-channel overlay write of an envelope of `E` bytes | At `sendOverlayMessage`: overlay header 54, network-message header 5, seal 28, outer length prefix 4: **`E + 91` bytes** handed to `MTWire.write`, one frame per call. For the preceding bare live envelopes this is `B + 1208` first / `B + 136` continuing, conditional on that exact wrapper path. | G sees the outer transport's bytes; P/channel peer sees overlay metadata. **open:** transport dressing, segmentation, ACKs, handshakes, reconnects and carrier-added data are not included in this one-write formula. Settle by tracing the selected `MTWire` transport and each hop. | [apps/messenger/ios/Code/Montana/MontanaNetFrames.swift:102-108](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaNetFrames.swift#L102), `:128-144`; [apps/messenger/ios/Code/Montana/MontanaDirect.swift:1184-1202](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaDirect.swift#L1184), `:1927-1954`. |
| Long text of `N` UTF-8 bytes, either wake class | One blob constructed from `mid || 0 || text`: `m + 1 + N` plaintext, **`m + N + 29` sealed bytes**, with no media bucket call. A reference replaces the text in the first/continuing wake envelope above. A cached usable reference skips rebuilding/uploading it. | Upload precedes the reference wake. P sees blob ID and base64 payload; conditional on known `m`, its decoded size exposes `N`. **open:** network writes are not always one: existing blobs, door hedges, retries and replication change counts. | [apps/messenger/ios/Code/Montana/MontanaWakePush.swift:1466-1490](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaWakePush.swift#L1466), `:1788-1797`, `:1922-1975`; [apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift:1730-1749](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift#L1730). |
| Media body of `N > 0` bytes supplied to `sealAndUpload` | `k = ceil(N/524288)` pieces, `n_i = min(524288, N - 524288 i)`. `P(n)=256` for `n<256`; otherwise `s=2^(bit_length(n)-5)`, `P(n)=s ceil(n/s)`. Piece `i` becomes `P(n_i)+28` sealed bytes, base64 `b64(P(n_i)+28)` for JSON upload. `N=0` is refused by this entry point. No 1232-byte cells here. | The function currently seals into local storage; its comment is not evidence of a network send. Later upload determines moments. P can see piece count/buckets and repeated blob IDs; same key/index/padded bytes derive the same nonce and sealed blob. **open:** all-link count/timing requires upload/replication state, not just `N`. | [apps/messenger/ios/Code/Montana/MontanaMediaKit.swift:138-140](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaMediaKit.swift#L138), `:161-184`, `:232-269`; [core/Code/crates/mt-messenger-e2e/src/media.rs:28-47](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-messenger-e2e/src/media.rs#L28); [core/Code/crates/mt-bindings/src/ffi_e2e.rs:390-394](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-bindings/src/ffi_e2e.rs#L390); [apps/messenger/ios/Code/Montana/MontanaWakePush.swift:1948-1975](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaWakePush.swift#L1948). |
| Media manifest / upload and download accompaniment | Manifest text at most 1900 UTF-8 bytes is returned inline; otherwise one separately sealed JSON manifest blob plus its reference is constructed. Blob PUT JSON includes `bid`, base64 `data`, and optional flags. Presence checks can suppress upload; bulk success schedules mirroring. These are additional to the letter envelope. | **open:** `N` alone does not determine manifest size, thumbnail/metadata, cache hits, mirrors, GET requests, receiver activity or HTTP responses. Settle with exact manifest, cache/recipient state and end-to-end traces, including the server and notification legs. | [apps/messenger/ios/Code/Montana/MontanaMediaKit.swift:195-215](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaMediaKit.swift#L195); [apps/messenger/ios/Code/Montana/MontanaWakePush.swift:1507-1528](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaWakePush.swift#L1507), `:1922-1975`, `:2131-2239`. |
| Send moments, retries and background traffic | No per-letter fixed all-link count follows from these branches: first contact can invoke both `wakeRdv` and `sendLetter`; a continuing letter carried on a standing near channel returns before the bell. Other cases use the retry-dependent bell gate. | Enqueue calls `attempt`; this is a reactive trigger, **not proof that a packet timestamp equals a keystroke**. OS scheduling, upload waits, door responses, retry state, subscriptions, receipts and background traffic remain in the trace. **open:** exact instants and counts need point 3's authorized field measurements; this contribution runs none. | [apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift:1009-1016](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift#L1009), `:1653-1689`, `:1704-1728`; [apps/messenger/ios/Code/Montana/MontanaWakePush.swift:1621-1630](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaWakePush.swift#L1621), `:1664-1721`. |

**F3 status: open on both the agent and phone lines.** The main-revision rows above are
phone evidence. `mt-agent` is outside this repository's published `core/Code` tree at M;
the separately verified public snapshot is `efir369999/montana_core` commit
`f2bf694cfd0dc6bc3f8803c8037ee3df13eafbb0`,
[core/Code/crates/mt-agent/src/card.rs:41,345-369](https://github.com/efir369999/montana_core/blob/f2bf694cfd0dc6bc3f8803c8037ee3df13eafbb0/core/Code/crates/mt-agent/src/card.rs#L41) (1536) and
[core/Code/crates/mt-agent/src/letter.rs:7,24-31](https://github.com/efir369999/montana_core/blob/f2bf694cfd0dc6bc3f8803c8037ee3df13eafbb0/core/Code/crates/mt-agent/src/letter.rs#L7) (2048).
It must not be presented as a file on this repository's main. The corrections and the
historical/current phone line mapping are in [FINDINGS-F2-F3.md](FINDINGS-F2-F3.md).

The retry delay rule is also source-visible, although exact firing times are not:
for attempt count `t`, let `p=max(0,t-12)`. With a node ACK the backoff is
`min(14400,300*2^p)` seconds; for a first contact after the fast ramp it is
`min(300,20*2^p)`; otherwise `min(20,2^max(0,t-1))`.
These are local eligibility delays, not a canonical sending slot or a guarantee of
physical departure at that instant ([apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift:446-447,466-471,1324-1325](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift#L446)).
The phone's local `MTPipe.window` divides Unix time by 60
([apps/messenger/ios/Code/Montana/MTPipe.swift:27-30](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MTPipe.swift#L27)); it must not be identified
with the authenticated chain height described in historical section 1.

### 4.3 Published `core/Code` postman API: a separate, conditional trace

These are **API encodings**, not an assertion that every current phone letter calls this
API. In particular the current delivery branches quoted in 4.2 must not be replaced by an
assumed MUQ-only path. Let `q` be the ciphertext/shard length passed to `HostDeposit`.

| Operation / observed link | Source-derived sizes and counts | Moments / open boundary | Evidence at M |
|---|---|---|---|
| Deposit, sender → entry proxy | `HostDeposit = q + 74 + 3309`; seal to host adds 1088-byte ML-KEM ciphertext and 16-byte tag, so `h = q + 4487`. `ProxyForward = 32 + 4 + h`. One operation tag + 4-byte frame length gives **`q + 4528` TLS plaintext bytes** sent; one 1-byte reply. Zero reference-wire cells. P at proxy sees host overlay and sealed blob, not the inner deposit. | A fresh TCP+TLS connection per call; writes/flushes occur after opening. **open:** G's TLS record sizes, handshake bytes, packets and elapsed time require runtime configuration/capture. | [core/Code/crates/mt-overlay/src/muq.rs:117-150](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-overlay/src/muq.rs#L117), `:208-221`; [core/Code/crates/mt-crypto/src/lib.rs:72-82](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-crypto/src/lib.rs#L72), `:504-521`; [core/Code/crates/mt-postman/src/muq_client.rs:32-47](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-postman/src/muq_client.rs#L32), `:96-103`; [core/Code/crates/mt-postman/src/wire.rs:47-58](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-postman/src/wire.rs#L47). |
| Deposit, entry proxy → host | If decoding/routing succeed, another fresh connection: tag + length + same sealed blob = **`q + 4492` TLS plaintext bytes**, with a 1-byte reply read. Host opens the deposit and sees its fields including ciphertext length; proxy forwards the sealed bytes unchanged. | Forwarding follows receipt/route lookup; response to sender follows forwarding. **open:** failures can stop before this leg; two TLS ciphertext streams cannot be equated with the unchanged inner bytes by G without decryption. | [core/Code/crates/mt-postman/src/muq.rs:218-258](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-postman/src/muq.rs#L218), `:464-487`; [core/Code/crates/mt-overlay/src/muq.rs:117-150](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-overlay/src/muq.rs#L117). |
| First use / collection / ACK / shard fan-out | `connect` makes a probe; `node_hello` writes 1 and reads 1184 + 32 application bytes. Registration, nonce request, subscription, ACK and erasure deposit are separate operations. Fetch first requests a nonce, then a signed subscription; erasure deposit loops over the supplied hosts. | **open:** number of these operations per first contact or letter, fetch response lengths and chosen `k,n` are caller/state dependent. Settle by identifying the active caller and its exact arguments, queue contents and retries; TLS/platform traces are additionally required for G. | [core/Code/crates/mt-postman/src/muq_client.rs:50-60](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Code/crates/mt-postman/src/muq_client.rs#L50), `:68-93`, `:127-206`, `:209-248`, `:251-281`. |

### 4.4 `core/Montana-Core`: cells are not deposits or TCP packets

At M: `C=1232`, step label 16, nonce 12, seal tag 16, target 32, 15 seal slots
of 16 bytes; outer plaintext 1188, body chunk 868, link message piece 1183.
These are derived from [core/Montana-Core/crates/mt-genesis/src/lib.rs:146-149](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-genesis/src/lib.rs#L146),
[core/Montana-Core/crates/mt-codec/src/lib.rs:427-443](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-codec/src/lib.rs#L427), and
[core/Montana-Core/crates/mt-wire/src/lib.rs:87-111](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/lib.rs#L87).
Define `U(x)=max(1,ceil(x/1183))` and
`D(N)=16 ceil(max(1,ceil(N/868))/12)` for accepted sizes. The group has 12 data and
4 parity blocks ([core/Montana-Core/crates/mt-wire/src/erasure.rs:59-76,229-271](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/erasure.rs#L59)).

| Event / line | Sizes, counts and visible fields | Moment / open boundary | Evidence at M |
|---|---|---|---|
| First link acquaintance, four handshake flights | Logical lengths **3138, 4226, 4397, 3309**; respectively **3, 4, 4, 3** units of 1232, directions initiator→responder, reverse, forward, reverse. Thus 14 units / 17248 stream bytes before later messages. Flight units are **unsealed**: G can parse their piece index/count, suite, ML-KEM public keys/ciphertexts and ML-DSA answering keys/signatures. The last piece's zero padding is clear too. Equal width does not make a handshake indistinguishable from sealed data. | Flights alternate after the previous read/cryptographic work. This is first connection between machines, not first letter between people. **open:** how many new links a user contact opens, TCP packetization, retransmission and timestamps need caller/state and capture. | [core/Montana-Core/crates/mt-wire/src/handshake.rs:45-63,84-104,126-142,160-172,418-420](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/handshake.rs#L45); [core/Montana-Core/crates/mt-codec/src/lib.rs:437-443](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-codec/src/lib.rs#L437); [core/Montana-Core/crates/mt-net/src/lib.rs:123-145,333-362,404-435](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-net/src/lib.rs#L123); [core/Montana-Core/crates/mt-wire/src/link.rs:31-50](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/link.rs#L31). |
| Ordinary link message of `x` encoded bytes | `U(x)` sealed 1232-byte units. At this stream boundary G sees a fresh 16-byte label and 12-byte nonce followed by sealed bytes for each unit. Kind, piece index/count and padding are inside the seal. Physical endpoints, count and timing remain visible. | Synchronous loop seals then writes each unit; no canonical-slot wait in this function. **open:** exact packet splits and elapsed times require transport traces; `x` must be the encoded message length, not automatically a user's `N`. | [core/Montana-Core/crates/mt-wire/src/link.rs:31-57,94-119](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/link.rs#L31); [core/Montana-Core/crates/mt-net/src/lib.rs:118-120,239-251](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-net/src/lib.rs#L118). |
| Body passed to `publish::cells_of`, `N` bytes | `D(N)` cells, each 1232 bytes, including zero-filled data/parity group completion. For an empty body this constructor still produces one group. Total must fit a `u16`, otherwise it returns an error. | This constructor does not itself send. **open:** mapping an app first contact/letter to this constructor is not established by the quoted caller, which publishes consensus objects. Settle with an actual messenger caller and its serialization; do not impose this formula on 4.2. | [core/Montana-Core/crates/mt-wire/src/erasure.rs:229-271](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/erasure.rs#L229); [core/Montana-Core/crates/mt-wire/src/publish.rs:19-57](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/publish.rs#L19); [core/Montana-Core/crates/montana-node/src/pulse.rs:846-863](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/montana-node/src/pulse.rs#L846). |
| Actual node deposit of those cells to an acquaintance | One cell is encoded with a 16-byte point: `16+1232=1248` message bytes, hence **`U(1248)=2` link units per cell**, **`2D(N)` units / `2464D(N)` stream bytes per completely successful acquaintance send**. This excludes handshake/collection and failures. The inner cell is inside the link-message seal. | `publish_at` loops over cells; `publish_at_the_point` loops over reached acquaintances and can stop a failed acquaintance partway. **open:** number of reached links, failures, background messages and later reads are state-dependent. | [core/Montana-Core/crates/mt-wire/src/message.rs:8-9,94-107](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/message.rs#L8); [core/Montana-Core/crates/montana-node/src/pulse.rs:774-782,850-863](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/montana-node/src/pulse.rs#L774); [core/Montana-Core/crates/montana-node/src/lib.rs:866-898](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/montana-node/src/lib.rs#L866); link-message row above. |
| Collection from a public point | Query is `16+2=18` bytes → 1 link unit. Answer with `c` cells is `2+1232c` bytes → `U(2+1232c)` units, including 1 unit for an empty answer. Collections can continue until an answer is shorter than the cap. | Request/answer ordering exposes activity and total units. **open:** `c`, number of queries and attribution to a particular letter need point contents and caller history. No assumption that collection falls in the deposit's window. | [core/Montana-Core/crates/mt-wire/src/message.rs:126-142,258-280](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/message.rs#L126); [core/Montana-Core/crates/montana-node/src/pulse.rs:789-837](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/montana-node/src/pulse.rs#L789); [core/Montana-Core/crates/mt-net/src/lib.rs:242-251](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-net/src/lib.rs#L242). |
| Multi-hop cell carriage / intended slot | `carry` opens, changes a seal slot and reseals a cell; slot has a derivation. These functions alone do not specify a complete sequence of sends on every link. | **open:** the published register still marks path, holder, spending and deferred delivery work as awaiting stage 18. A first-contact/letter all-link schedule cannot be obtained from these functions alone. Settle with the integrated sender/holder/forwarder trace and its authenticated window, the latter tracked in #4. | [core/Montana-Core/crates/mt-wire/src/cell.rs:284-303](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-wire/src/cell.rs#L284); [core/Montana-Core/crates/mt-derive/src/tag.rs:110-117](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-derive/src/tag.rs#L110); [core/docs/Montana Registry.md:127-144](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/docs/Montana%20Registry.md#L127); the actual deposit caller above. |

### 4.5 Intersection consequence and explicitly open rows

For observation intervals `r`, form `S_r` and `R_r` from the actual outbound and inbound
access-line events. Their membership, per-link counts, volume buckets, first-use shape,
public handshake material and timing cannot be discarded from the adversary's view.
The conditional counts and reactive triggers are the cited rows above. Carrying another
person's traffic would change the interpretation of membership; it does not by itself
prove that the summed flows cannot be separated. Neither a within-window unlinkability
theorem nor an across-window bound is asserted here.

**open:** packet/record sizes and timestamps on all links; phone-to-node, node-to-node,
node-to-notification-service and notification-service-to-phone mapping; complete receive,
receipt, retry, reconnect and replication counts; first-use directory/card/subscription
traffic; Android and other transport paths not mapped by these iOS rows; the build and
reader versions actually in service. To settle them requires a pinned deployment and
route/caller map, all relevant server/client source, and synchronized first-contact and
`N`-byte-letter captures with runtime/state metadata. That is the chair's point 3 and
subsequent implementation work, **not performed here**. A missing value is never zero.

## 5. What a proof would require — open

### 1. Leakage function of today's code

Define an execution `T` to include inputs, metadata, initial queue/cache/connection state,
all chosen routes, randomness, platform behavior and the environment's delivery schedule.
Its all-link observation is the ordered sequence

```
O_G(T) = [(link, direction, connection, event_kind, time, length, bytes)]
```

including connection open/close, requests, replies, notification/control traffic and retries.
The intended structural leakage projection is

```
L_G(T) = [(link, direction, connection, event_kind, time, length,
           public_fields, observable_equality)]
L_G+P(T) = (L_G(T), each observed postman's decoded request/response metadata)
```

`public_fields` means only bytes readable at the specified layer: for example the unsealed
reference handshake in 4.4, not automatically HTTP JSON inside TLS. `observable_equality`
retains repeated *visible* byte strings/identifiers; an unchanged blob inside two independently
encrypted TLS sessions is not thereby a visible equality for G. Full ciphertext bytes remain
in `O_G`; proving that they reveal no more than `L_G` is a separate obligation.

Let `W(T)` be the full application-write trace, including opaque encoded bytes.
The tables define its currently derivable structural projection `L_src(T)`: substitute
the row's accepted input lengths and actual operation count, retaining order and symbolic
send times. At every link, sum **all** relevant row instances, including failures and
background operations; do not multiply a one-operation formula by an assumed one attempt.
For G, application writes must pass through a separately specified transport transformation
`F_env` (HTTP serialization, TLS records, TCP packets, retransmissions, routing and OS timing).
Thus the source-level specification is **partial**:

```
L_src(T) = project_source(W(T))
L_G(T)   = project_public(F_env(W(T), runtime_state, environment))
```

Sufficiency of `L_src` to simulate that second projection is itself **open**; for example,
encoded byte values may affect serialization. `F_env` and rows marked **open** are
obligations, not an arbitrary function silently
chosen to hide activity. A packet-level numerical `L_G(N)` cannot presently be stated:
`N` alone omits fields, contact state, caches, route count, retries and response contents.
The witnesses for that dependence are 4.2's delivery/blob branches, 4.3's API operations,
and 4.4's acquaintance and collection loops. No new run was used to fill any open entry.

The former phrase “today the instants are the keystrokes” is replaced by **reactive, state-
and environment-dependent send events**. The first attempt is triggered from enqueue
([apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift:1009-1016](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/apps/messenger/ios/Code/Montana/MontanaDeliveryEngine.swift#L1009)); the reference
link writes after sealing ([core/Montana-Core/crates/mt-net/src/lib.rs:242-251](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/Montana-Core/crates/mt-net/src/lib.rs#L242)). Neither
source states equality between an input-event time and a physical packet timestamp.

### 2. Notion that can be stated from this function

A candidate is **relationship indistinguishability modulo declared leakage**, for one
interval and then `n` intervals. Two workload worlds change who corresponds with whom,
while fixing the public machine set and public connection/handshake identities, public
metadata and background workload. Under the *same declared environment distribution*,
they must induce the same joint distribution of `L_G` (or explicitly `L_G+P`) over all
links and all `n` intervals. Conditioning away unequal counts, lengths, link participation
or timing after seeing the secret bit is not allowed. For each quantum polynomial-time
passive distinguisher `A`, the proposed goal is

```
Adv_A = |Pr[A(O_G(T_0)) = 1] - Pr[A(O_G(T_1)) = 1]| <= epsilon(n, security_parameter).
```

This defines a goal, **not a proved property of this implementation**. The leakage must be
fixed independently of the challenge relationship and computable from the allowed public
inputs/state; its unresolved transport rows cannot be defined after the fact to make the
goal true. Explicitly demonstrate that the admissibility rules allow different relationships
before claiming anonymity: equality of the complete physical-link trace may leave no such
pair in sparse traffic. A first/continuing wake pair with different payload lengths, or a
live-letter pair with different `B`, is not a length-hiding challenge under the cited rows.
Nor does this conditional notion hide `S_r`, `R_r`, timestamps or volume across windows.
A stronger notion that hides those requires a different leakage specification and proof.

**open:** complete transport projection, admissible nontrivial challenge class, simulation/
hybrid argument for the ciphertext view, composition of all paths, and a bound on
`epsilon`. Cryptographic hypotheses remain ML-KEM, ML-DSA and SHA-256; no external TLS
standard is promoted into Montana's anonymity argument. Historical timing measurements
in [LEMMA.md](LEMMA.md) cannot discharge these obligations.

### 3. In-window theorem

**open.** Establish the declared property for an integrated schedule, forwarding and
collection implementation, including public fields and all send events. Fixed unit width
and local timing measurements alone do not imply that property (4.4 distinguishes
unsealed handshake units, sealed link units and encapsulated delivery cells).

### 4. Across-window bound

**open.** Bound the distinguisher's advantage over the joint trace of `n` intervals as a
function of activity, route overlap and reachable-carrier composition. No numerical bound
or minimum traffic floor follows from the source rows here. In particular a deferred
collection is not assumed to remain in the deposit window.

### 5. Implementation and authenticated windows

**open.** Reconcile the intended construction in sections 1–3 with each actual transport
path in section 4. The published register's pending mechanisms are at
[core/docs/Montana Registry.md:127-144](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/core/docs/Montana%20Registry.md#L127). Authentication of the window and its history is
[issue #4](https://github.com/efir369999/montana_messenger/issues/4); no part of that scope
is reimplemented or claimed complete here. The broader audit remains
[issue #2](https://github.com/efir369999/montana_messenger/issues/2).

## 6. Relation to the other open items

- Protocol-13 item 1 (window proof, issue #4) is the authentication of the window the slot is read
  from; nothing here closes or duplicates it. Without it the slot has no authenticated height.
- Protocol-13 item 11 ([11-ANONYMITY.md](https://github.com/efir369999/montana_messenger/blob/2c7eb0df782da1679d20fc220499e9e3ab72c6b6/Audit/Protocol-13/11-ANONYMITY.md)) is the anonymity of a node's identity and
  origin; this file is about the correspondence carried, and the two meet at the access line of an
  unreachable device.
- Issue #2 (external audit) names *traffic analysis mitigation against simulated GPA timing attacks*
  as scope item 2; the historical PR #7 timing tests are the project's own measurement of the
  code-level part of that item, and are self-verification, not an external audit.

## References

- Danezis, G. *Statistical disclosure attacks: traffic confirmation in open environments.* 2003.
- Danezis, G., Serjantov, A. *Statistical disclosure or intersection attacks on anonymity systems.* 2004.
- Murdoch, S., Danezis, G. *Low-cost traffic analysis of Tor.* 2005.
- Johnson, A., Wacek, C., Jansen, R., Sherr, M., Syverson, P. *Users get routed.* 2013.
- Nasr, M., Bahramali, A., Houmansadr, A. *DeepCorr.* 2018.
- Backes, M., Kate, A., Manoharan, P., Meiser, S., Mohammadi, E. *AnoA: a framework for analyzing anonymous communication protocols.* 2013.
- Kuhn, C., Beck, M., Schiffner, S., Jorswieck, E., Strufe, T. *On privacy notions in anonymous communication.* 2019.
- Reparaz, O., Balasch, J., Verbauwhede, I. *Dude, is my code constant time?* DATE 2017.
