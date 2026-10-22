#!/usr/bin/env python3
"""Rank synthetic/lab flow candidates; this is an attack baseline, not a GPA certificate."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import sys

MIN_OVERLAP = 16
MAX_COUNT = 2**53 - 1


def vector(value):
    if not isinstance(value, list) or len(value) < MIN_OVERLAP:
        raise ValueError('each flow needs at least 16 common time bins')
    if any(type(x) is not int or not 0 <= x <= MAX_COUNT for x in value):
        raise ValueError('bin counts must be nonnegative integers no greater than 2**53-1')
    return value


def pearson(a, b):
    ma, mb = math.fsum(a) / len(a), math.fsum(b) / len(b)
    da, db = [x - ma for x in a], [x - mb for x in b]
    va, vb = math.fsum(x*x for x in da), math.fsum(x*x for x in db)
    if va == 0 or vb == 0:
        return None
    return max(-1.0, min(1.0, math.fsum(x*y for x, y in zip(da, db)) / math.sqrt(va*vb)))


def rank(document, max_lag):
    if not isinstance(document, dict) or set(document) != {'ingress', 'candidates', 'truth'}:
        raise ValueError('expected exactly ingress, candidates and truth')
    ingress = vector(document['ingress'])
    candidates = document['candidates']
    if not isinstance(candidates, dict) or len(candidates) < 2:
        raise ValueError('at least two candidate flows are required')
    if any(not isinstance(name, str) or not name for name in candidates):
        raise ValueError('candidate names must be nonempty strings')
    truth = document['truth']
    if not isinstance(truth, str) or truth not in candidates:
        raise ValueError('truth must name one candidate')
    if type(max_lag) is not int or not 0 <= max_lag <= len(ingress) - MIN_OVERLAP:
        raise ValueError('lag must leave at least 16 overlapping bins')
    scores = {}
    for name, values in candidates.items():
        values = vector(values)
        if len(values) != len(ingress):
            raise ValueError('all flows must use the same time window and bin count')
        best = None
        for lag in range(-max_lag, max_lag + 1):
            # Positive lag means egress followed ingress. No wraparound.
            a = ingress[:len(ingress)-lag] if lag >= 0 else ingress[-lag:]
            b = values[lag:] if lag >= 0 else values[:len(values)+lag]
            score = pearson(a, b)
            if score is not None and (best is None or score > best['correlation']):
                best = {'correlation': score, 'lag_bins': lag, 'overlap_bins': len(a)}
        scores[name] = best
    result = {
        'kind': 'flow-correlation-baseline-not-security-verdict',
        'max_lag_bins': max_lag,
        'candidate_count': len(candidates),
        'uniform_random_top1': 1 / len(candidates),
        'scores': scores,
        'truth_rank_interval': None,
        'top1_credit': None,
        'status': 'inconclusive',
    }
    # Do not silently discard a constant flow and make the candidate set easier.
    if any(score is None for score in scores.values()):
        result['reason'] = 'at least one candidate has undefined correlation'
        return result
    target = scores[truth]['correlation']
    equal = sum(math.isclose(s['correlation'], target, rel_tol=0, abs_tol=1e-12)
                for s in scores.values())
    better = sum(s['correlation'] > target + 1e-12 for s in scores.values())
    result.update(status='ranked', truth_rank_interval=[better + 1, better + equal],
                  top1_credit=1 / equal if better == 0 else 0)
    return result


def demo():
    rng = random.Random(2)
    source = [rng.randrange(1000) for _ in range(128)]
    candidates = {f'flow-{i}': [rng.randrange(1000) for _ in source] for i in range(8)}
    candidates['flow-3'] = [0]*3 + source[:-3]
    return {'ingress': source, 'candidates': candidates, 'truth': 'flow-3'}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('duplicate JSON field: ' + key)
        value[key] = item
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace', nargs='?', type=Path)
    parser.add_argument('--demo', action='store_true', help='emit a synthetic positive control')
    parser.add_argument('--max-lag', type=int, default=3)
    args = parser.parse_args()
    if args.demo == (args.trace is not None):
        parser.error('choose either --demo or one trace JSON file')
    try:
        if args.demo:
            result = demo()
        else:
            raw = args.trace.read_bytes()
            result = rank(json.loads(raw, object_pairs_hook=unique_object), args.max_lag)
            result['input_sha256'] = hashlib.sha256(raw).hexdigest()
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(2, 'traffic: ' + str(error) + '\n')
    json.dump(result, sys.stdout, indent=2, sort_keys=True, allow_nan=False)
    print()


if __name__ == '__main__':
    main()
