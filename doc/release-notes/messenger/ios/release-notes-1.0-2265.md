1.0 (2265) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2265), from source commit `1fc72aed12f718b6bd05022b3b4852c4efb8b0aa` of 2026-10-21. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Voice messages can now be read as text. Tap the quote icon beside a voice message and its words appear under it. On iOS 26 the system's new speech analyzer does the work; on earlier iOS the phone's own on-device recognizer does, with punctuation and the names from your chat.
- Coins go to anyone you have a chat with at once. Your phone now tells its coin key to your correspondents by itself, and a transfer that meets a correspondent whose key has not come yet asks for it and goes through within seconds instead of refusing.

Security and anonymity
----------------------

- Recognition runs only on the phone: no audio and no text is sent to Apple or anyone else. A phone that cannot recognise the language on the device says so and sends nothing. The text lives in memory only and is never written to disk, so a deleted chat leaves no transcript behind. No message, key or coin road changes.
- The coin key told to correspondents is the public tag of the coin key, never a name or a secret; it rides the same end-to-end encrypted word about oneself as before, and the ask for it carries nothing but a flag. No coin moves without the sender's signature.
