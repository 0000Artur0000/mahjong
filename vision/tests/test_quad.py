import unittest

from dorahub_vision.quad import (
    centroid,
    contains,
    expand_to_contain,
    quad_from_points,
)

UNIT = ((0.2, 0.2), (0.8, 0.2), (0.8, 0.8), (0.2, 0.8))


class QuadTest(unittest.TestCase):
    def test_centroid_of_a_square(self):
        self.assertEqual(centroid(UNIT), (0.5, 0.5))

    def test_centroid_needs_points(self):
        with self.assertRaises(ValueError):
            centroid([])

    def test_contains_distinguishes_inside_from_outside(self):
        self.assertTrue(contains(UNIT, (0.5, 0.5)))
        self.assertTrue(contains(UNIT, (0.2, 0.2)))
        self.assertFalse(contains(UNIT, (0.05, 0.5)))

    def test_quad_is_left_alone_when_every_point_is_inside(self):
        self.assertEqual(expand_to_contain(UNIT, [(0.5, 0.5), (0.3, 0.7)]), UNIT)

    def test_quad_grows_until_the_outside_point_fits(self):
        outside = (0.05, 0.5)

        grown = expand_to_contain(UNIT, [outside])

        self.assertTrue(contains(grown, outside))
        # Форма сохраняется: гомотетия от центра, а не новый прямоугольник.
        self.assertEqual(centroid(grown), centroid(UNIT))
        self.assertTrue(all(contains(grown, corner) for corner in UNIT))

    def test_growing_keeps_every_original_point_inside(self):
        points = [(0.05, 0.5), (0.95, 0.1), (0.5, 0.99)]

        grown = expand_to_contain(UNIT, points)

        for point in points:
            self.assertTrue(contains(grown, point), point)

    def test_empty_points_leave_the_quad_untouched(self):
        self.assertEqual(expand_to_contain(UNIT, []), UNIT)

    def test_fallback_quad_covers_all_points(self):
        points = [(0.2, 0.3), (0.7, 0.4), (0.5, 0.9)]

        quad = quad_from_points(points)

        for point in points:
            self.assertTrue(contains(quad, point), point)

    def test_fallback_quad_needs_points(self):
        with self.assertRaises(ValueError):
            quad_from_points([])

    def test_fallback_quad_is_not_degenerate_for_one_point(self):
        quad = quad_from_points([(0.5, 0.5)])

        self.assertTrue(contains(quad, (0.5, 0.5)))
        self.assertGreater(quad[1][0] - quad[0][0], 0)


if __name__ == "__main__":
    unittest.main()
