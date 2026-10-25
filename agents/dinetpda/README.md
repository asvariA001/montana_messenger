# tchain: DinetPDA's minimum of the time-chain architecture

`tchain.py` is the DinetPDA agent's own minimum of the council's time-chain architecture, parts A to F, for
[issue #30](https://github.com/efir369999/montana_messenger/issues/30). It is written from the public description only:
sections 2 to 4 and 11 of the issue, and the wall's rule for reading a link (`MASTER_WALL.md:5` in this repository). It
uses the Python standard library and nothing else. It needs Python 3.8 or newer, and was run on Python 3.14.2 under
Windows 11.

| Part | What it does | Commands |
|---|---|---|
| A. The link | One move is one record of an append-only JSONL chain. One hash seals everything before it. | `add`, `verify`, `recent` |
| B. The window tau1 | 13 links make a window. The chairman's next signed link seals a full window. | `windows` |
| C. The constitution | A second chain of the same shape. Only a link that carries the Author's word changes it. | `const ...` |
| D. The open tasks | At most 13 open tasks. Every move on a task is a link tagged `[task k]`, and a task closes only by proof. | `task ...` |
| E. The start of a session | One command prints all a new session needs, in about 5 to 10 thousand tokens. | `start` |
| F. Measuring the context | The session's context, read from Claude Code's own transcript, and the rule it sets. | `context` |

## Quick start

```sh
T="python agents/dinetpda/tchain.py"            # from the repository root; python3 outside Windows
export TCHAIN_HOME="$HOME/.tchain"               # the data folder; never inside this repository by default
$T start                                         # read it; it ends with the acceptance command, ready to run
$T add "Accepted: no handover yet. Taking: item 12 (#23)" --from "Claude of DinetPDA #1 [5ca1ab1e]"
$T task add "NET-24 vectors against the Windows port" --issue efir369999/montana_messenger#23 \
   --deadline 2026-11-09T15:00:00Z --from "Claude of DinetPDA #1 [5ca1ab1e]"
$T task progress 1 40 "the tag vectors pass, the step vectors next" --from "Claude of DinetPDA #1 [5ca1ab1e]"
$T verify
$T context
```

Every command takes `--home DIR`, `--accept WORDS` and `--handover WORDS`, which are described below. `python
agents/dinetpda/tchain.py COMMAND -h` prints the command's own help.

## The data folder

The data folder is `--home DIR`. Without it, the folder is `$TCHAIN_HOME`. Without either, it is `.tchain` in the
current folder. That last default is refused when it would fall inside the git repository that holds `tchain.py`, so a
chain is never written into this repository unless someone asks for it. The folder holds `chain.jsonl` (the links),
`constitution.jsonl` (the constitution), and one empty `.lock` file next to each. A reading command creates no folder
and no chain. Its only write is the `.lock` file, next to a chain that already exists.

## A. The link

A link is one line of `chain.jsonl`. It is a JSON object with exactly six fields:

| Field | Definition |
|---|---|
| `n` | The link's number. The genesis is link 0, and each later link is one more, as the wall numbers its own genesis 0 (`MASTER_WALL.md:1110`). |
| `time` | When the link was written: ISO 8601 in UTC, to the second, the zone written as `Z`, for example `2026-10-25T15:00:00Z`. The tool writes it from the system clock, under the writers' lock. |
| `task` | What was done and how it was checked. It is one line of 1 to 280 characters: Unicode code points, as Python's `len` counts them. It has no control character, so no line end and no tab, and the spaces at its two ends are removed when it is written. A longer text is refused, never cut. |
| `from` | The signature: name, number and session label, written `NAME #N [label]`, for example `Claude of DinetPDA #6 [5ca1ab1e]`. The name holds no `#`, `[` or `]` and no space at either end. `N` is the agent's number, from 1, without leading zeros. The label is 8 lowercase hex digits: the first 8 of the session id. |
| `prev` | The hash of link `n - 1`. The genesis has 64 zeros. |
| `hash` | SHA-256, in 64 lowercase hex digits, over the canonical JSON of every field but `hash`. `prev` is inside, so each hash seals the whole chain before it. |

**Canonical JSON.** The tool writes `json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
allow_nan=False)` and encodes it as UTF-8. That means:

- the keys are sorted by code point;
- there is no space anywhere outside the strings;
- non-ASCII characters are written as themselves, not as `\u` escapes;
- strings are escaped as Python's `json` module escapes them (`"`, `\` and the control characters).

There is no Unicode normalisation: a text is hashed exactly as it was given. Each line of a chain file is the canonical
JSON of the whole record, `hash` included, followed by one LF. A CR before the LF is tolerated, because it changes no
field. The serialization is that of `canonical()` in this repository's `verify.py:27-29`: sorted keys, the same
separators, `ensure_ascii=False` and UTF-8. That function hashes the wall's own fixed fields; this one hashes every
field but `hash`.

**A vector.** The genesis below has the hash `19b4f08b059ce47c34477df1d68f1b43b15a8d7ca4b8c65c2feb4255bedfcf0a`, which
`test_tchain.py` pins:

```json
{"from":"Claude of DinetPDA #1 [5ca1ab1e]","n":0,"prev":"0000000000000000000000000000000000000000000000000000000000000000","task":"Принято: the genesis","time":"2026-10-25T15:00:00Z"}
```

**Append-only.** The tool appends and does nothing else: no command edits or deletes a link. Before each write, it
verifies the whole chain and refuses to write to a broken one. Writers and readers take an OS lock on `<file>.lock`:
`msvcrt.locking` on Windows, `flock` elsewhere. The system releases that lock if its holder dies.

**`verify`** recomputes the chain from its genesis, line by line, and names the first broken `n` with the reason. For
line `n` it checks, in this order:

1. the line is a JSON object in UTF-8;
2. its `n` is the integer `n`. A record that was deleted, inserted or moved fails here;
3. its `prev` is the previous link's hash, or 64 zeros at link 0;
4. its `hash` equals the hash recomputed from its fields. A changed `task`, `time`, `from` or `hash` fails here;
5. the line is exactly the canonical JSON of its record;
6. the time, the signature and the task are well formed;
7. the last line ends with a line end. A write that was cut short fails here.

Suppose a link is changed and its hash recomputed by hand. The next link's `prev` no longer matches, so `verify` stops
there: one hash seals everything before it.

What `verify` cannot see on its own is a chain cut at its tail, or a chain rewritten and resealed whole. Either one is
still internally whole. To catch them, keep a hash elsewhere, such as the head that `verify` prints, and pass it back:
`verify --anchor N:HASH` checks that link `N` still has that hash, and with it everything before it.

`recent [N]` prints the last `N` links, 13 by default, one line each, as `n time from: task`.

## B. The window tau1

- Window `w`, counted from 0, holds links `13w` to `13w + 12`. It is full once link `13w + 12` is written.
- The acceptance words are `Accepted` by default; `--accept WORDS` or `TCHAIN_ACCEPT` names others. A link *begins
  with* them when its task starts with exactly those words, in the same case, and the character after them, if any, is
  not a letter, a digit or `_`. So `Accepted: ...` begins with them, and `Acceptedly` and `accepted: ...` do not. Keep
  the same words for the life of a chain, because the windows are computed from them.
- The chairman at link `m` is the `from` of the last link, at `m` or before it, that begins with the acceptance words.
  While no link does, there is no chairman.
- A full window is sealed by the first link after it whose `from` is the chairman at that link. A link written by
  anyone else seals nothing. A new chairman's acceptance link seals the full windows before it, since he is the
  chairman at that link. Without any acceptance link, no window closes.
- `windows` lists each window: full or open, and which link sealed it, and the chairman. `add` says when the new link
  seals a window or fills one.

Since whoever writes the acceptance words becomes the chairman, a helper must never begin a link with them. The rules
of the turn that `start` prints say so.

## C. The constitution

`constitution.jsonl` is a second chain of the same shape. Its envelope (`n`, `time`, `from`, `prev`, `hash`), its
canonical JSON, its hash, its genesis and its `verify` are those of part A. In place of `task` it carries one operation:

| Command | `op` | Its own fields | Effect |
|---|---|---|---|
| `const article NO TEXT` | `article` | `no`, an integer, which may be negative (`-3`, `-2`...); `text`, 1 to 4,000 characters, line ends allowed | Article `NO` now reads `TEXT`, newly or in place of the old text |
| `const remove NO` | `remove` | `no` | Article `NO` no longer stands |
| `const decide QUESTION` | `decide` | `k`, the next place, assigned by the tool; `text`, the question | Place `k` of the line of decisions: a question for the Author |
| `const decided K` | `decided` | `k` | The Author's answer to place `K` |

Every constitution link also carries `word` and `word_time`, given by `--word WORDS` (or `--word-file`) and
`--word-time TIME`:

- `word` is the Author's words, verbatim: any length, line ends kept, never empty. For `decide`, it says why the
  question is the Author's to decide.
- `word_time` is when he said them, in the time format of part A. It defaults to the moment of the write, and it is
  never later than the link's own time.

**Only the Author's link changes the constitution.** The schema itself requires the word, so `verify` treats a
constitution link without one as broken. A link written past the tool without the word therefore breaks the chain at
its `n` and changes nothing: the standing text is computed only from the links before the break.

**The line of decisions.** A question for the Author goes in as `const decide`, never as plain text. Its place `k`
runs from 1. `const decided K` answers place `K` once.

The standing articles and the line are a replay of the verified chain. A whole link that breaks the order of the line
stays in the chain, as every link does, and changes nothing. That covers removing an article that does not stand, a
place out of turn, and a second answer. `const show` names such links, and the tool refuses to write them.

- `const show` prints the standing articles in number order, then the line of decisions.
- `const log [N]` prints the last `N` constitution links, one line each.
- `const verify [--anchor N:HASH]` is `verify` for this chain.

**Passing a word verbatim from Git Bash.** In Git Bash on Windows, an argument that begins with `/` is rewritten as a
path, so `/// да` arrives as `// да`. Pass such a word another way:

- with `--word-file PATH`, read as UTF-8, without a leading byte order mark or the one line end an editor adds last;
- with `--word-file -`, read from standard input;
- or with `MSYS_NO_PATHCONV=1` set.

## D. The open tasks

Every move on a task is a link of the main chain. Its task text follows this grammar, and the checklist is the replay
of those links:

| Move | The link's task |
|---|---|
| birth | `[task k] born: TITLE; issue OWNER/REPO#N; due TIME; P%` |
| the deadline moved | `[task k] deadline TIME` or `[task k] deadline TIME: why` |
| progress | `[task k] P%` or `[task k] P%: note` |
| a blocker | `[task k] blocker: WHAT` |
| the close | `[task k] closed by build: REF`, `closed by test: REF` or `closed by file: PATH:LINE` |
| any other move | `[task k] TEXT` |

The rules:

- `k` runs from 1 and is never used again, and at most 13 tasks are open at once.
- The chairman (part B) writes the birth, every deadline move and the close, as article -2 of the constitution in the
  issue gives these to him.
- Each task has a deadline from its birth, a percentage `P` from 0 to 100, and one GitHub issue. A deadline is in the
  future whenever it is set.
- A closed task takes no more moves.
- A blocker stands until the task's next progress or move.
- A task closes only by a proof. A build or a test proof gives a reference, such as the run and its exit code. A file
  proof gives `PATH:LINE`, and at the write the file must exist and hold that line. A relative path is read from the
  current folder.

The rules hold twice. The `task` commands, and `add` with a tagged text, refuse a link that breaks them. The replay
ignores a tagged link written past them, and `task list` names it.

| Command | What it writes |
|---|---|
| `task add TITLE --issue OWNER/REPO#N --deadline TIME [--pct P]` | the birth. The issue may also be given as its `https://github.com/OWNER/REPO/issues/N` address |
| `task move K TEXT` | a plain move. A text that would read as another move is refused |
| `task progress K P [TEXT]` | progress |
| `task blocker K TEXT` | a blocker. It also names the next task: the open, unblocked one with the earliest deadline |
| `task deadline K TIME [TEXT]` | the chairman moves the deadline |
| `task close K --proof build\|test\|file --ref REF` | the close |
| `task list [--all]` | nothing: it prints each open task in one line, with `OVERDUE` past the deadline. `--all` adds the closed ones |

## E. The start of a session

`start` reads both chains and the session's transcript, and writes nothing. It prints, in this order:

1. **The context table:**
   - UTC now;
   - the next agent number: one more than the number in the signature of the last acceptance link, or 1;
   - the data folder;
   - each chain's links, head and `verify` state;
   - the windows and the chairman;
   - the tasks: open, blocked and overdue;
   - this session's context (part F);
   - each part below with its size in characters and in estimated tokens, and the start's own exact total.
2. The constitution's standing articles, in full.
3. The questions waiting for the Author, at most 13 lines.
4. Every open task in one line, with its deadline and percentage.
5. The last handover link.
6. The last 13 links, one line each. The last of them is the last entry of the journal.
7. The rules of the turn. They include the acceptance command, ready to run, with the next number and the session's
   label.

The session reads the handover and the last link, writes its acceptance link, and starts work in the same turn.

**Size.** Tokens are estimated as a quarter of the UTF-8 bytes: about 4 characters of English, or 2 of Cyrillic, to a
token. That is an estimate, not a tokenizer's count. Everything but the constitution is bounded:

- 13 links of at most 280 characters;
- at most 13 tasks and 13 questions, at most 240 characters a line;
- one handover;
- the fixed rules.

The constitution is printed whole, because it is the law. A start above 10,000 estimated tokens ends with a warning
that says so. The heaviest case the tests build prints 18,939 characters, about 6.1 thousand estimated tokens:

- 13 open tasks and 13 open questions;
- 101 links, mostly in Cyrillic;
- 17 articles, one of them 2,600 characters.

## F. Measuring the context

- **The session's file.** Claude Code writes each session to `~/.claude/projects/PROJECT/SESSION_ID.jsonl`. The tool
  looks for `SESSION_ID.jsonl` in every project folder under `--projects DIR`. Without it, it looks under
  `$CLAUDE_CONFIG_DIR/projects`, and otherwise under `~/.claude/projects`. `SESSION_ID` is `$CLAUDE_CODE_SESSION_ID`
  or `--session ID`; `--file PATH` names the file itself.
- **The context** is the usage of the session's last assistant message: `input_tokens + cache_creation_input_tokens +
  cache_read_input_tokens`, where a missing field counts as 0. The tool passes over three kinds of line:
  - a subagent's line (`"isSidechain": true`);
  - a message Claude Code makes itself (model `<synthetic>`);
  - a last line still being written.

  A session file grows past 100 MB, so the tool reads it backwards, 1 MiB at a time.
- **The rule:**

  | Context | Rule |
  |---|---|
  | below 150,000 | work on; a handover is refused |
  | 150,000 to 200,000 | prepare the handover |
  | above 200,000 | stop at once |

- **The handover is refused below 150,000.** `add` refuses a link that begins with the handover words while the
  session's measured context is below 150,000. The handover words are `Handover` by default; `--handover WORDS` or
  `TCHAIN_HANDOVER` names others. When the context cannot be measured, because `CLAUDE_CODE_SESSION_ID` is unset or no
  file is found, the handover is written with a warning that the rule was not applied.

The tool depends on the shape of the assistant lines that Claude Code writes on this machine:
`{"type": "assistant", "isSidechain": false, "message": {"model": ..., "usage": {...}}}`. That shape is what this
machine's transcripts show, not a published contract. When the tool finds no usage, it says so.

## Commands and exit codes

```text
tchain.py start
tchain.py add TEXT --from SIGNATURE
tchain.py verify [--anchor N:HASH]
tchain.py recent [N]
tchain.py windows
tchain.py const article NO TEXT (--word WORDS | --word-file PATH) [--word-time TIME] --from SIGNATURE
tchain.py const remove NO (--word WORDS | --word-file PATH) [--word-time TIME] --from SIGNATURE
tchain.py const decide QUESTION (--word WHY | --word-file PATH) [--word-time TIME] --from SIGNATURE
tchain.py const decided K (--word ANSWER | --word-file PATH) [--word-time TIME] --from SIGNATURE
tchain.py const show | const log [N] | const verify [--anchor N:HASH]
tchain.py task add TITLE --issue OWNER/REPO#N --deadline TIME [--pct P] --from SIGNATURE
tchain.py task move K TEXT --from SIGNATURE
tchain.py task progress K P [TEXT] --from SIGNATURE
tchain.py task blocker K TEXT --from SIGNATURE
tchain.py task deadline K TIME [TEXT] --from SIGNATURE
tchain.py task close K --proof build|test|file --ref REF --from SIGNATURE
tchain.py task list [--all]
tchain.py context [--session ID | --file PATH] [--projects DIR]
```

| Exit code | Meaning |
|---|---|
| 0 | done |
| 1 | `verify` or `const verify` found a break, or an anchor that does not hold |
| 2 | refused (`refused: ...` on standard error) or misused (argparse's usage message) |

## Not built: G to I

Parts G to I of the architecture drive tabs in Cursor on macOS. They are not built here: this agent runs Claude Code
in VS Code on Windows 11, where there are no Cursor tabs to drive. What this agent needs from them is carried by part F,
which reads the session's own transcript, and by the handover link, which carries the essence to the next session.

## Tests

From the repository root:

```sh
python -m unittest discover -s agents/dinetpda -v
```

The 55 tests use temporary folders only, and no network. They take about 7 seconds. They cover:

- **The link:** appending and verifying from the genesis, the definition's hash computed without `tchain`, and the
  pinned vector. Also the 280-character limit and the signature's form, and two writers at once.
- **Tampering:** a changed task, time, signature, prev, genesis prev or hash, and a deleted, reordered or inserted
  record. Each makes `verify` fail at the right `n`. The tests also cover a link resealed by hand (caught at the next
  link), a cut tail (caught by an anchor), a line not in canonical form, a cut write, and the refusal to write to a
  broken chain.
- **Windows:** 13 links make a window, and only the chairman's next link seals it. A helper's link seals nothing,
  without the acceptance words no window closes, the chairman changes, and other acceptance words can be set.
- **The constitution:** setting, replacing and removing articles, negative numbers among them. The word kept verbatim
  with its time, `--word-file` and standard input. Without the Author's word nothing changes, even for a correctly
  hashed link written past the tool. The line of decisions, and links out of order that change nothing.
- **Tasks:** tagged links, the limit of 13, a blocker that sends the work to the next task, and closing only by a proof
  the chairman signs. A file and line that must exist, deadlines, and tagged links written past the rules.
- **The context meter:** a synthetic session in which the last assistant message wins, with the sum of three fields.
  It skips subagent lines, `<synthetic>` messages and a line still being written. The thresholds 149,999, 150,000,
  200,000 and 200,001, finding the session by its id, reading backwards across blocks, and the refused handover.
- **Start:** an empty folder, the heaviest case within the budget (one line per item, 13 links, its own exact total,
  nothing written), a start over its budget, and the session's label and context taken from Claude Code's variables.

## What this does not prove

- The signature is named attribution in a hash chain, not an asymmetric signature. Anyone who can write to the data
  folder can append a correctly hashed link under any name. `verify` proves that the chain is whole, not who wrote it.
- A chain rewritten whole is caught only against an anchor kept elsewhere.
- The start's token count is an estimate, and the context meter depends on Claude Code's transcript format (part F).
- It was run only on Windows 11 with Python 3.14.2. The code parses under the Python 3.8 grammar, but it was not run
  on 3.8, and the `flock` branch for Linux and macOS has not been run at all.
