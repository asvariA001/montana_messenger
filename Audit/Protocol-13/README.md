# Protocol closure: 13 items

<!-- status:start -->
Status recorded on 2026-10-16: **0 of 13 acceptance criteria closed**. Individual reports follow the requirement order. Earlier partial repairs do not close an item by themselves, and the requirements apply to the two core generations separately: a result for `Montana-Core` does not certify `Code`.

The work on them stands in [source](source/): `docs/` and `Montana-Core/` of commit `1f5ca38a60d8cb6780d8f015a74af8a5c43ed21a` of the core repository, branch `fix/session39-window-relation`, staged by the publisher every commit of that branch goes through, with the status file `Montana-Core/PROTOCOL-13.json` of the same commit, which the table below is written from. An item reads closed only where its report names the implementation revision, the exact test command and its result; this table claims no independent external audit.

**Reproducible build.** `sh Audit/Protocol-13/reproduce.sh` in a clone of this repository builds the node of `source/Montana-Core` with the locked dependencies and the paths of the clone and of the home folder replaced by fixed names, and prints its SHA-256. On commit `9a5d5656e6d4` of the core repository it printed `32897c85f8e13e6fd6e0fd27e9e9507e150311f8f6a9c2261e24a7d7f505e2bf` in two fresh clones on macOS 26.7 on arm64 (aarch64-apple-darwin) with rustc 1.92.0 (ded5c06cf 2025-12-08), LLVM 21.1.7; a build with another toolchain or for another platform is another binary, and the digest holds for the commit it names.

| Item | Required result | Status and remaining work |
|---|---|---|
| [1](01-WINDOW-PROOF.md) | A complete window proof and verifier | **Open.** Active, sessions 39 to 47. In the branch: the window closes through one verifying door, the roots of state are defined and a device walks from an anchor to the head (2add1f6); the ballot proves a count of votes and hides the standing it was drawn from, a payer's frame applies with its round, the draw's threshold is the width and the ticket orders runners, a right needs the machine's own part in the cement (cdcbd7a, 6ae1a3b, 9f22057, 2538b6a). Open: agreement on what a window decided. The author chose its construction on 2026-10-16 (AGREEMENT.md, section 6: the roll of a proposal is one body whose root stands inside its name); its specification text, its code and the test of four machines at a quorum of three are not written. A run on six hosts closed window 0 on two names, and the memory of a stalled window grew without a bound. |
| [2](02-CANONICAL-PROPOSAL.md) | Close only a verified canonical Proposal | **Open.** A participant count may trigger an attempt, but must not advance the closed head or authorize issuance. Recompute the beacon and archive the verified Proposal and its actual identifier. |
| [3](03-AUTHORIZED-ISSUANCE.md) | Bind redemption to authorized issuance | **Open.** Bind the window, recipient, amount and one-time redemption. Reject excess value, invented rights and repeated redemption at the verifying node. |
| [4](04-ADMISSION-ORIGIN.md) | Bind admission witnesses to one state | **Open.** The identity record, machine key, operator secret and public values must share an authenticated origin. Reject mixed witnesses. |
| [5](05-CONTINUITY-AND-REJOIN.md) | Prove continuity and return after a partition | **Open.** Enforce bitmap shift, period boundaries and required participation inside admission. Replaying other machines' records does not create one's own participation. |
| [6](06-CLOCKS-AND-BOOTSTRAP.md) | Remove clock authority and implicit private bootstrap | **Open.** The same protocol state must have the same validity under changed clocks or machine speed in both generations. Test settings must not silently become network settings. |
| [7](07-LEGACY-LOTTERY.md) | Make the earlier core's lottery deterministic | **Open.** Use historical thresholds and weights, correct epoch boundaries and complete reveals; reject conflicting proposals and unannounced private bootstrap. |
| [8](08-ATOMIC-WINDOW.md) | Apply and persist the entire window atomically | **Open.** Tables, proof, Proposal archive and head must recover together after rejection, crash or replay, without extra issuance. Partial storage repairs do not establish this transaction. |
| [9](09-AUTHENTICATED-JOIN.md) | Authenticate first connection and fast sync | **Open.** A fresh node must authenticate history from the network's trusted beginning or an authenticated checkpoint. A server's own advertised root does not authenticate its snapshot. |
| [10](10-OBSERVABLE-STALLS.md) | Expose a stalled protocol | **Open.** Distinguish insufficient quorum, missing data, invalid proof and unavailable diagnostics through the used network path. Timeouts must not authorize a close. |
| [11](11-ANONYMITY.md) | Protect node identity and origin | **Open.** Verify handshake identities, protected entry points, direct origin access, DNS, logs, public history, resource limits and replays. Total anonymity is a design requirement, not a verified property of this snapshot. |
| [12](12-SOURCE-AND-CLAIMS.md) | Reconcile code, specification, audits and publication | **Open.** Each closed finding needs an implementation revision and matching evidence. Changed proof identifiers require an explicit migration. Whole-repository consistency and historical disclosures remain under review. |
| [13](13-NETWORK-REPRODUCTION.md) | Reproduce distributed finality and joining | **Open.** Multiple weighted nodes must close, lose quorum, recover, partition, rejoin and restart with canonical artifacts. The authorized self-run on a separate server must preserve canonical artifacts; it is not independent external review. |
<!-- status:end -->

## Evidence rules

Each completion report must identify the statement being checked, source revision, exact test
command, positive case, adversarial counterexamples and observed result. A partial test list is
not a full workspace pass. A clean build, a signature, matching roots, a counter or a screenshot
is not by itself a proof of finality or an issuance limit.

A run by the implementing session, on the project's own machines or on a separate server, is
self-verification, not an independent external audit. Reports omit private host details,
credentials, recovery material and personal logs.

## Where this folder comes from

The publisher writes this folder from one commit of the core repository: this page and the
reports from `Montana-Core/protocol-13/`, the table above from `Montana-Core/PROTOCOL-13.json`, and
[source](source/) from `docs/` and `Montana-Core/` of the same commit. A session that moves an item
changes its report, its row and its work in one commit. A record that a later report supersedes
leaves this folder and stays in the history of the repository.
