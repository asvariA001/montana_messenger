1.0 (2292) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2292), from source commit `679efed3dd0545c6d97dc53238101de36e2edef5` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- A background wake no longer keeps Montana busy: the copy of the coin chains is checked at most once in ten minutes even when a notification starts the app, and a record's signature is verified once, not at every wake.
- Large stored values (the wall above all) now live in their own files instead of the app's settings, which had grown past the system's limit and were rewritten whole at every change.
- Saving a value that has not changed no longer rewrites the keychain.

Security and anonymity
----------------------

- Large sealed values stay sealed under the device key in their own files, named by the SHA-256 of their bytes; a copy and a change of person carry them exactly as before. A coin record is still verified before it is first believed. Nothing new is sent.
