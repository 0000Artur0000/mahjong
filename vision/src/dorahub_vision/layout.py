"""Geometry-first layout parser adapted from haitaks/mahjong."""

from dataclasses import dataclass, field
from itertools import combinations
from math import atan2, cos, fsum, hypot, isfinite, sin
from statistics import median, pstdev

MAX_TILES = 256
MAX_DISCARD_ROW_TILES = 6
# §7: канов максимум четыре, значит индикаторов доры не больше пяти.
DORA_MAX_TILES = 5


@dataclass(frozen=True)
class TableFrame:
    """Project the photographed table quadrilateral onto a unit square."""

    corners: tuple[
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
    ]
    _matrix: tuple[float, ...] = field(init=False, repr=False)
    _inverse_matrix: tuple[float, ...] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        if (
            len(self.corners) != 4
            or any(
                len(point) != 2
                or not all(
                    not isinstance(value, bool)
                    and isfinite(value)
                    for value in point
                )
                for point in self.corners
            )
        ):
            raise ValueError("table corners must be finite TL, TR, BR, BL")
        crosses = [
            (second[0] - first[0]) * (third[1] - second[1])
            - (second[1] - first[1]) * (third[0] - second[0])
            for first, second, third in zip(
                self.corners,
                (*self.corners[1:], self.corners[0]),
                (*self.corners[2:], *self.corners[:2]),
                strict=True,
            )
        ]
        if (
            any(abs(cross) < 1e-8 for cross in crosses)
            or min(crosses) < 0 < max(crosses)
        ):
            raise ValueError("table corners must form a convex quadrilateral")
        tl, tr, br, bl = self.corners
        dx1, dx2 = tr[0] - br[0], bl[0] - br[0]
        dy1, dy2 = tr[1] - br[1], bl[1] - br[1]
        dx3 = tl[0] - tr[0] + br[0] - bl[0]
        dy3 = tl[1] - tr[1] + br[1] - bl[1]
        determinant = dx1 * dy2 - dx2 * dy1
        if abs(determinant) < 1e-8:
            raise ValueError("table corners must form a quadrilateral")
        g = (dx3 * dy2 - dx2 * dy3) / determinant
        h = (dx1 * dy3 - dx3 * dy1) / determinant
        square_to_image = (
            tr[0] - tl[0] + g * tr[0],
            bl[0] - tl[0] + h * bl[0],
            tl[0],
            tr[1] - tl[1] + g * tr[1],
            bl[1] - tl[1] + h * bl[1],
            tl[1],
            g,
            h,
            1.0,
        )
        object.__setattr__(self, "_inverse_matrix", square_to_image)
        object.__setattr__(self, "_matrix", _invert_3x3(square_to_image))

    def map(self, x: float, y: float) -> tuple[float, float]:
        a, b, c, d, e, f, g, h, i = self._matrix
        scale = g * x + h * y + i
        if abs(scale) < 1e-8:
            raise ValueError("point lies on the table horizon")
        return (a * x + b * y + c) / scale, (
            d * x + e * y + f
        ) / scale

    def unmap(self, x: float, y: float) -> tuple[float, float]:
        a, b, c, d, e, f, g, h, i = self._inverse_matrix
        scale = g * x + h * y + i
        if abs(scale) < 1e-8:
            raise ValueError("point lies on the table horizon")
        return (a * x + b * y + c) / scale, (
            d * x + e * y + f
        ) / scale


@dataclass(frozen=True)
class TileBox:
    cx: float
    cy: float
    width: float
    height: float
    class_id: int | None = None
    face_score: float | None = None
    angle: float | None = None

    def __post_init__(self) -> None:
        values = (self.cx, self.cy, self.width, self.height)
        if (
            not all(isfinite(value) for value in values)
            or not 0 <= self.cx <= 1
            or not 0 <= self.cy <= 1
            or not 0 < self.width <= 1
            or not 0 < self.height <= 1
            or self.cx - self.width / 2 < 0
            or self.cx + self.width / 2 > 1
            or self.cy - self.height / 2 < 0
            or self.cy + self.height / 2 > 1
            or (
                self.class_id is not None
                and (not isinstance(self.class_id, int) or self.class_id < 0)
            )
            or (
                self.face_score is not None
                and (
                    isinstance(self.face_score, bool)
                    or not isfinite(self.face_score)
                    or not 0 <= self.face_score <= 1
                )
            )
            or (
                self.angle is not None
                and (
                    isinstance(self.angle, bool)
                    or not isfinite(self.angle)
                )
            )
        ):
            raise ValueError("tile box must fit normalized [0, 1] coordinates")

    @property
    def size(self) -> float:
        return (self.width + self.height) / 2

    @property
    def aspect(self) -> float:
        return self.height / self.width


@dataclass(frozen=True)
class LayoutParams:
    eps_k: float = 1.5
    min_samples: int = 2
    hand_min_tiles: int = 13
    hand_max_tiles: int = 18
    hand_merge_gap_k: float = 3.0
    discard_min_tiles: int = 2
    discard_outlier_k: float = 2.25
    dead_wall_min_tiles: int = 4
    dead_wall_max_tiles: int = 14
    face_threshold: float = 0.1
    player_direction: tuple[float, float] | None = None
    table_corners: tuple[
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
    ] | None = None

    def __post_init__(self) -> None:
        if (
            not all(
                isfinite(value)
                for value in (
                    self.eps_k,
                    self.hand_merge_gap_k,
                    self.discard_outlier_k,
                    self.face_threshold,
                )
            )
            or self.eps_k <= 0
            or not 1 <= self.min_samples <= MAX_TILES
            or not 1 <= self.hand_min_tiles <= self.hand_max_tiles
            or self.hand_merge_gap_k <= 0
            or self.discard_min_tiles < 1
            or self.discard_outlier_k <= 0
            or not 1 <= self.dead_wall_min_tiles <= self.dead_wall_max_tiles
            or not 0 <= self.face_threshold <= 1
            or (
                self.player_direction is not None
                and (
                    len(self.player_direction) != 2
                    or not all(isfinite(value) for value in self.player_direction)
                    or self.player_direction == (0, 0)
                )
            )
        ):
            raise ValueError("invalid layout parameters")
        if self.table_corners is not None:
            TableFrame(self.table_corners)


