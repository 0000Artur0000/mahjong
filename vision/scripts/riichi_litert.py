"""Run the audited Riichi YOLO LiteRT artifact."""

from __future__ import annotations

import argparse
import json
from math import isfinite
from pathlib import Path

from dorahub_vision.layout import TileBox, cluster_layout

SOURCE_CLASSES = (
    "1m", "1p", "1s", "E",
    "2m", "2p", "2s", "S",
    "3m", "3p", "3s", "W",
    "4m", "4p", "4s", "N",
    "5m", "5p", "5s", "C",
    "6m", "6p", "6s", "F",
    "7m", "7p", "7s", "P",
    "8m", "8p", "8s",
    "9m", "9p", "9s",
    "unknown", "0m", "0p", "0s",
)
NEAREST_HAND_REGION = (0.0, 0.52, 1.0, 0.88)
NEAREST_HAND_TILES = (
    (0.0, 0.40, 0.6, 0.92),
    (0.2, 0.40, 0.8, 0.92),
    (0.4, 0.40, 1.0, 0.92),
)
FULL_FIELD_REGIONS = tuple(
    (left, top, left + 0.5, top + 0.35)
    for top in (0.0, 0.22, 0.43, 0.65)
    for left in (0.0, 0.25, 0.5)
)
MIN_HAND_TILES = 10
MAX_HAND_TILES = 18


def predict(
    model_path: Path,
    image_path: Path,
    *,
    confidence: float = 0.2,
    iou_threshold: float = 0.45,
    region: tuple[float, float, float, float] | None = None,
) -> list[dict[str, object]]:
    if (
        not isfinite(confidence)
        or not 0 <= confidence <= 1
        or not isfinite(iou_threshold)
        or not 0 <= iou_threshold <= 1
    ):
        raise ValueError("confidence and IoU threshold must be finite values in [0, 1]")
    if region is not None and (
        not isinstance(region, tuple)
        or len(region) != 4
        or not all(isfinite(value) and 0 <= value <= 1 for value in region)
        or region[0] >= region[2]
        or region[1] >= region[3]
    ):
        raise ValueError("region must be a normalized left, top, right, bottom box")

    import numpy as np
    from ai_edge_litert.interpreter import Interpreter
    from PIL import Image

    original = Image.open(image_path).convert("RGB")
    crop_left = crop_top = 0
    if region is not None:
        crop_left, crop_top, crop_right, crop_bottom = (
            round(value * size)
            for value, size in zip(
                region,
                (original.width, original.height, original.width, original.height),
                strict=True,
            )
        )
        if crop_left >= crop_right or crop_top >= crop_bottom:
            raise ValueError("region is empty at this image resolution")
        image = original.crop((crop_left, crop_top, crop_right, crop_bottom))
    else:
        image = original
    scale = min(640 / image.width, 640 / image.height)
    resized = image.resize(
        (round(image.width * scale), round(image.height * scale)),
        Image.Resampling.LANCZOS,
    )
    pad_x, pad_y = (640 - resized.width) // 2, (640 - resized.height) // 2
    canvas = Image.new("RGB", (640, 640))
    canvas.paste(resized, (pad_x, pad_y))

    interpreter = Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if (
        len(inputs) != 1
        or inputs[0]["shape"].tolist() != [1, 640, 640, 3]
        or len(outputs) != 1
        or outputs[0]["shape"].tolist() != [1, 42, 8400]
    ):
        raise ValueError("unsupported Riichi LiteRT model signature")
    tensor = np.asarray(canvas, dtype=np.float32)[None] / 255
    interpreter.set_tensor(inputs[0]["index"], tensor)
    interpreter.invoke()
    output = interpreter.get_tensor(outputs[0]["index"])[0]

    candidates = []
    scores = output[4:]
    for anchor in np.flatnonzero(scores.max(axis=0) >= confidence):
        ranking = np.argsort(scores[:, anchor])[::-1]
        class_id = int(ranking[0])
        cx, cy, width, height = (float(value) for value in output[:4, anchor])
        left = (640 * (cx - width / 2) - pad_x) / scale / image.width
        top = (640 * (cy - height / 2) - pad_y) / scale / image.height
        right = (640 * (cx + width / 2) - pad_x) / scale / image.width
        bottom = (640 * (cy + height / 2) - pad_y) / scale / image.height
        left, top, right, bottom = (
            max(0.0, left),
            max(0.0, top),
            min(1.0, right),
            min(1.0, bottom),
        )
        if right <= left or bottom <= top:
            continue
        left = (crop_left + left * image.width) / original.width
        right = (crop_left + right * image.width) / original.width
        top = (crop_top + top * image.height) / original.height
        bottom = (crop_top + bottom * image.height) / original.height
        candidates.append(
            {
                "tile": SOURCE_CLASSES[class_id],
                "confidence": float(scores[class_id, anchor]),
                "box": [
                    (left + right) / 2,
                    (top + bottom) / 2,
                    right - left,
                    bottom - top,
                ],
                "alternatives": [
                    {
                        "tile": SOURCE_CLASSES[int(other)],
                        "confidence": float(scores[other, anchor]),
                    }
                    for other in ranking[1:4]
                ],
            }
        )
    return _nms(candidates, iou_threshold)


