1.0 (2330) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2330), from source commit `d0886d400527a9bbc58bd04420fde8267f92a157` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The chats open faster: a conversation shows its newest twelve messages at once and lays the rest a moment later, and the video-note camera starts only when you record, not when you open a chat.
- Less heat: the music library is walked only when you open it, not at every launch; the history is written chat by chat, only the chat that changed, and only what was written is read back.
- Messages that arrive while the app is closed wait as one sealed file each, so the app reads only the new ones when it opens.

Security and anonymity
----------------------

- Letters kept by the notification extension move from one keychain item to one file per letter in the app group's folder, each sealed with AES-GCM under a key born once in this device's keychain (this device only) and bound to the file's name; the old item is moved once and emptied. The history is stored as one sealed part per chat. Main-thread and disk budgets are written only to the device's own logs. No new address, server, key leaving the device or network road is added.
- Issue #136: the wallet now calls the core's join door (mtc_machine_join) from its background thread when the phone holds no head, walking from two anchor proposals the build would carry; this build carries no anchor, so no phone walks yet. No new address, server, key kind or network road is added.
