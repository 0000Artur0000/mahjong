"""Group full-table detections and render perspective-aware review overlays."""

import argparse
import json
from collections.abc import Iterable
from math import atan2, degrees, hypot
from pathlib import Path
from statistics import median

import cv2
import numpy as np

from dorahub_vision.quad import quad_from_points
from dorahub_vision.layout import (
    Cluster,
    LayoutParams,
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
    "dead_wall": (255, 80, 0),
    "dora": (255, 0, 255),
    "discard": (0, 171, 255),
}


def _wall_gap_box(face, proposals):
    """Restore a face tile lying in a one-tile gap between two wall backs."""

    if len(proposals) < 2:
        return None
    cx, cy = face[:2]
    nearest = sorted(
        proposals,
        key=lambda box: hypot(box[0] - cx, box[1] - cy),
    )[:2]
    distances = [hypot(box[0] - cx, box[1] - cy) for box in nearest]
    scale = median(max(box[2], box[3]) for box in nearest)
    if not scale or max(distances) > 1.25 * scale or min(distances) < 0.3 * scale:
        return None
    first = nearest[0][0] - cx, nearest[0][1] - cy
    second = nearest[1][0] - cx, nearest[1][1] - cy
    if (first[0] * second[0] + first[1] * second[1]) / (
        distances[0] * distances[1]
    ) > -0.7:
        return None
    return [
        cx,
        cy,
        median(box[2] for box in nearest),
        median(box[3] for box in nearest),
    ]


