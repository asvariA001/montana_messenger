# Coin record v3 (MTTX, version 3) -- every move of a balance is a signed record of its key's chain

The author's words 11.10.2026 22:5x MSK (No.64): «balances must be proven; the core and the nodes are every device, our servers,
phones, tablets and so on; agreeing to keep a copy they keep a copy of the chain of time of the coins of the TimeChain, and the
Explorer shows the proof of it locally and globally across the network, a quorum, and the whole TimeChain is built for audit».
The constitution: article 0 «either the coins left or not -- there is no third», article 6 «the phone is a full node», article 11
«1 second -- 1 coin».

Version 2 ([COIN-TX-V2.md](COIN-TX-V2.md)) ordered a key's transfers in one chain on one node, but a balance stayed a claim of the sender's
book: the node took any amount, the Explorer said so («balances live in the senders' books»). Version 3 makes the balance the fold
of the key's chain: coins enter a key only by a record (MINT, RECV, REFUND), leave only by a record (SEND), and the chain's node
refuses a SEND above what the chain proves. Every node keeps a replica of every chain, every phone that agreed to keep a copy keeps
one too, and each signs the root of what it holds; the Explorer folds any key's whole chain in the browser and counts who signed
the same root.

## 1. Record (5443 bytes, little-endian integers)

- offset 0, 4 bytes, magic: ASCII MTTX
- offset 4, 1 byte, version: 0x03
- offset 5, 1 byte, kind: 1 SEND, 2 RECV, 3 MINT, 4 REFUND, 5 GENESIS; any other value is no record
- offset 6, 8 bytes, amount: u64, coins
- offset 14, 8 bytes, birth: u64, milliseconds since 1970-01-01 UTC, the signer's clock (shown, never counted)
- offset 22, 16 bytes, nonce: random
- offset 38, 32 bytes, prev: the id of the previous record of the same key's chain (a v2 or v3 record); 32 zero bytes for the first
- offset 70, 32 bytes, pair: SEND -- HMAC-SHA-256(key = the pair's pipe secret, message = ASCII mt-coin-pair-v1), as in v1 and v2;
  every other kind -- 32 zero bytes
- offset 102, 32 bytes, to: SEND -- SHA-256(ASCII mt-coin-to, 10 bytes, then from_receiver, 32 bytes, then blind, 32 bytes);
  RECV -- blind itself; every other kind -- 32 zero bytes
- offset 134, 32 bytes, ref: RECV and REFUND -- the id of the SEND they settle; every other kind -- 32 zero bytes
- offset 166, 8 bytes, t0: u64, MINT -- the first second of the minted interval (node seconds since 1970); SEND and RECV -- dec, the
  declared part of the amount (section 3); every other kind -- 0
- offset 174, 8 bytes, t1: u64, MINT -- the second after the last of the interval; every other kind -- 0
- offset 182, 1952 bytes, pubkey: the signer's ML-DSA-65 public key (the seed's mt-coin-tx key, as in v1 and v2)
- offset 2134, 3309 bytes, sig: ML-DSA-65 over ASCII mt-coin-tx-v3 (13 bytes) followed by bytes 0..2134

id = SHA-256(record); from = SHA-256(pubkey) -- as in v1 and v2. The id, from, prev, to and ref travel as lowercase hex.

blind = HMAC-SHA-256(key = the pair's pipe secret, message = ASCII mt-coin-blind, 13 bytes, followed by the SEND's nonce, 16 bytes).
Both ends of the pipe compute it, no one else can. Until the receiver lays its RECV, an observer of the SEND sees the pair tag and
the commitment to, and cannot test a candidate receiver against it without the blind. The RECV opens it: anyone then checks that
the SEND's to equals SHA-256(mt-coin-to, from of the chain the RECV stands on, the blind the RECV carries) -- so a RECV is
provably the receiver's own, and the sender, who knows the blind too, cannot lay it on its own chain (its from is another).

The receiver's from reaches the sender inside the pipe, in the about word every phone already sends its correspondents (the key
k beside b, l, c): a coin key's tag, never a name. A sender that does not yet know its correspondent's from sends no v3 record: until
TX_V2_CUT it lays a v2 record, which names the pair's tag and no receiver, and which spends on its chain exactly as a SEND (section 3).

