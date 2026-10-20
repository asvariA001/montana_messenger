1.0 (2208) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2208), from source commit `5e2696bbe7b15c8375b15964a2f187892430c792` of 2026-10-14. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Fixed native audio processor attachment so call audio reaches the recorder. The release check now verifies both delegates against the bundled audio library.
- Fixed avatar circle and outline sizing. Balance and level badges no longer stretch the chat rows.
- Text and call bubbles keep their width; complete time and seal fields move to another line when space is short.
- Groups and channels are now tabs on the Chats page. Channels support invitation links and creation before adding subscribers.
- Calls use one native system provider and one audio-session queue. Fixed stale call actions, held-call endings and the headset route control in group calls.
- A blinking red dot shows active recording. A call with no captured audio no longer retries an empty export forever.
- Recorded calls earn Time Coins; unrecorded calls burn them at the same personal rate. The call record shows the signed total. Each participant is counted by recording on their own device.
- Top up and montana.xxx/coins explain the same coin rules in English, Russian and Chinese.
