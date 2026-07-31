import unittest

import cv2
import numpy as np

from dorahub_vision.layout import (
    LayoutParams,
    TableFrame,
    TileBox,
    cluster_layout,
)
from vision.scripts.table_plane import (
    appearance_face_score,
    estimate_table_corners,
)


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
        for actual, expected in zip(
            (frame.unmap(*point) for point in ((0, 0), (1, 0), (1, 1), (0, 1))),
            corners,
            strict=True,
        ):
            self.assertAlmostEqual(actual[0], expected[0], places=6)
            self.assertAlmostEqual(actual[1], expected[1], places=6)

        face = np.full((80, 80, 3), 180, dtype=np.uint8)
        face[30:50, 30:50] = 20
        self.assertGreater(
            appearance_face_score(face, TileBox(0.5, 0.5, 0.5, 0.5)),
            0.5,
        )
        self.assertEqual(
            appearance_face_score(
                np.full((80, 80, 3), 180, dtype=np.uint8),
                TileBox(0.5, 0.5, 0.5, 0.5),
            ),
            0,
        )

    def test_rectifies_a_table_cropped_by_the_photo(self) -> None:
        corners = ((-0.2, -0.1), (1.1, 0.0), (1.2, 1.1), (-0.1, 1.0))
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
        self.assertEqual(result.dead_wall.tile_count, 9)
        self.assertEqual(result.dora[0].tile_count, 1)
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
            for x in (
                0.49,
                0.52,
                0.55,
                0.58,
                0.61,
                0.64,
                0.67,
                0.70,
                0.73,
                0.76,
                0.79,
                0.82,
            )
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
        self.assertEqual(result.opponent_hands[0].tile_count, 12)
        self.assertEqual(result.opponent_hands[0].seat, "opposite")
        self.assertEqual(sum(group.tile_count for group in result.discards), 12)

    def test_opponent_hands_outrank_walls_without_image_edge_hardcode(self) -> None:
        own = [
            TileBox(0.15 + index * 0.045, 0.85, 0.035, 0.055, face_score=1)
            for index in range(14)
        ]
        live_wall = [
            TileBox(0.10 + index * 0.028, 0.25, 0.026, 0.018, face_score=0)
            for index in range(15)
        ]
        closed_hand = [
            TileBox(0.10 + index * 0.028, 0.05, 0.025, 0.04, face_score=0)
            for index in range(12)
        ]
        open_hand = [
            TileBox(
                x,
                0.18,
                0.025,
                0.04,
                face_score=1,
            )
            for x in (
                0.38,
                0.408,
                0.436,
                0.481,
                0.509,
                0.537,
                0.582,
                0.610,
                0.638,
            )
        ]
        paper = [
            TileBox(0.03 + index * 0.035, 0.5, 0.025, 0.04, face_score=1)
            for index in range(2)
        ]
        edge_meld = [
            *[
                TileBox(0.65 + index * 0.028, 0.04, 0.025, 0.04, face_score=0)
                for index in range(4)
            ],
            *[
                TileBox(0.80 + index * 0.028, 0.05, 0.025, 0.04, face_score=0)
                for index in range(3)
            ],
        ]

        result = cluster_layout(
            own + live_wall + closed_hand + open_hand + edge_meld + paper,
            LayoutParams(
                player_direction=(0, 1),
                table_corners=((0, 0), (1, 0), (1, 1), (0, 1)),
            ),
        )
        self.assertEqual(
            sorted(group.tile_count for group in result.opponent_hands),
            [7, 9, 12],
        )
        self.assertIn(15, [group.tile_count for group in result.walls])

    def test_separates_open_melds_from_a_nine_tile_discard_row(self) -> None:
        own = [
            TileBox(0.20 + index * 0.035, 0.88, 0.025, 0.04, face_score=1)
            for index in range(14)
        ]
        wall = [
            TileBox(0.25 + index * 0.025, 0.57, 0.022, 0.018, face_score=0)
            for index in range(16)
        ]
        discard = [
            TileBox(0.30 + index * 0.025, 0.50, 0.022, 0.035, face_score=1)
            for index in range(9)
        ]
        open_melds = [
            TileBox(0.08, y, 0.022, 0.035, face_score=1)
            for y in (0.25, 0.275, 0.30, 0.34, 0.365, 0.39)
        ]
        calls = [
            TileBox(x, y, 0.022, 0.035, face_score=1)
            for y in (0.27, 0.37)
            for x in (0.15, 0.175)
        ]
        missed_pair = [TileBox(0.08, 0.46, 0.022, 0.035, face_score=0)]

        result = cluster_layout(
            own + wall + discard + open_melds + calls + missed_pair,
            LayoutParams(
                player_direction=(0, 1),
                table_corners=((0, 0), (1, 0), (1, 1), (0, 1)),
            ),
        )

        self.assertIn(11, [group.tile_count for group in result.opponent_hands])
        self.assertIn(9, [group.tile_count for group in result.discards])

    def test_hand_selection_is_not_tied_to_bottom_of_image(self) -> None:
        result = cluster_layout(
            row(0.10, 0.10, 13) + row(0.55, 0.55, 5),
            LayoutParams(player_direction=(0, -1)),
        )

        self.assertEqual(result.hand.tile_count, 13)

    def test_missing_right_discard_does_not_shift_self_to_the_right(self) -> None:
        hand = [
            TileBox(0.15 + index * 0.045, 0.9, 0.035, 0.055, face_score=1)
            for index in range(14)
        ]

        def discard(x: float, y: float, columns: int, rows: int) -> list[TileBox]:
            return [
                TileBox(
                    tile.cx,
                    tile.cy,
                    tile.width,
                    tile.height,
                    face_score=1,
                )
                for tile in grid(
                    x,
                    y,
                    columns,
                    rows,
                    width=0.025,
                    height=0.04,
                    step_x=0.03,
                    step_y=0.045,
                )
            ]

        result = cluster_layout(
            hand
            + discard(0.46, 0.55, 3, 2)
            + discard(0.46, 0.25, 3, 2)
            + discard(0.20, 0.38, 2, 3),
            LayoutParams(
                player_direction=(0, 1),
                table_corners=((0, 0), (1, 0), (1, 1), (0, 1)),
            ),
        )

        self.assertEqual(
            {cluster.seat for cluster in result.discards},
            {"self", "opposite", "left"},
        )

    def test_overdetected_nearest_hand_does_not_select_an_opponent(self) -> None:
        near = row(0.88, 0.02, 20)
        opponent = row(0.20, 0.15, 13)

        result = cluster_layout(near + opponent)

        self.assertIsNone(result.hand)


if __name__ == "__main__":
    unittest.main()
