1.0 (2216) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2216), from source commit `3e469ccae72309badd335406fd286197deafd8f2` of 2026-10-16. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Video calls with recording on use far less of the phone: each moment of the recording is drawn once instead of every arriving frame, and the finished recording keeps the picture as it was recorded instead of encoding it a second time.
- When the phone runs hot, the call recording keeps the voice and slows the picture to one frame a second; at the hottest it records the voice alone. The picture returns when the phone cools.
