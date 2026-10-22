# Reproduction record

Date: 2026-10-22. Source reviewed:
`e558daace1ef32283167867cfa2cf8db883d48fe`. This is implementer/contributor verification,
not an independent audit. No deployed server or physical phone was tested.

Environment: macOS/Darwin arm64, Python 3.12.2, Rust 1.92.0
(`ded5c06cf 2025-12-08`), host OpenSSL 3.6.2. The client core/harness builds its own
OpenSSL 3.5.5 from the committed lockfile. Commands ran from the repository root
except the two full baseline workspaces indicated below.

## Completed checks

| Exact command | Observed result | What it establishes |
|---|---|---|
| `python3 -m unittest discover -s doc/external-audit/tests -v` | 26 tests passed | Inventory mismatch/missing/duplicate handling and attack-instrument positive/negative controls |
| `python3 doc/external-audit/snapshot.py --revision e558daace1ef32283167867cfa2cf8db883d48fe` | Exit 0; output in [snapshot.json](snapshot.json) | The three RELEASES folder trees match this public commit; all three lockfiles and the separate Protocol-13 source are pinned |
| `rustup run 1.92.0 cargo test --release --locked --offline --manifest-path doc/external-audit/probes/Cargo.toml` | 4 tests passed, 0 failed | Characterization of the actual libraries: variable sealed length, multiple padding buckets, reusable queue ID, one-time fetch nonce |
| `rustup run 1.92.0 cargo fmt --manifest-path doc/external-audit/probes/Cargo.toml -- --check` | Exit 0 | Harness formatting |
| `git diff --check` | Exit 0 | Patch whitespace |

All registry dependencies in the harness lockfile were also compared with `core/Code/Cargo.lock`
by `(name, version, source, checksum)`: no dependency version or checksum changed. The lock was
seeded from the existing client lock because fresh offline resolution refused the yanked
transitive `spin 0.9.8`. The locked build above succeeded without replacing that dependency.

Synthetic positive control:

```sh
python3 doc/external-audit/traffic.py --demo > /tmp/montana-audit-positive-control.json
python3 doc/external-audit/traffic.py /tmp/montana-audit-positive-control.json --max-lag 3
```

Observed: 8 candidates; true flow `flow-3` rank interval `[1, 1]`, correlation `1.0`,
lag `3`, overlap `125` bins, top-1 credit `1.0`, uniform-random reference `0.125`.
Input SHA-256: `9dbeba6dcd12073331f852c5c4da34bb3e064319fb223c9c4927586e778ce9aa`.
This is an intentionally correlated synthetic trace, **not measured Montana traffic**.
Constant traces produce `inconclusive`; equal candidates receive fractional credit.
No statistical significance, production attack success or security acceptance is claimed.

## Existing workspace baseline (separate from this PR's checks)

The following commands were started on the unchanged published source trees:

```sh
(cd core/Montana-Core && cargo test --workspace --release --locked --offline)
(cd core/Code && cargo test --workspace --release --locked --offline)
```

The initial reference-core run stopped with six `montana-node` unit-test failures at
socket creation: `Operation not permitted (os error 1)`. That is an execution-environment
restriction, not evidence of a protocol defect. A repeat with loopback sockets permitted
passed that unit-test target and proceeded to the longer integration proofs.

Full workspace completion is not yet recorded here. Neither a partial baseline nor the four
focused characterization probes should be presented as a complete workspace pass. The final
PR report must preserve this boundary if the longer baseline runs are incomplete.

GitHub-hosted CI was not installed: the available OAuth authorization lacks the `workflow`
scope. The proposed workflow was excluded from publication; `check.sh` supplies the local
check entry point. No hosted CI result is claimed.

Application builds, binary reproduction, actual node services, real capture collection,
side-channel certification, engagement/funding and independent remediation review were not
performed by this contribution.
