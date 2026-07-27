import unittest

from dorahub_vision.layout import TileBox, cluster_layout


def row(y: float, x: float, count: int) -> list[TileBox]:
    return [TileBox(x + index * 0.05, y, 0.04, 0.06) for index in range(count)]


class LayoutTest(unittest.TestCase):
    def test_clusters_hand_and_discard_and_rejects_bad_geometry(self) -> None:
        result = cluster_layout(row(0.85, 0.10, 13) + row(0.25, 0.55, 5))

        self.assertIsNotNone(result.hand)
        self.assertEqual(result.hand.tile_count, 13)
        self.assertEqual([cluster.tile_count for cluster in result.discards], [5])
        with self.assertRaises(ValueError):
            TileBox(0.99, 0.5, 0.1, 0.1)


if __name__ == "__main__":
    unittest.main()
