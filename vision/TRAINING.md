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

`--nearest-hand` uses the lower guided zone (`52%..88%` of image height), so
small physical tiles reach the model at roughly twice the full-frame scale.
The region is a capture-protocol calibration value, not automatic table-role
inference.

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
