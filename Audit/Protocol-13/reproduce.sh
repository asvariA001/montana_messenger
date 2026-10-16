#!/bin/sh
# The reproducible build of the item work published here: run from anywhere inside a clone of this repository. It builds the
# node of Montana-Core from Audit/Protocol-13/source with the locked dependencies and the paths of the clone and of the home
# folder replaced by fixed names, and prints the SHA-256 of the binary; the README of this folder states the digest measured on
# two fresh clones and the toolchain it was measured with.
set -eu
ROOT="$(git rev-parse --show-toplevel)"
ROOT="$(cd "$ROOT" && pwd -P)"
cd "$ROOT/Audit/Protocol-13/source/Montana-Core"
CARGO_TARGET_DIR="$ROOT/target-reproduce" \
RUSTFLAGS="--remap-path-prefix=$ROOT=/build --remap-path-prefix=$HOME=/home" \
CARGO_INCREMENTAL=0 \
cargo build --release --locked -p montana-node
shasum -a 256 "$ROOT/target-reproduce/release/montana-node"
