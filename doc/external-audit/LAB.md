# Isolated GPA experiment contract

## What runs today

The Rust probes in this directory run against the public client libraries. `traffic.py` runs an
attack baseline over a supplied trace or a synthetic positive control. **There is no runnable
production-equivalent Montana GPA testnet here:** the node-service implementations required by
the applications are excluded from this repository (AUDIT F-4). A stub relay or a simulator must
not be renamed a deployment of Montana. This document specifies the missing delivery and the
procedure an auditor can apply once it exists.

## Required lab delivery

Use an isolated network with synthetic identities/messages and no production accounts. Freeze
all executable inputs in the snapshot manifest plus image/artifact digests. The service owner
must supply exact build/start/stop commands, ports, configuration schema, a topology file and
synthetic key generation; the auditor must reproduce these from a clean environment.

| Role | Required artifact / observation |
|---|---|
| At least 8 sender/recipient pairs | Pinned iOS/Android app and native-library builds; workload driver, deterministic synthetic schedule, foreground/background/locked states |
| Entry/courier and queue host roles, **if the reviewed route actually uses them** | Published implementations/configuration, separate network identities; capture each ingress/egress link; test single-operator and independent-operator scenarios separately |
| Direct device path | Explicitly enabled/disabled modes; capture both ends; record fallback decisions and transitions |
| Naming/bootstrap, wake, DNS and diagnostics | Exact lab-only dependencies, retention/logging policy and capture boundaries; disclose omitted external dependencies |
| Observer | Passive capture on every virtual link, synchronized clock with measured error, direction/length/time only for attack input |
| Ground-truth recorder | Pair/session labels stored separately from observer traces; available for scoring only |
| Impairment controller | Logged loss, delay, jitter, bandwidth limits, partitions and reconnection; distinct from the passive observer |

These are logical roles, not a claim about the deployed hop count. Do not route the experiment
through public Montana infrastructure. The maintainer and auditor must reconcile the topology
with the application call graph before issuing launch instructions.

## Capture and evaluation procedure

1. Inventory source trees, native-library inputs, executable digests, host/OS/toolchain versions,
   service configuration, seeds, experiment IDs and expected route. Use lab-only identifiers in
   public artifacts. Archive raw captures privately with checksums and access/retention rules.
2. Pre-register workloads, observation durations, simultaneous-pair counts, idle/background
   traffic, churn and network impairment matrix. Repeat independent sessions; randomize run order.
   Include text, media, calls, receipts, typing, reconnect, offline retrieval and phone locking.
3. Capture before workload start and after completion on every relevant link. Preserve packet
   lengths/directions/timestamps and clock-offset estimates. Keep capture gaps distinct from zero
   traffic. A failed capture invalidates that trial rather than improving the result.
4. Export **byte counts per common time bin**, separately per direction and comparable observer
   vantage point. All candidates use the same bin origin, width and duration. Zero-fill only
   observed empty bins. The JSON below has no unit inference or resampling: the exporter owns
   alignment and records its version/parameters in a separate experiment manifest.
5. Select bin widths, lag limits and attack parameters on training/validation sessions; freeze
   them before held-out evaluation. Keep all realistic candidate recipients, including decoys.
   Ground truth never chooses or removes candidates or supplies a scoring feature.
6. Run the baseline below and stronger auditor-selected attacks (timing, volume, intersection
   across sessions). Save input/result checksums. Report top-k rank, top-1 success/advantage,
   confidence intervals, candidate count, null and positive controls, and overhead (bytes,
   latency, energy, failure/delivery rates). Use session-level resampling or another justified
   analysis of independent units; do not treat overlapping bins as independent experiments.
7. Publish scope, sanitized reproducible artifacts, deviations, limitations, negative results and
   unresolved findings. Fixes require a new pinned run and external retest. A single unsuccessful
   attacker or a single p-value cannot close Protocol-13 item 11.

## Baseline input and interpretation

`traffic.py INPUT.json --max-lag 3` consumes one ingress vector and a candidate map. Example
shape (each real vector needs at least 16 bins):

```json
{"ingress": [0, 80, 20, 0], "candidates": {"flow-a": [0, 0, 80, 20], "flow-b": [40, 0, 20, 0]}, "truth": "flow-a"}
```

The four-bin illustration above is intentionally too short to score. Generate a runnable
128-bin positive control with `python3 doc/external-audit/traffic.py --demo`. The demo models
**no protection**, with one flow delayed by three bins among eight candidates, not Montana.

The baseline maximizes signed Pearson correlation over integer lags in `[-max_lag, +max_lag]`,
without circular wraparound. Positive lag means the egress follows the ingress. Every lag must
retain at least 16 overlapping bins. The JSON records each best lag, overlap and score. Count
values must be integers between 0 and `2**53 - 1`; negative/fractional/non-finite values, unequal
windows, duplicate JSON fields, missing truth and fewer than two candidates are rejected with exit 2.

For tied candidates, the true flow receives a rank interval and fractional top-1 credit under
uniform tie breaking, rather than an alphabetical winner. `uniform_random_top1 = 1/N` is only
the uniform-candidate chance reference, not an empirically estimated null or a base-rate model.
A constant ingress or any wholly unscorable candidate makes the result **inconclusive**; the
candidate is never silently removed. No undefined correlation is mapped to a successful defense.

The tool computes no p-value, confidence interval, power, production security verdict or
cross-session aggregate. Its output for a synthetic control shows only that this instrument can
recognize that control. Maximizing over lags increases chance correlations; a later statistical
null must repeat the entire lag search. Temporal autocorrelation and candidate dependence also
need to be preserved by that null. Negative/constant controls validate failure handling; they do
not model a secure deployed protocol. A sophisticated adversary may succeed where this baseline
fails.
