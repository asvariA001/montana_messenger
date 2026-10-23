1.0 (2297) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2297), from source commit `9c4e9919a828d728b245da2c3e2b9504a58fa660` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Your coin balance now shows what the time chain says; after the time cut calls and taps no longer mint coins on the phone itself, and an unrecorded call burns exactly its price.

Security and anonymity
----------------------

- Past the time cut the phone mints nothing of its own: every number of the person's coins is read from the chain, less the sends still on their way. Until the chain first answers, the phone's own record still shows -- that view is replaced as soon as the chain speaks.
