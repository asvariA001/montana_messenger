1.0 (2349) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2349), from source commit `36cb86f661f14c65e1220f079ab4317a62beb485` of 2026-11-02. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Leaving the registration midway no longer leaves an identity behind: until the profile's checkmark the making is unfinished, and the next launch forgets it.
- The registration has no step of choosing the page's ground any more: a new identity is born on the indigo, and Appearance changes it later.
- GIFs play without stopping; a tap stops one and a tap plays it on. Appearance gains Reduce GIF animation, which brings back three plays and then only on a tap.
- In Appearance the app icons scroll and take the tap, the feed's motion section is gone, and Voice message style stands higher.
- Your node is no longer in the Settings list: it opens from Access by level, level 8.

Security and anonymity
----------------------

- Two new stored values, both on this phone: gifReduceMotion (a display preference, carried in the person's copy as other looks), and mt.birth.unfinished (the SHA-256 print of the words being made, never the words, kept only from Create to the profile's checkmark and never in a copy). No new server, network road or key leaving the device.
