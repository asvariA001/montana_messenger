# 1. Complete window statement

**Status: open. No closure or production readiness is claimed.**

## Requirement

Accept a valid window; reject insufficient quorum, forged weight, incorrect state roots and
another machine's proposer ticket. The proof and its verifying algorithm must bind these
properties to authenticated predecessor state and history under the protocol construction.
Checking witness-generation code alone is insufficient: a prover can replace that code.

## Source evidence

At public commit `6303fa5fbbb5ad93e4d7dd3dcc699693fb10e002`,
[`circuit/window.rs`](https://github.com/efir369999/montana_messenger/blob/6303fa5fbbb5ad93e4d7dd3dcc699693fb10e002/core/Montana-Core/crates/mt-proof/src/circuit/window.rs)
defines `public_of`, `description` and `trace_of`. The public input holds incoming accumulators,
an instance, outgoing accumulators and challenges. The constraints enforce six repetitions of
coefficient arithmetic under the commitment modulus, with reduction witnesses in range.
They do not implement the complete Consensus statement. The file itself distinguishes
arithmetic carriage from judging the window's equalities.

[`pulse.rs`](https://github.com/efir369999/montana_messenger/blob/6303fa5fbbb5ad93e4d7dd3dcc699693fb10e002/core/Montana-Core/crates/montana-node/src/pulse.rs)
contains `attempts_the_close`, `closes` and `live_doing`. Their existence and explanatory comments
do not supply the missing relation inside the window proof. Binding every coefficient to public
input proves that arithmetic used those coefficients; it does not authenticate their origin.

## What remains to implement and demonstrate

1. A single, canonical statement binds the predecessor, window, authenticated population,
   admitted weights, contributing identities, transition roots and proposer entitlement.
2. The verifier authenticates every premise from the predecessor; the claimant cannot supply
   its own total, membership root or trust anchor as authority.
3. The construction enforces quorum, unique participation, legal weight, transitions and
   ticket ownership, and carries the required history soundly.
4. Tests exercise the actual verifier with a valid statement and each invalid alternative,
   including altered roots, duplicate participation, invented weights and substituted tickets.
5. The reasoning for accumulation and history is documented with its exact assumptions.
   A successful arithmetic proof does not establish a missing soundness argument.

## Verification status

Existing arithmetic tests are narrower than the requirement above. Candidate repairs to quorum recovery,
beacon reconciliation, proof branches and persistence have separate, incomplete verification;
they are not evidence that item 1 is closed and are not included in the source revision pinned here.

Items 2, 3, 9 and 13 depend on this closure. Their finality, issuance and authenticated-joining
claims must remain open while this statement is incomplete.

## Full-proof counterexamples

Two reproductions ran on a separate Linux x86-64 build host against the candidate source
manifest of 201 files. Both constructed full proofs and obtained acceptance from the generic
proof verifier in 120.15 seconds in total. The [machine-readable result](evidence/window-audit-2026-10-14.json)
records source and log digests. Passing these tests means the open defects were reproduced.
The separate replay on the pinned public snapshot is still running at this report revision;
its result is not included in that candidate-run receipt.

- With every fold challenge zero, a nonzero instance leaves all six accumulators unchanged.
  The arithmetic constraints and proof verifier accept it. The required nonzero, derived
  challenges are not enforced by this path.
- With every challenge one, an instance coefficient of one and predecessor coefficients of
  `q - 1` produce six zero accumulators. The verifier accepts the proof without authenticating
  that predecessor. Rejecting zero alone would not repair this case or authenticate history.

These results concern the existing arithmetic proof API. They do not assert that a complete
Proposal verifier accepted an invalid window: that complete verifier is the missing component.
The [reproducer](reproduce/window_audit.rs) contains synthetic data only. It is an explicit
open-finding test, not a replacement for the required positive and negative acceptance tests.

The complete repair must authenticate the predecessor, bind challenge derivation to the fixed
statement, enforce the full relation and justify history accumulation. A guard in the honest
prover alone cannot establish any of those verifier properties.

## Separate gap in the stated security bound

Consensus's section "The proof a window carries" gives the conditional union bound `k / q^6`
for `k` windows, with `q = 8380417`; Canon states a 128-bit proof security target. Even taking
that bound at face value, `floor(q^6 / 2^128) = 1018`. Thus that bound alone does not establish
the target at 1024 windows, at 20160 windows, or at a billion windows. Also `q^6 < 2^138`, so
`q^-6` is greater than `2^-138`, contrary to the text's strict inequality.

These integer comparisons were checked on the separate build host. They identify a gap in
the claimed guarantee; failure of an upper bound to meet a target does not itself demonstrate
an attack with that probability. The underlying accumulation argument, decomposition binding,
adaptive transcripts and authenticated origin still need a complete soundness analysis.
Changing a repetition count alone cannot close item 1.
