# Build the Windows bindings only after the canonical wordlist guard passes.
# The caller sets CARGO_TARGET_DIR, MT_OPENSSL_DIR / OPENSSL_SRC_PERL and the
# Rust toolchain as the Windows build road documents. This script never packages.
$ErrorActionPreference = 'Stop'

$repo = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or -not $repo) {
    throw 'build-bindings: not inside a Git checkout'
}

$guard = Join-Path $PSScriptRoot 'check-wordlist.ps1'
& powershell -NoProfile -ExecutionPolicy Bypass -File $guard
if ($LASTEXITCODE -ne 0) {
    throw "build-bindings: wordlist guard failed ($LASTEXITCODE); bindings not built"
}

$core = Join-Path $repo 'core\Code'
Push-Location $core
try {
    cargo build --release -p mt-bindings
    if ($LASTEXITCODE -ne 0) {
        throw "build-bindings: cargo build -p mt-bindings failed ($LASTEXITCODE)"
    }
} finally {
    Pop-Location
}
