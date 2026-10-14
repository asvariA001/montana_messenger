# Reproduce item 1

Run on an isolated build host. These tests build arithmetic proofs that are accepted despite
the missing history checks. A passing result reproduces an **open finding**; it is not a
finality conformance pass. The inputs are synthetic and the tests open no network listeners.

From a clone that contains this report, create a fresh worktree of the public source revision:

```sh
git worktree add --detach ../montana-window-replay 13c4cd994c55ead68a75cb9322163c1307966b33
cp Audit/Protocol-13/reproduce/window_audit.rs ../montana-window-replay/core/Montana-Core/crates/mt-proof/tests/window_audit.rs
cd ../montana-window-replay/core/Montana-Core
cargo test --locked --release -p mt-proof --test window_audit -- --nocapture
```

The expected observations are two `OPEN FINDING` lines followed by two passing reproduction
tests. No complete Proposal verifier is invoked. The added file changes the source tree's test
count; this command intentionally names the reproduction target and makes no workspace-pass
claim. The compiler is the one pinned by that worktree's `rust-toolchain.toml`.

For the arithmetic of the specification's conditional history bound, from the report checkout:

```sh
python3 Audit/Protocol-13/reproduce/check_fold_bound.py core/Montana-Core
```

The script reads the modulus, repetition count and security target from the published Rust
declarations. It checks an upper-bound calculation, not the soundness of the construction or
an adversary's achieved probability. Keep each source tree and its build output separate when
comparing implementations or revisions.

## Actual-node weight checks

Two additional files preserve the candidate's actual-node tests. They are separate from the
completed public-snapshot arithmetic replay above; their recorded executions use the source
manifests in the weight-mask and arithmetic-repair receipts.

| Test file | What the recorded result means |
|---|---|
| `standing_privacy.rs` | The confidentiality expectation fails before the repair, recovering all three quantities from the next public part. It passes after the private-mask repair. The fixture also checks that the recovery method opens a commitment made with the retired mask derivation. |
| `window_weight_audit.rs` | A passing result reproduces an OPEN finding: the actual verifier accepts commitments to zero and `u64::MAX` with one unchanged presence proof and admitted-member context. |

The files belong under `core/Montana-Core/crates/montana-node/tests/` in the public layout.
The corresponding targeted invocation from `core/Montana-Core` is:

```sh
cargo test --locked --release -p montana-node --test standing_privacy --test window_weight_audit -- --nocapture
```

The candidate's mask repair is not included in the public core revision pinned above. Do not
expect the confidentiality regression to pass on that earlier source, and do not count either
test as complete-window conformance. Replaying these two node tests on the pinned public source
is distinct from the candidate runs; this report does not claim that additional replay completed.
