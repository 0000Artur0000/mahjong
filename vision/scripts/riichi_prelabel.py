"""Create a review-only YOLO draft from the audited mobile Riichi model."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import tempfile
from collections import Counter
from pathlib import Path

from dorahub_vision.riichi import MODEL_TILES
from vision.scripts.riichi_litert import predict

SOURCE_URL = (
    "https://www.kaggle.com/datasets/"
    "shinz114514/mahjong-hand-photos-taken-with-mobile-camera"
)
SOURCE_SHA256 = "35c076754dbac0a3d4345780cf731272f9df2b4e1168d0222b0b161f611044df"
MODEL_SHA256 = "04e92b1b58256806d1bfb301e2f4d212469532d8c688423636879e48c340dbe5"
EXPECTED_IMAGES = 257


def prelabel(
    source: Path,
    target: Path,
    model: Path,
    confidence: float = 0.2,
) -> dict[str, object]:
    images = sorted(source.glob("*.png")) if source.is_dir() else []
    if len(images) != EXPECTED_IMAGES:
        raise ValueError(f"expected Kaggle v1 with {EXPECTED_IMAGES} PNG images")
    if target.exists():
        raise ValueError(f"target already exists: {target}")
    if not model.is_file() or _sha256(model) != MODEL_SHA256:
        raise ValueError("model does not match the audited LiteRT artifact")
    if not 0 < confidence <= 1:
        raise ValueError("confidence must be within (0, 1]")

    target.parent.mkdir(parents=True, exist_ok=True)
    draft = Path(tempfile.mkdtemp(prefix=f".{target.name}-", dir=target.parent))
    image_dir, label_dir = draft / "images", draft / "labels"
    image_dir.mkdir()
    label_dir.mkdir()
    counts: Counter[str] = Counter()
    skipped_unknown = 0
    predictions = (draft / "predictions.jsonl").open("w", encoding="utf-8")
    review = (draft / "review.csv").open("w", encoding="utf-8", newline="")
    review_writer = csv.writer(review)
    review_writer.writerow(("image", "status", "session"))
    try:
        for index, image in enumerate(images, 1):
            detections = predict(model, image, confidence=confidence)
            rows = []
            for detection in detections:
                tile = detection["tile"]
                if tile not in MODEL_TILES:
                    skipped_unknown += 1
                    continue
                counts[tile] += 1
                rows.append(
                    f"{MODEL_TILES.index(tile)} "
                    + " ".join(f"{value:.8f}" for value in detection["box"])
                )
            image_dir.joinpath(image.name).symlink_to(image.resolve())
            label_dir.joinpath(f"{image.stem}.txt").write_text(
                "\n".join(rows) + ("\n" if rows else ""),
                encoding="utf-8",
            )
            predictions.write(
                json.dumps(
                    {"image": image.name, "detections": detections},
                    separators=(",", ":"),
                )
                + "\n"
            )
            review_writer.writerow((image.name, "unreviewed", ""))
            if index % 10 == 0 or index == len(images):
                print(f"{index}/{len(images)}", flush=True)
    finally:
        predictions.close()
        review.close()
    report = {
        "images": len(images),
        "detections": sum(counts.values()),
        "skippedUnknown": skipped_unknown,
        "classInstances": dict(sorted(counts.items())),
        "status": "draft_requires_manual_review",
    }
    (draft / "classes.txt").write_text(
        "\n".join(MODEL_TILES) + "\n", encoding="utf-8"
    )
    (draft / "source.json").write_text(
        json.dumps(
            {
                "source": SOURCE_URL,
                "version": 1,
                "licenseMetadata": "MIT",
                "archiveSha256": SOURCE_SHA256,
                "modelSha256": MODEL_SHA256,
                "confidence": confidence,
                **report,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    (draft / "README.txt").write_text(
        "DRAFT ONLY: correct every box/class, then mark review.csv and assign "
        "capture sessions before creating train/val/test splits.\n",
        encoding="utf-8",
    )
    draft.replace(target)
    print(json.dumps(report, sort_keys=True))
    return report


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as payload:
        for chunk in iter(lambda: payload.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("model", type=Path)
    parser.add_argument("--confidence", type=float, default=0.2)
    args = parser.parse_args()
    prelabel(args.source, args.target, args.model, args.confidence)


if __name__ == "__main__":
    main()
