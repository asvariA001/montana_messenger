1.0 (2286) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2286), from source commit `a8ecb6a1eedea9b9f5ef40319e4724eb00c8d972` of 2026-10-22. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Music on the shared Wall of Thoughts plays again: the author of a post with tracks lays its files on the nodes once more whenever a node has let them go, and the nodes now keep the Wall's files by their free space instead of for one week.
- Montana Core for Mac now downloads from the site and from GitHub as a disk image: open it and drag Montana Core into Applications. The image itself is signed with the Developer ID and notarized by Apple, as is the app inside it.

Security and anonymity
----------------------

- The Wall of Thoughts repair (first carried by build 2285, which never shipped) changes neither security nor anonymity: the owner of a Wall post asks the nodes which of its own encrypted pieces they hold and lays the missing ones again under the same key; the nodes see the same sealed bytes and names as before.
- Build 2286 changes neither security nor anonymity of the app: the Mac product is packed in a disk image signed with the same Developer ID and notarized by Apple on its own; the app inside is the same notarized, stapled app, and its reproducible digest counts the same files.