def render_predictions(
    predictions_path: Path,
    face_predictions_path: Path,
    image_root: Path,
    output: Path,
    mask_predictions_path: Path | None = None,
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
    mask_predictions = (
        {}
        if mask_predictions_path is None
        else {
            Path(item["image"]).name: item["detections"]
            for item in json.loads(mask_predictions_path.read_text())
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
        faces = face_predictions.get(image_path.name, ())
        wall_proposals = []
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
            wall_proposals.append([cx, cy, box_width, box_height])
            detections.append(
                {
                    "tile": "tile",
                    "confidence": 0.0,
                    "proposed": True,
                    "box": [cx, cy, box_width, box_height],
                }
            )

        for face in sorted(
            faces, key=lambda candidate: candidate["confidence"], reverse=True
        ):
            if any(
                _iou(face["box"], detection["box"]) >= 0.2
                for detection in detections
            ):
                continue
            gap_box = _wall_gap_box(face["box"], wall_proposals)
            if gap_box is not None:
                detections.append(
                    {
                        "tile": face["tile"],
                        "confidence": face["confidence"],
                        "proposed": True,
                        "box": gap_box,
                    }
                )

        masks = mask_predictions.get(image_path.name, ())
        boxes = []
        tile_polygons = {}
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
            if len(detection.get("polygon", ())) >= 3:
                tile_polygons[_tile_key(box)] = np.asarray(
                    detection["polygon"], dtype=np.int32
                )
            matched_mask = max(
                (
                    mask
                    for mask in masks
                    if _iou(
                        detection["box"],
                        _normalized_xyxy(mask["box"], image.shape),
                    )
                    >= 0.5
                ),
                key=lambda mask: mask.get("score", mask.get("sam_score", 0)),
                default=None,
            )
            if matched_mask and len(matched_mask.get("polygon", ())) >= 3:
                segmented = _segmented_outline(
                    matched_mask["polygon"], detection["box"], image.shape
                )
                if segmented is not None:
                    tile_polygons[_tile_key(box)] = segmented
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
        preview = _draw(image, layout.clusters, tile_polygons)
        target = output / f"{image_path.stem}.jpg"
        if not cv2.imwrite(str(target), preview):
            raise ValueError(f"cannot write {target}")
        rendered.append(target)
        summary.append(
            {
                "image": image_path.name,
                "tableCorners": corners,
                "plane": plane,
                "sceneTiles": _scene_tiles(layout.clusters, frame),
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
    clusters: Iterable[Cluster],
    tile_polygons: dict[tuple[float, ...], np.ndarray],
) -> np.ndarray:
    clusters = tuple(clusters)
    tile_shapes = []
    tile_fill = image.copy()
    for cluster in clusters:
        color = COLORS.get(cluster.role, (160, 160, 160))
        for tile in cluster.tiles:
            polygon = _tile_outline(tile, image.shape, tile_polygons)
            cv2.fillPoly(tile_fill, [polygon], color)
            tile_shapes.append((polygon, color))
    preview = cv2.addWeighted(tile_fill, 0.25, image, 0.75, 0)
    del tile_fill
    for polygon, color in tile_shapes:
        cv2.polylines(preview, [polygon], True, color, 2, cv2.LINE_AA)
    return preview


def _scene_tiles(
    clusters: Iterable[Cluster], frame: TableFrame
) -> list[dict[str, object]]:
    scene = []
    for group, cluster in enumerate(clusters):
        points = [frame.map(tile.cx, tile.cy) for tile in cluster.tiles]
        across = [
            -x * cluster.axis[1] + y * cluster.axis[0] for x, y in points
        ]
        middle = median(across)
        for tile, (x, y), level in zip(
            cluster.tiles, points, across, strict=True
        ):
            z = (
                int(level > middle)
                if cluster.role in {"wall", "dead_wall"} and cluster.rows > 1
                else int(cluster.role == "dora")
            )
            scene.append(
                {
                    "position": [round(x, 4), round(y, 4), z],
                    "yaw": round(atan2(cluster.axis[1], cluster.axis[0]), 4),
                    "role": cluster.role,
                    "seat": cluster.seat,
                    "group": group,
                    "face": (
                        MODEL_TILES[tile.class_id]
                        if tile.class_id is not None
                        and tile.class_id < len(MODEL_TILES)
                        else None
                    ),
                    "box": [
                        round(tile.cx, 4),
                        round(tile.cy, 4),
                        round(tile.width, 4),
                        round(tile.height, 4),
                    ],
                }
            )
    return scene


def _tile_key(tile: TileBox) -> tuple[float, ...]:
    return tile.cx, tile.cy, tile.width, tile.height


def _tile_outline(
    tile: TileBox,
    image_shape: tuple[int, ...],
    polygons: dict[tuple[float, ...], np.ndarray],
) -> np.ndarray:
    """Use the segmented silhouette; never invent unavailable 3-D edges."""

    segmented = polygons.get(_tile_key(tile))
    if segmented is not None:
        return segmented

    height, width = image_shape[:2]
    box_width, box_height = tile.width * width, tile.height * height
    major, minor = max(box_width, box_height), min(box_width, box_height)
    angle = degrees(tile.angle) if tile.angle is not None else (
        90 if box_height > box_width else 0
    )
    return np.rint(
        cv2.boxPoints(
            ((tile.cx * width, tile.cy * height), (major, minor), angle)
        )
    ).astype(np.int32)


def _segmented_outline(
    polygon: list[list[float]],
    box: list[float],
    image_shape: tuple[int, ...],
) -> np.ndarray | None:
    """Keep a tile-sized convex SAM silhouette, not its printed glyph."""

    points = np.asarray(polygon, dtype=np.float32)
    hull = cv2.convexHull(points).reshape(-1, 2)
    height, width = image_shape[:2]
    box_width, box_height = box[2] * width, box[3] * height
    area = cv2.contourArea(hull)
    span = np.ptp(hull, axis=0)
    cx, cy = box[0] * width, box[1] * height
    prompt = np.float32(
        [
            [cx - box_width / 2, cy - box_height / 2],
            [cx + box_width / 2, cy - box_height / 2],
            [cx + box_width / 2, cy + box_height / 2],
            [cx - box_width / 2, cy + box_height / 2],
        ]
    )
    intersection, _ = cv2.intersectConvexConvex(hull, prompt)
    if (
        area < box_width * box_height * 0.35
        or area > box_width * box_height * 1.1
        or intersection < area * 0.7
        or span[0] < box_width * 0.55
        or span[1] < box_height * 0.55
    ):
        return None
    return np.rint(hull).astype(np.int32)


def _normalized_xyxy(
    box: list[float], image_shape: tuple[int, ...]
) -> list[float]:
    height, width = image_shape[:2]
    left, top, right, bottom = box
    return [
        (left + right) / (2 * width),
        (top + bottom) / (2 * height),
        (right - left) / width,
        (bottom - top) / height,
    ]


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
        detection = {
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
        if index < len(polygons) and len(polygons[index]) >= 3:
            detection["polygon"] = polygons[index]
        detections.append(detection)
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
    parser.add_argument("--masks", type=Path)
    args = parser.parse_args()
    for path in render_predictions(
        args.predictions,
        args.face_predictions,
        args.image_root,
        args.output,
        args.masks,
    ):
        print(path)


if __name__ == "__main__":
    main()