def predict_nearest_hand(
    model_path: Path,
    image_path: Path,
    *,
    confidence: float = 0.2,
    iou_threshold: float = 0.45,
) -> list[dict[str, object]]:
    guided = predict(
        model_path,
        image_path,
        confidence=confidence,
        iou_threshold=iou_threshold,
        region=NEAREST_HAND_REGION,
    )
    tiled = _nms(
        [
            detection
            for region in NEAREST_HAND_TILES
            for detection in predict(
                model_path,
                image_path,
                confidence=confidence,
                iou_threshold=iou_threshold,
                region=region,
            )
        ],
        iou_threshold,
    )
    return _choose_hand(guided, tiled)


def predict_all_tiles(
    model_path: Path,
    image_path: Path,
    *,
    confidence: float = 0.2,
    iou_threshold: float = 0.3,
) -> list[dict[str, object]]:
    from PIL import Image

    # ponytail: twelve fresh calls favor recall; reuse one interpreter when
    # latency matters.
    detections = _nms(
        [
            detection
            for region in FULL_FIELD_REGIONS
            for detection in predict(
                model_path,
                image_path,
                confidence=confidence,
                iou_threshold=iou_threshold,
                region=region,
            )
        ],
        iou_threshold,
    )
    with Image.open(image_path) as image:
        return _generic_tiles(detections, image.size)


def predict_faces(
    model_path: Path,
    image_path: Path,
    *,
    confidence: float = 0.1,
    iou_threshold: float = 0.3,
) -> list[dict[str, object]]:
    """Номиналы по всему кадру: те же 12 регионов, но классы сохраняются.

    Отличается от `--all-tiles` только тем, что не схлопывает результат в единый
    label `tile`. Именно этот режим даёт `face-predictions.json` для группировки:
    один проход по всему кадру находит единицы лиц, тайлинг — десятки.
    """

    return _nms(
        [
            detection
            for region in FULL_FIELD_REGIONS
            for detection in predict(
                model_path,
                image_path,
                confidence=confidence,
                iou_threshold=iou_threshold,
                region=region,
            )
        ],
        iou_threshold,
    )


def _generic_tiles(
    detections: list[dict[str, object]],
    image_size: tuple[int, int],
) -> list[dict[str, object]]:
    image_width, image_height = image_size
    tiles = []
    for detection in detections:
        _, _, width, height = detection["box"]
        pixel_width, pixel_height = width * image_width, height * image_height
        aspect = pixel_width / pixel_height
        if min(pixel_width, pixel_height) < 8 or not 0.4 <= aspect <= 1.8:
            continue
        tiles.append(
            {
                "tile": "tile",
                "confidence": detection["confidence"],
                "box": detection["box"],
            }
        )
    return tiles


def _choose_hand(
    guided: list[dict[str, object]],
    tiled: list[dict[str, object]],
) -> list[dict[str, object]]:
    candidates = []
    for detections in (tiled, guided):
        if not detections:
            continue
        boxes = [TileBox(*detection["box"]) for detection in detections]
        hand = cluster_layout(boxes).hand
        selected = [
            detection
            for detection, box in zip(detections, boxes, strict=True)
            if hand is not None and box in hand.tiles
        ]
        if MIN_HAND_TILES <= len(selected) <= MAX_HAND_TILES:
            candidates.append(selected)
    return max(candidates, key=len, default=[])


def _nms(
    detections: list[dict[str, object]], threshold: float
) -> list[dict[str, object]]:
    selected = []
    for candidate in sorted(
        detections, key=lambda item: item["confidence"], reverse=True
    ):
        if all(_iou(candidate["box"], item["box"]) <= threshold for item in selected):
            selected.append(candidate)
    return selected


def _iou(first: list[float], second: list[float]) -> float:
    ax1, ay1 = first[0] - first[2] / 2, first[1] - first[3] / 2
    ax2, ay2 = first[0] + first[2] / 2, first[1] + first[3] / 2
    bx1, by1 = second[0] - second[2] / 2, second[1] - second[3] / 2
    bx2, by2 = second[0] + second[2] / 2, second[1] + second[3] / 2
    intersection = max(0.0, min(ax2, bx2) - max(ax1, bx1)) * max(
        0.0, min(ay2, by2) - max(ay1, by1)
    )
    return intersection / (first[2] * first[3] + second[2] * second[3] - intersection)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("images", type=Path, nargs="+")
    parser.add_argument("--confidence", type=float, default=0.2)
    parser.add_argument("--iou", type=float, default=0.3)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--nearest-hand", action="store_true")
    mode.add_argument("--all-tiles", action="store_true")
    mode.add_argument("--faces", action="store_true")
    args = parser.parse_args()
    for image in args.images:
        if args.faces:
            detections = predict_faces(
                args.model,
                image,
                confidence=args.confidence,
                iou_threshold=args.iou,
            )
        elif args.all_tiles:
            detections = predict_all_tiles(
                args.model,
                image,
                confidence=args.confidence,
            )
        elif args.nearest_hand:
            detections = predict_nearest_hand(
                args.model,
                image,
                confidence=args.confidence,
            )
        else:
            detections = predict(args.model, image, confidence=args.confidence)
        detections.sort(key=lambda item: item["box"][0])
        print(json.dumps({"image": str(image), "detections": detections}))


if __name__ == "__main__":
    main()
