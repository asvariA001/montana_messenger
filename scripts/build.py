#!/usr/bin/env python3
"""Builds Montana for Android with the platform's own tools only -- no Gradle, no libraries -- the same on every computer.

    the core     cargo ndk   native/montana-jni   →  libmontana.so (the core's crates, built by the core's own Rust)
    resources    aapt2       app/src/main/res     →  R.java and a resource package
    R.java       javac
    our Kotlin   kotlinc
    dex          d8          the classes          →  classes.dex (the phone's bytecode)
    package      the dex and the core go into the resource package
    sign         zipalign + apksigner             →  build/Montana.apk

The tools are the ones scripts/toolchain.json pins (python3 scripts/setup.py puts them in place). Every step runs in a
fresh folder of its own, so nothing from an earlier build can ride into this one."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

import toolchain

PIN, ROOT, HERE = toolchain.PIN, toolchain.ROOT, toolchain.HERE
MIN_SDK = "26"
BUILD = os.path.join(ROOT, "build")
APK = os.path.join(BUILD, "Montana.apk")
JNI = os.path.join(ROOT, "native", "montana-jni")
CORE_SO = os.path.join(JNI, "target", "aarch64-linux-android", "release", "libmontana.so")
APP = os.path.join(ROOT, "app", "src", "main")


def say(text):
    print(text, flush=True)


def run(args, env=None, cwd=ROOT):
    # the SDK's own scripts step back into the folder they started from, so they start from one every builder can enter
    r = subprocess.run(args, env=env, cwd=cwd)
    if r.returncode:
        raise SystemExit("failed (%d): %s" % (r.returncode, " ".join(args)))


def git(args, tree=ROOT):
    # the shared host builds in trees another builder may own; the tree is named, so git trusts exactly it
    r = subprocess.run(["git", "-c", "safe.directory=" + tree, "-C", tree] + args, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit("git %s in %s: %s" % (" ".join(args), tree, r.stderr.strip()))
    return r.stdout.strip()


def files(top, suffix):
    found = []
    for d, _, names in os.walk(top):
        found += [os.path.join(d, n) for n in names if n.endswith(suffix)]
    return sorted(found)


def version():
    v = {}
    for line in open(os.path.join(HERE, "version"), encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            v[key.strip()] = value.strip()
    if v["TARGET"]:
        # catching up: our own build is the count of commits on main, and the iOS build we go to stands beside it
        code, target = int(git(["rev-list", "--count", "HEAD"])), int(v["TARGET"])
        if max(code, target) == code:
            raise SystemExit("own build %d reached %d: the jump to %d would go backwards (scripts/version)"
                             % (code, target, target))
        return code, "%s (%d → %d)" % (v["MARKETING"], code, target)
    # level with iOS: the number is the iOS build whose features Android has
    return int(v["BUILD"]), "%s (%s)" % (v["MARKETING"], v["BUILD"])


def core(p):
    pin = PIN["protocol"]
    if not os.path.isdir(p.protocol):
        raise SystemExit("the core's tree is not beside this repository: %s at %s (scripts/toolchain.json)"
                         % (p.protocol, pin["commit"]))
    head = git(["rev-parse", "HEAD"], p.protocol)
    changed = git(["status", "--porcelain", "--untracked-files=no"], p.protocol)
    if head != pin["commit"] or changed:
        raise SystemExit("the core's tree is %s%s; this build is pinned to %s (scripts/toolchain.json)"
                         % (head[:12], " with changes" if changed else "", pin["commit"][:12]))
    channel = p.rust_channel()
    say("core: Rust %s, NDK %s" % (channel, PIN["ndk"]))
    run([p.tool("cargo"), "+" + channel, "ndk", "-t", "arm64-v8a", "-P", MIN_SDK, "build", "--release"],
        env=p.env(), cwd=JNI)
    say("core: %s, %d bytes" % (os.path.relpath(CORE_SO, ROOT), os.path.getsize(CORE_SO)))


def signing_key(p, given):
    if given:
        if not os.path.exists(given):
            raise SystemExit("no key at %s: a shared build signs only with the shared key (docs/BUILD.md)" % given)
        return given
    key = os.path.expanduser("~/.android/debug.keystore")
    if not os.path.exists(key):
        os.makedirs(os.path.dirname(key), exist_ok=True)
        run([os.path.join(p.java_home, "bin", "keytool"), "-genkeypair", "-keystore", key, "-storepass", "android",
             "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US"], env=p.env())
    return key


def apk(p, code, name, key, check):
    env, bt, jar = p.env(), p.build_tools, p.android_jar
    with tempfile.TemporaryDirectory(prefix="montana-build-") as out:
        res, gen, classes, dex = [os.path.join(out, d) for d in ("res", "gen", "classes", "dex")]
        for d in (res, gen, classes, dex):
            os.makedirs(d)
        base = os.path.join(out, "base.apk")
        say("1/6 resources")
        run([os.path.join(bt, "aapt2"), "compile", "--dir", os.path.join(APP, "res"), "-o", res + "/compiled.zip"])
        run([os.path.join(bt, "aapt2"), "link", "-I", jar, "--manifest", os.path.join(APP, "AndroidManifest.xml"),
             "--min-sdk-version", MIN_SDK, "--target-sdk-version", PIN["platform"].split("-")[1],
             "--version-code", str(code), "--version-name", name,
             "--java", gen, "--custom-package", "quest.montana.app", "-o", base, res + "/compiled.zip"])
        say("2/6 R.java")
        run([os.path.join(p.java_home, "bin", "javac"), "-nowarn", "--release", "17", "-cp", jar, "-d", classes]
            + files(gen, ".java"), env=env)
        say("3/6 Kotlin")
        r = subprocess.run([p.kotlinc, "-nowarn", "-jvm-target", "17", "-no-reflect", "-no-stdlib",
                            "-cp", os.pathsep.join([jar, p.stdlib, classes]), "-d", classes, "-Xjvm-default=all"]
                           + files(os.path.join(APP, "kotlin"), ".kt"), env=env, cwd=ROOT, capture_output=True, text=True)
        for line in (r.stdout + r.stderr).splitlines():
            if not line.startswith("warning:"):
                print(line)
        if r.returncode or not os.path.exists(os.path.join(classes, "quest", "montana", "app", "MainActivity.class")):
            raise SystemExit("Kotlin failed")
        if check:
            return say("the code compiles: %d classes" % len(files(classes, ".class")))
        say("4/6 dex")
        run([os.path.join(bt, "d8"), "--release", "--min-api", MIN_SDK, "--lib", jar, "--output", dex]
            + files(classes, ".class") + [p.stdlib], env=env)
        say("5/6 package")
        unsigned, aligned, signed = [os.path.join(out, n) for n in ("unsigned.apk", "aligned.apk", "signed.apk")]
        open(unsigned, "wb").write(open(base, "rb").read())
        with zipfile.ZipFile(unsigned, "a") as z:
            z.write(os.path.join(dex, "classes.dex"), "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
            # stored, not deflated: the system maps the library straight from the package, page-aligned below
            z.write(CORE_SO, "lib/arm64-v8a/libmontana.so", compress_type=zipfile.ZIP_STORED)
        say("6/6 align + sign")
        run([os.path.join(bt, "zipalign"), "-f", "-P", "16", "4", unsigned, aligned])
        # the shared key keeps its secret in KEY.pass beside it; in its PKCS12 store the key's password is the store's,
        # and apksigner reads one file named twice line by line, so the file is named once. A debug key's is the known one.
        if os.path.exists(key + ".pass"):
            secret = ["--ks-pass", "file:" + key + ".pass"]
        else:
            secret = ["--ks-pass", "pass:android", "--key-pass", "pass:android"]
        run([os.path.join(bt, "apksigner"), "sign", "--ks", key] + secret + ["--out", signed, aligned], env=env)
        os.makedirs(BUILD, exist_ok=True)
        open(APK, "wb").write(open(signed, "rb").read())
    say("   %s  %d bytes" % (os.path.relpath(APK, ROOT), os.path.getsize(APK)))


def digest(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def publish(directory, code, name):
    commit = git(["rev-parse", "HEAD"])
    place = os.path.join(directory, "%d-%s" % (code, commit[:7]))
    os.makedirs(place, exist_ok=True)
    target = os.path.join(place, "Montana.apk")
    open(target, "wb").write(open(APK, "rb").read())
    record = dict(build=code, name=name, commit=commit, sha256=digest(target), apk=target,
                  time=time.strftime("%Y-%m-%d %H:%M:%S %Z"))
    open(os.path.join(place, "build.json"), "w", encoding="utf-8").write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    open(os.path.join(directory, "latest.json"), "w", encoding="utf-8").write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print("RESULT " + json.dumps(record, ensure_ascii=False), flush=True)


def install(p, path):
    adb = p.adb if os.path.exists(p.adb) else shutil.which("adb")
    if not adb:
        raise SystemExit("no adb: python3 scripts/setup.py --apk-only")
    manifest = open(os.path.join(APP, "AndroidManifest.xml"), encoding="utf-8").read()
    package = re.search(r'package="([^"]+)"', manifest).group(1)
    run([adb, "install", "-r", path])
    run([adb, "shell", "am", "start", "-n", package + "/quest.montana.app.MainActivity"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="resources and Kotlin only: the code compiles (no core)")
    mode.add_argument("--core-only", action="store_true", help="the core only")
    ap.add_argument("--keystore", help="sign with this key; it must exist. Default ~/.android/debug.keystore, "
                                       "made on first use")
    ap.add_argument("--publish", metavar="DIR", help="also lay the APK in DIR/BUILD-COMMIT/ and DIR/latest.json")
    ap.add_argument("--install", action="store_true", help="install on the phone over adb and start it")
    a = ap.parse_args()
    p = toolchain.Paths()
    need = [p.java_home, p.kotlinc, p.build_tools, p.android_jar] if not a.core_only else []
    if not a.check:
        need.append(p.ndk)
    missing = [n for n in need if not os.path.exists(n)]
    if missing:
        raise SystemExit("the toolchain is not complete here (%s): python3 scripts/setup.py\n  " % p.host
                         + "\n  ".join(missing))
    code, name = version()
    say("Montana %s on %s" % (name, p.host))
    if not a.check:
        core(p)
    if a.core_only:
        return
    apk(p, code, name, None if a.check else signing_key(p, a.keystore), a.check)
    if a.check:
        return
    if a.publish:
        publish(a.publish, code, name)
    if a.install:
        install(p, APK)


if __name__ == "__main__":
    main()
