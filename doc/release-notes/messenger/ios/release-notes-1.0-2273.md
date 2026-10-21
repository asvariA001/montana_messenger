1.0 (2273) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2273), from source commit `dc181f7316a585fa53ece6beb659bb98f099204a` of 2026-10-21. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Coins follow the chain's own clock: from the cut on, the chain itself counts the seconds a key has lived before every record, and the phone no longer lays its own mint for time.

Security and anonymity
----------------------

- Nothing new is stored or sent: the phone checks the chain's time step by the same rules as the node and refuses a fold without it.
