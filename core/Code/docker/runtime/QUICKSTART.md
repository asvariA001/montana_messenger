# Earlier node implementation — Docker

This image builds `Code/crates/montana-node`, the earlier implementation. The
reference implementation of the current document set is in `Montana-Core`.
A run of this image establishes behaviour of this implementation; comparison
with the current set also requires the checks in `SPEC_DEVIATIONS.md` and
`Montana-Core/AUDIT.md`.

## Join an operator's cohort

```bash
git clone https://github.com/efir369999/montana_messenger.git
cd montana_messenger/core/Code/docker/runtime
```

Obtain a manifest from the operator of the cohort you intend to review. Mount
it using the manifest volume in `docker-compose.yml`, enable
`MONTANA_GENESIS_MANIFEST`, and pin its digest with `MONTANA_MANIFEST_SHA256`.
Then run:

```bash
docker compose up -d --build
```

Cohort mode is the default. A missing manifest stops startup before identity
creation. Malformed peer identities or addresses stop the network startup;
loss of the network worker returns an error to the consensus loop.
Reachability by itself establishes no quorum or finality.

A fresh node also needs authenticated chain history or a trusted checkpoint.
This source refuses a fast-sync snapshot whose anchor is known only from its
sender's unverified proposal. A discovery manifest authenticates peer addresses;
it does not authenticate that peer's state. Secure first-join recovery remains
open in `Code/docs/SPEC_DEVIATIONS.md`, DEV-015.

`Code/scripts/genesis-manifest.json` deliberately contains an empty peer set
and is refused by the node. Deployment identities and endpoints belong in the
operator's private manifest. Publishing an origin DNS name exposes the origin
when that name resolves directly to it; replacing an address with such a name
provides no anonymity.

## Run an isolated local node

Choose this mode explicitly:

```bash
MONTANA_MODE=isolated docker compose up -d --build
```

Use a separate data volume from any cohort identity. An isolated chain runs
its own genesis and proves nothing about participation in an existing cohort.
A fresh node starts as an observer; this command alone is no demonstration of
window production or candidate admission. A mounted cohort manifest or manifest pin conflicts with isolated mode and
stops startup.

## Observe the node

```bash
docker exec montana-node /usr/local/bin/montana-node status --data-dir /var/lib/montana
docker compose logs -f
```

`status` reads local state. Container health and a moving local height are
separate from agreement with another verifier on the same cemented proposal.
The entrypoint sends no automatic reports to an explorer or other service.

## Keep the identity private

On the first successful initialization, the entrypoint stores the recovery
output in `/var/lib/montana/mnemonic.txt`, readable only by its owner. Recovery
words go through standard input when supplied, and initialization runs without
a controlling terminal; its output goes to that private file. An existing
recovery file is preserved. Read it only from a private operator terminal:

```bash
docker exec montana-node cat /var/lib/montana/mnemonic.txt
```

For recovery, mount a secret file and set `MONTANA_MNEMONIC_FILE` to its path.
`MONTANA_MNEMONIC` remains accepted for existing operators, but container
environment configuration may expose its value to administrators; a mounted
secret avoids that copy. Never include recovery material in issues or logs.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `MONTANA_MODE` | `cohort` | `cohort` or explicit `isolated` |
| `MONTANA_DATA_DIR` | `/var/lib/montana` | private persistent data directory |
| `MONTANA_LISTEN` | `/ip4/0.0.0.0/tcp/8444` | listen address in cohort mode |
| `MONTANA_GENESIS_MANIFEST` | `/etc/montana/genesis-manifest.json` | private operator manifest |
| `MONTANA_MANIFEST_SHA256` | unset | expected manifest digest |
| `MONTANA_MNEMONIC_FILE` | unset | mounted recovery secret, read through stdin |
| `MONTANA_MNEMONIC` | unset | compatibility recovery source |
| `MONTANA_D_TEST_OVERRIDE` | unset | explicit test difficulty override |

The image builds from `core/`, which includes the wordlist:

```bash
cd montana_messenger/core
docker build -t montana-node:local -f Code/docker/runtime/Dockerfile.node .
```
