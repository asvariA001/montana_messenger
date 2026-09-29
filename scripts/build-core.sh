#!/bin/zsh
# Builds the Montana core (Rust: mt-bindings + native/montana-jni) for the phone (arm64).
set -euo pipefail
ROOT="${0:A:h:h}"
source "$HOME/.cargo/env"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.2.12479018"
cd "$ROOT/native/montana-jni"
cargo ndk -t arm64-v8a -P 26 build --release
ls -lh target/aarch64-linux-android/release/libmontana.so
