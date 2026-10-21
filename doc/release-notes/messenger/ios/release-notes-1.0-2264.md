1.0 (2264) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2264), from source commit `e65ee5e7c6e57a3a1dbc81e49b6d03ff12b05e1c` of 2026-10-21. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Voice messages can now be read as text. Tap the quote icon beside a voice message and its words appear under it. On iOS 26 the system's new speech analyzer does the work; on earlier iOS the phone's own on-device recognizer does, with punctuation and the names from your chat.

Security and anonymity
----------------------

- Recognition runs only on the phone: no audio and no text is sent to Apple or anyone else. A phone that cannot recognise the language on the device says so and sends nothing. The text lives in memory only and is never written to disk, so a deleted chat leaves no transcript behind. No message, key or coin road changes.
