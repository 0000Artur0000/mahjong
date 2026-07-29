# Riichi model smoke training

Synthetic data checks the 37-class pipeline; it is not a validation set and
does not prove accuracy on physical tiles.

```bash
git clone https://github.com/FluffyStuff/riichi-mahjong-tiles /tmp/riichi-tiles
git -C /tmp/riichi-tiles checkout 26e127ba2117f45cdce5ea0225748cc0cfad3169

PYTHONPATH=vision/src python3 vision/scripts/riichi_synthetic.py generate \
  /tmp/riichi-tiles /tmp/dorahub-riichi-synthetic

yolo detect train \
  data=/tmp/dorahub-riichi-synthetic/data.yaml \
  model=yolo26n.pt epochs=20 warmup_epochs=1 imgsz=640 batch=8 device=cpu \
  cache=disk project=/tmp/dorahub-riichi-runs name=smoke \
  seed=42 deterministic=True
```

Before training, or for an imported dataset:

```bash
PYTHONPATH=vision/src python3 vision/scripts/riichi_synthetic.py check \
  /tmp/dorahub-riichi-synthetic
```

Do not commit generated images, private acceptance photos, or model weights.

## Single-class COCO import

The local `coco_mahjong` archive already contains COCO boxes. Convert every
category to the generic `tile` class while preserving its train/validation
split:

```bash
PYTHONPATH=. python3 vision/scripts/coco_tiles.py \
  /path/to/coco_mahjong /tmp/coco-mahjong-yolo
```

The importer clips the source's one-pixel out-of-bounds boxes and symlinks the
images instead of copying gigabytes. The audited local archive has 1,709 train
images with 11,181 boxes and 427 validation images with 3,352 boxes. Keep it
and derived weights outside Git until its source and redistribution license
are confirmed.

The 2026-07-30 single-class experiment combined that archive with the Haitaks
layout baseline and fine-tuned YOLO26n for three epochs at 640 px with
180-degree rotation and mosaic. On the combined 477-image validation split it
improved precision/recall/mAP50 from `0.633/0.546/0.592` to
`0.861/0.861/0.907`.

The artifact is not a product replacement: on the five frozen club photos it
returned `25/20/24/17/24` boxes, missed wall tiles and falsely detected the
round score counters. More epochs on the same domain cannot supply those
missing hard negatives. Label separate full-table club scenes containing
walls, counters, racks and paper before the next fine-tune.

## Latest local smoke

On 2026-07-29 the default generator produced 518 scenes and 8,034 boxes.
YOLO26n, 10 epochs at 320 px, reached synthetic validation precision `0.050`,
recall `0.302`, mAP50 `0.0207`, and mAP50-95 `0.0160`. At confidence `0.01`
it produced zero detections on the five private physical-table photos.

The artifact is rejected for product use. The next quality step is real,
session-separated, labeled hand crops; more synthetic epochs are not a
substitute.

## Audited mobile model smoke

The released LiteRT model audited in `UPSTREAM.md` can check real photos
without retraining:

```bash
python3 -m pip install -r vision/requirements-litert.txt
PYTHONPATH=vision/src python3 vision/scripts/riichi_litert.py \
  /path/to/mahjong_yolo.tflite /path/to/photos/* --nearest-hand
```

`--nearest-hand` compares the lower guided zone with three overlapping crops,
then keeps a coherent 10–18-tile hand. Sparse detections are rejected instead
of being reported as a hand. The regions are capture-protocol calibration
values, not automatic table-role inference.

For high-recall full-field localization without tile classes:

```bash
PYTHONPATH=vision/src python3 vision/scripts/riichi_litert.py \
  /path/to/mahjong_yolo.tflite /path/to/photos/* --all-tiles
```

`--all-tiles` runs 12 overlapping crops, merges duplicates and returns the
single label `tile`. It detects readable faces; backs, walls and stacked tiles
still require single-class real-photo labels and fine-tuning.

## Real-photo review gate

Create a review-only YOLO draft from the audited Kaggle v1 source and LiteRT
artifact:

```bash
PYTHONPATH=vision/src python3 vision/scripts/riichi_prelabel.py \
  /path/to/riichi-mobile-kaggle-v1 /path/to/riichi-review-draft \
  /path/to/mahjong_yolo.tflite
```

The draft symlinks images, writes canonical 37-class labels and keeps
confidence/top-3 in `predictions.jsonl`. It intentionally has no `data.yaml`:

1. Add every missing readable face and delete every false box.
2. Correct classes; ambiguous/blurred scenes are `exclude`, never guessed.
3. Set each `review.csv` row to `reviewed` or `exclude`.
4. Give every reviewed row a capture session. Do not randomly split adjacent
   photos or mix one physical session across train/validation.

The local 2026-07-29 draft contains 257 images and 7,126 proposed boxes across
37/37 classes. Kaggle files have no EXIF and 141 distinct resolutions, so
capture sessions cannot be recovered automatically.

## Local CVAT review

The review draft is loaded into pinned CVAT `v2.51.0` at
`http://localhost:8080/tasks/1`. The verified task has 257 frames, 9 jobs,
37 labels and 7,126 rectangles. Local credentials, source images, annotations
and CVAT volumes stay outside Git.

The frozen five-photo acceptance set is separate at
`http://localhost:8080/tasks/2`: 5 frames, 1 job and 37 labels. The current
strict hand-only pass rejects two sparse scenes and proposes 36 rectangles on
the other three. Correct these proposals before measuring exact-hand accuracy;
never use this task for training, threshold tuning or model selection.

Full-field generic localization is separate at `http://localhost:8080/tasks/3`:
5 frames, 1 label (`tile`) and 194 proposals. It is a high-recall review task,
not ground truth.
