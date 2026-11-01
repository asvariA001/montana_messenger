1.0 (2336) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2336), from source commit `d039af024dca3f790915c06350ff26227664bbcb` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Faster and cooler start: when nothing in your chat archive has changed since the last launch, the app no longer reads and decrypts the whole archive again at every start -- it checks the size and time of each chat's log and moves on.

Security and anonymity
----------------------

- One new value on this device: mt.archive.face, the SHA-256 of each chat folder's log size and last-write time with the feed's chat names, the pipe count, the asked-row count and the recovery day, kept in the app's own defaults, scoped to the seated seed (SeedScope) and erased with it. It holds no message, key or address in clear and never leaves the device. It is computed only when the archive's keys are present, so an archive that could not be opened is never remembered as read. No new address, server, key leaving the device or network road is added.
