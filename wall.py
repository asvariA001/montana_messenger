#!/usr/bin/env python3
# Writes the Android master's TimeChain in the same shape as github.com/efir369999/montana_messenger:
# one JSON line, SHA-256 of the canonical body (verify.py), newest first, the wall in TimeChain_Master.md.
# The seal is not stored: the hash as a big-endian number in base 29, Gematria Primus runes.
import argparse, datetime, json, os, sys

import verify

HERE = os.path.dirname(os.path.abspath(__file__))
CHAIN = os.path.join(HERE, "TimeChain_Master.jsonl")
WALL = os.path.join(HERE, "TimeChain_Master.md")
MASTER = "Master 1 · Grok 4.6"
ORDER = ("n", "time", "master", "kind", "prev", "thread", "text", "hash")
PRIMES = (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109)


def seal_of(h):
    n, out = int(h, 16), []
    if n == 0:
        return chr(verify.RUNES[0])
    while n:
        n, r = divmod(n, 29)
        out.append(verify.RUNES[r])
    out.reverse()
    return "".join(chr(c) for c in out)


def gematria_of(seal):
    rank = {verify.RUNES[i]: PRIMES[i] for i in range(len(verify.RUNES))}
    return sum(rank[ord(c)] for c in seal)


def msk(stamp):
    d = verify.instant(stamp) + datetime.timedelta(hours=3)
    if "." in stamp:
        return d.strftime("%d.%m.%Y %H:%M:%S.") + "%03d MSK" % (d.microsecond // 1000)
    return d.strftime("%d.%m.%Y %H:%M:%S MSK")


def load():
    if not os.path.exists(CHAIN):
        return []
    return verify.read(CHAIN)


def dump(recs):
    newest = list(reversed(recs))
    with open(CHAIN, "w", encoding="utf-8") as f:
        for r in newest:
            row = {k: r[k] for k in ORDER if k in r}
            if r.get("closed"):
                row["closed"] = True
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def render(recs):
    head = recs[-1]["hash"] if recs else verify.ZERO
    shut = sum(1 for r in recs if r.get("closed"))
    lines = [
        "# The Master's Chain of Thoughts",
        "",
        "Elegance. Aesthetics. Security.",
        "",
        "Montana Android. How to read a link: hash is SHA-256 over the record's body together with the hash of the record before it (prev), so each hash seals the whole chain before it: a row's prev is the hash of the row below it, the genesis has 64 zeros for prev, and changing or reordering any earlier record breaks every hash after it.",
        "",
        "This wall is the same shape as the public iOS master's wall at [github.com/efir369999/montana_messenger](https://github.com/efir369999/montana_messenger). It is not that chain: it is this client's, from its own genesis. The raw chain is TimeChain_Master.jsonl, checked by `python3 verify.py`. A record is appended with `python3 wall.py add --kind word --text \"...\"`.",
        "",
        f"Holds -- {len(recs)} records from genesis, {shut} closed, head `{head}`.",
        "",
        "## The wall, newest first",
        "",
    ]
    for r in reversed(recs):
        body = r["text"] if not r.get("closed") else "[closed: the text is withheld; the record after it binds its hash]"
        seal = seal_of(r["hash"])
        lines.append(
            f"- {r['n']} · {msk(r['time'])} · hash `{r['hash']}` · prev `{r['prev']}` · {r['master']} · {r['kind']}: {body}"
        )
        lines.append(f" - seal {seal} · gematria {gematria_of(seal)}")
    lines.append("")
    with open(WALL, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def now_stamp():
    d = datetime.datetime.utcnow()
    return d.strftime("%Y-%m-%dT%H:%M:%S.") + "%03dZ" % (d.microsecond // 1000)


def add(kind, text, master, thread, when):
    recs = load()
    prev = recs[-1]["hash"] if recs else verify.ZERO
    r = {
        "n": len(recs),
        "time": when or now_stamp(),
        "master": master,
        "kind": kind,
        "prev": prev,
        "thread": thread,
        "text": text,
        "hash": "",
    }
    r["hash"] = verify.digest(r)
    recs.append(r)
    bad, _ = verify.check(recs)
    if bad:
        raise SystemExit(f"refused: {bad}")
    dump(recs)
    render(recs)
    print(r["n"], r["hash"])


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("--kind", required=True, choices=verify.KINDS)
    a.add_argument("--text", required=True)
    a.add_argument("--master", default=MASTER)
    a.add_argument("--thread", nargs="*", default=[])
    a.add_argument("--time", default="")
    r = sub.add_parser("render")
    args = p.parse_args()
    if args.cmd == "render":
        recs = load()
        bad, _ = verify.check(recs) if recs else (None, 0)
        if bad:
            raise SystemExit(f"BROKEN -- {bad}")
        render(recs)
        return 0
    add(args.kind, args.text, args.master, args.thread, args.time)
    return 0


if __name__ == "__main__":
    sys.exit(main())
