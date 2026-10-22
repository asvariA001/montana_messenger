1.0 (2288) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2288), from source commit `47080687a05c40869339d681589679a1c0f6382d` of 2026-10-22. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Words follow the background under them: a chat with its own background and a person's page with theirs now show white text on dark backgrounds and black text on light ones, as the system does.
- Notifications for a conversation no longer read the conversation's secret: each conversation keeps its own local name from the moment it is created, and the notification service finds the chat by that name.
- When the app cannot check for a newer build, its private diary now says why (no store channel, network error, the site's answer or the document), once per new reason.

Security and anonymity
----------------------

- Build 2288 narrows what the notification service touches: it no longer derives the chat alias from a conversation's established secret on every round, but reads a local name the conversation kept at birth; nothing changes on the wire.
- The diary line about the release check names the public server it asked and the kind of refusal; it carries no key, account or identity of the user.
- The text colour of a chat or a page is decided on the device from the background stored there; nothing new is sent or stored.
