# 1. Complete window statement

**Status: open. No closure or production readiness is claimed.**

Session 38 adds a [complete internal review of item 1](01-INTERNAL-AUDIT-SESSION-38.md),
covering W01-W15, with a [publicly reconstructible candidate](reproduce/session38/README.md).
The final node test reproduces order-dependent cements under conflicting signed weights.
A separate historical receipt documents secret extraction from an accepted presence proof.
The review keeps both defects open and distinguishes the two earlier component repairs
from the absent complete window relation. No replacement network is released by this audit.

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
The separate replay on the pinned public snapshot also completed: both full-proof
counterexamples reproduced in 3977.47 seconds. All 193 source files matched their manifest
after the run. Its [separate receipt](evidence/window-public-replay-2026-10-14.json) records
the public source revision and source/log digests. The slower run used the published prover,
not the candidate's optimized implementation.

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

## Why a running sum cannot authenticate weight

An additional separate-host reproduction uses the actual standing-commitment implementation.
Four bounded commitments of weight one have the same sum as a bounded opening of weight five.
With zero `r1`, first `r2` coefficient four in each original commitment, and first `r2`
coefficient minus one in the alternative opening, the first coefficient is:

```
4 * (17 * 1 + 4) = 84 = 17 * 5 - 1
```

All other coefficients are zero. At parent total seven, actual weight four fails the current
quorum while claimed weight five passes. This does not break an individual commitment: the
combined original randomness has left its bound. It proves why merely opening the running sum
cannot replace the authenticated tree of fresh commitments required by Canon.

A second example uses `255 + 1`: adding the encoded commitment coefficients does not perform
the carry of the integer sum. The child-sum proof must establish integer arithmetic as well
as the linkage to the correct children. Both synthetic examples reproduced on the separate
host in 0.02 seconds. They are tests of the commitment API, not a claim that a complete window
verifier or a deployed network accepted an invalid quorum.

## Focus of the current repair

Work is focused on item 1. The unpublished candidate adds a nonzero-challenge constraint
inside the AIR and a checked arithmetic API that independently derives challenges from fixed,
canonical input. A complete window verifier must additionally authenticate that input and
establish the weight, root, transition, ticket and history relations. The arithmetic repair
passed the separate-host v12 run: 189 proof-package tests, 72 conformance tests,
whole-workspace Clippy with warnings denied, and the release build. The source manifest
matched all 205 files after the run. The [receipt](evidence/window-arithmetic-repair-2026-10-14.json)
separates each command's results and log digest. These are selected package checks, not a
passing whole-workspace test sweep, and they do not close this item.

## The node verifier also accepts unauthenticated weight

The same v12 run constructed one real presence proof for an admitted machine. The owner
reused that proof and signed alternative standing commitments to zero and `u64::MAX`.
The actual `verify_heavy` function accepted both alternatives under the same admitted-root,
window, key and part-nullifier context. The test completed in 14.36 seconds. Its successful
result reproduces an open authority defect: it does not validate either invented weight.

The presence statement must bind the commitment to the machine's permitted predecessor
standing. A signature over a replacement commitment proves who signed those bytes, not that
their hidden quantity is authorized. The complete relation remains to be implemented.

## Confidentiality of the committed weight

A separate actual-node regression found that the current blind is the next window's public
part-nullifier. Given that later public value and the prior commitment, an observer subtracts
the commitment of zero and reads the three encoded quantities. The baseline regression
recovered `[1234567, 0, 1000]` and failed its confidentiality expectation in 14.80 seconds.
These are synthetic test values.

The subsequent candidate derives the mask privately with the existing HMAC-SHA-256 primitive,
binding the machine secret and the complete immutable quantity tuple. Its actual-node
confidentiality regression passed in 14.44 seconds; all 67 derivation-package tests and
whole-workspace Clippy also passed. The same candidate still accepts unauthorized weights
in the separate open-finding test, which completed in 15.94 seconds.

The conformance run then found one stale audit count: the new vector raised coverage from
248 frozen lines to 249. The subsequent snapshot changes only two audit documents; all code
is identical to the tested mask repair. All 74 conformance checks, the workspace release
build and tier-one checks of five normative documents pass there. All 206 source files
matched their manifest after the run, and the proof-package sources are identical to the
earlier arithmetic-repair run. The before/after
source and log digests are recorded in the [weight-mask receipt](evidence/weight-mask-repair-2026-10-14.json).
The [confidentiality regression](reproduce/standing_privacy.rs) and
[weight-authority reproduction](reproduce/window_weight_audit.rs) contain synthetic inputs only.

This closes the demonstrated disclosure through the next public part in the candidate.
It does not claim full anonymity, resistance after disclosure of the machine secret, or
proof of permitted weight. The latter still requires an authenticated predecessor relation:
`Cohort::branch_of` currently supplies weight one, while its membership branch authenticates
the naming half and does not authenticate a permitted weight. No complete weight transition,
window relation or history verification has been implemented by this repair.
