1.0 (2287) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2287), from source commit `3679172de4ef73be4180f05d4c24e04f25fb32df` of 2026-10-22. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Notifications for a conversation no longer read the conversation's secret: each conversation keeps its own local name from the moment it is created, and the notification service finds the chat by that name.
- When the app cannot check for a newer build, its private diary now says why (no store channel, network error, the site's answer or the document), once per new reason.

Security and anonymity
----------------------

- Build 2287 narrows what the notification service touches: it no longer derives the chat alias from a conversation's established secret on every round, but reads a local name the conversation kept at birth; nothing changes on the wire.
- The diary line about the release check names the public server it asked and the kind of refusal; it carries no key, account or identity of the user.
