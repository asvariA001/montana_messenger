1.0 (2210) Release Notes
========================

Montana for iPhone, iPad and Mac, build 1.0 (2210), from source commit `aaeba42ef131c01e48411b18b9fdc4dae150f59e` of 2026-10-14. The folder `apps/messenger/ios` of this repository holds that tree as it is built.

Notable changes
---------------

- Only the caller automatically sends a one-to-one call recording to the chat. Both devices keep their own recording and coin calculation; update both participants.
- Call totals use a coin badge with green earnings and red spending. Tap it to open the Explorer inside Montana.
- Cancelling the back gesture during a system interruption keeps the coin page open.
- Delayed call invitations keep their original start time through retries; an expired call cannot gain another ringing lifetime. VoIP delivery diagnostics now distinguish acceptance from confirmed ringing.
- A detailed guide and FAQ in three languages is available at montana.xxx/faq, with permanent answer links and structured files for assistants.