@dataclass
class Cluster:
    tiles: tuple[TileBox, ...]
    centroid: tuple[float, float]
    bounds: tuple[float, float, float, float]
    rows: int
    columns: int
    regularity: float
    dominant_orientation: str
    scale: float
    axis: tuple[float, float]
    linearity: float
    role: str = "other"
    heuristic_score: float = 0.0
    seat: str | None = None

    @property
    def tile_count(self) -> int:
        return len(self.tiles)


@dataclass(frozen=True)
class Meld:
    kind: str
    tiles: tuple[TileBox, ...]
    bounds: tuple[float, float, float, float]
    seat: str
    called_index: int


@dataclass(frozen=True)
class LayoutResult:
    clusters: tuple[Cluster, ...]
    hand: Cluster | None
    discards: tuple[Cluster, ...] = ()
    walls: tuple[Cluster, ...] = ()
    others: tuple[Cluster, ...] = ()
    dead_wall: Cluster | None = None
    dora: tuple[Cluster, ...] = ()
    opponent_hands: tuple[Cluster, ...] = ()
    noise: tuple[Cluster, ...] = ()
    melds: tuple[Meld, ...] = ()


def cluster_layout(
    boxes: list[TileBox] | tuple[TileBox, ...],
    params: LayoutParams | None = None,
) -> LayoutResult:
    """Validate detections, cluster them by scale, then assign table roles."""

    tiles = tuple(boxes)
    if len(tiles) > MAX_TILES or any(not isinstance(tile, TileBox) for tile in tiles):
        raise ValueError(f"layout accepts at most {MAX_TILES} TileBox detections")
    if not tiles:
        return LayoutResult((), None)
    if params is not None and not isinstance(params, LayoutParams):
        raise ValueError("params must be LayoutParams")
    config = params or LayoutParams()
    frame = TableFrame(config.table_corners) if config.table_corners else None
    clusters = _cluster(tiles, config, frame)
    hand, dead_wall = _assign_roles(clusters, config, frame)
    melds = tuple(
        meld
        for cluster in clusters
        if cluster.role in {"hand", "opponent_hand"}
        for meld in _open_melds(cluster, frame, config.face_threshold)
    )
    return LayoutResult(
        clusters=tuple(clusters),
        hand=hand,
        discards=tuple(
            cluster for cluster in clusters if cluster.role == "discard"
        ),
        walls=tuple(
            cluster
            for cluster in clusters
            if cluster.role in {"wall", "dead_wall"}
        ),
        others=tuple(cluster for cluster in clusters if cluster.role == "other"),
        dead_wall=dead_wall,
        dora=tuple(cluster for cluster in clusters if cluster.role == "dora"),
        opponent_hands=tuple(
            cluster
            for cluster in clusters
            if cluster.role == "opponent_hand"
        ),
        noise=tuple(cluster for cluster in clusters if cluster.role == "noise"),
        melds=melds,
    )


def _cluster(
    tiles: tuple[TileBox, ...],
    params: LayoutParams,
    frame: TableFrame | None = None,
) -> list[Cluster]:
    points = [_point(tile, frame) for tile in tiles]
    scales = [_tile_scale(tile, frame) for tile in tiles]
    # ponytail: O(n²) is bounded by MAX_TILES; add a spatial index only if profiling requires it.
    neighbors = [
        [
            other
            for other in range(len(tiles))
            if hypot(
                points[index][0] - points[other][0],
                points[index][1] - points[other][1],
            )
            < params.eps_k * (scales[index] + scales[other]) / 2
        ]
        for index in range(len(tiles))
    ]
    visited = [False] * len(tiles)
    labels = [-1] * len(tiles)
    cluster_id = 0
    for index in range(len(tiles)):
        if visited[index]:
            continue
        visited[index] = True
        if len(neighbors[index]) < params.min_samples:
            continue
        labels[index] = cluster_id
        frontier = list(neighbors[index])
        while frontier:
            candidate = frontier.pop()
            if not visited[candidate]:
                visited[candidate] = True
                if len(neighbors[candidate]) >= params.min_samples:
                    frontier.extend(neighbors[candidate])
            if labels[candidate] == -1:
                labels[candidate] = cluster_id
        cluster_id += 1

    groups: list[list[TileBox]] = [[] for _ in range(cluster_id)]
    singletons: list[list[TileBox]] = []
    for tile, label in zip(tiles, labels, strict=True):
        if label >= 0:
            groups[label].append(tile)
        else:
            singletons.append([tile])
    clusters = [
        _describe(tuple(group), frame) for group in groups + singletons
    ]
    return sorted(clusters, key=lambda cluster: cluster.tile_count, reverse=True)


def _describe(
    tiles: tuple[TileBox, ...], frame: TableFrame | None = None
) -> Cluster:
    points = [_point(tile, frame) for tile in tiles]
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    scale = median(_tile_scale(tile, frame) for tile in tiles)
    axis, linearity = _principal_axis(xs, ys)
    tiles = _order_tiles(tiles, axis, scale, frame)
    along = [x * axis[0] + y * axis[1] for x, y in zip(xs, ys, strict=True)]
    across = [-x * axis[1] + y * axis[0] for x, y in zip(xs, ys, strict=True)]
    corners = [
        frame.map(x, y) if frame else (x, y)
        for tile in tiles
        for x, y in (
            (tile.cx - tile.width / 2, tile.cy - tile.height / 2),
            (tile.cx + tile.width / 2, tile.cy - tile.height / 2),
            (tile.cx + tile.width / 2, tile.cy + tile.height / 2),
            (tile.cx - tile.width / 2, tile.cy + tile.height / 2),
        )
    ]
    return Cluster(
        tiles,
        (fsum(xs) / len(xs), fsum(ys) / len(ys)),
        (
            min(point[0] for point in corners),
            min(point[1] for point in corners),
            max(point[0] for point in corners),
            max(point[1] for point in corners),
        ),
        _count_bands(across, scale),
        _count_bands(along, scale),
        _regularity(along, scale),
        "horizontal" if abs(axis[0]) >= abs(axis[1]) else "vertical",
        scale,
        axis,
        linearity,
    )


