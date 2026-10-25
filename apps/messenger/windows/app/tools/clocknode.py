# A CHECK'S OWN NODE ON A CLOCK OF ITS OWN (the owner's word 25.10.2026 10:5x, chain №910–920: the checks move time by managed clocks
# or isolated rooms, never the computer's date, never a real wallet): the original's own door, apps/messenger/ios/Code/Node/
# montana-home-node.py, run unchanged but for four things -- its clock is the real one plus the days written in DIR/shift_days
# (read at every look, so a check moves it while the node runs: the box's minute, the Date header, the week of the blobs), its files
# lie under DIR instead of /var/lib and /etc/montana, it listens on the loopback alone, and it opens only the box, the blobs and the
# signals (no STUN ports, no TURN, no diaries, no wake pushes). Clients reach it only in a check's run: Windows by
# MONTANA_TEST_DOOR=http://127.0.0.1:PORT (doors::test_door), the emulator by 10.0.2.2:PORT. Nothing of the network is touched.
# usage: python3 -I app/tools/clocknode.py NODE_PY DIR [PORT]
import os
import sys
import time

node, root = sys.argv[1], os.path.abspath(sys.argv[2])
port = sys.argv[3] if len(sys.argv) > 3 else "8462"
home = root.replace("\\", "/")
for d in ("montana-node-wake/blobs", "montana-node-wake/diag", "montana-vault", "etc"):
    os.makedirs(os.path.join(root, d), exist_ok=True)
shift_file = os.path.join(root, "shift_days")
_real = time.time
_seen = [0.0, None]


def _shift():
    # the days ahead, as the check wrote them last; a file not there is the real day
    try:
        m = os.path.getmtime(shift_file)
        if m != _seen[1]:
            with open(shift_file, encoding="utf-8") as f:
                _seen[0] = float(f.read().strip() or 0)
            _seen[1] = m
    except (OSError, ValueError):
        _seen[0], _seen[1] = 0.0, None
    return _seen[0]


time.time = lambda: _real() + _shift() * 86400
os.environ["STORE_PORT"] = port
os.environ["MONTANA_CAPS"] = "box,blob,signal"
for k in ("VAULT_TLS_CERT", "VAULT_TLS_KEY", "COIN_REPLICA_FILE", "DIAG_FORWARD"):
    os.environ.pop(k, None)
with open(node, encoding="utf-8") as f:
    src = f.read()
src = src.replace("/var/lib/", home + "/").replace("/etc/montana/", home + "/etc/").replace('"0.0.0.0"', '"127.0.0.1"')
print(f"[clocknode] {node} on 127.0.0.1:{port}, files under {root}, clock + {_shift()} days", flush=True)
sys.argv = [node]
exec(compile(src, node, "exec"), {"__name__": "__main__", "__file__": node})
