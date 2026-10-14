#!/usr/bin/env python3
"""Check the stated conditional bound; this is not a soundness proof."""
import json
import re
import sys
from pathlib import Path

core = Path(sys.argv[1]) if len(sys.argv) == 2 else Path("core/Montana-Core")


def declared(path, name):
    text = path.read_text(encoding="utf-8")
    matches = re.findall(r"pub const " + re.escape(name) + r":\s*\w+\s*=\s*([0-9_]+)\s*,", text)
    if len(matches) != 1:
        raise SystemExit("Expected one declared constant: " + name)
    return int(matches[0].replace("_", ""))


fold = core / "crates/mt-proof/src/circuit/fold.rs"
q = declared(fold, "MODULUS")
repetitions = declared(fold, "REPETITIONS")
bits = declared(core / "crates/mt-proof/src/params.rs", "SECURITY_BITS")
maximum = q**repetitions // 2**bits
print(json.dumps({
    "scope": "Arithmetic of the stated conditional union bound; no attack probability is established",
    "modulus": q,
    "repetitions": repetitions,
    "target_bits": bits,
    "largest_history_meeting_stated_bound": maximum,
    "q_to_six_is_less_than_two_to_138": q**6 < 2**138,
    "cases": [
        {"windows": k, "stated_bound_meets_target": k * 2**bits <= q**repetitions}
        for k in [1, 1024, 20160, 1_000_000_000]
    ],
}, indent=2))
