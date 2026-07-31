import unittest
from math import pi

from dorahub_vision.backs import (
    Proposal,
    covered,
    expected_count,
    split_run,
    tile_size,
)


class BacksTest(unittest.TestCase):
    def test_splits_a_horizontal_run_into_tiles_of_the_given_step(self):
        run = split_run(
            cx=0.25, cy=0.42, length=0.30, thickness=0.04, angle=0.0, step=0.05
        )

        self.assertEqual(len(run), 6)
        self.assertAlmostEqual(run[0].cx, 0.125)
        self.assertAlmostEqual(run[-1].cx, 0.375)
        self.assertTrue(all(abs(tile.cy - 0.42) < 1e-9 for tile in run))
        self.assertTrue(all(tile.proposed for tile in run))

    def test_splits_along_the_axis_of_a_rotated_run(self):
        # Ряд под 90°: тайлы должны идти по вертикали, а не по горизонтали.
        run = split_run(
            cx=0.22, cy=0.25, length=0.30, thickness=0.04, angle=pi / 2, step=0.06
        )

        self.assertEqual(len(run), 5)
        self.assertTrue(all(abs(tile.cx - 0.22) < 1e-9 for tile in run))
        self.assertAlmostEqual(run[0].cy, 0.13)
        self.assertAlmostEqual(run[-1].cy, 0.37)

    def test_keeps_at_least_one_tile_for_a_short_run(self):
        self.assertEqual(
            len(split_run(0.1, 0.1, 0.02, 0.03, 0.0, 0.05)), 1
        )

    def test_rejects_a_run_too_long_to_be_tiles(self):
        # Край стола или лист бумаги, а не ряд тайлов.
        self.assertEqual(split_run(0.5, 0.5, 1.0, 0.02, 0.0, 0.01), [])

    def test_rejects_degenerate_runs(self):
        self.assertEqual(split_run(0.5, 0.5, 0.0, 0.05, 0.0, 0.05), [])
        self.assertEqual(split_run(0.5, 0.5, 0.2, 0.05, 0.0, 0.0), [])

    def test_covered_matches_by_centre_distance(self):
        detections = [(0.50, 0.50, 0.04, 0.05)]

        self.assertTrue(covered((0.51, 0.51, 0.04, 0.05), detections, slack=0.03))
        self.assertFalse(covered((0.60, 0.60, 0.04, 0.05), detections, slack=0.03))

    def test_tile_size_is_the_median_of_detections(self):
        detections = [(0, 0, 0.02, 0.03), (0, 0, 0.04, 0.05), (0, 0, 0.06, 0.07)]

        self.assertEqual(tile_size(detections), (0.04, 0.05))

    def test_tile_size_needs_at_least_one_detection(self):
        with self.assertRaises(ValueError):
            tile_size([])

    def test_expected_count_rounds_up_to_whole_tiles(self):
        self.assertEqual(expected_count(0.0021, 0.02, 0.03), 4)
        self.assertEqual(expected_count(0.0, 0.02, 0.03), 0)


if __name__ == "__main__":
    unittest.main()
