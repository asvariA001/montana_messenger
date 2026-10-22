# The intersection model: what Montana claims against a global passive adversary, and what is open

**Status: open. No proof exists for the specification, and the shipped code implements only part of
the construction the claim stands on.** This file states the claim precisely so that the gap can be
measured rather than argued. Nothing in it is marked solved.

Baseline: commit `2c6951f8b887b7e42bfe745f5bb42cdb207508f7` of this repository (the normative set in
`core/docs/`, the reference core in `core/Montana-Core/`). Line numbers refer to that commit.

## 1. Setting

- **Time** is counted in windows `W`, the height of the chain (`core/docs/Montana Constitution.md`
  [I-15], lines 336-364: no physical clock enters the protocol).
- **Machines** form the active set of a window. Every device reachable from outside carries others'
  traffic by that fact (Network, "Carrying is the work", lines 498-507 in `core/docs/Montana Network.md`).
- **A pipe** between two correspondents has, per window, one tag derived from their shared secret
  (`core/Montana-Core/crates/mt-derive/src/tag.rs` lines 36-40); the holder of that tag is the machine
  whose commitment stands least above it (Network conformance point 13, line 721).
- **A delivery** is a body cut into blocks of `chunk_bytes` and padded with zeros to whole blocks and
  whole groups (`core/Montana-Core/crates/mt-wire/src/erasure.rs` lines 224-263); every block rides one
  cell of `cell_bytes` = 1232 (`core/docs/Montana Canon.md` line 897; `mt-wire/src/lib.rs` lines 49-52).
- **A step** re-seals a cell under the key of the next hop, rewriting one slot of a seal array that
  is full of drawn values from the origin (`mt-wire/src/cell.rs` lines 284-303; `mt-wire/src/draw.rs`
  lines 85-93).
- **The moment of sending** is, by the set, the canonical slot of the window derived from the sender's
  ephemeral identity (Network, "The canonical slot", lines 328-356; `mt-derive/src/tag.rs` lines 110-117).
- **Deferred mail** to a sleeping device leaves the holder after a delay drawn uniformly over the
  residues of the window not yet reached, never for live traffic (Canon lines 1211-1225; Network
  lines 434-442).

## 2. The adversary

**GPA.** A global passive adversary reads every link of the network: every byte, its length, its
direction and the instant it crosses, at every point, for as long as it likes. It does not inject,
delay or drop (an active variant is addressed by the set separately: Network lines 346-356, 671-672).
It does not break ML-KEM-768, ML-DSA-65, ChaCha20-Poly1305 or SHA-256.

**GPA plus a share of machines.** The set also prices an adversary who owns a fraction `f` of the
active set and so reads what a hop reads: the label of its own step, the target commitment and the
fact of forwarding (Network lines 273-276; the price `f` cubed at the first steps, lines 233-238).

## 3. What the set claims — exactly

**[I-16] Transport indistinguishability** (`core/docs/Montana Constitution.md` lines 366-375): for any
object the network carries, an observer of the wire at one point, *and at any set of points that does
not hold both ends of the same delivery*, cannot determine what the object is, who originated it, for
whom it is destined, or whether a given machine is its origin or a relay.

What the set explicitly does **not** claim:

- **Both ends.** An observer holding both ends of one delivery correlates envelopes; the price is set
  by the delay profile and the volume carried (Constitution lines 371-375 and 396-413; Network
  observability ledger line 691: *a named physical prohibition*).
- **Inseparability of summed flows.** The sum of others' traffic with one's own removes the profile
  of one correspondence from one line; it does not make the summands inseparable (Constitution lines
  396-404, citing Murdoch and Danezis 2005, Johnson et al. 2013, DeepCorr 2018).
- **The degenerate case.** A device that is not reachable (behind a translator, asleep) exposes to the
  owner of its line and to its neighbouring hop *that a source is here, and the moments and volumes
  of its own activity* (Constitution lines 507-514). Content, recipient, path and the correspondent's
  address are claimed closed there by the same rules as everywhere else.
- **A stream** (voice, video) is a limit of volume, named (Network ledger line 690).

## 4. The intersection attack, stated against this setting

Let the rounds be windows `r = 1, 2, ...`. For a sender `A`, let `S_r` be the set of machines whose
access line carried an outbound cell in window `r`, and `R_r` the set whose access line carried an
inbound collection in window `r`. The classical intersection (set-disclosure) adversary hypothesises
"A corresponds with B" and tests `B ∈ R_r` for every `r` with `A ∈ S_r`; its statistical form
(statistical disclosure, Danezis 2003; Danezis and Serjantov 2004) estimates B's excess frequency in
`R_r` over the windows in which A was active, and succeeds after a number of rounds that falls as
the anonymity set shrinks and as A's activity pattern grows distinctive.

What the construction of the set does and does not give against this adversary:

1. **Timing inside a window.** The canonical slot removes the keystroke as the moment of sending, so
   within one window entry and exit of a path are not joinable by time (Network lines 341-344). This
   closes the *watermark by timing*, not the *membership of `S_r`*.
2. **Membership of `S_r` and `R_r`.** The set makes no synthetic traffic (Constitution lines 389-395).
   A sender with nothing to send emits nothing in that window. For a machine that carries others'
   traffic, emission is not evidence of authorship (Network ledger line 683, "closed by carriage"); for
   a device that is not reachable, the moments and volumes of its own activity are exposed on its
   access line (Constitution lines 507-514). **For such a device `A ∈ S_r` is observable by the GPA
   and `B ∈ R_r` likewise.** The set does not claim otherwise; it names the case and bounds what it
   exposes to *fact, moment and volume*, not to content, recipient or path.
