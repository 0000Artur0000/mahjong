"""Convert a COCO mahjong dataset to single-class YOLO detection labels."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

SPLITS = {"train": "train2017", "val": "val2017"}


def convert(source: Path, target: Path) -> dict[str, tuple[int, int]]:
    if target.exists():
        raise ValueError(f"target already exists: {target}")
    report = {}
    for split, image_dir_name in SPLITS.items():
        data = json.loads(
            (source / "annotations" / f"instances_{image_dir_name}.json").read_text()
        )
        annotations = defaultdict(list)
        for annotation in data["annotations"]:
            annotations[annotation["image_id"]].append(annotation["bbox"])

        images_dir = target / split / "images"
        labels_dir = target / split / "labels"
        images_dir.mkdir(parents=True)
        labels_dir.mkdir(parents=True)
        box_count = 0
        for image in data["images"]:
            source_image = (source / image_dir_name / image["file_name"]).resolve()
            if not source_image.is_file():
                raise ValueError(f"missing image: {source_image}")
            (images_dir / image["file_name"]).symlink_to(source_image)
            rows = []
            width, height = image["width"], image["height"]
            for x, y, box_width, box_height in annotations[image["id"]]:
                x1, y1 = max(0, x), max(0, y)
                x2, y2 = min(width, x + box_width), min(height, y + box_height)
                if x2 <= x1 or y2 <= y1:
                    raise ValueError(f"empty box in {image['file_name']}")
                rows.append(
                    "0 "
                    + " ".join(
                        f"{value:.10g}"
                        for value in (
                            (x1 + x2) / (2 * width),
                            (y1 + y2) / (2 * height),
                            (x2 - x1) / width,
                            (y2 - y1) / height,
                        )
                    )
                )
            (labels_dir / f"{Path(image['file_name']).stem}.txt").write_text(
                "\n".join(rows) + ("\n" if rows else "")
            )
            box_count += len(rows)
        report[split] = (len(data["images"]), box_count)

    (target / "data.yaml").write_text(
        f"path: {target.resolve()}\n"
        "train: train/images\n"
        "val: val/images\n"
        "names:\n  0: tile\n"
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    print(convert(args.source, args.target))


if __name__ == "__main__":
    main()
