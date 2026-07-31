#!/usr/bin/env python3
"""Метрика группировки по ролям: сравнить `groups.json` с эталонной разметкой.

Без этой метрики «стало лучше» проверяется глазами по нескольким картинкам —
ровно так текущие эвристики и оказались подогнаны под пять исходных фото.

Два режима:

    # заготовить эталон из текущего выхода — потом руками поправить роли
    python3 vision/scripts/score_layout.py seed ../test/<run>/groups.json ../test/roles-truth

    # сравнить выход с проверенным эталоном
    python3 vision/scripts/score_layout.py score ../test/<run>/groups.json ../test/roles-truth

Заготовка помечается `"reviewed": false`. Такие файлы в счёт не идут: сравнивать
выход сам с собой бессмысленно, метрика всегда была бы идеальной.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from dorahub_vision.evaluation import rate

# Роли, которые считаются ответом. Всё остальное — признание, что не разобрались.
ASSIGNED_ROLES = ("hand", "opponent_hand", "discard", "wall", "dead_wall", "dora")
SILENT_ROLES = ("other", "noise", "unknown")

MATCH_IOU = 0.5


def iou(first: list[float], second: list[float]) -> float:
    """IoU двух боксов в формате cx, cy, w, h."""

    ax1, ay1 = first[0] - first[2] / 2, first[1] - first[3] / 2
    ax2, ay2 = first[0] + first[2] / 2, first[1] + first[3] / 2
    bx1, by1 = second[0] - second[2] / 2, second[1] - second[3] / 2
    bx2, by2 = second[0] + second[2] / 2, second[1] + second[3] / 2
    intersection = max(0.0, min(ax2, bx2) - max(ax1, bx1)) * max(
        0.0, min(ay2, by2) - max(ay1, by1)
    )
    union = first[2] * first[3] + second[2] * second[3] - intersection
    return intersection / union if union > 0 else 0.0


def tiles_by_role(groups: list[dict]) -> list[tuple[list[float], str, str | None]]:
    """Развернуть группы в плоский список (бокс, роль, место)."""

    return [
        (box, group["role"], group.get("seat"))
        for group in groups
        for box in group.get("tileBoxes", ())
    ]


def seed(groups_path: Path, output: Path) -> list[Path]:
    """Сделать черновик эталона из выхода группировки, по файлу на кадр."""

    output.mkdir(parents=True, exist_ok=True)
    written = []
    for item in json.loads(groups_path.read_text()):
        target = output / f"{Path(item['image']).stem}.json"
        if target.exists():
            print(f"пропускаю, уже есть: {target.name}")
            continue
        target.write_text(
            json.dumps(
                {
                    "image": item["image"],
                    # Черновик, а не эталон: роли ещё не проверены человеком.
                    "reviewed": False,
                    "groups": [
                        {
                            "role": group["role"],
                            "seat": group.get("seat"),
                            "tileBoxes": group.get("tileBoxes", []),
                        }
                        for group in item["groups"]
                    ],
                },
                indent=2,
                ensure_ascii=False,
            )
        )
        written.append(target)
    return written


def score(groups_path: Path, truth_root: Path) -> dict:
    """Сравнить выход с проверенным эталоном на уровне отдельных тайлов."""

    predictions = {
        Path(item["image"]).stem: item for item in json.loads(groups_path.read_text())
    }

    matched: list[tuple[str, str]] = []  # (роль эталона, роль выхода)
    missed = 0  # тайлы эталона, которым не нашлось бокса в выходе
    undecided = 0  # тайлы, роль которых не определил сам размечающий
    scored_images, unreviewed, missing = [], [], []

    for stem, item in sorted(predictions.items()):
        truth_path = truth_root / f"{stem}.json"
        if not truth_path.exists():
            missing.append(stem)
            continue
        truth = json.loads(truth_path.read_text())
        if not truth.get("reviewed"):
            unreviewed.append(stem)
            continue

        scored_images.append(stem)
        predicted = tiles_by_role(item["groups"])
        available = list(range(len(predicted)))
        for box, truth_role, _ in tiles_by_role(truth["groups"]):
            if truth_role == "unknown":
                # Размечающий сам не разобрал зону — штрафовать за неё нечестно.
                undecided += 1
                continue
            best, best_iou = None, MATCH_IOU
            for index in available:
                value = iou(box, predicted[index][0])
                if value >= best_iou:
                    best, best_iou = index, value
            if best is None:
                missed += 1
                continue
            available.remove(best)
            matched.append((truth_role, predicted[best][1]))

    return {
        "schemaVersion": 1,
        "images": {
            "scored": scored_images,
            "unreviewed": unreviewed,
            "withoutTruth": missing,
        },
        "roles": {
            role: {
                "precision": rate(
                    sum(t == role and p == role for t, p in matched),
                    sum(p == role for _, p in matched),
                ),
                "recall": rate(
                    sum(t == role and p == role for t, p in matched),
                    sum(t == role for t, _ in matched),
                ),
            }
            for role in ASSIGNED_ROLES
        },
        # Главная цифра: доля тайлов, которым уверенно назначена неверная роль.
        # Молчание (other/noise/unknown) сюда не входит — оно честное.
        "falseRole": rate(
            sum(p in ASSIGNED_ROLES and t != p for t, p in matched),
            sum(p in ASSIGNED_ROLES for _, p in matched),
        ),
        "silence": rate(
            sum(p in SILENT_ROLES for _, p in matched),
            len(matched),
        ),
        "missedTiles": rate(missed, len(matched) + missed),
        "undecidedTiles": undecided,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    seed_parser = commands.add_parser("seed", help="черновик эталона из выхода")
    seed_parser.add_argument("groups", type=Path)
    seed_parser.add_argument("truth_root", type=Path)

    score_parser = commands.add_parser("score", help="сравнить с эталоном")
    score_parser.add_argument("groups", type=Path)
    score_parser.add_argument("truth_root", type=Path)

    args = parser.parse_args()
    if args.command == "seed":
        for path in seed(args.groups, args.truth_root):
            print(path)
        print("Проверьте роли и проставьте \"reviewed\": true — иначе кадр не считается.")
    else:
        print(json.dumps(score(args.groups, args.truth_root), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
