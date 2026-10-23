import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traffic import demo, pearson, rank, unique_object


class TrafficTests(unittest.TestCase):
    def test_positive_control_recovers_delayed_flow(self):
        result = rank(demo(), 3)
        self.assertEqual(result['truth_rank_interval'], [1, 1])
        self.assertEqual(result['top1_credit'], 1)
        self.assertAlmostEqual(result['scores']['flow-3']['correlation'], 1)
        self.assertEqual(result['scores']['flow-3']['lag_bins'], 3)
        self.assertEqual(result['scores']['flow-3']['overlap_bins'], 125)
        self.assertEqual(result['uniform_random_top1'], 1/8)

    def test_ties_are_fractional_not_alphabetic_winners(self):
        doc = demo()
        doc['candidates'] = {'a': doc['ingress'][:], 'z': doc['ingress'][:]}
        doc['truth'] = 'z'
        result = rank(doc, 0)
        self.assertEqual(result['truth_rank_interval'], [1, 2])
        self.assertEqual(result['top1_credit'], 0.5)

    def test_constant_flows_are_inconclusive(self):
        doc = demo()
        doc['candidates'] = {key: [500]*128 for key in doc['candidates']}
        result = rank(doc, 3)
        self.assertEqual(result['status'], 'inconclusive')
        self.assertIsNone(result['top1_credit'])

    def test_one_constant_candidate_cannot_be_silently_dropped(self):
        doc = demo()
        doc['candidates']['flow-0'] = [0]*128
        self.assertEqual(rank(doc, 3)['status'], 'inconclusive')

    def test_flat_ingress_is_inconclusive(self):
        doc = demo()
        doc['ingress'] = [500]*128
        self.assertEqual(rank(doc, 3)['status'], 'inconclusive')

    def test_reversing_time_is_not_the_positive_control(self):
        doc = demo()
        doc['candidates'] = {'true': doc['ingress'][::-1], 'other': doc['ingress'][:]}
        doc['truth'] = 'true'
        result = rank(doc, 0)
        self.assertEqual(result['truth_rank_interval'], [2, 2])
        self.assertEqual(result['top1_credit'], 0)

    def test_both_lag_directions(self):
        doc = demo()
        doc['candidates']['flow-3'] = doc['ingress'][3:] + [0]*3
        self.assertEqual(rank(doc, 3)['scores']['flow-3']['lag_bins'], -3)

    def test_invalid_counts_rejected(self):
        for value in (-1, True, 0.5, float('nan'), float('inf'), 2**53, '12'):
            with self.subTest(value=value):
                doc = demo()
                doc['ingress'][0] = value
                with self.assertRaises(ValueError):
                    rank(doc, 3)

    def test_short_or_unequal_windows_rejected(self):
        for values in ([], [1]*15, [1]*127):
            doc = demo()
            doc['candidates']['flow-0'] = values
            with self.assertRaises(ValueError):
                rank(doc, 3)

    def test_lag_cannot_rank_tiny_overlaps(self):
        for lag in (-1, 113, True, 1.5):
            with self.assertRaises(ValueError):
                rank(demo(), lag)
        rank(demo(), 112)  # Exactly 16 overlapping bins at the extremes.

    def test_missing_truth_or_single_candidate_rejected(self):
        doc = demo()
        for truth in ('missing', None, []):
            bad = copy.deepcopy(doc)
            bad['truth'] = truth
            with self.assertRaises(ValueError):
                rank(bad, 3)
        doc['candidates'] = {'flow-3': doc['ingress']}
        with self.assertRaises(ValueError):
            rank(doc, 3)

    def test_schema_rejects_unexpected_fields(self):
        doc = demo()
        doc['metadata'] = 'not part of attack input'
        with self.assertRaises(ValueError):
            rank(doc, 3)

    def test_duplicate_candidate_names_are_not_silently_overwritten(self):
        with self.assertRaisesRegex(ValueError, 'duplicate JSON field'):
            json.loads('{"candidates":{"same":[1],"same":[2]}}', object_pairs_hook=unique_object)

    def test_known_pearson_values(self):
        self.assertAlmostEqual(pearson([1, 2, 3], [3, 2, 1]), -1)
        self.assertAlmostEqual(pearson([1, 2, 3], [2, 4, 6]), 1)
        self.assertIsNone(pearson([1, 1, 1], [1, 2, 3]))


if __name__ == '__main__':
    unittest.main()
