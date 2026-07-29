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
