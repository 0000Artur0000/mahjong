"""Geometry-first layout parser adapted from haitaks/mahjong."""

from dataclasses import dataclass
from math import fsum, hypot, isfinite
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
    hand_y_min: float = 0.40
    hand_max_tiles: int = 18
    hand_max_rows: int = 3
    discard_min_tiles: int = 2
    wall_aspect: float = 1.5
    hand_y_weight: float = 0.6
    hand_regularity_weight: float = 0.4

    def __post_init__(self) -> None:
        if (
            not all(
                isfinite(value)
                for value in (
                    self.eps_k,
                    self.hand_y_min,
                    self.wall_aspect,
                    self.hand_y_weight,
                    self.hand_regularity_weight,
                )
            )
            or self.eps_k <= 0
            or not 1 <= self.min_samples <= MAX_TILES
            or not 0 <= self.hand_y_min < 1
            or self.hand_max_tiles < 1
            or self.hand_max_rows < 1
            or self.discard_min_tiles < 1
            or self.wall_aspect <= 0
            or self.hand_y_weight < 0
            or self.hand_regularity_weight < 0
            or self.hand_y_weight + self.hand_regularity_weight == 0
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
    _assign_roles(clusters, config)
    return LayoutResult(
        tuple(clusters),
        next((cluster for cluster in clusters if cluster.role == "hand"), None),
        tuple(cluster for cluster in clusters if cluster.role == "discard"),
        tuple(cluster for cluster in clusters if cluster.role == "wall"),
        tuple(cluster for cluster in clusters if cluster.role == "other"),
    )


def _cluster(tiles: tuple[TileBox, ...], params: LayoutParams) -> list[Cluster]:
    scale = median(tile.size for tile in tiles)
    radius = params.eps_k * scale
    # ponytail: O(n²) is bounded by MAX_TILES; add a spatial index only if profiling requires it.
    neighbors = [
        [
            other
            for other, candidate in enumerate(tiles)
            if hypot(tile.cx - candidate.cx, tile.cy - candidate.cy) < radius
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
    clusters = [_describe(tuple(group), scale) for group in groups + singletons]
    return sorted(clusters, key=lambda cluster: cluster.tile_count, reverse=True)


def _describe(tiles: tuple[TileBox, ...], scale: float) -> Cluster:
    xs = [tile.cx for tile in tiles]
    ys = [tile.cy for tile in tiles]
    horizontal = sum(tile.width >= tile.height for tile in tiles) >= len(tiles) / 2
    orientation = "horizontal" if horizontal else "vertical"
    return Cluster(
        tiles,
        (fsum(xs) / len(xs), fsum(ys) / len(ys)),
        (
            min(tile.cx - tile.width / 2 for tile in tiles),
            min(tile.cy - tile.height / 2 for tile in tiles),
            max(tile.cx + tile.width / 2 for tile in tiles),
            max(tile.cy + tile.height / 2 for tile in tiles),
        ),
        _count_bands(ys, scale),
        _count_bands(xs, scale),
        _regularity(tiles, orientation),
        orientation,
    )


def _count_bands(values: list[float], scale: float) -> int:
    ordered = sorted(values)
    return 1 + sum(
        current - previous > 0.6 * scale
        for previous, current in zip(ordered, ordered[1:])
    )


def _regularity(tiles: tuple[TileBox, ...], orientation: str) -> float:
    if len(tiles) < 3:
        return 1.0
    xs = [tile.cx for tile in tiles]
    ys = [tile.cy for tile in tiles]
    coordinates = ys if orientation == "vertical" and pstdev(ys) > pstdev(xs) else xs
    ordered = sorted(coordinates)
    gaps = [
        current - previous
        for previous, current in zip(ordered, ordered[1:])
    ]
    mean_gap = fsum(gaps) / len(gaps)
    return max(0.0, min(1.0, 1 - pstdev(gaps) / mean_gap)) if mean_gap else 1.0


def _assign_roles(clusters: list[Cluster], params: LayoutParams) -> None:
    candidates = [
        (score, cluster)
        for cluster in clusters
        if (score := _hand_score(cluster, params)) is not None
    ]
    if candidates:
        score, hand = max(candidates, key=lambda item: item[0])
        hand.role, hand.heuristic_score = "hand", score
    for cluster in clusters:
        if cluster.role != "other":
            continue
        aspect = median(tile.aspect for tile in cluster.tiles)
        if cluster.dominant_orientation == "vertical" and aspect > params.wall_aspect:
            cluster.role = "wall"
            cluster.heuristic_score = min(
                1.0, (aspect - params.wall_aspect) / 2 + 0.5
            )
        elif cluster.tile_count >= params.discard_min_tiles:
            cluster.role, cluster.heuristic_score = "discard", 0.5


def _hand_score(cluster: Cluster, params: LayoutParams) -> float | None:
    y = cluster.centroid[1]
    if (
        y < params.hand_y_min
        or cluster.rows > params.hand_max_rows
        or cluster.tile_count > params.hand_max_tiles
    ):
        return None
    low = (y - params.hand_y_min) / (1 - params.hand_y_min)
    weight = params.hand_y_weight + params.hand_regularity_weight
    return (
        params.hand_y_weight * low
        + params.hand_regularity_weight * cluster.regularity
    ) / weight