## 2. The chains and their nodes

A key's chain lives on one node of the fixed table montana.quest (index 0, Moscow), montana.xxx (index 1, Amsterdam), at index
from[0] mod 2 -- unchanged from v2 (MTCoinTx.chainNodes in the app, TX.chain in the Explorer, TX_CHAIN in the node). That node
alone takes a record on top of the chain's head (prev = head, else 409 with the head); it is the one place a record of that key
is ordered. A v2 or v3 record laid at the other node is refused there (403 not this key's chain node, with the node's name).

Every node keeps a replica of every chain: it pulls what its sibling took (POST /tx-since by the sibling's log index), checks each
record exactly as if it were laid at its own door -- the core's signature, the chain node, prev on the replica's head, the kind's
rules below, the balance -- and keeps it with the sibling's index and the sibling's at. A record that fails is not kept, the
replica stops at it and the node's journal says ALARM: a faulty sibling halts the copy, never bends it.

A machine outside the table keeps the same replica in the node's replica-only mode (COIN_REPLICA: the chain nodes' doors): it pulls
both logs, checks every record as above, takes no record and no claim at its door (403 «replica only»), and signs the root of every
checkpoint as a holder (section 6) with its own coin key, whose chain it opens by a GENESIS of 0. A RECV pulled from one log before
the SEND it settles arrives from the other waits at its place; nothing is skipped. The same code runs hosted inside another store's
process (Lauterbourg) with a database of its own. The nodes' fold has a name (TX_FOLD): a change of its rules re-folds every chain,
the copies of the other node's chains included, from the node's own log at its next start.

The node's at of a record is the node's second when it took it, never less than the at of the record before it on the chain.

## 3. The balance is the fold of the chain

A key's state, folded over its chain from the first record to the head, every record already checked by the core:

- proven: the coins the chain proves, never below zero
- declared: what a GENESIS announced, and the announced part every SEND and RECV carries on; spent after proven, never called proven
- birth: the at of the chain's first record (any version)
- mint_end: the t1 of the last MINT, birth before the first

Per record, in chain order:

- a v2 record (a SEND that names the pair's tag and no receiver -- laid by a build before v3, or by a v3 phone that does not yet know
  the receiver's from, section 1): before the key's first v3 record it is the book's own past, which the GENESIS announces as it
  stood after it, and it moves nothing; from the key's first v3 record on it is a SEND with D = max(0, A - proven): 1 at most A at
  most proven + declared, proven -= A - D, declared -= D. Its receiver is named on no chain: the receiver's book credits it by the
  pair's tag, and the receiver's chain carries those coins by a MINT within the time allowance (section 4). The author's words
  12.10.2026 12:1x MSK: «all transfers must be simply correct ... not versions» -- a v2 SEND at 11:59:58 MSK of 369 000 coins on top
  of its key's GENESIS had moved nothing, so the chain kept coins its book had already sent; every transfer now moves its balance.
- GENESIS (amount A, A may be 0): only as the key's first v3 record; A above 0 only before TX_V2_CUT and at most TX_GENESIS_CAP =
  A_time(TX_V2_CUT - TX_COIN_EPOCH), what time could have minted to one key since the coins began (TX_COIN_EPOCH = 01.09.2026 00:00
  MSK); declared = A. A GENESIS of 0 is the birth of a key new to the network.
  The chairman's correction 12.10.2026 00:0x MSK (the author's words 11.10.2026 20:2x: «so that I can pay in any scenario»): a book's
  balance from before v3 is spendable, never called proven. Declared coins are spent as declared and stay declared in every chain
  they reach.
- MINT (amount A, interval t0..t1): t0 at least mint_end, t1 - t0 at least 60, t1 at most the record's at, and A at most the
  allowance of the interval (section 4); proven += A, mint_end = t1.
- SEND (amount A, dec D): 1 at most A at most proven + declared; proven is spent first, so D must equal max(0, A - proven);
  proven -= A - D, declared -= D.
