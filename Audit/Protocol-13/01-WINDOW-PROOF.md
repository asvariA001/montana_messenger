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

This report records source inspection, not a newly completed acceptance test. Existing
arithmetic tests are narrower than the requirement above. Candidate repairs to quorum recovery,
beacon reconciliation, proof branches and persistence have separate, incomplete verification;
they are not evidence that item 1 is closed and are not included in the source revision pinned here.

Items 2, 3, 9 and 13 depend on this closure. Their finality, issuance and authenticated-joining
claims must remain open while this statement is incomplete.