3. **Deferred mail.** The uniform delay inside the window decorrelates arrival at the holder from
   departure toward a sleeping recipient (Canon lines 1211-1225). It reorders within a window; it does
   not move `B`'s collection out of the window in which the letter was deposited, so at the
   granularity of a window `R_r` is unchanged.
4. **Path and shape.** One size, one type, a per-step label, a full seal array and re-sealing at every
   hop remove every quantity an observer could use to match the cell seen at `A`'s line with the cell
   seen at `B`'s line *other than time and count* (Network lines 155-168, 260-264). This is what the
   intersection adversary is left with, and it is exactly what the intersection adversary uses.

**Conclusion of the model.** The set's claim is a claim about *linking one delivery across the
path*, closed by shape, label and slot, and about *the price of correlation* for an observer of both
ends. It is **not** a claim that intersection across windows is closed for devices whose own activity
reaches their access line. For reachable machines the set's answer is carriage: the access line of
such a machine carries a sum, and `A ∈ S_r` ceases to mean "A sent". A proof of that answer would have
to show that the sum is of sufficient composition in every window `A` sends in; the set states that
*the volume carried is other people's letters* (Constitution lines 393-395) and names the floor it
cannot supply (lines 406-413). No such proof exists in this repository.

## 5. What a proof would require — open

Each line below is open. None is claimed.

1. **A leakage function.** A formal definition `L(trace)` of what the GPA learns from a window: for
   every access line, the counts of units per window and their instants; for every inter-machine link,
   the same. With the canonical slot in force, the instants reduce to the slot index of the chain and
   the count; without it (today), they are the keystrokes.
2. **A notion.** Relationship anonymity in the sense of the AnoA framework (Backes, Kate, Manoharan,
   Meiser, Mohammadi 2013) or the hierarchy of Kuhn, Beck, Schiffner, Jorswieck and Strufe (2019),
   stated for one window and then for `n` windows, with the adversary of section 2.
3. **The in-window theorem.** That, under the canonical slot and one shape, two deliveries of one
   window are indistinguishable to the GPA except by the access lines they touch. This is the part
   the set argues in prose (Network lines 341-344) and the timing tests of this pull request witness at
   the level of code (`core/Montana-Core/crates/mt-wire/tests/timing_cell.rs`); it has no proof.
4. **The across-window bound.** The advantage of the statistical disclosure adversary over `n`
   windows as a function of the anonymity set per window, the fraction of devices that are reachable
   carriers, and the activity distribution of unreachable devices. This bound is the intersection
   model proper. The set gives no number and claims none; the honest expectation from the literature
   is that for an unreachable device with a distinctive activity pattern the advantage approaches one
   as `n` grows, and that only carriage (section 4, point 2) or a floor of traffic the set declines to
   supply changes that.
5. **Implementation.** The theorem of point 3 is about a construction that the shipped code does not
   yet run: the canonical slot, the holder rule, the path of distinct machines, the right spent at the
   holder, the deferred delay and the digest set are registered as *awaiting stage 18* in
   `core/docs/Montana Registry.md` lines 127-144, and the reference node today writes a unit to its
   link the moment it seals it (`core/Montana-Core/crates/mt-net/src/lib.rs` lines 242-251) and
   deposits an object directly at an acquaintance (`crates/montana-node/src/lib.rs` lines 866-898).
   Until stage 18 lands, the leakage of today's transport is the keystroke, and no in-window property
   holds for it. The messenger application reaches the network through a postman over TLS
   (`apps/messenger/ios` calls `mt_postman_start`; see the Gate -1 table in [README.md](README.md)),
   with none of these mechanisms on its path.

## 6. Relation to the other open items

- Protocol-13 item 1 (window proof, issue #4) is the authentication of the window the slot is read
  from; nothing here closes or duplicates it. Without it the slot has no authenticated height.
- Protocol-13 item 11 (`../Protocol-13/11-ANONYMITY.md`) is the anonymity of a node's identity and
  origin; this file is about the correspondence carried, and the two meet at the access line of an
  unreachable device.
- Issue #2 (external audit) names *traffic analysis mitigation against simulated GPA timing attacks*
  as scope item 2; the timing tests of this pull request are the project's own measurement of the
  code-level part of that item, and are self-verification, not an external audit.

## References

- Danezis, G. *Statistical disclosure attacks: traffic confirmation in open environments.* 2003.
- Danezis, G., Serjantov, A. *Statistical disclosure or intersection attacks on anonymity systems.* 2004.
- Murdoch, S., Danezis, G. *Low-cost traffic analysis of Tor.* 2005.
- Johnson, A., Wacek, C., Jansen, R., Sherr, M., Syverson, P. *Users get routed.* 2013.
- Nasr, M., Bahramali, A., Houmansadr, A. *DeepCorr.* 2018.
- Backes, M., Kate, A., Manoharan, P., Meiser, S., Mohammadi, E. *AnoA: a framework for analyzing anonymous communication protocols.* 2013.
- Kuhn, C., Beck, M., Schiffner, S., Jorswieck, E., Strufe, T. *On privacy notions in anonymous communication.* 2019.
- Reparaz, O., Balasch, J., Verbauwhede, I. *Dude, is my code constant time?* DATE 2017.
