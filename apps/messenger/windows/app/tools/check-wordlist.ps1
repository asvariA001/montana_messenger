# Fail closed before a Windows build: the canonical Montana wordlist must be
# exactly 13,116 bytes, LF-only, and equal to the committed fingerprint.
# A CRLF checkout changes the bytes embedded by mt-mnemonic and aborts identity
# creation. This check runs before cargo build -p mt-bindings.
$ErrorActionPreference = 'Stop'

$repo = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or -not $repo) {
    throw 'check-wordlist: not inside a Git checkout'
}

$wordlist = Join-Path $repo 'core\Montana wordlist.txt'
$expected = '2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda'

$attr = (& git -C $repo check-attr text eol -- 'core/Montana wordlist.txt') -join "`n"
if ($attr -notmatch '(?m)^core/Montana wordlist\.txt: text: set$' -or
    $attr -notmatch '(?m)^core/Montana wordlist\.txt: eol: lf$') {
    throw "check-wordlist: the LF attribute is missing; expected text: set and eol: lf, got:`n$attr"
}

$bytes = [IO.File]::ReadAllBytes($wordlist)
$cr = @($bytes | Where-Object { $_ -eq 13 }).Count
$lf = @($bytes | Where-Object { $_ -eq 10 }).Count
$hash = (Get-FileHash -LiteralPath $wordlist -Algorithm SHA256).Hash.ToLowerInvariant()

if ($bytes.Length -ne 13116 -or $cr -ne 0 -or $lf -ne 2048 -or $hash -ne $expected) {
    throw "check-wordlist: core/Montana wordlist.txt is not canonical (size=$($bytes.Length), CR=$cr, LF=$lf, sha256=$hash); check out the LF file before building"
}

"check-wordlist: canonical LF wordlist, size=$($bytes.Length), CR=0, LF=$lf, sha256=$hash"
exit 0
