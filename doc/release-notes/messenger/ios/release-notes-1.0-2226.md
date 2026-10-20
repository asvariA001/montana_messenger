1.0 (2226) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2226), from source commit `fda6a42218f49ee26c6f7ab3a583932bb14a4625` of 2026-10-17. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Notifications: a letter on a locked phone shows one banner with the sender's name and sounds once. The banner is handed to the system before any other work of the notification extension, so a bare push no longer replaces it; a letter is announced through one node, not two; the app and the extension share one record of what was shown, so a letter does not ring twice while the app is open.
