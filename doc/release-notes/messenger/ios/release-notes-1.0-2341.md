1.0 (2341) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2341), from source commit `82054e1d5dc84f99e141b6cf9b1e7bfc54c5cbd0` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Chats are fresh sooner after launch: the app reads its mailbox the moment it starts instead of waiting a fixed two and a half seconds, and a read that comes before the network's doors have answered -- or before the account's reference is ready -- is kept and goes the moment they are, never dropped.

Security and anonymity
----------------------

- No new stored value, address, server, key leaving the device or network road. The same mailbox read starts earlier and repeats only once the doors already in use have answered; nothing else changed.
