1.0 (2344) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2344), from source commit `65cc113a5eeb456c83e09e221cb55b140e85b057` of 2026-11-02. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The app no longer freezes and closes when it goes to the background. During a call, photos, videos, GIFs and Wall of Thoughts posts now send while the call is connected; they wait only while the call is ringing or connecting. Coins that did not leave because the key's node was busy for the minute are now sent again automatically for up to a minute, and if they still cannot go you are told why and nothing leaves your balance. On the Wall of Thoughts you can like, keep and repost a post even without a chat with its writer, a long press on the like shows who liked it, a long press on the eye of your own posts shows who saw them, and a tap on the writer's name opens their page. A repost now stands under your name, with the original's wall noted below. The side panel's background now reaches the bottom of the screen.

Security and anonymity
----------------------

- A post on the public Wall of Thoughts table now carries the @name its writer holds, which is already public and searchable; a lapsed name is not carried. A page sent to a person who holds a chat with the wall's owner carries the names of up to fifty people who liked each post, as those people named themselves; who viewed a post never leaves the owner's phone. Reacting to a table post without a chat opens a chat by that @name through the existing meeting road. No new stored value, server, key leaving the device or network road.
