1.0 (2337) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2337), from source commit `8b9b7d3e05c44cc8c94ed91e1a4d01b6c8e897e4` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Cooler phone: a quick pass through the feed on the way to another tab no longer wakes the phones of everyone in it, and a message arriving while the app is open no longer makes it read the whole mailbox again.
- Faster start and less heat: the chat history is no longer rewritten in full (several megabytes) at every launch, an incoming name no longer rewrites the names list on the main thread, and the clean-up of old stored files runs once a minute instead of once per write.

Security and anonymity
----------------------

- No new stored value, address, server, key leaving the device or network road. The feed now sends fewer wall asks and a push into an open app reads the mailbox through its existing gate; stored files no key names any more are still removed no sooner than a minute after their key moved on.
