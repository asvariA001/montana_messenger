# Internal audit of the complete window proof

Date: 2026-10-14. Session: 38. Reviewer: Codex, acting first as Architect 6.2.8
and then as Critic 5.1.3. This is internal review by the implementing session.

**Verdict: item 1 remains open. The candidate is not a working replacement network.**
The present change adds an adversarial node test; it does not implement a complete
window prover, a complete window verifier, or a replacement zero-knowledge scheme.
The author authorized an incompatible redesign and a new network. That authorization
is not implementation evidence, a new Genesis, or a release approval certificate.

## Scope and source

The target is the new native network in `Montana-Core`. The preceding `Code`
generation and the old running cohort are test work, not the target architecture.
The base revision is `efcc7fca717a57c4b3d42cdd109936d0e9f2d873`, descended from
`3392f6628613c7ce5fec1775a3592c9035c8e4ec`. Earlier changes remain intact. Session 38 is recorded at private candidate commit
`eae3c2bbb4936f8161bbca89bf1841975e6b3278`; the public reproduction fixture supplies
the reviewed source without requiring access to that private history.

The source paths and lines below refer to that base; the new test is identified
separately. Read together with `Montana-Core/WINDOW-STATEMENT.md` in the reconstructed fixture,
`Montana-Core/AUDIT.md` in that fixture, and the recorded session 37 [receipt](evidence/presence-disclosure-session37-2026-10-14.json).
Earlier receipts describe earlier executions. They are not fresh executions by
this session and do not certify every test in the workspace.

## Verified session 38 evidence

The [machine-readable receipt](evidence/window-internal-audit-session38-2026-10-14.json) retains failed attempts and the
final run. The final snapshot is `session38-window-v4`, archive SHA-256
`0a4a31fd973e328c43a733016e5c32553df784420a3f506954d9ed1b73daa38d`.
All 208 supplied source files matched their manifest before and after execution
on Linux x86-64, Rust 1.92.0 and OpenSSL 3.5.4. The final test was run in a separate
network namespace with loopback enabled; existing services were not changed.

| Check | Observed result |
|---|---|
| Workspace formatting | pass |
| Workspace Clippy, all targets, warnings denied | pass |
| Final cement adversarial test | open defect reproduced; 45.52 seconds excluding compilation |
| Conformance package | 74 tests pass |
| Workspace release build | pass |

These checks are not a whole-workspace test sweep. No complete window proof,
canonical finality certificate, new Genesis or deployed replacement network was
produced. The final test is the only implementation-file change made in session
38; the earlier candidate repairs have their own revisions and receipts.

The accompanying public audit fixture reconstructs 206 identical source files
from the published commit and a patch. Two private session reports are excluded
from publication; neither is a compilation input. The new audit and receipt were
written after the test snapshot and do not change its compilation inputs.

The [reproduction package](reproduce/session38/README.md) supplies the patch,
replacement document, manifest and preparation tool. All code references below
are relative to its reconstructed `Montana-Core/crates` directory, unless a
different prefix is given. They do not refer to the older `core` folder as if
it already contained the candidate repairs.

## Public reconstruction replay

The public base commit was fetched on the separate verification host. The
preparation tool reconstructed the candidate in a new directory and matched
all 206 published source hashes. The cement reproduction and all 74 conformance
checks then passed; all 206 hashes matched afterwards. The
[separate replay receipt](evidence/window-public-reconstruction-session38-2026-10-14.json)
and normalized logs record this execution. Passing the reproduction confirms W15;
it does not close it. The publication scanner found no prohibited names, live IPv4
addresses, personal email addresses, Cyrillic or secret-pattern matches in the
prepared audit package. This is a package check, not an anonymity audit of the
entire repository or its history.

## Acceptance ledger

The statuses describe the complete relation, not the existence of a helper with
a similar name. `partial` means a relevant component exists but does not establish
the obligation; `absent` means the integrated required relation is absent in the
reviewed native acceptance path. No required row is left unmapped.

