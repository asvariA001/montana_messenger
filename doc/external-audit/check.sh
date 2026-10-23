#!/bin/sh
# Offline tool checks; the locked Rust dependencies must already be cached.
set -eu
cd "$(dirname "$0")/../.."
python3 -m unittest discover -s doc/external-audit/tests -v
python3 doc/external-audit/snapshot.py --revision HEAD
rustup run 1.92.0 cargo test --release --locked --offline --manifest-path doc/external-audit/probes/Cargo.toml
