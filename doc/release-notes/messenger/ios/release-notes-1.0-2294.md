1.0 (2294) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2294), from source commit `55a032bb8d74a7b03ae344ab07074d674f9c0866` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Montana no longer reaches a server that left the network: the old Frankfurt machine now belongs to someone else, and the phone stopped connecting to it.

Security and anonymity
----------------------

- The phone's core node dialed four genesis machines; one address now belongs to a third party and would have learned the phone's network address. It is removed; the phone reaches Lauterbourg, Moscow and Amsterdam only. Nothing new is sent.
