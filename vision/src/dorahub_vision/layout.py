"""Geometry-first layout parser adapted from haitaks/mahjong."""

from dataclasses import dataclass, field
from math import atan2, cos, fsum, hypot, isfinite, sin
from statistics import median, pstdev

MAX_TILES = 256
MAX_DISCARD_ROW_TILES = 6


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
                    and 0 <= value <= 1
                    for value in point
                )
                for point in self.corners
            )
        ):
            raise ValueError("table corners must be normalized TL, TR, BR, BL")
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
            or self.hand_max_rows < 1
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
    has_face_evidence = any(
        tile.face_score is not None
        for cluster in clusters
        for tile in cluster.tiles
    )
    if frame:
        for cluster in clusters:
            if not (
                -0.1 <= cluster.centroid[0] <= 1.1
                and -0.1 <= cluster.centroid[1] <= 1.1
            ):
                cluster.role = "noise"
    walls = [
        cluster
        for cluster in clusters
        if cluster.role == "other"
        and cluster.tile_count >= params.dead_wall_min_tiles
        and (
            cluster.tile_count > params.dead_wall_min_tiles
            or _face_count(cluster, params.face_threshold) > 0
        )
        and cluster.rows <= 2
        and cluster.linearity >= 0.6
        and _back_fraction(cluster, params.face_threshold) > 0.5
    ]
    for wall in walls:
        wall.role, wall.heuristic_score = "wall", _back_fraction(
            wall, params.face_threshold
        )

    dead_wall = max(
        (
            wall
            for wall in walls
            if wall.tile_count <= params.dead_wall_max_tiles
            and 1 <= _face_count(wall, params.face_threshold) <= 5
        ),
        key=lambda wall: sum(tile.face_score or 0 for tile in wall.tiles),
        default=None,
    )
    if dead_wall is not None:
        dead_wall.role = "dead_wall"

    dora = min(
        (
            cluster
            for cluster in clusters
            if cluster.role == "other"
            and 2 <= cluster.tile_count <= 5
            and _back_fraction(cluster, params.face_threshold) > 0.5
            and _face_count(cluster, params.face_threshold) > 0
            and walls
            and min(_cluster_gap(cluster, wall) for wall in walls)
            <= 4 * max(cluster.scale, median(wall.scale for wall in walls))
        ),
        key=lambda cluster: min(_cluster_gap(cluster, wall) for wall in walls),
        default=None,
    )
    if dora is not None:
        dora.role = "dora"

    direction = _normalize(
        params.player_direction or _infer_player_direction(clusters)
    )
    available = [cluster for cluster in clusters if cluster.role == "other"]
    hand_candidates = [
        cluster
        for cluster in available
        if not has_face_evidence
        or not any(tile.face_score is not None for tile in cluster.tiles)
        or _face_count(cluster, params.face_threshold) > 0
    ]
    hand_groups = [
        parts
        for parts in _components(
            hand_candidates,
            params.hand_merge_gap_k,
            radial_direction=direction,
        )
        if sum(cluster.tile_count for cluster in parts) >= params.hand_min_tiles
        and _merged(parts, frame).rows <= params.hand_max_rows
    ]
    hand = None
    if hand_groups:
        parts = max(
            hand_groups,
            key=lambda group: (
                _projection(_merged(group, frame).centroid, direction)
                + _merged(group, frame).scale
                * min(sum(cluster.tile_count for cluster in group), 14)
                / 2
            ),
        )
        hand = _replace(
            clusters, parts, "hand", frame=frame, seat="self"
        )

    available = [cluster for cluster in clusters if cluster.role == "other"]
    if not available:
        return hand, dead_wall
    face_clusters = [
        cluster
        for cluster in available
        if _face_count(cluster, params.face_threshold) > 0
    ]
    radial_clusters = face_clusters or available
    center = _center(radial_clusters)
    directions = _seat_directions(direction)
    for seat in ("left", "opposite", "right"):
        seat_direction = directions[seat]
        opponent_groups = [
            *_components(
                [
                    cluster
                    for cluster in clusters
                    if cluster.role in {"other", "wall"}
                    and _face_count(cluster, params.face_threshold) > 0
                ],
                params.hand_merge_gap_k,
                radial_direction=seat_direction,
            ),
            *_components(
                [
                    cluster
                    for cluster in clusters
                    if cluster.role in {"other", "wall"}
                    and _face_count(cluster, params.face_threshold) == 0
                ],
                params.hand_merge_gap_k,
                radial_direction=seat_direction,
            ),
        ]
        scene_radius = max(
            0.5,
            2
            * median(
                hypot(
                    cluster.centroid[0] - center[0],
                    cluster.centroid[1] - center[1],
                )
                for cluster in radial_clusters
            )
        )
        candidates = []
        for parts in opponent_groups:
            merged = _merged(parts, frame)
            inner_walls = [
                wall
                for wall in walls
                if wall not in parts
                and wall is not dead_wall
                and _seat(wall.centroid, center, directions) == seat
            ]
            wall_barrier = max(
                (
                    _projection(wall.centroid, seat_direction)
                    - _projection(center, seat_direction)
                    for wall in inner_walls
                ),
                default=0.0,
            )
            outward = _projection(merged.centroid, seat_direction) - _projection(
                center, seat_direction
            )
            tangent = -seat_direction[1], seat_direction[0]
            sideways = abs(
                _projection(merged.centroid, tangent)
                - _projection(center, tangent)
            )
            edge_seed = (
                (seat == "left" and merged.centroid[0] <= 0.15)
                or (seat == "right" and merged.centroid[0] >= 0.85)
                or (seat == "opposite" and merged.centroid[1] <= 0.15)
            )
            visible = _face_count(merged, params.face_threshold) > 0
            table_edge = min(
                merged.centroid[0],
                merged.centroid[1],
                1 - merged.centroid[0],
            ) <= 0.15
            visible_seed = visible and (
                (
                    edge_seed
                    and (
                        merged.tile_count <= 4
                        or any(part.role == "wall" for part in parts)
                    )
                )
                or (
                    table_edge
                    and any(part.role == "wall" for part in parts)
                )
            )
            closed_seed = (
                merged.tile_count >= 6
                and wall_barrier
                and outward > wall_barrier + merged.scale
            ) or (
                merged.tile_count >= 10
                and edge_seed
            )
            if (
                (
                    2 <= merged.tile_count <= params.hand_max_tiles
                    if visible and edge_seed
                    else 3 <= merged.tile_count <= params.hand_max_tiles
                )
                and (
                    visible_seed
                    or closed_seed
                )
                and outward <= scene_radius
                and (
                    seat == "opposite"
                    or outward >= 1.5 * sideways
                )
                and abs(
                    merged.axis[0] * seat_direction[0]
                    + merged.axis[1] * seat_direction[1]
                )
                <= 0.65
            ):
                candidates.append((outward, parts))
        for _, parts in sorted(candidates, reverse=True, key=lambda item: item[0]):
            if not all(part in clusters for part in parts):
                continue
            _replace(
                clusters,
                parts,
                "opponent_hand",
                frame=frame,
                seat=seat,
            )

    _promote_edge_hands(
        clusters,
        params,
        directions,
        frame,
        center,
    )

    discard_candidates = [
        cluster
        for cluster in clusters
        if cluster.role == "other"
        and cluster.tile_count >= params.discard_min_tiles
        and _face_count(cluster, params.face_threshold) > 0
    ]
    if frame:
        for cluster in discard_candidates:
            if not 0.1 <= cluster.centroid[0] <= 0.9:
                cluster.role = "noise"
        discard_candidates = [
            cluster
            for cluster in discard_candidates
            if cluster.role == "other"
        ]
    if discard_candidates:
        discard_center = _center(discard_candidates)
        distances = [
            hypot(
                cluster.centroid[0] - discard_center[0],
                cluster.centroid[1] - discard_center[1],
            )
            for cluster in discard_candidates
        ]
        limit = max(
            params.discard_outlier_k * median(distances),
            params.hand_merge_gap_k
            * median(cluster.scale for cluster in discard_candidates),
        )
        for cluster, distance in zip(
            discard_candidates, distances, strict=True
        ):
            if distance <= limit:
                cluster.seat = _seat(
                    cluster.centroid, discard_center, directions
                )
                cluster.role, cluster.heuristic_score = (
                    "discard",
                    1 - distance / limit if limit else 1.0,
                )
            else:
                cluster.role = "noise"
    if dead_wall is not None:
        _, dead_wall = _split_dead_wall_dora(
            clusters, dead_wall, params, frame
        )
    return hand, dead_wall


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


