1.0 (2260) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2260), from source commit `d9d602571d5ab9e1c65ea71f44ca47ae8fd4899e` of 2026-10-20. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- One person is one conversation: when two people scan each other's codes at the same moment, the chat no longer appears twice — both phones fold it into one.

Security and anonymity
----------------------

- Folding two pipes to one person discloses nothing new: the accepting phone's question carries only tags keyed by the new pipe's secret, which the other side can match only for pipes it already holds; the unused slots are random noise.
