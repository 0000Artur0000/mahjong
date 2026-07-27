"""Versioned product metrics for Vision candidates."""

from dataclasses import dataclass
from math import ceil, isfinite
from typing import Iterable


@dataclass(frozen=True)
class SceneEvaluation:
    """One scene after detector matching against ground truth."""

    truth: tuple[str, ...]
    predictions: tuple[str | None, ...]
    false_detections: int
    group_results: tuple[bool, ...]
    confidence: float
    corrected_tiles: int
    confirmation_ms: int
    reshoot: bool

    def __post_init__(self) -> None:
        if (
            not self.truth
            or not self.group_results
            or len(self.truth) != len(self.predictions)
            or any(not tile for tile in self.truth)
            or any(tile == "" for tile in self.predictions)
            or self.false_detections < 0
            or not isfinite(self.confidence)
            or not 0 <= self.confidence <= 1
            or not 0 <= self.corrected_tiles <= len(self.truth) + self.false_detections
            or self.confirmation_ms < 0
        ):
            raise ValueError("invalid scene evaluation")


def evaluate(
    scenes: Iterable[SceneEvaluation], acceptance_threshold: float
) -> dict[str, object]:
    """Calculate the ML-01 report; a missing denominator produces null."""

    if not isfinite(acceptance_threshold) or not 0 <= acceptance_threshold <= 1:
        raise ValueError("acceptance_threshold must be between 0 and 1")
    rows = tuple(scenes)
    accepted = tuple(row for row in rows if row.confidence >= acceptance_threshold)
    exact = tuple(
        not row.false_detections
        and row.predictions == row.truth
        and all(row.group_results)
        for row in rows
    )
    detected = sum(prediction is not None for row in rows for prediction in row.predictions)
    false_detections = sum(row.false_detections for row in rows)
    truth_tiles = sum(len(row.truth) for row in rows)
    correctly_classified = sum(
        prediction == truth
        for row in rows
        for truth, prediction in zip(row.truth, row.predictions, strict=True)
        if prediction is not None
    )
    correct_groups = sum(sum(row.group_results) for row in rows)
    total_groups = sum(len(row.group_results) for row in rows)
    confirmation_times = sorted(row.confirmation_ms for row in rows)

    return {
        "schemaVersion": 1,
        "acceptanceThreshold": acceptance_threshold,
        "counts": {
            "scenes": len(rows),
            "acceptedScenes": len(accepted),
            "rejectedScenes": len(rows) - len(accepted),
        },
        "rates": {
            "automationCoverage": _rate(len(accepted), len(rows)),
            "falseAccept": _rate(
                sum(
                    not is_exact
                    for row, is_exact in zip(rows, exact, strict=True)
                    if row.confidence >= acceptance_threshold
                ),
                len(accepted),
            ),
            "exactHand": _rate(sum(exact), len(rows)),
            "detectionPrecision": _rate(detected, detected + false_detections),
            "detectionRecall": _rate(detected, truth_tiles),
            "classificationAccuracy": _rate(correctly_classified, detected),
            "groupingAccuracy": _rate(correct_groups, total_groups),
            "reshoot": _rate(sum(row.reshoot for row in rows), len(rows)),
        },
        "ux": {
            "meanCorrectedTiles": (
                sum(row.corrected_tiles for row in rows) / len(rows) if rows else None
            ),
            "confirmationMsP50": _percentile(confirmation_times, 0.50),
            "confirmationMsP90": _percentile(confirmation_times, 0.90),
        },
    }


def _rate(numerator: int, denominator: int) -> dict[str, int | float | None]:
    return {
        "numerator": numerator,
        "denominator": denominator,
        "value": numerator / denominator if denominator else None,
    }


def _percentile(values: list[int], percentile: float) -> int | None:
    return values[max(0, ceil(percentile * len(values)) - 1)] if values else None
