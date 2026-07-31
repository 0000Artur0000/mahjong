import json
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from vision.scripts.layout_preview import render_predictions


class LayoutPreviewTest(unittest.TestCase):
    def test_renders_real_cli_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "scene.jpg"
            cv2.imwrite(str(image), np.full((200, 200, 3), 180, dtype=np.uint8))
            predictions = root / "predictions.json"
            faces = root / "faces.json"
            predictions.write_text(
                json.dumps([{"image": image.name, "detections": []}])
            )
            faces.write_text(
                json.dumps([{"image": image.name, "detections": []}])
            )

            rendered = render_predictions(
                predictions,
                faces,
                root,
                root / "output",
            )

            self.assertEqual(len(rendered), 1)
            self.assertTrue(rendered[0].is_file())
            self.assertTrue((root / "output/groups.json").is_file())

    def test_accepts_countgd_polygons_without_a_face_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "scene.jpg"
            cv2.imwrite(str(image), np.full((200, 200, 3), 180, dtype=np.uint8))
            predictions = root / "countgd.json"
            predictions.write_text(
                json.dumps(
                    {
                        image.name: {
                            "boxes": [[50, 50, 100, 120]],
                            "scores": [0.9],
                            "polygons": [
                                [[50, 50], [100, 50], [100, 120], [50, 120]]
                            ],
                        }
                    }
                )
            )

            rendered = render_predictions(
                predictions,
                Path("-"),
                root,
                root / "output",
            )

            self.assertEqual(len(rendered), 1)
            self.assertTrue(rendered[0].is_file())


if __name__ == "__main__":
    unittest.main()
