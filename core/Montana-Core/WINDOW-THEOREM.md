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
- A **round** `r = (b, H_r, L_r, B_r)`: a beacon, the heavy and the light attestations that
  answered it, and the ballots standing beside them (certificate.rs, `Round`).
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
  section 10 is its derivation, whose whole sum reaches the target of Canon on the Johnson bound,
  a theorem (Canon, `proof_queries`, decision 4 of the author, 24.10, and the author's word of 24.10 21:21 UTC).
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
appearance per kind, attempt and part. The one dependence on order was the pledged name of a part
in attempt zero; it is now that of the first heavy attestation of the part in the canonical order
(Consensus, "The sanction of a double answer"): `Book::heavy` keeps the least `(place, name)` of the
part, `place` being chain, round and round nullifier (agreement.rs, `Place`), and a least element of a
total order does not depend on the order the elements are handed in (finding F-431-4, closed, section 8). ∎

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
  F-431-3 for the two clauses of section 3 it did not check; the pair is now recomputed from the
  roll by `cement_of_roll` (decision 3), and a final beacon no verified attestation of the
  certificate answered is refused (F-431-2, closed on the author's word of 24.10 21:21 UTC).
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

- **F-431-2 (closed, 24.10 21:21 UTC, the author's word "work by the specification"; see below).** `decided_of` takes the final beacon from any round of the certificate
  (certificate.rs:417-423), including a round whose beacon no verified attestation answered.
  WINDOW-RELATION.md section 3, "Close", names the final beacon as one "which a verified
  attestation answered". Required: the final beacon is answered by a verified heavy attestation
  of the certificate.
- **F-431-3 (closed, 24.10, decision 3 of the author).** `decided_of` bounded the final beacon's count by the certificate's
  (certificate.rs:449-453) and does not recompute its `cement_aggregate` from verified parts.
  WINDOW-RELATION.md section 3, "Cement": "Every verifier recomputes both from the attestations it
  holds and refuses a beacon whose state is not that pair." Required: the pair of the final beacon
  is the aggregate and the count of a set of verified parts of the certificate. Theorem 4 does not
  rest on F-431-2 or F-431-3: the head is fixed by the agreement of the window after, which decides
  an identifier carrying the final beacon and the pair. A proposal taken on the last step, which is
  not the head, did rest on them.
  *How decision 3 closes them.* Consensus 7.2.0, "The join", 3, now reads: "The device recomputes
  that pair rather than reading it off the beacon, by the rule a machine of the window applies when it
  replays a roll: the roll whose identifier `N` carries, standing beside `N`, names parts ascending
  and without repeat, each the part-nullifier of a heavy attestation of the certificate just
  verified, in the number `N` carries as the count of its cement, and aggregates with the window as
  context to the aggregate `N` carries." The step of the walk carries the rolls (`Step::rolls`,
  certificate.rs; `join` collects them with `rolls_of`, pulse.rs) and `walk` calls
  `cement_of_roll` after `decided_of` and before `verify_successor`. F-431-3 is closed: a pair that
  is not the aggregate of verified parts is refused (tests `walk` in a_walk_from_an_anchor.rs: a
  final beacon with an aggregate that is no cement; a final beacon over the pair of a set holding a
  part no verified attestation carries; a proposal whose roll was not handed in). F-431-2 is not
  closed as the finding stated it: "which a verified attestation answered" cannot be checked from a
  certificate of heavy attestations alone, since a round may be answered by light attestations only
  (pulse.rs:546, 826-829) and a certificate carries none, and the author chose the roll and not the
  lights. What a device now holds of the final beacon is that its pair is the cement of verified
  parts; the beacon itself stands outside the name of the proposal (Canon, "The proposal that closes
  a window"), so an unanswered beacon carrying a verified pair decides nothing the pair does not.
  If the specification is to require the answer, the certificate must carry the light attestations
  of the final round: a question for the author, not changed here.
  *How the author's word closes F-431-2.* Asked whether to put the light attestations of the final
  round into the certificate, the requirement "which a verified attestation answered" of Consensus,
  "The beacon a window closes on", standing, or to drop the requirement, the author answered "work by
  the specification" (24.10 21:21 UTC): the requirement stands. Consensus 7.3.0 and Canon 9.5.0, "The certificate
  of a window", now name in the certificate the light attestations of the window after answering the
  final beacon its proposal names, verified under the key their part's heavy attestation proved and
  on the name it pledged, and the retention keeps them beside the heavy ones; "The join", 1 to 3, asks
  for them and judges the final beacon "which an attestation of the certificate verified in 2
  answered". In code `Round` carries `light`, `verify_certificate` collects in `Closure::answered`
  every beacon a verified heavy or light attestation answered (a light one that does not verify
  answers nothing and refuses nothing, as a ballot or a pledge), `decided_of` refuses a final beacon
  outside that set ("a final beacon no verified attestation of the certificate answered"), and `join`
  reads the light attestations of every round (`rounds_standing`, pulse.rs). Tests:
  `a_final_beacon_no_verified_attestation_answered_closes_nothing` (certificate_matrix.rs) and the
  last block of `a_device_walks_from_its_anchor_to_the_head_and_no_further`
  (a_walk_from_an_anchor.rs).
- **F-431-4 (closed, 24.10, decision of the author).** In attempt zero the pledged name of a faulty
  part was that of its first heavy attestation in the order handed in (agreement.rs:112-118).
  Theorem 1 does not rest on it, the part being the adversary's either way; but two verifiers given
  one certificate in two orders could count that part under two names. Closed by the definition the
  reading below found missing: Consensus, "The sanction of a double answer", now states the canonical
  order of the heavy attestations of a window (chain, round, round nullifier, and the pledged name
  for a tie), Canon, "The confirmation of a window", states its bytes, and `Book::heavy` takes the
  place of the attestation and keeps the first (agreement.rs, `Place`, `Book::heavy`). Test:
  `the_first_heavy_attestation_in_the_canonical_order_stands_whatever_the_order_handed_in`, which
  refuses an implementation keeping the first handed in. The earlier wording of the finding asked for
  a contradiction counted under neither name; the author chose the first of the order, which is the
  rule the sanction already states. The live pulse still refuses a second heavy attestation of a part
  naming another name by arrival (pulse.rs, `gather_saying`), so its own book holds what reached it
  first; the book of a certificate does not depend on it.
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
    `Book::heavy` kept the first in the order handed in (agreement.rs:112-118) and recorded no
    contradiction. An open pledge of attempt zero other than the heavy cannot arise
    (layout.rs:759-765 refuses it at parsing), so the case is exactly two verified heavy
    attestations of one part on two names. The specification does not define the canonical order
    of the heavy attestations of a window -- the phrase stood at Consensus.md:812, Canon.md:4832 and
    Identity.md:124 without a definition -- so the fix waited on that definition (C-2: an ambiguity
    is flagged before code); the author decided it on 24.10 and Consensus 7.1.0 states it.
- **F-431-1 (closed).** The comment at round.rs:71 said round zero links to "the proposal of the
  window before"; certificate.rs:406-409 and the Canon it quotes say "two before", and `walk` passes
  the proposal of `W - 2` (:748-758). The pulse agrees with the Canon: `Pulse::open` takes
  `stands_on`, "the proposal of the window two before, which round zero links to" (pulse.rs:223-225),
  and its caller hands the identifier of `two_before` (pulse.rs:1253-1262). The comment now says so.

## 9. Checked by machine

- **Lean 4** (`formal/Window.lean`, Lean 4.35.0, core library only, no `sorry`, no axiom of its
  own; `#print axioms` names only `propext`, `Quot.sound` and `Classical.choice`):
  `quorum_intersection` (Lemma 4 over the naturals under the integer rule of close.rs,
  `100 c ≥ 67 T`, with inclusion-exclusion as a hypothesis, by `omega`); `more_than_the_faulty`
  (its corollary under `100 f < 34 T`); `the_vectors_of_the_close` (the frozen vectors of
  close.rs:116-119 under the same rule, the third being the one a rule dividing first gets wrong,
  by `decide`); `no_other_open_quorum` and `one_decision` (Theorem 1) over an abstract book of
  parts, attempts and names under the counting rules of agreement.rs:165-210, with the three
  contradicting pairs of agreement.rs:70-89 as the honest predicate and the cryptographic
  assumptions as hypotheses. The rational reading of the quorum rule is not stated in Lean; the
  integer rule is the only one the file reasons about.
- **Kani** (bounded model checking of the code itself, under `cfg(kani)`):
  - Theorem 1: `two_books_of_one_window_never_decide_two_names` (`crates/montana-node/src/agreement.rs`)
    runs the book of agreement.rs itself on two books of one window: the three honest parts say the
    same in both under the honest predicate, the fourth part is free in each, a decision in both is
    covered (`kani::cover`), and the two names are equal. Bounds: 4 parts, 3 honest, 2 attempts,
    names 1 and 2 (0 is a pledge not made), committee 0, unwind 9. Result: not yet in.
  - Lemma 2: `an_accepted_certificate_satisfies_the_predicate` (`crates/montana-node/src/certificate.rs`)
    runs `verify_certificate` itself, with presence proofs, signatures and ballot proofs as oracles the
    objects carry and with stubs for `Beacon::id`, `genesis_state_hash`, `round_threshold` and
    `Cement::aggregate`: every `Ok` satisfies (C2) to (C6); (C1) and (C7) rest on the stubs and are
    not proved here. Two covers ask that a certificate is accepted, and that one is accepted with two
    attestations of one part. Bounds: 1 round, 3 heavy attestations over 3 parts, electorate 2,
    2 ballots, 2 pledges over 2 attempts, unwind 9. Result: not yet in.
  Lemma 1 (the answer independent of the order of the rounds) has no harness yet: the canonical order
  of heavy attestations is defined since Consensus 7.1.0 and the book keeps it, but the harness is not
  written; the unit test of F-431-4 holds the rule on examples, which is not a proof.
- **Tests** stand in for neither: they exercise the matrix (`tests/certificate_matrix.rs`,
  `tests/a_walk_from_an_anchor.rs`).

## 10. Obligations

- **O-1.** The derivation of `eps_K` for the three descriptions from the parameters of the scheme,
  at the security target of Canon. *The parameters* (params.rs:10-20, ext.rs:13-14, field.rs:17
  at 8d6409d; Canon, the table beside `proof_security_bits`): the base field `p = 2^64 - 2^32 + 1`,
  every challenge drawn in the cubic extension `K` of order `p^3` (about `2^192`), blowup 8 (rate
  `rho = 1/8`), 48 queries at distinct positions at that reading (transcript.rs:117-120; 92 since decision 4 and the derivation of the sum, below), fold 4, a tail of 64, and no
  proof-of-work term in the transcript. `eps_K` is the sum of three terms: the mixing and the deep
  composition, the commit phase of the folding, and the query phase. The first is a term over the
  order of `K` (Canon gives `2^-172`) and binds in no regime below; the commit phase is over the order
  of `K` too, but with the square of the domain and the seventh power of the parameter `m` of the
  Johnson analysis in it, and it binds `m` (derived below); the query term does not depend on the
  height, so the presence, the ticket and the ballot share it. Per
  attempt, by the regime of the proximity parameter `delta` the analysis of the folding reaches:
  - unique decoding, `delta` below `(1 - rho)/2` (a theorem): `((1 + rho)/2)^48 = (9/16)^48 = 2^-39.8`;
  - the Johnson bound, `delta` below `1 - sqrt(rho)` (a theorem for Reed-Solomon codes): at best
    `(sqrt(rho)(1 + 1/(2m)))^48`, which is `2^-69.9` at `m = 16` and tends to `2^-72`;
  - capacity, `delta` toward `1 - rho` (a conjecture on the proximity gaps of Reed-Solomon codes, not
    a theorem): `rho^48 = 2^-144`.
  *Closed by decision 4 (24.10, the author; Canon 9.3.0, `proof_queries`), and the sum derived on
  the author's word of 24.10 21:21 UTC (Canon 9.4.0).* The author chose the second of the two ways:
  parameters that reach the target on the Johnson bound, and not the capacity conjecture. Blowup
  stays 8, so the extension a prover holds and the time of the low-degree extension do not grow.
  The first pass (Canon 9.3.0) took 88 queries at `m = 16` from the query term alone; the target is
  `eps_K` whole, and the commit phase of the folding is what binds `m` from above.
  *The bound.* Ben-Sasson, Carmon, Ishai, Kopparty and Saraf, "Proximity gaps for Reed-Solomon
  codes", ePrint 2020/654, read in ECCC TR20-083 revision 3 (3 July 2021), section 8.2, Theorem 8.3
  (Batched FRI Soundness) with Lemma 8.2, verbatim up to typesetting: for integer `m >= 3`,
  `alpha = sqrt(rho)(1 + 1/2m)`, `s` invocations of the query step,
  `eps_FRI := eps_C + alpha^s`, where
  `eps_C = (m + 1/2)^7 |D0|^2 / (2 rho^(3/2) |F|) + (2m + 1)(|D0| + 1)/sqrt(rho) * sum_{i<r} l(i) / |F|`.
  The denominator of the first term is `2 rho^(3/2)` in the lemma; `3 rho^(3/2)` is the bound (8.8)
  on the batching event alone, which the lemma sums with the events of the `r` rounds. The
  revision of 30 May 2020 states the theorem differently (Theorem 7.2, through a gap `eta`); this
  paper uses revision 3. The field `F` of the theorem is `K` here: the code is read over `K`, the
  domain lies in the base field inside it, and every folding and mixing coefficient is drawn from
  `K`, so `|F| = p^3`.
  *The parameters.* `rho = 1/8`; `|D0| = 2^19`, the largest extended domain of the three descriptions
  (the ballot and the frame; the presence is `2^17`); five layers of fold 4, so `sum l(i) = 20`;
  `|K| = p^3`, about `2^192`; the mixing and the deep composition `2^-172` (Canon, the row of
  `proof_challenge_field`; independent coefficients, no factor for the count of columns).
  *The sum.* `m` trades the phases: the query term falls toward `2^-1.5 s` as `m` grows and the
  first term of `eps_C` grows as `m^7`. At `m = 16` the first term is `2^-122.19`, so no count of
  queries reaches 128 there; at `m = 9` it is `2^-127.77`, still above. At `m = 7`: `eps_C =
  2^-130.15 + 2^-163.27 = 2^-130.15`; the query term is `(2^-1.5 x 15/14)^92 = 2^-1.40046 x 92 =
  2^-128.84`; with the mixing, `eps_K <= 2^-130.15 + 2^-128.84 + 2^-172 = 2^-128.35`. At `m = 8` and
  92 queries the sum is `2^-128.32`, at `m = 6` it needs 93 (`2^-128.57`), at `m = 5` 94, at `m = 3`
  101. With 91 queries the sum is `2^-127.24` at `m = 7`, `2^-127.70` at `m = 8`, `2^-127.36` at
  `m = 9`, and no `m` reaches 128. So 92 is the least count, at `m = 7` or `8`, and `m = 7` gives
  the larger margin, 0.35 bit. The presence, `|D0| = 2^17` and four layers, has
  `eps_C = 2^-134.15` and a sum of `2^-128.81`. **128 bits are proved for `eps_K` whole**, for the
  three descriptions, on the Johnson bound, from the theorem and the parameters above; the knowledge
  extractor of (A3) beyond the soundness of the folding is the reference's and ethSTARK's (ePrint
  2021/582), not re-derived here.
  `PROOF_LEN` is 4 928 + 92 x 4 344 = 404 576 B and `PRESENCE_PROOF_LEN` 4 896 + 92 x 3 576 =
  333 888 B; every length that carries a proof moved with them (Canon: `opening_len`, `action_len`,
  `frame_len`, `ballot_len`, `candidacy_len`, `confirmation_len`, `proposal_len`, the frozen vectors
  of the proposal, the frame and the notice, and the cells of a delivery: `collect_answer_max` 336 ->
  608 -> 624, a round point 227 -> 127 -> 124 heavy attestations by the `u16` index); the largest
  message of the wire is 768 770 B, 96 s at 64 kb/s, inside `PATIENCE_SECONDS` = 120. One
  consequence decision 4 did not name: the free block of the trace must stay above what the queries
  open, `2 x proof_queries + 2 = 186` values (Canon, "The free block, the randomizer and the two parts
  of the composition"), and `proof_free_rows` was 128; it is 256 now, two blocks of the permutation,
  which changes no description (the identifier of a description does not take the free block into
  its bytes) and leaves the frame, the ballot and the presence inside their heights. (A3) now names
  no conjecture.
- **O-2.** (A5) read off `mt-proof/src/circuit/presence.rs`, constraint by constraint
  (24.10.2026, read at 0fe6134; the Poseidon permutation layer is taken as the permutation, as A4
  takes it). Width 12, rate 8, capacity 4 (poseidon.rs:17-22). Every chain opens under its domain:
  at each seam of `opens` (presence.rs:164) the four capacity cells one row on equal the limbs of the
  domain (capacity.rs:57) -- `MT_KEY_HALVES`, `MT_ADMITTED_LEAF`, `MT_ADMITTED_NODE` at each of the
  40 levels, `MT_PART_NF`, `MT_PART_KEY_DIGEST`. Every padding cell of every absorption is a literal
  (sponge.rs:45, presence.rs:147), so no rate cell past the elements is free.
  *The leaf.* The capacity crosses every seam inside the chain of the halves, the seam into the
  block of the second squeeze included (`carry_rows`, presence.rs:89; sponge.rs:126, 165), and the
  rate crosses that seam too (`squeeze_rows`, presence.rs:106; sponge.rs:96): the extra block enters
  with the whole state the absorption left. The four cells that block leaves enter the leaf block
  (`digest_carries` at the seam before the leaf, sponge.rs:102), whose remaining rate is padding.
  At every level the node block enters with the pair the bit orders, the child being the four cells
  the block before it left (path.rs:99); the four cells the last node leaves are bound to public
  word 0, the admitted root (presence.rs:117-125).
  *The part.* The lane loads the four cells the absorption of the secret leaves -- the first squeeze
  -- at its last row, holds them unchanged wherever no load stands, and wires them into the first
  four rate cells of the nullifier block (lane.rs:73; presence.rs:156-160); the lane is loaded once,
  before the wire, so the reading holds whether or not the rules close over the wrap. The fifth
  rate cell is the public window (presence.rs:137), the rest padding, and the four cells the block
  leaves are bound to the public part-nullifier (presence.rs:117-125).
  *The key.* The absorption of 488 limbs of the one-time key ends in the four cells bound to the
  public key digest (presence.rs:117-125, 62 blocks carried).
  *What the statement is.* The witness is a vector of field elements in the rate of the blocks of
  the halves, not a byte string: no rule ranges the limbs. (A5) therefore reads: there is a vector
  `v` of limbs whose naming half has its leaf in the tree of `root`, with `p` the part-nullifier of
  its spending half and `W`. Under (A4) the leaf fixes the naming half and the naming half fixes `v`,
  so `v` is the vector of the admitted machine and `p = part(s, W)` for its secret `s`; the range of
  the limbs is not needed by the argument. The tests `a_presence_cannot_graft_another_machines_public_membership_path`
  and `a_part_cannot_choose_extra_nullifiers_through_sponge_padding` refuse the two wrong circuits
  this reading names.
- **O-3.** Lemma 3 reads the two halves as two squeezes of one absorption from the shape of the
  description (presence.rs:15-20, 103-108, 152-162); the constraint that the half wired into the
  part-nullifier is the state of the first squeeze, and the half under the leaf that of the second,
  is checked constraint by constraint under O-2: the lane loads at the last row of the absorption,
  the leaf takes the cells the extra block leaves, and the extra block enters with the whole state
  (capacity and rate carried). Discharged with O-2.
- **O-4.** (A6) as a theorem of the roots of state (read at 8d6409d). *The construction.* The roots a
  machine recomputes for a proposal take `admitted_root` as the root of its cohort and `admitted_count`
  as its count (montana-node state.rs:303, 306), and both are of one list: `Cohort::of` sorts the
  naming halves and removes repeats (pulse.rs:60-62), the root is `root_of` over that list
  (pulse.rs:63) and the count is its length (pulse.rs:90-92). `root_of` (mt-proof admitted.rs) places
  the leaves of the list at the first `n` positions of a tree of depth `D = note_tree_depth = 40`
  (mt-genesis lib.rs:146) and the empty leaf at every other position, the empty internal of a level
  standing for a missing right child. The leaf of a half and the empty leaf are one domain over two
  different blocks -- four words, the one-element and zeros against the one-element and zeros
  (poseidon.rs:242-253) -- so under (A4) no leaf is the empty leaf and two halves have two leaves; and
  under (A4) for the node the root fixes the vector of leaves it stands over.
  **Lemma 5 (the count is the leaves).** For every list of naming halves of length at most `2^D`, the
  count of its cohort is the number of distinct non-empty leaves under its root, except with the
  probability of a collision of the family. *Proof.* Equal roots fix equal vectors of leaves; the
  non-empty positions hold the leaves of the distinct halves of the list, `n` of them, and the count is
  `n`. QED
  *(A6) from Lemma 5.* A final proposal carries roots a quorum pledged; by the Corollary of Lemma 4
  the quorum holds a machine whose secret the adversary does not hold (A7), which pledges only after
  recomputing the roots (A8) and refuses a proposal whose roots differ from its own
  (certificate.rs:578-603), so the root and the count of the proposal are of one cohort and Lemma 5
  applies. *The bound.* Past `2^D` halves `root_of` leaves its loop with more than one node standing
  and returns the first -- the root of the first `2^D` leaves -- while the count is the length of the
  whole list, so the count would exceed the leaves of the root. `Cohort::of` names the case in its
  refusal ("a cohort the tree of admitted machines cannot hold", pulse.rs:63-65) and `root_of` does not
  refuse it: finding F-436-1, closed in code by a refusal in `root_of` of a list longer than `2^D`,
  with a test at depth one that refuses the named wrong implementation (three leaves taken under a
  tree of two). O-4 is discharged with that closure.
- **O-5.** F-431-1 to F-431-4 closed in code, each with a test that refuses the named wrong
  verifier. State: F-431-1 closed; F-431-4 closed (decision 2); F-431-3 closed (decision 3);
  F-431-2 closed on the author's word of 24.10 21:21 UTC (the certificate carries the light
  attestations answering the final beacon), see section 8. Not compiled yet: O-5 is discharged when
  the ring runs the tests named there green.

Item 1 stands closed when O-1 to O-5 are discharged, the Lean file and the Kani harnesses run
green on the build host, and the ring of the core is green on the commit that carries them.
