# 10. Make protocol stalls observable

**Status: open through the complete operator/network path.**

The earlier core has local head, accumulated-share, threshold and progress-age
diagnostics. The native candidate reports unreconciled beacon cement and returns
without advancing when its waiting budget expires. These are useful partial
observations, not a completed status service for either generation.

The separate-host three-node diagnostic exposed another distinction: thousands
of advancing rounds can coexist with an unchanged window and insufficient heavy
participants. Reporting a growing round alone would hide this stall. A pending
historical-read scheduling repair addresses the observed starvation; it does
not establish finality.

Closure requires the actually used network and operator interfaces to
distinguish insufficient quorum, missing authenticated data, proof rejection
and unavailable diagnostics. A reachable but stalled peer must remain visibly
stalled. Missing status must not display success. Time since progress is an
observation only and cannot authorize a successor or lower the quorum.

The website already distinguishes checkpoint reconstruction and signatures from
finality and authorized issuance. That correction does not supply the missing
core diagnostic service or certify the nodes behind a checkpoint.

## Refused deposits and the observed shortfall

A public deposit that fails verification cannot establish why a quorum or its
supporting data is absent. The node keeps `no data` or `no quorum` as the
observed shortfall and reports the cumulative process-local refusal count beside
it (`refused N`). That count includes rejected proof-bearing objects; it neither
attributes the shortfall to them nor identifies a depositor. A dead diagnostic
door still overrides the observation with `no diagnostics`.

The regression `a_stall_names_its_cause` deposits an attestation with a turned
signature byte beside valid attestations while closing pledges are withheld.
Its required verdict stays `no quorum`, with a nonzero refusal count. A separate
missing-data fixture makes the same check for `no data`. Neither observation
enters a proposal or a state root, and neither authorizes a close.

## No verdict `proof refused`

After the refusal counts stopped selecting the verdict, nothing produced
`proof refused`, and an operator verdict a node can never give is dead surface.
It is removed, not given a new source. Every refusal the node counts is of an
object that arrived at a public point, which anyone may deposit; the node's own
objects that it cannot build end at once as `Unprovable` and never reach a
patience exit. Consensus names what monitoring reports
(`Montana Consensus.md`, "The liveness price"): an electorate that does not
return, data that is not delivered, a missing eligible proposer or verified
certificate. It names no refused proof as a condition. Canon keeps the reasons
of refusal in the machine's own account ("Invariants of the messages of the
wire"), which is where they stay: as `refused N` beside the verdict and in the
per-reason counts of the account line.

The verdicts are therefore `no quorum` (exit 1), `no data` (exit 2) and
`no diagnostics` (exit 4). Exit 3 is retired and given to no other verdict.
Proof rejection is still distinguishable, as the count beside the verdict, but
never as the cause of a shortfall.
