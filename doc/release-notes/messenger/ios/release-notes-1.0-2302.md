1.0 (2302) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2302), from source commit `8df01bb5413367a4ac46e28578e44811b77b63bd` of 2026-10-23. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Montana now finds its update on every iPhone: the update chat and the button on the time panel no longer depend on the iOS version.

Security and anonymity
----------------------

- Security: the update channel is the environment Apple signed for the installed app, so an App Store build still never points at a TestFlight beta. Anonymity is unchanged.
