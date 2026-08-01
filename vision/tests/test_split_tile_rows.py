import unittest

import cv2
import numpy as np

from vision.scripts.split_tile_rows import split_merged_rows


class SplitTileRowsTest(unittest.TestCase):
    def test_splits_a_confirmed_periodic_row(self):
        image = np.full((160, 600, 3), (120, 100, 40), np.uint8)
        for x in range(0, 600, 60):
            cv2.rectangle(image, (x, 25), (x + 57, 135), (245, 245, 245), -1)

        boxes, _, polygons = split_merged_rows(
            image,
            [[0, 25, 180, 135], [180, 25, 360, 135], [0, -10, 180, 50]],
            [0.9, 0.8, 0.7],
        )

        self.assertEqual(10, len(boxes))
        self.assertEqual((10, 4, 2), polygons.shape)


if __name__ == "__main__":
    unittest.main()
