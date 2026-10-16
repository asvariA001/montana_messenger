# Audit reports

The project's audit reports in English. The publisher writes this folder from the project's own repositories and keeps here only what it lists; a record a later report supersedes leaves the folder and stays in the history of this repository.

- [Protocol-13](Protocol-13/README.md): the 13-item protocol review -- finality, issuance, authenticated joining and node privacy -- with its status table, one report for each item and the source of the core commit they speak of.

The repository-wide [audit package](../AUDIT.md) states the scope, the bytes under audit and the open findings of the applications and the client core.

Reports distinguish implementation, a reproduced counterexample, a regression check and a complete acceptance result. A finding reads closed only where its report names the implementation revision, the exact test command and its result; every other finding is open, with what remains named. A run by the implementer, on its own machines or on a separate server, is self-verification, not an independent external audit. Nothing here certifies the protocol or a released application.

The project's internal reports written in Russian stay in its local audit and are not published.
