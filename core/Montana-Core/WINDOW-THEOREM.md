# The closure of a window: definitions, theorems and proofs

This note states, as theorems with proofs, what the verifier of a window's closure establishes and
under which assumptions. It is the paper half of item 1 of the audit list (issue #4). Its relation
is the one of [WINDOW-RELATION.md](WINDOW-RELATION.md), section 3; its acceptance matrix is that
note's section 4. The code it is a statement about is `crates/montana-node/src/certificate.rs`
(`verify_certificate`, `decided_of`, `judge`, `walk`), `crates/montana-node/src/agreement.rs`
(`Book`), `crates/montana-node/src/attest.rs` (`verify_heavy`, `verify_pledge`) and
`crates/mt-pulse/src/close.rs`. Line numbers refer to the tree of commit `aabc3d5`.

The standard is the author's: a complete mathematical closure, a formal proof on paper, and code
held stricter than the paper. Section 8 states, clause by clause, where the code meets the paper,
where it is stricter, and where it is weaker; section 9 states which statements are checked by
machine and by which tool; section 10 lists what remains to be discharged.

## 1. Objects

Bytes are compared as unsigned big-endian integers of their full width. `H` is SHA-256 under the
domains of `mt-codec::domain`; `P` is the Poseidon digest family of `mt-proof`. `card(S)` is the
number of elements of a finite set `S`.

- A **part** `p` is a 32-byte part-nullifier; a **name** `n` is a 32-byte proposal identifier.
- An **electorate** `E = (root, T, census, committee)`: `root` a digest of the tree of admitted
  machines, `T = admitted_count`, and `K = committee.expected` the expected votes
  (certificate.rs:28-36).
- A **heavy attestation** `a = (W, att, p, k, pi, sigma)`: window, the two identifiers it attests,
  part, one-time answering key, presence proof, signature (attest.rs:102-120).
- A **round** `r = (b, H_r, B_r)`: a beacon, the heavy attestations that answered it, and the
  ballots standing beside them (certificate.rs:192-196).
- A **pledge** `g = (W, attempt, n, since, p, k, sigma)`; it is *closing* when it stands on its own
  attempt and *open* otherwise (agreement.rs:20-26, 127-131).
- The **quorum rule** `Q(c, t)`: `t ≠ 0`, `c ≤ t`, and `100 c ≥ 67 t` (close.rs:20-31; the ratio is
  `Entry::Ratio("confirmation_quorum", 67, 100)`, mt-genesis/src/lib.rs:99). The **committee rule**
  `V(v, K)`: `100 v ≥ 67 K` (close.rs:36-46). The rule of the agreement is
  `Q*(c, t, v, K) = Q(c, t) and V(v, K)` (close.rs:50-57, agreement.rs:38-50).

## 2. The predicate of a certificate

Fix a window `W`, an electorate `E`, a predecessor `P` (a proposal of window `W - 1`, or Genesis), an
aggregate `A` (the cement aggregate carried by the proposal of `W - 2`), a count of chains `c`, rounds
`R` and pledges `G`. Let `theta = round_threshold(T, c)`.

An attestation `a` of round `r` is **valid in r** when all of the following hold:

1. `a.W = W` and `r.b.window = W`;
2. `a.att` is two identifiers strictly ascending, one of which is `id(r.b)`; the other is its
   **pledged name** `name(a)` (certificate.rs:359-366);
3. `eligible(a.p, A, r.b.chain, r.b.round, theta)` (round.rs:60-69);
4. `a.sigma` verifies under `a.k` over the signed scope of `a`, under the one suite
   (attest.rs:289-314);
5. `a.pi` verifies under the description of the presence with public input
   `(E.root, a.p, P(key_digest(a.k)), W)` (attest.rs:228-248).

Define the **cement** `C` as the set of parts of the heavy attestations of all rounds of `R`; the
**key** `kappa(p)` as the key of the attestations of part `p`; and the **votes** `v(p)`, for `p` in
`C`, as the largest count among the ballots of `p` that verify against `(W, E, A)`, and `0` where
none does (certificate.rs:307-321).

**Definition (Pi_cert).** `Pi_cert(W, E, P, A, c, R, G)` holds, with closure `L`, exactly when:

- (C1) `P` is Genesis, or `P.window + 1 = W` without overflow;
- (C2) every attestation of every round is valid in its round;
- (C3) any two attestations of one part carry one key;
- (C4) `Q(card(C), T)`;
- (C5) the sum of `v(p)` over `C` is below `2^64` and satisfies `V(sum, K)`;
- (C6) the book of the window decides `id(P)`: its heavy attestations counted as open pledges of
  attempt zero on their pledged names, and the pledges of `G` whose part is in `C` and whose
  signature verifies under `kappa(part)` (agreement.rs:189-210);
- (C7) the aggregate of `C` under `W` exists;

and `L = (W, aggregate(C, W), card(C), C ascending, v)`.

## 3. Assumptions

- **(A1) Collision resistance of SHA-256** (FIPS 180-4) under every domain in which the
  identifiers, the aggregate and the place are taken.
- **(A2) Strong unforgeability of ML-DSA** (FIPS 204) for the answering keys and the runner keys:
  no adversary produces a valid signature under a key it did not generate, on a scope that key did
  not sign; `eps_sig` bounds its advantage over the keys of a walk.
- **(A3) Knowledge soundness of the proof scheme** for the descriptions of the presence, the ticket
  and the ballot: from any adversary that outputs an accepting proof for a public input, an
  extractor obtains a witness satisfying the description, except with probability `eps_K` per
  proof. The value of `eps_K` is a function of the parameters of the scheme; obligation O-1 of
  section 10 is its derivation.
- **(A4) Collision resistance of the Poseidon family** of `mt-proof`, for the tree of admitted
  machines and the part-nullifier.
- **(A5) The description of the presence** enforces that the leaf of the naming half of a secret
  `s` stands in the tree of `root`, that `p = part(s, W)`, and that the key digest is a public
  input bound to the proof (obligation O-2).
- **(A6) The admitted count is the count of leaves.** For every final proposal, `admitted_count`
  equals the number of distinct leaves of `admitted_root`: a state invariant carried by the roots
  of state, which every member of the deciding quorum recomputes (WINDOW-RELATION.md section 3,
  "Proposal"; obligation O-4).
- **(A7) The fault bound.** In every electorate the adversary holds the secrets of at most `f`
  admitted machines, with `100 f` below `34 T`.
- **(A8) Honest conduct.** A machine whose secret the adversary does not hold signs no contradicting
  pair of pledges in the sense of agreement.rs:70-89, answers under one key per window
  (attest.rs:49-53), and pledges a name only after recomputing the roots it carries.

## 4. Lemmas

**Lemma 1 (order).** `verify_certificate` returns `Ok` iff (C2) and (C3) hold together with the
remaining clauses, whatever the order of the rounds and of the attestations within them.

*Proof.* The key map is extended only after an attestation verifies (certificate.rs:289-298), and
the second-key refusal at :282-288 reads only that map. If (C2) holds, the map at any point holds
the key of every earlier attestation of the part, so the refusal fires iff (C3) fails. If (C2)
fails, the first invalid attestation is refused at :267-281 or :289, or a second key is refused
before it is reached; in both cases the result is `Err`. The cement is a set; the ballots are read
after every part stands (:308-321) and the largest count is kept; the book keeps the first
appearance per kind, attempt and part. The one dependence on order is the pledged name of a part
in attempt zero, which is that of its first heavy attestation: under (C3) and (A8) an honest part
pledges one name, and the case of a faulty part is finding F-431-4 of section 8. ∎

**Lemma 2 (exactness).** `verify_certificate(W, E, P, A, c, R, G)` returns `Ok(L)` iff
`Pi_cert(W, E, P, A, c, R, G)` holds with closure `L`.

*Proof.* Clause by clause against the code: (C1) :251-255; (C2) :266-296 with attest.rs:205-249;
(C3) Lemma 1; (C4) :322 with close.rs:87-90 and 12-31, the refusal of `t = 0` and of `c` above `t`
standing at close.rs:21; (C5) :323 with :182-188; (C6) :327-344, a pledge whose part is outside `C`
or which fails `verify_pledge` adding nothing; (C7) :345-347. Every multiplication is taken in
`u128` over operands below `2^64` and factors of at most 100, so none overflows; the checked forms
refuse rather than wrap. ∎

**Lemma 3 (one part, one machine).** Under (A3)-(A6), except with probability `N eps_K` over `N`
verified presence proofs, distinct parts of verified attestations against one root and one window
come from secrets with distinct leaves. Hence `card(C) ≤ T`, and at most `f` parts of `C` come from
secrets the adversary holds.

*Proof.* Extract `s` from each proof (A3): by (A5), `leaf(s)` stands in the tree of `root` and
`p = part(s, W)`. The description reads the two halves of the secret as one absorption squeezed
twice (presence.rs:15-20, 103-108): the half that spends is taken at the seam of the first squeeze
and wired into the absorption of the part-nullifier (presence.rs:152-162; nullifier.rs:112-125),
and the half that names, of the second squeeze, is what the leaf is taken over; the window is a
boundary the verifier supplies (presence.rs:134-140). Two parts `p ≠ p'` of one window therefore
have `s ≠ s'`, and if they stood under one leaf the two secrets would give one half that names: a
collision of the map from a secret to its naming half, which (A4) excludes. The map from parts to
leaves is injective, so `card(C) ≤ T` by (A6), and the parts of the adversary's secrets number at
most `f`. ∎

**Lemma 4 (quorum intersection).** For finite sets `S1, S2` within a universe of `T` elements with
`Q(card(S1), T)` and `Q(card(S2), T)`, `100 card(S1 ∩ S2) ≥ 34 T`.

*Proof.* `card(S1 ∩ S2) ≥ card(S1) + card(S2) - T`. Multiply by 100 and use `100 card(Si) ≥ 67 T`:
`100 card(S1 ∩ S2) ≥ 134 T - 100 T = 34 T`. ∎

**Corollary.** Under (A7), two quorums of one electorate share a part whose secret the adversary
does not hold.

## 5. Agreement

**Theorem 1 (one decision per window).** Under (A1)-(A8), except with the probabilities of Lemma 3
and `eps_sig`, no two books of one window over one electorate decide two different names.

*Proof.* Let book `B1` decide `X` at attempt `a` and book `B2` decide `Y ≠ X` at attempt `b`; the two
books may hold different pledges. Every pledge either counts stands under a key proven by a heavy
attestation of its part (certificate.rs:327-334), so under (A2) a counted pledge of an honest part
is one that honest machine signed. All parts lie in one universe of at most `T` leaves (Lemma 3).

Case `a = b`. The closing pledges of attempt `a` on `X` in `B1` and on `Y` in `B2` each reach the
quorum; by the corollary an honest part signed two closing pledges of one attempt on two names, a
contradicting pair (agreement.rs:70-73), which (A8) excludes.

Case `a` before `b` (the other is symmetric). `B2` counts a closing pledge on `Y` at `b` only where
`(b, Y)` is an open quorum of `B2` (agreement.rs:198-201). Claim: for every attempt `e` not before
`a`, no book holds an open quorum `(e, Z)` with `Z ≠ X`. Induct on `e`. The counted open pledges of
such a quorum stand on none or on an open quorum `(e', Z)` with `e'` before `e`
(agreement.rs:175-178). By the corollary an honest part `h` closed `X` at `a` in `B1` and opened `Z`
at `e` in the open quorum. If `e = a`: a closing pledge on `X` counts at `a` only where `(a, X)` is
an open quorum of `B1`, so an honest part opened two names in one attempt, which (A8) excludes. If
`e` is after `a` and the open pledge of `h` stands on none or on an attempt before `a`, the pair is
the third contradiction (agreement.rs:80-89), which (A8) excludes. Otherwise it stands on
`(e', Z)` with `e'` not before `a` and before `e`: an open quorum on `Z ≠ X`, excluded by the
hypothesis of induction. Taking `e = b` and `Z = Y` gives the contradiction. ∎

## 6. Completeness and soundness of the certificate

**Theorem 2 (completeness).** If every attestation of `R` was produced by an honest machine of `E`
in a round it was drawn for, every pledge of `G` by honest machines, the honest parts reach
`Q(card, T)`, their ballots cast the votes their standing drew with `V(sum, K)`, and the book of
their pledges decides `id(P)`, then `verify_certificate` accepts.

*Proof.* Each clause of `Pi_cert` follows from the honest producer (attest.rs:38-120: the key from
the seed of the window, the proof from the secret, the attested pair ascending) and the
completeness of ML-DSA and of the proof scheme; (C3) from one key per window; then Lemma 2. ∎

**Theorem 3 (soundness).** Under (A1)-(A8), except with probability `N eps_K + eps_sig`, if
`verify_certificate` accepts then (i) a set of parts of distinct admitted machines reaching
`Q(card, T)`, at most `f` of them the adversary's, attested window `W` against `E.root`; (ii) the
agreement of a quorum decided `id(P)`, and by Theorem 1 no certificate of `W` decides another name;
(iii) the votes counted for each part are at most the votes its standing drew in `W`.

*Proof.* (i) Lemma 2, clauses (C2) and (C4), and Lemma 3. (ii) Clause (C6) and Theorem 1. (iii)
Clause (C5) and the knowledge soundness of the ballot (A3), the ballot being verified against the
census of `E`. ∎

**The matrix of WINDOW-RELATION.md section 4, row by row.**

- A window of distinct admitted machines reaching the quorum, honest roots, owned ticket — accepted:
  Theorem 2.
- Count strictly below the quorum — refused: (C4), close.rs:21-30.
- A presence proof against another electorate root — refused: (C2.5) and (A3).
- A repeated part-nullifier — counted once: the cement is a set; (C3) for its key.
- An attestation signed by a key the presence proof does not name — refused: (C2.5), the key digest
  being a public input.
- A proposal whose roots differ from the recomputed ones — refused: `judge` with roots,
  certificate.rs:578-604.
- A proposal signed by a key another machine's ticket names — refused: certificate.rs:636-652, the
  runner digest being a public input.
- A predecessor that is not the final proposal of `W - 1` — refused: certificate.rs:555-559 and (C1).
- An observer given the serialized proofs alone — recovers no spending half: the hiding argument of
  WINDOW-RELATION.md section 5.
- A final beacon below the quorum, naming more parts than its certificate verified, or not linked to
  round zero and the predecessor — refused: `decided_of`, certificate.rs:410-460; see F-431-2 and
  F-431-3 for the two clauses of section 3 it does not check.
- Two runners, or two branches of one round, of one decision — one identifier: `decided_identifier`,
  certificate.rs:497-521.
- The running node's own close — as above: one door, `verify_certificate`.
- A device walking from an anchor — the head is the newest proposal whose certificate verified:
  Theorem 4.

## 7. The walk

**Theorem 4 (the head).** Let the anchor `(X0, X1)` be the final proposals of windows `w` and
`w + 1`. Under (A1)-(A8), if `walk` returns `(Y, Z)` then `Z` is the unique name decided by the
certificates of window `Z.window + 1`, every proposal the walk passed after `X1` is the unique decided
proposal of its window, and `Z` is the newest proposal whose certificate the walk verified.

*Proof.* Induct on the steps, with the invariant: `later` is a decided proposal, `earlier` is its
predecessor, and the electorate and the aggregate the step reads are `Electorate::of(earlier)` and
`earlier.cement_aggregate`, those of the proposal of `W - 2` for the certificate of window `W`
(certificate.rs:699-715). The step verifies the certificate of `later`, or carries the one verified
in the step before (:704-705); by Theorem 3 its agreement decided `id(later)`, uniquely. A
candidate is taken only when the certificate of the step after verifies with it as predecessor
(:773-786), so by Theorem 3 it is the unique decided name of its window. Where no step after
exists, the candidate is taken after `head` was fixed at :716-719 and never becomes the head. ∎

The roots of a proposal a device behind takes are those the quorum that decided it recomputed:
(A8) makes an honest part pledge a name only after recomputing the roots, and by Lemma 4 and (A7)
every deciding quorum holds an honest part.

## 8. Code against paper

**Stricter than the paper.** `judge` refuses a proposal under rules above `implemented_major`
(:560-564) or rolling the version back (:565-569), any suite but the one, and any key or signature
of another width; `links_to` refuses a round past `u32::MAX` (round.rs:107-109); every count past
`u64` is refused and never wrapped.

**Weaker than the paper — findings.**

- **F-431-2.** `decided_of` takes the final beacon from any round of the certificate
  (certificate.rs:417-423), including a round whose beacon no verified attestation answered.
  WINDOW-RELATION.md section 3, "Close", names the final beacon as one "which a verified
  attestation answered". Required: the final beacon is answered by a verified heavy attestation
  of the certificate.
- **F-431-3.** `decided_of` bounds the final beacon's count by the certificate's
  (certificate.rs:449-453) and does not recompute its `cement_aggregate` from verified parts.
  WINDOW-RELATION.md section 3, "Cement": "Every verifier recomputes both from the attestations it
  holds and refuses a beacon whose state is not that pair." Required: the pair of the final beacon
  is the aggregate and the count of a set of verified parts of the certificate. Theorem 4 does not
  rest on F-431-2 or F-431-3: the head is fixed by the agreement of the window after, which decides
  an identifier carrying the final beacon and the pair. A proposal taken on the last step, which is
  not the head, does rest on them.
- **F-431-4.** In attempt zero the pledged name of a faulty part is that of its first heavy
  attestation in the order handed in (agreement.rs:112-118). Theorem 1 does not rest on it, the
  part being the adversary's either way; but two verifiers given one certificate in two orders can
  count that part under two names. Required: a part pledging two names in attempt zero is recorded
  as a contradiction and counted under neither.
- **Reading of F-431-2 to F-431-4 against the specification (24.10, Agent 432).**
  - F-431-2 and F-431-3 quote WINDOW-RELATION.md section 3, which states the conduct of a machine
    of the window. What a device that was not there verifies is stated in Consensus, "The
    certificate of a window" (docs/Montana Consensus.md:815-830): presence proofs, signatures,
    pledge keys, distinct parts, the counting of the agreement, ballots, votes, and the proposal's
    ticket and signature. It names neither an answer to the final beacon nor a recomputation of its
    pair, and the certificate carries what neither needs: heavy attestations and ballots, no light
    attestation (a final beacon answered by lights alone has no heavy beside it, pulse.rs:546,
    826-829) and no roll (the parts of the pair stand in the roll, pulse.rs:1922-1931, which no
    `Step` carries). Requiring either in `decided_of` would refuse honest walks. The two stay open as
    a question of the specification: a step carrying the roll of the proposal would let the device
    recompute the pair by the rule `replay` already applies. The head does not rest on them
    (Theorem 4).
  - F-431-4 is a divergence of code from the specification: Consensus, "The sanction of a double
    answer" (docs/Montana Consensus.md:812): "The first appearance stands and the second is refused,
    by the canonical order of the window and never by the order of arrival at a machine."
    `Book::heavy` keeps the first in the order handed in (agreement.rs:112-118) and records no
    contradiction. An open pledge of attempt zero other than the heavy cannot arise
    (layout.rs:759-765 refuses it at parsing), so the case is exactly two verified heavy
    attestations of one part on two names. The specification does not define the canonical order
    of the heavy attestations of a window -- the phrase stands at Consensus.md:812, Canon.md:4832 and
    Identity.md:124 without a definition -- so the fix waits on that definition (C-2: an ambiguity
    is flagged before code).
- **F-431-1 (closed).** The comment at round.rs:71 said round zero links to "the proposal of the
  window before"; certificate.rs:406-409 and the Canon it quotes say "two before", and `walk` passes
  the proposal of `W - 2` (:748-758). The pulse agrees with the Canon: `Pulse::open` takes
  `stands_on`, "the proposal of the window two before, which round zero links to" (pulse.rs:223-225),
  and its caller hands the identifier of `two_before` (pulse.rs:1253-1262). The comment now says so.

## 9. Checked by machine

- **Lean 4** (`formal/Window.lean`): `quorum_intersection` (Lemma 4 over the naturals, with
  inclusion-exclusion as a hypothesis, by `omega`); `quorum_exact` (the integer rule of close.rs
  against its rational reading); `one_decision` (Theorem 1) over an abstract book of parts,
  attempts and names under the counting rules of agreement.rs:165-210, with the three contradicting
  pairs as the honest predicate and the cryptographic assumptions as hypotheses.
- **Kani** (harnesses in `crates/montana-node/src/certificate.rs` under `cfg(kani)`): with the
  verification of signatures and proofs replaced by nondeterministic oracles, every `Ok` of
  `verify_certificate` satisfies `Pi_cert` (Lemma 2); the answer is independent of the order of the
  rounds where no part pledges two names in attempt zero (Lemma 1, F-431-4); and no arithmetic
  panics or wraps for counts up to the bound the harness fixes.
- **Tests** stand in for neither: they exercise the matrix (`tests/certificate_matrix.rs`,
  `tests/a_walk_from_an_anchor.rs`).

## 10. Obligations

- **O-1.** The derivation of `eps_K` for the three descriptions from the parameters of the scheme,
  at the security target of Canon.
- **O-2.** (A5) read off `mt-proof/src/circuit/presence.rs`, constraint by constraint.
- **O-3.** Lemma 3 reads the two halves as two squeezes of one absorption from the shape of the
  description (presence.rs:15-20, 103-108, 152-162); the constraint that the half wired into the
  part-nullifier is the state of the first squeeze, and the half under the leaf that of the second,
  is checked constraint by constraint under O-2.
- **O-4.** (A6) as a theorem of the roots of state.
- **O-5.** F-431-1 to F-431-4 closed in code, each with a test that refuses the named wrong
  verifier.

Item 1 stands closed when O-1 to O-5 are discharged, the Lean file and the Kani harnesses run
green on the build host, and the ring of the core is green on the commit that carries them.
