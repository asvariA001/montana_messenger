1.0 (2240) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2240), from source commit `a9c8c25aad9fa8dbaa965a66213d3b1a3146eb2c` of 2026-10-18. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Less heat and longer battery: your coin book is no longer downloaded and decoded again every half minute -- only what is new is read, about once a minute.
- Less heat: the phone reads its mailbox from each node once, waits when a node asks it to slow down, and asks only for the days a letter can still be there.
- Less heat for the people you write to: a letter the node already keeps is re-sent more and more rarely instead of every five minutes, so their phones are not woken again and again; it still reaches them the moment they appear.
- A first message to someone who has not answered yet waits for them instead of being re-sent every twenty seconds.
- Direct connections: an address that never answers is tried more and more rarely; when your network comes back, every address is tried again.
- A lighter start: profile pictures from contact cards, deleted chats and the check of the chat list are no longer redone at every launch.
- Your coin balance in your profile reaches your contacts over a connection that is already open and no longer wakes their phones.
