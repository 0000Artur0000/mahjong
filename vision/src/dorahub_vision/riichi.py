"""Riichi tile vocabulary and model-output trust boundary."""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from math import isfinite

from .layout import MAX_TILES, TileBox

NUMBERED_TILES = tuple(f"{rank}{suit}" for suit in "mps" for rank in range(1, 10))
HONOR_TILES = ("E", "S", "W", "N", "P", "F", "C")
RED_FIVES = ("0m", "0p", "0s")
MODEL_TILES = NUMBERED_TILES + HONOR_TILES + RED_FIVES
FACE_TILES = frozenset(MODEL_TILES)
NON_FACE_TILES = frozenset(("back", "unknown"))
ROLES = frozenset(("hand", "dora", "discard"))

_ALIASES = {
    "east": "E",
    "south": "S",
    "west": "W",
    "north": "N",
    "haku": "P",
    "white": "P",
    "hatsu": "F",
    "green": "F",
    "chun": "C",
    "red": "C",
}


def normalize_tile(label: str) -> str:
    """Normalize common Riichi dataset labels to mjai tile notation."""

    if not isinstance(label, str) or not (value := label.strip()):
        raise ValueError("tile label must be a non-empty string")
    if value in FACE_TILES or value in NON_FACE_TILES:
        return value
    if value.upper() in HONOR_TILES:
        return value.upper()
    lowered = value.lower()
    if lowered in _ALIASES:
        return _ALIASES[lowered]
    compact = lowered.replace("_", "")
    if (
        len(compact) == 2
        and compact[0] in "mps"
        and compact[1] in "0123456789"
    ):
        return compact[1] + compact[0]
    if (
        len(compact) == 2
        and (
            (compact[0] == "z" and compact[1] in "1234567")
            or (compact[1] == "z" and compact[0] in "1234567")
        )
    ):
        rank = compact[1] if compact[0] == "z" else compact[0]
        return HONOR_TILES[int(rank) - 1]
    raise ValueError(f"unsupported Riichi tile label: {label!r}")


def dora_from_indicator(indicator: str) -> str:
    """Return the visible dora for a standard Riichi indicator."""

    tile = normalize_tile(indicator)
    if tile not in FACE_TILES:
        raise ValueError("dora indicator must be a face tile")
    if tile[-1:] in {"m", "p", "s"}:
        rank = 5 if tile[0] == "0" else int(tile[0])
        return f"{rank % 9 + 1}{tile[1]}"
    cycle = ("E", "S", "W", "N") if tile in "ESWN" else ("P", "F", "C")
    return cycle[(cycle.index(tile) + 1) % len(cycle)]


@dataclass(frozen=True)
class TileCandidate:
    tile: str
    confidence: float

    def __post_init__(self) -> None:
        if self.tile not in FACE_TILES | NON_FACE_TILES:
            raise ValueError("candidate tile must be canonical")
        if (
            isinstance(self.confidence, bool)
            or not isinstance(self.confidence, int | float)
            or not isfinite(self.confidence)
            or not 0 <= self.confidence <= 1
        ):
            raise ValueError("candidate confidence must be finite and within [0, 1]")


