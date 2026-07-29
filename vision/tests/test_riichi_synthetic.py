import tempfile
import unittest
from pathlib import Path

from PIL import Image

from dorahub_vision.riichi import MODEL_TILES
from vision.scripts.riichi_synthetic import ASSET_NAMES, check_dataset, generate_dataset


class RiichiSyntheticDatasetTest(unittest.TestCase):
    def test_generates_audited_canonical_dataset(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets = root / "assets"
            export = assets / "Export" / "Regular"
            export.mkdir(parents=True)
            (assets / "LICENSE.md").write_text("This work is in the public domain.\n")
            tile = Image.new("RGBA", (60, 80), (245, 240, 230, 255))
            tile.save(export / "Front.png")
            for name in set(ASSET_NAMES.values()):
                glyph = Image.new("RGBA", tile.size, (0, 0, 0, 0))
                glyph.putpixel((30, 40), (20, 20, 20, 255))
                glyph.save(export / f"{name}.png")

            dataset = root / "dataset"
            generate_dataset(
                assets,
                dataset,
                counts=(len(MODEL_TILES),) * 3,
                image_size=128,
                seed=7,
            )
            report = check_dataset(dataset)

            self.assertEqual(report["classes"], 37)
            self.assertEqual(report["splits"]["train"]["images"], 37)
            self.assertIn(f"path: {dataset.resolve()}", (dataset / "data.yaml").read_text())


if __name__ == "__main__":
    unittest.main()
