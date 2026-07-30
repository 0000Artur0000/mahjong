"""Geometry-first layout parser adapted from haitaks/mahjong."""

from dataclasses import dataclass
from math import atan2, cos, fsum, hypot, isfinite, sin
from statistics import median, pstdev

MAX_TILES = 256


@dataclass(frozen=True)
class TileBox:
    cx: float
    cy: float
    width: float
    height: float
    class_id: int | None = None

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
    hand_min_tiles: int = 3
    hand_max_tiles: int = 18
    hand_max_rows: int = 3
    hand_merge_gap_k: float = 6.0
    discard_min_tiles: int = 2
    dead_wall_min_tiles: int = 4
    dead_wall_max_tiles: int = 14
    wall_aspect: float = 1.6

    def __post_init__(self) -> None:
        if (
            not all(
                isfinite(value)
                for value in (
                    self.eps_k,
                    self.hand_merge_gap_k,
                    self.wall_aspect,
                )
            )
            or self.eps_k <= 0
            or not 1 <= self.min_samples <= MAX_TILES
            or not 1 <= self.hand_min_tiles <= self.hand_max_tiles
            or self.hand_max_rows < 1
            or self.hand_merge_gap_k <= 0
            or self.discard_min_tiles < 1
            or not 1 <= self.dead_wall_min_tiles <= self.dead_wall_max_tiles
            or self.wall_aspect <= 1
        ):
            raise ValueError("invalid layout parameters")


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

    @property
    def tile_count(self) -> int:
        return len(self.tiles)


@dataclass(frozen=True)
class LayoutResult:
    clusters: tuple[Cluster, ...]
    hand: Cluster | None
    discards: tuple[Cluster, ...] = ()
    walls: tuple[Cluster, ...] = ()
    others: tuple[Cluster, ...] = ()
    dead_wall: Cluster | None = None


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
    clusters = _cluster(tiles, config)
    hand, dead_wall = _assign_roles(clusters, config)
    return LayoutResult(
        tuple(clusters),
        hand,
        tuple(cluster for cluster in clusters if cluster.role == "discard"),
        tuple(
            cluster
            for cluster in clusters
            if cluster.role in {"wall", "dead_wall"}
        ),
        tuple(cluster for cluster in clusters if cluster.role == "other"),
        dead_wall,
    )


