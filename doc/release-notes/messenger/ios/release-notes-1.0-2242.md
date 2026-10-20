1.0 (2242) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2242), from source commit `b8b6931b21fc63469f6aa406c09f3676e21feea6` of 2026-10-18. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The app no longer closes at launch: a settings record that could not be saved made 2239 and 2240 quit at every start, and that whole kind of record is now saved correctly.
- The phone stays cool: the copy your contacts keep for you no longer starts again and again when the phone is short of space. It checks the free space first and tries once a day.
- Chats open and scroll faster: the chat list no longer waits for the system keychain, and the coin book is read and written in the background.
- The folder tabs float over the chat list like the time panel: the list scrolls beneath them.
