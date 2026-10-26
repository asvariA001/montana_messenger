# 2. Close only a verified canonical Proposal

**Status: met on the branch head `cb4283c`, pending the chairman's verdict in #10.** The issuance
right on the redemption path (`pay.rs:78`) is out of scope here. It belongs to item 3 (#14).

This page used to describe the public baseline `6303fa5`. There, `live_doing` treated
`attempts_the_close` as the close. That is no longer the code. Every line below is read on
`cb4283c` (tree `729a7123`), under `core/Montana-Core/crates/`.

## Where the head, the closed window and the right move

- **The close of a lived window.** `live_doing` calls `take_the_proposal(...)?`
  (`montana-node/src/pulse.rs:1794-1803`) and then `machine.close(...)?` (`pulse.rs:1814-1820`).
  `Machine::close` (`montana-node/src/lib.rs:1063`) keeps the proposals and writes the head and
  the closed window in one record, through `Store::commit_window` (`lib.rs:1116`). The record goes
  through `shows` first (`mt-store/src/lib.rs:418-430`), which refuses a head or a closed window
  whose proposal the store does not hold.
- **`take_the_proposal` returns one of two things** (`pulse.rs:2320-2387`):
  - a proposal standing at the window's points under the identifier this machine recomputed
    (`pulse.rs:2331`), judged whole by `verify_proposal` (`pulse.rs:2411-2421`);
  - its own proof, verified at `pulse.rs:2359`.

  Otherwise, after `patience` askings, it returns `Err("a window no proposal of its decision ever
  stood at")` (`pulse.rs:2380-2384`), and `live_doing` leaves before the close.
- **The count only selects the final beacon.** `carries_the_quorum` (`pulse.rs:2260`) picks the
  beacon. That authorizes the attempt to take a Proposal, not the close.
- **The right.** It is issued at `pulse.rs:1833-1873`, only after `close` has succeeded, and only
  for the window this close made final (`before.id()`, `pulse.rs:1839`). It is computed from the
  decided roll. The window just closed carries `right: None` (`pulse.rs:1882`).
- **The walk of a joining device.** `certificate::walk` (`pulse.rs:2572`) judges each
  certificate and Proposal first. Only then do `finalize` (`pulse.rs:2575`) and `let_go_of`
  (`pulse.rs:2580`) move the head and the closed window, through the same `commit_window` and
  `shows`.

No path in `src` moves the head, the closed window or a right of the pulse without a verified
Proposal.

## The test seam

`pulse::withhold` (`pulse.rs:2270-2307`) holds back the Proposal a machine proved of its own, so a
test can reach "quorum and beacon, but no verified Proposal" on the live path.

- It sits behind the cargo feature `withhold` (`montana-node/Cargo.toml:34-35`). Only the crate's
  own dev-dependency turns it on (`Cargo.toml:41-42`).
- Without the feature, `withholds` is the constant `false` (`pulse.rs:2301-2307`).
- It is reached only after `verify_proposal(&own, ...)` (`pulse.rs:2359-2360`).

It changes no rule.

## Acceptance boxes of #10

| # | Box | Evidence | Verdict |
|---|-----|----------|---------|
| 1 | Gate -1: file and line for every move of the head, the closed window and the right | the section above | holds (by reading) |
| 2 | A positive case closes, the head moves onto the identifier, and the right is issued | `tests/a_device_joins_the_running_chain.rs:89-113`; `tests/two_hosts_live_a_window.rs:109-137` | holds (by run) |
| 3 | Absent, invalid and substituted proofs are refused before any change | `tests/certificate_matrix.rs` (at the door); `tests/a_device_joins_the_running_chain.rs:149-179` (at the walk); `a_substituted_or_unproven_proposal_at_the_points_closes_nothing` in `tests/a_quorum_without_a_verified_proposal_closes_nothing.rs` (on the live path, six substitutes) | holds for the head, the closed window and the right of the pulse; the right on redemption is item 3 |
| 4 | A count at the quorum without a verified Proposal moves nothing, and the test fails on code that closes on the count | `tests/a_quorum_without_a_verified_proposal_closes_nothing.rs`, through `pulse::live` and across a restart | holds (by run, and by mutation M1) |
| 5 | The Proposal, its proof and its identifier persist, survive a restart, are served and are verified by a joining machine | `tests/a_device_joins_the_running_chain.rs:182-204`; `tests/a_window_outlives_the_restart_of_every_holder.rs:79-121` | holds (by run) |
| 6 | Commands, exit codes and commits kept; one network namespace per test binary on the core test host | the runs below | holds |
| 7 | Self-verification, then the chairman's review | #10 | the chairman's verdict |

## Mutations

Both mutants were run on detached commits that are on no branch.

- **M1** (`4c5bb01`): `live_doing` closes on a proposal assembled from the decided cement.
  - The box 4 test: rc 101, 0 passed, 1 failed, 335.75 s.
  - The mutant closed window 2 and issued the right of window 1.
- **M2** (`bb6085c`): `standing_proposal` takes a proposal under the expected identifier without
  `verify_proposal`.
  - The box 3 live test: rc 101, 0 passed, 1 failed, 265.46 s.

## Runs

- **`2556c4b`** (box 6; the same core tree as `cb4283c` except for one test comment).
  - Run `a554-p2lock` on the core test host: `cargo test --locked`.
  - All 45 test binaries of `montana-node` and `mt-store` ran, each under `unshare -n`.
  - 0 binaries failed.
- **`cb4283c`**, the head that merges, on Lauterbourg-3 (snapshots of tree `729a7123`):

| Name | Command | rc | Result |
| --- | --- | --- | --- |
| c1 | `cargo metadata --locked --format-version 1 --no-deps` | 0 |  |
| c2 | `cargo fmt --all --check` | 0 |  |
| c3 | `cargo clippy --locked --workspace --all-targets -- -D warnings` | 0 |  |
| c4 | `cargo test --locked -p mt-store` | 0 | 3 binaries, 25 passed, 0 failed |
| s1 | `cargo test --locked -p montana-node --no-fail-fast --test four_machines_decide_one_name` | running |  |
| s2 | the same, `--test` a_round_over_the_wire, a_window_outlives_the_restart_of_every_holder, certificate_matrix, a_ballot_casts_the_standing_a_machine_lived | running |  |
| s3 | the same, `--test` a_quorum_without_a_verified_proposal_closes_nothing, a_device_joins_the_running_chain, three_machines_live_a_window, a_walk_from_an_anchor | running |  |
| s4 | the same, `--test` two_hosts_live_a_window, a_payment_settles_before_a_window, issuance_audit, value_enters_the_network, a_verdict_reads_no_clock, a_record_is_opened, a_wallet_pays_a_wallet, genesis_stands_under_window_zero_alone | running |  |
| s5 | the same, `--lib --bins` and the other 23 test binaries | running |  |

Each run is a systemd unit with `PrivateNetwork=yes`, `MemoryMax=12G` and `CARGO_NET_OFFLINE=true`. Logs are kept on the host in `/srv/montana/a562-logs/`.

## Not established here

- The issuance right on redemption: `pay.rs:78` takes the count of the living from the operator,
  not from a kept final Proposal. That is item 3 (#14), reproduced by `tests/issuance_audit.rs`.
- Whether a machine's local reading of the cement is the agreed decision. That is item 1 (#4).
- Kani (PR #29) is a separate formal check.
- This is self-verification by the council's sessions, not an independent external audit.