def _count_bands(values: list[float], scale: float) -> int:
    return len(_bands(values, 0.6 * scale))


def _bands(values: list[float], tolerance: float) -> list[list[float]]:
    bands: list[list[float]] = []
    for value in sorted(values):
        if not bands or value - bands[-1][-1] > tolerance:
            bands.append([value])
        else:
            bands[-1].append(value)
    return bands


def _regularity(values: list[float], scale: float) -> float:
    centers = [fsum(band) / len(band) for band in _bands(values, 0.6 * scale)]
    if len(centers) < 3:
        return 1.0
    gaps = [
        current - previous
        for previous, current in zip(centers, centers[1:])
    ]
    mean_gap = fsum(gaps) / len(gaps)
    return max(0.0, min(1.0, 1 - pstdev(gaps) / mean_gap)) if mean_gap else 1.0


def _principal_axis(
    xs: list[float], ys: list[float]
) -> tuple[tuple[float, float], float]:
    if len(xs) < 2:
        return (1.0, 0.0), 1.0
    mean_x, mean_y = fsum(xs) / len(xs), fsum(ys) / len(ys)
    xx = fsum((x - mean_x) ** 2 for x in xs)
    yy = fsum((y - mean_y) ** 2 for y in ys)
    xy = fsum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys, strict=True))
    angle = 0.5 * atan2(2 * xy, xx - yy)
    spread = xx + yy
    difference = hypot(xx - yy, 2 * xy)
    axis = (cos(angle), sin(angle))
    if (
        abs(axis[0]) >= abs(axis[1]) and axis[0] < 0
        or abs(axis[1]) > abs(axis[0]) and axis[1] < 0
    ):
        axis = (-axis[0], -axis[1])
    return axis, (spread + difference) / (2 * spread) if spread else 1.0


def _order_tiles(
    tiles: tuple[TileBox, ...],
    axis: tuple[float, float],
    scale: float,
    frame: TableFrame | None = None,
) -> tuple[TileBox, ...]:
    rows: list[list[tuple[float, float, TileBox]]] = []
    projected = sorted(
        [
            (
                -x * axis[1] + y * axis[0],
                x * axis[0] + y * axis[1],
                tile,
            )
            for tile in tiles
            for x, y in (_point(tile, frame),)
        ],
        key=lambda item: (item[0], item[1]),
    )
    for across, along, tile in projected:
        if not rows or across - rows[-1][-1][0] > 0.6 * scale:
            rows.append([])
        rows[-1].append((across, along, tile))
    return tuple(
        item[2]
        for row in rows
        for item in sorted(row, key=lambda value: value[1])
    )


def _assign_roles(
    clusters: list[Cluster],
    params: LayoutParams,
    frame: TableFrame | None = None,
) -> tuple[Cluster | None, Cluster | None]:
    tiles = tuple(tile for cluster in clusters for tile in cluster.tiles)
    clusters.clear()

    hand_tiles = _bottom_hand(tiles, params, frame)
    claimed = set(hand_tiles)
    hand = _describe(hand_tiles, frame) if hand_tiles else None
    if hand is not None:
        hand.role, hand.seat = "hand", "self"
        clusters.append(hand)

    remaining = tuple(tile for tile in tiles if tile not in claimed)
    face_tiles = tuple(
        tile
        for tile in remaining
        if tile.face_score is not None
        and tile.face_score >= params.face_threshold
    )
    discard_candidates = [
        cluster
        for oriented in (
            face_tiles,
            tuple(tile for tile in face_tiles if tile.height >= tile.width),
            tuple(tile for tile in face_tiles if tile.height < tile.width),
        )
        for cluster in _cluster(oriented, params, frame)
        if params.discard_min_tiles <= cluster.tile_count <= 24
        and cluster.rows <= 4
        and (
            cluster.rows > 1
            or cluster.tile_count <= MAX_DISCARD_ROW_TILES
            or cluster.linearity < 0.9
        )
    ]
    discards = _discard_quartet(discard_candidates)
    directions = _seat_directions((0.0, 1.0))
    center = _center(list(discards)) if discards else _tile_center(remaining, frame)
    for discard in discards:
        discard.role = "discard"
        discard.seat = _seat(discard.centroid, center, directions)
        discard.heuristic_score = 1.0
        clusters.append(discard)
        claimed.update(discard.tiles)

    remaining = tuple(tile for tile in tiles if tile not in claimed)
    wall_candidates = [
        cluster
        for cluster in _cluster(remaining, params, frame)
        if cluster.tile_count >= params.dead_wall_min_tiles
        and cluster.rows <= 2
        and cluster.linearity >= 0.6
        and _back_fraction(cluster, params.face_threshold) > 0.5
    ]
    walls = _one_wall_per_seat(wall_candidates, center, directions, params)
    dead_wall = None
    for wall in walls:
        faces = tuple(
            tile
            for tile in wall.tiles
            if tile.face_score is not None
            and tile.face_score >= params.face_threshold
        )
        backs = tuple(tile for tile in wall.tiles if tile not in faces)
        if 1 <= len(faces) <= DORA_MAX_TILES:
            wall_body = _describe(backs, frame)
            is_dead = (
                dead_wall is None
                and len(backs) >= params.dead_wall_min_tiles
                and wall.tile_count <= params.dead_wall_max_tiles
            )
            wall_body.role = "dead_wall" if is_dead else "wall"
            wall_body.seat = _seat(wall_body.centroid, center, directions)
            if is_dead:
                dead_wall = wall_body
            dora = _describe(faces, frame)
            dora.role, dora.seat = "dora", wall_body.seat
            clusters.extend((wall_body, dora))
        else:
            wall.role = "wall"
            wall.seat = _seat(wall.centroid, center, directions)
            clusters.append(wall)
        claimed.update(wall.tiles)

    leftovers = tuple(tile for tile in tiles if tile not in claimed)
    for noise in _cluster(leftovers, params, frame):
        noise.role = "noise"
        clusters.append(noise)
    return hand, _extract_embedded_dora(clusters, params, frame) or dead_wall


