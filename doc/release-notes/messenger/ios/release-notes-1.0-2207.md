1.0 (2207) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2207), from source commit `ffd89d2e23c34a2fd3cb2c132e8196f898999a53` of 2026-10-14. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Fixed avatar circle and outline sizing. Balance and level badges no longer stretch the chat rows.
- Text and call bubbles keep their width; complete time and seal fields move to another line when space is short.
- Groups and channels are now tabs on the Chats page. Channels support invitation links and creation before adding subscribers.
- Calls use one native system provider and one audio-session queue. Fixed stale call actions, held-call endings and the headset route control in group calls.
- A blinking red dot shows active recording. A call with no captured audio no longer retries an empty export forever.
- Recorded calls earn Time Coins; unrecorded calls burn them at the same personal rate. The call record shows the signed total. Each participant is counted by recording on their own device.
- Top up and montana.xxx/coins explain the same coin rules in English, Russian and Chinese.
