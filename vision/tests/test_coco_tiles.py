import json
import tempfile
import unittest
from pathlib import Path

from vision.scripts.coco_tiles import convert


class CocoTilesTest(unittest.TestCase):
    def test_converts_all_categories_and_clips_boxes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            for split in ("train2017", "val2017"):
                (source / split).mkdir(parents=True)
                (source / split / "tile.jpg").write_bytes(b"\xff\xd8")
            (source / "annotations").mkdir()
            payload = {
                "images": [
                    {
                        "id": 1,
                        "file_name": "tile.jpg",
                        "width": 100,
                        "height": 50,
                    }
                ],
                "annotations": [
                    {"image_id": 1, "category_id": 48, "bbox": [-1, 10, 21, 20]}
                ],
            }
            for split in ("train2017", "val2017"):
                (source / "annotations" / f"instances_{split}.json").write_text(
                    json.dumps(payload)
                )

            target = root / "target"
            report = convert(source, target)

            self.assertEqual(report, {"train": (1, 1), "val": (1, 1)})
            self.assertTrue((target / "train/images/tile.jpg").is_symlink())
            self.assertEqual(
                (target / "train/labels/tile.txt").read_text(),
                "0 0.1 0.4 0.2 0.4\n",
            )


if __name__ == "__main__":
    unittest.main()
