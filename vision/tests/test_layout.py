import unittest
from math import pi

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
    rectify_table,
    unrectify_box,
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

    def test_warps_a_table_image_to_top_down(self) -> None:
        image = np.zeros((100, 120, 3), dtype=np.uint8)
        corners = ((0.2, 0.1), (0.8, 0.2), (0.9, 0.9), (0.1, 0.8))
        polygon = np.array(
            [(round(x * 119), round(y * 99)) for x, y in corners],
            dtype=np.int32,
        )
        cv2.fillConvexPoly(image, polygon, (255, 255, 255))

        rectified = rectify_table(image, corners, 64)

        self.assertEqual(rectified.shape, (64, 64, 3))
        self.assertGreater(rectified[32, 32].mean(), 250)
        for actual, expected in zip(
            unrectify_box(
                [0.5, 0.5, 0.2, 0.4],
                ((0, 0), (1, 0), (1, 1), (0, 1)),
            ),
            [0.5, 0.5, 0.2, 0.4],
            strict=True,
        ):
            self.assertAlmostEqual(actual, expected)

    def test_grows_hand_from_lowest_tile_and_marks_the_rest_as_noise(self) -> None:
        result = cluster_layout(row(0.85, 0.10, 13) + row(0.25, 0.55, 5))

        self.assertIsNotNone(result.hand)
        self.assertEqual(result.hand.tile_count, 13)
        self.assertFalse(result.others)
        self.assertEqual([cluster.tile_count for cluster in result.noise], [5])
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
                class_id=27 if index == 0 else None,
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
        self.assertFalse(result.discards)
        self.assertIn(12, [cluster.tile_count for cluster in result.noise])

    def test_dora_must_be_a_face_tile_embedded_in_the_wall(self) -> None:
        indicator = TileBox(0.32, 0.30, 0.035, 0.05, 27, 0.9)
        wall = [
            TileBox(x, 0.30, 0.035, 0.05, face_score=0.0)
            for x in (0.20, 0.24, 0.28, 0.36, 0.40, 0.44)
        ]
        unrelated_face = TileBox(0.65, 0.55, 0.035, 0.05, 3, 0.99)

        result = cluster_layout(
            [*wall, indicator, unrelated_face],
            LayoutParams(player_direction=(0, 1)),
        )

        self.assertEqual(result.dora[0].tiles, (indicator,))
        self.assertNotIn(unrelated_face, result.dead_wall.tiles)

    def test_dora_survives_two_detected_dead_wall_stacks(self) -> None:
        indicator = TileBox(0.32, 0.30, 0.035, 0.05, 27, 0.9)
        backs = [
            TileBox(x, 0.30, 0.035, 0.05, face_score=0.0)
            for x in (0.28, 0.36)
        ]

        result = cluster_layout([*backs, indicator])

        self.assertEqual(result.dora[0].tiles, (indicator,))
        self.assertEqual(result.dead_wall.tile_count, 2)

    def test_dora_is_not_guessed_from_one_wall_stack(self) -> None:
        indicator = TileBox(0.32, 0.30, 0.035, 0.05, 27, 0.9)
        back = TileBox(0.28, 0.30, 0.035, 0.05, face_score=0.0)

        result = cluster_layout([back, indicator])

        self.assertIsNone(result.dead_wall)
        self.assertFalse(result.dora)

    def test_names_open_melds_from_gap_rotation_and_tile_classes(self) -> None:
        def tiles(
            xs: tuple[float, ...],
            classes: tuple[int | None, ...],
        ) -> list[TileBox]:
            return [
                TileBox(
                    x,
                    0.88,
                    0.025,
                    0.04,
                    class_id,
                    1.0,
                    0.0 if index == 0 else pi / 2,
                )
                for index, (x, class_id) in enumerate(
                    zip(xs, classes, strict=True)
                )
            ]

        concealed = [
            TileBox(
                0.10 + index * 0.03,
                0.88,
                0.025,
                0.04,
                face_score=1.0,
                angle=pi / 2,
            )
            for index in range(7)
        ]
        result = cluster_layout(
            concealed
            + tiles((0.35, 0.38, 0.41), (0, 1, None))
            + tiles((0.48, 0.51, 0.54), (9, 9, None))
            + tiles((0.61, 0.64, 0.67, 0.70), (18, 18, None, None)),
            LayoutParams(player_direction=(0, 1)),
        )

        self.assertEqual(
            [(meld.kind, len(meld.tiles)) for meld in result.melds],
            [("chi", 3), ("pon", 3), ("kan", 4)],
        )
        self.assertTrue(all(meld.called_index == 0 for meld in result.melds))

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

    def test_does_not_invent_discards_without_two_parallel_pairs(self) -> None:
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
        self.assertFalse(result.opponent_hands)
        self.assertFalse(result.discards)
        self.assertGreaterEqual(sum(group.tile_count for group in result.noise), 12)

    def test_closed_opponent_hands_are_noise(self) -> None:
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
        self.assertFalse(result.opponent_hands)
        self.assertIn(15, [group.tile_count for group in result.walls])

    def test_open_opponent_tiles_are_noise_without_four_discards(self) -> None:
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

        self.assertFalse(result.opponent_hands)
        self.assertFalse(result.discards)
        self.assertGreaterEqual(sum(group.tile_count for group in result.noise), 20)

    def test_hand_selection_skips_a_shorter_bottom_group(self) -> None:
        result = cluster_layout(
            row(0.70, 0.10, 13) + row(0.88, 0.55, 5),
            LayoutParams(player_direction=(0, -1)),
        )

        self.assertEqual(result.hand.tile_count, 13)
        self.assertIn(5, [group.tile_count for group in result.noise])

    def test_hand_search_keeps_expanding_until_it_reaches_thirteen(self) -> None:
        result = cluster_layout(row(0.20, 0.10, 13) + row(0.88, 0.55, 5))

        self.assertIsNotNone(result.hand)
        self.assertEqual(result.hand.tile_count, 18)

    def test_finds_four_discards_as_two_parallel_pairs(self) -> None:
        hand = [
            TileBox(0.18 + index * 0.045, 0.90, 0.035, 0.055, face_score=1)
            for index in range(14)
        ]

        def horizontal(y: float) -> list[TileBox]:
            return [
                TileBox(
                    tile.cx,
                    tile.cy,
                    tile.width,
                    tile.height,
                    face_score=1,
                )
                for tile in grid(
                    0.43,
                    y,
                    4,
                    2,
                    width=0.025,
                    height=0.04,
                    step_x=0.03,
                    step_y=0.045,
                )
            ]

        def vertical(x: float) -> list[TileBox]:
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
                    0.43,
                    2,
                    4,
                    width=0.04,
                    height=0.025,
                    step_x=0.045,
                    step_y=0.03,
                )
            ]

        paper = [
            TileBox(0.04 + index * 0.03, 0.72, 0.02, 0.03, face_score=1)
            for index in range(2)
        ]
        opponent_hand = [
            TileBox(0.15 + index * 0.045, 0.08, 0.035, 0.055, face_score=1)
            for index in range(14)
        ]
        result = cluster_layout(
            hand
            + horizontal(0.65)
            + horizontal(0.28)
            + vertical(0.24)
            + vertical(0.70)
            + paper
            + opponent_hand,
            LayoutParams(table_corners=((0, 0), (1, 0), (1, 1), (0, 1))),
        )

        self.assertEqual(len(result.discards), 4)
        self.assertEqual(
            {cluster.seat for cluster in result.discards},
            {"self", "left", "opposite", "right"},
        )
        self.assertEqual(sorted(cluster.tile_count for cluster in result.discards), [8] * 4)
        self.assertEqual(sum(cluster.tile_count for cluster in result.noise), 16)

    def test_three_discard_clusters_are_not_presented_as_a_complete_table(self) -> None:
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

        self.assertFalse(result.discards)
        self.assertEqual(sum(group.tile_count for group in result.noise), 18)

    def test_caps_an_overdetected_nearest_hand(self) -> None:
        near = row(0.88, 0.02, 20)
        opponent = row(0.20, 0.15, 13)

        result = cluster_layout(near + opponent)

        self.assertEqual(result.hand.tile_count, 18)
        self.assertEqual(sum(group.tile_count for group in result.noise), 15)


if __name__ == "__main__":
    unittest.main()
