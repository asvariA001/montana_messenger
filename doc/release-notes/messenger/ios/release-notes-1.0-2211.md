1.0 (2211) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2211), from source commit `bb7ab8c5e1e8f74e74b3b187e73fea9615613e9b` of 2026-10-15. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Montana is no longer closed in the background when Do Not Disturb or a Focus holds an incoming call and the caller keeps calling: every call wake-up is now answered to iOS unless a Montana call is already on the screen.
- When iOS ends the app, the diagnostics now name the real reason (for example an unanswered call wake-up) instead of calling it a normal background end.
- Messages waiting for delivery no longer rewrite the whole outgoing queue on every retry: much less disk writing and battery use.