def _bottom_hand(
    tiles: tuple[TileBox, ...],
    params: LayoutParams,
    frame: TableFrame | None,
) -> tuple[TileBox, ...]:
    """Grow the player's hand from the lowest detected tile."""

    if not tiles:
        return ()
    rejected: set[TileBox] = set()
    lowest = max(tiles, key=lambda tile: tile.cy + tile.height / 2)
    lowest_edge = lowest.cy + lowest.height / 2
    candidates = sorted(
        tiles,
        key=lambda tile: tile.cy + tile.height / 2,
        reverse=True,
    )
    for candidate in candidates:
        if (
            lowest_edge - candidate.cy - candidate.height / 2
            > 4 * max(lowest.size, candidate.size)
        ):
            break
        if candidate in rejected:
            continue
        partner = min(
            (
                tile
                for tile in tiles
                if tile is not candidate
                and 0.5 <= candidate.size / tile.size <= 2
            ),
            key=lambda tile: hypot(
                tile.cx - candidate.cx,
                tile.cy - candidate.cy,
            ),
            default=None,
        )
        if (
            partner is not None
            and hypot(partner.cx - candidate.cx, partner.cy - candidate.cy)
            <= min(2.0, params.hand_merge_gap_k)
            * max(candidate.size, partner.size)
        ):
            selected = _grow_hand_line(candidate, partner, tiles, params)
            if len(selected) > params.hand_max_tiles:
                physical_count = (
                    2 * fsum(tile.width for tile in selected)
                    / median(tile.height for tile in selected)
                )
                if (
                    median(tile.aspect for tile in selected) < 2.8
                    or physical_count > params.hand_max_tiles
                ):
                    selected = selected[: params.hand_max_tiles]
            if len(selected) >= params.hand_min_tiles:
                return tuple(selected)
            rejected.update(selected)
    # Открытые чи/поны разрывают руку на несколько коротких рядов. Расширяем
    # нижнюю микрозону до минимальной полной руки вместо выбора центрального
    # сброса как одного красивого ряда.
    visible = [
        tile
        for tile in candidates
        if (tile.face_score or 0) >= params.face_threshold
    ]
    fallback = visible if len(visible) >= params.hand_min_tiles else candidates
    fallback = fallback[: params.hand_max_tiles]
    ordered_x = sorted(fallback, key=lambda tile: tile.cx)
    if len(ordered_x) >= params.hand_min_tiles:
        gap, cut = max(
            (
                (right.cx - left.cx, index)
                for index, (left, right) in enumerate(
                    zip(ordered_x, ordered_x[1:]), start=1
                )
            ),
            default=(0.0, 0),
        )
        parts = (ordered_x[:cut], ordered_x[cut:])
        viable = [part for part in parts if len(part) >= params.hand_min_tiles]
        if viable and gap > 3 * median(tile.width for tile in fallback):
            fallback = max(
                viable,
                key=lambda part: median(
                    tile.cy + tile.height / 2 for tile in part
                ),
            )
    return (
        tuple(fallback)
        if len(fallback) >= params.hand_min_tiles
        else ()
    )


def _grow_hand_line(
    seed: TileBox,
    nearest: TileBox,
    tiles: tuple[TileBox, ...],
    params: LayoutParams,
) -> list[TileBox]:
    selected = [seed, nearest]
    remaining = [tile for tile in tiles if tile not in selected]
    while remaining and len(selected) < 2 * params.hand_max_tiles:
        line = _describe(tuple(selected))
        candidate = min(
            (
                tile
                for tile in remaining
                if 0.5 <= line.scale / tile.size <= 2
                and _axis_distance((tile.cx, tile.cy), line)
                <= 0.9 * max(line.scale, tile.size)
                and _projected_gap(line, _describe((tile,)), line.axis)
                <= (params.hand_merge_gap_k + 1.5)
                * max(line.scale, tile.size)
            ),
            key=lambda tile: _cluster_gap(line, _describe((tile,))),
            default=None,
        )
        if candidate is None:
            break
        selected.append(candidate)
        remaining.remove(candidate)
    return selected


