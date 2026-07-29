import json
import unittest
from pathlib import Path

from dorahub_vision.layout import TileBox
from dorahub_vision.riichi import (
    RiichiDetection,
    TileCandidate,
    build_vision_result,
    dora_from_indicator,
    normalize_tile,
)


def detected(
    tile: str,
    x: float,
    role: str,
    confidence: float = 0.95,
) -> RiichiDetection:
    return RiichiDetection(tile, confidence, TileBox(x, 0.8, 0.04, 0.06), role)


class RiichiVisionTest(unittest.TestCase):
    def test_normalizes_riichi_classes_and_rotates_dora(self) -> None:
        self.assertEqual(normalize_tile("m_0"), "0m")
        self.assertEqual(normalize_tile("z7"), "C")
        self.assertEqual(normalize_tile("haku"), "P")
        self.assertEqual(dora_from_indicator("9p"), "1p")
        self.assertEqual(dora_from_indicator("N"), "E")
        self.assertEqual(dora_from_indicator("C"), "P")

    def test_builds_ordered_riichi_result_matching_contract_v2(self) -> None:
        result = build_vision_result(
            (
                detected("3m", 0.3, "hand"),
                detected("1m", 0.1, "hand"),
                detected("4p", 0.5, "dora"),
                detected("E", 0.7, "discard"),
            ),
            model_version="riichi-test",
            minimum_confidence=0.8,
        )

        self.assertEqual(result["status"], "accepted")
        self.assertEqual(result["zones"]["hand"], ["1m", "3m"])
        self.assertEqual(result["zones"]["doraIndicators"], ["4p"])
        self.assertEqual(result["zones"]["doras"], ["5p"])
        schema = json.loads(
            (
                Path(__file__).parents[2]
                / "contracts"
                / "vision"
                / "vision-result.schema.json"
            ).read_text()
        )
        self.assertEqual(schema["properties"]["schemaVersion"]["const"], 2)
        self.assertTrue(set(schema["required"]) <= set(result))

    def test_rejects_uncertain_or_fake_model_output(self) -> None:
        result = build_vision_result(
            (detected("unknown", 0.1, "hand", 0.4),),
            model_version="riichi-test",
            minimum_confidence=0.8,
        )

        self.assertEqual(result["status"], "rejected")
        self.assertEqual(
            result["warnings"],
            ["missing_hand", "unclassified_tile", "low_confidence"],
        )
        self.assertEqual(result["zones"]["hand"], [])
        with self.assertRaises(ValueError):
            RiichiDetection.from_model(
                {
                    "tile": "tile",
                    "confidence": 0.5,
                    "box": [0.5, 0.5, 0.1, 0.1],
                    "role": "hand",
                }
            )
        with self.assertRaises(ValueError):
            RiichiDetection(
                "1m",
                0.9,
                TileBox(0.5, 0.5, 0.1, 0.1),
                "hand",
                (TileCandidate("2m", 0.5), TileCandidate("3m", 0.7)),
            )


if __name__ == "__main__":
    unittest.main()
