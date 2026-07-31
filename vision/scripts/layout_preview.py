"""Group full-table detections and render perspective-aware review overlays."""

import argparse
import json
from collections.abc import Iterable
from math import atan2, degrees, hypot
from pathlib import Path

import cv2
import numpy as np

from dorahub_vision.quad import quad_from_points
from dorahub_vision.layout import (
    Cluster,
    LayoutParams,
    Meld,
    TableFrame,
    TileBox,
    cluster_layout,
)
from dorahub_vision.riichi import MODEL_TILES
from vision.scripts.table_plane import (
    appearance_face_score,
    propose_back_tiles,
)

COLORS = {
    "hand": (118, 230, 0),
    "opponent_hand": (3, 255, 118),
    "wall": (255, 176, 0),
    "dead_wall": (249, 0, 213),
    "dora": (255, 0, 255),
    "discard": (0, 171, 255),
}
MELD_COLORS = {
    "chi": (0, 255, 255),
    "pon": (255, 0, 160),
    "kan": (0, 0, 255),
    "kan_candidate": (0, 128, 255),
    "chi_or_pon": (255, 255, 255),
}


def render_predictions(
    predictions_path: Path,
    face_predictions_path: Path,
    image_root: Path,
    output: Path,
) -> list[Path]:
    predictions = json.loads(predictions_path.read_text())
    countgd = isinstance(predictions, dict)
    items = (
        (
            {"image": image, "countgd": result}
            for image, result in predictions.items()
        )
        if countgd
        else predictions
    )
    face_predictions = (
        {}
        if str(face_predictions_path) == "-"
        else {
            Path(item["image"]).name: item["detections"]
            for item in json.loads(face_predictions_path.read_text())
        }
    )
    output.mkdir(parents=True, exist_ok=True)
    rendered = []
    summary = []
    for item in items:
        image_path = image_root / item["image"]
        image = cv2.imread(str(image_path))
        if image is None:
            raise ValueError(f"cannot decode {image_path}")
        detections = (
            _countgd_detections(item["countgd"], image.shape)
            if countgd
            else [dict(detection) for detection in item["detections"]]
        )
        raw_boxes = [tuple(detection["box"]) for detection in detections]
        points = [(box[0], box[1]) for box in raw_boxes]
        # Круглый или обрезанный стол не имеет четырёх видимых углов. Сцена по
        # самим тайлам не зависит от центра кадра и не выдумывает перспективу.
        corners = (
            quad_from_points(points)
            if points
            else ((0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0))
        )
        plane = "tiles"
        params = LayoutParams(
            player_direction=(0, 1),
            table_corners=corners,
        )
        # Рубашки чужого цвета детектор не видит вовсе: без них не найти ни стену,
        # ни мёртвую стену, ни дору. Дополняем предложениями по цвету.
        for proposal in (
            () if countgd else propose_back_tiles(image, corners, raw_boxes)
        ):
            # Нарезка по повёрнутому прямоугольнику может выйти за край кадра —
            # TileBox принимает только нормированные координаты.
            # Бокс обязан целиком лежать в кадре, а не только центром: обрезаем
            # рамку по краю, сохраняя центр.
            cx = min(1.0, max(0.0, proposal.cx))
            cy = min(1.0, max(0.0, proposal.cy))
            box_width = max(1e-4, min(proposal.width, 2 * cx, 2 * (1 - cx)))
            box_height = max(1e-4, min(proposal.height, 2 * cy, 2 * (1 - cy)))
            if box_width <= 1e-4 or box_height <= 1e-4:
                continue
            detections.append(
                {
                    "tile": "tile",
                    "confidence": 0.0,
                    "proposed": True,
                    "box": [cx, cy, box_width, box_height],
                }
            )

        faces = face_predictions.get(image_path.name, ())
        boxes = []
        for detection in detections:
            matched = max(
                (
                    face
                    for face in faces
                    if _iou(detection["box"], face["box"]) >= 0.2
                ),
                key=lambda face: face["confidence"],
                default=None,
            )
            box = TileBox(
                *detection["box"],
                class_id=(
                    MODEL_TILES.index(matched["tile"])
                    if matched and matched["tile"] in MODEL_TILES
                    else None
                ),
                face_score=matched["confidence"] if matched else 0.0,
                angle=detection.get("angle"),
            )
            if countgd and not faces:
                box = TileBox(
                    box.cx,
                    box.cy,
                    box.width,
                    box.height,
                    box.class_id,
                    appearance_face_score(image, box),
                    box.angle,
                )
            boxes.append(box)
        layout = cluster_layout(boxes, params)
        fallback = set() if faces else {
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
                    tile.angle,
                )
                if tile in fallback
                else tile
                for tile in boxes
            ]
            layout = cluster_layout(boxes, params)

        frame = TableFrame(corners)
        preview = _draw(image, frame, layout.clusters, layout.melds)
        target = output / f"{image_path.stem}.jpg"
        if not cv2.imwrite(str(target), preview):
            raise ValueError(f"cannot write {target}")
        rendered.append(target)
        summary.append(
            {
                "image": image_path.name,
                "tableCorners": corners,
                "plane": plane,
                "melds": [
                    {
                        "kind": meld.kind,
                        "seat": meld.seat,
                        "tiles": len(meld.tiles),
                        "calledIndex": meld.called_index,
                    }
                    for meld in layout.melds
                ],
                "groups": [
                    {
                        "role": cluster.role,
                        "seat": cluster.seat,
                        "tiles": cluster.tile_count,
                        "centroid": [
                            round(value, 3) for value in cluster.centroid
                        ],
                        # Боксы нужны, чтобы сравнивать результат с эталонной
                        # разметкой ролей потайлово: по счётчику этого не сделать.
                        "tileBoxes": [
                            [
                                round(tile.cx, 4),
                                round(tile.cy, 4),
                                round(tile.width, 4),
                                round(tile.height, 4),
                            ]
                            for tile in cluster.tiles
                        ],
                    }
                    for cluster in layout.clusters
                ],
            }
        )
        del image, preview
    (output / "groups.json").write_text(json.dumps(summary, indent=2))
    return rendered


