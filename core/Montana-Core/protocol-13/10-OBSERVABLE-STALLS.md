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
