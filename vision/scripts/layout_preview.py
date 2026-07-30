"""Group full-table detections and render perspective-aware review overlays."""

import argparse
import json
from collections.abc import Iterable
from pathlib import Path

import cv2
import numpy as np

from dorahub_vision.layout import (
    Cluster,
    LayoutParams,
    TableFrame,
    TileBox,
    cluster_layout,
)
from vision.scripts.table_plane import appearance_face_score, estimate_table_corners

COLORS = {
    "hand": (118, 230, 0),
    "opponent_hand": (3, 255, 118),
    "wall": (255, 176, 0),
    "dead_wall": (249, 0, 213),
    "dora": (255, 0, 255),
    "discard": (0, 171, 255),
}


def render_predictions(
    predictions_path: Path,
    face_predictions_path: Path,
    image_root: Path,
    output: Path,
) -> list[Path]:
    predictions = json.loads(predictions_path.read_text())
    face_predictions = {
        Path(item["image"]).name: item["detections"]
        for item in json.loads(face_predictions_path.read_text())
    }
    output.mkdir(parents=True, exist_ok=True)
    rendered = []
    summary = []
    for item in predictions:
        image_path = image_root / item["image"]
        image = cv2.imread(str(image_path))
        if image is None:
            raise ValueError(f"cannot decode {image_path}")
        corners = estimate_table_corners(image)
        params = LayoutParams(
            player_direction=(0, 1),
            table_corners=corners,
        )
        faces = face_predictions.get(image_path.name, ())
        boxes = [
            TileBox(
                *detection["box"],
                face_score=max(
                    (
                        face["confidence"]
                        for face in faces
                        if _iou(detection["box"], face["box"]) >= 0.2
                    ),
                    default=0.0,
                ),
            )
            for detection in item["detections"]
        ]
        layout = cluster_layout(boxes, params)
        fallback = {
            tile
            for wall in layout.walls
            if (
                wall.tile_count <= 5
                or min(
                    wall.centroid[0],
                    wall.centroid[1],
                    1 - wall.centroid[0],
                )
                <= 0.15
            )
            and not any((tile.face_score or 0) >= 0.1 for tile in wall.tiles)
            for tile in wall.tiles
        }
        if fallback:
            boxes = [
                TileBox(
                    tile.cx,
                    tile.cy,
                    tile.width,
                    tile.height,
                    tile.class_id,
                    max(
                        tile.face_score or 0,
                        appearance_face_score(image, tile),
                    ),
                )
                if tile in fallback
                else tile
                for tile in boxes
            ]
            layout = cluster_layout(boxes, params)

        frame = TableFrame(corners)
        preview = _draw(image, frame, layout.clusters)
        target = output / f"{image_path.stem}.jpg"
        if not cv2.imwrite(str(target), preview):
            raise ValueError(f"cannot write {target}")
        rendered.append(target)
        summary.append(
            {
                "image": image_path.name,
                "tableCorners": corners,
                "groups": [
                    {
                        "role": cluster.role,
                        "seat": cluster.seat,
                        "tiles": cluster.tile_count,
                        "centroid": [
                            round(value, 3) for value in cluster.centroid
                        ],
                    }
                    for cluster in layout.clusters
                ],
            }
        )
    (output / "groups.json").write_text(json.dumps(summary, indent=2))
    return rendered


def _draw(
    image: np.ndarray,
    frame: TableFrame,
    clusters: Iterable[Cluster],
) -> np.ndarray:
    height, width = image.shape[:2]
    filled = image.copy()
    polygons = []
    for cluster in clusters:
        color = COLORS.get(cluster.role)
        if color is None:
            continue
        left, top, right, bottom = cluster.bounds
        pad = cluster.scale * 0.2
        polygon = np.array(
            [
                (
                    round(x * width),
                    round(y * height),
                )
                for x, y in (
                    frame.unmap(left - pad, top - pad),
                    frame.unmap(right + pad, top - pad),
                    frame.unmap(right + pad, bottom + pad),
                    frame.unmap(left - pad, bottom + pad),
                )
            ],
            dtype=np.int32,
        )
        cv2.fillPoly(filled, [polygon], color)
        polygons.append((cluster, polygon, color))
    preview = cv2.addWeighted(filled, 0.15, image, 0.85, 0)
    table = np.array(
        [
            (
                round(x * width),
                round(y * height),
            )
            for x, y in (
                frame.unmap(0, 0),
                frame.unmap(1, 0),
                frame.unmap(1, 1),
                frame.unmap(0, 1),
            )
        ],
        dtype=np.int32,
    )
    cv2.polylines(preview, [table], True, (255, 255, 0), 4)
    for cluster, polygon, color in polygons:
        cv2.polylines(preview, [polygon], True, color, 4)
        seat = f"/{cluster.seat}" if cluster.seat else ""
        cv2.putText(
            preview,
            f"{cluster.role}{seat}: {cluster.tile_count}",
            tuple(polygon[np.argmin(polygon[:, 1])]),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            color,
            2,
            cv2.LINE_AA,
        )
    return preview


def _iou(first: list[float], second: list[float]) -> float:
    ax1, ay1 = first[0] - first[2] / 2, first[1] - first[3] / 2
    ax2, ay2 = first[0] + first[2] / 2, first[1] + first[3] / 2
    bx1, by1 = second[0] - second[2] / 2, second[1] - second[3] / 2
    bx2, by2 = second[0] + second[2] / 2, second[1] + second[3] / 2
    intersection = max(0, min(ax2, bx2) - max(ax1, bx1)) * max(
        0, min(ay2, by2) - max(ay1, by1)
    )
    return intersection / (
        first[2] * first[3] + second[2] * second[3] - intersection
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("predictions", type=Path)
    parser.add_argument("face_predictions", type=Path)
    parser.add_argument("image_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    for path in render_predictions(
        args.predictions,
        args.face_predictions,
        args.image_root,
        args.output,
    ):
        print(path)


if __name__ == "__main__":
    main()
