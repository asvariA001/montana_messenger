1.0 (2270) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2270), from source commit `dafe38980253663d885953255e7740ca8f56b8ca` of 2026-10-21. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- The last icons on glass over the gold background are dark now too: network, channel and reactions.
- Groundwork for protecting the past: from 29 October coin transfers and the signature that marks a letter as yours rest on a key of the conversation's own, so the conversation's first secret can later be erased.

Security and anonymity
----------------------

- From 29.10.2026 UTC a coin transfer's blind and a letter's author field are computed from the conversation root, SHA-256 of mt-conv-root, a zero byte and the established secret, instead of the established secret itself; a receiver checks the root first, then the secret. Nothing new is stored or sent and the nodes do not change. Until then every build blinds and signs as before.
