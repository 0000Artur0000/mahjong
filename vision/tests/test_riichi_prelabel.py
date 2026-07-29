import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from vision.scripts.riichi_prelabel import (
    EXPECTED_IMAGES,
    MODEL_SHA256,
    prelabel,
)


class RiichiPrelabelTest(unittest.TestCase):
    def test_exports_review_only_yolo_draft(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, target, model = root / "source", root / "draft", root / "model"
            source.mkdir()
            model.write_bytes(b"model")
            for index in range(EXPECTED_IMAGES):
                Image.new("RGB", (2, 2)).save(source / f"{index:06d}.png")
            detections = [
                {
                    "tile": "1m",
                    "confidence": 0.9,
                    "box": [0.5, 0.5, 0.2, 0.2],
                    "alternatives": [],
                },
                {
                    "tile": "unknown",
                    "confidence": 0.3,
                    "box": [0.2, 0.2, 0.1, 0.1],
                    "alternatives": [],
                },
            ]

            with (
                patch(
                    "vision.scripts.riichi_prelabel._sha256",
                    return_value=MODEL_SHA256,
                ),
                patch(
                    "vision.scripts.riichi_prelabel.predict",
                    return_value=detections,
                ),
                redirect_stdout(StringIO()),
            ):
                report = prelabel(source, target, model)

            self.assertEqual(report["detections"], EXPECTED_IMAGES)
            self.assertEqual(report["skippedUnknown"], EXPECTED_IMAGES)
            self.assertTrue((target / "images/000000.png").is_symlink())
            self.assertEqual(
                (target / "labels/000000.txt").read_text(),
                "0 0.50000000 0.50000000 0.20000000 0.20000000\n",
            )
            self.assertNotIn("data.yaml", {path.name for path in target.iterdir()})


if __name__ == "__main__":
    unittest.main()
