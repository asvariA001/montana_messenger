1.0 (2339) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2339), from source commit `60a54c0893a4ab9bda656eda3e37509e094abaff` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Cooler phone: a quick pass through the feed on the way to another tab no longer wakes the phones of everyone in it, and a message arriving while the app is open no longer makes it read the whole mailbox again.
- Faster start and less heat: the chat history is no longer rewritten in full (several megabytes) at every launch, an incoming name no longer rewrites the names list on the main thread, and the clean-up of old stored files runs once a minute instead of once per write.
- Steadier connection, less heat: the app finds the network's doors once and holds them; it no longer starts a new search for every request while one is under way (seventeen searches in five seconds were measured), a change of network or VPN starts one fresh search on the new network, and the first mailbox read at launch waits for the first door that answers instead of giving up.
- Less heat while the feed comes in: your wall is saved in the background, not on the main thread, and in the order it changed.
- Cooler phone between wakes: the app reads the whole mailbox (four days) at most once a minute and, between, only today's letters -- one page instead of four. It still reads everything at launch, when you pull the list down, when the network says a letter waits, and when a new chat opens.

Security and anonymity
----------------------

- No new stored value, address, server, key leaving the device or network road. The feed now sends fewer wall asks and a push into an open app reads the mailbox through its existing gate; stored files no key names any more are still removed no sooner than a minute after their key moved on.
- No new address, server or stored secret. The door map stores the same machine list as before, now in one fixed key order; a network change only re-asks the doors already known.
- No new stored value, address, server or key leaving the device. The wall is encoded, sealed and stored on one serial background queue (montana.board.writes) in the same keys and under the same seal as before; clearing, parking or forgetting a person waits for the writes on their way first.
- No new stored value, address, server or key. A pickup between the whole reads asks the same labels for fewer days (MTNodeWire.freshDays, always inside the box term); a letter sealed under an older day by a sender whose clock is off by more than an hour waits for the next whole read, at most a minute while the app runs. The notification extension still reads the whole term.
