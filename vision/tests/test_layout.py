import unittest

from dorahub_vision.layout import TileBox, cluster_layout


def row(y: float, x: float, count: int) -> list[TileBox]:
    return [TileBox(x + index * 0.05, y, 0.04, 0.06) for index in range(count)]


def grid(
    x: float,
    y: float,
    columns: int,
    rows: int,
    *,
    width: float,
    height: float,
    step_x: float,
    step_y: float,
) -> list[TileBox]:
    return [
        TileBox(x + column * step_x, y + line * step_y, width, height)
        for line in range(rows)
        for column in range(columns)
    ]


class LayoutTest(unittest.TestCase):
    def test_clusters_hand_and_discard_and_rejects_bad_geometry(self) -> None:
        result = cluster_layout(row(0.85, 0.10, 13) + row(0.25, 0.55, 5))

        self.assertIsNotNone(result.hand)
        self.assertEqual(result.hand.tile_count, 13)
        self.assertEqual([cluster.tile_count for cluster in result.discards], [5])
        with self.assertRaises(ValueError):
            TileBox(0.99, 0.5, 0.1, 0.1)

    def test_groups_hand_melds_discards_and_two_level_dead_wall(self) -> None:
        concealed = [
            TileBox(0.08 + index * 0.045, 0.88, 0.035, 0.055)
            for index in range(10)
        ]
        meld = [
            TileBox(0.67 + index * 0.045, 0.88, 0.035, 0.055)
            for index in range(4)
        ]
        dead_wall = grid(
            0.20,
            0.24,
            5,
            2,
            width=0.036,
            height=0.018,
            step_x=0.034,
            step_y=0.022,
        )
        discards = grid(
            0.43,
            0.46,
            6,
            2,
            width=0.025,
            height=0.04,
            step_x=0.03,
            step_y=0.045,
        )

        result = cluster_layout(concealed + meld + dead_wall + list(reversed(discards)))

        self.assertEqual(result.hand.tile_count, 14)
        self.assertEqual(result.dead_wall.tile_count, 10)
        self.assertEqual([cluster.tile_count for cluster in result.discards], [12])
        self.assertEqual(result.discards[0].tiles, tuple(discards))

    def test_hand_selection_is_not_tied_to_bottom_of_image(self) -> None:
        result = cluster_layout(row(0.10, 0.10, 13) + row(0.55, 0.55, 5))

        self.assertEqual(result.hand.tile_count, 13)

    def test_overdetected_nearest_hand_does_not_select_an_opponent(self) -> None:
        near = row(0.88, 0.02, 20)
        opponent = row(0.20, 0.15, 13)

        result = cluster_layout(near + opponent)

        self.assertEqual(result.hand.tile_count, 20)


if __name__ == "__main__":
    unittest.main()
