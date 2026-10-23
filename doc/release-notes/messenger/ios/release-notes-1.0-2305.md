1.0 (2305) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2305), from source commit `1d217f36fc911677645e2d3a59e81ce148de4d8e` of 2026-10-24. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Coins: the wallet's number rises every second again, as it did before, and each second's coin is shown rising. The count is still kept by the network's core.

Security and anonymity
----------------------

- Security: the screen adds only the seconds the chain itself credits, never past the online cut; nothing is minted on the phone. Anonymity is unchanged.
