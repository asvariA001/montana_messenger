"""Tests of tchain.py (issue #30). From the repository root: python -m unittest discover -s agents/dinetpda -v"""

import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tchain  # noqa: E402

CHAIR = "Claude of DinetPDA #1 [aaaaaaaa]"
NEXT_CHAIR = "Claude of DinetPDA #2 [bbbbbbbb]"
HELPER = "subagent of DinetPDA #1 [cccccccc]"
VARIABLES = ("TCHAIN_HOME", "TCHAIN_ACCEPT", "TCHAIN_HANDOVER", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CONFIG_DIR")


def definition_hash(record):
    """The hash as issue #30 defines it, computed here without tchain: SHA-256 over the record's JSON with sorted keys,
    every field but hash, prev included."""
    body = {key: value for key, value in record.items() if key != "hash"}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
                          .encode("utf-8")).hexdigest()


@contextlib.contextmanager
def working_directory(path):
    before = os.getcwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(before)


class ChainCase(unittest.TestCase):
    """A fresh data folder in a temporary directory, and an environment without tchain's or Claude Code's variables."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name) / "home"
        environment = mock.patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        for name in VARIABLES:
            os.environ.pop(name, None)

    def cli(self, *argv, home=True):
        """Run the command line in this process: (exit code, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = tchain.main(list(argv) + (["--home", str(self.home)] if home else []))
            except SystemExit as exit_:  # argparse refuses a misused command this way
                code = exit_.code
        return code, out.getvalue(), err.getvalue()

    def ok(self, *argv):
        code, out, err = self.cli(*argv)
        self.assertEqual(code, 0, msg=f"{argv}: {err or out}")
        return out

    def refused(self, *argv):
        code, out, err = self.cli(*argv)
        self.assertEqual(code, 2, msg=f"{argv} was not refused: {out}")
        return err

    def lines(self, name=tchain.CHAIN_FILE):
        return (self.home / name).read_bytes().split(b"\n")[:-1]

    def write_lines(self, lines, name=tchain.CHAIN_FILE):
        (self.home / name).write_bytes(b"".join(line + b"\n" for line in lines))

    def records(self, name=tchain.CHAIN_FILE):
        return [json.loads(line) for line in self.lines(name)]

    def add(self, text, signature=CHAIR):
        return self.ok("add", text, "--from", signature)


class TheLink(ChainCase):
    """A: one move is one record; its hash covers every field but itself, prev included."""

    def test_append_then_verify_recomputes_from_the_genesis(self):
        for i in range(3):
            self.add(f"move {i}")
        out = self.ok("verify")
        self.assertIn("ok: 3 links, 0 to 2", out)
        prev = "0" * 64
        for n, line in enumerate(self.lines()):
            record = json.loads(line)
            self.assertEqual(set(record), {"n", "time", "task", "from", "prev", "hash"})
            self.assertEqual((record["n"], record["prev"], record["from"]), (n, prev, CHAIR))
            self.assertEqual(record["hash"], definition_hash(record))
            self.assertEqual(line, json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
                             .encode("utf-8"))
            self.assertRegex(record["time"], r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
            prev = record["hash"]
        self.assertIn(f"head {prev}", out)
        self.assertNotIn(b"\r", (self.home / tchain.CHAIN_FILE).read_bytes())

    def test_the_hash_is_pinned_by_a_vector(self):
        genesis = {"n": 0, "time": "2026-10-25T15:00:00Z", "task": "Принято: the genesis",
                   "from": "Claude of DinetPDA #1 [70662df9]", "prev": "0" * 64}
        vector = "0bf1068d5f82be6aa3455c3125c627ab82c914d01627b5af5e7cb08afd9dccba"
        self.assertEqual(definition_hash(genesis), vector)
        self.assertEqual(tchain.seal(genesis), vector)
        self.assertEqual(tchain.seal(dict(genesis, hash="anything")), vector)

    def test_non_ascii_is_written_as_itself(self):
        self.add("звено № 1")
        self.assertIn("звено № 1".encode("utf-8"), self.lines()[0])

    def test_a_task_is_one_line_of_at_most_280_characters(self):
        self.add("x" * 280)
        for text in ("x" * 281, "two\nlines", "a\ttab", "   "):
            self.refused("add", text, "--from", CHAIR)
        self.assertEqual(len(self.lines()), 1)

    def test_a_signature_is_name_number_and_an_8_hex_label(self):
        for signature in ("Claude", "Claude #1", "Claude [aaaaaaaa]", "Claude #1 [AAAAAAAA]", "Claude #1 [aaaaaaa]",
                          "Claude #0 [aaaaaaaa]", " Claude #1 [aaaaaaaa]", "Cl#aude #1 [aaaaaaaa]", "Claude #01 [aaaaaaaa]"):
            self.refused("add", "x", "--from", signature)
        self.assertFalse((self.home / tchain.CHAIN_FILE).exists())

    def test_recent_prints_the_last_n_links(self):
        for i in range(20):
            self.add(f"move {i}")
        out = self.ok("recent", "3").splitlines()
        self.assertEqual([line.split(": ", 1)[1] for line in out], ["move 17", "move 18", "move 19"])
        self.assertEqual(len(self.ok("recent").splitlines()), 13)
        self.assertEqual(self.ok("recent", "0"), "")

    def test_an_empty_chain_verifies(self):
        self.assertIn("holds no link yet", self.ok("verify"))

    def test_two_writers_at_once_keep_one_chain(self):
        chain = tchain.Chain(self.home / tchain.CHAIN_FILE, tchain.check_link)
        errors = []

        def write(signature):
            try:
                for i in range(15):
                    chain.append({"task": f"move {i}", "from": signature})
            except Exception as error:  # noqa: BLE001 -- reported below
                errors.append(error)

        writers = [threading.Thread(target=write, args=(signature,)) for signature in (CHAIR, HELPER)]
        for writer in writers:
            writer.start()
        for writer in writers:
            writer.join()
        self.assertEqual(errors, [])
        records, broken = chain.read()
        self.assertIsNone(broken)
        self.assertEqual(len(records), 30)


class Tampering(ChainCase):
    """A: any change to a written link makes verify fail, and verify names the first broken n."""

    def setUp(self):
        super().setUp()
        for i in range(6):
            self.add(f"move {i}")
        self.original = self.lines()

    def broken_at(self, n):
        code, out, _ = self.cli("verify")
        self.assertEqual(code, 1, out)
        self.assertIn(f"broken at n={n}:", out)
        return out

    def rewrite(self, n, **fields):
        lines = list(self.original)
        lines[n] = tchain.canonical(dict(json.loads(lines[n]), **fields))
        self.write_lines(lines)

    def test_a_task(self):
        self.rewrite(3, task="another move")
        self.assertIn("hash does not match", self.broken_at(3))

    def test_a_time(self):
        self.rewrite(3, time="2026-01-01T00:00:00Z")
        self.broken_at(3)

    def test_a_signature(self):
        self.rewrite(3, **{"from": NEXT_CHAIR})
        self.broken_at(3)

    def test_a_prev(self):
        self.rewrite(3, prev="f" * 64)
        self.assertIn("prev is not the hash of link 2", self.broken_at(3))

    def test_the_genesis_prev(self):
        self.rewrite(0, prev="1" * 64)
        self.assertIn("64 zeros", self.broken_at(0))

    def test_a_hash(self):
        self.rewrite(3, hash="e" * 64)
        self.broken_at(3)

    def test_a_deleted_record(self):
        self.write_lines(self.original[:3] + self.original[4:])
        self.assertIn("n is 4 where 3 stands", self.broken_at(3))

    def test_reordered_records(self):
        lines = list(self.original)
        lines[3], lines[4] = lines[4], lines[3]
        self.write_lines(lines)
        self.broken_at(3)

    def test_an_inserted_record(self):
        self.write_lines(self.original[:3] + [self.original[2]] + self.original[3:])
        self.broken_at(3)

    def test_a_link_resealed_after_a_change_is_caught_by_the_next(self):
        record = dict(json.loads(self.original[3]), task="another move")
        record["hash"] = tchain.seal(record)
        lines = list(self.original)
        lines[3] = tchain.canonical(record)
        self.write_lines(lines)
        self.assertIn("prev is not the hash of link 3", self.broken_at(4))

    def test_a_cut_tail_is_caught_by_an_anchor(self):
        head = json.loads(self.original[5])["hash"]
        self.write_lines(self.original[:4])
        self.assertEqual(self.cli("verify")[0], 0)  # what is left is still a whole chain
        code, out, _ = self.cli("verify", "--anchor", f"5:{head}")
        self.assertEqual(code, 1)
        self.assertIn("ends at n=3", out)
        self.write_lines(self.original)
        self.assertIn("matches the anchor", self.ok("verify", "--anchor", f"5:{head}"))

    def test_a_line_not_in_canonical_form(self):
        lines = list(self.original)
        lines[2] = lines[2].replace(b'","', b'", "', 1)
        self.write_lines(lines)
        self.assertIn("not the canonical JSON", self.broken_at(2))

    def test_a_cut_write(self):
        data = b"".join(line + b"\n" for line in self.original)
        (self.home / tchain.CHAIN_FILE).write_bytes(data[:-10])
        self.broken_at(5)
        (self.home / tchain.CHAIN_FILE).write_bytes(data[:-1])
        self.assertIn("no line end", self.broken_at(5))

    def test_a_line_that_is_not_json(self):
        self.write_lines(self.original[:2] + [b"edited by hand"] + self.original[3:])
        self.broken_at(2)

    def test_crlf_line_ends_change_no_field(self):
        (self.home / tchain.CHAIN_FILE).write_bytes(b"".join(line + b"\r\n" for line in self.original))
        self.ok("verify")

    def test_nothing_is_written_to_a_broken_chain(self):
        self.rewrite(2, task="changed")
        before = (self.home / tchain.CHAIN_FILE).read_bytes()
        self.assertIn("broken at n=2", self.refused("add", "one more", "--from", CHAIR))
        self.assertEqual((self.home / tchain.CHAIN_FILE).read_bytes(), before)


class Windows(ChainCase):
    """B: 13 links make a window; a full window is sealed by the chairman's next signed link and by no other."""

    def fill(self, count, signature=CHAIR):
        for i in range(count):
            self.add(f"move {i}", signature)

    def test_a_full_window_is_sealed_by_the_chairmans_next_link_only(self):
        self.add("Accepted: start read; taking the first task")  # link 0 makes CHAIR the chairman
        self.fill(11)
        self.assertIn("window 0: links 0 to 11, 12 of 13: open", self.ok("windows"))
        self.assertIn("window 0 is full", self.add("the thirteenth link"))  # link 12 fills window 0
        self.assertIn("window 0: links 0 to 12, full, not sealed", self.ok("windows"))
        self.assertNotIn("seals", self.add("a helper's move", HELPER))  # link 13 is not the chairman's
        self.assertIn("window 0: links 0 to 12, full, not sealed", self.ok("windows"))
        self.assertIn("seals window 0 (links 0 to 12)", self.add("the chairman's move"))  # link 14
        out = self.ok("windows")
        self.assertIn(f"window 0: links 0 to 12, full, sealed by link 14 ({CHAIR})", out)
        self.assertIn("window 1: links 13 to 14, 2 of 13: open", out)
        self.assertIn(f"chairman: {CHAIR}, accepted at link 0", out)

    def test_without_the_acceptance_words_no_window_closes(self):
        self.fill(30)
        out = self.ok("windows")
        self.assertIn("window 0: links 0 to 12, full, not sealed", out)
        self.assertIn("window 1: links 13 to 25, full, not sealed", out)
        self.assertIn("window 2: links 26 to 29, 4 of 13: open", out)
        self.assertIn('chairman: none -- no link begins with "Accepted"', out)

    def test_the_chairman_is_whoever_last_wrote_the_acceptance_words(self):
        self.add("Accepted: the first session", CHAIR)  # link 0
        self.fill(12, HELPER)  # links 1 to 12 fill window 0
        self.add("Accepted: handover of agent 1", NEXT_CHAIR)  # link 13: the new chairman's first link seals it
        self.fill(12, CHAIR)  # links 14 to 25, by the chairman before, seal nothing
        out = self.ok("windows")
        self.assertIn(f"window 0: links 0 to 12, full, sealed by link 13 ({NEXT_CHAIR})", out)
        self.assertIn("window 1: links 13 to 25, full, not sealed", out)
        self.assertIn(f"chairman: {NEXT_CHAIR}, accepted at link 13", out)
        self.add("the chairman's move", NEXT_CHAIR)  # link 26
        self.assertIn(f"window 1: links 13 to 25, full, sealed by link 26 ({NEXT_CHAIR})", self.ok("windows"))

    def test_the_words_begin_the_link_whole_and_in_their_case(self):
        for text in ("Accepted: x", "Accepted", "Accepted, taking item 12"):
            self.assertTrue(tchain.begins_with(text, "Accepted"), text)
        for text in ("Acceptedly", "accepted: x", "Not Accepted", "Accepted_x", " Accepted"):
            self.assertFalse(tchain.begins_with(text, "Accepted"), text)

    def test_other_acceptance_words(self):
        self.add("Принял: start read")
        self.fill(13)
        self.assertIn("full, not sealed", self.ok("windows"))
        self.assertIn("full, sealed by link 13", self.ok("windows", "--accept", "Принял"))
        os.environ["TCHAIN_ACCEPT"] = "Принял"
        self.assertIn("full, sealed by link 13", self.ok("windows"))


class TheConstitution(ChainCase):
    """C: a second chain of the same envelope; only a link carrying the Author's word changes the standing text."""

    def const(self, *argv):
        return self.ok("const", *argv)

    def state(self):
        chain = tchain.Chain(self.home / tchain.CONSTITUTION_FILE, tchain.check_constitution_link)
        records, broken = chain.read()
        return tchain.constitution(records), broken

    def standing(self):
        return {number: record["text"] for number, record in self.state()[0].articles.items()}

    def forge(self, **fields):
        """Append a correctly hashed link past the tool, as a hand with write access could."""
        lines = self.lines(tchain.CONSTITUTION_FILE)
        record = dict({"n": len(lines), "time": "2026-10-25T12:00:00Z", "from": CHAIR,
                       "prev": json.loads(lines[-1])["hash"], "word_time": "2026-10-25T12:00:00Z"}, **fields)
        record = {key: value for key, value in record.items() if value is not None}
        record["hash"] = tchain.seal(record)
        self.write_lines(lines + [tchain.canonical(record)], tchain.CONSTITUTION_FILE)

    def test_articles_are_set_replaced_and_removed_by_the_authors_word(self):
        self.const("article", "0", "Elegance. Aesthetics. Security.", "--word", "/// put it first", "--from", CHAIR)
        self.const("article", "-3", "Time is the metadata of reality.", "--word", "/// yes", "--from", CHAIR)
        self.const("article", "1", "The agent wins time.", "--word", "/// yes", "--from", CHAIR)
        self.const("article", "1", "The agent wins time for the Author.", "--word", "/// for me", "--from", CHAIR)
        self.const("remove", "0", "--word", "/// drop it", "--from", CHAIR)
        self.assertEqual(self.standing(), {-3: "Time is the metadata of reality.", 1: "The agent wins time for the Author."})
        out = self.const("show")
        self.assertIn("2 articles standing", out)
        self.assertLess(out.index("-3. Time is"), out.index("1. The agent wins time for the Author."))
        self.assertIn("ok: 5 links", self.const("verify"))
        records = self.records(tchain.CONSTITUTION_FILE)
        self.assertEqual([record["op"] for record in records], ["article"] * 4 + ["remove"])
        self.assertEqual(set(records[4]), {"n", "time", "from", "prev", "hash", "op", "no", "word", "word_time"})
        self.assertEqual(records[0]["prev"], "0" * 64)
        self.assertEqual(len(self.const("log").splitlines()), 5)

    def test_the_word_is_kept_verbatim_with_its_time(self):
        word = "/// да\n  to both questions  "
        self.const("article", "2", "text", "--word", word, "--word-time", "2026-01-02T10:58:00Z", "--from", CHAIR)
        record = self.records(tchain.CONSTITUTION_FILE)[0]
        self.assertEqual((record["word"], record["word_time"]), (word, "2026-01-02T10:58:00Z"))
        self.refused("const", "article", "2", "text", "--word", "w", "--word-time", "2099-01-01T00:00:00Z",
                     "--from", CHAIR)
        self.refused("const", "article", "2", "text", "--word", "w", "--word-time", "25.10.2026 10:58", "--from", CHAIR)

    def test_the_word_from_a_file_or_standard_input(self):
        path = Path(self.tmp.name) / "word.txt"
        path.write_bytes("﻿/// да\r\nsecond line\r\n".encode("utf-8"))
        self.const("article", "4", "text", "--word-file", str(path), "--from", CHAIR)
        stdin = io.TextIOWrapper(io.BytesIO("/// нет\n".encode("utf-8")), encoding="utf-8")
        with mock.patch.object(sys, "stdin", stdin):
            self.const("article", "4", "text two", "--word-file", "-", "--from", CHAIR)
        words = [record["word"] for record in self.records(tchain.CONSTITUTION_FILE)]
        self.assertEqual(words, ["/// да\r\nsecond line", "/// нет"])
        self.refused("const", "article", "4", "text", "--word", "w", "--word-file", str(path), "--from", CHAIR)

    def test_without_the_authors_word_nothing_changes(self):
        self.const("article", "0", "Elegance.", "--word", "/// yes", "--from", CHAIR)
        self.refused("const", "article", "0", "Changed.", "--from", CHAIR)
        self.refused("const", "article", "0", "Changed.", "--word", "  ", "--from", CHAIR)
        self.refused("const", "remove", "0", "--from", CHAIR)
        self.assertEqual(self.standing(), {0: "Elegance."})
        before = self.lines(tchain.CONSTITUTION_FILE)
        for word in ("", None):  # an empty word, and no word at all, written past the tool with a correct hash
            with self.subTest(word=word):
                self.write_lines(before, tchain.CONSTITUTION_FILE)
                self.forge(op="article", no=0, text="Changed.", word=word)
                state, broken = self.state()
                self.assertEqual(broken.n, 1)
                self.assertEqual({n: r["text"] for n, r in state.articles.items()}, {0: "Elegance."})
                self.assertIn("broken at n=1", self.cli("const", "verify")[1])
                self.assertIn("broken at n=1", self.refused("const", "article", "0", "Again.", "--word", "/// yes",
                                                            "--from", CHAIR))

    def test_the_line_of_decisions(self):
        out = self.const("decide", "Shall a window close by a quorum of the living?", "--word",
                         "Gate 0: the consensus is his to choose", "--from", CHAIR)
        self.assertIn("place 1 of the line of decisions", out)
        self.assertIn("place 2", self.const("decide", "Shall the ports walk by day?", "--word",
                                            "the exception names the iOS client only", "--from", CHAIR))
        self.const("decided", "1", "--word", "/// да", "--word-time", "2026-01-02T10:58:00Z", "--from", CHAIR)
        self.assertIn("decided at link 2", self.refused("const", "decided", "1", "--word", "/// again", "--from", CHAIR))
        self.assertIn("no place 3", self.refused("const", "decided", "3", "--word", "/// ?", "--from", CHAIR))
        state, broken = self.state()
        self.assertIsNone(broken)
        self.assertEqual([place["answer"] is None for place in state.line], [False, True])
        self.assertEqual(state.line[0]["answer"]["word"], "/// да")
        self.assertEqual(state.articles, {})  # an answer is not an article: the Author's article link changes the text
        show = self.const("show")
        self.assertIn("line of decisions: 2 places, 1 open", show)
        self.assertIn("1. decided at link 2 by the Author's word of 2026-01-02T10:58:00Z", show)
        self.assertIn("2. open since link 1: Shall the ports walk by day?", show)

    def test_a_link_out_of_order_changes_nothing(self):
        self.const("article", "5", "Build the Economy of Time.", "--word", "/// yes", "--from", CHAIR)
        self.refused("const", "remove", "7", "--word", "/// remove 7", "--from", CHAIR)
        self.forge(op="remove", no=7, word="/// remove 7")
        self.forge(op="decide", k=4, text="A question out of turn", word="why")
        state, broken = self.state()
        self.assertIsNone(broken)  # each link is whole; the replay of the constitution rejects them
        self.assertEqual([n for n, _ in state.rejected], [1, 2])
        self.assertEqual((self.standing(), state.line), ({5: "Build the Economy of Time."}, []))
        show = self.const("show")
        self.assertIn("link 1 changes nothing: article 7 does not stand", show)
        self.assertIn("link 2 changes nothing: place 4 is out of turn", show)


class DataFolder(ChainCase):
    """The data folder: --home, else $TCHAIN_HOME, else ./.tchain, never inside the tool's repository by default."""

    def test_the_default_inside_the_repository_is_refused(self):
        repository = next((p for p in Path(tchain.__file__).resolve().parents if (p / ".git").exists()), None)
        if repository is None:
            self.skipTest("tchain.py is not inside a git repository here")
        with working_directory(repository / "agents"):
            code, _, err = self.cli("add", "x", "--from", CHAIR, home=False)
        self.assertEqual(code, 2)
        self.assertIn("--home", err)
        self.assertFalse((repository / "agents" / ".tchain").exists())

    def test_the_default_is_dot_tchain_in_the_current_folder(self):
        with working_directory(self.tmp.name):
            self.assertEqual(self.cli("add", "x", "--from", CHAIR, home=False)[0], 0)
        self.assertTrue((Path(self.tmp.name) / ".tchain" / tchain.CHAIN_FILE).exists())

    def test_the_environment_variable(self):
        os.environ["TCHAIN_HOME"] = str(self.home)
        self.assertEqual(self.cli("add", "x", "--from", CHAIR, home=False)[0], 0)
        self.assertEqual(len(self.records()), 1)


if __name__ == "__main__":
    unittest.main()
