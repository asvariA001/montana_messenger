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