def _discard_quartet(candidates: list[Cluster]) -> tuple[Cluster, ...]:
    """Find two parallel pairs whose pair axes are perpendicular."""

    pairings = (((0, 1), (2, 3)), ((0, 2), (1, 3)), ((0, 3), (1, 2)))
    best: tuple[float, tuple[Cluster, ...]] | None = None
    for quartet in combinations(candidates, 4):
        if len({tile for cluster in quartet for tile in cluster.tiles}) != sum(
            cluster.tile_count for cluster in quartet
        ):
            continue
        counts = [cluster.tile_count for cluster in quartet]
        if max(counts) - min(counts) > MAX_DISCARD_ROW_TILES:
            continue
        center = _center(list(quartet))
        radii = [
            hypot(
                cluster.centroid[0] - center[0],
                cluster.centroid[1] - center[1],
            )
            for cluster in quartet
        ]
        if not min(radii) or max(radii) > 2 * min(radii):
            continue
        directions = _seat_directions((0.0, 1.0))
        if len({_seat(cluster.centroid, center, directions) for cluster in quartet}) < 4:
            continue
        for first_pair, second_pair in pairings:
            a, b = (quartet[index] for index in first_pair)
            c, d = (quartet[index] for index in second_pair)
            first_axis = _paired_axis(a, b)
            second_axis = _paired_axis(c, d)
            first_parallel = abs(_projection(a.axis, b.axis))
            second_parallel = abs(_projection(c.axis, d.axis))
            perpendicular = 1 - abs(_projection(first_axis, second_axis))
            first_opposed = _opposed_pair(a, b, first_axis)
            second_opposed = _opposed_pair(c, d, second_axis)
            if min(
                first_parallel,
                second_parallel,
                perpendicular,
                first_opposed,
                second_opposed,
            ) < 0.65:
                continue
            score = (
                first_parallel
                + second_parallel
                + perpendicular
                + first_opposed
                + second_opposed
                + sum(min(cluster.tile_count, 18) for cluster in quartet) / 72
            )
            if best is None or score > best[0]:
                best = score, quartet
    return best[1] if best else ()


def _paired_axis(first: Cluster, second: Cluster) -> tuple[float, float]:
    sign = 1 if _projection(first.axis, second.axis) >= 0 else -1
    return _normalize(
        (first.axis[0] + sign * second.axis[0], first.axis[1] + sign * second.axis[1])
    )


def _opposed_pair(
    first: Cluster,
    second: Cluster,
    axis: tuple[float, float],
) -> float:
    delta = _normalize(
        (
            second.centroid[0] - first.centroid[0],
            second.centroid[1] - first.centroid[1],
        )
    )
    return 1 - abs(_projection(delta, axis))


def _one_wall_per_seat(
    candidates: list[Cluster],
    center: tuple[float, float],
    directions: dict[str, tuple[float, float]],
    params: LayoutParams,
) -> tuple[Cluster, ...]:
    by_seat: dict[str, list[Cluster]] = {}
    for wall in candidates:
        by_seat.setdefault(_seat(wall.centroid, center, directions), []).append(wall)
    return tuple(
        max(
            walls,
            key=lambda wall: (
                1
                <= _face_count(wall, params.face_threshold)
                <= DORA_MAX_TILES,
                _back_fraction(wall, params.face_threshold),
                wall.linearity,
                -hypot(
                    wall.centroid[0] - center[0],
                    wall.centroid[1] - center[1],
                ),
                wall.tile_count,
            ),
        )
        for walls in by_seat.values()
    )


def _tile_center(
    tiles: tuple[TileBox, ...], frame: TableFrame | None
) -> tuple[float, float]:
    if not tiles:
        return 0.5, 0.5
    points = [_point(tile, frame) for tile in tiles]
    return median(point[0] for point in points), median(point[1] for point in points)


def _promote_edge_hands(
    clusters: list[Cluster],
    params: LayoutParams,
    directions: dict[str, tuple[float, float]],
    frame: TableFrame | None,
    center: tuple[float, float],
) -> None:
    live_walls = [cluster for cluster in clusters if cluster.role == "wall"]
    for cluster in clusters:
        if (
            cluster.role == "other"
            and MAX_DISCARD_ROW_TILES
            <= cluster.tile_count
            <= params.hand_max_tiles
            and _face_count(cluster, params.face_threshold) > 0
            and _open_meld_sizes(cluster, frame)
        ):
            cluster.role = "opponent_hand"
            cluster.seat = _seat(cluster.centroid, center, directions)

    for hand in tuple(
        cluster for cluster in clusters if cluster.role == "opponent_hand"
    ):
        parts = [
            hand,
            *(
                cluster
                for cluster in clusters
                if cluster.role == "other"
                and 1 <= cluster.tile_count <= 4
                and (
                    cluster.tile_count == 1
                    or _face_count(cluster, params.face_threshold) > 0
                )
                and _cluster_gap(hand, cluster)
                <= (
                    2.5 if cluster.tile_count == 1 else params.hand_merge_gap_k
                )
                * max(hand.scale, cluster.scale)
            ),
        ]
        if (
            len(parts) > 1
            and sum(part.tile_count for part in parts)
            <= params.hand_max_tiles
        ):
            _replace(
                clusters,
                parts,
                "opponent_hand",
                frame=frame,
                seat=hand.seat,
            )

    by_edge: dict[str, list[Cluster]] = {
        "left": [],
        "opposite": [],
        "right": [],
    }
    for wall in live_walls:
        seat, edge_distance = _opponent_edge(wall.centroid)
        if edge_distance <= 0.15:
            by_edge[seat].append(wall)
            if _face_count(wall, params.face_threshold) > 0:
                wall.role = "opponent_hand"
                wall.seat = seat

    for seat, edge_walls in by_edge.items():
        back_walls = [
            wall
            for wall in edge_walls
            if wall.role == "wall"
            and _face_count(wall, params.face_threshold) == 0
        ]
        if len(back_walls) < 2:
            continue
        outward = directions[seat]
        ranked = sorted(
            back_walls,
            key=lambda wall: _projection(wall.centroid, outward),
            reverse=True,
        )
        if (
            _projection(ranked[0].centroid, outward)
            - _projection(ranked[1].centroid, outward)
            >= ranked[0].scale
        ):
            ranked[0].role = "opponent_hand"
            ranked[0].seat = seat

    for seat in ("left", "opposite", "right"):
        for parts in _components(
            [
                cluster
                for cluster in clusters
                if cluster.role == "other"
                and _opponent_edge(cluster.centroid)[0] == seat
                and _opponent_edge(cluster.centroid)[1] <= 0.15
            ],
            params.hand_merge_gap_k,
            radial_direction=directions[seat],
        ):
            merged = _merged(parts, frame)
            if (
                len(parts) >= 2
                and 6 <= merged.tile_count <= params.hand_max_tiles
                and _face_count(merged, params.face_threshold) == 0
                and abs(
                    merged.axis[0] * directions[seat][0]
                    + merged.axis[1] * directions[seat][1]
                )
                <= 0.65
            ):
                _replace(
                    clusters,
                    parts,
                    "opponent_hand",
                    frame=frame,
                    seat=seat,
                )


