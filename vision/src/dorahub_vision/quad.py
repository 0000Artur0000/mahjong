"""Четырёхугольник плоскости стола: расширение под детекции и запасной путь.

Плоскость оценивается по цвету сукна, и это ненадёжно: бумага, руки и предметы на
столе обрезают контур. На клубных фото такой квад терял до 16% найденных тайлов —
они выпадали из сцены ещё до группировки, и ни рука, ни стена в этой зоне уже не
могли появиться.

Здесь чистая геометрия без OpenCV: расширить квад так, чтобы он накрыл все тайлы,
и построить квад по одним тайлам, если цвет не сработал вовсе.
"""

from __future__ import annotations

from math import hypot

Point = tuple[float, float]
Quad = tuple[Point, Point, Point, Point]


def centroid(points) -> Point:
    points = list(points)
    if not points:
        raise ValueError("нужна хотя бы одна точка")
    return (
        sum(x for x, _ in points) / len(points),
        sum(y for _, y in points) / len(points),
    )


def _ray_exit(origin: Point, target: Point, corners) -> float:
    """Во сколько раз луч из `origin` через `target` длиннее до границы квада.

    Возвращает долю: 1.0 — точка ровно на границе, меньше — внутри, больше —
    снаружи. Именно эта величина и говорит, насколько надо расширить квад.
    """

    dx, dy = target[0] - origin[0], target[1] - origin[1]
    if hypot(dx, dy) == 0:
        return 0.0

    best = None
    count = len(corners)
    for index in range(count):
        x1, y1 = corners[index]
        x2, y2 = corners[(index + 1) % count]
        ex, ey = x2 - x1, y2 - y1
        denominator = dx * ey - dy * ex
        if abs(denominator) < 1e-12:
            continue
        # Параметр вдоль луча и вдоль стороны.
        t = ((x1 - origin[0]) * ey - (y1 - origin[1]) * ex) / denominator
        u = ((x1 - origin[0]) * dy - (y1 - origin[1]) * dx) / denominator
        if t > 0 and 0 <= u <= 1:
            best = t if best is None else min(best, t)
    if best is None or best <= 0:
        return 0.0
    return 1 / best


def expand_to_contain(corners: Quad, points, margin: float = 1.05) -> Quad:
    """Раздуть квад от его центра, пока внутрь не попадут все точки.

    Гомотетия от центра сохраняет форму квада, то есть и наклон плоскости: мы не
    придумываем новую перспективу, а лишь перестаём отсекать край стола.
    """

    points = [(float(x), float(y)) for x, y in points]
    if not points:
        return corners
    center = centroid(corners)
    needed = max([_ray_exit(center, point, corners) for point in points])
    if needed <= 1.0:
        # Все точки уже внутри — форму не трогаем, запас ни к чему.
        return corners
    scale = needed * margin
    return tuple(  # type: ignore[return-value]
        (
            center[0] + (x - center[0]) * scale,
            center[1] + (y - center[1]) * scale,
        )
        for x, y in corners
    )


def quad_from_points(points, margin: float = 1.08) -> Quad:
    """Запасной квад по самим тайлам, когда цвет стола не распознался.

    Осевая рамка вместо настоящей плоскости: перспективу она не восстанавливает,
    но сохраняет все тайлы в сцене. Это честнее, чем падать или работать с
    заведомо обрезанным контуром.
    """

    points = [(float(x), float(y)) for x, y in points]
    if not points:
        raise ValueError("нужна хотя бы одна точка")
    xs = [x for x, _ in points]
    ys = [y for _, y in points]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    half_width = max((max(xs) - min(xs)) / 2 * margin, 1e-3)
    half_height = max((max(ys) - min(ys)) / 2 * margin, 1e-3)
    return (
        (cx - half_width, cy - half_height),
        (cx + half_width, cy - half_height),
        (cx + half_width, cy + half_height),
        (cx - half_width, cy + half_height),
    )


def contains(corners: Quad, point: Point) -> bool:
    """Лежит ли точка внутри выпуклого квада."""

    signs = []
    count = len(corners)
    for index in range(count):
        x1, y1 = corners[index]
        x2, y2 = corners[(index + 1) % count]
        cross = (x2 - x1) * (point[1] - y1) - (y2 - y1) * (point[0] - x1)
        signs.append(cross)
    return all(value >= -1e-9 for value in signs) or all(
        value <= 1e-9 for value in signs
    )
