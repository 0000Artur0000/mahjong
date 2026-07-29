import unittest

from vision.scripts.riichi_litert import (
    SOURCE_CLASSES,
    _choose_hand,
    _generic_tiles,
    _iou,
    _nms,
    predict,
)


class RiichiLiteRTTest(unittest.TestCase):
    def test_class_map_and_class_agnostic_nms(self) -> None:
        self.assertEqual(len(SOURCE_CLASSES), 38)
        self.assertEqual(SOURCE_CLASSES[19:28:4], ("C", "F", "P"))
        first = {"tile": "1m", "confidence": 0.9, "box": [0.5, 0.5, 0.2, 0.2]}
        duplicate = {
            "tile": "2m",
            "confidence": 0.8,
            "box": [0.51, 0.5, 0.2, 0.2],
        }
        separate = {"tile": "3m", "confidence": 0.7, "box": [0.8, 0.8, 0.1, 0.1]}

        self.assertGreater(_iou(first["box"], duplicate["box"]), 0.45)
        self.assertEqual(_nms([duplicate, separate, first], 0.45), [first, separate])
        with self.assertRaises(ValueError):
            predict(None, None, confidence=float("nan"))
        with self.assertRaises(ValueError):
            predict(None, None, region=(0.0, 0.9, 1.0, 0.5))

    def test_nearest_hand_rejects_sparse_noise(self) -> None:
        sparse = [
            {
                "tile": "1m",
                "confidence": 0.9,
                "box": [0.1 + index * 0.05, 0.7, 0.04, 0.06],
            }
            for index in range(7)
        ]
        hand = [
            {
                "tile": "1m",
                "confidence": 0.9,
                "box": [0.1 + index * 0.04, 0.7, 0.035, 0.05],
            }
            for index in range(13)
        ]

        self.assertEqual(_choose_hand(sparse, sparse), [])
        self.assertEqual(_choose_hand(sparse, hand), hand)

    def test_full_field_returns_only_tile_boxes(self) -> None:
        detections = [
            {
                "tile": "1m",
                "confidence": 0.9,
                "box": [0.5, 0.5, 0.04, 0.04],
                "alternatives": [{"tile": "2m", "confidence": 0.1}],
            },
            {"tile": "2m", "confidence": 0.8, "box": [0.5, 0.5, 0.2, 0.01]},
            {"tile": "3m", "confidence": 0.7, "box": [0.5, 0.5, 0.005, 0.005]},
        ]

        self.assertEqual(
            _generic_tiles(detections, (1000, 1000)),
            [{"tile": "tile", "confidence": 0.9, "box": [0.5, 0.5, 0.04, 0.04]}],
        )


if __name__ == "__main__":
    unittest.main()