def _open_meld_sizes(
    cluster: Cluster,
    frame: TableFrame | None,
) -> tuple[int, ...]:
    projections = sorted(
        _projection(_point(tile, frame), cluster.axis)
        for tile in cluster.tiles
    )
    gaps = [
        current - previous
        for previous, current in zip(projections, projections[1:])
    ]
    if not gaps:
        return ()
    typical_gap = median(gaps)
    cuts = [
        index
        for index, gap in enumerate(gaps, start=1)
        if gap > 1.5 * typical_gap
    ]
    sizes = tuple(
        end - start
        for start, end in zip((0, *cuts), (*cuts, len(projections)), strict=True)
    )
    return sizes if len(sizes) >= 2 and all(3 <= size <= 4 for size in sizes) else ()


def _open_melds(
    cluster: Cluster,
    frame: TableFrame | None,
    face_threshold: float,
) -> tuple[Meld, ...]:
    ordered = sorted(
        cluster.tiles,
        key=lambda tile: _projection(_point(tile, frame), cluster.axis),
    )
    projections = [
        _projection(_point(tile, frame), cluster.axis) for tile in ordered
    ]
    gaps = [
        current - previous
        for previous, current in zip(projections, projections[1:])
    ]
    cuts = (
        [
            index
            for index, gap in enumerate(gaps, start=1)
            if gap > 1.5 * median(gaps)
        ]
        if gaps
        else []
    )
    runs = [
        ordered[start:end]
        for start, end in zip(
            (0, *cuts), (*cuts, len(ordered)), strict=True
        )
    ]
    melds = []
    for tiles in runs:
        if len(tiles) not in {3, 4}:
            continue
        sideways = [
            tile for tile in tiles if _is_sideways(tile, cluster, frame)
        ]
        if len(sideways) != 1:
            continue
        described = _describe(tuple(tiles), frame)
        if _face_count(described, face_threshold) < len(tiles) - 1:
            continue
        called = sideways[0]
        kind = _meld_kind(tiles)
        if kind is None:
            continue
        melds.append(
            Meld(
                kind,
                described.tiles,
                described.bounds,
                cluster.seat or "self",
                described.tiles.index(called),
            )
        )
    return tuple(melds)


def _is_sideways(
    tile: TileBox,
    cluster: Cluster,
    frame: TableFrame | None,
) -> bool:
    if tile.angle is None:
        return False
    start = _point(tile, frame)
    end = (
        tile.cx + cos(tile.angle) * tile.size / 2,
        tile.cy + sin(tile.angle) * tile.size / 2,
    )
    end = frame.map(*end) if frame else end
    axis = _normalize((end[0] - start[0], end[1] - start[1]))
    return abs(
        axis[0] * cluster.axis[0] + axis[1] * cluster.axis[1]
    ) >= 0.7


