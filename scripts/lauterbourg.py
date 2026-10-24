#!/usr/bin/env python3
"""The shared build of Montana for Android, run from any computer: Lauterbourg builds REF from the shared repository,
this computer takes the APK and checks it, lays it on the site and checks what the site gives back, and with --install
puts it on the phone.

usage: python3 scripts/lauterbourg.py [REF] [--no-site] [--install]
  REF        a branch or a commit of the shared repository (default main); push it there first
  --no-site  build and bring the APK here, lay nothing on the site
  --install  install the APK on the phone over adb and start it

Every build lands on the site at once (the author's word 05.10.2026 21:20 MSK): the site's download/android/ keeps each
build under its number and commit, latest.json names the newest, and the landing page's Android door reads latest.json.
The site counts as having a build only when the copy it serves has the build's own digest."""
import argparse
import hashlib
import json
import os
import subprocess
import urllib.request

import toolchain

PIN, ROOT = toolchain.PIN, toolchain.ROOT
HOST, SITE = PIN["shared_host"], PIN["site"]
HERE_OUT = os.path.join(ROOT, "build", "lauterbourg")


def say(text):
    print(text, flush=True)


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit("failed (%d): %s\n%s" % (r.returncode, " ".join(args), (r.stdout + r.stderr).strip()))
    return r.stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def build(ref):
    say("Lauterbourg builds %s" % ref)
    # the build is silent for minutes while Kotlin compiles, and a silent line was cut on the way (06.10, twice at
    # «3/6 Kotlin»: «Connection reset by peer»); the line speaks every fifteen seconds of its own
    proc = subprocess.Popen(["ssh", "-o", "ServerAliveInterval=15", HOST["ssh"], "python3", HOST["build"], ref], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    record = None
    for line in proc.stdout:
        print("  lau  " + line.rstrip(), flush=True)
        if line.startswith("RESULT "):
            record = json.loads(line[len("RESULT "):])
    if proc.wait() or not record:
        raise SystemExit("the shared build failed (exit %d)" % proc.returncode)
    return record


def bring(record):
    os.makedirs(HERE_OUT, exist_ok=True)
    local = os.path.join(HERE_OUT, "Montana-%d-%s.apk" % (record["build"], record["commit"][:7]))
    run(["scp", "-q", "%s:%s" % (HOST["ssh"], record["apk"]), local])
    if digest(open(local, "rb").read()) != record["sha256"]:
        raise SystemExit("the APK brought here is not the one Lauterbourg built: %s" % local)
    say("here: %s (%d bytes, sha256 %s)" % (os.path.relpath(local, ROOT), os.path.getsize(local), record["sha256"][:16]))
    return local


# runs on each site host: the uploaded part becomes the build's file, then the newest copy and latest.json follow it
LAY = """
import hashlib, json, os, sys
d, name, record = sys.argv[1], sys.argv[2], json.loads(RECORD)
def put(path, data):
    part = path + ".part"
    open(part, "wb").write(data)
    os.rename(part, path)
os.rename(os.path.join(d, "." + name + ".part"), os.path.join(d, name))
data = open(os.path.join(d, name), "rb").read()
put(os.path.join(d, "Montana.apk"), data)
put(os.path.join(d, "latest.json"), (json.dumps(record, ensure_ascii=False, indent=2) + chr(10)).encode())
print(hashlib.sha256(data).hexdigest())
"""


def remote(host, script, args):
    # the script rides stdin: ssh joins its arguments into one line for the remote shell
    return subprocess.run(["ssh", host, "python3", "-"] + args, input=script, capture_output=True, text=True)


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "montana-publish", "Cache-Control": "no-cache"})
    return urllib.request.urlopen(req, timeout=120).read()


def lay_on_site(local, record):
    name = os.path.basename(local)
    shown = dict(build=record["build"], name=record["name"], commit=record["commit"], sha256=record["sha256"],
                 file=name, size=os.path.getsize(local), time=record["time"])
    # what changed, for the Montana room's scroll (iOS MTClientRelease notes and l10n): the app shows the phone's language, else «notes»
    if record.get("notes"):
        shown["notes"] = record["notes"]
    if record.get("l"):
        shown["l"] = record["l"]
    for host in SITE["hosts"]:
        made = remote(host, "import os, sys" + chr(10) + "os.makedirs(sys.argv[1], exist_ok=True)" + chr(10), [SITE["dir"]])
        if made.returncode:
            raise SystemExit("%s: %s" % (host, made.stderr.strip()))
        run(["scp", "-q", local, "%s:%s/.%s.part" % (host, SITE["dir"], name)])
        there = remote(host, "RECORD = %r" % json.dumps(shown, ensure_ascii=False) + chr(10) + LAY, [SITE["dir"], name])
        if there.returncode or there.stdout.strip() != record["sha256"]:
            raise SystemExit("%s did not take the build: %s" % (host, (there.stdout + there.stderr).strip()))
        say("site: %s holds %s" % (host, name))
    # the holder's own word: what the site serves, byte for byte
    served = fetch(SITE["url"] + name)
    if digest(served) != record["sha256"]:
        raise SystemExit("the site serves other bytes under %s" % (SITE["url"] + name))
    latest = json.loads(fetch(SITE["url"] + "latest.json"))
    if latest.get("build") != record["build"]:
        raise SystemExit("the site's latest.json names build %s" % latest.get("build"))
    say("site: %s%s serves the build (%d bytes, sha256 %s)" % (SITE["url"], name, len(served), record["sha256"][:16]))


def subject_note(commit):
    """The commit's subject up to its first parenthesis or colon: the change in a line (the rest names the iOS sources)."""
    got = subprocess.run(["git", "-C", ROOT, "log", "-1", "--format=%s", commit], capture_output=True, text=True)
    line = got.stdout.strip()
    for cut in (" (", ": "):
        if cut in line:
            line = line.split(cut, 1)[0]
    return [line[:160]] if line else []


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ref", nargs="?", default="main")
    ap.add_argument("--no-site", action="store_true")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--note", action="append", default=[], help="a line of what changed (English); default: the commit's subject")
    ap.add_argument("--note-ru", action="append", default=[], help="the same line in Russian")
    a = ap.parse_args()
    record = build(a.ref)
    record["notes"] = a.note or subject_note(record["commit"])
    if a.note_ru:
        record["l"] = {"en": record["notes"], "ru": a.note_ru}
    local = bring(record)
    if not a.no_site:
        lay_on_site(local, record)
    if a.install:
        import build as local_build
        local_build.install(toolchain.Paths(), local)
    say("Montana %s, %s" % (record["name"], record["commit"][:12]))


if __name__ == "__main__":
    main()
