1.0 (2298) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2298), from source commit `e66c53c8fefb3dc8a143f6024bab0b8a3365d072` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The chats page no longer stutters: the coin view of each conversation is reckoned off the main thread.
- Montana on a Mac opens only when you open it: a call or a letter no longer launches the app after you quit it.

Security and anonymity
----------------------

- A Mac keeps no push token at any notify machine: Apple's letter token is dropped, every row of both tokens is withdrawn from every door, and the keys go only when every door has said yes. A closed Mac hears no calls or letters; it hears them while the person keeps it open.
