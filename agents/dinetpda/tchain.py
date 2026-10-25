#!/usr/bin/env python3
"""tchain: DinetPDA's own minimum of the council's time-chain architecture, parts A to F of issue #30.

A. One move is one link of an append-only JSONL chain, sealed by SHA-256: one hash seals everything before it.
B. 13 links make a window tau1; a full window is sealed by the chairman's next signed link.
C. The constitution is a second chain of the same envelope; only a link that carries the Author's word changes it.
D. At most 13 open tasks, each with a deadline from its birth, a percentage and one GitHub issue; every move on a task
   is a link tagged [task k], and a task closes only by proof.
E. `start` prints all a new session needs, in about 5-10k tokens: the context table, the constitution, the open tasks,
   the handover, the last 13 links, the UTC time, the next agent number and the rules of the turn.
F. The context of a Claude Code session, from its own transcript; below 150k a handover is refused.

The definitions are written out in README.md beside this file. Standard library only, Python 3.8 or newer.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime
import hashlib
import json
import os
import re
import sys
import time
import unicodedata
from pathlib import Path
from typing import Callable, NamedTuple, Optional

# ---------------------------------------------------------------------------------------------------------------------
# A. The link

GENESIS_PREV = "0" * 64  # the prev of link 0, the genesis
TEXT_LIMIT = 280  # characters (Unicode code points) of a link's task
TIME_FORMAT = "%Y-%m-%dT%H:%M:%SZ"  # ISO 8601 in UTC to the second, the zone written as Z
TIME_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
HASH_RE = re.compile(r"[0-9a-f]{64}")
# a signature is name, number and 8-hex session label: "Claude of DinetPDA #6 [70662df9]"
SIGNATURE_RE = re.compile(r"(?P<name>[^#\[\]]+) #(?P<number>[1-9][0-9]*) \[(?P<label>[0-9a-f]{8})\]")
LINK_KEYS = frozenset({"n", "time", "task", "from", "prev", "hash"})
CHAIN_FILE = "chain.jsonl"


class Refused(Exception):
    """A request the tool does not carry out; the command line prints it and exits with 2."""


class Broken(NamedTuple):
    """The first link of a chain file that does not hold, and why."""

    n: int
    reason: str


def canonical(record: dict) -> bytes:
    """The canonical JSON of a record: keys sorted by code point, the separators "," and ":" without spaces, non-ASCII
    characters written as themselves (ensure_ascii=False), encoded as UTF-8. A chain file holds one such line per link."""
    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def seal(record: dict) -> str:
    """A link's hash: SHA-256, in 64 lowercase hex digits, over the canonical JSON of every field but hash. The field
    prev, the hash of the link before, is inside, so each hash seals the whole chain before it."""
    return hashlib.sha256(canonical({key: value for key, value in record.items() if key != "hash"})).hexdigest()


def utc_now() -> str:
    """The current time as a link writes it: ISO 8601 in UTC to the second, the zone written as Z."""
    return datetime.datetime.now(datetime.timezone.utc).strftime(TIME_FORMAT)


def parse_time(value) -> datetime.datetime:
    """A time written as a link writes it, read back; anything else is refused."""
    if isinstance(value, str) and TIME_RE.fullmatch(value):
        with contextlib.suppress(ValueError):
            return datetime.datetime.strptime(value, TIME_FORMAT).replace(tzinfo=datetime.timezone.utc)
    raise Refused(f"time {value!r} is not ISO 8601 UTC such as 2026-10-25T15:00:00Z")


def check_text(value, what: str, limit: int, one_line: bool = True) -> str:
    """A text a link carries: a non-empty string of valid Unicode, at most `limit` characters (code points). A one-line
    text has no control character at all; another may hold line ends and tabs."""
    if not isinstance(value, str) or not value.strip():
        raise Refused(f"{what} is empty")
    if len(value) > limit:
        raise Refused(f"{what} has {len(value)} characters; the limit is {limit}")
    control = [c for c in value if unicodedata.category(c) == "Cc" and (one_line or c not in "\n\t")]
    if control:
        raise Refused(f"{what} holds the control character {control[0]!r}" + ("; a link is one line" if one_line else ""))
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise Refused(f"{what} is not valid Unicode") from None
    return value


def check_signature(value) -> str:
    """A signature: name, number and 8-hex session label, written NAME #N [label]."""
    match = SIGNATURE_RE.fullmatch(value) if isinstance(value, str) else None
    if not match or match["name"] != match["name"].strip() or any(unicodedata.category(c) == "Cc" for c in value):
        raise Refused(f"signature {value!r} is not NAME #N [label] with a lowercase 8-hex session label, such as "
                      "'Claude of DinetPDA #6 [70662df9]'")
    return value


def check_line(line: bytes, n: int, prev: str, check_body: Callable[[dict], None]) -> Optional[str]:
    """Why line n of a chain file is not the link that follows `prev`, or None when it is."""
    if line.endswith(b"\r"):
        line = line[:-1]  # an editor that rewrote the line end as CRLF changed no field
    try:
        record = json.loads(line.decode("utf-8"))
    except ValueError:  # UnicodeDecodeError is a ValueError
        return "the line is not JSON in UTF-8"
    if not isinstance(record, dict):
        return "the line is not a JSON object"
    if type(record.get("n")) is not int:
        return "n is missing or not an integer"
    if record["n"] != n:
        return f"n is {record['n']} where {n} stands: a link was deleted, inserted or moved"
    if record.get("prev") != prev:
        return "prev is not the 64 zeros of the genesis" if n == 0 else f"prev is not the hash of link {n - 1}"
    try:
        if record.get("hash") != seal(record):
            return "hash does not match the link: a field was changed"
        if canonical(record) != line:
            return "the line is not the canonical JSON of its link"
    except (TypeError, ValueError):  # UnicodeEncodeError is a ValueError
        return "the link holds a value canonical JSON cannot write"
    try:
        parse_time(record.get("time"))
        check_signature(record.get("from"))
        check_body(record)
    except Refused as refusal:
        return str(refusal)
    return None


def check_link(record: dict) -> None:
    """A link of the main chain has exactly n, time, task, from, prev and hash; its task is one line."""
    if set(record) != LINK_KEYS:
        raise Refused(f"a link has exactly the fields {', '.join(sorted(LINK_KEYS))}")
    check_text(record["task"], "the task", TEXT_LIMIT)


if os.name == "nt":
    import msvcrt

    def _try_lock(handle) -> bool:
        handle.seek(0)
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            return False
        return True

    def _unlock(handle) -> None:
        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)

