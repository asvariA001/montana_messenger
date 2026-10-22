1.0 (2282) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2282), from source commit `51a2d7c9b023253d40cf90d3f30888e130fc0a57` of 2026-10-22. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Every switch in Settings now says under it what it does.
- With Show sender off, no notification names who wrote or shows their photo -- in private chats, groups and rooms alike.

Security and anonymity
----------------------

- The lasting keys a chat derives from its secret are made once when the chat is born and kept beside it, so the screens that read them no longer touch the chat secret (the first step of the key ratchet).
- Show sender off now hides the sender in group, room and private notifications in both the app and the notification extension.