| Obligation | Status | Implementation evidence | Required closure |
|---|---|---|---|
| Authenticated Genesis, suite, parent, height and input roots | partial | `mt-store` binds a directory to Genesis; `window::verify_step` takes arithmetic bytes, not an authenticated predecessor | A verifier derives context from a checked parent and refuses candidate-selected trust anchors |
| Unique participants and permitted hidden weights | partial | Presence proves membership and part derivation; `presence::public_of` omits weight and its origin | Prove that each contribution opens the permitted record in the same authenticated parent state |
| Fresh commitment to the exact integer sum | absent | `mt-pulse::fold` adds ring commitments; `FoldNode` has a layout | Prove child authentication, integer carries, fresh openings and bounded randomness at every node |
| Parent quorum and a separate living count | partial | `close::quorum_reached` computes a bounded integer inequality | Bind numerator, denominator and living count to the preceding obligations inside the accepted relation |
| Canonical operations and output roots | absent | Proposal serializes six roots; the window AIR binds coefficient arithmetic | Prove every permitted operation and recompute every output root from authenticated input roots |
| Proposer ticket ownership and historical eligibility | absent | `runs_the_chain` computes a local ticket; Proposal carries bytes named `ticket_proof` | Prove the admitted secret, the proposal signing key, historical aggregate and threshold in one relation |
| History from Genesis including every reset and terminal check | absent | `verify_step` checks one arithmetic step; `discharge` is a bit-decomposition gadget | Authenticate predecessors and every reset, then verify the terminal claim under a complete soundness argument |
| Hidden witnesses in the entire proof transcript | absent | An accepted presence proof discloses its constant secret lane | A hiding construction covers trace openings, extension-field evaluations and FRI material, with degree and entropy accounting |

The actual complete-window verifier has no accepting positive-case artifact in
this candidate. Rejecting every window would not close any of these rows.

## Findings and closure conditions

### W01 The registered window description proves arithmetic

**Open, blocks release.** `mt-proof/src/circuit/window.rs:153` builds the
description entirely from fold arithmetic, an inverse constraint and public
coefficient boundaries. `verify_step` at line 96 validates input shape,
canonical coefficients and transcript-derived challenges. It has no authenticated
state, operations, membership, quorum or ticket arguments.

An accepting arithmetic proof can therefore be genuine while providing no
evidence for a proposed window. The repair belongs in the full proving relation
and its protocol entry point. Renaming the helper or attaching a signature does
not supply the missing predicates. Closure requires the complete acceptance
matrix below through the real node entry point.

### W02 An accepted transcript discloses a permanent secret half

**Open, blocks confidentiality.** `presence.rs:297` fills four lane columns
with the spending half. `scheme.rs:445` interpolates the columns without a
random mask. A constant polynomial evaluates to its constant at every query.

The session 37 observer receives serialized proof bytes only, after the actual
`verify_heavy` accepted them, and predicts part and credit nullifiers at five
heights. Its recorded test duration is 15.63 seconds. The server's existing
raw log was read again in session 38; the experiment was not rerun merely to
rename its owner. It does not recover a signing key or demonstrate theft.