else:
    import fcntl

    def _try_lock(handle) -> bool:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            return False
        return True

    def _unlock(handle) -> None:
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class Chain:
    """One append-only JSONL chain file: the links (A) or the constitution (C). A link is only ever added after the
    last; nothing is written to a chain that does not verify."""

    def __init__(self, path: Path, check_body: Callable[[dict], None]):
        self.path = path
        self.check_body = check_body

    @contextlib.contextmanager
    def _locked(self, timeout: float = 10.0):
        """The lock of the chain's readers and writers: an OS lock on <file>.lock, released by the system if its holder
        dies, so a writer never sees half of another's line."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path.with_name(self.path.name + ".lock"), "a+b") as handle:
            give_up = time.monotonic() + timeout
            while not _try_lock(handle):
                if time.monotonic() > give_up:
                    raise Refused(f"another writer has held {self.path.name} for over {timeout:.0f} s")
                time.sleep(0.01)
            try:
                yield
            finally:
                _unlock(handle)

    def _verify(self):
        data = self.path.read_bytes() if self.path.exists() else b""
        lines = data.split(b"\n")
        terminated = lines[-1] == b""
        if terminated:
            lines.pop()
        records, prev = [], GENESIS_PREV
        for n, line in enumerate(lines):
            reason = check_line(line, n, prev, self.check_body)
            if reason is None and n == len(lines) - 1 and not terminated:
                reason = "the last line has no line end: its write was cut short"
            if reason:
                return records, Broken(n, reason)
            records.append(json.loads(line))
            prev = records[-1]["hash"]
        return records, None

    def read(self):
        """The links that hold, recomputed from the genesis, and the first break or None (verify)."""
        if not self.path.exists():
            return [], None
        try:
            with self._locked():
                return self._verify()
        except PermissionError:  # a read-only copy: nobody writes there
            return self._verify()

    def append(self, body: dict, rule: Optional[Callable[[list], Optional[dict]]] = None) -> list:
        """Write one link after the last and return the chain with it. The link gets its n, time, prev and hash here.
        `rule` sees the verified chain first, under the lock: it may refuse the link, or return the fields that depend
        on the chain so far, such as the place of a new question."""
        with self._locked():
            records, broken = self._verify()
            if broken:
                raise Refused(f"{self.path.name} is broken at n={broken.n} ({broken.reason}); "
                              "nothing is written to a broken chain")
            now = utc_now()  # taken before the rule, so what the rule allows by its own clock holds at the link's time
            extra = (rule(records) if rule else None) or {}
            record = dict(body, **extra, n=len(records), time=now, prev=records[-1]["hash"] if records else GENESIS_PREV)
            record["hash"] = seal(record)
            line = canonical(record)
            reason = check_line(line, record["n"], record["prev"], self.check_body)
            if reason:
                raise Refused(reason)
            with open(self.path, "ab", buffering=0) as handle:
                data = line + b"\n"
                while data:
                    data = data[handle.write(data):]
                os.fsync(handle.fileno())
            return records + [record]


def _within(path: Path, folder: Path) -> bool:
    inner, outer = (os.path.normcase(os.path.realpath(p)) for p in (path, folder))
    return inner == outer or inner.startswith(outer.rstrip(os.sep) + os.sep)


def home_folder(given: Optional[str]) -> Path:
    """The data folder: --home, else $TCHAIN_HOME, else .tchain in the current folder. That default is refused when it
    falls inside the git repository that holds this tool, so a chain is never written into the repository unasked."""
    if given:
        return Path(given)
    if os.environ.get("TCHAIN_HOME"):
        return Path(os.environ["TCHAIN_HOME"])
    home = Path.cwd() / ".tchain"
    repository = next((p for p in Path(__file__).resolve().parents if (p / ".git").exists()), None)
    if repository is not None and _within(home, repository):
        raise Refused(f"the default data folder {home} lies inside the repository {repository}; "
                      "give --home DIR or set TCHAIN_HOME")
    return home


def links(args) -> Chain:
    return Chain(home_folder(args.home) / CHAIN_FILE, check_link)


def link_line(record: dict) -> str:
    return f"{record['n']} {record['time']} {record['from']}: {record['task']}"


def plural(count: int, noun: str) -> str:
    return f"{count} {noun}" + ("" if count == 1 else "s")


def warn_broken(broken: Optional[Broken], path: Path) -> None:
    if broken:
        print(f"warning: {path.name} is broken at n={broken.n} ({broken.reason}); only the links before it count",
              file=sys.stderr)


def report_verify(chain: Chain, anchor: Optional[str]) -> int:
    """verify: recompute the chain from its genesis and name the first broken n; with an anchor N:HASH kept elsewhere,
    also prove that link N still has that hash, which catches a cut tail or a chain rewritten whole."""
    records, broken = chain.read()
    if broken:
        print(f"broken at n={broken.n}: {broken.reason}")
        print(f"links 0 to {broken.n - 1} hold" if broken.n else "no link holds")
        return 1
    if anchor:
        number, _, expected = anchor.partition(":")
        if not re.fullmatch(r"[0-9]+", number) or not HASH_RE.fullmatch(expected):
            raise Refused("--anchor is N:HASH, the link number and its 64 lowercase hex digits")
        if int(number) >= len(records):
            print(f"broken: the anchor names link {number}, but the chain ends at n={len(records) - 1}: links were cut off")
            return 1
        if records[int(number)]["hash"] != expected:
            print(f"broken at n={number}: its hash is not the anchor's: the chain was rewritten up to it")
            return 1
    if not records:
        print(f"ok: {chain.path} holds no link yet")
        return 0
    print(f"ok: {plural(len(records), 'link')}, 0 to {len(records) - 1}, recomputed from the genesis"
          + (f"; link {number} matches the anchor" if anchor else ""))
    print(f"head {records[-1]['hash']}")
    return 0


def write_link(args, text: Optional[str], compose: Optional[Callable[[list], str]] = None) -> list:
    """Write one link of the main chain, by `add` or by a task command, and print it. The text is given, or composed
    under the lock from the chain so far (a new task's number). A link tagged [task k] must keep the checklist's rules."""
    signature = check_signature(args.signature)
    words = accept_words(args)
    if text is not None and begins_with(text, handover_words(args)):
        check_handover()

    def rule(records):
        task = check_text((compose(records) if compose else text).strip(), "the link's text", TEXT_LIMIT)
        reason = task_rule(checklist(records, words), chairmen(records, words)[-1] if records else None,
                           {"n": len(records), "time": utc_now(), "from": signature, "task": task}, written=True)
        if reason:
            raise Refused(reason)
        return {"task": task}

    records = links(args).append({"from": signature}, rule)
    print(link_line(records[-1]))
    print_window_news(records, words)
    return records


def cmd_add(args) -> int:
    write_link(args, check_text(args.text.strip(), "the link's text", TEXT_LIMIT))
    return 0


def cmd_verify(args) -> int:
    return report_verify(links(args), args.anchor)


def cmd_recent(args) -> int:
    chain = links(args)
    records, broken = chain.read()
    for record in records[len(records) - min(args.count, len(records)):]:
        print(link_line(record))
    warn_broken(broken, chain.path)
    return 0


def _count(text: str) -> int:
    if not re.fullmatch(r"[0-9]+", text):
        raise argparse.ArgumentTypeError(f"{text!r} is not a count")
    return int(text)


def _link_commands(commands, common) -> None:
    add = commands.add_parser("add", parents=[common], help="append one link: one move")
    add.add_argument("text", help=f"what was done and how it was checked, one line of at most {TEXT_LIMIT} characters")
    add.add_argument("--from", dest="signature", required=True, metavar="SIGNATURE",
                     help="NAME #N [label]: name, agent number, 8-hex session label")
    add.set_defaults(run=cmd_add)
    verify = commands.add_parser("verify", parents=[common], help="recompute the chain from its genesis")
    verify.add_argument("--anchor", metavar="N:HASH", help="a link's hash kept elsewhere, to check against")
    verify.set_defaults(run=cmd_verify)
    recent = commands.add_parser("recent", parents=[common], help="the last N links, one line each")
    recent.add_argument("count", nargs="?", type=_count, default=13, help="how many (default 13)")
    recent.set_defaults(run=cmd_recent)


# ---------------------------------------------------------------------------------------------------------------------
# B. The window tau1

WINDOW = 13  # links in one window tau1
ACCEPT = "Accepted"  # the acceptance words by default; --accept or $TCHAIN_ACCEPT names others


class Window(NamedTuple):
    number: int  # window w holds links 13w to 13w + 12
    first: int
    last: int  # the last link it holds so far
    full: bool
    sealed_by: Optional[int]  # the link that sealed it, or None


def accept_words(args) -> str:
    return getattr(args, "accept", None) or os.environ.get("TCHAIN_ACCEPT") or ACCEPT


def begins_with(text: str, words: str) -> bool:
    """Whether a link's text begins with the words, whole and in their case: "Accepted: ..." and "Accepted" begin with
    "Accepted"; "Acceptedly", "accepted: ..." and "Not accepted" do not."""
    after = text[len(words):len(words) + 1]
    return text.startswith(words) and not (after.isalnum() or after == "_")


def chairmen(records: list, words: str) -> list:
    """The chairman at each link: the signature of the last link, at it or before it, that begins with the acceptance
    words; None while no link does."""
    chairman, result = None, []
    for record in records:
        if begins_with(record["task"], words):
            chairman = record["from"]
        result.append(chairman)
    return result


def last_acceptance(records: list, words: str) -> Optional[dict]:
    return next((record for record in reversed(records) if begins_with(record["task"], words)), None)


def windows(records: list, words: str) -> list:
    """The windows tau1 of a chain. Window w holds links 13w to 13w + 12 and is full once link 13w + 12 is written. A
    full window is sealed by the first later link signed by the chairman of that link's moment; while no link begins
    with the acceptance words there is no chairman, and no window closes."""
    chair = chairmen(records, words)
    sealer = [None] * (len(records) + 1)  # sealer[m]: the first link at m or after it signed by its chairman
    for m in range(len(records) - 1, -1, -1):
        sealer[m] = m if chair[m] is not None and records[m]["from"] == chair[m] else sealer[m + 1]
    result = []
    for first in range(0, len(records), WINDOW):
        last = first + WINDOW - 1
        full = last < len(records)
        result.append(Window(first // WINDOW, first, min(last, len(records) - 1), full, sealer[last + 1] if full else None))
    return result


def window_line(window: Window, records: list) -> str:
    span = f"window {window.number}: links {window.first} to {window.last}"
    if not window.full:
        return f"{span}, {window.last - window.first + 1} of {WINDOW}: open"
    if window.sealed_by is None:
        return f"{span}, full, not sealed: it waits for the chairman's next link"
    return f"{span}, full, sealed by link {window.sealed_by} ({records[window.sealed_by]['from']})"


def chairman_line(records: list, words: str) -> str:
    accepted = last_acceptance(records, words)
    if accepted is None:
        return f'chairman: none -- no link begins with "{words}", so no window closes'
    return f"chairman: {accepted['from']}, accepted at link {accepted['n']}"


def print_window_news(records: list, words: str) -> None:
    """After a link is written: the windows it sealed, or that it filled one."""
    newest = records[-1]["n"]
    for window in windows(records, words):
        if window.sealed_by == newest:
            print(f"seals window {window.number} (links {window.first} to {window.last})")
        elif window.full and window.last == newest:
            print(f"window {window.number} is full: the chairman's next link seals it")


def cmd_windows(args) -> int:
    chain = links(args)
    records, broken = chain.read()
    words = accept_words(args)
    for window in windows(records, words):
        print(window_line(window, records))
    print(chairman_line(records, words) if records else "no link yet, so no window")
    warn_broken(broken, chain.path)
    return 0


def _window_commands(commands, common) -> None:
    command = commands.add_parser("windows", parents=[common], help="the windows tau1: full, sealed, and by whom")
    command.set_defaults(run=cmd_windows)


# ---------------------------------------------------------------------------------------------------------------------
# C. The constitution

CONSTITUTION_FILE = "constitution.jsonl"
ARTICLE_LIMIT = 4000  # characters of an article's text or of a question for the Author
OPERATIONS = {  # each operation's own fields, beside n, time, from, prev, hash, op, word and word_time
    "article": frozenset({"no", "text"}),  # article no now reads text
    "remove": frozenset({"no"}),  # article no no longer stands
    "decide": frozenset({"k", "text"}),  # place k of the line of decisions: a question for the Author
    "decided": frozenset({"k"}),  # the Author's answer to place k
}
CONSTITUTION_ENVELOPE = frozenset({"n", "time", "from", "prev", "hash", "op", "word", "word_time"})


class Constitution(NamedTuple):
    articles: dict  # article number -> the link that set its standing text
    line: list  # the line of decisions: place k at index k - 1, {"ask": link, "answer": link or None}
    rejected: list  # (n, reason) for each link that changes nothing


def check_word(value) -> str:
    """The Author's word: verbatim, so of any length and with any line ends, but never empty."""
    if not isinstance(value, str) or not value.strip():
        raise Refused("the Author's word is empty: only his link changes the constitution")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise Refused("the Author's word is not valid Unicode") from None
    return value


def check_constitution_link(record: dict) -> None:
    """A link of the constitution chain: the envelope of every link, one operation with its fields, and the word, the
    Author's words verbatim with their time (for decide, why the question is his). A link without a word is none."""
    fields = OPERATIONS.get(record.get("op"))
    if fields is None:
        raise Refused(f"op is not one of {', '.join(OPERATIONS)}")
    if set(record) != CONSTITUTION_ENVELOPE | fields:
        raise Refused(f"a {record['op']} link has exactly the fields {', '.join(sorted(CONSTITUTION_ENVELOPE | fields))}")
    if "no" in fields and type(record["no"]) is not int:
        raise Refused("an article's number is an integer")
    if "k" in fields and (type(record["k"]) is not int or record["k"] < 1):
        raise Refused("a place in the line of decisions is a whole number from 1")
    if "text" in fields:
        check_text(record["text"], "the text", ARTICLE_LIMIT, one_line=False)
    check_word(record["word"])
    if parse_time(record["word_time"]) > parse_time(record["time"]):
        raise Refused("the word's time is later than the link's")


def constitution_rule(state: Constitution, record: dict) -> Optional[str]:
    """Why a link would change nothing in the constitution as it stands, or None."""
    if record["op"] == "remove" and record["no"] not in state.articles:
        return f"article {record['no']} does not stand"
    if record["op"] == "decide" and record["k"] != len(state.line) + 1:
        return f"place {record['k']} is out of turn: the next place is {len(state.line) + 1}"
    if record["op"] == "decided":
        if not 1 <= record["k"] <= len(state.line):
            return f"the line of decisions has no place {record['k']}"
        answer = state.line[record["k"] - 1]["answer"]
        if answer is not None:
            return f"place {record['k']} was decided at link {answer['n']}"
    return None


def constitution(records: list) -> Constitution:
    """The standing text and the line of decisions: the verified chain replayed from its genesis. A link that breaks
    the line's order (removing what does not stand, a place out of turn, a second answer) stays in the chain, as every
    link does, and changes nothing."""
    state = Constitution({}, [], [])
    for record in records:
        reason = constitution_rule(state, record)
        if reason:
            state.rejected.append((record["n"], reason))
        elif record["op"] == "article":
            state.articles[record["no"]] = record
        elif record["op"] == "remove":
            del state.articles[record["no"]]
        elif record["op"] == "decide":
            state.line.append({"ask": record, "answer": None})
        else:
            state.line[record["k"] - 1]["answer"] = record
    return state


def constitution_chain(args) -> Chain:
    return Chain(home_folder(args.home) / CONSTITUTION_FILE, check_constitution_link)


def flat(text: str, width: Optional[int] = None) -> str:
    """A text on one line, its line ends shown as " / ", clipped to `width` characters with a final ellipsis."""
    text = " / ".join(part.strip() for part in text.splitlines() if part.strip())
    return text if width is None or len(text) <= width else text[:width - 1] + "\u2026"


def article_line(record: dict) -> str:
    return f"{record['no']}. {record['text']}"


def decision_line(k: int, place: dict, width: Optional[int] = None) -> str:
    ask, answer = place["ask"], place["answer"]
    if answer is None:
        return (f"{k}. open since link {ask['n']}: {flat(ask['text'], width)} -- why it is the Author's: "
                f"{flat(ask['word'], width)}")
    return (f"{k}. decided at link {answer['n']} by the Author's word of {answer['word_time']}: "
            f"{flat(answer['word'], width)} -- the question: {flat(ask['text'], width)}")


def constitution_link_line(record: dict, width: int = 160) -> str:
    what = f"{record['op']} {record['no'] if 'no' in record else record['k']}"
    if "text" in record:
        what += ": " + flat(record["text"], width)
    return (f"{record['n']} {record['time']} {record['from']}: {what} -- word of {record['word_time']}: "
            f"{flat(record['word'], width)}")


def read_word(args) -> str:
    """The Author's word from --word, or verbatim from --word-file: a UTF-8 file, or - for standard input, without a
    leading byte order mark or the one line end an editor puts last. A file is the safe road for a word that begins
    with "/", which Git Bash on Windows rewrites in an argument unless MSYS_NO_PATHCONV=1."""
    if args.word is not None:
        return check_word(args.word)
    data = sys.stdin.buffer.read() if args.word_file == "-" else Path(args.word_file).read_bytes()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise Refused(f"{args.word_file} is not UTF-8") from None
    text = text[1:] if text.startswith("\ufeff") else text
    for end in ("\r\n", "\n"):
        if text.endswith(end):
            text = text[:-len(end)]
            break
    return check_word(text)


def write_constitution(args, body: dict, assign: Optional[Callable[[Constitution], dict]] = None) -> dict:
    """Write one constitution link: the operation, the Author's word verbatim and its time (by default, now)."""
    body = dict(body, word=read_word(args), word_time=args.word_time or utc_now())
    body["from"] = check_signature(args.signature)
    parse_time(body["word_time"])

    def rule(records):
        state = constitution(records)
        extra = assign(state) if assign else {}
        reason = constitution_rule(state, dict(body, **extra))
        if reason:
            raise Refused(reason)
        return extra

    return constitution_chain(args).append(body, rule)[-1]


def cmd_article(args) -> int:
    text = check_text(args.text.strip(), "the article's text", ARTICLE_LIMIT, one_line=False)
    record = write_constitution(args, {"op": "article", "no": args.no, "text": text})
    print(f"{record['n']} {record['time']}: article {record['no']} stands, by the Author's word of {record['word_time']}")
    return 0


def cmd_remove(args) -> int:
    record = write_constitution(args, {"op": "remove", "no": args.no})
    print(f"{record['n']} {record['time']}: article {record['no']} no longer stands, by the Author's word of "
          f"{record['word_time']}")
    return 0


def cmd_decide(args) -> int:
    question = check_text(args.question.strip(), "the question", ARTICLE_LIMIT, one_line=False)
    record = write_constitution(args, {"op": "decide", "text": question}, lambda state: {"k": len(state.line) + 1})
    print(f"{record['n']} {record['time']}: place {record['k']} of the line of decisions waits for the Author: "
          f"{flat(question, 160)}")
    return 0


def cmd_decided(args) -> int:
    record = write_constitution(args, {"op": "decided", "k": args.k})
    print(f"{record['n']} {record['time']}: place {record['k']} decided by the Author's word of {record['word_time']}")
    return 0


def cmd_const_show(args) -> int:
    chain = constitution_chain(args)
    records, broken = chain.read()
    state = constitution(records)
    print(f"constitution: {plural(len(state.articles), 'article')} standing, from {plural(len(records), 'link')} "
          f"of {chain.path.name}")
    for number in sorted(state.articles):
        print(article_line(state.articles[number]))
    waiting = sum(place["answer"] is None for place in state.line)
    print(f"line of decisions: {plural(len(state.line), 'place')}, {waiting} open")
    for k, place in enumerate(state.line, 1):
        print(decision_line(k, place))
    for n, reason in state.rejected:
        print(f"link {n} changes nothing: {reason}")
    warn_broken(broken, chain.path)
    return 0


def cmd_const_log(args) -> int:
    chain = constitution_chain(args)
    records, broken = chain.read()
    for record in records[len(records) - min(args.count, len(records)):]:
        print(constitution_link_line(record))
    warn_broken(broken, chain.path)
    return 0


def cmd_const_verify(args) -> int:
    return report_verify(constitution_chain(args), args.anchor)


def _constitution_commands(commands, common) -> None:
    const = commands.add_parser("const", help="the constitution: a second chain, changed only by the Author's word")
    operations = const.add_subparsers(dest="operation", required=True, metavar="OPERATION")
    author = argparse.ArgumentParser(add_help=False)
    word = author.add_mutually_exclusive_group(required=True)
    word.add_argument("--word", help="the Author's words, verbatim (for decide: why the question is his to decide)")
    word.add_argument("--word-file", metavar="PATH", help="the same words from a UTF-8 file, or - for standard input")
    author.add_argument("--word-time", metavar="TIME", help="when he said them, ISO 8601 UTC (default: now)")
    author.add_argument("--from", dest="signature", required=True, metavar="SIGNATURE",
                        help="NAME #N [label] of the agent who writes the link")
    article = operations.add_parser("article", parents=[common, author], help="article NO now reads TEXT")
    article.add_argument("no", type=int, metavar="NO")
    article.add_argument("text")
    article.set_defaults(run=cmd_article)
    remove = operations.add_parser("remove", parents=[common, author], help="article NO no longer stands")
    remove.add_argument("no", type=int, metavar="NO")
    remove.set_defaults(run=cmd_remove)
    decide = operations.add_parser("decide", parents=[common, author],
                                   help="a question for the Author, into the line of decisions")
    decide.add_argument("question")
    decide.set_defaults(run=cmd_decide)
    decided = operations.add_parser("decided", parents=[common, author], help="the Author's answer to place K")
    decided.add_argument("k", type=int, metavar="K")
    decided.set_defaults(run=cmd_decided)
    show = operations.add_parser("show", parents=[common], help="the standing articles and the line of decisions")
    show.set_defaults(run=cmd_const_show)
    log = operations.add_parser("log", parents=[common], help="the last N constitution links, one line each")
    log.add_argument("count", nargs="?", type=_count, default=13, help="how many (default 13)")
    log.set_defaults(run=cmd_const_log)
    verify = operations.add_parser("verify", parents=[common], help="recompute the constitution chain from its genesis")
    verify.add_argument("--anchor", metavar="N:HASH", help="a link's hash kept elsewhere, to check against")
    verify.set_defaults(run=cmd_const_verify)


# ---------------------------------------------------------------------------------------------------------------------
# D. The open tasks

PLACES = 13  # open tasks at most
PROOFS = ("build", "test", "file")
TIME_PATTERN = r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"
ISSUE_PATTERN = r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+#[1-9][0-9]*"
TAG_RE = re.compile(r"\[task (?P<k>[1-9][0-9]*)\] (?P<move>.+)")
MOVES = (  # what follows the tag; a text that is none of these is a plain move
    ("born", re.compile(rf"born: (?P<title>.+); issue (?P<issue>{ISSUE_PATTERN}); due (?P<due>{TIME_PATTERN}); "
                        r"(?P<pct>[0-9]{1,3})%")),
    ("deadline", re.compile(rf"deadline (?P<due>{TIME_PATTERN})(?:: (?P<why>.+))?")),
    ("progress", re.compile(r"(?P<pct>[0-9]{1,3})%(?:: (?P<note>.+))?")),
    ("blocker", re.compile(r"blocker: (?P<what>.+)")),
    ("closed", re.compile(r"closed by (?P<proof>build|test|file): (?P<ref>.+)")),
)
FILE_REF_RE = re.compile(r"(?P<path>.+):(?P<line>[1-9][0-9]*)")


class Checklist(NamedTuple):
    tasks: dict  # task number -> its state (see checklist)
    rejected: list  # (n, reason) for each tagged link that changes no task
    next_k: int  # the number the next task is born with


def task_move(text: str):
    """A tagged link read by its grammar: (k, kind, fields), or None for a link not tagged [task k]."""
    tag = TAG_RE.fullmatch(text)
    if not tag:
        return None
    for kind, grammar in MOVES:
        match = grammar.fullmatch(tag["move"])
        if match:
            return int(tag["k"]), kind, match.groupdict()
    return int(tag["k"]), "move", {"text": tag["move"]}


def open_tasks(state: Checklist) -> list:
    return [task for task in state.tasks.values() if task["closed"] is None]


def task_rule(state: Checklist, chairman: Optional[str], record: dict, written: bool = False) -> Optional[str]:
    """Why a link tagged [task k] would change no task, or None; a link not tagged is no move on a task. The chairman
    gives a task its birth and moves its deadline, and closes it on a proof he checked; every deadline is in the
    future when set. `written` adds what only the writer can check: that a file and line given as proof exist."""
    move = task_move(record["task"])
    if move is None:
        return None
    k, kind, fields = move
    task = state.tasks.get(k)
    if kind in ("born", "deadline", "closed") and record["from"] != chairman:
        return f"only the chairman ({chairman or 'none yet'}) may write a task's {kind} link"
    if kind == "born":
        if task is not None:
            return f"task {k} was born at link {task['born']}"
        if k != state.next_k:
            return f"a new task is task {state.next_k}, not task {k}"
        if len(open_tasks(state)) >= PLACES:
            return f"all {PLACES} places are taken: a task must close first"
    elif task is None:
        return f"task {k} was never born"
    elif task["closed"] is not None:
        return f"task {k} was closed at link {task['closed']}"
    if "due" in fields and parse_time(fields["due"]) <= parse_time(record["time"]):
        return f"the deadline {fields['due']} is not in the future"
    if "pct" in fields and fields["pct"] is not None and not 0 <= int(fields["pct"]) <= 100:
        return f"{fields['pct']}% is not a percentage from 0 to 100"
    if kind == "closed" and fields["proof"] == "file":
        place = FILE_REF_RE.fullmatch(fields["ref"])
        if not place:
            return "a file proof is PATH:LINE"
        if written:
            return file_proof_problem(Path(place["path"]), int(place["line"]))
    return None


def file_proof_problem(path: Path, line: int) -> Optional[str]:
    """Why a file and line cannot be a proof here, or None: the file must exist and hold that line."""
    if not path.is_file():
        return f"the proof's file {path} does not exist (paths are read from the current folder)"
    data = path.read_bytes()
    lines = data.count(b"\n") + (1 if data and not data.endswith(b"\n") else 0)
    return f"{path} has {lines} lines, so no line {line}" if line > lines else None


def checklist(records: list, words: str) -> Checklist:
    """The open tasks: the links tagged [task k] replayed from the genesis. A tagged link that breaks a rule stays in
    the chain, as every link does, and changes no task."""
    state = Checklist({}, [], 1)
    for record, chairman in zip(records, chairmen(records, words)):
        move = task_move(record["task"])
        if move is None:
            continue
        reason = task_rule(state, chairman, record)
        if reason:
            state.rejected.append((record["n"], reason))
            continue
        k, kind, fields = move
        n = record["n"]
        if kind == "born":
            state.tasks[k] = {"k": k, "title": fields["title"], "issue": fields["issue"], "due": fields["due"],
                              "pct": int(fields["pct"]), "born": n, "last": n, "blocker": None, "closed": None,
                              "proof": None}
            state = state._replace(next_k=k + 1)
            continue
        task = state.tasks[k]
        task["last"] = n
        if kind == "deadline":
            task["due"] = fields["due"]
        elif kind == "blocker":
            task["blocker"] = (n, fields["what"])
        elif kind == "closed":
            task.update(closed=n, proof=(fields["proof"], fields["ref"]), pct=100, blocker=None)
        else:  # progress or a plain move: the work goes on
            task["blocker"] = None
            if kind == "progress":
                task["pct"] = int(fields["pct"])
    return state


def next_task(state: Checklist, besides: int = 0) -> Optional[dict]:
    """The open task to work on next: the unblocked one with the earliest deadline, then the lowest number."""
    ready = [task for task in open_tasks(state) if task["blocker"] is None and task["k"] != besides]
    return min(ready, key=lambda task: (task["due"], task["k"]), default=None)


def task_line(task: dict, now: str, width: Optional[int] = None) -> str:
    """A task in one line: number, percentage, deadline, issue and title, and its blocker or its proof."""
    if task["closed"] is not None:
        proof, ref = task["proof"]
        line = f"[task {task['k']}] closed at link {task['closed']} by {proof}: {ref}, issue {task['issue']}: " \
               f"{task['title']}"
    else:
        overdue = " OVERDUE" if task["due"] < now else ""
        line = f"[task {task['k']}] {task['pct']}% due {task['due']}{overdue}, issue {task['issue']}: {task['title']}"
        if task["blocker"]:
            line += f" -- blocker (link {task['blocker'][0]}): {task['blocker'][1]}"
    return flat(line, width)


def parse_issue(text: str) -> str:
    """A GitHub issue as OWNER/REPO#N, from that form or from its https://github.com/OWNER/REPO/issues/N address."""
    address = re.fullmatch(r"https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/issues/([1-9][0-9]*)/?", text)
    issue = f"{address[1]}#{address[2]}" if address else text
    if not re.fullmatch(ISSUE_PATTERN, issue):
        raise Refused(f"issue {text!r} is not OWNER/REPO#N or https://github.com/OWNER/REPO/issues/N")
    return issue


def cmd_task_add(args) -> int:
    title = check_text(args.title.strip(), "the task's title", TEXT_LIMIT)
    issue, due = parse_issue(args.issue), args.deadline
    parse_time(due)
    records = write_link(args, None, lambda records: f"[task {checklist(records, accept_words(args)).next_k}] born: "
                                                     f"{title}; issue {issue}; due {due}; {args.pct}%")
    print(f"{plural(len(open_tasks(checklist(records, accept_words(args)))), 'open task')} of {PLACES} places")
    return 0


def cmd_task_move(args) -> int:
    text = f"[task {args.k}] {args.text.strip()}"
    kind = task_move(text)[1]
    if kind != "move":
        command = {"born": "add", "closed": "close"}.get(kind, kind)
        raise Refused(f"this text reads as a {kind} link of the grammar; write it with task {command}")
    write_link(args, text)
    return 0


def cmd_task_progress(args) -> int:
    write_link(args, f"[task {args.k}] {args.pct}%" + (f": {args.text.strip()}" if args.text else ""))
    return 0


def cmd_task_blocker(args) -> int:
    records = write_link(args, f"[task {args.k}] blocker: {args.text.strip()}")
    after = next_task(checklist(records, accept_words(args)), besides=args.k)
    print(f"the work goes on: next {task_line(after, utc_now())}" if after else
          "no other open task stands unblocked")
    return 0


def cmd_task_deadline(args) -> int:
    parse_time(args.deadline)
    write_link(args, f"[task {args.k}] deadline {args.deadline}" + (f": {args.text.strip()}" if args.text else ""))
    return 0


def cmd_task_close(args) -> int:
    reference = check_text(args.ref.strip(), "the proof's reference", TEXT_LIMIT)
    write_link(args, f"[task {args.k}] closed by {args.proof}: {reference}")
    return 0


def cmd_task_list(args) -> int:
    chain = links(args)
    records, broken = chain.read()
    state, now = checklist(records, accept_words(args)), utc_now()
    shown = sorted(state.tasks.values(), key=lambda task: task["k"]) if args.all else open_tasks(state)
    print(f"open tasks: {len(open_tasks(state))} of {PLACES} places")
    for task in shown:
        print(task_line(task, now))
    for n, reason in state.rejected:
        print(f"link {n} changes no task: {reason}")
    warn_broken(broken, chain.path)
    return 0


def _percent(text: str) -> int:
    value = _count(text)
    if value > 100:
        raise argparse.ArgumentTypeError(f"{value} is not a percentage from 0 to 100")
    return value


def _task_commands(commands, common) -> None:
    task = commands.add_parser("task", help=f"the open tasks: at most {PLACES}, each move a link tagged [task k]")
    operations = task.add_subparsers(dest="operation", required=True, metavar="OPERATION")
    signed = argparse.ArgumentParser(add_help=False)
    signed.add_argument("--from", dest="signature", required=True, metavar="SIGNATURE", help="NAME #N [label]")
    add = operations.add_parser("add", parents=[common, signed], help="a new task: the chairman's link of its birth")
    add.add_argument("title")
    add.add_argument("--issue", required=True, help="the GitHub issue: OWNER/REPO#N or its address")
    add.add_argument("--deadline", required=True, metavar="TIME", help="ISO 8601 UTC, such as 2026-11-01T15:00:00Z")
    add.add_argument("--pct", type=_percent, default=0, help="the percentage done at birth (default 0)")
    add.set_defaults(run=cmd_task_add)
    move = operations.add_parser("move", parents=[common, signed], help="one move on task K")
    move.add_argument("k", type=int, metavar="K")
    move.add_argument("text")
    move.set_defaults(run=cmd_task_move)
    progress = operations.add_parser("progress", parents=[common, signed], help="task K is PCT percent done")
    progress.add_argument("k", type=int, metavar="K")
    progress.add_argument("pct", type=_percent, metavar="PCT")
    progress.add_argument("text", nargs="?", help="what moved it")
    progress.set_defaults(run=cmd_task_progress)
    blocker = operations.add_parser("blocker", parents=[common, signed],
                                    help="task K waits on a build or on the Author; the work goes on to the next")
    blocker.add_argument("k", type=int, metavar="K")
    blocker.add_argument("text", help="what it waits on")
    blocker.set_defaults(run=cmd_task_blocker)
    deadline = operations.add_parser("deadline", parents=[common, signed], help="the chairman moves task K's deadline")
    deadline.add_argument("k", type=int, metavar="K")
    deadline.add_argument("deadline", metavar="TIME")
    deadline.add_argument("text", nargs="?", help="why the plan changed")
    deadline.set_defaults(run=cmd_task_deadline)
    close = operations.add_parser("close", parents=[common, signed], help="the chairman closes task K by a proof")
    close.add_argument("k", type=int, metavar="K")
    close.add_argument("--proof", required=True, choices=PROOFS, help="a build, a test, or a file and line")
    close.add_argument("--ref", required=True, help="the build or the test run and its exit code, or PATH:LINE")
    close.set_defaults(run=cmd_task_close)
    listing = operations.add_parser("list", parents=[common], help="the open tasks, one line each")
    listing.add_argument("--all", action="store_true", help="the closed tasks too")
    listing.set_defaults(run=cmd_task_list)


# ---------------------------------------------------------------------------------------------------------------------
# F. Measuring the context

USAGE_FIELDS = ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
PREPARE_AT = 150_000  # from here the handover is prepared; below it a handover is refused
STOP_ABOVE = 200_000  # past this the session stops at once
HANDOVER = "Handover"  # the handover words by default; --handover or $TCHAIN_HANDOVER names others
SESSION_RE = re.compile(r"[A-Za-z0-9_-]{1,128}")


class Context(NamedTuple):
    tokens: int  # the sum of the three usage fields
    parts: dict  # each of them
    path: Path  # the session's file
    timestamp: str  # when that message was written


def handover_words(args) -> str:
    return getattr(args, "handover", None) or os.environ.get("TCHAIN_HANDOVER") or HANDOVER


def projects_folder(given: Optional[str] = None) -> Path:
    """Where Claude Code keeps its sessions: --projects, else $CLAUDE_CONFIG_DIR/projects, else ~/.claude/projects."""
    if given:
        return Path(given)
    base = os.environ.get("CLAUDE_CONFIG_DIR")
    return (Path(base) if base else Path.home() / ".claude") / "projects"


def session_file(session: str, projects: Path) -> Path:
    """The file of a session: PROJECT/SESSION_ID.jsonl under the projects folder, in whichever project it is."""
    if not SESSION_RE.fullmatch(session):
        raise Refused(f"{session!r} is not a Claude Code session id")
    found = sorted(projects.glob(f"*/{session}.jsonl"), key=lambda path: path.stat().st_mtime)
    if not found:
        raise Refused(f"no {session}.jsonl under {projects}")
    return found[-1]


def lines_backwards(path: Path, block: int = 1 << 20):
    """The lines of a file from the last to the first, read a block at a time: the end of a long session costs one
    block, not the whole file."""
    with open(path, "rb") as handle:
        position, tail = handle.seek(0, os.SEEK_END), b""
        while position > 0:
            step = min(block, position)
            position -= step
            handle.seek(position)
            lines = (handle.read(step) + tail).split(b"\n")
            tail = lines.pop(0)  # it may begin in the block before
            yield from reversed(lines)
        yield tail


def measure(path: Path) -> Context:
    """The context of a session: the usage of its last assistant message, input_tokens + cache_creation_input_tokens
    + cache_read_input_tokens. A subagent's line (isSidechain), a message Claude Code makes itself (model
    "<synthetic>") and a line still being written are passed over."""
    for line in lines_backwards(path):
        if b'"assistant"' not in line or b'"usage"' not in line:
            continue
        try:
            record = json.loads(line)
        except ValueError:
            continue
        message = record.get("message") if isinstance(record, dict) else None
        if not isinstance(message, dict) or record.get("type") != "assistant" or record.get("isSidechain"):
            continue
        usage = message.get("usage")
        if not isinstance(usage, dict) or message.get("model") == "<synthetic>":
            continue
        parts = {field: usage[field] if type(usage.get(field)) is int else 0 for field in USAGE_FIELDS}
        return Context(sum(parts.values()), parts, path, str(record.get("timestamp") or ""))
    raise Refused(f"{path} holds no assistant message with usage")


def context_rule(tokens: int) -> str:
    """The rule of the turn for a context: below 150k work on, from 150k prepare the handover, past 200k stop."""
    if tokens < PREPARE_AT:
        return f"work on: below {PREPARE_AT // 1000}k a handover is refused"
    if tokens <= STOP_ABOVE:
        return f"prepare the handover: the context is {PREPARE_AT // 1000}k or more"
    return f"stop at once: the context is past {STOP_ABOVE // 1000}k"


def own_context() -> Optional[Context]:
    """The context of the session this tool runs in, found by $CLAUDE_CODE_SESSION_ID; None when it cannot be read."""
    session = os.environ.get("CLAUDE_CODE_SESSION_ID")
    if not session:
        return None
    try:
        return measure(session_file(session, projects_folder()))
    except (Refused, OSError):
        return None


def check_handover() -> None:
    """Below 150k a handover is refused, and the session works on. Where the session's context cannot be read, the
    handover is let through with a warning, since the rule cannot be applied."""
    context = own_context()
    if context is None:
        print("warning: this session's context cannot be read (CLAUDE_CODE_SESSION_ID and its file), so the handover "
              "rule is not applied", file=sys.stderr)
    elif context.tokens < PREPARE_AT:
        raise Refused(f"a handover is refused below {PREPARE_AT:,} tokens, and this session's context is "
                      f"{context.tokens:,}: work on")


def cmd_context(args) -> int:
    if args.file:
        path = Path(args.file)
    else:
        session = args.session or os.environ.get("CLAUDE_CODE_SESSION_ID")
        if not session:
            raise Refused("CLAUDE_CODE_SESSION_ID is not set: give --session ID or --file PATH")
        path = session_file(session, projects_folder(args.projects))
    if not path.is_file():
        raise Refused(f"{path} does not exist")
    context = measure(path)
    parts = context.parts
    print(f"context: {context.tokens:,} tokens = input {parts['input_tokens']:,} + cache creation "
          f"{parts['cache_creation_input_tokens']:,} + cache read {parts['cache_read_input_tokens']:,}")
    print(f"from the last assistant message ({context.timestamp or 'no time'}) of {path}")
    print(f"rule: {context_rule(context.tokens)}")
    return 0


def _context_commands(commands, common) -> None:
    command = commands.add_parser("context", parents=[common], help="this session's context in tokens, and its rule")
    source = command.add_mutually_exclusive_group()
    source.add_argument("--session", metavar="ID", help="a session id (default: $CLAUDE_CODE_SESSION_ID)")
    source.add_argument("--file", metavar="PATH", help="a session's .jsonl file itself")
    command.add_argument("--projects", metavar="DIR", help="Claude Code's projects folder "
                                                           "(default: $CLAUDE_CONFIG_DIR/projects, else ~/.claude/projects)")
    command.set_defaults(run=cmd_context)


# ---------------------------------------------------------------------------------------------------------------------
# E. The start of a session

START_BUDGET = 10_000  # estimated tokens a start may take
SHOWN_QUESTIONS = 13  # open questions of the line of decisions shown in full lines; the rest are counted
LINE_WIDTH = 240  # characters of a question's or a task's line in the start


def estimated_tokens(text: str) -> int:
    """An estimate of a text's tokens: a quarter of its UTF-8 bytes, so about 4 characters of English or 2 of Cyrillic
    to a token. It is an estimate, not a tokenizer's count."""
    return -(-len(text.encode("utf-8")) // 4)


def next_agent_number(records: list, words: str) -> int:
    """The next agent's number: one more than the number in the signature of the last acceptance link, or 1."""
    accepted = last_acceptance(records, words)
    return int(SIGNATURE_RE.fullmatch(accepted["from"])["number"]) + 1 if accepted else 1


def rules_of_the_turn(args, records: list, words: str, handover: Optional[dict]) -> list:
    tool = ("python" if os.name == "nt" else "python3") + f" {Path(__file__).resolve().as_posix()}"
    home = f' --home "{args.home}"' if args.home else ""
    accepted = last_acceptance(records, words)
    name = SIGNATURE_RE.fullmatch(accepted["from"])["name"] if accepted else "NAME"
    session = os.environ.get("CLAUDE_CODE_SESSION_ID", "")[:8].lower()
    label = session if re.fullmatch(r"[0-9a-f]{8}", session) else "LABEL"
    number = next_agent_number(records, words)
    taken = f"handover link {handover['n']}" if handover else "no handover yet"
    return [
        "1. Read the handover and the last link above: they carry the essence. Do not read the whole chain.",
        "2. Write your acceptance link now, and start the work in this same turn:",
        f'   {tool} add "{words}: {taken}. Taking: <one concrete step>" --from "{name} #{number} [{label}]"{home}',
        f"   The signature is the name, the number {number} and the first 8 hex digits of the session id"
        " (CLAUDE_CODE_SESSION_ID).",
        f"3. Every move is one link of at most {TEXT_LIMIT} characters: what was done and how it was checked "
        "(file:line, commit, command and exit code).",
        f"4. A move on a task is tagged: task move|progress|blocker K ... Waiting on a build or on the Author is "
        "task blocker K, and the work goes on to the next task. A task closes only by a proof the chairman checked: "
        "task close K --proof build|test|file --ref ...",
        '5. A question for the Author goes into the line of decisions (const decide "..." --word "..."), never as '
        "plain text, and the work does not stop. Only a link carrying the Author's word changes the constitution.",
        f"6. Measure the context with {tool} context: below {PREPARE_AT // 1000}k a handover is refused, work on; "
        f"from {PREPARE_AT // 1000}k prepare the handover; past {STOP_ABOVE // 1000}k stop at once.",
        f'7. The last link of the session is its handover: "{handover_words(args)} #{number + 1}: done (verified) '
        '...; open ...; next step ...".',
        "8. The chains are append-only: never edit their files. verify names the first broken link.",
    ]


def chain_health(path: Path, records: list, broken: Optional[Broken]) -> str:
    head = f", head {records[-1]['hash'][:12]}" if records else ""
    status = f"BROKEN at n={broken.n} ({broken.reason}): write nothing, restore it" if broken else "verify ok"
    return f"{path.name}: {plural(len(records), 'link')}{head}, {status}"


def start_text(args) -> str:
    """The whole start of a session: the context table, then the parts it measures, each in one line per item."""
    words, now = accept_words(args), utc_now()
    chain, charter = links(args), constitution_chain(args)
    records, broken = chain.read()
    charter_records, charter_broken = charter.read()
    state, tasks = constitution(charter_records), checklist(records, words)
    handover = next((record for record in reversed(records) if begins_with(record["task"], handover_words(args))), None)
    questions = [(k, place) for k, place in enumerate(state.line, 1) if place["answer"] is None]
    more = len(questions) - SHOWN_QUESTIONS
    open_now = open_tasks(tasks)
    parts = [  # (its name in the table, its heading, its lines)
        ("constitution", "the constitution: its standing articles",
         [article_line(state.articles[number]) for number in sorted(state.articles)] or ["(no article stands yet)"]),
        ("questions", "the line of decisions: questions waiting for the Author (why: const show)",
         [f"{k}. open since link {place['ask']['n']}: {flat(place['ask']['text'], LINE_WIDTH - 40)}"
          for k, place in questions[:SHOWN_QUESTIONS]]
         + ([f"... and {more} more: const show"] if more > 0 else []) or ["(no question waits)"]),
        ("open tasks", f"the open tasks: {len(open_now)} of {PLACES} places",
         [task_line(task, now, LINE_WIDTH) for task in open_now] or ["(no open task)"]),
        ("handover", "the last handover", [link_line(handover)] if handover else ["(no handover link yet)"]),
        ("last 13 links", "the last 13 links; the last is the last entry of the journal",
         [link_line(record) for record in records[-13:]] or ["(no link yet)"]),
        ("rules", "the rules of the turn", rules_of_the_turn(args, records, words, handover)),
    ]
    sections = [f"== {heading} ==\n" + "\n".join(lines) + "\n" for _, heading, lines in parts]
    spans = windows(records, words)
    waiting = [window.number for window in spans if window.full and window.sealed_by is None]
    context = own_context()
    rows = [
        ("now (UTC)", now),
        ("next agent", f"number {next_agent_number(records, words)}"),
        ("data folder", str(home_folder(args.home))),
        ("chain", chain_health(chain.path, records, broken)),
        ("windows", f"{sum(window.full for window in spans)} full, "
                    f"{sum(window.sealed_by is not None for window in spans)} sealed"
                    + (f"; window {waiting[0]} waits for the chairman's next link" if waiting else "")),
        ("chairman", chairman_line(records, words).split(": ", 1)[1] if records else "none: no link yet"),
        ("constitution", f"{plural(len(state.articles), 'article')} standing, {len(questions)} of "
                         f"{plural(len(state.line), 'question')} open; "
                         + chain_health(charter.path, charter_records, charter_broken)),
        ("tasks", f"{len(open_now)} open of {PLACES} places, {sum(t['blocker'] is not None for t in open_now)} blocked, "
                  f"{sum(t['due'] < now for t in open_now)} overdue"
                  + (f"; {plural(len(tasks.rejected), 'tagged link')} changing no task" if tasks.rejected else "")),
        ("session context", f"{context.tokens:,} tokens: {context_rule(context.tokens)}" if context else
                            "not measured: CLAUDE_CODE_SESSION_ID or its file is missing"),
    ]

    def render(total: str) -> str:
        table = [f"{name:<17}{value}" for name, value in rows]
        table.append(f"{'part':<17}{'chars':>8}{'~tokens':>9}")
        table += [f"{name:<17}{len(section):>8,}{estimated_tokens(section):>9,}"
                  for (name, _, _), section in zip(parts, sections)]
        table.append(f"{'this start':<17}{total}")
        return (f"tchain start: read it, write your acceptance link, and work in this same turn\n\n"
                "== the context table ==\n" + "\n".join(table) + "\n\n" + "\n".join(sections))

    text = render("")
    for _ in range(8):  # the total counts itself, digits and all: render until it is exact
        settled = render(f"{len(text):>8,}{estimated_tokens(text):>9,}")
        if settled == text:
            break
        text = settled
    if estimated_tokens(text) > START_BUDGET:
        text += (f"\nwarning: this start is about {estimated_tokens(text):,} tokens, above its budget of "
                 f"{START_BUDGET:,}: shorten the constitution or close tasks\n")
    return text


def cmd_start(args) -> int:
    sys.stdout.write(start_text(args))
    return 0


def _start_commands(commands, common) -> None:
    command = commands.add_parser("start", parents=[common], help="all a new session needs, in about 5-10k tokens")
    command.set_defaults(run=cmd_start)


# ---------------------------------------------------------------------------------------------------------------------
# The command line


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--home", metavar="DIR", help="the data folder (default: $TCHAIN_HOME, else ./.tchain)")
    common.add_argument("--accept", metavar="WORDS",
                        help=f'the acceptance words that make a chairman (default: $TCHAIN_ACCEPT, else "{ACCEPT}")')
    common.add_argument("--handover", metavar="WORDS",
                        help=f'the words a handover link begins with (default: $TCHAIN_HANDOVER, else "{HANDOVER}")')
    parser = argparse.ArgumentParser(prog="tchain.py", description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")
    for install in (_start_commands, _link_commands, _window_commands, _constitution_commands, _task_commands,
                    _context_commands):
        install(commands, common)
    return parser


def main(argv: Optional[list] = None) -> int:
    """Run one command. Exit codes: 0 done, 1 a chain does not verify, 2 refused or misused."""
    args = build_parser().parse_args(argv)
    try:
        return args.run(args)
    except Refused as refusal:
        print(f"refused: {refusal}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        with contextlib.suppress(AttributeError, ValueError):
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
