1.0 (2227) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2227), from source commit `bd0319bf90cb7ca63eca553feacfc3e57aed2519` of 2026-10-17. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Wallet: the whole balance is available at once. Coins earned while the app was away go to your TimeChain as soon as you come back, and time you have not minted yet is never lost: the chain weighs all your mints together against your key's whole time since its birth, not interval by interval. A phone that already holds a gap closes it with its next mint.
