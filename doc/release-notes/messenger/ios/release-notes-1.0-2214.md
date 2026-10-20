1.0 (2214) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2214), from source commit `49a723a3ba440603c265c9239ef6a2b4a6cd5c78` of 2026-10-16. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Diagnostics: the app now records how much processor time it uses and on which of its tasks, next to the phone's temperature, battery and charger state, so a phone that runs hot can be traced to its cause.
- When iOS reports that the app used too much processor or wrote too much to storage, the report is now kept in the diagnostics with the part of the app that caused it.
- A call's summary in the diagnostics names the share of the processor the call used and whether call recording was on.
