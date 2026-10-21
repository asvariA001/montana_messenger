1.0 (2267) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2267), from source commit `e4cd136fc54cdf5166f3fede7a99120296932ce1` of 2026-10-21. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Each chat's key now moves forward on a one-way chain once a day, starting 29 October: the letters of every day are sealed under a key of that day, derived from the day before and never back. Up to 28 October nothing changes in how letters travel, and this version talks with the earlier ones as before.

Security and anonymity
----------------------

- The daily step is one SHA-256 under its own domain over the day before, the same rule the protocol's Canon states for a pipe; nothing of the chain crosses the wire. From 29 October a version older than this one no longer opens this one's letters, so update every phone before then. The secret the chat began with is still kept on the phone in this version (for the safety number, the backup and the copies kept by correspondents); erasing it, which is what makes earlier days unreadable from a taken phone, comes as its own step.