def _cluster(tiles: tuple[TileBox, ...], params: LayoutParams) -> list[Cluster]:
    # ponytail: O(n²) is bounded by MAX_TILES; add a spatial index only if profiling requires it.
    neighbors = [
        [
            other
            for other, candidate in enumerate(tiles)
            if hypot(tile.cx - candidate.cx, tile.cy - candidate.cy)
            < params.eps_k * (tile.size + candidate.size) / 2
        ]
        for tile in tiles
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
    clusters = [_describe(tuple(group)) for group in groups + singletons]
    return sorted(clusters, key=lambda cluster: cluster.tile_count, reverse=True)


def _describe(tiles: tuple[TileBox, ...]) -> Cluster:
    xs = [tile.cx for tile in tiles]
    ys = [tile.cy for tile in tiles]
    scale = median(tile.size for tile in tiles)
    axis, linearity = _principal_axis(xs, ys)
    tiles = _order_tiles(tiles, axis, scale)
    along = [x * axis[0] + y * axis[1] for x, y in zip(xs, ys, strict=True)]
    across = [-x * axis[1] + y * axis[0] for x, y in zip(xs, ys, strict=True)]
    return Cluster(
        tiles,
        (fsum(xs) / len(xs), fsum(ys) / len(ys)),
        (
            min(tile.cx - tile.width / 2 for tile in tiles),
            min(tile.cy - tile.height / 2 for tile in tiles),
            max(tile.cx + tile.width / 2 for tile in tiles),
            max(tile.cy + tile.height / 2 for tile in tiles),
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
) -> tuple[TileBox, ...]:
    rows: list[list[tuple[float, float, TileBox]]] = []
    projected = sorted(
        [
            (
                -tile.cx * axis[1] + tile.cy * axis[0],
                tile.cx * axis[0] + tile.cy * axis[1],
                tile,
            )
            for tile in tiles
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
    clusters: list[Cluster], params: LayoutParams
) -> tuple[Cluster | None, Cluster | None]:
    scene_scale = median(cluster.scale for cluster in clusters)
    candidates = [
        (score, cluster)
        for cluster in clusters
        if (score := _hand_score(cluster, scene_scale, params)) is not None
    ]
    hand = None
    if candidates:
        score, hand = max(candidates, key=lambda item: item[0])
        parts = [hand]
        merged = hand
        while True:
            companion = next(
                (
                    cluster
                    for cluster in clusters
                    if cluster not in parts
                    and _is_hand_companion(merged, cluster, params)
                ),
                None,
            )
            if companion is None:
                break
            parts.append(companion)
            merged = _describe(
                tuple(tile for cluster in parts for tile in cluster.tiles)
            )
        merged.role, merged.heuristic_score = "hand", score
        clusters[:] = [
            merged,
            *(cluster for cluster in clusters if cluster not in parts),
        ]
        hand = merged

    wall_candidates = [
        (score, cluster)
        for cluster in clusters
        if cluster is not hand
        and (score := _dead_wall_score(cluster, params)) is not None
    ]
    dead_wall = None
    if wall_candidates:
        score, dead_wall = max(wall_candidates, key=lambda item: item[0])
        dead_wall.role, dead_wall.heuristic_score = "dead_wall", score

    for cluster in clusters:
        if cluster.role != "other":
            continue
        if cluster.tile_count >= params.discard_min_tiles:
            cluster.role, cluster.heuristic_score = "discard", cluster.regularity
    return hand, dead_wall


def _hand_score(
    cluster: Cluster, scene_scale: float, params: LayoutParams
) -> float | None:
    if (
        cluster.tile_count < params.hand_min_tiles
        or cluster.rows > params.hand_max_rows
    ):
        return None
    edge = 1 - 2 * _edge_distance(cluster)
    count = min(1.0, cluster.tile_count / 13)
    relative_scale = cluster.scale / (cluster.scale + scene_scale)
    shape = (cluster.regularity + cluster.linearity) / 2
    overflow = max(0, cluster.tile_count - params.hand_max_tiles) / params.hand_max_tiles
    return (
        0.35 * count
        + 0.25 * relative_scale
        + 0.25 * edge
        + 0.15 * shape
        - 0.25 * overflow
    )


def _is_hand_companion(
    hand: Cluster, candidate: Cluster, params: LayoutParams
) -> bool:
    if hand.tile_count + candidate.tile_count > params.hand_max_tiles:
        return False
    sizes = hand.scale / candidate.scale
    return (
        0.5 <= sizes <= 2
        and _bounds_gap(hand.bounds, candidate.bounds)
        <= params.hand_merge_gap_k * max(hand.scale, candidate.scale)
        and abs(_edge_distance(hand) - _edge_distance(candidate))
        <= params.hand_merge_gap_k * max(hand.scale, candidate.scale)
    )


def _edge_distance(cluster: Cluster) -> float:
    left, top, right, bottom = cluster.bounds
    return min(left, top, 1 - right, 1 - bottom)


def _bounds_gap(
    first: tuple[float, float, float, float],
    second: tuple[float, float, float, float],
) -> float:
    horizontal = max(first[0] - second[2], second[0] - first[2], 0)
    vertical = max(first[1] - second[3], second[1] - first[3], 0)
    return hypot(horizontal, vertical)


def _dead_wall_score(
    cluster: Cluster, params: LayoutParams
) -> float | None:
    if (
        not params.dead_wall_min_tiles
        <= cluster.tile_count
        <= params.dead_wall_max_tiles
        or cluster.rows > 2
    ):
        return None
    shape = median(max(tile.aspect, 1 / tile.aspect) for tile in cluster.tiles)
    if shape < params.wall_aspect:
        return None
    return (
        0.45 * (cluster.rows == 2)
        + 0.35 * shape / (shape + 1)
        + 0.20 * cluster.regularity
    )
