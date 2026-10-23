1.0 (2295) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2295), from source commit `7806640bf4630674edf9ec38a84e9dbda8dab925` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- From 26 October, time coins are minted while you are here: every forty seconds the phone records the seconds you spent with Montana open or behind the Montana VPN as a MINT in the time chain. Taps, calls and time away mint nothing separately.

Security and anonymity
----------------------

- The phone now records minted seconds itself as MINT records in the time chain, signed by its own key; the node accepts only seconds inside its freshness window, so a phone cannot mint time it was not present for. Nothing new about the person is sent: a MINT carries the key, the interval and the amount, as before.
