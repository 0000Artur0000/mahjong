"""Estimate the visible table plane for perspective-aware grouping."""

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from dorahub_vision.layout import TileBox


def estimate_table_corners(
    image: np.ndarray,
) -> tuple[
    tuple[float, float],
    tuple[float, float],
    tuple[float, float],
    tuple[float, float],
]:
    if image.ndim != 3 or image.shape[2] != 3:
        raise ValueError("expected a BGR image")
    height, width = image.shape[:2]
    if min(height, width) < 32:
        raise ValueError("image is too small")
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB).astype(np.float32)
    sample = lab[
        round(height * 0.28) : round(height * 0.75),
        round(width * 0.18) : round(width * 0.82),
    ].reshape(-1, 3)
    table_color = np.median(sample, axis=0)
    mask = (
        np.linalg.norm(lab - table_color, axis=2) < 30
    ).astype(np.uint8) * 255
    size = max(9, round(min(height, width) * 0.018)) | 1
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size)),
        iterations=2,
    )
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)),
    )
    points = cv2.findNonZero(mask)
    if points is None:
        raise ValueError("table surface was not found")
    hull = cv2.convexHull(points)
    perimeter = cv2.arcLength(hull, True)
    polygon = None
    for epsilon in np.linspace(0.02, 0.08, 13):
        candidate = cv2.approxPolyDP(hull, epsilon * perimeter, True)
        if len(candidate) == 4:
            polygon = candidate[:, 0]
            break
    if polygon is None or cv2.contourArea(polygon) < width * height * 0.35:
        raise ValueError("table plane is incomplete")
    top = sorted(polygon, key=lambda point: point[1])[:2]
    bottom = sorted(polygon, key=lambda point: point[1])[2:]
    top_left, top_right = sorted(top, key=lambda point: point[0])
    bottom_left, bottom_right = sorted(bottom, key=lambda point: point[0])
    ordered = top_left, top_right, bottom_right, bottom_left
    return tuple(
        (
            min(1.0, max(0.0, float(x) / max(1, width - 1))),
            min(1.0, max(0.0, float(y) / max(1, height - 1))),
        )
        for x, y in ordered
    )


def appearance_face_score(image: np.ndarray, tile: TileBox) -> float:
    """Fallback for tiny face-up tiles missed by the face classifier."""

    if image.ndim != 3 or image.shape[2] != 3:
        raise ValueError("expected a BGR image")
    height, width = image.shape[:2]
    left = round((tile.cx - tile.width * 0.3) * width)
    right = round((tile.cx + tile.width * 0.3) * width)
    top = round((tile.cy - tile.height * 0.3) * height)
    bottom = round((tile.cy + tile.height * 0.3) * height)
    patch = cv2.cvtColor(
        image[
            max(0, top) : min(height, bottom),
            max(0, left) : min(width, right),
        ],
        cv2.COLOR_BGR2GRAY,
    )
    if patch.size < 16:
        return 0.0
    return min(1.0, max(0.0, (float(patch.std()) - 45) / 10))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("images", type=Path, nargs="+")
    args = parser.parse_args()
    for path in args.images:
        image = cv2.imread(str(path))
        if image is None:
            raise ValueError(f"cannot decode {path}")
        print(
            json.dumps(
                {
                    "image": str(path),
                    "tableCorners": estimate_table_corners(image),
                }
            )
        )


if __name__ == "__main__":
    main()
