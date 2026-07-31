"""Предложения боксов для тайлов, которых не увидел детектор.

Детектор обучен на тайлах, лежащих лицом вверх, и пропускает рубашки, если их
цвет не встречался в обучении: на клубных фото это стены целиком. Без них
невозможны ни `wall`, ни `dead_wall`, ни `dora` — дора лежит на мёртвой стене.

Здесь нет привязки к конкретному цвету рубашки. Признак общий: **однородная
область на плоскости стола, чей цвет заметно отличается от самого стола и которую
не закрыл ни один детектированный бокс**. Так же находятся синие, зелёные и любые
другие рубашки; кремовые на кремовом столе детектор и так видит.

Результат — предложения, а не детекции: они помечаются `proposed=True`, чтобы
дальше по конвейеру их можно было судить строже.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import ceil, cos, hypot, sin


@dataclass(frozen=True)
class Proposal:
    """Предложенный бокс в нормированных координатах кадра."""

    cx: float
    cy: float
    width: float
    height: float
    proposed: bool = True


def split_run(
    cx: float,
    cy: float,
    length: float,
    thickness: float,
    angle: float,
    step: float,
) -> list[Proposal]:
    """Разрезать вытянутую область на тайлы вдоль её длинной оси.

    Область задаётся повёрнутым прямоугольником: ряд тайлов на столе почти всегда
    лежит под углом к кадру, и осевая рамка заполнена им лишь наполовину. Шаг
    решётки берётся из медианного размера уже найденных тайлов, а не подбирается
    по картинке.
    """

    if length <= 0 or thickness <= 0 or step <= 0:
        return []

    count = max(1, int(round(length / step)))
    # Слишком длинная область — это край стола или бумага, а не ряд тайлов.
    if count > 40:
        return []

    unit = length / count
    direction = (cos(angle), sin(angle))
    proposals = []
    for index in range(count):
        offset = (index + 0.5) * unit - length / 2
        proposals.append(
            Proposal(
                cx + direction[0] * offset,
                cy + direction[1] * offset,
                unit,
                thickness,
            )
        )
    return proposals


def covered(box: tuple[float, float, float, float], detections, slack: float) -> bool:
    """Уже ли эта точка занята детекцией: сравниваем по центрам, а не по IoU.

    Предложение и детекция одного тайла могут заметно разойтись рамками, но их
    центры остаются рядом — этого достаточно, чтобы не плодить дубли.
    """

    cx, cy = box[0], box[1]
    return any(
        hypot(cx - detection[0], cy - detection[1]) < slack for detection in detections
    )


def tile_size(detections) -> tuple[float, float]:
    """Медианный размер тайла по уже найденным боксам."""

    if not detections:
        raise ValueError("нужен хотя бы один детектированный тайл для масштаба")
    widths = sorted(detection[2] for detection in detections)
    heights = sorted(detection[3] for detection in detections)
    middle = len(widths) // 2
    return widths[middle], heights[middle]


def expected_count(area: float, tile_width: float, tile_height: float) -> int:
    """Сколько тайлов помещается в область такой площади."""

    unit = tile_width * tile_height
    return int(ceil(area / unit)) if unit > 0 else 0
