1.0 (2296) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2296), from source commit `03de274b71608ba2fe3e2bc020bfb5f5b2caba50` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Coins given on a letter now leave as a SEND record in the time chain under that letter; the reaction only names the record, and the reader's coins arrive from the chain itself.

Security and anonymity
----------------------

- A paid reaction sent by this build moves its coins only by a SEND record laid on the sender's chain and taken by the receiver's key; its chat word carries the record's name and the letter's name and moves no coins. The older reaction word of earlier builds is still credited by the reader for now -- that road is not closed yet.
