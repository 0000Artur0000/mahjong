import json
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np

from dorahub_vision.layout import TileBox
from vision.scripts.layout_preview import _tile_outline, render_predictions


class LayoutPreviewTest(unittest.TestCase):
    def test_prefers_a_segmented_tile_silhouette(self) -> None:
        tile = TileBox(0.5, 0.5, 0.1, 0.2)
        polygon = np.array(((10, 20), (30, 15), (35, 50), (12, 55)))

        actual = _tile_outline(
            tile,
            (200, 300, 3),
            {(tile.cx, tile.cy, tile.width, tile.height): polygon},
        )

        np.testing.assert_array_equal(actual, polygon)

    def test_renders_real_cli_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "scene.jpg"
            cv2.imwrite(str(image), np.full((200, 200, 3), 180, dtype=np.uint8))
            predictions = root / "predictions.json"
            faces = root / "faces.json"
            predictions.write_text(
                json.dumps(
                    [
                        {
                            "image": image.name,
                            "detections": [
                                {
                                    "box": [0.5, 0.5, 0.2, 0.3],
                                    "confidence": 0.9,
                                }
                            ],
                        }
                    ]
                )
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
            scene = json.loads((root / "output/groups.json").read_text())[0][
                "sceneTiles"
            ]
            self.assertEqual(len(scene), 1)
            self.assertEqual(len(scene[0]["position"]), 3)

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
