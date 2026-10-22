# Source review of the premises of issue #2

Reviewed public commit: `e558daace1ef32283167867cfa2cf8db883d48fe` (2026-10-22).
See [snapshot.json](snapshot.json) for independently recomputable folder identifiers.
Line numbers below refer to this commit; symbols remain searchable after later publications.
This is a Codex-assisted contributor review from the public snapshot, not an independent
third-party assurance report. It does not claim a production exploit or a network experiment.

## Assessment

The proposed audit is worthwhile, but its initial description assumes properties the public
snapshot does not establish. Quoting those assumptions as accomplished architecture would
mis-scope the audit before it starts. The immediate deliverable is a bounded source/design
assessment with a clear route to a later deployment assessment.

| Premise in #2 | Public evidence | Review conclusion / required acceptance evidence |
|---|---|---|
| Single-use routing tokens | `core/Code/crates/mt-overlay/src/muq.rs:20` defines a queue with separate send/receive IDs; `queue_host.rs:102,109` stores and uses their mapping for deposits; `subscribe_relay:158` consumes a host-issued nonce | The reviewed queue ID is reusable. A fetch nonce is one-time. The probes accept two distinct authorized messages under one send ID and reject reuse of a fetch nonce. An auditor must identify which actual deployed token is claimed to be single-use, its consumer and persistence across restart. This is a scope distinction, not a newly reported vulnerability. |
| Queue obfuscation / onion-routing integrity | `core/Code/crates/mt-overlay/tests/e2e_stage2_offline.rs:19` exercises an in-memory host; lines 54–62 put `hd.to_bytes()` directly into `ProxyForward.sealed` and decode it again | That test covers encoding, signature authorization, storage and reconstruction. It performs no sealed-box encryption at that hop, socket I/O or observation of an adversary. Its name and comments do not establish a deployed onion path or GPA resistance. Supply the route implementation, hop bindings, keys, collusion model and captures. |
| Metadata resistance from encrypted delivery | `core/Code/crates/mt-messenger-e2e/src/media.rs:42` seals the input at its existing length; `apps/messenger/ios/Code/Montana/MTPipe.swift:70` calls `MontanaDirect.seal`, whose `MontanaDirect.swift:1946` passes the payload unchanged to `mt_e2e_seal_blob` | The existing F-14 remains relevant to this snapshot. The library probe seals 16 and 4096 bytes into 44 and 4124 bytes, with a successful decrypt and rejected tampering. Encryption/authentication does not hide the input-length difference. This does not measure TLS packetization or identify real conversations. |
| Padding provides a single observable shape | `core/Code/crates/mt-overlay/src/inbox.rs:5` declares seven padding buckets | Buckets 256 and 1024 differ. A fixed-cell reference-core construction cannot be credited to a client path merely because both are in this repository. Capture the emitted path and include framing, fragmentation, scheduling and cover traffic. |
| Comprehensive Rust/Go review is sufficient | `AUDIT.md`, sections 1 and 3, excludes the node services; `README.md` locates Swift, Kotlin and JNI callers; Go is used for Xray | The audit needs both Rust core generations and the application composition. Production delivery, wake, naming and diagnostics services are unavailable in this public snapshot (F-4). Their operator behavior cannot be inferred from client code. |
| Stable source implies a certified release | `RELEASES.md` supplies public folder trees; `AUDIT.md` F-12 records binary/core provenance limitations; Protocol-13 has its own candidate source | A snapshot can be frozen for source assessment. Source-to-binary binding and deployment identity are separate evidence. The supplied inventory verifies Git objects only. |

The iOS pipe also derives a tag from the pair's secret and minute in `MTPipe.swift:46`, with
`windowSeconds = 60` at line 34. This is another construction to scope explicitly, not permission
to generalize the MUQ probes to every application path. The source review does not assert that
all released binaries execute the exact library bytes tested here.

## Threat model the engagement must settle

A global passive adversary observes timing, direction, sizes, endpoints and connection lifetimes
at every relevant link simultaneously and can correlate observations across sessions. Payload
confidentiality, anonymous identifiers and one-time nonces are different properties. The auditor
must document the observer's visibility, background load, observation duration and allowed
auxiliary knowledge for every result. State clock synchronization error and what TLS/transport
framing exposes to that observer.

Separate experiments cover compromised/colluding hops, packet injection, replay, selective
delay/drop and forced reconnection: those are active capabilities and must not be silently
folded into or excluded from a claim about a passive adversary. Also record device compromise,
DNS, wake services, diagnostic logs, direct paths and history as separate trust boundaries.
Bootstrap, idle operation, text, receipts, typing, attachments, calls, offline fetch, restart and
Wi-Fi/cellular transitions require their own observations. A pass in one regime says nothing
about the others without an argument.

## Evidence standard

Each finding needs a claim, public commit/tree, reachable symbol, observer capabilities,
minimal synthetic reproducer, command/environment, observed/expected outcome, impact and
remaining limits. Cite existing F-1/F-3/F-4/F-12/F-14 and Protocol-13 items 9/11/12/13 where
appropriate instead of creating a competing closure register. Newly discovered weaknesses use
[SECURITY.md](../../SECURITY.md); do not publish private endpoints, user traffic or secrets.

For traffic results pre-register the candidate population, workload family, capture duration,
bin widths, lag search, training/validation/test split, attack selection and statistical analysis.
Split by independent sessions/devices/network conditions, not overlapping windows of the same
capture. Include confidence intervals, power/sample-size justification, an unprotected positive
control and null/constant controls; account for lag/attack/hyperparameter selection in the null
procedure. A non-significant result or an unsuccessful baseline attack cannot prove anonymity.
Do not invent a numeric acceptance threshold after observing the result: maintainer and auditor
must agree the operational criterion before collecting evaluation traces.

## Intake recommendation

Accept this contribution as reproducible audit preparation. Keep issue #2 open until the
maintainer records its funding and missing-service dispositions, or explicitly closes its
planning scope with owned successor work. Reserve external-audit completion for an actual
independent report and retest. The whole point of the contribution is to make those decisions
reviewable without falsely turning preparation into certification.
