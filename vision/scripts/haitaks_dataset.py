"""Import and verify the CC BY 4.0 haitaks/mahjong dataset."""

from __future__ import annotations

import argparse
import hashlib
import math
import shutil
from pathlib import Path

SPLITS = ("train", "valid", "test")
SOURCE_COMMIT = "4955fb61e3d9dab7e6a3640ce2a63759ca0da27f"


def import_dataset(source: Path, target: Path) -> None:
    if target.exists():
        raise ValueError(f"target already exists: {target}")
    target.mkdir(parents=True)
    seen_images: dict[str, str] = {}
    for split in SPLITS:
        source_images = source / split / "images"
        source_labels = source / split / "labels"
        target_images = target / split / "images"
        target_labels = target / split / "labels"
        target_images.mkdir(parents=True)
        target_labels.mkdir(parents=True)
        images = {path.stem: path for path in source_images.glob("*.jpg")}
        labels = {path.stem: path for path in source_labels.glob("*.txt")}
        if images.keys() != labels.keys():
            raise ValueError(f"unpaired images/labels in {split}")
        for name in sorted(images):
            label_text = _single_class_labels(labels[name])
            image_hash = hashlib.sha256(images[name].read_bytes()).hexdigest()
            if image_hash in seen_images:
                if seen_images[image_hash] != label_text:
                    raise ValueError(f"duplicate image has conflicting labels: {images[name]}")
                continue
            seen_images[image_hash] = label_text
            shutil.copy2(images[name], target_images / images[name].name)
            (target_labels / f"{name}.txt").write_text(label_text, encoding="utf-8")

    for name in ("README.dataset.txt", "README.roboflow.txt"):
        (target / name).write_text(
            (source / name).read_text(encoding="utf-8").rstrip() + "\n",
            encoding="utf-8",
        )
    (target / "data.yaml").write_text(
        "path: .\ntrain: train/images\nval: valid/images\ntest: test/images\n"
        "names:\n  0: tile\n",
        encoding="utf-8",
    )
    (target / "SOURCE.md").write_text(
        "# Dataset source\n\n"
        "Imported from [haitaks/mahjong](https://github.com/haitaks/mahjong) "
        f"commit `{SOURCE_COMMIT}`.\n"
        "The source is [Roboflow Mahjong_YOLO v2]"
        "(https://universe.roboflow.com/test-wmo8i/mahjong_yolo/dataset/2446) "
        "and declares "
        "[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), "
        "workspace `test-wmo8i`, project `mahjong_yolo`, version `2`.\n\n"
        "The upstream 86-class map is corrupted, so all valid boxes are remapped "
        "to class `0: tile`; eight polygon rows are converted to bounding boxes. "
        "Nine exact image+label duplicates are removed, leaving 209 images. "
        "This dataset is a detector/layout baseline, not a tile-classification "
        "or product validation set.\n",
        encoding="utf-8",
    )
    _write_manifest(target)
    check_dataset(target)


def check_dataset(dataset: Path) -> None:
    image_count = label_count = box_count = 0
    image_hashes: set[str] = set()
    for split in SPLITS:
        images = {path.stem: path for path in (dataset / split / "images").glob("*.jpg")}
        labels = {path.stem: path for path in (dataset / split / "labels").glob("*.txt")}
        if not images or images.keys() != labels.keys():
            raise ValueError(f"unpaired or empty split: {split}")
        for image in images.values():
            payload = image.read_bytes()
            if not payload.startswith(b"\xff\xd8"):
                raise ValueError(f"not a JPEG: {image}")
            digest = hashlib.sha256(payload).hexdigest()
            if digest in image_hashes:
                raise ValueError(f"duplicate image: {image}")
            image_hashes.add(digest)
        for label in labels.values():
            for line in label.read_text(encoding="utf-8").splitlines():
                parts = line.split()
                if len(parts) != 5 or parts[0] != "0":
                    raise ValueError(f"invalid label row: {label}")
                _validate_box(tuple(map(float, parts[1:])))
                box_count += 1
        image_count += len(images)
        label_count += len(labels)
    _check_manifest(dataset)
    print(f"dataset ok: images={image_count} labels={label_count} boxes={box_count}")


def _single_class_labels(source: Path) -> str:
    rows: list[str] = []
    for line in source.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if not parts:
            continue
        values = tuple(map(float, parts[1:]))
        if len(parts) == 5:
            box = values
        elif len(values) >= 6 and len(values) % 2 == 0:
            xs, ys = values[::2], values[1::2]
            box = (
                (min(xs) + max(xs)) / 2,
                (min(ys) + max(ys)) / 2,
                max(xs) - min(xs),
                max(ys) - min(ys),
            )
        else:
            raise ValueError(f"unsupported annotation: {source}")
        _validate_box(box)
        rows.append("0 " + " ".join(f"{value:.10g}" for value in box))
    return "\n".join(rows) + ("\n" if rows else "")


def _validate_box(box: tuple[float, ...]) -> None:
    if len(box) != 4:
        raise ValueError("box must contain cx, cy, width, height")
    cx, cy, width, height = box
    if (
        not all(math.isfinite(value) for value in box)
        or width <= 0
        or height <= 0
        or cx - width / 2 < -1e-9
        or cx + width / 2 > 1 + 1e-9
        or cy - height / 2 < -1e-9
        or cy + height / 2 > 1 + 1e-9
    ):
        raise ValueError(f"box outside normalized image: {box}")


def _write_manifest(dataset: Path) -> None:
    files = sorted(
        path
        for split in SPLITS
        for path in (dataset / split).rglob("*")
        if path.is_file()
    )
    (dataset / "payload.sha256").write_text(
        "".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  "
            f"{path.relative_to(dataset).as_posix()}\n"
            for path in files
        ),
        encoding="utf-8",
    )


def _check_manifest(dataset: Path) -> None:
    for line in (dataset / "payload.sha256").read_text(encoding="utf-8").splitlines():
        expected, relative = line.split("  ", 1)
        actual = hashlib.sha256((dataset / relative).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f"checksum mismatch: {relative}")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    importer = subparsers.add_parser("import")
    importer.add_argument("source", type=Path)
    importer.add_argument("target", type=Path)
    checker = subparsers.add_parser("check")
    checker.add_argument("dataset", type=Path)
    args = parser.parse_args()
    if args.command == "import":
        import_dataset(args.source, args.target)
    else:
        check_dataset(args.dataset)


if __name__ == "__main__":
    main()
