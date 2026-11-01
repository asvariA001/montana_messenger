1.0 (2332) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2332), from source commit `7fafdbdf66cc0e16f81e556d1fec6afe5bebdb77` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The chats scroll at once after the app opens: the key is no longer derived from your words at every launch -- it is derived once and kept on this device.
- Opening the chats no longer builds the music and the feed pages behind them; a page is built when you look at it, and the music library is gathered off the main thread.
- Less heat: your wall's posts for the shared table are prepared in the background, not while the chats open.

Security and anonymity
----------------------

- The master seed stretched from the recovery words (2^20 iterations) is now kept in the keychain beside the words, under the same class (AfterFirstUnlockThisDeviceOnly: this device only, never synchronized, never in a backup), bound to the SHA-256 of the seated words, and erased with them (clear, a new identity, forgetMasterSeed). It is the same secret as the words in another shape. Every reader asks one owner (MontanaSeed.root); a guard (tools/mt-root-owner-check.py) refuses a second derivation site. No new address, server, key leaving the device or network road is added.
