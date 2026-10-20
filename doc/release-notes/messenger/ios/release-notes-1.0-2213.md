1.0 (2213) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2213), from source commit `527515bec2d8482f20087a5bb4e780c94d063f87` of 2026-10-15. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Calls: everything a call does -- its connection, the camera of an incoming video call, the answers to a network change and the return to a call -- now runs in one place, in the order it happens, instead of from several threads at once. This removes a class of rare failures when a call starts, reconnects or comes back.
- The Mute button always shows what the microphone really does, also after iOS refuses a mute request and after a call on hold is resumed.
- Accepting an incoming call uses the one connection prepared while it rang: the accept no longer waits on a timer, and a call is no longer ended because that preparation took a moment longer.
- During a call the app asks the connection for its statistics less often: one measurement instead of two running side by side.
