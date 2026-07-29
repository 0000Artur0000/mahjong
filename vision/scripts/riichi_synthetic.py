"""Generate and verify a synthetic 37-class Riichi YOLO smoke dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw

from dorahub_vision.riichi import MODEL_TILES

ASSET_SOURCE = "https://github.com/FluffyStuff/riichi-mahjong-tiles"
ASSET_COMMIT = "26e127ba2117f45cdce5ea0225748cc0cfad3169"
SPLITS = ("train", "val", "test")
ASSET_NAMES = {
    **{f"{rank}m": f"Man{rank}" for rank in range(1, 10)},
    **{f"{rank}p": f"Pin{rank}" for rank in range(1, 10)},
    **{f"{rank}s": f"Sou{rank}" for rank in range(1, 10)},
    "E": "Ton",
    "S": "Nan",
    "W": "Shaa",
    "N": "Pei",
    "P": "Haku",
    "F": "Hatsu",
    "C": "Chun",
    "0m": "Man5-Dora",
    "0p": "Pin5-Dora",
    "0s": "Sou5-Dora",
}


def generate_dataset(
    assets: Path,
    target: Path,
    *,
    counts: tuple[int, int, int] = (370, 74, 74),
    image_size: int = 640,
    seed: int = 42,
) -> None:
    if target.exists():
        raise ValueError(f"target already exists: {target}")
    if image_size < 128 or len(counts) != 3 or any(count < len(MODEL_TILES) for count in counts):
        raise ValueError("image_size must be >= 128 and every split needs at least 37 scenes")

    faces, asset_digest = _load_faces(assets)
    target.mkdir(parents=True)
    (target / "classes.txt").write_text("\n".join(MODEL_TILES) + "\n", encoding="utf-8")
    (target / "data.yaml").write_text(
        f"path: {target.resolve().as_posix()}\n"
        "train: train/images\nval: val/images\ntest: test/images\nnames:\n"
        + "".join(f"  {index}: {tile}\n" for index, tile in enumerate(MODEL_TILES)),
        encoding="utf-8",
    )
    (target / "source.json").write_text(
        json.dumps(
            {
                "source": ASSET_SOURCE,
                "commit": ASSET_COMMIT,
                "license": "public domain",
                "assetSha256": asset_digest,
                "seed": seed,
                "imageSize": image_size,
                "splitCounts": dict(zip(SPLITS, counts, strict=True)),
                "purpose": "synthetic pipeline smoke only; not product validation",
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    for split_index, (split, count) in enumerate(zip(SPLITS, counts, strict=True)):
        images = target / split / "images"
        labels = target / split / "labels"
        images.mkdir(parents=True)
        labels.mkdir(parents=True)
        rng = random.Random(seed + split_index)
        for index in range(count):
            image, rows = _scene(
                faces,
                rng,
                image_size,
                forced_tile=MODEL_TILES[index % len(MODEL_TILES)],
            )
            name = f"{split}-{index:06d}"
            image.convert("RGB").save(images / f"{name}.jpg", quality=92)
            (labels / f"{name}.txt").write_text("\n".join(rows) + "\n", encoding="utf-8")
    check_dataset(target)


def check_dataset(dataset: Path) -> dict[str, object]:
    classes = tuple(
        line.strip()
        for line in (dataset / "classes.txt").read_text(encoding="utf-8").splitlines()
        if line.strip()
    )
    if classes != MODEL_TILES:
        raise ValueError("classes.txt must match the canonical 37-class order")

    hashes: dict[str, str] = {}
    totals = [0] * len(MODEL_TILES)
    split_counts: dict[str, dict[str, int]] = {}
    for split in SPLITS:
        image_dir, label_dir = dataset / split / "images", dataset / split / "labels"
        images = {path.stem: path for path in image_dir.glob("*.jpg")}
        labels = {path.stem: path for path in label_dir.glob("*.txt")}
        if not images or images.keys() != labels.keys():
            raise ValueError(f"unpaired or empty split: {split}")
        per_class = [0] * len(MODEL_TILES)
        boxes = 0
        for name, image in images.items():
            payload = image.read_bytes()
            if not payload.startswith(b"\xff\xd8"):
                raise ValueError(f"not a JPEG: {image}")
            digest = hashlib.sha256(payload).hexdigest()
            if digest in hashes:
                raise ValueError(f"duplicate image across splits: {hashes[digest]} and {image}")
            hashes[digest] = str(image)
            for line in labels[name].read_text(encoding="utf-8").splitlines():
                parts = line.split()
                if len(parts) != 5:
                    raise ValueError(f"invalid YOLO row: {labels[name]}")
                try:
                    class_id = int(parts[0])
                    box = tuple(float(value) for value in parts[1:])
                except ValueError as error:
                    raise ValueError(f"invalid YOLO row: {labels[name]}") from error
                if not 0 <= class_id < len(MODEL_TILES):
                    raise ValueError(f"class outside canonical map: {labels[name]}")
                _validate_box(box)
                per_class[class_id] += 1
                totals[class_id] += 1
                boxes += 1
        missing = [MODEL_TILES[index] for index, count in enumerate(per_class) if not count]
        if missing:
            raise ValueError(f"classes missing from {split}: {missing}")
        split_counts[split] = {"images": len(images), "boxes": boxes}

    source = json.loads((dataset / "source.json").read_text(encoding="utf-8"))
    if source.get("commit") != ASSET_COMMIT or source.get("license") != "public domain":
        raise ValueError("source.json must pin the audited public-domain asset source")
    if source.get("splitCounts") != {
        split: split_counts[split]["images"] for split in SPLITS
    }:
        raise ValueError("source.json split counts do not match the dataset")
    if not (dataset / "data.yaml").read_text(encoding="utf-8").startswith(
        f"path: {dataset.resolve().as_posix()}\n"
    ):
        raise ValueError("data.yaml must point to the current absolute dataset root")
    report = {
        "classes": len(MODEL_TILES),
        "splits": split_counts,
        "classInstances": dict(zip(MODEL_TILES, totals, strict=True)),
    }
    print(json.dumps(report, sort_keys=True))
    return report


def _load_faces(assets: Path) -> tuple[dict[str, Image.Image], str]:
    root = assets / "Export" / "Regular"
    license_payload = (assets / "LICENSE.md").read_bytes()
    license_text = license_payload.decode().lower()
    if "public domain" not in license_text:
        raise ValueError("asset source must include its public-domain license")
    front_payload = (root / "Front.png").read_bytes()
    front = Image.open(root / "Front.png").convert("RGBA")
    faces: dict[str, Image.Image] = {}
    digest = hashlib.sha256()
    digest.update(license_payload)
    digest.update(front_payload)
    for tile in MODEL_TILES:
        path = root / f"{ASSET_NAMES[tile]}.png"
        payload = path.read_bytes()
        digest.update(tile.encode())
        digest.update(payload)
        face = front.copy()
        face.alpha_composite(Image.open(path).convert("RGBA"))
        faces[tile] = face
    return faces, digest.hexdigest()


def _scene(
    faces: dict[str, Image.Image],
    rng: random.Random,
    size: int,
    *,
    forced_tile: str,
) -> tuple[Image.Image, list[str]]:
    color = (rng.randint(20, 60), rng.randint(75, 125), rng.randint(60, 110), 255)
    image = Image.new("RGBA", (size, size), color)
    draw = ImageDraw.Draw(image)
    for _ in range(24):
        x, y = rng.randrange(size), rng.randrange(size)
        shade = rng.randint(-18, 18)
        fill = tuple(max(0, min(255, channel + shade)) for channel in color[:3]) + (30,)
        draw.ellipse((x, y, x + rng.randint(2, 20), y + rng.randint(2, 20)), fill=fill)

    count = rng.randint(7, 18)
    hand = [forced_tile, *(rng.choice(MODEL_TILES) for _ in range(count - 1))]
    rng.shuffle(hand)
    width = max(18, min(rng.randint(30, 48), int((size - 24) / (count * 0.9))))
    step = width * rng.uniform(0.86, 0.96)
    start_x = max(4, (size - (step * (count - 1) + width)) / 2)
    base_y = rng.uniform(size * 0.69, size * 0.82)
    rows = [
        _place(image, faces[tile], tile, start_x + index * step, base_y + rng.uniform(-3, 3), width, rng)
        for index, tile in enumerate(hand)
    ]

    for _ in range(rng.randint(0, 6)):
        tile = rng.choice(MODEL_TILES)
        discard_width = rng.randint(max(16, width - 8), width)
        rows.append(
            _place(
                image,
                faces[tile],
                tile,
                rng.uniform(6, size - discard_width - 6),
                rng.uniform(size * 0.28, size * 0.58),
                discard_width,
                rng,
                angle=rng.uniform(-10, 10),
            )
        )
    return image, rows


def _place(
    target: Image.Image,
    source: Image.Image,
    tile: str,
    x: float,
    y: float,
    width: int,
    rng: random.Random,
    *,
    angle: float | None = None,
) -> str:
    height = round(width * 4 / 3)
    rendered = source.resize((width, height), Image.Resampling.LANCZOS)
    rendered = rendered.rotate(
        rng.uniform(-4, 4) if angle is None else angle,
        resample=Image.Resampling.BICUBIC,
        expand=True,
    )
    box = rendered.getbbox()
    if box is None:
        raise ValueError(f"empty tile asset: {tile}")
    rendered = rendered.crop(box)
    px = max(0, min(round(x), target.width - rendered.width))
    py = max(0, min(round(y), target.height - rendered.height))
    target.alpha_composite(rendered, (px, py))
    cx = (px + rendered.width / 2) / target.width
    cy = (py + rendered.height / 2) / target.height
    normalized = (cx, cy, rendered.width / target.width, rendered.height / target.height)
    _validate_box(normalized)
    return f"{MODEL_TILES.index(tile)} " + " ".join(f"{value:.6f}" for value in normalized)


def _validate_box(box: tuple[float, ...]) -> None:
    if len(box) != 4:
        raise ValueError("box must contain cx, cy, width, height")
    cx, cy, width, height = box
    if (
        not all(math.isfinite(value) for value in box)
        or width <= 0
        or height <= 0
        or cx - width / 2 < -1e-6
        or cx + width / 2 > 1 + 1e-6
        or cy - height / 2 < -1e-6
        or cy + height / 2 > 1 + 1e-6
    ):
        raise ValueError(f"box outside normalized image: {box}")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    generator = subparsers.add_parser("generate")
    generator.add_argument("assets", type=Path)
    generator.add_argument("target", type=Path)
    generator.add_argument("--train", type=int, default=370)
    generator.add_argument("--val", type=int, default=74)
    generator.add_argument("--test", type=int, default=74)
    generator.add_argument("--image-size", type=int, default=640)
    generator.add_argument("--seed", type=int, default=42)
    checker = subparsers.add_parser("check")
    checker.add_argument("dataset", type=Path)
    args = parser.parse_args()
    if args.command == "generate":
        generate_dataset(
            args.assets,
            args.target,
            counts=(args.train, args.val, args.test),
            image_size=args.image_size,
            seed=args.seed,
        )
    else:
        check_dataset(args.dataset)


if __name__ == "__main__":
    main()