def _draw(
    image: np.ndarray,
    frame: TableFrame,
    clusters: Iterable[Cluster],
    melds: Iterable[Meld] = (),
) -> np.ndarray:
    clusters = tuple(clusters)
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
    del filled
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
    tile_shapes = []
    tile_fill = preview.copy()
    for cluster in clusters:
        color = COLORS.get(cluster.role, (160, 160, 160))
        shade = tuple(round(channel * 0.45) for channel in color)
        for tile in cluster.tiles:
            top, bottom, sides = _tile_prism(tile, image.shape)
            cv2.fillPoly(tile_fill, [bottom, *sides], shade)
            cv2.fillPoly(tile_fill, [top], color)
            tile_shapes.append((top, bottom, color, shade))
    preview = cv2.addWeighted(tile_fill, 0.25, preview, 0.75, 0)
    del tile_fill
    for top, bottom, color, shade in tile_shapes:
        cv2.polylines(preview, [bottom], True, shade, 1, cv2.LINE_AA)
        cv2.polylines(preview, [top], True, color, 2, cv2.LINE_AA)
        for start, end in zip(top, bottom, strict=True):
            cv2.line(preview, tuple(start), tuple(end), shade, 1, cv2.LINE_AA)
    for meld in melds:
        left, top, right, bottom = meld.bounds
        polygon = np.array(
            [
                (round(x * width), round(y * height))
                for x, y in (
                    frame.unmap(left, top),
                    frame.unmap(right, top),
                    frame.unmap(right, bottom),
                    frame.unmap(left, bottom),
                )
            ],
            dtype=np.int32,
        )
        color = MELD_COLORS[meld.kind]
        cv2.polylines(preview, [polygon], True, color, 6)
        cv2.putText(
            preview,
            f"{meld.kind}/{meld.seat}",
            tuple(polygon[np.argmin(polygon[:, 1])]),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            color,
            2,
            cv2.LINE_AA,
        )
    return preview


def _tile_prism(
    tile: TileBox,
    image_shape: tuple[int, ...],
) -> tuple[np.ndarray, np.ndarray, list[np.ndarray]]:
    """Return a small pseudo-3D prism around one detected tile."""

    height, width = image_shape[:2]
    box_width, box_height = tile.width * width, tile.height * height
    major, minor = max(box_width, box_height), min(box_width, box_height)
    angle = degrees(tile.angle) if tile.angle is not None else (
        90 if box_height > box_width else 0
    )
    top = np.rint(
        cv2.boxPoints(
            ((tile.cx * width, tile.cy * height), (major, minor), angle)
        )
    ).astype(np.int32)
    depth = max(2, round(minor * 0.08))
    bottom = top + (depth, depth)
    sides = [
        np.array(
            [top[index], top[(index + 1) % 4], bottom[(index + 1) % 4], bottom[index]],
            dtype=np.int32,
        )
        for index in range(4)
    ]
    return top, bottom, sides


def _countgd_detections(
    result: dict[str, object],
    image_shape: tuple[int, ...],
) -> list[dict[str, object]]:
    height, width = image_shape[:2]
    polygons = result.get("polygons", ())
    detections = []
    for index, raw_box in enumerate(result["boxes"]):
        left, top, right, bottom = raw_box
        left, right = sorted(
            (
                min(float(width), max(0.0, left)),
                min(float(width), max(0.0, right)),
            )
        )
        top, bottom = sorted(
            (
                min(float(height), max(0.0, top)),
                min(float(height), max(0.0, bottom)),
            )
        )
        if right <= left or bottom <= top:
            continue
        angle = None
        if index < len(polygons):
            points = polygons[index]
            edges = [
                (
                    second[0] - first[0],
                    second[1] - first[1],
                )
                for first, second in zip(
                    points, (*points[1:], points[0]), strict=True
                )
            ]
            dx, dy = max(edges, key=lambda edge: hypot(*edge))
            angle = atan2(dy / height, dx / width)
        detections.append(
            {
                "tile": "tile",
                "confidence": result["scores"][index],
                "box": [
                    (left + right) / (2 * width),
                    (top + bottom) / (2 * height),
                    (right - left) / width,
                    (bottom - top) / height,
                ],
                "angle": angle,
            }
        )
    return detections


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
