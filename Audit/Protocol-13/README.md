# Protocol closure: 13 items

Status recorded on 2026-10-14: **0 of 13 acceptance criteria closed**. Individual reports follow
the requirement order, starting with the complete window statement. Earlier partial repairs do not
close an item by themselves. The requirements below apply to the two core generations
separately; a result for `Montana-Core` does not certify `Code`.

This initial report is pinned to public commit
[`6303fa5fbbb5ad93e4d7dd3dcc699693fb10e002`](https://github.com/efir369999/montana_messenger/tree/6303fa5fbbb5ad93e4d7dd3dcc699693fb10e002).
Its `core` tree is `4ce3d6705224c8c99db0db1710a70bbde3d04361`.
The source revisions of application folders are recorded separately in [RELEASES](../../RELEASES.md).
Private candidate repairs are not represented as repairs present in this public snapshot.
This report publishes no new core build and claims no completed network acceptance run.

| Item | Required result | Status and remaining work |
|---|---|---|
| [1](01-WINDOW-PROOF.md) | A complete window proof and verifier | **Open, active.** Bind authenticated quorum and weights, state transitions, proposer entitlement and authenticated history. The published window circuit proves accumulation arithmetic. |
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

## Session 38: item 1

The [internal review](01-INTERNAL-AUDIT-SESSION-38.md) records 13 open findings
and two narrowly repaired components, with the complete acceptance matrix. Its
[reproduction package](reproduce/session38/README.md) reconstructs the candidate
from a public commit and verifies 206 source hashes. Source repairs in that fixture
are not represented as already present in the published `core` snapshot. The review
is by the implementing session; no independent external audit or closure is claimed.

## Evidence rules

Each completion report must identify the statement being checked, source revision, exact test
command, positive case, adversarial counterexamples and observed result. A partial test list is
not a full workspace pass. A clean build, a signature, matching roots, a counter or a screenshot
is not by itself a proof of finality or an issuance limit.

The current task runs verification on a separate build host. Reports omit private host details,
credentials, recovery material and personal logs. Historical internal reports require review
before publication; this directory does not claim that all of them have been published.
