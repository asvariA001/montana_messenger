Contributing to Montana Core
============================

The Montana project operates an open contributor model: anyone is welcome to contribute to its development in the form of
peer review, testing and proposed changes. This document explains the practical process and the rules a change has to keep.

Getting Started
---------------

New contributors are welcome and needed.

In-depth review and testing are the most effective way to start. The audit package, [`AUDIT.md`](AUDIT.md), names the scope,
the builds, the test vectors and the catalogue of open findings; [`Audit/`](Audit/) holds the source-pinned reports of the
thirteen protocol closure requirements, each with its open criteria. A finding that closes one of them, or shows that a
report is wrong, is the most valuable contribution there is.

Before you start, build the tree and run its tests as the [README](README.md#build-from-source) describes: the core's
`cargo test --workspace --release` in `core/Montana-Core` and in `core/Code`, then the applications.

How This Repository Is Made
---------------------------

Every folder of this repository is the tree of one commit of the project, staged by its build road as it is built; the
commit and the git tree of each folder are in [`RELEASES.md`](RELEASES.md). Before every build the road writes the build's
entry in [`CHANGELOG.md`](CHANGELOG.md) and its notes in [`doc/release-notes`](doc/release-notes): what the build changes,
what it changes for security and anonymity, and its source commit. A build whose entry is not on GitHub is not built.

Development happens on the project's own main line, so a pull request here is not merged as it stands. Open an issue or a
pull request to propose a change: it is read, and a change the project takes reaches this repository with the next build,
named in its changelog entry.

Rules Every Change Keeps
------------------------

- **Post-quantum only.** Montana's own security rests on ML-DSA (FIPS 204), ML-KEM (FIPS 203) and SHA-256. A classical
  primitive appears only where an external standard demands it to be admitted at all (TLS, WebRTC), and is never counted as
  Montana's protection.
- **The specification is the source of truth.** The normative specification is in [`core/docs`](core/docs); the core
  implements it byte for byte. A change of behaviour in the core comes with the change of the specification that asks for it.
- **Deterministic consensus.** Integer arithmetic only, explicit little-endian layout, ordered maps, no floating point, and no
  clock other than the TimeChain's in consensus state. Each formula carries test vectors, and a vector on a degenerate input
  stands beside one that tells a wrong order or direction apart.
- **Every device is its own node.** Between two phones there is no server, relay or third party that the conversation needs.
- **Privacy has no degrees.** Nothing may reveal who talks to whom, or when, to anyone other than the two parties. A person is
  twenty-four words held on the device: no phone number, no e-mail address, no account on a server.
- **No secret in a log.** Not a key, a seed, a word of the recovery phrase or a message, in any build.

Communication
-------------

Questions, reports and proposals go into the issues of this repository, with the build (Settings, About), the device and
system version and the exact time, as the [README](README.md#reports) asks. Never paste a recovery phrase, another person's
address or message content into an issue.

A weakness is reported by e-mail and not in an issue: see [`SECURITY.md`](SECURITY.md).