def _opponent_edge(point: tuple[float, float]) -> tuple[str, float]:
    distances = {
        "left": point[0],
        "opposite": point[1],
        "right": 1 - point[0],
    }
    seat = min(distances, key=distances.get)
    return seat, distances[seat]


def _split_dead_wall_dora(
    clusters: list[Cluster],
    dead_wall: Cluster,
    params: LayoutParams,
    frame: TableFrame | None,
) -> tuple[Cluster | None, Cluster]:
    faces = tuple(
        tile
        for tile in dead_wall.tiles
        if tile.face_score is not None
        and tile.face_score >= params.face_threshold
    )
    backs = tuple(tile for tile in dead_wall.tiles if tile not in faces)
    if not faces or len(backs) < 2:
        return None, dead_wall
    back_wall = _describe(backs, frame)
    dora_tiles = tuple(
        tile
        for tile in faces
        if _axis_distance(_point(tile, frame), back_wall)
        <= 0.7 * back_wall.scale
    )
    if not dora_tiles:
        return None, dead_wall
    dora = _describe(dora_tiles, frame)
    dora.role = "dora"
    back_wall.role = "dead_wall"
    back_wall.heuristic_score = dead_wall.heuristic_score
    leftovers = tuple(tile for tile in faces if tile not in dora_tiles)
    clusters[:] = [
        back_wall if cluster is dead_wall else cluster
        for cluster in clusters
    ]
    clusters.append(dora)
    if leftovers:
        clusters.append(_describe(leftovers, frame))
    return dora, back_wall


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
