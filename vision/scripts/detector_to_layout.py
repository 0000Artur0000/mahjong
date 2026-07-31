#!/usr/bin/env python3
"""Перевести вывод пакета детектора в формат, который читает layout_preview.

Пакет `dorahub-tile-detector-v8` отдаёт пиксельные `[x1, y1, x2, y2]` и абсолютный
путь к изображению, а `layout_preview.py` ждёт нормализованные `[cx, cy, w, h]`,
имя файла и поле `tile` — как в прогонах `club-*`. Без этого шага второй скрипт
падает на валидации `TileBox`.

    python3 vision/scripts/detector_to_layout.py results/predictions.json out.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image


def convert(raw: list[dict], image_root: Path | None) -> list[dict]:
    converted = []
    for item in raw:
        path = Path(item["image"])
        if image_root is not None:
            path = image_root / path.name
        width, height = Image.open(path).size
        detections = []
        for detection in item["detections"]:
            x1, y1, x2, y2 = detection["box"]
            detections.append(
                {
                    "tile": "tile",
                    "confidence": detection["confidence"],
                    "box": [
                        ((x1 + x2) / 2) / width,
                        ((y1 + y2) / 2) / height,
                        (x2 - x1) / width,
                        (y2 - y1) / height,
                    ],
                }
            )
        converted.append({"image": path.name, "detections": detections})
    return converted


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("predictions", type=Path, help="results/predictions.json пакета")
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--image-root",
        type=Path,
        default=None,
        help="искать изображения здесь, если пути в файле уже неверны",
    )
    args = parser.parse_args()

    raw = json.loads(args.predictions.read_text())
    converted = convert(raw, args.image_root)
    args.output.write_text(json.dumps(converted))
    for item in converted:
        print(f"{item['image']}: {len(item['detections'])} тайлов")


if __name__ == "__main__":
    main()
