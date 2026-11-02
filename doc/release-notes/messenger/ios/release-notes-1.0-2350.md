1.0 (2350) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2350), from source commit `c093818f09f0d2cc805cc4529c512c8112007635` of 2026-11-02. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The feed's motion is one for everyone: the springiness and animation duration an older build let you set no longer apply, and they leave the phone at launch.
- Signing in and creating an identity stand on one indigo background from the first page to the last; it no longer changes along the way.
- Montana's messages about new builds reach every phone again: a phone whose App Store check stayed silent now reads its channel from the receipt Apple wrote.
- The balance shows coins by default; the choice of its main sum starts anew.
- No wallet or VPN icons under the words Pantheon on Fire, and no 0-60 s line under it.

Security and anonymity
----------------------

- Three stored display values leave the phone at launch by their prints (chatSpringBounce, chatSpringDuration and the balance's main sum, 5da1d072bfa76c8b) and no copy carries them any more.
- When StoreKit's AppTransaction does not answer, the release channel is read only from the name of the app's own receipt file (sandboxReceipt = beta, receipt = store, anything else = no channel); nothing is sent, and a store build never points at a TestFlight link.
- No new server, network road or key leaving the device.
