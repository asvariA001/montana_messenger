1.0 (2261) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2261), from source commit `1b0f742d9473a9fbce9d1403afbc61d97904524a` of 2026-10-20. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Coins reach a new person at once: someone who has not written anything about themselves still tells your phone where their coins arrive.
- A transfer that cannot go says so right away — «Coins were not sent», your balance untouched — instead of hanging on «being confirmed».

Security and anonymity
----------------------

- The coin receiving key now rides the word about oneself even without a bio; it is the same public receiving key, sent only inside the pair's end-to-end pipe, so nothing new leaves the phone. A refused transfer is shown on the sender's phone alone and is never presented as confirmed.
