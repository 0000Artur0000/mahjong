#!/usr/bin/env python3
"""Split CountGD boxes that cover a whole row into one box per tile."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def _axes(rect):
    (cx, cy), (width, height), angle = rect
    radians = np.deg2rad(angle)
    width_axis = np.array([np.cos(radians), np.sin(radians)])
    height_axis = np.array([-np.sin(radians), np.cos(radians)])
    if width >= height:
        return np.array([cx, cy]), width_axis, height_axis, width, height
    return np.array([cx, cy]), height_axis, width_axis, height, width


def _pitch(image, center, long_axis, short_axis, length, thickness):
    source = np.float32(
        [
            center - long_axis * length / 2 - short_axis * thickness / 2,
            center + long_axis * length / 2 - short_axis * thickness / 2,
            center + long_axis * length / 2 + short_axis * thickness / 2,
            center - long_axis * length / 2 + short_axis * thickness / 2,
        ]
    )
    width, height = round(length), round(thickness)
    target = np.float32(
        [[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]]
    )
    rectified = cv2.warpPerspective(
        image, cv2.getPerspectiveTransform(source, target), (width, height)
    )
    gray = cv2.cvtColor(rectified, cv2.COLOR_BGR2GRAY)
    edges = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    band = max(3, round(height * 0.27))
    projection = edges[:band].mean(0) + edges[-band:].mean(0)
    projection = cv2.GaussianBlur(projection.reshape(1, -1), (0, 0), 2).ravel()
    projection = (projection - np.median(projection)) / (np.std(projection) + 1e-6)

    best = (-np.inf, 0)
    for pitch in np.arange(thickness * 0.35, thickness * 0.65, 0.5):
        for phase in np.arange(0, pitch, 2):
            points = np.arange(phase, width, pitch)
            points = points[(points > 8) & (points < width - 8)]
            if len(points) < 2:
                continue
            score = np.mean(
                [
                    projection[max(0, round(x) - 3) : min(width, round(x) + 4)].max()
                    for x in points
                ]
            )
            if score > best[0]:
                best = (score, pitch)
    return best


def split_merged_rows(image, boxes, scores, min_confirmation=0.4):
    """Use repeated seams to replace merged row detections with tile polygons."""

    boxes = np.asarray(boxes, dtype=float).reshape(-1, 4)
    scores = np.asarray(scores, dtype=float)
    polygons = np.asarray(
        [[[x1, y1], [x2, y1], [x2, y2], [x1, y2]] for x1, y1, x2, y2 in boxes],
        dtype=float,
    ).reshape(-1, 4, 2)
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    white = cv2.inRange(hsv, (0, 0, 130), (180, 105, 255))
    white = cv2.morphologyEx(white, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    contours, _ = cv2.findContours(white, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    for contour in sorted(contours, key=cv2.contourArea, reverse=True):
        area = cv2.contourArea(contour)
        rect = cv2.minAreaRect(contour)
        center, long_axis, short_axis, length, thickness = _axes(rect)
        fill = area / max(length * thickness, 1)
        if (
            area < image.shape[0] * image.shape[1] * 0.005
            or length / max(thickness, 1) < 3.5
            or thickness < 40
            or fill < 0.55
        ):
            continue

        outline = cv2.boxPoints(rect)
        inside = np.array([_overlaps_row(outline, box) for box in boxes])
        confirmed = inside & (scores >= min_confirmation)
        if confirmed.sum() < 2:
            continue

        periodicity, pitch = _pitch(
            image, center, long_axis, short_axis, length, thickness
        )
        count = round(length / pitch)
        if periodicity < 0.5 or not 3 <= count <= 30 or inside.sum() >= count * 0.75:
            continue

        confidence = float(scores[confirmed].max())
        tile_thickness = min(thickness, pitch * 1.8)
        split_boxes, split_polygons = [], []
        for index in range(count):
            start = -length / 2 + length * index / count
            end = -length / 2 + length * (index + 1) / count
            corners = np.array(
                [
                    center + long_axis * start - short_axis * tile_thickness / 2,
                    center + long_axis * end - short_axis * tile_thickness / 2,
                    center + long_axis * end + short_axis * tile_thickness / 2,
                    center + long_axis * start + short_axis * tile_thickness / 2,
                ]
            )
            split_boxes.append([*corners.min(0), *corners.max(0)])
            split_polygons.append(corners)
        boxes = np.concatenate([boxes[~inside], np.asarray(split_boxes)])
        scores = np.concatenate([scores[~inside], np.full(count, confidence)])
        polygons = np.concatenate([polygons[~inside], np.asarray(split_polygons)])

    return boxes, scores, polygons


def _overlaps_row(outline, box):
    tile = np.float32(
        [
            [box[0], box[1]],
            [box[2], box[1]],
            [box[2], box[3]],
            [box[0], box[3]],
        ]
    )
    intersection, _ = cv2.intersectConvexConvex(np.float32(outline), tile)
    area = max(1, (box[2] - box[0]) * (box[3] - box[1]))
    return intersection >= area * 0.25


def process(predictions: dict, image_root: Path) -> dict:
    output = {}
    for name, result in predictions.items():
        image = cv2.imread(str(image_root / name))
        if image is None:
            raise FileNotFoundError(image_root / name)
        boxes, scores, polygons = split_merged_rows(
            image, result["boxes"], result["scores"]
        )
        output[name] = {
            **result,
            "count": len(boxes),
            "boxes": boxes.tolist(),
            "scores": scores.tolist(),
            "polygons": polygons.tolist(),
        }
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("predictions", type=Path)
    parser.add_argument("images", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = process(json.loads(args.predictions.read_text()), args.images)
    args.output.write_text(json.dumps(result))
    print(f"{len(result)} images, {sum(x['count'] for x in result.values())} tiles")


if __name__ == "__main__":
    main()
