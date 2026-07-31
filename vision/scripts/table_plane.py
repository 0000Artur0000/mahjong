"""Estimate the visible table plane for perspective-aware grouping."""

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from dorahub_vision.layout import TableFrame, TileBox


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
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    if count <= 1:
        raise ValueError("table surface was not found")
    table = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    points = cv2.findNonZero((labels == table).astype(np.uint8))
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


def rectify_table(
    image: np.ndarray,
    corners: tuple[
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
    ],
    size: int = 1024,
) -> np.ndarray:
    """Warp a confident table quadrilateral to a square top-down image."""

    if size < 32:
        raise ValueError("rectified image is too small")
    height, width = image.shape[:2]
    source = np.float32(
        [(x * (width - 1), y * (height - 1)) for x, y in corners]
    )
    target = np.float32(
        ((0, 0), (size - 1, 0), (size - 1, size - 1), (0, size - 1))
    )
    return cv2.warpPerspective(
        image,
        cv2.getPerspectiveTransform(source, target),
        (size, size),
    )


def unrectify_box(
    box: list[float],
    corners: tuple[
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
    ],
) -> list[float]:
    """Map a normalized top-down box back to the original photo."""

    cx, cy, width, height = box
    frame = TableFrame(corners)
    points = [
        frame.unmap(x, y)
        for x, y in (
            (cx - width / 2, cy - height / 2),
            (cx + width / 2, cy - height / 2),
            (cx + width / 2, cy + height / 2),
            (cx - width / 2, cy + height / 2),
        )
    ]
    left = max(0.0, min(x for x, _ in points))
    top = max(0.0, min(y for _, y in points))
    right = min(1.0, max(x for x, _ in points))
    bottom = min(1.0, max(y for _, y in points))
    return [
        (left + right) / 2,
        (top + bottom) / 2,
        right - left,
        bottom - top,
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("images", type=Path, nargs="+")
    parser.add_argument("--rectify-output", type=Path)
    parser.add_argument("--size", type=int, default=1024)
    args = parser.parse_args()
    if args.rectify_output:
        args.rectify_output.mkdir(parents=True, exist_ok=True)
    for path in args.images:
        image = cv2.imread(str(path))
        if image is None:
            raise ValueError(f"cannot decode {path}")
        corners = estimate_table_corners(image)
        print(
            json.dumps(
                {
                    "image": str(path),
                    "tableCorners": corners,
                }
            )
        )
        if args.rectify_output:
            target = args.rectify_output / f"{path.stem}-topdown.jpg"
            if not cv2.imwrite(
                str(target), rectify_table(image, corners, args.size)
            ):
                raise ValueError(f"cannot write {target}")


if __name__ == "__main__":
    main()


def propose_back_tiles(
    image,
    corners,
    detections,
    *,
    colour_distance: float = 14.0,
    min_tiles: float = 0.6,
    max_tiles: float = 30.0,
):
    """Найти тайлы, которых не увидел детектор: рубашки чужого цвета.

    Признак общий, без привязки к синему: однородная область **внутри плоскости
    стола**, чей цвет в LAB отстоит от цвета самого стола дальше порога и которую
    не закрыл ни один детектированный бокс. Область режется на тайлы шагом,
    взятым из медианного размера уже найденных, — стена это ряд одинаковых тайлов.

    Возвращает список `Proposal`; пустой список, если предлагать нечего.
    """

    import numpy as np

    from dorahub_vision.backs import Proposal, covered, split_run, tile_size

    if not detections:
        return []
    height, width = image.shape[:2]
    tile_width, tile_height = tile_size(detections)
    unit = max(1.0, tile_width * width * tile_height * height)

    # Только каналы a/b: тени, блики, бумага и тёмный телефон отличаются от стола
    # яркостью, а рубашка тайла — цветом. По полному LAB они неразличимы.
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB).astype(np.float32)[:, :, 1:]
    table_mask = np.zeros((height, width), np.uint8)
    polygon = np.array(
        [(round(x * (width - 1)), round(y * (height - 1))) for x, y in corners],
        dtype=np.int32,
    )
    cv2.fillPoly(table_mask, [polygon], 255)

    sample = lab[table_mask > 0]
    if sample.size == 0:
        return []
    table_colour = np.median(sample, axis=0)
    distance = np.linalg.norm(lab - table_colour, axis=2)

    occupied = np.zeros((height, width), np.uint8)
    for cx, cy, box_width, box_height in detections:
        cv2.rectangle(
            occupied,
            (round((cx - box_width / 2) * width), round((cy - box_height / 2) * height)),
            (round((cx + box_width / 2) * width), round((cy + box_height / 2) * height)),
            255,
            -1,
        )

    mask = ((distance > colour_distance) & (table_mask > 0) & (occupied == 0)).astype(
        np.uint8
    ) * 255
    kernel = max(3, round(tile_height * height * 0.3)) | 1
    mask = cv2.morphologyEx(
        mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel, kernel))
    )
    mask = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel, kernel)),
    )

    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    proposals: list[Proposal] = []
    for index in range(1, count):
        area = stats[index, cv2.CC_STAT_AREA]
        tiles = area / unit
        if not min_tiles <= tiles <= max_tiles:
            continue
        points = cv2.findNonZero((labels == index).astype(np.uint8))
        (rect_cx, rect_cy), (rect_w, rect_h), angle = cv2.minAreaRect(points)
        # Заполненность считаем по повёрнутому прямоугольнику: ряд тайлов лежит
        # под углом, и осевая рамка у него заполнена лишь наполовину.
        if rect_w * rect_h <= 0 or area < 0.6 * rect_w * rect_h:
            continue
        length, thickness = max(rect_w, rect_h), min(rect_w, rect_h)
        radians = np.deg2rad(angle if rect_w >= rect_h else angle + 90)
        step = tile_width * width if rect_w >= rect_h else tile_height * height
        for proposal in split_run(
            rect_cx / width,
            rect_cy / height,
            length / width if abs(np.cos(radians)) > abs(np.sin(radians)) else length / height,
            thickness / height,
            float(radians),
            (step / width) if abs(np.cos(radians)) > abs(np.sin(radians)) else (step / height),
        ):
            if not covered(
                (proposal.cx, proposal.cy, proposal.width, proposal.height),
                detections,
                slack=0.6 * max(tile_width, tile_height),
            ):
                proposals.append(proposal)
    return proposals
