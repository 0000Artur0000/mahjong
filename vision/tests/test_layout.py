import unittest

import cv2
import numpy as np

from dorahub_vision.layout import (
    LayoutParams,
    TableFrame,
    TileBox,
    cluster_layout,
)
from vision.scripts.table_plane import estimate_table_corners


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
    def test_estimates_and_rectifies_table_plane(self) -> None:
        image = np.zeros((200, 200, 3), dtype=np.uint8)
        polygon = np.array([[25, 35], [180, 20], [195, 190], [5, 175]])
        cv2.fillConvexPoly(image, polygon, (190, 190, 190))

        corners = estimate_table_corners(image)
        frame = TableFrame(corners)

        for actual, expected in zip(
            (frame.map(*point) for point in corners),
            ((0, 0), (1, 0), (1, 1), (0, 1)),
            strict=True,
        ):
            self.assertAlmostEqual(actual[0], expected[0], places=6)
            self.assertAlmostEqual(actual[1], expected[1], places=6)

    def test_clusters_hand_and_keeps_ambiguous_group_unassigned(self) -> None:
        result = cluster_layout(row(0.85, 0.10, 13) + row(0.25, 0.55, 5))

        self.assertIsNotNone(result.hand)
        self.assertEqual(result.hand.tile_count, 13)
        self.assertEqual([cluster.tile_count for cluster in result.others], [5])
        with self.assertRaises(ValueError):
            TileBox(0.99, 0.5, 0.1, 0.1)
        with self.assertRaises(ValueError):
            TileBox(0.5, 0.5, 0.1, 0.1, face_score=True)

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
        dead_wall = [
            TileBox(
                tile.cx,
                tile.cy,
                tile.width,
                tile.height,
                face_score=1.0 if index == 0 else 0.0,
            )
            for index, tile in enumerate(dead_wall)
        ]
        live_wall = [
            TileBox(
                tile.cx,
                tile.cy,
                tile.width,
                tile.height,
                face_score=0.0,
            )
            for tile in grid(
                0.65,
                0.70,
                5,
                1,
                width=0.036,
                height=0.018,
                step_x=0.034,
                step_y=0.022,
            )
        ]
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
        discards = [
            TileBox(
                tile.cx,
                tile.cy,
                tile.width,
                tile.height,
                face_score=1.0,
            )
            for tile in discards
        ]

        result = cluster_layout(
            concealed + meld + dead_wall + live_wall + list(reversed(discards)),
            LayoutParams(player_direction=(0, 1)),
        )

        self.assertEqual(result.hand.tile_count, 14)
        self.assertEqual(result.dead_wall.tile_count, 10)
        self.assertEqual([cluster.tile_count for cluster in result.discards], [12])
        self.assertEqual(result.discards[0].tiles, tuple(discards))

    def test_does_not_guess_wall_or_dead_wall_without_back_majority(self) -> None:
        ambiguous = [
            TileBox(
                tile.cx,
                tile.cy,
                tile.width,
                tile.height,
                face_score=1.0 if index < 2 else 0.0,
            )
            for index, tile in enumerate(
                grid(
                    0.20,
                    0.30,
                    4,
                    1,
                    width=0.036,
                    height=0.018,
                    step_x=0.034,
                    step_y=0.022,
                )
            )
        ]
        result = cluster_layout(
            row(0.85, 0.10, 13) + ambiguous,
            LayoutParams(player_direction=(0, 1)),
        )

        self.assertIsNone(result.dead_wall)
        self.assertFalse(result.walls)

    def test_grows_micro_zones_in_table_coordinates(self) -> None:
        own = [
            TileBox(
                0.16 + index * 0.045,
                0.84,
                0.035,
                0.055,
                face_score=1.0,
            )
            for index in range(14)
        ]
        opponent = [
            TileBox(
                x,
                0.08,
                0.025,
                0.04,
                face_score=0.0,
            )
            for x in (0.62, 0.65, 0.68, 0.71, 0.79, 0.82, 0.85)
        ]
        discards = [
            TileBox(
                tile.cx,
                tile.cy,
                tile.width,
                tile.height,
                face_score=1.0,
            )
            for tile in grid(
                0.42,
                0.40,
                6,
                2,
                width=0.025,
                height=0.04,
                step_x=0.03,
                step_y=0.045,
            )
        ]

        result = cluster_layout(
            own + opponent + discards,
            LayoutParams(
                player_direction=(0, 1),
                table_corners=((0, 0), (1, 0), (1, 1), (0, 1)),
            ),
        )

        self.assertEqual(result.hand.tile_count, 14)
        self.assertEqual(result.opponent_hands[0].tile_count, 7)
        self.assertEqual(result.opponent_hands[0].seat, "opposite")
        self.assertEqual(sum(group.tile_count for group in result.discards), 12)

    def test_hand_selection_is_not_tied_to_bottom_of_image(self) -> None:
        result = cluster_layout(
            row(0.10, 0.10, 13) + row(0.55, 0.55, 5),
            LayoutParams(player_direction=(0, -1)),
        )

        self.assertEqual(result.hand.tile_count, 13)

    def test_overdetected_nearest_hand_does_not_select_an_opponent(self) -> None:
        near = row(0.88, 0.02, 20)
        opponent = row(0.20, 0.15, 13)

        result = cluster_layout(near + opponent)

        self.assertEqual(result.hand.tile_count, 20)


if __name__ == "__main__":
    unittest.main()
