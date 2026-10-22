#!/usr/bin/env python3
"""Inventory committed audit inputs; never attest builds, deployment or security."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

FOLDERS = ('core', 'apps/messenger/ios', 'apps/messenger/android')
AUDIT_SOURCE = 'Audit/Protocol-13/source'
LOCKS = (
    'core/Montana-Core/Cargo.lock',
    'core/Code/Cargo.lock',
    AUDIT_SOURCE + '/Montana-Core/Cargo.lock',
)


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True)
    if result.returncode:
        raise ValueError('git could not read the requested committed object')
    return result.stdout


def inventory(root, revision):
    commit = git(root, 'rev-parse', '--verify', '--end-of-options',
                 revision + '^{commit}').decode().strip()

    def object_at(path):
        return git(root, 'rev-parse', commit + ':' + path).decode().strip()

    def blob(path):
        return git(root, 'show', commit + ':' + path)

    releases = blob('RELEASES.md').decode('utf-8')
    start, end = '<!-- sources:start -->', '<!-- sources:end -->'
    if releases.count(start) != 1 or releases.count(end) != 1:
        raise ValueError('RELEASES.md must contain one Sources block')
    if releases.index(start) >= releases.index(end):
        raise ValueError('RELEASES.md Sources markers are reversed')
    block = releases.split(start)[1].split(end)[0]
    sources = {}
    for line in block.splitlines():
        if not line.strip().startswith('| `'):
            continue
        cells = [cell.strip() for cell in line.strip().strip('|').split('|')]
        if len(cells) != 5:
            raise ValueError('malformed Sources row')
        folder, build, private_commit, recorded_tree, staged = cells
        if not (folder.startswith('`') and folder.endswith('`')):
            raise ValueError('malformed source folder')
        folder = folder[1:-1]
        if folder not in FOLDERS or folder in sources:
            raise ValueError('unexpected or duplicate source folder: ' + folder)
        if not re.fullmatch(r'`[0-9a-f]{40}`', recorded_tree):
            raise ValueError('malformed folder tree: ' + folder)
        tree = object_at(folder)
        if git(root, 'cat-file', '-t', commit + ':' + folder).strip() != b'tree':
            raise ValueError('source is not a directory: ' + folder)
        if tree != recorded_tree[1:-1]:
            raise ValueError('RELEASES.md tree mismatch: ' + folder)
        sources[folder] = {
            'tree': tree, 'build_label': build,
            'private_source_label_unverified': private_commit.strip('`'),
            'staged_label_unverified': staged,
        }
    if set(sources) != set(FOLDERS):
        raise ValueError('RELEASES.md must name all three source folders')
    if git(root, 'cat-file', '-t', commit + ':' + AUDIT_SOURCE).strip() != b'tree':
        raise ValueError('protocol audit source is not a directory')
    return {
        'schema': 1,
        'kind': 'committed-source-inventory-not-security-attestation',
        'public_commit': commit,
        'public_tree': git(root, 'rev-parse', commit + '^{tree}').decode().strip(),
        'sources': sources,
        'protocol_audit_source_tree': object_at(AUDIT_SOURCE),
        'lock_sha256': {path: hashlib.sha256(blob(path)).hexdigest() for path in LOCKS},
        'excludes': ['working-tree edits', 'binary provenance', 'deployed services',
                     'independent audit', 'GPA resilience'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--revision', default='HEAD', help='public Git revision to inventory')
    args = parser.parse_args()
    try:
        result = inventory(args.repo, args.revision)
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(2, 'snapshot: ' + str(error) + '\n')
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    print()


if __name__ == '__main__':
    main()
