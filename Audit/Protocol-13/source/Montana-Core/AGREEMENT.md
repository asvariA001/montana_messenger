# Agreement on what a window decided

Date: 2026-10-16. Session: 42. Author of this note: the Architect (Claude), under the author's words
of 2026-10-16 recorded in the chain of the council: close item 1 whole, with nothing left over.

This note is the design gate for the one finding of that work that is not a remainder of item 1. It
states the finding, why no rule local to a machine closes it, what closing it requires, the
construction recommended, and what it costs, so that the author can rule on it as a stage of its own.

## 1. The finding

A window's decision is the proposal its machines take, and its name is taken over what the window
decided: the predecessor, the six roots, the counts, the census and the cement pair (Canon, "The
proposal that closes a window"). Two inputs of that name are what a machine held when it left a
round, and nothing makes two machines hold the same:

- **the cement pair.** A machine advances only on a beacon whose pair equals the cement it holds
  (`montana-node/src/pulse.rs`, `matches_cement`, `reconcile_beacon`), and closes on the first
  advanceable beacon at the quorum (`live_doing`). A machine holding one part more than a beacon at
  the quorum cannot reconcile it and closes later on another beacon with another pair; where the
  quorum asks less than the whole electorate, two machines close one window on two pairs.
- **the frames.** A machine applies the frames standing at the points of a round as it leaves that
  round (Canon, "The roots of state"; `Machine::apply_what_stands`). A frame that reaches one machine
  before it leaves the round and another after has entered one plane and not the other, and the two
  carry two note roots for one window — at any population, three machines included.

Two names for one window part the window after: its heavy attestations attest the name each machine
took, its cement counts parts per name, and where no name reaches the quorum of that window no
machine of it closes. A machine attests one name per window under its part, and a second is the
equivocation the set sanctions, so nothing re-votes. Safety is untouched — two names cannot both
reach a quorum without a third of the electorate signing both — and liveness is not: the set orders
the rounds and the versions of a round (the smaller identifier) but never votes a second time on the
predecessor of a window.

The runs of this tree are of two and three machines, where the quorum asks all and no frame raced a
close; they show neither half.

## 2. Why no local rule closes it

Every rule a machine applies to what it holds — the smaller identifier among the candidates it has
seen, the larger cement, the first to arrive — agrees between two machines only where both saw the
same candidates before they attested, and nothing bounds when a candidate arrives. A machine that
switched after attesting would equivocate; one that never switches keeps a split forever. Agreement
under a bounded fraction of faults and unbounded delays is the problem of Byzantine agreement, and
its known constructions vote more than once: a second vote that locks, or a common coin.

## 3. What closing it requires

- **One decision per window, at the quorum of the electorate**, safe while fewer than a third of it
  signs two ways — the property the set has today and keeps.
- **Liveness once delivery is bounded**: after delays fall below a bound no machine knows in
  advance, every honest machine decides, with waits that grow from attempt to attempt.
- **A decision any machine can adopt**: a proposal names what it applied — the frames in order and
  the parts of its cement — so a machine that left its rounds holding something else recomputes the
  decided state from the named content rather than from its own reading of the points.
- **A certificate a device verifies**: the walk reads the decision off the votes as it reads the
  cement today.

## 4. The construction recommended

An agreement in the manner of Tendermint (Buchman, Kwon, Milosevic, 2018), run inside the window
after over the predecessor of the window, with Montana's own pieces:

- **proposers**: the runners of the window, in the order of their tickets — the order Canon now
  states (`CARRIES_WITHIN`) — so the first proposal of an attempt usually comes from one machine and
  nobody learns whose;
- **the first vote**: the heavy attestation as it stands, attesting the proposal its machine takes;
  later attempts vote under the key that heavy proved, as light attestations do;
- **the second vote and the lock**: a seal — a signature of the part's key over the window, the
  attempt and the name — published once the first votes of an attempt reach the quorum on one name;
  a machine that sealed a name votes only that name in later attempts unless it holds first votes at
  the quorum on another name of a later attempt;
- **the decision**: seals at the quorum on one name, which is what makes the predecessor final and
  what a device walks;
- **the content**: the proposal carries the identifiers of the frames it applied, in the order it
  applied them, and the parts of its cement, so a machine adopting it replays the window's frames
  from that list rather than from the points it happened to read.

## 5. What it costs, and what stands meanwhile

It touches the object of a proposal and adds one object (the seal), the rules of the close and of
finality in Consensus and Canon, the pulse of the node (attempts, the lock and its persistence,
adoption and replay of a window's frames), the certificate and the walk, and the gate's vectors; and
it asks a test of four machines with a quorum of three under a held part, a late frame and a
partition that heals. By the measure of the work of this branch it is several days, not one.

Meanwhile safety stands as it stood, the register of the Constitution names the finding open, and
`AUDIT.md` carries it among what was met on the way.
