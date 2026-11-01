1.0 (2338) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2338), from source commit `16a351ace2279489ddacd243e133a6556b7e7c8a` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Cooler phone: a quick pass through the feed on the way to another tab no longer wakes the phones of everyone in it, and a message arriving while the app is open no longer makes it read the whole mailbox again.
- Faster start and less heat: the chat history is no longer rewritten in full (several megabytes) at every launch, an incoming name no longer rewrites the names list on the main thread, and the clean-up of old stored files runs once a minute instead of once per write.
- Steadier connection, less heat: the app finds the network's doors once and holds them; it no longer starts a new search for every request while one is under way (seventeen searches in five seconds were measured), a change of network or VPN starts one fresh search on the new network, and the first mailbox read at launch waits for the first door that answers instead of giving up.

Security and anonymity
----------------------

- No new stored value, address, server, key leaving the device or network road. The feed now sends fewer wall asks and a push into an open app reads the mailbox through its existing gate; stored files no key names any more are still removed no sooner than a minute after their key moved on.
- No new address, server or stored secret. The door map stores the same machine list as before, now in one fixed key order; a network change only re-asks the doors already known.