@dataclass(frozen=True)
class RiichiDetection:
    tile: str
    confidence: float
    box: TileBox
    role: str
    alternatives: tuple[TileCandidate, ...] = ()

    def __post_init__(self) -> None:
        TileCandidate(self.tile, self.confidence)
        if not isinstance(self.box, TileBox):
            raise ValueError("box must be TileBox")
        if self.role not in ROLES:
            raise ValueError(f"role must be one of {sorted(ROLES)}")
        if len(self.alternatives) > 3 or any(
            not isinstance(candidate, TileCandidate) for candidate in self.alternatives
        ):
            raise ValueError("alternatives must contain at most three TileCandidate values")
        tiles = (self.tile, *(candidate.tile for candidate in self.alternatives))
        if len(tiles) != len(set(tiles)):
            raise ValueError("primary tile and alternatives must be unique")
        if tuple(sorted(self.alternatives, key=lambda item: item.confidence, reverse=True)) != (
            self.alternatives
        ):
            raise ValueError("alternatives must be sorted by descending confidence")

    @classmethod
    def from_model(cls, payload: Mapping[str, object]) -> "RiichiDetection":
        """Parse one untrusted detector/classifier payload."""

        if not isinstance(payload, Mapping):
            raise ValueError("model detection must be an object")
        raw_box = payload.get("box")
        if (
            not isinstance(raw_box, list | tuple)
            or len(raw_box) != 4
            or any(
                isinstance(value, bool) or not isinstance(value, int | float)
                for value in raw_box
            )
        ):
            raise ValueError("box must be [cx, cy, width, height]")
        raw_alternatives = payload.get("alternatives", ())
        if not isinstance(raw_alternatives, list | tuple):
            raise ValueError("alternatives must be an array")
        alternatives = []
        for raw in raw_alternatives:
            if not isinstance(raw, Mapping):
                raise ValueError("alternative must be an object")
            alternatives.append(
                TileCandidate(
                    normalize_tile(raw.get("tile")),  # type: ignore[arg-type]
                    raw.get("confidence"),  # type: ignore[arg-type]
                )
            )
        return cls(
            normalize_tile(payload.get("tile")),  # type: ignore[arg-type]
            payload.get("confidence"),  # type: ignore[arg-type]
            TileBox(*(float(value) for value in raw_box)),
            payload.get("role"),  # type: ignore[arg-type]
            tuple(alternatives),
        )


def build_vision_result(
    detections: Iterable[RiichiDetection],
    *,
    model_version: str,
    minimum_confidence: float,
) -> dict[str, object]:
    """Build schema-v2 output for a human-confirmed Riichi photo workflow."""

    items = tuple(detections)
    if (
        not isinstance(model_version, str)
        or not model_version.strip()
        or isinstance(minimum_confidence, bool)
        or not isinstance(minimum_confidence, int | float)
        or not isfinite(minimum_confidence)
        or not 0 <= minimum_confidence <= 1
    ):
        raise ValueError("model version and minimum confidence are required")
    if len(items) > MAX_TILES or any(
        not isinstance(detection, RiichiDetection) for detection in items
    ):
        raise ValueError(f"result accepts at most {MAX_TILES} RiichiDetection values")

    grouped = {
        role: sorted(
            (item for item in items if item.role == role),
            key=(lambda item: item.box.cx)
            if role != "discard"
            else (lambda item: (item.box.cy, item.box.cx)),
        )
        for role in ROLES
    }
    face_grouped = {
        role: [item for item in grouped[role] if item.tile in FACE_TILES]
        for role in ROLES
    }
    warnings = []
    if not face_grouped["hand"]:
        warnings.append("missing_hand")
    if len(grouped["hand"]) > 18:
        warnings.append("too_many_hand_tiles")
    if len(grouped["dora"]) > 5:
        warnings.append("too_many_dora_indicators")
    if any(item.tile in NON_FACE_TILES for item in items):
        warnings.append("unclassified_tile")
    if any(item.confidence < minimum_confidence for item in items):
        warnings.append("low_confidence")

    rejected = bool(warnings)
    indicators = [item.tile for item in face_grouped["dora"]]
    return {
        "schemaVersion": 2,
        "modelVersion": model_version.strip(),
        "status": "rejected" if rejected else "accepted",
        "detections": [_serialize(item) for item in items],
        "zones": {
            "hand": [item.tile for item in face_grouped["hand"]],
            "doraIndicators": indicators,
            "doras": [dora_from_indicator(tile) for tile in indicators],
            "discards": [item.tile for item in face_grouped["discard"]],
        },
        "warnings": warnings,
        "rejectionReason": warnings[0] if warnings else None,
    }


def _serialize(detection: RiichiDetection) -> dict[str, object]:
    return {
        "tile": detection.tile,
        "confidence": detection.confidence,
        "box": [
            detection.box.cx,
            detection.box.cy,
            detection.box.width,
            detection.box.height,
        ],
        "role": detection.role,
        "alternatives": [
            {"tile": candidate.tile, "confidence": candidate.confidence}
            for candidate in detection.alternatives
        ],
    }
