import json
import tempfile
import unittest
from pathlib import Path

from vision.scripts.score_layout import iou, score, seed


def box(cx, cy):
    return [cx, cy, 0.02, 0.03]


def groups_file(directory: Path, groups: list[dict], image="a.jpg") -> Path:
    path = directory / "groups.json"
    path.write_text(json.dumps([{"image": image, "groups": groups}]))
    return path


def truth_file(directory: Path, groups: list[dict], reviewed=True, stem="a") -> Path:
    path = directory / f"{stem}.json"
    path.write_text(
        json.dumps({"image": f"{stem}.jpg", "reviewed": reviewed, "groups": groups})
    )
    return path


class ScoreLayoutTest(unittest.TestCase):
    def test_iou_is_one_for_identical_boxes_and_zero_when_apart(self):
        self.assertAlmostEqual(iou(box(0.5, 0.5), box(0.5, 0.5)), 1.0)
        self.assertEqual(iou(box(0.1, 0.1), box(0.9, 0.9)), 0.0)

    def test_counts_matching_roles_as_correct(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            groups = groups_file(
                root,
                [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}],
            )
            truth_file(
                root,
                [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}],
            )

            report = score(groups, root)

            self.assertEqual(report["roles"]["hand"]["precision"]["value"], 1.0)
            self.assertEqual(report["roles"]["hand"]["recall"]["value"], 1.0)
            self.assertEqual(report["falseRole"]["value"], 0.0)

    def test_wrong_role_counts_as_false_role_not_as_silence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            groups = groups_file(
                root, [{"role": "wall", "seat": None, "tileBoxes": [box(0.5, 0.5)]}]
            )
            truth_file(
                root, [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}]
            )

            report = score(groups, root)

            self.assertEqual(report["falseRole"]["value"], 1.0)
            self.assertEqual(report["silence"]["value"], 0.0)
            self.assertEqual(report["roles"]["hand"]["recall"]["value"], 0.0)

    def test_giving_up_counts_as_silence_not_as_false_role(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            groups = groups_file(
                root, [{"role": "other", "seat": None, "tileBoxes": [box(0.5, 0.5)]}]
            )
            truth_file(
                root, [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}]
            )

            report = score(groups, root)

            self.assertEqual(report["silence"]["value"], 1.0)
            self.assertIsNone(report["falseRole"]["value"], "нет уверенных ответов")

    def test_unreviewed_truth_is_not_scored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            groups = groups_file(
                root, [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}]
            )
            truth_file(
                root,
                [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}],
                reviewed=False,
            )

            report = score(groups, root)

            self.assertEqual(report["images"]["unreviewed"], ["a"])
            self.assertEqual(report["images"]["scored"], [])
            self.assertIsNone(report["falseRole"]["value"])

    def test_tile_without_a_matching_box_is_reported_as_missed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            groups = groups_file(
                root, [{"role": "hand", "seat": "self", "tileBoxes": [box(0.1, 0.1)]}]
            )
            truth_file(
                root,
                [
                    {
                        "role": "hand",
                        "seat": "self",
                        "tileBoxes": [box(0.1, 0.1), box(0.9, 0.9)],
                    }
                ],
            )

            report = score(groups, root)

            self.assertEqual(report["missedTiles"]["numerator"], 1)
            self.assertEqual(report["missedTiles"]["denominator"], 2)

    def test_unknown_in_truth_is_skipped_instead_of_penalised(self):
        # Размечающий сам не разобрал зону: считать это ошибкой алгоритма нечестно.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            groups = groups_file(
                root,
                [
                    {"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]},
                    {"role": "wall", "seat": None, "tileBoxes": [box(0.2, 0.2)]},
                ],
            )
            truth_file(
                root,
                [
                    {"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]},
                    {"role": "unknown", "seat": None, "tileBoxes": [box(0.2, 0.2)]},
                ],
            )

            report = score(groups, root)

            self.assertEqual(report["undecidedTiles"], 1)
            self.assertEqual(report["falseRole"]["denominator"], 1, "спорный тайл не в счёт")
            self.assertEqual(report["falseRole"]["value"], 0.0)

    def test_seed_marks_draft_unreviewed_and_keeps_existing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "truth"
            groups = groups_file(
                root, [{"role": "hand", "seat": "self", "tileBoxes": [box(0.5, 0.5)]}]
            )

            written = seed(groups, out)
            draft = json.loads((out / "a.json").read_text())
            self.assertEqual(len(written), 1)
            self.assertFalse(draft["reviewed"])

            draft["reviewed"] = True
            (out / "a.json").write_text(json.dumps(draft))
            self.assertEqual(seed(groups, out), [], "проверенный эталон не затирается")


if __name__ == "__main__":
    unittest.main()
