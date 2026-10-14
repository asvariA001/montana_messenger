# Reproducing the source-review counterexamples

These are internal adversarial tests with synthetic keys and records. They do
not contact a live node, demonstrate a live quorum or constitute an independent
cryptographic review.

`baseline-tests.patch` adds tests only. Eight original files were compared
byte for byte with this repository at public commit `6303fa5`; the patch also
adds one record-tree integration test file. That commit's
`core` tree is `4ce3d6705224c8c99db0db1710a70bbde3d04361`; it is the published
snapshot of source `3848245e308b`. No implementation fix or parameter update is
included in this patch. It uses zero context lines; the commands below explicitly
select that patch format.

Use a separate checkout of the public baseline. Keep the review checkout and
its patch available, then apply the patch from the baseline repository root:

```sh
git worktree add ../montana-before-review 6303fa5
git -C ../montana-before-review apply --unidiff-zero --check /absolute/path/to/baseline-tests.patch
git -C ../montana-before-review apply --unidiff-zero /absolute/path/to/baseline-tests.patch
```

Each command below selects tests that operate in memory. Run the commands
separately: failure is the expected baseline result, so a shell that exits on
failure would stop before the next reproduction. Both workspaces pin Rust in
their own `rust-toolchain.toml`. Compilation needs their locked dependencies;
the fixtures themselves need no network service.

From the baseline's `core/Montana-Core`:

```sh
cargo test --locked --release -p mt-proof --lib a_presence_cannot_graft
cargo test --locked --release -p mt-proof --lib a_part_cannot_choose_extra
cargo test --locked --release -p mt-proof --lib padding_cannot_create_a_second
cargo test --locked --release -p mt-pulse --lib an_empty_population_or_fabricated
cargo test --locked --release -p mt-proof --test record_tree
```

The first two tests rebuild coherent traces: one substitutes another machine's
public membership path, the other changes absorption padding and the published
nullifier. They do not merely corrupt a proof byte. The next command tests the
same padding defect in opening and admission. Each forged trace satisfies the
old AIR and therefore fails the test asserting that it must be rejected. The
pulse command reproduces the empty-population quorum acceptance. The record-tree
command checks three interleaved little-endian keys: the old root disagrees with
an independent recursive partition, and a returned membership path does not
reach that root. Both record-tree assertions fail on the baseline.

From the baseline's `core/Code`:

```sh
cargo test --locked --release -p montana-node --lib session31_envelope_regression
cargo test --locked --release -p mt-lottery --lib quorum_does_not_fall
cargo test --locked --release -p mt-messenger-e2e --lib adversarial_
cargo test --locked --release -p mt-messenger-e2e --lib regression_limits
cargo test --locked --release -p mt-messenger-e2e --test e2e_full regression_limits
```

The envelope tests reproduce duplicate and trailing-byte acceptance. The
lottery test checks the threshold around the old multiplication overflow. The
five `adversarial_` ratchet tests reproduce noncanonical stored-state acceptance,
a skipped-key count above the runtime bound, an exhausted send counter and a
failed KEM step that changes the sending state.

Baseline results recorded so far: both presence attacks, both opening/admission
padding attacks, the empty-population close, both envelope tests, the threshold
overflow and all five ratchet tests fail for their asserted security invariants.
Both `regression_limits` cases also fail on the baseline: the receive counter
overflows, and the receiver refuses a message of the accepted maximum size.
A baseline failure is evidence of the counterexample, not
evidence that a repair has passed. See the current audit ledgers and workflow
for the candidate's separate validation state.
