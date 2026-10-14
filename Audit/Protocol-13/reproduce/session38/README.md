# Reproduce the session 38 candidate

This is an audit fixture, not a network release. It reconstructs the source used
for the session 38 item-1 findings from public commit
`6303fa5fbbb5ad93e4d7dd3dcc699693fb10e002`, the accompanying patch and replacement
README. The repository's
published `core` folder remains the earlier snapshot; the patch includes the
candidate changes made before session 38 as well as its new adversarial test.

The source manifest covers 206 files. The original server snapshot had 208;
two private session reports are deliberately excluded. They are not compilation
inputs. Every included file must match its recorded SHA-256 before and after
execution. This patch is source code, not a proof that any open requirement is met.

## Prepare a new directory

Use a full clone of the public repository and Python 3.9 or later. From its root:

```sh
python3 Audit/Protocol-13/reproduce/session38/prepare.py . /tmp/item1-candidate
```

The destination must not exist. The tool reads the pinned commit, applies the
patch and verifies the manifest. It leaves the clone and any existing directory
untouched. A failed preparation is retained for inspection; choose a fresh
destination when repeating it.

## Run the checks

Use Linux, the Rust toolchain pinned by the reconstructed workspace, and OpenSSL
3.5 or later for the differential primitive checks. If OpenSSL is installed in a
custom prefix, configure `PATH` and its library search path for that installation.
The test fixture retains its temporary stores. Proof tests are CPU- and
memory-intensive; the workspace runs them sequentially.

```sh
cd /tmp/item1-candidate/core/Montana-Core
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
unshare --net sh -c 'ip link set lo up && cargo test --release -p montana-node --test window_cement_audit -- --nocapture'
cargo test --release -p mt-conformance
cargo build --release --workspace
```

The isolated network namespace requires the corresponding Linux privilege.
The cement test starts two actual `Machine` instances, with separate stores,
in one test process. It calls their `Pulse::gather` directly. It does not test
wire delivery, multiple hosts or canonical network finality. No application
service is installed or changed by these commands.

The cement test must report the open finding: reversing the order of two validly
signed, unauthorized weight commitments changes the running cement. Its positive
presence control passes. Wrong membership root, correctly signed key substitution
and correctly signed proof corruption fail at verification. These are presence
controls; they do not establish the absent full ticket or window relation.

The following related commands reproduce earlier, separately recorded findings:

```sh
cargo test --release -p montana-node --test presence_disclosure_audit -- --nocapture
cargo test --release -p montana-node --test window_weight_audit -- --nocapture
cargo test --release -p mt-suite --test window_weight_audit -- --nocapture
cargo test --release -p mt-proof --test window_audit -- --nocapture
```

The session 38 receipt names which commands were actually run again. This list
does not claim a fresh run of every earlier test or a full-workspace test pass.
An `open_finding` pass is confirmation of a defect, not a repaired-verifier pass.

After running, return to the public clone and check the source again:

```sh
python3 Audit/Protocol-13/reproduce/session38/prepare.py . /tmp/item1-candidate --verify-only
```

The [internal report](../../01-INTERNAL-AUDIT-SESSION-38.md) records the full
acceptance matrix and the limits of this evidence.
