# Findings F2 and F3 of the GPA report: what the set requires, and what was done

Findings F2 and F3 of [README.md](README.md) section 4 were documented there and not fixed. This
file reads the set for each, quotes what it requires by file and line, and states the change made
and the test that holds it. Line numbers of the set are of `core/docs/` at commit `f2c2b6f` of this
repository (these files are unchanged since the baseline `2c6951f` of README.md).

## F3 -- a first letter of the agent line was told from every later letter by its size

**What the code did.** In the crate `mt-agent` of the repository `efir369999/montana_core` (the
line the public `core/` folder is published from; the crate itself is not in this repository), a
first letter was padded to 1536 bytes (`Code/crates/mt-agent/src/card.rs` 41, 357-360 at
`bf701ef`) and every later letter to 2048 (`src/letter.rs` 7, 24-31). The seal adds a nonce and a
tag of one length to both, so the two kinds of letter stood on the postman's line 512 bytes apart,
and a door holding the pipe read a first contact from the length alone.

**What the set says the size of a letter must be.** Nothing in the set names two sizes, and three
passages require one:

- *"There is therefore no difference at all, rather than a difference that ends after a letter. A
  correspondence begun by a name and one begun by acquaintance are one object from the first letter
  onward -- the same tag, the same holder, the same path, the same shape, the same slot -- and there
  is no second kind of pipe for an implementation to build or an observer to tell apart"*
  (`core/docs/Montana Network.md` 301-305).
- *"the request and the first letter leave in the same window, the letter under an ordinary tag like
  every letter after it"* (Network 298-299); *"Every letter of the correspondence, the first one
  included, travels under an ordinary tag derived from the established secret"* (Network 324, the
  invariants of first contact).
- The observability ledger closes *"the shape and size of anything"* with *"one cell, one type"*
  (Network 686), and the Constitution's first requirement of [I-16] puts every object *"in the same
  size bucket as an ordinary message"* (`core/docs/Montana Constitution.md` 382-385). The one length
  of the wire is `cell_bytes` (`core/docs/Montana Canon.md` 4652).

The set speaks of the reference wire, where a letter is cells of one width; the agent line rides a
postman over TLS and is outside that wire (README.md, Gate -1). What carries over is the rule and
not the number: the set gives a first letter no shape of its own, so the agent line may not give it
one either. The number that applies on that line is the one every later letter already has, 2048,
which is also the phone's (`apps/messenger/ios/Code/Montana/MTNodeWire.swift` 122,
`MontanaWakePush.swift` 1528-1538).

**Why the one size can be sent.** The reader of a first letter on the phone takes any plaintext
longer than the encapsulation and four bytes and reads five fields from it: `apps/messenger/ios/Code/Montana/MontanaWakePush.swift` 2769 and
`apps/messenger/ios/Code/MontanaNSE/NotificationService.swift` 791, 820, 951 (`plain.count > 1092`);
the agent's own reader does the same (`card.rs` 43, 290-298). No reader compares the length to
1536, so a first letter of 2048 bytes is read by every phone in the field and by the agent.

**What was changed (core line, branch `fable-2/f3-core-line`).** `card.rs` pads the first letter to
`crate::letter::ENVELOPE_SIZE`, the size of every letter; the constant of its own is gone. The test
`a_first_letter_is_the_size_of_every_letter_at_the_door` seals a first letter and an ordinary letter
and asserts their sealed lengths equal (nonce + 2048 + tag), that the first letter is still accepted,
and names the wrong implementation it rejects, `first_wake_body_at_a_size_of_its_own`, written in
the test as the padding to 1536 this file held until the change, whose seal is 512 bytes shorter.
Ring and numbers: see the report of the council's run named in the pull request that carries this
file.

**What stays.** The phone itself still pads *its own* first letter to 1536
(`MontanaWakePush.swift` 1761-1774, the function `wakeRdv`), so a first contact *from a phone* is
still told by size on the postman's line; the fix of the agent line does not change that. The
change on the phone is the same one line (pad to `MTNodeWire.envelopeSize`), and its readers already
accept it, as quoted above. It is outside the scope of this repository's `core/` and is left here as
the remaining part of F3, open.

## F2 -- the client line's padding frames announced themselves, and nothing sent them

**What the code did.** `core/Code/crates/mt-net/src/framing.rs` defined a 1024-byte frame with a
three-byte plaintext header carrying `FLAG_PADDING` (lines 1-8, 20-22), built padding frames of
random bytes (53-59), and counted whether one frame in five was padding (172-202,
`FrameWindowState`, `MIN_PADDING_RATIO_*`). No code outside the file's own tests and the fuzz target
`crates/mt-net/fuzz/fuzz_targets/fuzz_decode_frame.rs` called any of it; the only other mention was
the re-export in `crates/mt-net/src/lib.rs` 25-29.

**What the set says.** Synthetic filler is forbidden outright, and a filler that announces itself
is named as the mark the set refuses to carry:

- *"Nothing is made even, and evenness would itself be the mark. A constant rate would be the worst
  solution ... Synthetic filler therefore does not exist, and neither does a price for it: the same
  bytes carry other people's letters and are paid for by a share of the window"*
  (`core/docs/Montana Constitution.md` 389-395; the same in `core/docs/Montana Network.md` 514-520).
- *"A unit carries a message or a piece of one and never synthetic filler: where nothing waits,
  nothing is sent"* (`core/docs/Montana Canon.md` 2271).
- A sleeping device's link is held *"not with empty filler, but with the transit it carries in any
  case"* (Network 509-512).

A padding frame is synthetic filler; a padding flag in a plaintext header is the one thing worse
than filler, a filler that tells the observer it is one. The set leaves no form in which the module
could be made live, so the right change is removal, not repair.

**What was changed (this branch, `core/Code`; the same commit on the core line).** The module
`framing.rs`, its re-export, the fuzz target and its `[[bin]]` entry, the `csprng` feature and the
`getrandom` dependency of `mt-net` that only the padding frames used, and the error variant
`EntropyUnavailable` that only they raised, are removed; `Cargo.lock` loses the one edge; the three
documents that listed uniform framing among the crate's contents say so no more
(`core/Code/docs/audit-checklist.md`, `core/Code/docs/SPEC_DEVIATIONS.md`, and `ROADMAP.md` on the
core line). Nothing else in the workspace referenced the module, so no caller changes. Ring and
numbers: see the report of the council's run named in the pull request that carries this file.