Closure requires a complete witness-hiding construction, including out-of-domain
and FRI disclosures. Moving the lane, masking a struct field, or making one
extraction test fail does not establish zero knowledge. The relevant caution
about base-field masks and quotient disclosure is described by
[Haboeck and Kindi](https://eprint.iacr.org/2024/1037); that paper is a design
source, not an implemented repair or a theorem for this candidate.

### W03 Signed weight is not authorized weight

**Open, blocks quorum.** `montana-node/src/attest.rs:195` verifies a signature
and presence. Its public vector at line 229 has no `weight_commit`.
`presence.rs:323` encodes admitted root, key digest, part and window only.
`Cohort::branch_of` at `pulse.rs:97` authenticates a naming half and returns
the literal standing one at line 119.

The admitted owner reuses one presence proof, commits zero or `u64::MAX`, and
signs the replacement normally. Both pass the actual verifier. Session 37's
recorded reproduction took 16.65 seconds. The source and existing raw log were
checked again in this review. Closure requires an authenticated private weight
record and a proof tying the fresh commitment to that record. Adding a hash of
the chosen commitment to the transcript alone still lets its owner prove a
fresh unauthorized value.

### W04 A short opening of the running sum is not the integer sum

**Open, blocks sum soundness.** `mt-pulse/src/fold.rs:57` adds commitments in
the ring. The actual commitment admits the aggregate counterexample
`4 * (17 * 1 + 4) = 17 * 5 - 1`: four bounded weight-one inputs sum to the
bytes of a bounded weight-five opening. With parent total seven this changes
the quorum outcome. The individual commitment is not broken: the combined
honest randomness has exceeded its bound.

The separate `255 + 1` counterexample shows why adding encoded coefficients
does not implement integer carries. Both executable examples are in
`mt-suite/tests/window_weight_audit.rs`. Closure requires the fresh tree of
authenticated child-sum proofs described by Canon, including carry bounds and
odd-child rules. A new root with unchecked children does not close this finding.
The protocol must also specify how distributed parties construct those proofs.
A collector holding only public child commitments and proofs does not thereby
obtain their private openings. Collecting every participant's permanent secret
would violate the intended ownership and confidentiality requirements.

### W05 The quorum helper does not authenticate its numbers

**Open, blocks quorum.** `Pulse::closes` at `pulse.rs:484` receives the
cemented and total standings from its caller. `mt-pulse/src/close.rs` correctly
rejects zero totals, oversized subsets and multiplication overflow; its own
comment states that it is an arithmetic predicate. That predicate does not
open a commitment or authenticate its denominator.

Closure requires the fixed parent total and the exact proven participant sum
to feed the inequality inside the accepted statement. An unrelated count of
parts, even if honestly collected, cannot substitute for weighted quorum.

### W06 Output roots have no complete window transition proof

**Open, blocks state validity.** `mt-state/src/layout.rs:455` defines the
Proposal roots. `window.rs:153` never reads those fields or a canonical
operation set. Local operation verification and the presence of root fields
do not prove the entire transition represented by a proposed window.

Closure requires one ordered transition from authenticated input roots, with
all affected roots recomputed and public outputs compared. At least one honest
nonempty operation set must pass; wrong input root, wrong output root, omission,
insertion and reordering must fail where they change canonical state.

### W07 A local draw is not proof of the proposing key's right

**Open, blocks proposer entitlement.** `Pulse::runs_the_chain` at
`pulse.rs:197` computes a ticket from a local secret and aggregate. The Proposal
layout carries `runner_pubkey` and `ticket_proof`, but these fields do not
create a circuit that binds their common origin, membership and historical
threshold. The native circuit registry and the full window description were
read; they supply no such complete ticket relation.

Closure requires a proof that the permitted runner secret authorizes the
signing key and clears the threshold from the authenticated historical state.
Replaying another runner's eligibility must fail even with a valid attacker
signature. A test that only changes a signature is not that counterexample.

### W08 Arithmetic input does not authenticate predecessor history

**Open, blocks history.** `prove_step` and `verify_step` accept the caller's
incoming accumulators. The transcript fixes those bytes before drawing its
challenges, but cannot certify where the bytes came from. The interface does
not receive Genesis, a checked predecessor statement or a proof chain.

Closure requires a verifier-authenticated base and a justified recursive or
accumulated relation for every step. Replacing the incoming accumulator,
Genesis, parent, height or suite must fail without relying on the honest
producer's checks.

### W09 Decomposition does not authenticate resets or discharge

**Open, blocks history.** `mt-proof/src/circuit/discharge.rs:108` constrains
bits and reconstruction. It does not verify that a reset recommitted the
previous claim, that all resets are present, or that the final claim is rooted
in the network's Genesis. The window description does not compose those duties.

Closure requires the explicit relation between old and new commitments at
every reset and a terminal verifier for the authenticated accumulated claim.
Changed resets, omitted resets, truncated history and a forged terminal
opening must be rejected independently.

### W10 The lifetime security argument is incomplete

**Open, blocks a security claim.** Consensus states a conditional bound
`k / (q - 1)^6` and explicitly leaves its premises unproved. Six different
transcript draws alone do not establish independence under adaptive attempts,
authentication of history, or binding across recommitments. The previously
claimed comparison with `2^-138` had the wrong direction.

Closure requires an explicit theorem with the actual challenge space,
history/adversary budget, commitment assumptions, reductions and the target
of Canon. Increasing the repetition count does not establish missing premises.
[LatticeFold](https://eprint.iacr.org/2024/257) supplies its own low-norm
extraction construction; naming it does not import that theorem into this code.

### W11 Zero and claimant-selected challenges were repaired locally

**Closed at the arithmetic component, not at item 1.** Revision `3392f66`
adds the inverse constraint and checked APIs which recompute nonzero challenges
from canonical inputs. Earlier receipt evidence records 189 proof-package
tests, 72 conformance tests, Clippy and release. `mt-proof/tests/window_audit.rs`
still distinguishes the generic AIR interface from the checked step.

The required closure of W08 and W09 remains separate. A valid step over an
invented predecessor is not a valid history.

### W12 The future-public-part weight mask was repaired locally

**Closed for that extraction path, not for overall confidentiality.**
Revision `3392f66` replaces the next window's public part as a blind with an
HMAC-derived private blind over the machine secret and committed tuple.
The existing receipt records the failing baseline and passing actual-node
regression. W02 is a different channel and remains open. Neither repair proves
the origin of the weight in W03.

### W13 The normative descriptions disagree on required relations

**Open, blocks a canonical implementation target.** Canon's Confirmation
invariant requires authorized standing, whereas Consensus's presence public
vector excludes the weight commitment. Canon's fresh fold node verifies
children and recommits their integer sum; parts of Consensus describe simple
outside addition as sufficient. Consensus alternately excludes the ticket
from the window proof and requires it inside, and alternately says history
verifies prior proofs and inherits nonlinear predicates by attestation.

The relevant passages are Canon's Confirmation and fold-node invariants and
Consensus's sections on presence and the proof a window carries. These are
different requirements, not equivalent wording. The authorized redesign must
choose one sound complete relation and synchronize its normative bytes,
circuits, identifiers, parameters and new Genesis. This audit does not silently
rewrite the specification to match the incomplete implementation.

### W14 The complete acceptance matrix has not run

**Open, blocks closure.** No canonical valid complete-window proof was
produced in these receipts. Arithmetic proofs, presence proofs, signatures,
matching roots and successful builds establish their own narrower results.
There is no basis for reporting that the complete verifier rejects a foreign
ticket or accepts an honest weighted window.

Closure requires every row of the following matrix against the same pinned
complete protocol entry point. An honest-prover refusal is insufficient.

### W15 Conflicting signed weights reach different running cements

**Open, reproduced on the final session 38 test source.**
`montana-node/tests/window_cement_audit.rs` uses the real `Machine`, `Pulse`,
presence prover, signatures and `gather` path. Two machines have separate stores
in one test process; their receiving pulses use the same identity, admitted root
and parent. One receives zero then maximum weight; the other
receives maximum then zero, for the same part and same presence proof.
`Cement::add` keeps the first contribution and ignores subsequent ones.

The test confirms that both first contributions enter and the resulting
beacon cement bytes differ. It also checks that repeated participation does
not increase the count and that the quorum helper can answer for caller values
unrelated to that cement. Positive presence and wrong-root controls accompany
it. The changed-key control is correctly re-signed by the substituted key's
owner, and the changed-proof control is correctly re-signed by the original
owner. Both are refused specifically at presence-proof verification. Neither
is a test of the missing complete runner-ticket relation.

The test passed in 45.52 seconds excluding compilation. It calls `gather`
directly, not over the wire, inside an isolated network namespace. This is a
demonstrated path from W03 to disagreement about the running cement,
not a claim that a complete finality verifier accepted conflicting final
Proposals. Such a verifier remains absent. Closing W03 with a canonical weight
relation is necessary; rejecting a second conflicting arrival alone leaves
the two first-arrival choices different.

An earlier draft used two pulses of one machine and unsigned key/proof
corruption controls. Critic review caught both limitations. Its first result
is preserved in the receipt and is not presented as the final experiment.

## Required complete verifier matrix

| Case | Required outcome | Complete relation evidence |
|---|---|---|
| Valid distributed unequal weights, valid transition and owned ticket | accept | absent |
| Weight strictly below fixed parent quorum | reject | absent |
| Zero, maximum or unauthorized substituted member weight | reject | absent; component presently accepts substitutions |
| Duplicate participant with a second valid signature | reject or count exactly once under the canonical rule | partial; uniqueness alone does not authenticate weight |
| False short opening of the summed commitments | reject | absent |
| Integer carry, overflow and fresh-node substitution | reject invalid case; accept valid carries | absent |
| Wrong input root or invented parent total | reject | absent |
| Wrong output root or altered canonical operations | reject | absent |
| Another participant's qualifying ticket under attacker's key | reject | absent |
| Wrong historical aggregate or threshold | reject | absent |
| Fabricated parent, different Genesis, height or suite | reject | absent |
| Modified, skipped or reordered resets | reject | absent |
| Invalid terminal discharge | reject | absent |
| Observer given only public transcript bytes | cannot recover protected witness | failed for current presence transcript |

## Critic review

From the cryptographic perspective, W02, W03, W04 and W08-W10 are independent
failures of required premises. No one-row fix, extra signature, higher
repetition count or documentation change closes their composition.

From the distributed-systems perspective, an authenticated parent must supply
one weight context to every verifier. W15 tests the current failure of that
property at the running cement. The operation/Proposal persistence transition
is the downstream responsibility of items 2 and 8; no claim of completing them
is made here.

From the audit perspective, source hashes bind experiments to bytes, not to
the truth of an unimplemented theorem. Successful reproduction of an attack
is evidence of an open defect. This session's review is not independent
external assurance, and its timechain attribution cannot substitute for it.

## Work required before a new network can be called ready

1. Specify and implement a witness-hiding proof backend with its supported
   relation, disclosure budget, degree bound and soundness analysis.
2. Define authenticated private weight state, permitted updates and how
   distributed participants supply witnesses for fresh sums without publishing
   their hidden values or entrusting their long-term secrets to a collector.
3. Implement the complete window and ticket relations and their native verifier.
4. Replace the incomplete history argument with a proved construction and
   implement its base, step, reset and terminal algorithms.
5. Generate the new identifiers and Genesis only from the completed artifacts;
   execute the complete acceptance matrix, then the separate network scenarios.

The reader should not treat this list as an implemented design. This revision
does not ship a new network. Item 1 can be declared closed only after its
implementation and evidence replace every open row above.

## Attribution in the local timechain

The [attribution record](evidence/session38-attribution.json) binds this report
to its SHA-256 and records the author label `Session 38 Codex` in the local
hash chain. The preceding record hash is retained; private chain contents are
not published. This is hash-linked attribution, not a public-key digital
signature, an independent timestamp service or an external audit opinion.
