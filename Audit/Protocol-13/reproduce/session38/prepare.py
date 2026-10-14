#!/usr/bin/env python3
"""Reconstruct the audited candidate in a new directory without changing a checkout."""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile


def verify(root, manifest):
    for name, wanted in manifest["files"].items():
        actual = hashlib.sha256((root / "core" / name).read_bytes()).hexdigest()
        if actual != wanted:
            raise SystemExit("Source hash mismatch: " + name)
    print("Matched", len(manifest["files"]), "source files.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, help="Clone of the public repository")
    parser.add_argument("destination", type=Path, help="New directory; must not exist")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    manifest = json.loads((here / "source-manifest.json").read_text())
    root = args.destination.resolve()
    if args.verify_only:
        verify(root, manifest)
        return
    # Refuse an existing path, including an empty directory. Nothing is deleted.
    root.mkdir(parents=False, exist_ok=False)
    archive = subprocess.run(
        [
            "git", "-C", str(args.repository.resolve()), "archive", "--format=tar",
            manifest["base_public_commit"], "core/Montana-Core", "core/docs",
        ],
        check=True, stdout=subprocess.PIPE,
    ).stdout
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as contents:
        for member in contents:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or name.parts[0] != "core":
                raise SystemExit("Unsafe archive member")
            target = root.joinpath(*name.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as output:
                    output.write(contents.extractfile(member).read())
            else:
                raise SystemExit("Non-regular archive member: " + member.name)
    patch = str(here / "candidate.patch")
    subprocess.run(["git", "apply", "--check", patch], cwd=root, check=True)
    subprocess.run(["git", "apply", patch], cwd=root, check=True)
    # A replacement document avoids republishing removed historical text in a diff.
    for destination, supplied in manifest.get("replacement_files", {}).items():
        dest_path, source_path = PurePosixPath(destination), PurePosixPath(supplied)
        if any(path.is_absolute() or ".." in path.parts for path in [dest_path, source_path]):
            raise SystemExit("Unsafe replacement path")
        if destination not in manifest["files"]:
            raise SystemExit("Replacement is not covered by the source manifest")
        (root / "core" / dest_path).write_bytes((here / source_path).read_bytes())
    verify(root, manifest)
    print("Candidate prepared at", root / "core" / "Montana-Core")
    print("Passing open_finding tests reproduce defects. Item 1 remains open.")


if __name__ == "__main__":
    main()