- RECV (amount A, dec D, ref S, to = blind): S a v3 SEND held by the node (own or replica); A equals S's amount and D its dec; S's to
  equals SHA-256(mt-coin-to, this chain's from, blind); S settled by no other key and not refunded (section 5); proven += A - D,
  declared += D.
- REFUND (amount A, ref S): S a SEND of this same chain, A equals S's amount, the record's at at least S's at + 86400, S not
  settled by a RECV (section 5); proven += A - D, declared += D, D being S's dec.

The chain's node refuses (409 with a reason word) any record whose fold step fails: a SEND or a v2 record above proven + declared
(«overspend»), a MINT above its allowance («allowance»), a RECV on a SEND that is not this key's or settled («settled»), a REFUND
before its day or after a RECV («settled»), a second GENESIS («genesis»). The same fold runs in every replica, on every phone that keeps a copy and in the
Explorer, so a node that took a bad record is caught by everyone who folds after it.

POST /tx-head {"from"} -- 200 {"head", "height", "birth", "mint_end", "proven", "declared"}, the amounts as decimal strings (a u64
does not fit every reader's number); a v2 phone reads only head.
POST /tx-chain {"from", "since": h} -- 200 {"recs": [{"id", "rec", "at", "h", "seq", "origin"}], "head", "height"}, at most 256
records in chain order, h the record's place in the chain (0 the first).

## 4. The allowance of a MINT -- time, by the doubling rule

1 second -- 1 coin, times the level: the level of a key is the number of whole doublings of the seconds since its birth (the app's
rule, MTPersonChain.level, bit_length(s) - 1), at least 1 and at most 13 (MTCoinBook.limit, MTPiLevels.multiplier), and the music
doubles it (MTPiLevels.ceiling: «up to 26 a second»). For the s-th second since birth (s = 1 for the second that starts at birth):

  rate(s) = 2 x min(13, max(1, bit_length(s) - 1))

  A(x) = sum of rate(s) for s = 1 .. x, A(0) = 0

  allowance(t0, t1) = A(t1 - birth) - A(t0 - birth)

So a key mints at most 2 coins a second in its first 3 seconds, 4 in seconds 4..7, 2k in seconds 2^k .. 2^(k+1) - 1, and 26 a
second from its 8192nd second on. MINT intervals of one key never overlap (t0 at least mint_end), so the sum of a key's MINTs up to
any second T is at most A(T - birth): no hand mints faster than time.

Every source of coins the app mints (a letter, a second of a call, the wallet's pull, the seconds away, chess, music) is a claim of
the phone; the node cannot see a call or a letter. What the node can prove is that the sum stays under time. A phone lays one MINT
for what its book holds above its chain (proven + declared) since the last one, at most the allowance; what the book holds above it
stays the book's until a later interval's time covers it.

Coins that reach a v3 phone by a v2 record (a sender that did not know its from, until TX_V2_CUT) are credited by its book under the
pair's tag and enter its chain the same way: as part of the next MINT, under the same time ceiling. They are never proven faster
than the receiver's own time, and no RECV can name them (a v2 record commits to no receiver).

## 5. Settling a SEND -- one RECV or one REFUND, never both

The SEND's chain node is the one authority on its fate: it keeps a claim per SEND, set once, either to the receiver's from or to
the sender's REFUND.

- A RECV is laid at the receiver's chain node. When that node is not the SEND's, it first asks the SEND's node to claim the SEND
  for it: POST /tx-claim {"rec": the RECV} at the sibling; the sibling verifies the RECV with the core, checks the commitment and
  sets the claim to the receiver's from when the SEND is unclaimed and its own clock is before S.at + 86400, or answers ok when the
  claim already names the same from. Only then is the RECV taken. A claim names a key, not a record, so a RECV refused for a moved
  head is laid again on the new head and claims the same SEND again.
- A REFUND is laid at the sender's chain node, the authority itself: taken only when its clock is at S.at + 86400 or later and the
  SEND is unclaimed; the claim becomes the REFUND.
- One clock decides: the authority's. A RECV that reaches it in time wins; after the day only the sender's REFUND can settle it.

POST /tx-settle {"send"} -- 200 {"state": "open" or "recv" or "refund", "by", "at"} at the SEND's chain node.

Every record a node takes is first verified by the core -- the C door mt_verify of mt-bindings, the function the phone links --
loaded by the node from the library built out of the protocol's crates; a node without it takes no coin record (503). The rows laid
before this check are verified once at the node's start, and a forged one never stands on a chain.

## 6. The root and the holders

Checkpoints are the node seconds T that are multiples of 600. The root of T:

  root(T) = SHA-256(ASCII mt-coin-root-v1, 15 bytes, then for every key with a chain record of at at most T, in ascending order of
  from: from (32 bytes), the id of its last record with at at most T (32 bytes), the number of its chain records with at at most T,
  u64 little-endian)

POST /tx-root {"at": T} -- 200 {"at", "root", "keys", "records"}; without at, the newest checkpoint at least 300 seconds old (the
replicas' margin).

A holder -- a node or a phone that keeps a replica -- signs a root it computed itself with its seed's coin key:

  message = ASCII mt-coin-root-sig-v1 (19 bytes), then T as u64 little-endian, then root (32 bytes)

POST /tx-root-sign {"pk": base64, "at": T, "root": hex, "sig": base64} -- the node verifies with the core, keeps only the last
signature of each holder (one row a key, I-14), and only of a key that has a chain on the network.
POST /tx-quorum {"at": T} -- 200 {"at", "root", "holders": [{"from", "root", "pk", "sig", "at"}]}.

The Explorer reads both nodes' roots of the same T, verifies every holder's signature in the browser, and shows how many nodes and
how many phones signed the same root -- the global quorum -- and, for a key, the fold of its whole chain -- the local proof.

Phones keep a replica only with the person's consent to keep a copy (the keeping of MTKeeping, «a copy at the correspondents»), and
then the whole network's chains, not only their correspondents': a holder's signature must not say whose chains it cares about.

## 7. Limits (I-14, I-15)

- A record is taken only by a proven right: SEND on a positive proven balance, MINT on time already passed, RECV on an existing
  unclaimed SEND, REFUND on its own unclaimed SEND after a day, GENESIS once per key. A fresh key's only free record is its GENESIS
  of 0; births are counted node-wide (TX_BIRTHS_PM a minute).
- A key lays at most TX_KEY_PM records a minute and TX_KEY_PD a day at its chain node; a MINT covers at least 60 seconds.
- The node's pace table (by address, and the node's whole minute) never empties itself whole -- a fresh caller key once bought a
  clean slate -- and refuses a key never seen once four times its ceiling of live rows stands. A key's own pace is counted in the log
  itself, so no fresh name escapes it.
- The log's budget TX_BUDGET: births stop at three quarters of it, every other record at the whole (507). A transfer is never
  pruned (I-2): the chain is the proof.

## 8. Compatibility and the cut

- v1 (builds 2190 and older): the node takes no new v1 record from TX_V1_CUT (13.10.2026 00:00 MSK) -- 400 «retired». The older
  build's send then hears a refusal from every door and keeps the coins («not sent», nothing debited). A receiver on 2191 or later
  still finds every v1 record laid before the cut by its pair tag and credits it once, as before.
- v2 (build 2191, and a v3 phone that does not yet know its receiver's from): taken, chain-ordered and signature-checked until
  TX_V2_CUT (20.10.2026 00:00 MSK), then 400 «retired» and the coins stay with the sender. On a chain that holds a v3 record it spends
  as a SEND (section 3); its receiver's chain carries it by a MINT (section 4).
- v3 (build 2192 and later): a 2191 phone reads a v3 SEND as no record (its reader knows versions 1 and 2), so it credits nothing;
  no RECV comes, and after a day the sender's REFUND gives the coins back -- the coins did not leave.
- The head of a key may be a v2 record; the first v3 record of that key stands on it.

## 9. Test vectors

Every vector below is checked by the node's own run (coin-node-check.mjs, kept with the node's code, which is not public) against the node's
code and the web core, and by tools/explorer-check.mjs against the Explorer's code (V1 and V3 against the page's own exported
functions). Each names the wrong implementation it refuses.

- V1 the receiver's commitment. from = the 32 bytes 0x00, 0x01 .. 0x1f; blind = the 32 bytes 0x20 .. 0x3f.
  to = SHA-256(mt-coin-to, from, blind) = b08fb9c67b693f0cfe394de25474d2b79ae990a87a2f6a24bb7727560a8d88a4.
  Refuses: the blind hashed before from (8d9016dfed6ea8b2c6c03daaf943afe73dfc25ed46a4bc9049bf5afe15de9c3f), or the hex text hashed
  instead of the bytes.
- V2 the blind. secret = the 32 bytes 0x40 .. 0x5f; nonce = the 16 bytes 0x60 .. 0x6f.
  blind = HMAC-SHA-256(key = secret, message = mt-coin-blind, nonce) = 530efcc186fe26cb48fb4023c7a7e884c94516d14e62feaa06e81c76f89d7be4.
  Refuses: the key and the message swapped (c9791acc18552bdd84114470faa6aa038af2f0ab245b4bfdb0ea9cd2e93ceb16).
- V3 the time a key may mint, A(x): 1:2 2:4 3:6 4:10 7:22 8:28 15:70 16:78 8191:180230 8192:180256 8193:180282 86400:2213664.
  Refuses: a level of bit_length(s) without the minus one (A(2) would be 6), a level rounded up (A(3) would be 8), the music's factor
  left out (every value halved), seconds counted from 0 (A(1) would be 4), and a ceiling other than 13 at the 8192nd second.
- V4 the root. Three keys, from_c = SHA-256(ASCII «root key c») for c in a, b, c, laid in the order a, c, b; key a has 3 records,
  c has 2, b has 1; record i of key c is SHA-256(ASCII «root head c i»), at 100 + i; T = 1000.
  root = 4db30532f47a38696a81aa103cabea16ecf6034934cf26decbb9e78ad848b5e1.
  Refuses: the keys taken in the order laid with the count big-endian (b5cb0949009d6d9f9c34d20f5e719de913930ba61afd4e96835a1575efea07a6);
  the node's run also refuses the keys in descending order.
- V5 a SEND and its RECV, signed by the web core (deterministic ML-DSA-65). S = the key of seed SHA-256(ASCII «seed v3 vector S»), R =
  the key of seed SHA-256(ASCII «seed v3 vector R»). The SEND: amount 1234, birth 1760300000000, nonce = SHA-256(«nonce v3 S»)[0:16],
  prev zero, pair = SHA-256(«pair v3 S»), to = SHA-256(mt-coin-to, from of R, blind), blind = HMAC-SHA-256(SHA-256(«secret v3 S»),
  mt-coin-blind, nonce). Its signed message hashes to cf00d783cfb5364e6c00afdaaeb203b32635b31d6894650f3fb68d7b3c96e3e5 and its id is
  2dd4cf64695222b31d4cd0c799a1f21888aaacb83bab7febfce055f6b7da46fa. The RECV of R: amount 1234, nonce = SHA-256(«nonce v3 R»)[0:16],
  to = the blind, ref = the SEND's id; id ce5a492c063c71435ccb9582e3662c1d876310fe04d2846d73f7d07bd0236646.
  Refuses: a signature over v2's prefix or over bytes 0..2053 (neither verifies), any field at another offset (another id), and a
  RECV whose blind does not open the SEND's to for R's from.
- V6 a v2 record on an opened chain. Key S: GENESIS 1000 declared, MINT 500, SEND 1200 (dec 700), then a v2 record of 50 on S's
  head: taken, S's state proven 0, declared 250; a v2 record of 251 next: 409 «overspend». In the Explorer's run: GENESIS 0, MINT 1000,
  SEND 100, a v2 record of 50 -- balance 850. Refuses: a v2 record that moves nothing on an opened chain (the balance would stay 900,
  the 2193 send of 11:59:58), and a v2 record taken above the chain's balance.
- V7 the graph (section 10). Key S: GENESIS 0, MINT 1000 over 120 seconds, SEND 100 to R, the v2 record of V6; key R: GENESIS 0, the
  RECV of that SEND. R's graph: one edge, «received 100 from» S's address, and under it S's one source, its MINT of 1000 over 120
  seconds with no parent; S's graph: its MINT alone. Refuses: an edge drawn from a SEND before a RECV opened it (S's graph would list
  its sends or name R), a parent drawn for a MINT or a GENESIS, and the receiver of a v2 record named anywhere.

## 10. The graph of the coins' appearance -- the anonymous owner

The author's words 12.10.2026 11:5x MSK: «the fact of minting creates the graph of the coin's appearance, as said here of the
anonymous owner» (the financial layer's document, «Montana Financial Layer and Anonymous Owner» 1.0.0, sections 2.5, 3.2, 5).

The graph is read from the records alone, with no new field and no new door: the Explorer reads a key's chain (POST /tx-chain), folds
it, and draws its sources --

- a MINT: coins born of the key's own time (section 4), a source with no parent. No record of any kind names who made a key: a key's
  first record is its own GENESIS, signed by itself, and its first coins are minted, so a key has no funding trail and no lineage
  (the document's 2.5 and 5.2: «born with coins from emission -- no top-up, no trail back»);
- a GENESIS above 0: the wallet's balance announced when the chain opened, a source with no parent, labelled as announced;
- a RECV: an edge to the key of the SEND it opens (the SEND's from), with the amount and its announced part; the sender's own sources
  before that SEND are drawn under it in turn, a few keys deep;
- a REFUND: the key's own SEND come back.

A SEND is an edge only once its RECV opened the blind (section 1); before that the receiver is not drawn, and a v2 record, which
commits to no receiver, never draws one. Every key is shown only by its from tag: no name, no phone, no conversation stands in any
record (the pair's tag says that a pair paid, never who the pair is).

What of the document maps onto v3, and what does not:

- Mapped: birth without a parent and without a top-up (above); a payment from one key only -- a SEND is signed by one key and spends
  that key's chain, so no record co-spends two keys (the document's 6.4); a balance is a key's own -- the app keeps no summed balance
  across seeds, and a node or the Explorer never adds keys together (5.3 c); a transfer to one's own other account is never an
  ordinary act -- Montana and Business say under it that it links the two accounts publicly forever and ask before it leaves (5.3 a,
  b); the forbidden promises of 5.5 («anonymous payments», «untraceable transfers», «full anonymity») are said nowhere.
- Different in v3: a key's receiver stays hidden until the RECV opens the blind -- the document's Send names its destination at once;
  in v3 the edge appears when the receiver takes it, and then it is public forever (I-2). A seed has one coin key today, drawn by
  role (HKDF-Expand of the master seed under «mt-coin-tx»); the document's ladder of identities (5.1: HKDF-SHA-256 with 32 zero bytes
  of salt over entropy_32, info «mt-account-key» and i as u32 little-endian) is a separate set of keys. Moving a chain's coins from
  today's key to identity 0 would be a SEND between two identities of one owner -- the very link 5.3 forbids -- so today's key stays
  its owner's first identity, and the ladder opens only new ones.
- Not in v3, waiting for the protocol's new Genesis (named, not hidden): the block lattice with balance_after, Receivable and
  mint_source_hash (2, 3.2), the emission's equal share of the live set L(W) and dust_carry (3.1), PresenceWitness (3.3), the trust
  flow and its dilution (4). v3's MINT is bounded by a key's own time, not by witnessed presence (section 11).

## 11. What v3 does not close (named, not hidden)

- Minting is a phone's claim bounded by time, not a proof of a call or a letter: the node cannot see either. Two seeds of one
  person can each mint at the full rate. A call's MINT carrying the second side's signature would close nothing the time bound
  does not: the wallet's pull (MTWalletPull.mintSecond) and the seconds away (MTPersonChain.came) mint with no second side at the
  same rate, so a dishonest phone mints under those names instead. Sybil keys (many seeds) are many people to the node.
- GENESIS announces the books' balances from before v3 and proves none of them: they are spent as declared and stay declared in
  every chain they reach, so a proven balance is never mixed with them. Any key may announce up to TX_GENESIS_CAP before the v2 cut
  (a phone's claim, as before v3); many keys are many announcements -- the cap bounds each, the label names all of them.
- The time allowance cuts what the app's rules mint above 26 a second averaged over the interval (a long run of bubbles of the
  Money Flow at a high level): that part stays in the book and is not proven.
- Two chain nodes order the chains; if one is down, its keys' records wait (their coins stay), and a RECV of a SEND whose node is
  down waits for its claim. A node that loses its disk is restored from its sibling's replica.
- A holder's signature proves what that holder folded, not that it is independent: one person can run many holders. The quorum
  shows the count; the Explorer's own fold is the proof a visitor needs no one else for.
- The chain table (montana.quest, montana.xxx) stands in three places -- the app, the Explorer and the node -- each checked against
  this document by its own run.
