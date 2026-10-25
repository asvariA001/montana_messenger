#!/usr/bin/env python3
"""tchain: DinetPDA's own minimum of the council's time-chain architecture, parts A to F of issue #30.

A. One move is one link of an append-only JSONL chain, sealed by SHA-256: one hash seals everything before it.

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

    def append(self, body: dict, rule: Optional[Callable[[list], None]] = None) -> list:
        """Write one link after the last and return the chain with it. The link gets its n, time, prev and hash here;
        `rule` sees the verified chain first and may refuse the link."""
        with self._locked():
            records, broken = self._verify()
            if broken:
                raise Refused(f"{self.path.name} is broken at n={broken.n} ({broken.reason}); "
                              "nothing is written to a broken chain")
            if rule:
                rule(records)
            record = dict(body, n=len(records), time=utc_now(), prev=records[-1]["hash"] if records else GENESIS_PREV)
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


def cmd_add(args) -> int:
    text = check_text(args.text.strip(), "the link's text", TEXT_LIMIT)
    records = links(args).append({"task": text, "from": check_signature(args.signature)})
    print(link_line(records[-1]))
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
# The command line


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--home", metavar="DIR", help="the data folder (default: $TCHAIN_HOME, else ./.tchain)")
    parser = argparse.ArgumentParser(prog="tchain.py", description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")
    for install in (_link_commands,):
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
