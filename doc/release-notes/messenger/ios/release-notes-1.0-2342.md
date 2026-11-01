1.0 (2342) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2342), from source commit `0e1d9b73dbee51e347480023342850f81097f02e` of 2026-11-01. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The phone stays cooler and the app opens lighter: at launch it no longer re-sends wall pages to visitors who never came for the last one (they get the page when they reappear), no longer lays out the music list until you open the music page, remembers which parts of the coin book it has already opened so a launch fetches only the new ones, and no longer keeps redialling addresses that only its own tunnel answered.

Security and anonymity
----------------------

- No new stored value, address, server, key leaving the device or network road. One new local file (coin-vault-pieces) keeps only the names of coin-book pieces already fetched, under the same protection as the book, and leaves with the account; wall pages, music and dials are the same roads, started later or less often.