def _meld_kind(tiles: list[TileBox]) -> str | None:
    classes = [_canonical_class(tile.class_id) for tile in tiles]
    known = [class_id for class_id in classes if class_id is not None]
    if len(tiles) == 4:
        if len(set(known)) > 1:
            return "kan_candidate"
        return "kan" if len(known) >= 2 else "kan_candidate"
    if not known:
        return None
    if len(set(known)) == 1:
        return "pon" if len(known) >= 2 else "chi_or_pon"
    if any(class_id >= 27 for class_id in known):
        return "chi_or_pon"
    suits = {class_id // 9 for class_id in known}
    ranks = {class_id % 9 for class_id in known}
    if (
        len(suits) == 1
        and len(ranks) == len(known)
        and max(ranks) - min(ranks) <= 2
    ):
        return "chi"
    return "chi_or_pon"


def _canonical_class(class_id: int | None) -> int | None:
    if class_id is None:
        return None
    if 0 <= class_id < 34:
        return class_id
    return {34: 4, 35: 13, 36: 22}.get(class_id)


def _opponent_edge(point: tuple[float, float]) -> tuple[str, float]:
    distances = {
        "left": point[0],
        "opposite": point[1],
        "right": 1 - point[0],
    }
    seat = min(distances, key=distances.get)
    return seat, distances[seat]


def _extract_embedded_dora(
    clusters: list[Cluster],
    params: LayoutParams,
    frame: TableFrame | None,
) -> Cluster | None:
    """Find class-confirmed face tiles embedded in a line of wall backs."""

    existing = next(
        (cluster for cluster in clusters if cluster.role == "dead_wall"),
        None,
    )
    if sum(cluster.role == "dora" for cluster in clusters) == 1:
        return existing

    known_wall_tiles = {
        tile
        for cluster in clusters
        if cluster.role in {"wall", "dead_wall", "dora"}
        for tile in cluster.tiles
    }
    tiles = tuple(
        tile
        for cluster in clusters
        if cluster.role not in {"hand", "discard", "opponent_hand"}
        for tile in cluster.tiles
    )
    faces = [
        tile
        for tile in tiles
        if tile.class_id is not None
        and tile.face_score is not None
        and tile.face_score >= params.face_threshold
    ]
    backs = [
        tile
        for tile in tiles
        if tile.face_score is not None
        and tile.face_score < params.face_threshold
    ]
    candidates: list[tuple[tuple[float, ...], TileBox, Cluster]] = []
    for face in faces:
        point = _point(face, frame)
        scale = _tile_scale(face, frame)
        support = tuple(
            back
            for back in backs
            if 0.4 <= _tile_scale(back, frame) / scale <= 2.5
            and hypot(
                _point(back, frame)[0] - point[0],
                _point(back, frame)[1] - point[1],
            )
            <= 3 * max(scale, _tile_scale(back, frame))
        )
        if len(support) < 2:
            continue
        wall = _describe(support, frame)
        along = [_projection(_point(back, frame), wall.axis) for back in support]
        face_along = _projection(point, wall.axis)
        distance = _axis_distance(point, wall) / wall.scale
        if (
            wall.rows <= 2
            and wall.linearity >= 0.7
            and distance <= 0.55
            and min(along) <= face_along <= max(along)
        ):
            candidates.append(
                (
                    (
                        sum(back in known_wall_tiles for back in support),
                        min(len(support), params.dead_wall_max_tiles),
                        wall.linearity,
                        -distance,
                        face.face_score or 0,
                    ),
                    face,
                    wall,
                )
            )
    if not candidates:
        return existing

    for cluster in clusters:
        if cluster.role == "dora":
            cluster.role = "other"
        elif cluster.role == "dead_wall":
            cluster.role = "wall"

    _, _, wall = max(candidates, key=lambda candidate: candidate[0])
    support = {
        back
        for back in backs
        if _axis_distance(_point(back, frame), wall) <= 0.55 * wall.scale
    }
    along = [_projection(_point(back, frame), wall.axis) for back in support]
    anchor = max(candidates, key=lambda candidate: candidate[0])[1]
    dora_tiles = tuple(
        face
        for _, face, candidate_wall in sorted(
            candidates, reverse=True, key=lambda item: item[0]
        )
        if len(support.intersection(candidate_wall.tiles)) >= 2
        and abs(_projection(candidate_wall.axis, wall.axis)) >= 0.8
        and _axis_distance(_point(face, frame), wall) <= 0.55 * wall.scale
        and min(along) <= _projection(_point(face, frame), wall.axis) <= max(along)
        and hypot(
            _point(face, frame)[0] - _point(anchor, frame)[0],
            _point(face, frame)[1] - _point(anchor, frame)[1],
        )
        <= (DORA_MAX_TILES + 1) * wall.scale
    )[:DORA_MAX_TILES]
    wall_tiles = tuple(
        sorted(
            support,
            key=lambda tile: min(
                hypot(
                    _point(tile, frame)[0] - _point(dora, frame)[0],
                    _point(tile, frame)[1] - _point(dora, frame)[1],
                )
                for dora in dora_tiles
            ),
        )[: params.dead_wall_max_tiles - len(dora_tiles)]
    )
    selected = {*dora_tiles, *wall_tiles}
    updated: list[Cluster] = []
    for cluster in clusters:
        leftovers = tuple(tile for tile in cluster.tiles if tile not in selected)
        if not leftovers:
            continue
        replacement = _describe(leftovers, frame)
        replacement.role = cluster.role
        replacement.seat = cluster.seat
        replacement.heuristic_score = cluster.heuristic_score
        updated.append(replacement)

    dead_wall = _describe(wall_tiles, frame)
    dead_wall.role = "dead_wall"
    dora = _describe(dora_tiles, frame)
    dora.role = "dora"
    clusters[:] = [dead_wall, dora, *updated]
    return dead_wall

def _axis_distance(point: tuple[float, float], cluster: Cluster) -> float:
    x = point[0] - cluster.centroid[0]
    y = point[1] - cluster.centroid[1]
    return abs(cluster.axis[0] * y - cluster.axis[1] * x)


def _components(
    clusters: list[Cluster],
    gap_k: float,
    *,
    radial_direction: tuple[float, float] | None = None,
) -> list[list[Cluster]]:
    remaining = list(clusters)
    groups: list[list[Cluster]] = []
    while remaining:
        group = [remaining.pop(0)]
        for cluster in group:
            neighbors = [
                candidate
                for candidate in remaining
                if 0.5 <= cluster.scale / candidate.scale <= 2
                and _cluster_gap(cluster, candidate)
                <= ((gap_k + 1) if radial_direction else gap_k)
                * max(cluster.scale, candidate.scale)
                and (
                    radial_direction is None
                    or _projected_gap(
                        cluster, candidate, radial_direction
                    )
                    <= max(cluster.scale, candidate.scale)
                )
            ]
            group.extend(neighbors)
            for neighbor in neighbors:
                remaining.remove(neighbor)
        groups.append(group)
    return groups


def _enforce_table_structure(
    clusters: list[Cluster],
    directions: dict[str, tuple[float, float]],
    center: tuple[float, float],
    frame: TableFrame | None,
    params: LayoutParams,
) -> None:
    """Свести результат к тому, что физически возможно на столе.

    Правила задают структуру жёстче любой эвристики:

    - §5: каждый игрок сбрасывает **в свой дискард**, и принадлежность сбросов
      обязана оставаться видимой. Значит сбросов не больше четырёх, по одному на
      место. Семь групп сброса означают, что кластеризация разорвала пул, а не
      что сбросов семь.
    - §4.2 и §7: мёртвая стена одна, в ней 14 тайлов; первый индикатор доры —
      третий сверху от края, каждый кан открывает следующий. Канов максимум
      четыре, поэтому дора — один ряд длиной от одного до пяти тайлов.

    Разорванные сбросы одного игрока сливаются; лишние wall/dora остаются
    нерешёнными, чтобы не выдавать физически невозможную структуру.
    """

    by_seat: dict[str | None, list[Cluster]] = {}
    for cluster in [c for c in clusters if c.role == "discard"]:
        seat = cluster.seat or _seat(cluster.centroid, center, directions)
        by_seat.setdefault(seat, []).append(cluster)
    for seat, parts in by_seat.items():
        if len(parts) > 1:
            _replace(clusters, parts, "discard", frame=frame, seat=seat)
        else:
            parts[0].seat = seat

    by_seat = {}
    for cluster in [c for c in clusters if c.role == "opponent_hand"]:
        by_seat.setdefault(cluster.seat, []).append(cluster)
    for seat, parts in by_seat.items():
        if len(parts) <= 1:
            continue
        if sum(part.tile_count for part in parts) <= params.hand_max_tiles:
            _replace(clusters, parts, "opponent_hand", frame=frame, seat=seat)

    walls = sorted(
        (cluster for cluster in clusters if cluster.role == "wall"),
        key=lambda cluster: cluster.tile_count,
        reverse=True,
    )
    for wall in walls[:4]:
        wall.seat = _seat(wall.centroid, center, directions)
    for extra in walls[4:]:
        extra.role, extra.seat = "other", None

    doras = sorted(
        (cluster for cluster in clusters if cluster.role == "dora"),
        key=lambda cluster: cluster.tile_count,
        reverse=True,
    )
    # Дора всегда в одном месте: вторую не выдумываем, а возвращаем в нерешённые.
    for extra in doras[1:]:
        extra.role, extra.seat = "other", None
    for cluster in clusters:
        if cluster.role == "dora" and not 1 <= cluster.tile_count <= DORA_MAX_TILES:
            cluster.role, cluster.seat = "other", None


def _merged(
    clusters: list[Cluster], frame: TableFrame | None = None
) -> Cluster:
    return _describe(
        tuple(tile for cluster in clusters for tile in cluster.tiles),
        frame,
    )


def _replace(
    clusters: list[Cluster],
    parts: list[Cluster],
    role: str,
    *,
    frame: TableFrame | None = None,
    seat: str | None = None,
) -> Cluster:
    merged = _merged(parts, frame)
    merged.role, merged.seat = role, seat
    clusters[:] = [
        merged,
        *(cluster for cluster in clusters if cluster not in parts),
    ]
    return merged


def _center(clusters: list[Cluster]) -> tuple[float, float]:
    return (
        median(cluster.centroid[0] for cluster in clusters),
        median(cluster.centroid[1] for cluster in clusters),
    )


def _normalize(direction: tuple[float, float]) -> tuple[float, float]:
    size = hypot(*direction)
    return direction[0] / size, direction[1] / size


def _seat_directions(
    player: tuple[float, float],
) -> dict[str, tuple[float, float]]:
    x, y = player
    return {
        "self": (x, y),
        "right": (y, -x),
        "opposite": (-x, -y),
        "left": (-y, x),
    }


def _seat(
    point: tuple[float, float],
    center: tuple[float, float],
    directions: dict[str, tuple[float, float]],
) -> str:
    delta = point[0] - center[0], point[1] - center[1]
    return max(
        directions,
        key=lambda seat: delta[0] * directions[seat][0]
        + delta[1] * directions[seat][1],
    )


def _projection(
    point: tuple[float, float], direction: tuple[float, float]
) -> float:
    return point[0] * direction[0] + point[1] * direction[1]


def _projected_gap(
    first: Cluster,
    second: Cluster,
    direction: tuple[float, float],
) -> float:
    def extent(cluster: Cluster) -> tuple[float, float]:
        left, top, right, bottom = cluster.bounds
        values = [
            _projection(point, direction)
            for point in (
                (left, top),
                (right, top),
                (right, bottom),
                (left, bottom),
            )
        ]
        return min(values), max(values)

    first_min, first_max = extent(first)
    second_min, second_max = extent(second)
    return max(first_min - second_max, second_min - first_max, 0)


def _cluster_gap(first: Cluster, second: Cluster) -> float:
    return _bounds_gap(first.bounds, second.bounds)


def _point(
    tile: TileBox, frame: TableFrame | None
) -> tuple[float, float]:
    return frame.map(tile.cx, tile.cy) if frame else (tile.cx, tile.cy)


def _tile_scale(tile: TileBox, frame: TableFrame | None) -> float:
    if frame is None:
        return tile.size
    center = frame.map(tile.cx, tile.cy)
    right = frame.map(tile.cx + tile.width / 2, tile.cy)
    bottom = frame.map(tile.cx, tile.cy + tile.height / 2)
    return (
        2 * hypot(right[0] - center[0], right[1] - center[1])
        + 2 * hypot(bottom[0] - center[0], bottom[1] - center[1])
    ) / 2


def _invert_3x3(matrix: tuple[float, ...]) -> tuple[float, ...]:
    a, b, c, d, e, f, g, h, i = matrix
    determinant = (
        a * (e * i - f * h)
        - b * (d * i - f * g)
        + c * (d * h - e * g)
    )
    if abs(determinant) < 1e-8:
        raise ValueError("table corners produce a singular transform")
    return tuple(
        value / determinant
        for value in (
            e * i - f * h,
            c * h - b * i,
            b * f - c * e,
            f * g - d * i,
            a * i - c * g,
            c * d - a * f,
            d * h - e * g,
            b * g - a * h,
            a * e - b * d,
        )
    )


def _infer_player_direction(
    clusters: list[Cluster],
) -> tuple[float, float]:
    center = _center(clusters)
    candidate = max(
        clusters,
        key=lambda cluster: cluster.tile_count * cluster.scale,
    )
    direction = (
        candidate.centroid[0] - center[0],
        candidate.centroid[1] - center[1],
    )
    return direction if hypot(*direction) else (0.0, 1.0)


def _face_count(cluster: Cluster, threshold: float) -> int:
    return sum(
        tile.face_score is not None and tile.face_score >= threshold
        for tile in cluster.tiles
    )


def _back_fraction(cluster: Cluster, threshold: float) -> float:
    observed = [
        tile.face_score for tile in cluster.tiles if tile.face_score is not None
    ]
    return (
        sum(score < threshold for score in observed) / len(observed)
        if observed
        else 0.0
    )


def _bounds_gap(
    first: tuple[float, float, float, float],
    second: tuple[float, float, float, float],
) -> float:
    horizontal = max(first[0] - second[2], second[0] - first[2], 0)
    vertical = max(first[1] - second[3], second[1] - first[3], 0)
    return hypot(horizontal, vertical)
